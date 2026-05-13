;; Example 12: Fire a handful of traces at Langfuse via mulog-opentelemetry.
;;
;; This is a smoke test for the observability pipeline. It does NOT call any
;; LLM provider — it stubs the response so you can verify *only* that events
;; land in Langfuse with the right shape.
;;
;; ---------------------------------------------------------------------------
;; Prerequisites
;; ---------------------------------------------------------------------------
;;   export LANGFUSE_PUBLIC_KEY=pk-lf-...
;;   export LANGFUSE_SECRET_KEY=sk-lf-...
;;   export LANGFUSE_HOST=https://cloud.langfuse.com   ;; optional, this is the default
;;
;;   `mulog-opentelemetry` is an *app* dependency; the library doesn't pull it in.
;;   Start a REPL with it on the classpath:
;;
;;     clojure -Sdeps '{:deps {com.brunobonacci/mulog-opentelemetry {:mvn/version "0.10.1"}}}' \
;;             -M:repl
;;
;;   Then in the REPL:
;;
;;     (load-file "examples/12_langfuse_smoke.clj")
;;
;; ---------------------------------------------------------------------------

(require '[com.brunobonacci.mulog :as mu]
         '[litellm.observability :as obs])

(import 'java.util.Base64)

;; ---------------------------------------------------------------------------
;; Read env + build the OTLP endpoint and Basic auth header
;; ---------------------------------------------------------------------------

(def public-key
  (or (System/getenv "LANGFUSE_PUBLIC_KEY")
      (throw (ex-info "LANGFUSE_PUBLIC_KEY is not set" {}))))

(def secret-key
  (or (System/getenv "LANGFUSE_SECRET_KEY")
      (throw (ex-info "LANGFUSE_SECRET_KEY is not set" {}))))

(def host
  (or (System/getenv "LANGFUSE_HOST") "https://cloud.langfuse.com"))

(def otel-url (str host "/api/public/otel/v1/traces"))

(def basic-auth
  (str "Basic "
       (.encodeToString (Base64/getEncoder)
                        (.getBytes (str public-key ":" secret-key) "UTF-8"))))

;; ---------------------------------------------------------------------------
;; Start the publisher
;; ---------------------------------------------------------------------------

(println "Posting OTLP traces to" otel-url)

;; If a previous (load-file ...) left a publisher registered (e.g. an earlier
;; failure mid-script), drop it so we start clean.
(mu/stop-all-publishers!)

(def stop-publisher!
  (mu/start-publisher!
   {:type :multi
    :publishers
    [;; Route LLM events to Langfuse via OTel. `:send :traces` is required by
     ;; mulog-opentelemetry (the same publisher type is also used for `:logs`).
     {:type      :open-telemetry
      :send      :traces
      :url       otel-url
      :http-opts {:headers {"Authorization" basic-auth}}}
     ;; Mirror everything to stdout so we can sanity-check what was sent
     {:type :console :pretty? true}]}))

;; Capture message content in the events (off by default per OTel GenAI spec)
(obs/set-options! {:capture-content? true})

;; ---------------------------------------------------------------------------
;; Synthetic responses — no LLM API calls happen
;; ---------------------------------------------------------------------------

(def ^:private fake-completion
  {:id      "smoke-completion-1"
   :model   "gpt-4o-mini"
   :choices [{:index         0
              :finish-reason :stop
              :message       {:role :assistant :content "Hello from the smoke test."}}]
   :usage   {:prompt-tokens 12 :completion-tokens 7 :total-tokens 19}})

(def ^:private fake-tool-call
  (assoc fake-completion
         :id "smoke-completion-2"
         :choices [{:index         0
                    :finish-reason :tool_calls
                    :message       {:role       :assistant
                                    :content    nil
                                    :tool-calls [{:id       "call_abc"
                                                  :type     "function"
                                                  :function {:name      "get_weather"
                                                             :arguments "{\"city\":\"Paris\"}"}}]}}]))

;; ---------------------------------------------------------------------------
;; Fire a few traces
;; ---------------------------------------------------------------------------

(println "\n=== 1. Single completion under an observation ===")

(obs/with-observation {:session-id "smoke-sess-1"
                       :user-id    "smoke-user"
                       :name       "single-completion"}
  (obs/instrument-completion
   :openai "gpt-4o-mini"
   {:messages    [{:role :user :content "say hi"}]
    :temperature 0.2
    :max-tokens  20}
   (fn [] fake-completion)
   (obs/options)))

(println "=== 2. Two-turn tool-call flow (both completions share a parent trace) ===")

(obs/with-observation {:session-id "smoke-sess-2"
                       :user-id    "smoke-user"
                       :name       "weather-agent"}
  (obs/instrument-completion
   :openai "gpt-4o-mini"
   {:messages [{:role :user :content "weather in Paris?"}]
    :tools    [{:type "function" :function {:name "get_weather"}}]}
   (fn [] fake-tool-call)
   (obs/options))
  (obs/instrument-completion
   :openai "gpt-4o-mini"
   {:messages [{:role :user :content "weather in Paris?"}
               {:role :tool :tool-call-id "call_abc" :content "22C sunny"}]}
   (fn [] fake-completion)
   (obs/options)))

(println "=== 3. Async completion via future + capture-context ===")

(obs/with-observation {:session-id "smoke-sess-3" :name "async-completion"}
  (let [ctx (obs/capture-context)]
    @(future
       (mu/with-context ctx
         (obs/instrument-completion
          :anthropic "claude-3-5-sonnet-20241022"
          {:messages [{:role :user :content "async hello"}]}
          (fn [] (assoc fake-completion :model "claude-3-5-sonnet-20241022"))
          (obs/options))))))

(println "=== 4. An :error-outcome span (instrument-completion that throws) ===")

(obs/with-observation {:session-id "smoke-sess-4" :name "error-path"}
  (try
    (obs/instrument-completion
     :openai "gpt-4o-mini"
     {:messages [{:role :user :content "boom"}]}
     (fn [] (throw (ex-info "synthetic 429" {:status 429})))
     (obs/options))
    (catch Exception _)))

;; ---------------------------------------------------------------------------
;; Drain & stop. The OTel publisher's default :publish-delay is 5000 ms, so we
;; wait one full cycle plus a margin for the HTTP round-trip. Bump this if
;; events don't appear in Langfuse.
;; ---------------------------------------------------------------------------

(println "\nWaiting 10s for the publisher to flush...")
(Thread/sleep 10000)

(stop-publisher!)
(println "Done. Open Langfuse → Tracing and look for sessions smoke-sess-1 .. smoke-sess-4.")
