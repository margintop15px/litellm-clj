(ns examples.10-json-output-example
  "Examples showing JSON output / structured output with litellm-clj.

  Three :response-format types are supported:
    {:type :json-object}          - free-form JSON (model decides shape)
    {:type :json-schema
     :json-schema {:name   \"...\"
                   :schema {...}  ; raw JSON Schema object
                   :strict true}} - constrained to a JSON Schema
    {:type :malli
     :schema <malli-schema>}      - Malli schema drives both the JSON Schema
                                    sent to the provider AND post-response
                                    validation/decode (adds :parsed-output)
    {:type :text}                  - explicit text (default)

  :validate-output (default true) — when using :malli, parse and validate the
  response JSON; throws on schema mismatch; adds :parsed-output to message.

  Provider mapping
  ----------------
  | Provider    | json-object          | json-schema                        |
  |-------------|----------------------|------------------------------------|
  | OpenAI      | response_format      | response_format + json_schema      |
  | Azure       | response_format      | response_format + json_schema      |
  | OpenRouter  | response_format      | response_format + json_schema      |
  | Anthropic   | system-prompt inject | output_config.format (native GA)   |
  | Gemini      | responseMimeType     | responseMimeType + responseSchema  |
  | Mistral     | response_format      | response_format + json_schema      |
  | Ollama      | format: \"json\"       | format: <schema-map>               |

  :malli is normalised to :json-schema before provider dispatch."
  (:require [litellm.core :as litellm]
            [cheshire.core :as json]))

;; ---------------------------------------------------------------------------
;; Example 1 – Free-form JSON (json-object mode)
;; ---------------------------------------------------------------------------

(defn example-json-object []
  (println "\n=== JSON Object Mode (OpenAI) ===")
  (let [response (litellm/completion
                   :openai "gpt-4o-mini"
                   {:messages [{:role :system :content "You are a helpful assistant."}
                               {:role :user   :content "Return today's date and a fun fact as JSON."}]
                    :response-format {:type :json-object}
                    :max-tokens 256}
                   {:api-key (System/getenv "OPENAI_API_KEY")})]
    (println "Parsed:" (json/decode (litellm/extract-content response) true))))

;; ---------------------------------------------------------------------------
;; Example 2 – Raw JSON Schema (json-schema mode)
;; ---------------------------------------------------------------------------

(def person-json-schema
  {:type "object"
   :properties {:name {:type "string"}
                :age  {:type "integer"}
                :city {:type "string"}}
   :required ["name" "age" "city"]
   :additionalProperties false})

(defn example-json-schema []
  (println "\n=== JSON Schema Mode (OpenAI, strict) ===")
  (let [response (litellm/completion
                   :openai "gpt-4o-mini"
                   {:messages [{:role :user :content "Generate a fictional person record."}]
                    :response-format {:type :json-schema
                                      :json-schema {:name   "person"
                                                    :schema person-json-schema
                                                    :strict true}}
                    :max-tokens 128}
                   {:api-key (System/getenv "OPENAI_API_KEY")})]
    (println "Parsed:" (json/decode (litellm/extract-content response) true))))

;; ---------------------------------------------------------------------------
;; Example 3 – Malli schema as output format (recommended)
;;
;; The library converts the Malli schema to JSON Schema automatically,
;; sends it to the provider, then validates and decodes the response.
;; The decoded Clojure map (keyword keys, correct types) is at :parsed-output.
;; ---------------------------------------------------------------------------

(def person-malli-schema
  [:map
   [:name :string]
   [:age  :int]
   [:city :string]])

(defn example-malli-schema []
  (println "\n=== Malli Schema Mode (OpenAI) – :parsed-output available ===")
  (let [response (litellm/completion
                   :openai "gpt-4o-mini"
                   {:messages [{:role :user :content "Generate a fictional person record."}]
                    :response-format {:type   :malli
                                      :schema person-malli-schema}
                    ;; :validate-output true  ; this is the default
                    :max-tokens 128}
                   {:api-key (System/getenv "OPENAI_API_KEY")})]
    (let [parsed (-> response :choices first :message :parsed-output)]
      (println "Keyword-keyed, type-safe output:" parsed)
      (println "Name:" (:name parsed)
               "| Age:" (:age parsed)
               "| City:" (:city parsed)))))

;; ---------------------------------------------------------------------------
;; Example 4 – Malli with :validate-output false (skip validation)
;; ---------------------------------------------------------------------------

(defn example-malli-no-validate []
  (println "\n=== Malli Schema Mode – validation disabled ===")
  (let [response (litellm/completion
                   :openai "gpt-4o-mini"
                   {:messages [{:role :user :content "Generate a person record."}]
                    :response-format {:type   :malli
                                      :schema person-malli-schema}
                    :validate-output false     ; skip Malli decode/validate
                    :max-tokens 128}
                   {:api-key (System/getenv "OPENAI_API_KEY")})]
    ;; :parsed-output will be nil; raw JSON string still available
    (println "Raw content:" (litellm/extract-content response))))

;; ---------------------------------------------------------------------------
;; Example 5 – Anthropic with native output_config (json-schema)
;; ---------------------------------------------------------------------------

(defn example-anthropic-structured []
  (println "\n=== Anthropic – native output_config (json-schema) ===")
  (let [response (litellm/completion
                   :anthropic "claude-sonnet-4-5"
                   {:messages [{:role :user
                                :content "List three programming languages with release years."}]
                    :response-format {:type   :malli
                                      :schema [:map
                                               [:languages
                                                [:vector [:map
                                                          [:name :string]
                                                          [:year :int]]]]]}
                    :max-tokens 256}
                   {:api-key (System/getenv "ANTHROPIC_API_KEY")})]
    (println "Parsed:" (-> response :choices first :message :parsed-output))))

;; ---------------------------------------------------------------------------
;; Example 6 – Same Malli schema, different providers
;; ---------------------------------------------------------------------------

(defn example-cross-provider []
  (println "\n=== Same Malli schema across providers ===")
  (doseq [[provider model env-var]
          [[:openai    "gpt-4o-mini"             "OPENAI_API_KEY"]
           [:anthropic "claude-haiku-4-5"        "ANTHROPIC_API_KEY"]
           [:mistral   "mistral-small-latest"    "MISTRAL_API_KEY"]]]
    (when (System/getenv env-var)
      (println (str "Provider: " (name provider)))
      (let [response (litellm/completion
                       provider model
                       {:messages [{:role :user
                                    :content "Give me a product: name (string), price (float), in_stock (bool)."}]
                        :response-format {:type   :malli
                                          :schema [:map
                                                   [:name     :string]
                                                   [:price    :double]
                                                   [:in_stock :boolean]]}
                        :max-tokens 128}
                       {:api-key (System/getenv env-var)})]
        (println "  Result:" (-> response :choices first :message :parsed-output))))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main [& _args]
  (example-json-object)
  (example-json-schema)
  (example-malli-schema)
  (example-malli-no-validate)
  (example-anthropic-structured)
  (example-cross-provider))
