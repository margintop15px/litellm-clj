# LLM Inference Observability

litellm-clj emits structured [mulog](https://github.com/BrunoBonacci/mulog) events for every LLM
operation. Events carry [OpenTelemetry GenAI semantic convention](https://opentelemetry.io/docs/specs/semconv/gen-ai/)
attributes (`gen_ai.*`) so that any mulog publisher can consume them.

The library only depends on **mulog core**. Which publisher(s) to use — console output, OpenTelemetry
/ Langfuse, Elasticsearch, etc. — is an **application concern**.

---

## Architecture

```
litellm-clj                    Your app                  Backend
─────────────────────────      ───────────────────────   ─────────────
litellm/completion          →  mulog publisher(s)    →  Langfuse OTLP
  emits :gen-ai/completion       :opentelemetry           /api/public/otel
  with gen_ai.* attributes        :console
  :litellm/kind :llm              :elasticsearch
```

All LLM events carry `:litellm/kind :llm`. Internal library events (errors, health
checks, retries) carry `:litellm/kind :lib`. This lets you route them to different
publishers or filter them independently.

---

## Quick Start

### 1. Add mulog to your app

In your application's `deps.edn` (not the library):

```clojure
{:deps {com.brunobonacci/mulog {:mvn/version "0.10.1"}
        ;; Add the OTel publisher to forward to Langfuse:
        com.brunobonacci/mulog-opentelemetry {:mvn/version "0.10.1"}}}
```

### 2. Configure and start a publisher

```clojure
(require '[com.brunobonacci.mulog :as mu])
(require '[litellm.observability :as obs])

;; Development: print all events to console
(def stop-publisher! (mu/start-publisher! {:type :console}))

;; Production: forward LLM events to Langfuse via OpenTelemetry
(def stop-publisher!
  (mu/start-publisher!
    {:type :multi
     :publishers
     [{:type    :opentelemetry
       :url     "https://cloud.langfuse.com/api/public/otel"
       :headers {"Authorization"
                 (str "Basic "
                      (-> (str "pk-lf-YOUR_KEY:sk-lf-YOUR_SECRET")
                          (.getBytes "UTF-8")
                          java.util.Base64/getEncoder
                          (.encodeToString  )))}
       ;; Only route LLM inference events to Langfuse
       :filter-fn #(= :llm (:litellm/kind %))}
      ;; All events (lib + llm) to console for debugging
      {:type :console}]}))

;; Shut down cleanly at app exit:
;; (stop-publisher!)
```

### 3. Optionally enable content capture

By default message content is **not** captured (per the OTel GenAI spec recommendation).
Enable it when you need to see prompts and responses in Langfuse:

```clojure
(obs/set-options! {:capture-content? true})
```

---

## Tracing Agent Flows

### Synchronous flows

Wrap your agent loop in `with-observation`. Every `litellm/completion` or
`litellm/embedding` call inside becomes a **child span** automatically.

```clojure
(require '[litellm.observability :as obs])
(require '[litellm.core :as litellm])

(obs/with-observation
  {:session-id "sess-abc"   ; → langfuse.session_id
   :user-id    "user-456"   ; → langfuse.user_id
   :name       "weather-agent"}
  (let [r1 (litellm/completion :openai "gpt-4o"
              {:messages [{:role :user :content "What's the weather in Paris?"}]
               :tools    [weather-tool]}
              {:api-key (System/getenv "OPENAI_API_KEY")})
        tool-result (call-weather-tool r1)
        r2 (litellm/completion :openai "gpt-4o"
              {:messages [{:role :user    :content "What's the weather in Paris?"}
                          (get-in r1 [:choices 0 :message])
                          {:role :tool
                           :tool-call-id (get-in r1 [:choices 0 :message :tool-calls 0 :id])
                           :content      (str tool-result)}]}
              {:api-key (System/getenv "OPENAI_API_KEY")})]
    (get-in r2 [:choices 0 :message :content])))
```

In Langfuse this renders as:

```
Trace: litellm/observation  [session=sess-abc, user=user-456]
  └── Span: gen-ai/completion  [model=gpt-4o, in=18tok, out=12tok, finish=tool_calls]
  └── Span: gen-ai/completion  [model=gpt-4o, in=42tok, out=95tok, finish=stop]
```

### Async flows (core.async / futures)

mulog context is thread-local. Cross async boundaries by capturing and restoring it:

```clojure
(require '[com.brunobonacci.mulog :as mu])

(obs/with-observation {:session-id "async-sess"}
  (let [ctx (obs/capture-context)]          ; snapshot before async boundary
    ;; In a core.async go block:
    (async/go
      (mu/with-context ctx                   ; restore context
        (<! (async/thread
              (litellm/completion :openai "gpt-4o" {:messages [...]} config)))))
    ;; Or in a future:
    @(future
       (mu/with-context ctx
         (litellm/completion :openai "gpt-4o" {:messages [...]} config)))))
```

---

## Streaming Observability

Streaming completions return a channel immediately; full token counts are unavailable
until the stream is consumed. Use `observe-stream` to collect the stream and emit a
trace span:

```clojure
(require '[litellm.observability :as obs])

(let [ch (litellm/completion :openai "gpt-4o"
                             {:messages [{:role :user :content "Tell me a story"}]
                              :stream   true}
                             {:api-key (System/getenv "OPENAI_API_KEY")})]
  ;; Collects all chunks, emits a :gen-ai/completion-stream span
  (obs/observe-stream ch :openai "gpt-4o" {:messages [...]}))
;; => {:content "Once upon a time..." :chunks [...] :error nil}
```

> **Note:** Token counts in the stream span are not available (streaming providers
> do not return usage in chunks). If precise token accounting in traces is required,
> use non-streaming completions.

---

## Event Reference

### `:gen-ai/completion`

Emitted once per non-streaming `litellm/completion` call.

| Key | Type | Description |
|-----|------|-------------|
| `:litellm/kind` | `:llm` | Marks this as an LLM inference event |
| `:gen_ai.system` | string | Provider name, e.g. `"openai"`, `"anthropic"` |
| `:gen_ai.operation.name` | `"chat"` | Operation type |
| `:gen_ai.request.model` | string | Model requested |
| `:gen_ai.request.max_tokens` | int? | Max tokens parameter |
| `:gen_ai.request.temperature` | float? | Temperature parameter |
| `:gen_ai.request.top_p` | float? | Top-p parameter |
| `:gen_ai.response.id` | string | Response ID from provider |
| `:gen_ai.response.model` | string | Actual model that responded |
| `:gen_ai.usage.input_tokens` | int | Prompt token count |
| `:gen_ai.usage.output_tokens` | int | Completion token count |
| `:gen_ai.response.finish_reasons` | vec | e.g. `[:stop]`, `[:tool_calls]` |
| `:gen_ai.input.messages` | JSON string? | Input messages (opt-in, see `set-options!`) |
| `:gen_ai.output.messages` | JSON string? | Output messages (opt-in) |
| `:mulog/duration` | nanoseconds | Call duration (auto) |
| `:mulog/outcome` | `:ok`/`:error` | Outcome (auto) |
| `:mulog/trace-id` | uuid | Unique span ID (auto) |
| `:mulog/parent-trace` | uuid? | Parent span ID when nested inside `with-observation` |
| `:langfuse.session_id` | string? | From `with-observation` `:session-id` |
| `:langfuse.user_id` | string? | From `with-observation` `:user-id` |

### `:gen-ai/embedding`

Emitted once per `litellm/embedding` call.

| Key | Value |
|-----|-------|
| `:gen_ai.operation.name` | `"embeddings"` |
| `:gen_ai.usage.input_tokens` | int |
| `:gen_ai.response.embedding_count` | int |

### `:gen-ai/completion-stream`

Emitted by `observe-stream` after the stream is fully consumed.

| Key | Value |
|-----|-------|
| `:gen_ai.output.content` | Accumulated text |

### `:litellm/observation`

Emitted by `with-observation` as the root trace span.

---

## Filtering Events by Kind

| `:litellm/kind` | What | Example use |
|-----------------|------|-------------|
| `:llm` | LLM inference spans (`completion`, `embedding`, `observation`) | Route to Langfuse |
| `:lib` | Internal library events (errors, health checks, retries) | Route to operational monitoring |

```clojure
;; Only LLM events → Langfuse
{:filter-fn #(= :llm (:litellm/kind %))}

;; All litellm events → ELK
{:filter-fn #(#{:llm :lib} (:litellm/kind %))}
```

---

## Langfuse Setup

1. Create a project in [Langfuse](https://cloud.langfuse.com) and copy your API keys.

2. Add to your app's `deps.edn`:
   ```clojure
   com.brunobonacci/mulog-opentelemetry {:mvn/version "0.10.1"}
   ```

3. Configure the publisher (see [Quick Start](#quick-start) above).

4. Use `with-observation` to group related completions into a single trace.

5. In Langfuse's UI, navigate to **Traces** — you should see your agent flows with
   individual generation spans, token counts, and latency.

**Regional OTLP endpoints:**
- EU: `https://cloud.langfuse.com/api/public/otel`
- US: `https://us.cloud.langfuse.com/api/public/otel`
- Self-hosted: `http://localhost:3000/api/public/otel`
