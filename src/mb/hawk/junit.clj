(ns mb.hawk.junit
  (:require
   [clojure.test :as t]
   [mb.eftest.report :as eftest.report]
   [mb.hawk.junit.write :as write]))

(set! *warn-on-reflection* true)

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
  (write/reset-var-less-errors!)
  (write/create-thread-pool!))

(defmethod handle-event!* :summary
  [_]
  (write/write-var-less-errors!)
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
    :results         []}))

(defmethod handle-event!* :end-test-ns
  [{test-ns :ns, :as event}]
  (let [context (::context (meta test-ns))
        result  (merge
                 event
                 context
                 {:duration-ms (- (System/currentTimeMillis) (:start-time-ms context))})]
    (write/write-ns-result! result)))

(defmethod handle-event!* :begin-test-var
  [{test-var :var}]
  (alter-meta!
   test-var assoc ::context
   {:start-time-ms   (System/currentTimeMillis)
    :assertion-count 0
    :results         []}))

(defmethod handle-event!* :end-test-var
  [{test-ns :ns, test-var :var, :as event}]
  (let [context (::context (meta test-var))
        result  (merge
                 event
                 context
                 {:duration-ms (- (System/currentTimeMillis) (:start-time-ms context))})]
    (alter-meta! test-ns update-in [::context :results] conj result)))

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
    (write/record-var-less-error!
     (assoc event :testing-path eftest.report/*testing-path*))))
