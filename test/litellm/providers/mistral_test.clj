(ns litellm.providers.mistral-test
  (:require [clojure.test :refer [deftest testing is]]
            [litellm.providers.mistral :as mistral]))

;; ============================================================================
;; Message Transformation Tests
;; ============================================================================

(deftest test-transform-messages
  (testing "Transform messages to Mistral format"
    (let [messages    [{:role :user :content "Hello"}
                       {:role :assistant :content "Hi there"}]
          transformed (mistral/transform-messages messages)]
      (is (= 2 (count transformed)))
      (is (= "user" (:role (first transformed))))
      (is (= "Hello" (:content (first transformed)))))))

(deftest test-transform-messages-echoes-assistant-tool-calls
  (testing "an assistant message's tool_calls are echoed back (required for multi-turn tool calls); nil content is dropped, tool result keeps tool_call_id"
    (let [messages    [{:role :user :content "What's the magic number?"}
                       {:role       :assistant
                        :content    nil
                        :tool-calls [{:id       "call_1"
                                      :type     "function"
                                      :function {:name "get_magic_number" :arguments "{}"}}]}
                       {:role :tool :tool-call-id "call_1" :content "{\"magic\":4242}"}]
          transformed (vec (mistral/transform-messages messages))
          assistant   (nth transformed 1)
          tool        (nth transformed 2)]
      (is (= 3 (count transformed)))
      (is (= "assistant" (:role assistant)))
      (is (= 1 (count (:tool_calls assistant))))
      (is (= "call_1" (get-in assistant [:tool_calls 0 :id])))
      (is (= "function" (get-in assistant [:tool_calls 0 :type])))
      (is (= "get_magic_number" (get-in assistant [:tool_calls 0 :function :name])))
      (is (not (contains? assistant :content)))
      (is (= "tool" (:role tool)))
      (is (= "call_1" (:tool_call_id tool)))
      (is (= "{\"magic\":4242}" (:content tool))))))

(deftest test-supports-streaming
  (testing "Mistral supports streaming"
    (is (true? (mistral/supports-streaming-impl :mistral)))))

(deftest test-supports-function-calling
  (testing "Mistral supports function calling"
    (is (true? (mistral/supports-function-calling-impl :mistral)))))

;; ============================================================================
;; Embedding Tests
;; ============================================================================

(deftest test-transform-embedding-request-single-input
  (testing "Transform embedding request with single string input"
    (let [request     {:model "mistral-embed"
                       :input "Hello world"}
          config      {}
          transformed (mistral/transform-embedding-request-impl :mistral request config)]
      (is (= "mistral-embed" (:model transformed)))
      (is (vector? (:input transformed)))
      (is (= 1 (count (:input transformed))))
      (is (= "Hello world" (first (:input transformed)))))))

(deftest test-transform-embedding-request-array-input
  (testing "Transform embedding request with array input"
    (let [request     {:model "mistral-embed"
                       :input ["Hello" "World"]}
          config      {}
          transformed (mistral/transform-embedding-request-impl :mistral request config)]
      (is (= "mistral-embed" (:model transformed)))
      (is (vector? (:input transformed)))
      (is (= 2 (count (:input transformed))))
      (is (= "Hello" (first (:input transformed))))
      (is (= "World" (second (:input transformed)))))))

(deftest test-transform-embedding-response
  (testing "Transform embedding response from Mistral format"
    (let [response    {:body {:object "list"
                              :data   [{:object    "embedding"
                                        :embedding [0.1 0.2 0.3]
                                        :index     0}
                                       {:object    "embedding"
                                        :embedding [0.4 0.5 0.6]
                                        :index     1}]
                              :model  "mistral-embed"
                              :usage  {:prompt_tokens     10
                                       :completion_tokens 0
                                       :total_tokens      10}}}
          transformed (mistral/transform-embedding-response-impl :mistral response)]
      (is (= "list" (:object transformed)))
      (is (= "mistral-embed" (:model transformed)))
      (is (= 2 (count (:data transformed))))
      (is (= [0.1 0.2 0.3] (:embedding (first (:data transformed)))))
      (is (= [0.4 0.5 0.6] (:embedding (second (:data transformed)))))
      (is (= 10 (get-in transformed [:usage :prompt-tokens])))
      (is (= 0 (get-in transformed [:usage :completion-tokens]))))))

(deftest test-supports-embeddings
  (testing "Mistral supports embeddings"
    (is (true? (mistral/supports-embeddings-impl :mistral)))))

(deftest test-embedding-cost-map
  (testing "Embedding cost map contains mistral-embed model"
    (is (contains? mistral/default-cost-map "mistral-embed"))

    (testing "Costs have correct structure"
      (let [cost (get mistral/default-cost-map "mistral-embed")]
        (is (contains? cost :input))
        (is (contains? cost :output))
        (is (number? (:input cost)))
        (is (zero? (:output cost)))))))

