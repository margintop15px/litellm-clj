# Examples

A collection of practical examples for common LiteLLM use cases.

## Basic Usage

### Simple Question & Answer

```clojure
(require '[litellm.router :as router])

(router/quick-setup!)

(defn ask [question]
  (-> (router/chat :openai question)
      router/extract-content))

(ask "What is the capital of France?")
;; => "The capital of France is Paris."
```

### With System Prompt

```clojure
(defn ask-expert [question domain]
  (-> (router/chat :openai question
        :system-prompt (str "You are an expert in " domain))
      router/extract-content))

(ask-expert "Explain quantum entanglement" "physics")
```

## Multi-turn Conversations

### Building a Conversation

```clojure
(require '[litellm.core :as core])

(defn chat-session []
  (let [history (atom [{:role :system :content "You are a helpful assistant"}])]
    
    (fn [user-message]
      (swap! history conj {:role :user :content user-message})
      
      (let [response (core/completion :openai "gpt-4"
                       {:messages @history}
                       {:api-key (System/getenv "OPENAI_API_KEY")})
            assistant-message (core/extract-message response)]
        
        (swap! history conj assistant-message)
        (:content assistant-message)))))

;; Usage
(def chat (chat-session))
(chat "Hi, I'm learning Clojure")
;; => "Great! Clojure is a powerful functional programming language..."
(chat "What's a good first project?")
;; => "For beginners, I'd recommend starting with..."
```

## Streaming Examples

### Progressive CLI Output

```clojure
(require '[litellm.core :as core]
         '[litellm.streaming :as streaming]
         '[clojure.core.async :refer [go-loop <!]])

(defn streaming-chat [question]
  (let [ch (core/completion :openai "gpt-4"
             {:messages [{:role :user :content question}]
              :stream true}
             {:api-key (System/getenv "OPENAI_API_KEY")})]
    
    (streaming/consume-stream-with-callbacks ch
      (fn [chunk]
        (print (streaming/extract-content chunk))
        (flush))
      (fn [_] (println))
      (fn [error] (println "Error:" error)))))

(streaming-chat "Write a short poem about Clojure")
```


## Structured JSON Output

### Malli Schema (Recommended)

```clojure
(require '[litellm.core :as llm])

(def response
  (llm/completion :openai "gpt-4o-mini"
    {:messages [{:role :user :content "Generate a product record."}]
     :response-format {:type   :malli
                       :schema [:map
                                [:name     :string]
                                [:price    :double]
                                [:in-stock :boolean]
                                [:tags     [:vector :string]]]}}
    {:api-key (System/getenv "OPENAI_API_KEY")}))

;; Keyword keys, correct Clojure types
(-> response :choices first :message :parsed-output)
;; => {:name "Widget Pro", :price 29.99, :in-stock true, :tags ["gadget" "new"]}
```

### Validation Failure Handling

```clojure
(try
  (llm/completion :openai "gpt-4o-mini"
    {:messages [{:role :user :content "Say hello."}]
     :response-format {:type :malli :schema [:map [:count :int]]}}
    {:api-key (System/getenv "OPENAI_API_KEY")})
  (catch clojure.lang.ExceptionInfo e
    (when (= :validation-error (:type (ex-data e)))
      (println "Schema mismatch:" (:errors (ex-data e))))))
```

### Skip Validation

Set `:validate-output false` to receive the raw response without decode/validate:

```clojure
(llm/completion :openai "gpt-4o-mini"
  {:messages [{:role :user :content "Give me JSON."}]
   :response-format {:type :malli :schema [:map [:name :string]]}
   :validate-output false}
  {:api-key (System/getenv "OPENAI_API_KEY")})
;; :parsed-output is absent; :content holds the raw JSON string
```

### Raw JSON Schema

If you prefer to manage your own schema, pass it directly:

```clojure
(llm/completion :openai "gpt-4o-mini"
  {:messages [{:role :user :content "Generate a person."}]
   :response-format {:type :json-schema
                     :json-schema {:name   "person"
                                   :schema {:type "object"
                                            :properties {:name {:type "string"}
                                                         :age  {:type "integer"}}
                                            :required ["name" "age"]
                                            :additionalProperties false}
                                   :strict true}}}
  {:api-key (System/getenv "OPENAI_API_KEY")})
```

### Cross-Provider

The same `:response-format` map works across all providers:

```clojure
(def schema [:map [:summary :string] [:sentiment [:enum "positive" "negative" "neutral"]]])

(defn analyze [provider model api-key text]
  (-> (llm/completion provider model
        {:messages [{:role :user :content (str "Analyze sentiment: " text)}]
         :response-format {:type :malli :schema schema}}
        {:api-key api-key})
      (get-in [:choices 0 :message :parsed-output])))

(analyze :openai    "gpt-4o-mini"          (System/getenv "OPENAI_API_KEY")    "Great product!")
(analyze :anthropic "claude-haiku-4-5"     (System/getenv "ANTHROPIC_API_KEY") "Great product!")
(analyze :mistral   "mistral-small-latest" (System/getenv "MISTRAL_API_KEY")   "Great product!")
```

## Next Steps

- Review the [[API Guide|api-guide]] for detailed API documentation
- Check [[Core API|core-api]] for direct provider access
- Explore [[Router API|router-api]] for configuration management
- Learn about [[streaming|streaming]] responses
- Read about [[JSON Output|json-output]] for the full structured output guide
- Read about [[error handling|/docs/ERROR_HANDLING.md]]
