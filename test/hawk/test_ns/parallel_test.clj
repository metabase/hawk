(ns ^:hawk.tests/skip hawk.test-ns.parallel-test
  (:require
   [clojure.test :refer :all]
   [mb.hawk.parallel :as parallel]))

(defn- disallowed-parallel-function []
  (parallel/assert-test-is-not-parallel "disallowed-parallel-function"))

(use-fixtures :each (fn [thunk]
                      (disallowed-parallel-function)
                      (thunk)))

(deftest synchronized-test
  (is (= 1 1)))

(deftest ^:parallel parallel-test
  (is (= 1 1)))
