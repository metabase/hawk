(ns ^:hawk.tests/skip mb.eftest.test-ns.single-failing-test
  (:require
   [clojure.test :refer :all]))

(deftest single-failing-test
  (is (= 1 2)))
