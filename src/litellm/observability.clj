(ns litellm.observability
  "LLM inference observability via mulog structured events.

  This namespace emits structured mulog events for every LLM operation.
  Events tagged with `:litellm/kind :llm` carry OpenTelemetry GenAI semantic
  convention attributes (`gen_ai.*`) so that apps using the mulog-opentelemetry
  publisher can route them to Langfuse (or any OTLP-compatible backend) with
  zero extra instrumentation.

  The library itself only depends on mulog core; publishers are an **app concern**.
  Add `com.brunobonacci/mulog-opentelemetry` to your application and configure it
  to point at your Langfuse OTLP endpoint — see `doc/observability.md` for the
  full setup guide.

  ## Quick start

  ```clojure
  (require '[litellm.observability :as obs])
  (require '[litellm.core :as litellm])

  ;; Start a console publisher for development
  (require '[com.brunobonacci.mulog :as μ])
  (μ/start-publisher! {:type :console})

  ;; Wrap your agent logic — all completions become child spans
  (obs/with-observation {:session-id \"sess-123\" :user-id \"u-456\"}
    (litellm/completion :openai \"gpt-4o\" {:messages [...]} config))
  ```"
  (:require [com.brunobonacci.mulog :as μ]
            [cheshire.core :as json]
            [litellm.streaming :as streaming]))

;; ============================================================================
;; Configuration
;; ============================================================================

(defonce ^:private config
  (atom {:capture-content? false}))

(defn options
  "Returns the current observability options map."
  []
  @config)

(defn set-options!
  "Configure observability options.

  Options:
  - `:capture-content?` — When true, `gen_ai.input.messages` and
    `gen_ai.output.messages` are included in LLM events. Defaults to false
    (per OpenTelemetry GenAI spec recommendation: content capture is opt-in).

  Example:
  ```clojure
  (obs/set-options! {:capture-content? true})
  ```"
  [opts]
  (swap! config merge opts))

;; ============================================================================
;; Context helpers (synchronous and async flows)
;; ============================================================================

(defn capture-context
  "Snapshots the current mulog trace context as a plain map.

  Use this to propagate trace context across async boundaries (core.async
  go blocks, futures, thread pools). Restore with `com.brunobonacci.mulog/with-context`.

  Example — core.async:
  ```clojure
  (obs/with-observation {:session-id \"s-1\"}
    (let [ctx (obs/capture-context)]
      (async/go
        (μ/with-context ctx
          (<! (async/thread (litellm/completion ...)))))))
  ```

  Example — future:
  ```clojure
  (obs/with-observation {:session-id \"s-1\"}
    (let [ctx (obs/capture-context)]
      @(future (μ/with-context ctx (litellm/completion ...)))))
  ```"
  []
  (μ/local-context))

;; ============================================================================
;; User-facing trace wrapper
;; ============================================================================

