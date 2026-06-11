(ns mb.hawk.parallel-fixtures-test
  (:require
   [clojure.test :refer :all]
   [mb.hawk.core :as hawk]
   [mb.hawk.parallel :as parallel]))

(defn- disallowed-parallel-function []
  (parallel/assert-test-is-not-parallel "disallowed-parallel-function"))

(use-fixtures :each (fn [thunk]
                      (disallowed-parallel-function)
                      (thunk)))

;; we'll make this test `^:parallel` for testing purposes. When it runs normally that's fine, it won't do anything
;; interesting.
(deftest test-test
  (is (= 1 1)))

(deftest assert-test-is-not-parallel-works-inside-fixtures-test
  (testing "asssert-test-is-not-parallel should work inside :each fixtures (#26)"
    (testing "should succeed when test is NOT parallel"
      (is (=? {:test 1, :pass 1, :fail 0, :error 0, :single-threaded 1}
              (hawk/run-tests [#'test-test]))))
    (testing "should error when test IS parallel"
      (try
        (alter-meta! #'test-test assoc :parallel true)
        (is (=? {:test 1, :pass 1, :fail 0, :error 1, :parallel 1}
                (hawk/run-tests [#'test-test])))
        (finally
          (alter-meta! #'test-test dissoc :parallel))))))
