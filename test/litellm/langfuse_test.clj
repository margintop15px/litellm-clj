(ns litellm.langfuse-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [cheshire.core :as json]
            [hato.client :as http]
            [litellm.langfuse :as lf]
            [litellm.errors :as errors]))

(def ^:private test-config
  {:public-key "pk-test"
   :secret-key "sk-test"
   :host       "https://example.langfuse.test"})

(defn- with-clean-cache [f]
  (lf/clear-cache!)
  (f)
  (lf/clear-cache!))

(use-fixtures :each with-clean-cache)

;; ============================================================================
;; Compile — text
;; ============================================================================

(deftest compile-text-substitutes-vars
  (is (= "Hello, Sergey! Welcome to Langfuse."
         (lf/compile-prompt
          {:name "greeting" :type "text"
           :prompt "Hello, {{user}}! Welcome to {{system}}."}
          {:user "Sergey" :system "Langfuse"}))))

(deftest compile-text-missing-var-preserved
  (is (= "Hello, {{user}}! Welcome to Langfuse."
         (lf/compile-prompt
          {:name "greeting" :type "text"
           :prompt "Hello, {{user}}! Welcome to {{system}}."}
          {:system "Langfuse"}))))

(deftest compile-text-accepts-string-keys
  (is (= "Hi, Anna"
         (lf/compile-prompt
          {:name "greeting" :type "text" :prompt "Hi, {{user}}"}
          {"user" "Anna"}))))

;; ============================================================================
;; Compile — chat
;; ============================================================================

(deftest compile-chat-substitutes-content-and-keywordizes-role
  (let [prompt {:name "critic" :type "chat"
                :prompt [{:role "system"
                          :content "You are a {{level}} movie critic."}
                         {:role "user"
                          :content "Do you like {{movie}}?"}]}
        out    (lf/compile-prompt prompt {:level "harsh" :movie "Dune 2"})]
    (is (= [{:role :system  :content "You are a harsh movie critic."}
            {:role :user    :content "Do you like Dune 2?"}]
           out))))

(deftest compile-chat-expands-message-placeholders
  (let [prompt {:name "agent" :type "chat"
                :prompt [{:role "system" :content "Be helpful."}
                         {:type "placeholder" :name "history"}
                         {:role "user" :content "Continue."}]}
        history [{:role "user" :content "Earlier turn"}
                 {:role "assistant" :content "Earlier reply"}]
        out    (lf/compile-prompt prompt {:history history})]
    (is (= [{:role :system    :content "Be helpful."}
            {:role :user      :content "Earlier turn"}
            {:role :assistant :content "Earlier reply"}
            {:role :user      :content "Continue."}]
           out))))

(deftest compile-chat-missing-placeholder-preserved
  (let [prompt {:name "agent" :type "chat"
                :prompt [{:role "system" :content "Be helpful."}
                         {:type "placeholder" :name "history"}]}
        out    (lf/compile-prompt prompt {})]
    (is (= [{:role :system :content "Be helpful."}
            {:type "placeholder" :name "history"}]
           out))))

(deftest compile-chat-missing-var-preserved
  (let [prompt {:name "p" :type "chat"
                :prompt [{:role "user" :content "Hi {{name}}"}]}
        out    (lf/compile-prompt prompt {})]
    (is (= [{:role :user :content "Hi {{name}}"}] out))))

;; ============================================================================
;; HTTP — request shape
;; ============================================================================

(defn- ok [body]
  {:status 200 :body (json/encode body)})

(defn- captured-request
  "Run f under a redef'd hato/request that captures the opts map and returns
  the given response. Returns the captured opts."
  [response f]
  (let [captured (atom nil)]
    (with-redefs [http/request (fn [opts] (reset! captured opts) response)]
      (f))
    @captured))

