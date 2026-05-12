# JSON Output & Structured Output

litellm-clj supports structured/JSON output across all providers through a single `:response-format` key. You can pass a raw JSON Schema, or a **Malli schema** — the library converts it automatically and validates the response for you.

## Quick Start

```clojure
(require '[litellm.core :as llm])

;; Malli schema as output format (recommended)
(def response
  (llm/completion :openai "gpt-4o-mini"
    {:messages [{:role :user :content "Give me a person record."}]
     :response-format {:type   :malli
                       :schema [:map [:name :string] [:age :int] [:city :string]]}}
    {:api-key (System/getenv "OPENAI_API_KEY")}))

;; The decoded, keyword-keyed Clojure map is at :parsed-output
(-> response :choices first :message :parsed-output)
;; => {:name "Alice", :age 30, :city "Paris"}
```

## Response Format Types

### `:json-object` — free-form JSON

The model returns valid JSON in any shape it chooses. No schema enforcement.

```clojure
{:response-format {:type :json-object}}
```

Provider mapping:
- OpenAI / Azure / OpenRouter / Mistral → `response_format: {type: "json_object"}`
- Anthropic → system prompt injection (no native equivalent)
- Gemini → `generationConfig.responseMimeType: "application/json"`
- Ollama → `format: "json"`

### `:json-schema` — raw JSON Schema

Provide a JSON Schema map directly. You are responsible for parsing and validating the response.

```clojure
{:response-format {:type :json-schema
                   :json-schema {:name   "person"
                                 :schema {:type "object"
                                          :properties {:name {:type "string"}
                                                       :age  {:type "integer"}}
                                          :required ["name" "age"]
                                          :additionalProperties false}
                                 :strict true}}}
```

Provider mapping:
- OpenAI / Azure / OpenRouter / Mistral → `response_format: {type: "json_schema", json_schema: {...}}`
- Anthropic → `output_config: {format: {type: "json_schema", schema: {...}}}` (native GA)
- Gemini → `generationConfig.responseMimeType` + `responseSchema`
- Ollama → `format: <schema-map>`

### `:malli` — Malli schema (recommended)

Pass any Malli schema. The library:
1. Converts it to JSON Schema via `malli.json-schema/transform` and sends it to the provider.
2. After the response, parses the JSON content, decodes it with `json-transformer` (keyword keys, type coercion), and validates against your schema.
3. Adds the decoded value at `[:choices 0 :message :parsed-output]`.

```clojure
{:response-format {:type   :malli
                   :schema [:map
                            [:name    :string]
                            [:age     :int]
                            [:tags    [:vector :string]]
                            [:active  :boolean]]}}
```

The `:malli` type is normalised to `:json-schema` before provider dispatch — all providers work identically.

#### Accessing the decoded output

```clojure
(let [response (llm/completion :openai "gpt-4o-mini" request config)
      parsed   (-> response :choices first :message :parsed-output)]
  (:name parsed)   ; => "Alice"
  (:age parsed)    ; => 30  (integer, not string)
  (:tags parsed))  ; => ["clojure" "lisp"]
```

#### Validation failure

If the model's response doesn't match the schema, an `ExceptionInfo` is thrown:

```clojure
(try
  (llm/completion ...)
  (catch clojure.lang.ExceptionInfo e
    (when (= :validation-error (:type (ex-data e)))
      (println "Schema errors:" (:errors (ex-data e))))))
```

#### Disabling validation

Set `:validate-output false` to skip decode/validate and receive the raw response:

```clojure
{:response-format {:type :malli :schema [...]}
 :validate-output false}
```

`:parsed-output` will be absent; `:content` still holds the raw JSON string.

## Provider Support Matrix

| Provider    | `:json-object`           | `:json-schema`                     | `:malli`           |
|-------------|--------------------------|------------------------------------|--------------------|
| OpenAI      | `response_format`        | `response_format` + `json_schema`  | ✅ via conversion  |
| Azure       | `response_format`        | `response_format` + `json_schema`  | ✅ via conversion  |
| OpenRouter  | `response_format`        | `response_format` + `json_schema`  | ✅ via conversion  |
| Anthropic   | system prompt injection  | `output_config.format` (native GA) | ✅ via conversion  |
| Gemini      | `responseMimeType`       | `responseMimeType` + `responseSchema` | ✅ via conversion |
| Mistral     | `response_format`        | `response_format` + `json_schema`  | ✅ via conversion  |
| Ollama      | `format: "json"`         | `format: <schema>`                 | ✅ via conversion  |

> **Anthropic note:** `:json-schema` (and `:malli`) uses Anthropic's native `output_config` API (GA, no beta header). `:json-object` falls back to system prompt injection since Anthropic has no native schema-free JSON mode. Native support requires Claude Haiku 4.5, Sonnet 4.5/4.6, or Opus 4.5/4.6/4.7.

## Examples

### Nested Malli schema

```clojure
(def address-schema
  [:map
   [:street :string]
   [:city   :string]
   [:zip    :string]])

(def person-schema
  [:map
   [:name    :string]
   [:age     :int]
   [:address address-schema]
   [:hobbies [:vector :string]]])

(llm/completion :openai "gpt-4o-mini"
  {:messages [{:role :user :content "Generate a detailed person record."}]
   :response-format {:type :malli :schema person-schema}
   :max-tokens 256}
  {:api-key (System/getenv "OPENAI_API_KEY")})
```

### Cross-provider portability

```clojure
(def schema [:map [:summary :string] [:sentiment [:enum "positive" "negative" "neutral"]]])

(defn analyze [provider model api-key text]
  (-> (llm/completion provider model
        {:messages [{:role :user :content (str "Analyze: " text)}]
         :response-format {:type :malli :schema schema}}
        {:api-key api-key})
      (get-in [:choices 0 :message :parsed-output])))

;; Same call, any provider
(analyze :openai    "gpt-4o-mini"          (System/getenv "OPENAI_API_KEY")    "Great product!")
(analyze :anthropic "claude-haiku-4-5"     (System/getenv "ANTHROPIC_API_KEY") "Great product!")
(analyze :mistral   "mistral-small-latest" (System/getenv "MISTRAL_API_KEY")   "Great product!")
```

### Router API

```clojure
(require '[litellm.router :as router])

(router/register! :structured
  {:provider :openai
   :model    "gpt-4o-mini"
   :config   {:api-key (System/getenv "OPENAI_API_KEY")}})

(router/completion :structured
  {:messages [{:role :user :content "Generate a person."}]
   :response-format {:type :malli :schema [:map [:name :string] [:age :int]]}})
```

## See Also

- [Example file](../examples/10_json_output_example.clj)
- [Core API Reference](core-api.md)
- [Malli documentation](https://github.com/metosin/malli)