(defmacro with-observation
  "Wraps body in a root mulog trace span.

  All `litellm/completion` and `litellm/embedding` calls inside become child
  spans automatically through mulog's thread-local context propagation.

  `attrs` map supports:
  - `:session-id` — maps to `langfuse.session_id`
  - `:user-id`    — maps to `langfuse.user_id`
  - `:name`       — human-readable label for the trace
  - any other keys are forwarded as-is into the mulog context

  For async flows (core.async go blocks, futures), call `(capture-context)`
  **before** the async boundary and restore with `μ/with-context` inside.

  Example:
  ```clojure
  (obs/with-observation {:session-id \"sess-123\" :user-id \"u-456\" :name \"weather-agent\"}
    (let [r1 (litellm/completion :openai \"gpt-4o\" {:messages [...] :tools [tool]} cfg)
          result (call-tool r1)
          r2 (litellm/completion :openai \"gpt-4o\" {:messages [...tool-msg...]} cfg)]
      r2))
  ```"
  [attrs & body]
  `(μ/with-context
     (merge (dissoc ~attrs :session-id :user-id :name)
            (cond-> {:litellm/kind :llm}
              (:session-id ~attrs) (assoc :langfuse.session_id (:session-id ~attrs))
              (:user-id ~attrs)    (assoc :langfuse.user_id    (:user-id ~attrs))
              (:name ~attrs)       (assoc :langfuse.trace.name (:name ~attrs))))
     (μ/trace ::litellm/observation
       [:litellm/kind :llm]
       ~@body)))

;; ============================================================================
;; Internal instrumentation — completion
;; ============================================================================

(defn ^:no-doc instrument-completion
  "Wraps a completion call in a `μ/trace` span with OpenTelemetry GenAI
  semantic-convention attributes.

  Both request and response attributes land on the **same** span: mulog
  evaluates the pairs vector *after* the body completes, so the `volatile!`
  capturing the response is guaranteed to be populated by the time pairs
  are read.

  `opts` keys:
  - `:capture-content?` — include message content in the event (default false)"
  [provider-name model request completion-fn opts]
  (let [resp (volatile! nil)]
    (μ/trace ::gen-ai/completion
      ;; Pairs evaluated after body — volatile is safe here (single-threaded)
      [:litellm/kind                  :llm
       :gen_ai.system                 (name provider-name)
       :gen_ai.operation.name         "chat"
       :gen_ai.request.model          model
       :gen_ai.request.max_tokens     (:max-tokens request)
       :gen_ai.request.temperature    (:temperature request)
       :gen_ai.request.top_p          (:top-p request)
       :gen_ai.response.id            (:id @resp)
       :gen_ai.response.model         (:model @resp)
       :gen_ai.usage.input_tokens     (-> @resp :usage :prompt-tokens)
       :gen_ai.usage.output_tokens    (-> @resp :usage :completion-tokens)
       :gen_ai.response.finish_reasons (mapv :finish-reason (:choices @resp))
       :gen_ai.input.messages         (when (:capture-content? opts)
                                        (json/encode (:messages request)))
       :gen_ai.output.messages        (when (:capture-content? opts)
                                        (some->> (:choices @resp)
                                                 (mapv :message)
                                                 json/encode))]
      (let [result (completion-fn)]
        (vreset! resp result)
        result))))

;; ============================================================================
;; Internal instrumentation — embedding
;; ============================================================================

(defn ^:no-doc instrument-embedding
  "Wraps an embedding call in a `μ/trace` span with GenAI attributes."
  [provider-name model request embedding-fn]
  (let [resp (volatile! nil)]
    (μ/trace ::gen-ai/embedding
      [:litellm/kind              :llm
       :gen_ai.system             (name provider-name)
       :gen_ai.operation.name     "embeddings"
       :gen_ai.request.model      model
       :gen_ai.usage.input_tokens (-> @resp :usage :prompt-tokens)
       :gen_ai.response.embedding_count (some-> @resp :data count)]
      (let [result (embedding-fn)]
        (vreset! resp result)
        result))))

;; ============================================================================
;; Streaming observability
;; ============================================================================

(defn observe-stream
  "Collects a streaming channel and emits a `::gen-ai/completion-stream` span.

  Because token counts are not available in streaming chunks, only
  `:gen_ai.output.content` is captured on the response side. Use non-streaming
  completions if precise token accounting in traces is required.

  Returns the collected result map: `{:content str :chunks [...] :error ...}`.

  Example:
  ```clojure
  (let [ch (litellm/completion :openai \"gpt-4o\"
                               {:messages [...] :stream true}
                               config)]
    (obs/observe-stream ch :openai \"gpt-4o\" {:messages [...]}))
  ```"
  [ch provider-name model request]
  (let [resp (volatile! nil)]
    (μ/trace ::gen-ai/completion-stream
      [:litellm/kind          :llm
       :gen_ai.system         (name provider-name)
       :gen_ai.operation.name "chat"
       :gen_ai.request.model  model
       :gen_ai.request.max_tokens  (:max-tokens request)
       :gen_ai.request.temperature (:temperature request)
       :gen_ai.output.content      (:content @resp)]
      (let [result (streaming/collect-stream ch)]
        (vreset! resp result)
        result))))
