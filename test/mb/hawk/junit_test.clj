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
        (testing "the running namespace gets a file with only its finished vars"
          (let [suite (parse-file dir "mb.hawk.junit_test.ns_a.xml")]
            (is (= "mb.hawk.junit-test.ns-a" (get-in suite [:attrs :name])))
            (is (= {:tests "1", :failures "0", :errors "0"}
                   (select-keys (:attrs suite) [:tests :failures :errors])))
            (is (= ["finished-test"] (map (comp :name :attrs) (testcases suite))))))
        (testing "a running namespace with no vars yet still gets a file"
          (is (some? (parse-file dir "mb.hawk.junit_test.ns_b.xml"))))
        (let [suite                     (parse-file dir "mb_hawk_timeouts.xml")
              [var-case fixture-case]   (testcases suite)]
          (testing "the timeout file has one testcase for the hung var and one for the namespace in a fixture"
            (is (= {:name "mb.hawk.timeouts", :tests "2", :errors "2", :failures "1"}
                   (:attrs suite)))
            (is (= 2 (count (testcases suite)))))
          (testing "the running var is named by classname and name, with its assertions and a timed out error"
            (is (= "mb.hawk.junit-test.ns-a" (get-in var-case [:attrs :classname])))
            (is (= "hung-test" (get-in var-case [:attrs :name])))
            (is (= "1" (get-in var-case [:attrs :assertions])))
            (is (= "failed before the hang" (-> (elements :failure var-case) first :attrs :message)))
            (is (= {:message "timed out", :type "mb.hawk.Timeout"}
                   (-> (elements :error var-case) first :attrs))))
          (testing "a namespace with no running var is a testcase with no classname"
            (is (not (contains? (:attrs fixture-case) :classname)))
            (is (= "timed out in :once fixture (mb.hawk.junit-test.ns-b)" (get-in fixture-case [:attrs :name])))
            (is (= "timed out" (-> (elements :error fixture-case) first :attrs :message)))))))
      (finally
        (remove-ns (ns-name ns-a))
        (remove-ns (ns-name ns-b))))))

(deftest write-partial-results-skips-vars-already-in-ns-results-test
  (let [[fake [fake-var]] (fake-ns 'mb.hawk.junit-test.ns-c '[done-test])]
    (try
      (do-with-isolated-junit-state
       (fn [dir]
        (event! {:type :begin-test-ns, :ns fake})
        (event! {:type :begin-test-var, :var fake-var})
        (event! {:type :end-test-var, :var fake-var})
        (swap! @#'hawk.junit/running update :vars conj fake-var)
        (hawk.junit/write-partial-results!)
        (testing "a var that is still in the running set but already finished is not written as timed out"
          (let [suite (parse-file dir "mb_hawk_timeouts.xml")]
            (is (= [nil] (map (comp :classname :attrs) (testcases suite))))))
        (testing "it is in the namespace file"
          (is (= ["done-test"] (map (comp :name :attrs) (testcases (parse-file dir "mb.hawk.junit_test.ns_c.xml"))))))))
      (finally
        (remove-ns (ns-name fake))))))

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
  (let [[fake [fake-var]] (fake-ns 'mb.hawk.junit-test.ns-e '[stuck-test])]
    (try
      (do-with-isolated-junit-state
       (fn [dir]
        (event! {:type :begin-test-ns, :ns fake})
        (event! {:type :begin-test-var, :var fake-var})
        (binding [*err* (java.io.StringWriter.)]
          (with-redefs [write/write-timeout-results! (fn [_] (throw (ex-info "disk full" {})))]
            (hawk.junit/write-partial-results!))
          (testing "the failure is reported on stderr"
            (is (re-find #"could not write timed out tests on shutdown: disk full" (str *err*)))))
        (testing "the namespace file is still written"
          (is (some? (parse-file dir "mb.hawk.junit_test.ns_e.xml"))))))
      (finally
        (remove-ns (ns-name fake))))))

(deftest install-shutdown-hook-test
  (let [hook (atom nil)]
    (with-redefs [hawk.junit/shutdown-hook hook]
      (hawk.junit/install-shutdown-hook!)
      (let [installed @hook]
        (try
          (testing "the hook is installed"
            (is (instance? Thread installed)))
          (testing "a second call does not install another hook"
            (hawk.junit/install-shutdown-hook!)
            (is (identical? installed @hook)))
          (finally
            (.removeShutdownHook (Runtime/getRuntime) ^Thread installed)))))))

(defn- fake-ns-result [fake]
  {:ns fake, :duration-ms 0, :timestamp "now", :test-count 0, :error-count 0, :failure-count 0, :results []})

(deftest write-ns-result-without-pool-test
  (let [[fake _] (fake-ns 'mb.hawk.junit-test.ns-f [])]
    (try
      (do-with-isolated-junit-state
       (fn [dir]
         (event! {:type :begin-test-ns, :ns fake})
         (event! {:type :end-test-ns, :ns fake})
         (testing "a namespace that ends after the shutdown hook stopped the pool is written in the current thread"
           (is (some? (parse-file dir "mb.hawk.junit_test.ns_f.xml"))))
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
           (write/write-ns-result! (fake-ns-result fake))
           (testing "a pool that rejects the task does not lose the namespace"
             (is (some? (parse-file dir "mb.hawk.junit_test.ns_g.xml")))))))
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
           (is (= (set still-going) (:vars @@#'hawk.junit/running))))
         (testing "every ended var is in the namespace results"
           (is (= (set ended) (set (map :var (get-in (meta fake) [::hawk.junit/context :results]))))))))
      (finally
        (remove-ns (ns-name fake))))))
