(ns mb.hawk.junit-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [clojure.xml :as xml]
   [mb.hawk.junit :as junit])
  (:import
   (java.io File)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)
   (java.util.concurrent TimeUnit)
   (org.apache.commons.io FileUtils)))

(set! *warn-on-reflection* true)

(defn- absolute-classpath []
  (->> (str/split (System/getProperty "java.class.path") (re-pattern File/pathSeparator))
       (map #(.getAbsolutePath (io/file %)))
       (str/join File/pathSeparator)))

(defn- start-hawk
  "Start hawk in a new JVM in `dir` in `mode`, running only the tests in `a-ns`."
  ^Process [^File dir mode a-ns]
  (-> (ProcessBuilder. ^"[Ljava.lang.String;"
                       (into-array String
                                   [(str (System/getProperty "java.home") "/bin/java")
                                    "-cp" (absolute-classpath)
                                    "clojure.main"
                                    "-e" (pr-str `(do (require 'mb.hawk.core)
                                                      (mb.hawk.core/find-and-run-tests-cli
                                                       {:only '[~a-ns], :mode ~mode})))]))
      (.directory dir)
      (.redirectErrorStream true)
      (.redirectOutput (io/file dir "output.txt"))
      (.start)))

(defn- wait-for-file [^File file timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (.exists file)                             true
        (> (System/currentTimeMillis) deadline)    false
        :else                                      (do (Thread/sleep 100) (recur))))))

(defn- kill-hanging-test
  "Run the hanging test namespace in a new JVM in `mode`, send SIGTERM once the test hangs, and call `f` with the
  JUnit file for that namespace."
  [mode f]
  (let [dir     (.toFile (Files/createTempDirectory "hawk-junit-test" (make-array FileAttribute 0)))
        process (start-hawk dir mode 'hawk.test-ns.hanging-test)]
    (try
      (is (wait-for-file (io/file dir "hanging") 120000)
          "the test started to hang")
      (.destroy process)
      (is (.waitFor process 60 TimeUnit/SECONDS)
          "the JVM shut down")
      (f (io/file dir "target/junit/hawk.test_ns.hanging_test.xml") (io/file dir "output.txt"))
      (finally
        (.destroyForcibly process)
        (FileUtils/deleteDirectory dir)))))

(deftest sigterm-writes-running-tests-test
  (testing "a CI run killed by SIGTERM while a test hangs writes that test as still running"
    (kill-hanging-test
     :cli/ci
     (fn [^File file output]
       (is (.exists file) (slurp output))
       (when (.exists file)
         (let [[testcase] (filter #(= :testcase (:tag %)) (:content (xml/parse file)))
               [error]    (filter #(= :error (:tag %)) (:content testcase))]
           (is (= {:classname "hawk.test-ns.hanging-test", :name "hanging-test"}
                  (select-keys (:attrs testcase) [:classname :name])))
           (is (= "The test was still running when the JVM shut down" (get-in error [:attrs :message])))))))))

(deftest partial-ns-result-test
  (let [fake-ns  (create-ns 'mb.hawk.junit-test.fake)
        fake-var (intern fake-ns 'running-test)
        context  {:start-time-ms 0, :test-count 2, :error-count 0, :failure-count 0, :results [{:var 'finished}]}]
    (alter-meta! fake-ns assoc ::junit/context context)
    (alter-meta! fake-var assoc ::junit/context {:start-time-ms 500, :assertion-count 0, :results []})
    (try
      (testing "a running var is added as a result with a shutdown error"
        (let [result (#'junit/partial-ns-result fake-ns #{fake-var} 1000)]
          (is (= [3 1 false] ((juxt :test-count :error-count :running-outside-var?) result)))
          (is (= [:shutdown] (map :type (:results (last (:results result))))))))
      (testing "a namespace with no running var is running outside a test var"
        (let [result (#'junit/partial-ns-result fake-ns #{} 1000)]
          (is (= [3 1 true] ((juxt :test-count :error-count :running-outside-var?) result)))
          (is (= [{:var 'finished}] (:results result)))))
      (finally
        (remove-ns 'mb.hawk.junit-test.fake)))))
