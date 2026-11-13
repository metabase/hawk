(ns mb.hawk.junit-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer :all]
   [clojure.xml :as xml]
   [mb.hawk.junit :as hawk.junit]
   [mb.hawk.junit.write :as write])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)
   (java.util.concurrent Executors ExecutorService TimeUnit)
   (org.apache.commons.io FileUtils)))

(set! *warn-on-reflection* true)

(defn- temp-dir ^java.io.File []
  (.toFile (Files/createTempDirectory "hawk-junit-test" (make-array FileAttribute 0))))

(defn- fake-ns [ns-symb var-symbs]
  (remove-ns ns-symb)
  (let [new-ns (create-ns ns-symb)]
    [new-ns (mapv #(intern new-ns (with-meta % {:test (fn [])})) var-symbs)]))

(defn- do-with-isolated-junit-state [f]
  (let [dir (temp-dir)]
    (try
      (with-redefs [write/output-dir           (str dir)
                    write/thread-pool          (atom nil)
                    hawk.junit/running         (atom @#'hawk.junit/initial-running-state)
                    hawk.junit/stopped?        (atom false)
                    hawk.junit/var-less-errors (atom [])]
        (f dir))
      (finally
        (FileUtils/deleteDirectory dir)))))

(defn- parse-file [^java.io.File dir filename]
  (let [file (io/file dir filename)]
    (when (.exists file)
      (xml/parse file))))

(defn- elements [tag node]
  (filter #(= tag (:tag %)) (:content node)))

(defn- testcases [suite]
  (elements :testcase suite))

(defn- event! [event]
  (binding [*testing-vars* (list)]
    (hawk.junit/handle-event! event)))

(defn- fail-event [a-var message]
  {:type :fail, :var a-var, :message message, :expected 1, :actual 2, :file "fake.clj", :line 1})

(deftest write-partial-results-test
  (let [[ns-a [finished-var hung-var]] (fake-ns 'mb.hawk.junit-test.ns-a '[finished-test hung-test])
        [ns-b _]                       (fake-ns 'mb.hawk.junit-test.ns-b [])]
    (try
      (do-with-isolated-junit-state
       (fn [dir]
         (event! {:type :begin-test-ns, :ns ns-a})
         (event! {:type :begin-test-var, :var finished-var})
         (event! {:type :pass, :var finished-var})
         (event! {:type :end-test-var, :var finished-var})
         (event! {:type :begin-test-var, :var hung-var})
         (event! (fail-event hung-var "failed before the hang"))
         (event! {:type :begin-test-ns, :ns ns-b})
         (hawk.junit/write-partial-results!)
         (testing "no separate timeout file is written"
           (is (nil? (parse-file dir "mb_hawk_timeouts.xml"))))
         (let [suite                    (parse-file dir "mb.hawk.junit_test.ns_a.xml")
               [finished-case hung-case] (testcases suite)]
           (testing "the running namespace file has the finished var and the running var"
             (is (= "mb.hawk.junit-test.ns-a" (get-in suite [:attrs :name])))
             (is (= ["finished-test" "hung-test"] (map (comp :name :attrs) (testcases suite))))
             (is (empty? (:content finished-case))))
           (testing "the counts include the assertions of the running var and its timeout error"
             (is (= {:tests "3", :failures "1", :errors "1"}
                    (select-keys (:attrs suite) [:tests :failures :errors]))))
           (testing "the running var is named by classname and name, with its assertions and a timed out error"
             (is (= "mb.hawk.junit-test.ns-a" (get-in hung-case [:attrs :classname])))
             (is (= "1" (get-in hung-case [:attrs :assertions])))
             (is (= "failed before the hang" (-> (elements :failure hung-case) first :attrs :message)))
             (is (= {:message "timed out", :type "mb.hawk.Timeout"}
                    (-> (elements :error hung-case) first :attrs)))))
         (let [suite          (parse-file dir "mb.hawk.junit_test.ns_b.xml")
               [fixture-case] (testcases suite)]
           (testing "a namespace with no running var gets one testcase with no classname"
             (is (= 1 (count (testcases suite))))
             (is (= {:tests "1", :failures "0", :errors "1"}
                    (select-keys (:attrs suite) [:tests :failures :errors])))
             (is (not (contains? (:attrs fixture-case) :classname)))
             (is (= "timed out outside a test var (mb.hawk.junit-test.ns-b)" (get-in fixture-case [:attrs :name])))
             (is (= "timed out" (-> (elements :error fixture-case) first :attrs :message)))))))
      (finally
        (remove-ns (ns-name ns-a))
        (remove-ns (ns-name ns-b))))))

(deftest events-after-shutdown-hook-are-dropped-test
  (let [[fake [fake-var]] (fake-ns 'mb.hawk.junit-test.ns-c '[slow-test])]
    (try
      (do-with-isolated-junit-state
       (fn [dir]
         (event! {:type :begin-test-ns, :ns fake})
         (event! {:type :begin-test-var, :var fake-var})
         (hawk.junit/write-partial-results!)
         (event! {:type :end-test-var, :var fake-var})
         (event! {:type :end-test-ns, :ns fake})
         (testing "events after the hook do not change the running state"
           (is (= #{fake} (:namespaces @@#'hawk.junit/running)))
           (is (= #{fake-var} (get-in (meta fake) [::hawk.junit/context :running-vars]))))
         (testing "the file the hook wrote is not replaced"
           (is (= "timed out"
                  (-> (parse-file dir "mb.hawk.junit_test.ns_c.xml") testcases first
                      (->> (elements :error)) first :attrs :message))))))
      (finally
        (remove-ns (ns-name fake))))))

(defn- do-with-blocked-end-test-ns
  "Run `f` while a handler for `:end-test-ns` of `a-ns` is blocked in progress, holding the read lock."
  [a-ns f]
  (let [in-handler (promise)
        release    (promise)]
    (with-redefs [write/write-ns-result! (fn [_]
                                           (deliver in-handler true)
                                           @release)]
      (let [handler (future (event! {:type :end-test-ns, :ns a-ns}))]
        @in-handler
        (try
          (f release)
          (finally
            (deliver release true)
            @handler))))))

(deftest shutdown-hook-waits-for-handlers-in-progress-test
  (let [[fake [fake-var]] (fake-ns 'mb.hawk.junit-test.ns-j '[done-test])]
    (try
      (do-with-isolated-junit-state
       (fn [dir]
         (event! {:type :begin-test-ns, :ns fake})
         (event! {:type :begin-test-var, :var fake-var})
         (event! {:type :end-test-var, :var fake-var})
         (let [err             (java.io.StringWriter.)
               while-in-handler (atom nil)]
           (with-redefs [hawk.junit/stop-events-timeout-ms 10000]
             (do-with-blocked-end-test-ns
              fake
              (fn [release]
                (let [hook (binding [*err* err]
                             (future (hawk.junit/write-partial-results!)))]
                  (reset! while-in-handler (deref hook 200 ::waiting))
                  (deliver release true)
                  @hook))))
           (testing "the hook waits while a handler is in progress"
             (is (= ::waiting @while-in-handler)))
           (testing "the hook continues when the handler ends, without a timeout"
             (is (= "" (str err)))))
         (testing "the hook sees the state after the handler, so the namespace that ended gets no partial file"
           (is (empty? (:namespaces @@#'hawk.junit/running)))
           (is (nil? (parse-file dir "mb.hawk.junit_test.ns_j.xml"))))))
      (finally
        (remove-ns (ns-name fake))))))

(deftest shutdown-hook-writes-when-handlers-do-not-finish-test
  (let [[ns-done _]                (fake-ns 'mb.hawk.junit-test.ns-l [])
        [ns-running [running-var]] (fake-ns 'mb.hawk.junit-test.ns-m '[stuck-test])]
    (try
      (do-with-isolated-junit-state
       (fn [dir]
         (event! {:type :begin-test-ns, :ns ns-done})
         (event! {:type :begin-test-ns, :ns ns-running})
         (event! {:type :begin-test-var, :var running-var})
         (do-with-blocked-end-test-ns
          ns-done
          (fn [_]
            (with-redefs [hawk.junit/stop-events-timeout-ms 100]
              (binding [*err* (java.io.StringWriter.)]
                (hawk.junit/write-partial-results!)
                (testing "the hook says on stderr that it did not wait for all handlers"
                  (is (re-find #"event handlers did not finish in 100 ms" (str *err*))))))
            (testing "the hook still writes the running namespace"
              (is (some? (parse-file dir "mb.hawk.junit_test.ns_m.xml"))))))))
      (finally
        (remove-ns (ns-name ns-done))
        (remove-ns (ns-name ns-running))))))

(deftest summary-after-shutdown-hook-does-not-write-test
  (do-with-isolated-junit-state
   (fn [_]
     (let [calls (atom 0)]
       (with-redefs [write/write-var-less-errors! (fn [_] (swap! calls inc))]
         (hawk.junit/write-partial-results!)
         (event! {:type :summary}))
       (testing "a :summary after the hook is dropped, so the var-less errors are written once"
         (is (= 1 @calls)))))))

(deftest write-partial-results-after-summary-test
  (let [[fake [fake-var]] (fake-ns 'mb.hawk.junit-test.ns-d '[some-test])]
    (try
      (do-with-isolated-junit-state
       (fn [^java.io.File dir]
         (event! {:type :begin-test-ns, :ns fake})
         (event! {:type :begin-test-var, :var fake-var})
         (event! {:type :summary})
         (hawk.junit/write-partial-results!)
         (testing "after :summary the run is finished, so nothing is written"
           (is (empty? (.list dir))))))
      (finally
        (remove-ns (ns-name fake))))))

(deftest write-partial-results-writes-var-less-errors-test
  (do-with-isolated-junit-state
   (fn [dir]
     (event! {:type :error, :message "fixture boom", :actual (ex-info "boom" {})})
     (hawk.junit/write-partial-results!)
     (testing "var-less errors collected so far are written"
       (is (= 1 (count (testcases (parse-file dir "mb_hawk_var_less_errors.xml")))))))))

(deftest write-partial-results-continues-after-a-failed-write-test
  (let [[ns-e [var-e]] (fake-ns 'mb.hawk.junit-test.ns-e '[stuck-test])
        [ns-i [var-i]] (fake-ns 'mb.hawk.junit-test.ns-i '[other-stuck-test])
        write-now      write/write-ns-result-now!]
    (try
      (do-with-isolated-junit-state
       (fn [dir]
         (event! {:type :begin-test-ns, :ns ns-e})
         (event! {:type :begin-test-var, :var var-e})
         (event! {:type :begin-test-ns, :ns ns-i})
         (event! {:type :begin-test-var, :var var-i})
         (event! {:type :error, :message "fixture boom", :actual (ex-info "boom" {})})
         (binding [*err* (java.io.StringWriter.)]
           (with-redefs [write/write-ns-result-now! (fn [result]
                                                      (if (= ns-e (:ns result))
                                                        (throw (ex-info "disk full" {}))
                                                        (write-now result)))]
             (hawk.junit/write-partial-results!))
           (testing "the failure is reported on stderr"
             (is (re-find #"could not write partial results for mb.hawk.junit-test.ns-e on shutdown: disk full"
                          (str *err*)))))
         (testing "the other namespace file is still written"
           (is (some? (parse-file dir "mb.hawk.junit_test.ns_i.xml"))))
         (testing "the var-less errors are still written"
           (is (some? (parse-file dir "mb_hawk_var_less_errors.xml"))))))
      (finally
        (remove-ns (ns-name ns-e))
        (remove-ns (ns-name ns-i))))))

(deftest install-shutdown-hook-test
  (let [calls (atom 0)
        hook  (delay
                (swap! calls inc)
                (#'hawk.junit/add-shutdown-hook!))]
    (with-redefs [hawk.junit/shutdown-hook hook]
      (do-with-isolated-junit-state
       (fn [_]
         (try
           (event! {:type :begin-test-run})
           (event! {:type :begin-test-run})
           (testing "the first :begin-test-run installs the hook"
             (is (instance? Thread @hook)))
           (testing "a second run does not install another hook"
             (is (= 1 @calls)))
           (finally
             (.removeShutdownHook (Runtime/getRuntime) ^Thread @hook)
             (write/wait-for-writes-to-finish))))))))

(defn- fake-ns-result [fake]
  {:ns fake, :duration-ms 0, :timestamp "now", :test-count 0, :error-count 0, :failure-count 0, :results []})

(deftest write-ns-result-without-pool-test
  (let [[fake _] (fake-ns 'mb.hawk.junit-test.ns-f [])]
    (try
      (do-with-isolated-junit-state
       (fn [dir]
         (event! {:type :begin-test-ns, :ns fake})
         (event! {:type :end-test-ns, :ns fake})
         (testing "with no pool, nothing is written"
           (is (nil? (parse-file dir "mb.hawk.junit_test.ns_f.xml"))))
         (testing "it has left the running set"
           (is (empty? (:namespaces @@#'hawk.junit/running))))))
      (finally
        (remove-ns (ns-name fake))))))

(deftest write-ns-result-rejected-by-pool-test
  (let [[fake _] (fake-ns 'mb.hawk.junit-test.ns-g [])]
    (try
      (do-with-isolated-junit-state
       (fn [dir]
         (let [pool (doto (Executors/newCachedThreadPool) (.shutdown))]
           (reset! @#'write/thread-pool pool)
           (testing "a pool that rejects the task is ignored, and nothing is written"
             (is (nil? (write/write-ns-result! (fake-ns-result fake))))
             (is (nil? (parse-file dir "mb.hawk.junit_test.ns_g.xml")))))))
      (finally
        (remove-ns (ns-name fake))))))

(deftest wait-for-writes-to-finish-timeout-test
  (do-with-isolated-junit-state
   (fn [_]
     (let [^ExecutorService pool (Executors/newCachedThreadPool)
           started               (promise)]
       (try
         (.submit pool ^Runnable (fn []
                                   (deliver started true)
                                   (Thread/sleep 10000)))
         @started
         (reset! @#'write/thread-pool pool)
         (let [start-ms (System/currentTimeMillis)]
           (write/wait-for-writes-to-finish 1)
           (testing "it stops waiting after the timeout"
             (is (< (- (System/currentTimeMillis) start-ms) 5000)))
           (testing "it clears the pool"
             (is (nil? @@#'write/thread-pool))))
         (finally
           (.shutdownNow pool)
           (.awaitTermination pool 5 TimeUnit/SECONDS)))))))

(deftest running-state-is-safe-in-parallel-test
  (let [var-symbs           (mapv #(symbol (str "parallel-test-" %)) (range 200))
        [fake fake-vars]    (fake-ns 'mb.hawk.junit-test.ns-h var-symbs)
        [ended still-going] (split-at 150 fake-vars)]
    (try
      (do-with-isolated-junit-state
       (fn [_]
         (event! {:type :begin-test-ns, :ns fake})
         (->> fake-vars
              (map (fn [v]
                     (future
                       (event! {:type :begin-test-var, :var v})
                       (event! {:type :pass, :var v}))))
              doall
              (run! deref))
         (->> ended
              (map (fn [v]
                     (future
                       (event! {:type :end-test-var, :var v}))))
              doall
              (run! deref))
         (testing "every var that has not ended is still running, and no other var is"
           (is (= (set still-going) (get-in (meta fake) [::hawk.junit/context :running-vars]))))
         (testing "every ended var is in the namespace results"
           (is (= (set ended) (set (map :var (get-in (meta fake) [::hawk.junit/context :results]))))))))
      (finally
        (remove-ns (ns-name fake))))))
