(ns eftest.runner-test
  (:require
   [clojure.test :refer :all]
   [eftest.runner :as sut]
   [mb.hawk.core :as hawk.core]))

(defn- with-test-out-str* [f]
  (let [s (java.io.StringWriter.)]
    (binding [clojure.test/*test-out* s]
      (f))
    (-> (str s)
        (.replace "\u001B" "")
        (.replaceAll "\\[([0-9]{1};)?[0-9]{0,2}m" ""))))

(defmacro ^:private with-test-out-str [& body]
  `(with-test-out-str* (fn [] ~@body)))

(defn- test-run-tests
  ([test-locs]
   (test-run-tests test-locs {}))
  ([test-locs opts]
   (let [vars (hawk.core/find-tests test-locs {})
         ret  (promise)
         out  (with-test-out-str (deliver ret (sut/run-tests vars opts)))]
     {:output out
      :return @ret})))

(deftest test-reporting
  (let [out (:output (test-run-tests 'eftest.test-ns.single-failing-test))]
    (is (re-find #"FAIL in eftest.test-ns.single-failing-test/single-failing-test" out))
    (is (not (re-find #"IllegalArgumentException" out)))))

(deftest test-fail-fast
  (let [result (:return
                (test-run-tests
                 '[eftest.test-ns.single-failing-test
                   eftest.test-ns.another-failing-test]
                 {:fail-fast? true, :multithread? false}))]
    (is (= {:test 1 :fail 1} (select-keys result [:test :fail])))))

(deftest test-fail-multi
  (let [out (:output
             (test-run-tests
              '[eftest.test-ns.single-failing-test
                eftest.test-ns.another-failing-test]))]
    (println out)
    (is (re-find #"(?m)expected: 1\n  actual: 2" out))
    (is (re-find #"(?m)expected: 3\n  actual: 4" out))))

(deftest test-slow-test-report
  (testing "should fail with an accurate var location"
    (let [out (:output
                (test-run-tests ['eftest.test-ns.slow-test] {:test-warn-time 5}))]
      (is (re-find #"LONG TEST in eftest.test-ns.slow-test/a-slow-test\n" out)))))
