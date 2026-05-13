(ns litellm.langfuse
  "Client for Langfuse's prompt management REST API.

  Mirrors the official Langfuse SDK surface against `/api/public/v2/prompts`:
  fetch versioned prompts by name + version|label, compile them with
  `{{var}}` placeholders and Langfuse message placeholders, create new
  versions, list, list versions, and manage labels.

  Configuration is resolved at call time from explicit opts, then env vars:

    LANGFUSE_PUBLIC_KEY, LANGFUSE_SECRET_KEY, LANGFUSE_HOST

  Default host: https://cloud.langfuse.com. Auth is HTTP Basic with the
  public key as username and secret key as password.

  A process-local in-memory cache is on by default (60s TTL, keyed by
  host + public-key + name + version|label so multi-tenant callers don't
  collide). Disable per-call via `:cache? false`.

  Quick start:

    (require '[litellm.langfuse :as lf]
             '[litellm.core      :as llm])

    (def msgs (lf/render \"movie-critic\"
                        {:criticlevel \"expert\" :movie \"Dune 2\"}))
    (llm/completion {:model \"gpt-4o-mini\" :messages msgs})"
  (:require [hato.client :as http]
            [cheshire.core :as json]
            [com.brunobonacci.mulog :as mu]
            [malli.core :as m]
            [malli.error :as me]
            [clojure.string :as str]
            [litellm.errors :as errors]
            [litellm.schemas :as schemas]))

;; ============================================================================
;; Configuration
;; ============================================================================

(def ^:private default-host "https://cloud.langfuse.com")
(def ^:private default-cache-ttl-ms 60000)
(def ^:private default-timeout-ms 30000)
(def ^:private provider :langfuse)

(defn- env [k] (System/getenv k))

(defn resolve-config
  "Merge explicit opts with env-var fallbacks. Returns a fully-resolved
  config map with :host, :public-key, :secret-key, :timeout-ms,
  :cache?, :cache-ttl-ms. Throws on missing credentials."
  [opts]
  (let [host       (or (:host opts)        (env "LANGFUSE_HOST")        default-host)
        public-key (or (:public-key opts)  (env "LANGFUSE_PUBLIC_KEY"))
        secret-key (or (:secret-key opts)  (env "LANGFUSE_SECRET_KEY"))
        timeout-ms (or (:timeout-ms opts)  default-timeout-ms)
        cache?     (if (contains? opts :cache?) (:cache? opts) true)
        cache-ttl  (or (:cache-ttl-ms opts) default-cache-ttl-ms)]
    (when (str/blank? public-key)
      (throw (errors/invalid-config
              "Langfuse public key not set (pass :public-key or set LANGFUSE_PUBLIC_KEY)")))
    (when (str/blank? secret-key)
      (throw (errors/invalid-config
              "Langfuse secret key not set (pass :secret-key or set LANGFUSE_SECRET_KEY)")))
    {:host         host
     :public-key   public-key
     :secret-key   secret-key
     :timeout-ms   timeout-ms
     :cache?       cache?
     :cache-ttl-ms cache-ttl}))

;; ============================================================================
;; Cache (in-memory, TTL, multi-tenant safe)
;; ============================================================================

(defonce ^:private cache (atom {}))

(defn- now-ms [] (System/currentTimeMillis))

(defn- cache-key
  "Key tuple including host + public-key so different tenants don't collide."
  [config prompt-name selector]
  [(:host config) (:public-key config) prompt-name selector])

(defn- cache-get [k]
  (when-let [entry (get @cache k)]
    (if (< (now-ms) (:expires-at entry))
      (:value entry)
      (do (swap! cache dissoc k) nil))))

(defn- cache-put! [k value ttl-ms]
  (swap! cache assoc k {:value value :expires-at (+ (now-ms) ttl-ms)})
  value)

(defn clear-cache!
  "Drop every cached prompt across all tenants."
  []
  (reset! cache {})
  nil)

(defn invalidate!
  "Drop cached entries for a prompt name (all versions/labels) within the
  resolved tenant."
  ([prompt-name] (invalidate! prompt-name {}))
  ([prompt-name opts]
   (let [{:keys [host public-key]} (resolve-config (:config opts))]
     (swap! cache
            (fn [m]
              (into {}
                    (remove (fn [[[h pk n _] _]]
                              (and (= h host)
                                   (= pk public-key)
                                   (= n prompt-name))))
                    m))))
   nil))

;; ============================================================================
;; HTTP layer
;; ============================================================================

(defn- key-rename
  "Rename known camelCase Langfuse fields to kebab-case so Clojure
  consumers get idiomatic keys."
  [m]
  (cond-> m
    (contains? m :commitMessage)
    (-> (assoc :commit-message (:commitMessage m))
        (dissoc :commitMessage))))

(defn- normalize-prompt [prompt]
  (if (map? prompt) (key-rename prompt) prompt))

(defn- denormalize-spec
  "Convert kebab-case input keys to the camelCase Langfuse expects."
  [spec]
  (cond-> spec
    (contains? spec :commit-message)
    (-> (assoc :commitMessage (:commit-message spec))
        (dissoc :commit-message))))

(defn- parse-body [body-str]
  (when (and (string? body-str) (seq body-str))
    (try (json/parse-string body-str true)
         (catch Exception _ nil))))

(defn- status->error
  "Throw an appropriate litellm error for a non-2xx response."
  [status parsed context]
  (let [msg (or (some-> parsed :message)
                (some-> parsed :error)
                (str "Langfuse HTTP " status))]
    (cond
      (= 404 status)
      (errors/prompt-not-found
       provider (:prompt-name context)
       :http-status status
       :version     (:version context)
       :label       (:label context))

      (= 401 status)
      (errors/authentication-error provider msg :http-status status)

      (= 403 status)
      (errors/authorization-error provider msg :http-status status)

      (= 429 status)
      (errors/rate-limit provider msg :http-status status)

      (<= 400 status 499)
      (errors/invalid-request msg :context {:status status :body parsed})

      (<= 500 status 599)
      (errors/server-error provider msg :http-status status)

      :else
      (errors/provider-error provider msg :http-status status))))

(defn- request*
  "Single sync HTTP call against Langfuse. Translates non-2xx into typed
  litellm errors. Returns parsed JSON body on 2xx (or nil for 204)."
  [{:keys [method path config query body context]}]
  (let [{:keys [host public-key secret-key timeout-ms]} config
        url  (str host path)
        opts (cond-> {:method            method
                      :url               url
                      :basic-auth        {:user public-key :pass secret-key}
                      :headers           {"Accept" "application/json"}
                      :timeout           timeout-ms
                      :throw-exceptions? false}
               (seq query) (assoc :query-params query)
               (some? body) (assoc :body (json/encode body)
                                   :content-type :json))]
    (mu/log ::request
            :litellm/kind :langfuse
            :http.method  (str/upper-case (name method))
            :http.url     url)
    (let [response (errors/wrap-http-errors provider #(http/request opts))
          status   (:status response)
          parsed   (parse-body (:body response))]
      (if (<= 200 status 299)
        parsed
        (throw (status->error status parsed context))))))

;; ============================================================================
;; Compilation (pure)
;; ============================================================================

(def ^:private var-pattern #"\{\{\s*([\w.-]+)\s*\}\}")

(defn- lookup-var [vars var-name prompt-name]
  (let [kk (keyword var-name)
        sk (str var-name)]
    (cond
      (contains? vars kk) (str (get vars kk))
      (contains? vars sk) (str (get vars sk))
      :else (do (mu/log ::missing-var
                        :litellm/kind :langfuse
                        :prompt.name  prompt-name
                        :var          var-name)
                nil))))

(defn- substitute-vars
  "Replace `{{var}}` tokens in `s` with values from `vars`. Missing vars are
  left in place and reported via mu/log."
  [s vars prompt-name]
  (if-not (string? s)
    s
    (str/replace s var-pattern
                 (fn [[whole var-name]]
                   (or (lookup-var vars var-name prompt-name) whole)))))

(defn- placeholder? [entry]
  (and (map? entry) (= "placeholder" (:type entry))))

(defn- expand-placeholders
  "Splice in messages for each `{:type \"placeholder\" :name x}` entry from
  `(get vars (keyword x))`. Missing keys leave the placeholder intact."
  [messages vars prompt-name]
  (into []
        (mapcat
         (fn [entry]
           (if (placeholder? entry)
             (let [v (get vars (keyword (:name entry)))]
               (cond
                 (nil? v) (do (mu/log ::missing-placeholder
                                      :litellm/kind :langfuse
                                      :prompt.name  prompt-name
                                      :placeholder  (:name entry))
                              [entry])
                 (sequential? v) (vec v)
                 :else [v]))
             [entry])))
        messages))

(defn- keywordize-role [msg]
  (if (and (map? msg) (string? (:role msg)))
    (update msg :role keyword)
    msg))

(defn compile-prompt
  "Compile a Langfuse prompt map with caller-supplied vars.

  - Text prompts return a string.
  - Chat prompts return a vector of `{:role :content ...}` messages with
    Langfuse message placeholders expanded and `:role` keywordized for
    drop-in use with `litellm.core/completion`.

  Missing `{{var}}` tokens are preserved verbatim (and logged via mu/log)."
  [prompt vars]
  (let [pname (:name prompt)
        body  (:prompt prompt)]
    (case (:type prompt)
      "text"
      (substitute-vars body vars pname)

      "chat"
      (let [expanded (expand-placeholders body vars pname)]
        (mapv (fn [msg]
                (-> (if (and (map? msg) (string? (:content msg)))
                      (update msg :content substitute-vars vars pname)
                      msg)
                    keywordize-role))
              expanded)))))

;; ============================================================================
;; Fetch
;; ============================================================================

(defn- selector
  "Cache-key selector — version takes precedence; otherwise label
  (default \"production\")."
  [{:keys [version label]}]
  (cond
    version [:version version]
    label   [:label label]
    :else   [:label "production"]))

(defn- fetch-prompt*
  "Uncached HTTP fetch."
  [config prompt-name {:keys [version label type]}]
  (let [query (cond-> {}
                version                       (assoc :version version)
                (and (not version) label)     (assoc :label label)
                (and (not version) (not label)) (assoc :label "production")
                type                          (assoc :type type))
        raw   (request* {:method  :get
                         :path    (str "/api/public/v2/prompts/" prompt-name)
                         :config  config
                         :query   query
                         :context {:prompt-name prompt-name
                                   :version     version
                                   :label       (or label
                                                    (when-not version "production"))}})]
    (normalize-prompt raw)))

(defn get-prompt
  "Fetch a Langfuse prompt by name. Returns the parsed prompt map (with
  `:commit-message` rather than camelCase `:commitMessage`).

  Options:
    :version       Specific version (int). Mutually exclusive with :label.
    :label         Label (e.g. \"production\", \"staging\"). Defaults to \"production\".
    :type          Optional \"chat\"/\"text\" type hint forwarded to Langfuse.
    :cache?        Per-call override of the cache flag in config.
    :config        Map with :public-key/:secret-key/:host (else env vars)."
  ([prompt-name] (get-prompt prompt-name {}))
  ([prompt-name opts]
   (let [config     (resolve-config (:config opts))
         sel        (selector opts)
         use-cache? (if (contains? opts :cache?) (:cache? opts) (:cache? config))
         k          (cache-key config prompt-name sel)]
     (or (when use-cache? (cache-get k))
         (let [prompt (fetch-prompt* config prompt-name opts)]
           (when use-cache?
             (cache-put! k prompt (:cache-ttl-ms config)))
           prompt)))))

(defn render
  "Convenience: `get-prompt` + `compile-prompt` in one call."
  ([prompt-name vars] (render prompt-name vars {}))
  ([prompt-name vars opts]
   (compile-prompt (get-prompt prompt-name opts) vars)))

;; ============================================================================
;; Write (create / list / versions / labels)
;; ============================================================================

(defn- validate-spec! [spec]
  (when-not (m/validate schemas/CreatePromptSpec spec)
    (throw (errors/invalid-request
            "Invalid Langfuse prompt spec"
            :request spec
            :errors  (me/humanize (m/explain schemas/CreatePromptSpec spec))))))

(defn create-prompt
  "Create a new Langfuse prompt (a new version is added if `:name` already
  exists; Langfuse prompts are immutable + versioned).

  Required: :name :type :prompt
  Optional: :labels :tags :config :commit-message

  Returns the parsed prompt map and invalidates any cached entries for
  this name."
  ([spec] (create-prompt spec {}))
  ([spec opts]
   (validate-spec! spec)
   (let [config (resolve-config (:config opts))
         body   (denormalize-spec spec)
         raw    (request* {:method  :post
                           :path    "/api/public/v2/prompts"
                           :config  config
                           :body    body
                           :context {:prompt-name (:name spec)}})]
     (invalidate! (:name spec) {:config (:config opts)})
     (normalize-prompt raw))))

(defn list-prompts
  "List prompts for the resolved project.

  Optional keys (all sent as query params, camelCased where Langfuse expects):
    :page :limit :name :tag :label :from-updated-at :to-updated-at
    :config (Langfuse credentials map)."
  ([] (list-prompts {}))
  ([opts]
   (let [config (resolve-config (:config opts))
         q      (-> opts (dissoc :config))
         q      (cond-> q
                  (:from-updated-at q) (-> (assoc :fromUpdatedAt (:from-updated-at q))
                                           (dissoc :from-updated-at))
                  (:to-updated-at q)   (-> (assoc :toUpdatedAt (:to-updated-at q))
                                           (dissoc :to-updated-at)))]
     (request* {:method  :get
                :path    "/api/public/v2/prompts"
                :config  config
                :query   q
                :context {}}))))

(defn get-prompt-versions
  "List every version on record for a given prompt name. Bypasses the
  local prompt cache."
  ([prompt-name] (get-prompt-versions prompt-name {}))
  ([prompt-name opts]
   (list-prompts (merge opts {:name prompt-name}))))

(defn update-prompt-labels
  "Replace the label set on a specific prompt version via
  `PATCH /api/public/v2/prompts/{name}/versions/{version}` with body
  `{\"newLabels\": [...]}`. Invalidates cached entries for this name."
  ([prompt-name version labels]
   (update-prompt-labels prompt-name version labels {}))
  ([prompt-name version labels opts]
   (let [config (resolve-config (:config opts))
         path   (str "/api/public/v2/prompts/" prompt-name "/versions/" version)
         raw    (request* {:method  :patch
                           :path    path
                           :config  config
                           :body    {:newLabels (vec labels)}
                           :context {:prompt-name prompt-name :version version}})]
     (invalidate! prompt-name {:config (:config opts)})
     (normalize-prompt raw))))
