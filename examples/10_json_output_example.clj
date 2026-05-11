(ns examples.10-json-output-example
  "Examples showing JSON output / structured output with litellm-clj.

  Three modes are supported via the :response-format key:
    {:type :json-object}          - free-form JSON (model decides shape)
    {:type :json-schema
     :json-schema {:name   \"...\"
                   :schema {...}  ; JSON Schema object
                   :strict true}} - constrained to a specific schema
    {:type :text}                  - default text (explicit opt-out)

  Provider behaviour
  ------------------
  | Provider    | json-object          | json-schema                      |
  |-------------|----------------------|----------------------------------|
  | OpenAI      | response_format      | response_format + json_schema    |
  | Azure       | response_format      | response_format + json_schema    |
  | OpenRouter  | response_format      | response_format + json_schema    |
  | Anthropic   | system-prompt inject | system-prompt inject w/ schema   |
  | Gemini      | responseMimeType     | responseMimeType + responseSchema|
  | Mistral     | response_format      | (json-object only)               |
  | Ollama      | format: \"json\"       | format: <schema>                 |"
  (:require [litellm.core :as litellm]
            [cheshire.core :as json]))

;; ---------------------------------------------------------------------------
;; Example 1 – Free-form JSON object (json-object mode)
;; ---------------------------------------------------------------------------

(defn example-json-object []
  (println "\n=== JSON Object Mode ===")
  (let [response (litellm/completion
                   "openai"
                   {:model "gpt-4o-mini"
                    :messages [{:role :system
                                :content "You are a helpful assistant."}
                               {:role :user
                                :content "Return today's date and a fun fact about it as JSON."}]
                    :response-format {:type :json-object}
                    :max-tokens 256}
                   {:api-key (System/getenv "OPENAI_API_KEY")})]
    (let [raw (litellm/extract-content response)
          parsed (json/decode raw true)]
      (println "Raw content:" raw)
      (println "Parsed:" parsed))))

;; ---------------------------------------------------------------------------
;; Example 2 – Constrained schema (json-schema mode, strict)
;; ---------------------------------------------------------------------------

(def person-schema
  {:type "object"
   :properties {:name {:type "string"}
                :age  {:type "integer"}
                :city {:type "string"}}
   :required ["name" "age" "city"]
   :additionalProperties false})

(defn example-json-schema []
  (println "\n=== JSON Schema Mode (strict) ===")
  (let [response (litellm/completion
                   "openai"
                   {:model "gpt-4o-mini"
                    :messages [{:role :user
                                :content "Generate a fictional person record."}]
                    :response-format {:type :json-schema
                                      :json-schema {:name   "person"
                                                    :schema person-schema
                                                    :strict true}}
                    :max-tokens 128}
                   {:api-key (System/getenv "OPENAI_API_KEY")})]
    (let [raw    (litellm/extract-content response)
          parsed (json/decode raw true)]
      (println "Parsed person:" parsed)
      (println "Name:" (:name parsed)
               "| Age:" (:age parsed)
               "| City:" (:city parsed)))))

;; ---------------------------------------------------------------------------
;; Example 3 – Anthropic (system-prompt injection)
;; ---------------------------------------------------------------------------

(defn example-anthropic-json []
  (println "\n=== Anthropic – JSON Object (system prompt injection) ===")
  (let [response (litellm/completion
                   "anthropic"
                   {:model "claude-3-haiku-20240307"
                    :messages [{:role :user
                                :content "List three programming languages with their release years."}]
                    :response-format {:type :json-object}
                    :max-tokens 256}
                   {:api-key (System/getenv "ANTHROPIC_API_KEY")})]
    (let [raw    (litellm/extract-content response)
          parsed (json/decode raw true)]
      (println "Parsed:" parsed))))

;; ---------------------------------------------------------------------------
;; Example 4 – Gemini (responseMimeType)
;; ---------------------------------------------------------------------------

(defn example-gemini-json []
  (println "\n=== Gemini – JSON Object ===")
  (let [response (litellm/completion
                   "gemini"
                   {:model "gemini-1.5-flash-latest"
                    :messages [{:role :user
                                :content "Return the capitals of France, Germany, and Italy as JSON."}]
                    :response-format {:type :json-object}
                    :max-tokens 128}
                   {:api-key (System/getenv "GEMINI_API_KEY")})]
    (let [raw    (litellm/extract-content response)
          parsed (json/decode raw true)]
      (println "Parsed:" parsed))))

;; ---------------------------------------------------------------------------
;; Example 5 – Schema across providers (switch freely)
;; ---------------------------------------------------------------------------

(defn structured-request [provider model api-key-env]
  (litellm/completion
    provider
    {:model model
     :messages [{:role :user
                 :content "Give me a product record: name, price (float), in_stock (bool)."}]
     :response-format {:type :json-schema
                       :json-schema {:name   "product"
                                     :schema {:type "object"
                                              :properties {:name     {:type "string"}
                                                           :price    {:type "number"}
                                                           :in_stock {:type "boolean"}}
                                              :required ["name" "price" "in_stock"]
                                              :additionalProperties false}}}
     :max-tokens 128}
    {:api-key (System/getenv api-key-env)}))

(defn example-cross-provider []
  (println "\n=== Same schema, different providers ===")
  (doseq [[provider model env-var]
          [["openai"    "gpt-4o-mini"                "OPENAI_API_KEY"]
           ["anthropic" "claude-3-haiku-20240307"    "ANTHROPIC_API_KEY"]]]
    (when (System/getenv env-var)
      (println (str "Provider: " provider))
      (let [response (structured-request provider model env-var)
            raw      (litellm/extract-content response)
            parsed   (json/decode raw true)]
        (println "  Result:" parsed)))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main [& _args]
  (example-json-object)
  (example-json-schema)
  (example-anthropic-json)
  (example-gemini-json)
  (example-cross-provider))
