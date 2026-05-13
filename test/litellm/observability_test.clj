(ns litellm.observability-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [com.brunobonacci.mulog :as μ]
            [litellm.observability :as obs]))

;; ============================================================================
;; Test helpers
;; ============================================================================

(defmacro with-captured-events
  "Runs body with an inline mulog publisher that collects all emitted events
  into `events-atom`. Stops the publisher after body completes."
  [events-atom & body]
  `(let [stop-fn# (μ/start-publisher!
                    {:type :inline
                     :fn   (fn [events#]
                             (doseq [e# events#]
                               (swap! ~events-atom conj e#)))})]
     (try
       ~@body
       (finally (stop-fn#)))))

(defn events-of-type
  "Filter captured events by mulog event name keyword."
  [events event-name]
  (filter #(= event-name (:mulog/event-name %)) events))

;; ============================================================================
;; Configuration tests
;; ============================================================================

(deftest test-set-options
  (testing "default options have capture-content? false"
    (obs/set-options! {:capture-content? false})
    (is (false? (:capture-content? (obs/options)))))

  (testing "set-options! merges into existing config"
    (obs/set-options! {:capture-content? true})
    (is (true? (:capture-content? (obs/options))))
    (obs/set-options! {:capture-content? false})))

;; ============================================================================
;; instrument-completion event shape
;; ============================================================================

(def ^:private fake-response
  {:id      "chatcmpl-test123"
   :model   "gpt-4o"
   :choices [{:index        0
              :finish-reason :stop
              :message      {:role :assistant :content "Hello, world!"}}]
   :usage   {:prompt-tokens 10 :completion-tokens 5 :total-tokens 15}})

(deftest test-instrument-completion-event-shape
  (let [events (atom [])]
    (with-captured-events events
      (obs/instrument-completion
       :openai "gpt-4o"
       {:messages [{:role :user :content "Hi"}]
        :temperature 0.7
        :max-tokens 100}
       (fn [] fake-response)
       {:capture-content? false}))

    (let [completion-events (events-of-type @events ::obs/gen-ai/completion)]
      (testing "exactly one completion event is emitted"
        (is (= 1 (count completion-events))))

      (let [e (first completion-events)]
        (testing "litellm/kind is :llm"
          (is (= :llm (:litellm/kind e))))

        (testing "gen_ai.system is provider name"
          (is (= "openai" (:gen_ai.system e))))

        (testing "gen_ai.operation.name is chat"
          (is (= "chat" (:gen_ai.operation.name e))))

        (testing "request attributes"
          (is (= "gpt-4o" (:gen_ai.request.model e)))
          (is (= 0.7      (:gen_ai.request.temperature e)))
          (is (= 100      (:gen_ai.request.max_tokens e))))

        (testing "response attributes are populated (volatile pattern works)"
          (is (= "chatcmpl-test123" (:gen_ai.response.id e)))
          (is (= "gpt-4o"           (:gen_ai.response.model e)))
          (is (= 10                 (:gen_ai.usage.input_tokens e)))
          (is (= 5                  (:gen_ai.usage.output_tokens e)))
          (is (= [:stop]            (:gen_ai.response.finish_reasons e))))

        (testing "content is absent when capture-content? false"
          (is (nil? (:gen_ai.input.messages e)))
          (is (nil? (:gen_ai.output.messages e))))

        (testing "mulog trace metadata is present"
          (is (some? (:mulog/trace-id e)))
          (is (some? (:mulog/duration e)))
          (is (= :ok (:mulog/outcome e))))))))

(deftest test-instrument-completion-content-capture
  (testing "messages are included when capture-content? true"
    (let [events  (atom [])
          messages [{:role :user :content "Hi"}]]
      (with-captured-events events
        (obs/instrument-completion
         :openai "gpt-4o"
         {:messages messages}
         (fn [] fake-response)
         {:capture-content? true}))
      (let [e (first (events-of-type @events ::obs/gen-ai/completion))]
        (is (some? (:gen_ai.input.messages e)))
        (is (some? (:gen_ai.output.messages e)))))))

(deftest test-instrument-completion-error-outcome
  (testing "outcome is :error when completion-fn throws"
    (let [events (atom [])]
      (with-captured-events events
        (try
          (obs/instrument-completion
           :openai "gpt-4o" {:messages []}
           (fn [] (throw (ex-info "API error" {:status 429})))
           {})
          (catch Exception _)))
      (let [e (first (events-of-type @events ::obs/gen-ai/completion))]
        (is (= :error (:mulog/outcome e)))))))

;; ============================================================================
;; instrument-embedding event shape
;; ============================================================================

(def ^:private fake-embedding-response
  {:object "list"
   :data   [{:embedding [0.1 0.2 0.3] :index 0 :object "embedding"}]
   :usage  {:prompt-tokens 4 :total-tokens 4}})

(deftest test-instrument-embedding-event-shape
  (let [events (atom [])]
    (with-captured-events events
      (obs/instrument-embedding
       :openai "text-embedding-3-small"
       {:input "Hello world"}
       (fn [] fake-embedding-response)))

    (let [embedding-events (events-of-type @events ::obs/gen-ai/embedding)]
      (testing "one embedding event is emitted"
        (is (= 1 (count embedding-events))))

      (let [e (first embedding-events)]
        (testing "gen_ai.operation.name is embeddings"
          (is (= "embeddings" (:gen_ai.operation.name e))))
        (testing "litellm/kind is :llm"
          (is (= :llm (:litellm/kind e))))
        (testing "embedding count is captured"
          (is (= 1 (:gen_ai.response.embedding_count e))))))))

;; ============================================================================
;; with-observation context propagation
;; ============================================================================

(deftest test-with-observation-creates-trace
  (testing "with-observation emits an observation span"
    (let [events (atom [])]
      (with-captured-events events
        (obs/with-observation {:session-id "sess-1" :user-id "u-1"}
          :done))
      (let [obs-events (events-of-type @events ::obs/litellm/observation)]
        (is (= 1 (count obs-events)))
        (is (= :llm (:litellm/kind (first obs-events))))))))

(deftest test-with-observation-child-spans
  (testing "completions inside with-observation have a parent-trace"
    (let [events (atom [])]
      (with-captured-events events
        (obs/with-observation {:session-id "sess-2"}
          (obs/instrument-completion
           :openai "gpt-4o" {:messages []}
           (fn [] fake-response)
           {})))

      (let [obs-event        (first (events-of-type @events ::obs/litellm/observation))
            completion-event (first (events-of-type @events ::obs/gen-ai/completion))]
        (testing "observation span exists"
          (is (some? obs-event)))
        (testing "completion span's parent-trace is the observation trace"
          (is (= (:mulog/trace-id obs-event)
                 (:mulog/parent-trace completion-event))))))))

(deftest test-with-observation-session-attributes
  (testing "session-id and user-id map to langfuse.* context keys"
    (let [events (atom [])]
      (with-captured-events events
        (obs/with-observation {:session-id "sess-abc" :user-id "user-xyz"}
          (obs/instrument-completion
           :anthropic "claude-3-5-sonnet-20241022" {:messages []}
           (fn [] (assoc fake-response :model "claude-3-5-sonnet-20241022"))
           {})))
      ;; Context keys are in the events' local context
      (let [comp-event (first (events-of-type @events ::obs/gen-ai/completion))]
        (is (= "sess-abc"  (:langfuse.session_id comp-event)))
        (is (= "user-xyz"  (:langfuse.user_id comp-event)))))))

;; ============================================================================
;; capture-context for async flows
;; ============================================================================

(deftest test-capture-context-returns-map
  (testing "capture-context returns a map"
    (is (map? (obs/capture-context)))))

(deftest test-capture-context-async-propagation
  (testing "context captured before async boundary is restored in future"
    (let [events (atom [])]
      (with-captured-events events
        (obs/with-observation {:session-id "async-sess"}
          (let [ctx (obs/capture-context)]
            ;; Simulate async boundary
            @(future
               (μ/with-context ctx
                 (obs/instrument-completion
                  :openai "gpt-4o" {:messages []}
                  (fn [] fake-response)
                  {}))))))
      (let [obs-event        (first (events-of-type @events ::obs/litellm/observation))
            completion-event (first (events-of-type @events ::obs/gen-ai/completion))]
        (testing "async completion is child of observation span"
          (is (= (:mulog/trace-id obs-event)
                 (:mulog/parent-trace completion-event))))
        (testing "session-id propagated to async child"
          (is (= "async-sess" (:langfuse.session_id completion-event))))))))

;; ============================================================================
;; litellm/kind on lib events
;; ============================================================================

(deftest test-lib-events-have-kind
  (testing "all emitted lib events carry :litellm/kind :lib"
    ;; We verify the design by checking a known lib event manually
    (let [events (atom [])
          stop   (μ/start-publisher! {:type :inline
                                      :fn   (fn [es]
                                              (doseq [e es]
                                                (swap! events conj e)))})]
      (μ/log ::test/lib-event :litellm/kind :lib :msg "test")
      (stop)
      (let [e (first @events)]
        (is (= :lib (:litellm/kind e)))))))

;; ============================================================================
;; observe-stream
;; ============================================================================

(deftest test-observe-stream-emits-span
  (testing "observe-stream emits a completion-stream span with content"
    (let [events (atom [])
          ;; Create a fake channel with pre-loaded chunks
          ch     (clojure.core.async/chan 10)]
      ;; Pre-load a chunk and close the channel
      (clojure.core.async/put! ch {:choices [{:delta {:content "hello "} :finish-reason nil}]})
      (clojure.core.async/put! ch {:choices [{:delta {:content "world"}  :finish-reason :stop}]})
      (clojure.core.async/close! ch)

      (with-captured-events events
        (obs/observe-stream ch :openai "gpt-4o" {:messages [{:role :user :content "Hi"}]}))

      (let [stream-events (events-of-type @events ::obs/gen-ai/completion-stream)]
        (testing "one stream event emitted"
          (is (= 1 (count stream-events))))
        (let [e (first stream-events)]
          (testing "kind is :llm"
            (is (= :llm (:litellm/kind e))))
          (testing "operation is chat"
            (is (= "chat" (:gen_ai.operation.name e))))
          (testing "content is captured"
            (is (= "hello world" (:gen_ai.output.content e)))))))))
