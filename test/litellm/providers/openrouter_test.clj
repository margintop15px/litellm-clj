(ns litellm.providers.openrouter-test
  (:require [clojure.test :refer [deftest is testing]]
            [litellm.providers.openrouter :as openrouter]))

(deftest test-transform-messages-with-tool-calls
  (testing "Assistant tool calls and tool results round-trip in OpenAI wire shape"
    (let [messages [{:role       :assistant
                     :content    nil
                     :tool-calls [{:id       "call_1"
                                   :function {:name      "lookup"
                                              :arguments "{\"q\":\"x\"}"}}]}
                    {:role       :assistant
                     :content    "   "
                     :tool-calls [{:id       "call_2"
                                   :type     "function"
                                   :function {:name      "search"
                                              :arguments "{\"q\":\"y\"}"}}]}
                    {:role         :tool
                     :content      "done"
                     :name         "lookup"
                     :tool-call-id "call_1"}
                    {:role :assistant
                     :content nil}
                    {:role :assistant
                     :content "   "}]]
      (is (= [{:role       "assistant"
               :tool_calls [{:id       "call_1"
                             :type     "function"
                             :function {:name      "lookup"
                                        :arguments "{\"q\":\"x\"}"}}]}
              {:role       "assistant"
               :tool_calls [{:id       "call_2"
                             :type     "function"
                             :function {:name      "search"
                                        :arguments "{\"q\":\"y\"}"}}]}
              {:role         "tool"
               :content      "done"
               :name         "lookup"
               :tool_call_id "call_1"}
              {:role    "assistant"
               :content ""}
              {:role "assistant"}]
             (vec (openrouter/transform-messages messages)))))))

(deftest test-transform-streaming-chunk-text
  (testing "Streaming text deltas omit nil fields"
    (let [chunk  {:id      "chatcmpl_1"
                  :object  "chat.completion.chunk"
                  :created 123
                  :model   "openai/gpt-4o-mini"
                  :choices [{:index 0
                             :delta {:role "assistant"
                                     :content "Hello"}}]}
          result (openrouter/transform-streaming-chunk-impl :openrouter chunk)]
      (is (= {:id      "chatcmpl_1"
              :object  "chat.completion.chunk"
              :created 123
              :model   "openai/gpt-4o-mini"
              :choices [{:index 0
                         :delta {:role :assistant
                                 :content "Hello"}}]}
             result))
      (is (= result (openrouter/transform-streaming-chunk chunk))))))

(deftest test-transform-streaming-chunk-tool-call-start
  (testing "Streaming tool call start deltas preserve index, id, type, name, and arguments"
    (let [chunk  {:id      "chatcmpl_2"
                  :object  "chat.completion.chunk"
                  :created 124
                  :model   "openai/gpt-4o-mini"
                  :choices [{:index 0
                             :delta {:tool_calls
                                     [{:index    0
                                       :id       "call_1"
                                       :type     "function"
                                       :function {:name      "lookup"
                                                  :arguments "{\"q\""}}]}}]}
          result (openrouter/transform-streaming-chunk-impl :openrouter chunk)]
      (is (= [{:index    0
               :id       "call_1"
               :type     "function"
               :function {:name      "lookup"
                          :arguments "{\"q\""}}]
             (get-in result [:choices 0 :delta :tool-calls]))))))

(deftest test-transform-streaming-chunk-tool-call-arguments-and-finish
  (testing "Streaming argument-only deltas and tool_calls finish reason are preserved"
    (let [chunk  {:id      "chatcmpl_3"
                  :object  "chat.completion.chunk"
                  :created 125
                  :model   "openai/gpt-4o-mini"
                  :choices [{:index         0
                             :delta         {:tool_calls
                                             [{:index    0
                                               :function {:arguments ":\"x\"}"}}]}
                             :finish_reason "tool_calls"}]}
          result (openrouter/transform-streaming-chunk-impl :openrouter chunk)]
      (is (= [{:index    0
               :function {:arguments ":\"x\"}"}}]
             (get-in result [:choices 0 :delta :tool-calls])))
      (is (= :tool_calls (get-in result [:choices 0 :finish-reason]))))))
