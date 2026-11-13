(ns mb.hawk.junit
  (:require
   [clojure.test :as t]
   [mb.eftest.report :as eftest.report]
   [mb.hawk.junit.write :as write])
  (:import
   (java.util.concurrent TimeUnit)
   (java.util.concurrent.locks ReentrantReadWriteLock)))

(set! *warn-on-reflection* true)

;; Var-less `:error`s (fixture-init throws, namespace load/compile errors) belong to no test var, so they can't be
;; attached to a namespace's `<testcase>` output. We collect them here across the run and hand the batch to
;; `write/write-var-less-errors!` at `:summary`. See `mb.hawk.junit.write`.
(defonce ^:private var-less-errors (atom []))

(def ^:private initial-running-state
  {:namespaces #{}
   :finished?  false})

(defonce ^:private running (atom initial-running-state))

(defonce ^:private ^ReentrantReadWriteLock event-lock (ReentrantReadWriteLock.))

(defonce ^:private stopped? (atom false))

(def ^:private stop-events-timeout-ms 1000)

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
                    {:ns (:ns (meta test-var))}))
        lock     (.readLock event-lock)]
    (.lock lock)
    (try
      (when-not @stopped?
        (handle-event!* event))
      (catch Throwable e
        (throw (ex-info (str "Error handling event: " (ex-message e))
                        {:event event}
                        e)))
      (finally
        (.unlock lock)))))

;; for unknown event types (e.g. `:clojure.test.check.clojure-test/trial`) just ignore them.
(defmethod handle-event!* :default
  [_])

(declare install-shutdown-hook!)

(defmethod handle-event!* :begin-test-run
  [_]
  (install-shutdown-hook!)
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
    :results         []
    :running-vars    #{}})
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
  [{test-ns :ns, test-var :var}]
  (alter-meta!
   test-var assoc ::context
   {:start-time-ms   (System/currentTimeMillis)
    :assertion-count 0
    :results         []})
  (alter-meta! test-ns update-in [::context :running-vars] (fnil conj #{}) test-var))

(defmethod handle-event!* :end-test-var
  [{test-ns :ns, test-var :var, :as event}]
  (let [context (::context (meta test-var))
        result  (merge
                 event
                 context
                 {:duration-ms (- (System/currentTimeMillis) (:start-time-ms context))})]
    (alter-meta! test-ns update ::context #(-> %
                                                (update :results conj result)
                                                (update :running-vars disj test-var)))))

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

(defn- timed-out-var-result [test-var now-ms]
  (let [context     (::context (meta test-var))
        duration-ms (- now-ms (:start-time-ms context))]
    (-> context
        (assoc :ns          (:ns (meta test-var))
               :var         test-var
               :duration-ms duration-ms)
        (update :results conj {:type :timeout, :duration-ms duration-ms}))))

(defn- partial-ns-result [test-ns now-ms]
  (let [context      (::context (meta test-ns))
        timed-out    (->> (:running-vars context)
                          (sort-by symbol)
                          (mapv #(timed-out-var-result % now-ms)))
        outside-var? (empty? timed-out)
        errors       (cond-> (count timed-out) outside-var? inc)]
    (-> context
        (update :results into timed-out)
        (update :test-count + errors)
        (update :error-count + errors)
        (assoc :ns                     test-ns
               :duration-ms            (- now-ms (:start-time-ms context))
               :timed-out-outside-var? outside-var?))))

(defn- try-write! [description f]
  (try
    (f)
    (catch Throwable e
      (binding [*out* *err*]
        (println (format "hawk: could not write %s on shutdown: %s" description (ex-message e)))))))

(defn- stop-events!
  "Drop all later events, then wait up to `stop-events-timeout-ms` for the event handlers in progress to finish."
  []
  (reset! stopped? true)
  (let [lock (.writeLock event-lock)]
    (if (.tryLock lock stop-events-timeout-ms TimeUnit/MILLISECONDS)
      (.unlock lock)
      (binding [*out* *err*]
        (println (format "hawk: event handlers did not finish in %d ms on shutdown; writing JUnit output anyway"
                         stop-events-timeout-ms))))))

(defn write-partial-results!
  "Write the JUnit output for a test run that has not finished, and the var-less errors collected so far. Each
  running namespace gets its file with its finished vars, and each running var as a `<testcase>` with a \"timed out\"
  error. A running namespace with no running var hung outside a test var (for example in a fixture), so it gets one
  `<testcase>` with no `classname` and a \"timed out\" error. Stops event handling first, so nothing changes the
  state while it writes. Does nothing after `:summary`. Called by the shutdown hook."
  []
  (stop-events!)
  (when-not (:finished? @running)
    (try-write! "queued namespace results" #(write/wait-for-writes-to-finish 2))
    (let [now-ms (System/currentTimeMillis)]
      (doseq [test-ns (sort-by ns-name (:namespaces @running))]
        (try-write! (str "partial results for " (ns-name test-ns))
                    #(write/write-ns-result-now! (partial-ns-result test-ns now-ms))))
      (try-write! "var-less errors" #(write/write-var-less-errors! @var-less-errors)))))

(defn- add-shutdown-hook! ^Thread []
  (doto (Thread. ^Runnable write-partial-results! "hawk JUnit shutdown writer")
    (->> (.addShutdownHook (Runtime/getRuntime)))))

(defonce ^:private shutdown-hook (delay (add-shutdown-hook!)))

(defn- install-shutdown-hook!
  "Install a JVM shutdown hook that calls [[write-partial-results!]], so a test run killed by SIGTERM or SIGINT (for
  example by a CI timeout) still writes JUnit output for the tests that were running. Installs the hook only once."
  []
  @shutdown-hook)