(deftest get-prompt-uses-basic-auth-and-default-label
  (let [resp  (ok {:name "p" :version 1 :type "text" :prompt "x"})
        opts  (captured-request resp #(lf/get-prompt "p" {:config test-config}))]
    (is (= :get (:method opts)))
    (is (= "https://example.langfuse.test/api/public/v2/prompts/p" (:url opts)))
    (is (= {:user "pk-test" :pass "sk-test"} (:basic-auth opts)))
    (is (= {:label "production"} (:query-params opts)))))

(deftest get-prompt-uses-version-when-given
  (let [resp (ok {:name "p" :version 3 :type "text" :prompt "x"})
        opts (captured-request resp
                               #(lf/get-prompt "p" {:version 3 :config test-config}))]
    (is (= {:version 3} (:query-params opts)))))

(deftest get-prompt-uses-explicit-label
  (let [resp (ok {:name "p" :version 1 :type "text" :prompt "x"})
        opts (captured-request resp
                               #(lf/get-prompt "p" {:label "staging" :config test-config}))]
    (is (= {:label "staging"} (:query-params opts)))))

(deftest get-prompt-normalizes-commit-message
  (with-redefs [http/request
                (fn [_]
                  (ok {:name "p" :version 1 :type "text" :prompt "x"
                       :commitMessage "added a thing"}))]
    (let [p (lf/get-prompt "p" {:config test-config :cache? false})]
      (is (= "added a thing" (:commit-message p)))
      (is (not (contains? p :commitMessage))))))

(deftest create-prompt-posts-camelcased-body
  (let [spec {:name "p" :type "chat"
              :prompt [{:role "user" :content "hi"}]
              :labels ["production"]
              :commit-message "initial"}
        resp (ok (assoc spec :version 1 :commitMessage "initial"))
        opts (captured-request resp #(lf/create-prompt spec {:config test-config}))
        body (json/parse-string (:body opts) true)]
    (is (= :post (:method opts)))
    (is (= "https://example.langfuse.test/api/public/v2/prompts" (:url opts)))
    (is (= "initial" (:commitMessage body)))
    (is (not (contains? body :commit-message)))))

(deftest update-prompt-labels-patches-version
  (let [resp (ok {:name "p" :version 2 :type "text" :prompt "x"
                  :labels ["production" "staging"]})
        opts (captured-request resp
                               #(lf/update-prompt-labels
                                 "p" 2 ["production" "staging"]
                                 {:config test-config}))
        body (json/parse-string (:body opts) true)]
    (is (= :patch (:method opts)))
    (is (= "https://example.langfuse.test/api/public/v2/prompts/p/versions/2"
           (:url opts)))
    (is (= {:newLabels ["production" "staging"]} body))))

(deftest list-prompts-camelcases-date-keys
  (let [resp (ok {:data [] :meta {}})
        opts (captured-request resp
                               #(lf/list-prompts
                                 {:limit 10
                                  :from-updated-at "2026-01-01"
                                  :config test-config}))]
    (is (= {:limit 10 :fromUpdatedAt "2026-01-01"} (:query-params opts)))))

;; ============================================================================
;; Errors
;; ============================================================================

(deftest http-404-maps-to-prompt-not-found
  (with-redefs [http/request (fn [_]
                               {:status 404
                                :body   (json/encode {:message "Not found"})})]
    (try
      (lf/get-prompt "missing" {:config test-config :cache? false})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [d (ex-data e)]
          (is (= :litellm/prompt-not-found (:type d)))
          (is (= "missing" (-> d :context :prompt-name))))))))

(deftest http-401-maps-to-authentication-error
  (with-redefs [http/request (fn [_]
                               {:status 401
                                :body   (json/encode {:message "Bad creds"})})]
    (try
      (lf/get-prompt "p" {:config test-config :cache? false})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :litellm/authentication-error (:type (ex-data e))))))))

(deftest http-500-maps-to-server-error
  (with-redefs [http/request (fn [_] {:status 503 :body "down"})]
    (try
      (lf/get-prompt "p" {:config test-config :cache? false})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :litellm/server-error (:type (ex-data e))))))))

