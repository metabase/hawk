(ns mb.hawk.reporter.junit
  (:require
   [clojure.test :as t]
   [mb.hawk.reporter.interface :as hawk.reporter]
   [mb.hawk.reporter.junit.write :as write]))

(hawk.reporter/register-reporter! :hawk/junit)

(defmacro ^:private with-error-handling
  [event & body]
  `(try
    ~@body
    (catch Throwable e#
      (throw (ex-info (str "Error handling event: " (ex-message e#))
                      {:event ~event}
                      e#)))))

(defn- with-var-and-ns
  [{test-var :var, :as event}]
  (let [test-var (or test-var
                     (when (seq t/*testing-vars*)
                       (last t/*testing-vars*)))]
    (merge
     {:var test-var}
     event
     (when test-var
       {:ns (:ns (meta test-var))}))))

(defn handle-event!
  "Write JUnit output for a `clojure.test` event such as success or failure."
  [thunk {test-var :var, :as event}]
  (let [test-var (or test-var
                     (when (seq t/*testing-vars*)
                       (last t/*testing-vars*)))
        event    (merge
                  {:var test-var}
                  event
                  (when test-var
                    {:ns (:ns (meta test-var))}))]
    (try
     (thunk event)
     (catch Throwable e
       (throw (ex-info (str "Error handling event: " (ex-message e))
                       {:event event}
                       e))))))

;; for unknown event types (e.g. `:clojure.test.check.clojure-test/trial`) just ignore them.
(defmethod hawk.reporter/handle-event [:hawk/junit :default]
  [_ _])

(defmethod hawk.reporter/handle-event [:hawk/junit :begin-test-run]
  [_ event]
  (with-error-handling event
    (write/clean-output-dir!)
    (write/create-thread-pool!)))

(defmethod hawk.reporter/handle-event [:hawk/junit :summary]
  [_ event]
  (with-error-handling event
    (write/wait-for-writes-to-finish)))

(defmethod hawk.reporter/handle-event [:hawk/junit :begin-test-ns]
  [_reporter event]
  (let [event         (with-var-and-ns event)
        {test-ns :ns} event]
    (with-error-handling event
      (alter-meta!
       test-ns assoc ::context
       {:start-time-ms   (System/currentTimeMillis)
        :timestamp       (java.time.OffsetDateTime/now)
        :test-count      0
        :error-count     0
        :failure-count   0
        :results         []}))))

(defmethod hawk.reporter/handle-event [:hawk/junit :end-test-ns]
  [_ event]
  (let [event         (with-var-and-ns event)
        {test-ns :ns} event
        context       (::context (meta test-ns))]
    (with-error-handling event
      (write/write-ns-result! (merge
                               event
                               context
                               {:duration-ms (- (System/currentTimeMillis) (:start-time-ms context))})))))

(defmethod hawk.reporter/handle-event [:hawk/junit :begin-test-var]
  [_ event]
  (let [event           (with-var-and-ns event)
        {test-var :var} event]
    (alter-meta!
     test-var assoc ::context
     {:start-time-ms   (System/currentTimeMillis)
      :assertion-count 0
      :results         []})))

(defmethod hawk.reporter/handle-event [:hawk/junit :end-test-var]
  [_ {test-var :var :as event}]
  (let [event         (with-var-and-ns event)
        {test-ns :ns} event
        context       (::context (meta test-var))
        result        (merge
                       event
                       context
                       {:duration-ms (- (System/currentTimeMillis) (:start-time-ms context))})]
    (alter-meta! test-ns update-in [::context :results] conj result)))

(defn- inc-ns-test-counts! [{test-var :var :as _event} & ks]
  (alter-meta! (:ns (meta test-var)) update ::context (fn [context]
                                                       (reduce
                                                        (fn [context k]
                                                          (update context k inc))
                                                        context
                                                        ks))))

(defn- record-assertion-result! [{test-var :var :as event}]
  (let [event (assoc event :testing-contexts (vec t/*testing-contexts*))]
    (alter-meta! test-var update ::context
                 (fn [context]
                   (-> context
                       (update :assertion-count inc)
                       (update :results conj event))))))

(defmethod hawk.reporter/handle-event [:hawk/junit :pass]
  [_ event]
  (let [event (with-var-and-ns event)]
    (with-error-handling event
     (inc-ns-test-counts! event :test-count)
     (record-assertion-result! event))))

(defmethod hawk.reporter/handle-event [:hawk/junit :fail]
  [_ event]
  (let [event (with-var-and-ns event)]
    (with-error-handling event
     (inc-ns-test-counts! event :test-count :failure-count)
     (record-assertion-result! event))))

(defmethod hawk.reporter/handle-event [:hawk/junit :error]
  [_ event]
  (let [{test-var :var :as event} (with-var-and-ns event)]
    ;; some `:error` events happen because of errors in fixture initialization and don't have associated vars/namespaces
    (when test-var
     (with-error-handling event
       (inc-ns-test-counts! event :test-count :error-count)
       (record-assertion-result! event)))))
