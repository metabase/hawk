(ns ^:hawk.tests/skip hawk.test-ns.hanging-test
  (:require
   [clojure.test :refer :all]))

(set! *warn-on-reflection* true)

(deftest hanging-test
  (is (= 1 1))
  (spit "hanging" "")
  (Thread/sleep 600000))
