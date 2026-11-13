(ns mb.hawk.junit
  (:require
   [clojure.test :as t]
   [mb.eftest.report :as eftest.report]
   [mb.hawk.junit.write :as write]))

(set! *warn-on-reflection* true)

;; Var-less `:error`s (fixture-init throws, namespace load/compile errors) belong to no test var, so they can't be
;; attached to a namespace's `<testcase>` output. We collect them here across the run and hand the batch to
;; `write/write-var-less-errors!` at `:summary`. See `mb.hawk.junit.write`.
(defonce ^:private var-less-errors (atom []))

(def ^:private initial-running-state
  {:namespaces #{}
   :vars       #{}
   :finished?  false})

(defonce ^:private running (atom initial-running-state))

(defmulti ^:private handle-event!*
  {:arglists '([event])}
  :type)

(defn handle-event!
  "Write JUnit output for a `clojure.test` event such as success or failure."
  [{test-var :var, :as event}]
  (let [test-var (or test-var
                     (when (seq t/*testing-vars*)
                       (last t/*testing-vars*)))
        event    (merge
                  {:var test-var}
                  event
                  (when test-var
                    {:ns (:ns (meta test-var))}))]
    (try
      (handle-event!* event)
      (catch Throwable e
        (throw (ex-info (str "Error handling event: " (ex-message e))
                        {:event event}
                        e))))))

;; for unknown event types (e.g. `:clojure.test.check.clojure-test/trial`) just ignore them.
(defmethod handle-event!* :default
  [_])

(defmethod handle-event!* :begin-test-run
  [_]
  (write/clean-output-dir!)
  (reset! var-less-errors [])
  (reset! running initial-running-state)
  (write/create-thread-pool!))

(defmethod handle-event!* :summary
  [_]
  (swap! running assoc :finished? true)
  (write/write-var-less-errors! @var-less-errors)
  (write/wait-for-writes-to-finish))

(defmethod handle-event!* :begin-test-ns
  [{test-ns :ns}]
  (alter-meta!
   test-ns assoc ::context
   {:start-time-ms   (System/currentTimeMillis)
    :timestamp       (java.time.OffsetDateTime/now)
    :test-count      0
    :error-count     0
    :failure-count   0
    :results         []})
  (swap! running update :namespaces conj test-ns))

(defmethod handle-event!* :end-test-ns
  [{test-ns :ns, :as event}]
  (let [context (::context (meta test-ns))
        result  (merge
                 event
                 context
                 {:duration-ms (- (System/currentTimeMillis) (:start-time-ms context))})]
    (swap! running update :namespaces disj test-ns)
    (write/write-ns-result! result)))

(defmethod handle-event!* :begin-test-var
  [{test-var :var}]
  (alter-meta!
   test-var assoc ::context
   {:start-time-ms   (System/currentTimeMillis)
    :assertion-count 0
    :results         []})
  (swap! running update :vars conj test-var))

(defmethod handle-event!* :end-test-var
  [{test-ns :ns, test-var :var, :as event}]
  (let [context (::context (meta test-var))
        result  (merge
                 event
                 context
                 {:duration-ms (- (System/currentTimeMillis) (:start-time-ms context))})]
    (alter-meta! test-ns update-in [::context :results] conj result)
    (swap! running update :vars disj test-var)))

(defn- inc-ns-test-counts! [{test-ns :ns, :as _event} & ks]
  (alter-meta! test-ns update ::context (fn [context]
                                          (reduce
                                           (fn [context k]
                                             (update context k inc))
                                           context
                                           ks))))

(defn- record-assertion-result! [{test-var :var, :as event}]
  (let [event (assoc event :testing-contexts (vec t/*testing-contexts*))]
    (alter-meta! test-var update ::context
                 (fn [context]
                   (some-> context
                           (update :assertion-count inc)
                           (update :results conj event))))))

(defmethod handle-event!* :pass
  [event]
  (inc-ns-test-counts! event :test-count)
  (record-assertion-result! event))

(defmethod handle-event!* :fail
  [event]
  (inc-ns-test-counts! event :test-count :failure-count)
  (record-assertion-result! event))

(defmethod handle-event!* :error
  [{test-var :var, :as event}]
  (if test-var
    (do
      (inc-ns-test-counts! event :test-count :error-count)
      (record-assertion-result! event))
    ;; Some `:error` events happen because of fixture-initialization throws (or namespace load/compile errors) and
    ;; have no associated var/namespace. `clojure.test` still counts them toward the run's error total (and thus the
    ;; exit code), so surface them via a separate JUnit file rather than dropping them. `*testing-path*` gives the
    ;; namespace and fixture scope when known, purely for a readable name. See `mb.hawk.junit.write`.
    (swap! var-less-errors conj
           (assoc event :testing-path eftest.report/*testing-path*))))

(defn- duration-since [{:keys [start-time-ms]} now-ms]
  (if start-time-ms
    (- now-ms start-time-ms)
    0))

(defn- assertion-counts [var-results]
  (let [types (map :type (mapcat :results var-results))]
    {:test-count    (count (filter #{:pass :fail :error :timeout} types))
     :failure-count (count (filter #{:fail} types))
     :error-count   (count (filter #{:error :timeout} types))}))

(defn- timed-out-var-result [test-var now-ms]
  (let [context     (::context (meta test-var))
        duration-ms (duration-since context now-ms)]
    (-> context
        (assoc :ns          (:ns (meta test-var))
               :var         test-var
               :duration-ms duration-ms)
        (update :results (fnil conj []) {:type :timeout, :duration-ms duration-ms}))))

(defn- partial-ns-result [test-ns running-vars now-ms]
  (let [context   (::context (meta test-ns))
        finished  (:results context)
        done      (into #{} (map :var) finished)
        timed-out (->> running-vars
                       (filter #(= test-ns (:ns (meta %))))
                       (remove done)
                       (sort-by symbol)
                       (mapv #(timed-out-var-result % now-ms)))
        fixture?  (empty? timed-out)
        results   (into finished timed-out)]
    (merge context
           (cond-> (assertion-counts results)
             fixture? (-> (update :test-count inc)
                          (update :error-count inc)))
           {:ns                    test-ns
            :duration-ms           (duration-since context now-ms)
            :results               results
            :timed-out-in-fixture? fixture?})))

(defn- try-write! [description f]
  (try
    (f)
    (catch Throwable e
      (binding [*out* *err*]
        (println (format "hawk: could not write %s on shutdown: %s" description (ex-message e)))))))

(defn write-partial-results!
  "Write the JUnit output for a test run that has not finished, and the var-less errors collected so far. Each
  running namespace gets its file with its finished vars, and each running var as a `<testcase>` with a \"timed out\"
  error. A running namespace with no running var hung in a fixture, so it gets one `<testcase>` with no `classname`
  and a \"timed out\" error. Does nothing after `:summary`. Called by the shutdown hook."
  []
  (when-not (:finished? @running)
    (try-write! "queued namespace results" #(write/wait-for-writes-to-finish 2))
    (let [{:keys [namespaces vars]} @running
          now-ms                    (System/currentTimeMillis)]
      (doseq [test-ns (sort-by ns-name namespaces)
              :when   (::context (meta test-ns))]
        (try-write! (str "partial results for " (ns-name test-ns))
                    #(write/write-ns-result-now! (partial-ns-result test-ns vars now-ms))))
      (try-write! "var-less errors" #(write/write-var-less-errors! @var-less-errors)))))

(defonce ^:private shutdown-hook (atom nil))

(defn install-shutdown-hook!
  "Install a JVM shutdown hook that calls [[write-partial-results!]], so a test run killed by SIGTERM or SIGINT (for
  example by a CI timeout) still writes JUnit output for the tests that were running. Installs the hook only once."
  []
  (when-not @shutdown-hook
    (let [hook (Thread. ^Runnable write-partial-results! "hawk JUnit shutdown writer")]
      (when (compare-and-set! shutdown-hook nil hook)
        (.addShutdownHook (Runtime/getRuntime) hook)))))
