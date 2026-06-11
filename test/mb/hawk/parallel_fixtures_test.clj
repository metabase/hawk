(ns mb.hawk.parallel-fixtures-test
  (:require
   [clojure.test :refer :all]
   [mb.hawk.core :as hawk]))

(deftest assert-test-is-not-parallel-works-inside-fixtures-test
  (testing "asssert-test-is-not-parallel should work inside :each fixtures (#26)"
    (letfn [(run-test [symb]
              (with-open [w (java.io.StringWriter.)]
                (binding [*test-out* w]
                  (hawk/run-tests [(requiring-resolve symb)]))))]
      (testing "single-threaded test should be ok"
        (is (=? {:test 1, :pass 1, :fail 0, :error 0, :single-threaded 1}
                (run-test 'hawk.test-ns.parallel-test/synchronized-test))))
      (testing "parallel test should fail because of parallel-unsafe fixture"
        (is (=? {:test 1, :pass 1, :fail 0, :error 1, :parallel 1}
                (run-test 'hawk.test-ns.parallel-test/parallel-test)))))))