(deftest missing-public-key-throws-invalid-config
  (with-redefs [http/request (fn [_] (ok {}))]
    (try
      (lf/get-prompt "p" {:config {:secret-key "sk" :host "https://x"}})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :litellm/invalid-config (:type (ex-data e))))))))

;; ============================================================================
;; Cache
;; ============================================================================

(deftest cache-hit-skips-second-http-call
  (let [calls (atom 0)]
    (with-redefs [http/request
                  (fn [_]
                    (swap! calls inc)
                    (ok {:name "p" :version 1 :type "text" :prompt "x"}))]
      (lf/get-prompt "p" {:config test-config})
      (lf/get-prompt "p" {:config test-config})
      (is (= 1 @calls) "cached fetch should hit HTTP only once"))))

(deftest cache-disabled-per-call
  (let [calls (atom 0)]
    (with-redefs [http/request
                  (fn [_]
                    (swap! calls inc)
                    (ok {:name "p" :version 1 :type "text" :prompt "x"}))]
      (lf/get-prompt "p" {:config test-config :cache? false})
      (lf/get-prompt "p" {:config test-config :cache? false})
      (is (= 2 @calls)))))

(deftest cache-respects-different-tenants
  (let [calls (atom 0)
        cfg-a (assoc test-config :public-key "pk-A")
        cfg-b (assoc test-config :public-key "pk-B")]
    (with-redefs [http/request
                  (fn [_]
                    (swap! calls inc)
                    (ok {:name "p" :version 1 :type "text" :prompt "x"}))]
      (lf/get-prompt "p" {:config cfg-a})
      (lf/get-prompt "p" {:config cfg-b})
      (is (= 2 @calls) "different public keys must not share cache entries"))))

(deftest cache-expires-after-ttl
  (let [calls (atom 0)
        clock (atom 1000)]
    (with-redefs [http/request
                  (fn [_]
                    (swap! calls inc)
                    (ok {:name "p" :version 1 :type "text" :prompt "x"}))
                  litellm.langfuse/now-ms (fn [] @clock)]
      (lf/get-prompt "p" {:config (assoc test-config :cache-ttl-ms 100)})
      (swap! clock + 200) ; past the TTL
      (lf/get-prompt "p" {:config (assoc test-config :cache-ttl-ms 100)})
      (is (= 2 @calls)))))

(deftest invalidate-drops-cached-entry
  (let [calls (atom 0)]
    (with-redefs [http/request
                  (fn [_]
                    (swap! calls inc)
                    (ok {:name "p" :version 1 :type "text" :prompt "x"}))]
      (lf/get-prompt "p" {:config test-config})
      (lf/invalidate! "p" {:config test-config})
      (lf/get-prompt "p" {:config test-config})
      (is (= 2 @calls)))))

(deftest create-prompt-invalidates-cached-entry
  (let [calls (atom 0)
        resp  (ok {:name "p" :version 1 :type "text" :prompt "x"})]
    (with-redefs [http/request
                  (fn [_]
                    (swap! calls inc)
                    resp)]
      (lf/get-prompt "p" {:config test-config})  ; populates cache
      (lf/create-prompt {:name "p" :type "text" :prompt "x"}
                        {:config test-config})  ; should invalidate
      (lf/get-prompt "p" {:config test-config})  ; cache miss
      (is (= 3 @calls)))))

;; ============================================================================
;; Render — fetch + compile end-to-end (no network)
;; ============================================================================

(deftest render-fetches-then-compiles
  (with-redefs [http/request
                (fn [_]
                  (ok {:name "greet" :version 1 :type "chat"
                       :prompt [{:role "system" :content "Be {{tone}}."}
                                {:role "user"   :content "Hi"}]}))]
    (is (= [{:role :system :content "Be friendly."}
            {:role :user   :content "Hi"}]
           (lf/render "greet" {:tone "friendly"} {:config test-config})))))
