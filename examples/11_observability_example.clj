;; Example 11: LLM Inference Observability with mulog
;;
;; This example shows how to add observability to your LLM application using
;; litellm-clj's built-in mulog instrumentation.
;;
;; Prerequisites:
;;   In your app's deps.edn, add:
;;     com.brunobonacci/mulog {:mvn/version "0.10.1"}
;;     com.brunobonacci/mulog-opentelemetry {:mvn/version "0.10.1"}  ; for Langfuse
;;
;; Usage:
;;   clojure -M:dev -i examples/11_observability_example.clj

(require '[com.brunobonacci.mulog :as mu])
(require '[litellm.observability :as obs])
(require '[litellm.core :as litellm])

;; ============================================================================
;; 1. Start a publisher
;; ============================================================================

;; For development: print all events to the console
(def stop-console-publisher!
  (mu/start-publisher! {:type :console}))

(println "Console publisher started. Events will print below.\n")

;; ============================================================================
;; 2. Basic synchronous trace
;; ============================================================================

(println "=== Example 1: Basic synchronous trace ===\n")

;; All litellm/completion calls inside with-observation become child spans.
;; session-id and user-id propagate to every child event automatically.
(obs/with-observation {:session-id "sess-demo-1"
                       :user-id    "user-42"
                       :name       "basic-example"}
  ;; This completion will emit a :gen-ai/completion event with:
  ;;   gen_ai.system = "openai", gen_ai.request.model = "gpt-4o-mini"
  ;;   gen_ai.usage.input_tokens, gen_ai.usage.output_tokens
  ;;   mulog/parent-trace → the observation span
  (when (System/getenv "OPENAI_API_KEY")
    (let [response (litellm/completion
                    :openai "gpt-4o-mini"
                    {:messages   [{:role :user :content "Say 'observability works' and nothing else."}]
                     :max-tokens 10}
                    {:api-key (System/getenv "OPENAI_API_KEY")})]
      (println "Response:" (get-in response [:choices 0 :message :content])))))

(when-not (System/getenv "OPENAI_API_KEY")
  (println "(skipped — OPENAI_API_KEY not set)"))

;; ============================================================================
;; 3. Tool-call flow — two completions linked in one trace
;; ============================================================================

(println "\n=== Example 2: Tool-call flow ===\n")

(defn- mock-weather-tool-call
  "Pretend we ran a tool and got a result."
  [_response]
  "22°C, sunny")

(obs/with-observation {:session-id "sess-demo-2"
                       :name       "weather-agent"
                       :user-id    "user-42"}
  (when (System/getenv "OPENAI_API_KEY")
    (let [weather-tool {:type     "function"
                        :function {:name        "get_weather"
                                   :description "Get current weather"
                                   :parameters  {:type       "object"
                                                 :properties {:location {:type "string"}}
                                                 :required   ["location"]}}}

          ;; First completion — model decides to call a tool
          r1           (litellm/completion
                        :openai "gpt-4o-mini"
                        {:messages   [{:role :user :content "What's the weather in Paris?"}]
                         :tools      [weather-tool]
                         :max-tokens 100}
                        {:api-key (System/getenv "OPENAI_API_KEY")})

          tool-result  (mock-weather-tool-call r1)

          ;; Second completion — model responds with tool result in context
          r2           (litellm/completion
                        :openai "gpt-4o-mini"
                        {:messages   [{:role :user :content "What's the weather in Paris?"}
                                      (get-in r1 [:choices 0 :message])
                                      {:role         :tool
                                       :tool-call-id (get-in r1 [:choices 0 :message :tool-calls 0 :id] "call_demo")
                                       :content      tool-result}]
                         :max-tokens 100}
                        {:api-key (System/getenv "OPENAI_API_KEY")})]

      ;; In Langfuse these two completions appear as siblings under the observation trace:
      ;; Trace: weather-agent (sess-demo-2)
      ;;   └── Span: gen-ai/completion  [finish=tool_calls]
      ;;   └── Span: gen-ai/completion  [finish=stop]
      (println "Final answer:" (get-in r2 [:choices 0 :message :content])))))

(when-not (System/getenv "OPENAI_API_KEY")
  (println "(skipped — OPENAI_API_KEY not set)"))

;; ============================================================================
;; 4. Async flow — propagate context across a future boundary
;; ============================================================================

(println "\n=== Example 3: Async context propagation ===\n")

(obs/with-observation {:session-id "sess-async" :name "async-demo"}
  ;; Capture the current mulog context before crossing the async boundary
  (let [ctx (obs/capture-context)]
    (when (System/getenv "OPENAI_API_KEY")
      @(future
         ;; Restore context inside the future — the completion span will be a
         ;; child of the observation span just as if it ran synchronously.
         (mu/with-context ctx
           (let [resp (litellm/completion
                       :openai "gpt-4o-mini"
                       {:messages   [{:role :user :content "Reply with: async works"}]
                        :max-tokens 10}
                       {:api-key (System/getenv "OPENAI_API_KEY")})]
             (println "Async response:" (get-in resp [:choices 0 :message :content]))))))))

(when-not (System/getenv "OPENAI_API_KEY")
  (println "(skipped — OPENAI_API_KEY not set)"))

;; ============================================================================
;; 5. Streaming with observability
;; ============================================================================

(println "\n=== Example 4: Streaming with observe-stream ===\n")

(obs/with-observation {:session-id "sess-stream" :name "stream-demo"}
  (when (System/getenv "OPENAI_API_KEY")
    (let [ch (litellm/completion
              :openai "gpt-4o-mini"
              {:messages   [{:role :user :content "Count to 3, one word per line."}]
               :stream     true
               :max-tokens 20}
              {:api-key (System/getenv "OPENAI_API_KEY")})]
      ;; observe-stream collects the channel and emits a :gen-ai/completion-stream span
      (let [{:keys [content]} (obs/observe-stream ch :openai "gpt-4o-mini"
                                                  {:messages [{:role :user :content "Count to 3"}]})]
        (println "Streamed content:" content)))))

(when-not (System/getenv "OPENAI_API_KEY")
  (println "(skipped — OPENAI_API_KEY not set)"))

;; ============================================================================
;; 6. Langfuse setup (production)
;; ============================================================================

(println "\n=== Langfuse setup snippet (not executed) ===\n")

(println "
;; In production, replace the console publisher with the OTel publisher:
;;
;; (require '[com.brunobonacci.mulog :as mu])
;; (require '[litellm.observability :as obs])
;;
;; (def stop!
;;   (mu/start-publisher!
;;     {:type :multi
;;      :publishers
;;      [{:type    :opentelemetry
;;        :url     \"https://cloud.langfuse.com/api/public/otel\"  ; or US/self-hosted URL
;;        :headers {\"Authorization\"
;;                  (str \"Basic \"
;;                       (-> (str \"pk-lf-YOUR_KEY:sk-lf-YOUR_SECRET\")
;;                           (.getBytes \"UTF-8\")
;;                           java.util.Base64/getEncoder
;;                           .encodeToString))}
;;        ;; Route only LLM inference events to Langfuse
;;        :filter-fn #(= :llm (:litellm/kind %))}
;;       {:type :console}]}))
;;
;; (obs/set-options! {:capture-content? true})  ; opt-in to log message content
")

;; ============================================================================
;; Clean up
;; ============================================================================

(stop-console-publisher!)
(println "\nDone. Publisher stopped.")