;; ============================================================================
;; Model Cost Tests
;; ============================================================================

(deftest test-get-cost-per-token
  (testing "Get cost for known model"
    (let [cost (mistral/get-cost-per-token-impl :mistral "mistral-small-latest")]
      (is (some? cost))
      (is (contains? cost :input))
      (is (contains? cost :output))))

  (testing "Get cost for unknown model returns zeros"
    (let [cost (mistral/get-cost-per-token-impl :mistral "unknown-model")]
      (is (= {:input 0.0 :output 0.0} cost)))))

;; ============================================================================
;; Reasoning Support Tests
;; ============================================================================

(deftest test-supports-reasoning
  (testing "Magistral models support reasoning"
    (is (true? (mistral/supports-reasoning? "magistral-small-2506")))
    (is (true? (mistral/supports-reasoning? "magistral-medium-2506"))))

  (testing "Regular models don't support reasoning"
    (is (false? (mistral/supports-reasoning? "mistral-small-latest")))
    (is (false? (mistral/supports-reasoning? "mistral-embed")))))

(deftest test-add-reasoning-system-prompt
  (testing "Add reasoning system prompt to messages"
    (let [messages [{:role "user" :content "What is 2+2?"}]
          result   (mistral/add-reasoning-system-prompt messages "high")]
      (is (= 2 (count result)))
      (is (= "system" (:role (first result))))
      (is (re-find #"think step-by-step" (:content (first result))))))

  (testing "Prepend to existing system message"
    (let [messages [{:role "system" :content "You are helpful"}
                    {:role "user" :content "Hello"}]
          result   (mistral/add-reasoning-system-prompt messages "high")]
      (is (= 2 (count result)))
      (is (= "system" (:role (first result))))
      (is (re-find #"think step-by-step" (:content (first result))))
      (is (re-find #"You are helpful" (:content (first result)))))))

;; ============================================================================
;; Transform Request Tests
;; ============================================================================

(deftest test-transform-request-basic
  (testing "Transform basic request"
    (let [request     {:model      "mistral-small-latest"
                       :messages   [{:role :user :content "Hello"}]
                       :max-tokens 100}
          config      {}
          transformed (mistral/transform-request-impl :mistral request config)]
      (is (= "mistral-small-latest" (:model transformed)))
      (is (= 100 (:max_tokens transformed)))
      (is (= 1 (count (:messages transformed)))))))

(deftest test-transform-request-with-reasoning
  (testing "Transform request with reasoning for magistral model"
    (let [request     {:model            "magistral-small-2506"
                       :messages         [{:role :user :content "Solve this"}]
                       :reasoning-effort "high"}
          config      {}
          transformed (mistral/transform-request-impl :mistral request config)]
      (is (= "magistral-small-2506" (:model transformed)))
      ;; Should have added system message with reasoning prompt
      (is (= 2 (count (:messages transformed))))
      (is (= "system" (:role (first (:messages transformed)))))))

  (testing "Don't add reasoning prompt for non-magistral models"
    (let [request     {:model            "mistral-small-latest"
                       :messages         [{:role :user :content "Solve this"}]
                       :reasoning-effort "high"}
          config      {}
          transformed (mistral/transform-request-impl :mistral request config)]
      (is (= "mistral-small-latest" (:model transformed)))
      ;; Should not have added system message
      (is (= 1 (count (:messages transformed)))))))

;; ============================================================================
;; Response Format / JSON Output Tests
;; ============================================================================

(deftest test-transform-request-json-object-format
  (testing "transform-request-impl includes response_format for :json-object"
    (let [request {:model           "mistral-small-latest"
                   :messages        [{:role :user :content "Give me JSON"}]
                   :response-format {:type :json-object}}
          result  (mistral/transform-request-impl :mistral request {})]
      (is (= {:type "json_object"} (:response_format result))))))

(deftest test-transform-request-no-response-format
  (testing "transform-request-impl does not add response_format when absent"
    (let [request {:model    "mistral-small-latest"
                   :messages [{:role :user :content "Hello"}]}
          result  (mistral/transform-request-impl :mistral request {})]
      (is (nil? (:response_format result))))))

(deftest test-transform-request-json-schema-format
  (testing "transform-request-impl produces json_schema response_format with sub-object"
    (let [schema  {:type "object" :properties {:title {:type "string"}} :required ["title"]}
          request {:model           "mistral-small-latest"
                   :messages        [{:role :user :content "Give me structured JSON"}]
                   :response-format {:type        :json-schema
                                     :json-schema {:name "book" :schema schema :strict true}}}
          result  (mistral/transform-request-impl :mistral request {})]
      (is (= "json_schema" (get-in result [:response_format :type])))
      (is (= "book" (get-in result [:response_format :json_schema :name])))
      (is (= schema (get-in result [:response_format :json_schema :schema])))
      (is (true? (get-in result [:response_format :json_schema :strict]))))))
