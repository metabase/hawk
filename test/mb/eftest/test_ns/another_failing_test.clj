(ns ^:hawk.tests/skip mb.eftest.test-ns.another-failing-test
  (:require
   [clojure.test :refer :all]))

(deftest another-failing-test
  (is (= 3 4)))
