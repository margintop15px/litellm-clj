;; Example 13: End-to-end smoke against Langfuse's prompt management API.
;;
;; Creates a chat prompt, fetches it back, compiles it with vars, manages
;; labels, and exercises the local cache. Use this to verify the
;; `litellm.langfuse` namespace against a real Langfuse project.
;;
;; ---------------------------------------------------------------------------
;; Prerequisites
;; ---------------------------------------------------------------------------
;;   export LANGFUSE_PUBLIC_KEY=pk-lf-...
;;   export LANGFUSE_SECRET_KEY=sk-lf-...
;;   export LANGFUSE_HOST=https://cloud.langfuse.com   ;; optional, this is the default
;;
;;   In a REPL:
;;     (load-file "examples/13_langfuse_prompts_smoke.clj")
;; ---------------------------------------------------------------------------

(require '[litellm.langfuse :as lf]
         '[com.brunobonacci.mulog :as mu])

;; Mirror the namespace's mu/log events to stdout for visibility.
(mu/stop-all-publishers!)
(def stop-publisher!
  (mu/start-publisher! {:type :console :pretty? true}))

(def prompt-name (str "litellm-clj-smoke-" (System/currentTimeMillis)))

(println "\n=== 1. Create a chat prompt with a {{var}} and a message placeholder ===")

(def created
  (lf/create-prompt
   {:name    prompt-name
    :type    "chat"
    :prompt  [{:role "system"
               :content "You are {{persona}}. Be concise."}
              {:type "placeholder" :name "history"}
              {:role "user" :content "Say hi to {{user}}."}]
    :labels  ["production"]
    :tags    ["smoke"]
    :commit-message "initial smoke version"}))

(println "Created version" (:version created) "of" (:name created))

(println "\n=== 2. Fetch it back (production label, cache miss) ===")
(def fetched (lf/get-prompt prompt-name))
(println "Fetched type=" (:type fetched) "labels=" (:labels fetched))

(println "\n=== 3. Second fetch — cache hit (no HTTP request event should fire) ===")
(lf/get-prompt prompt-name)

(println "\n=== 4. Compile with placeholders + vars ===")
(def history
  [{:role "user"      :content "What's 2+2?"}
   {:role "assistant" :content "4"}])

(def compiled
  (lf/render prompt-name
             {:persona "a pirate"
              :user    "Sergey"
              :history history}))

(clojure.pprint/pprint compiled)

(println "\n=== 5. List versions ===")
(clojure.pprint/pprint (lf/get-prompt-versions prompt-name {:limit 5}))

(println "\n=== 6. Update labels (add 'staging') ===")
(def relabeled
  (lf/update-prompt-labels prompt-name (:version created) ["production" "staging"]))
(println "New labels:" (:labels relabeled))

(println "\n=== 7. Confirm cache was invalidated on relabel ===")
(def re-fetched (lf/get-prompt prompt-name))
(println "Re-fetched labels:" (:labels re-fetched))

(println "\n=== 8. 404 path — fetching a missing name ===")
(try
  (lf/get-prompt (str prompt-name "-does-not-exist") {:cache? false})
  (catch clojure.lang.ExceptionInfo e
    (println "Got expected exception:" (:type (ex-data e)))))

(println "\nWaiting 2s for mu/log to flush...")
(Thread/sleep 2000)
(stop-publisher!)
(println "Done. Open Langfuse → Prompts and find:" prompt-name)
