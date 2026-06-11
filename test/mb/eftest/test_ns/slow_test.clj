(ns ^:hawk.tests/skip mb.eftest.test-ns.slow-test
  (:require
   [clojure.test :refer :all]))

(set! *warn-on-reflection* true)

(deftest a-slow-test
  (is (true? (do (Thread/sleep 10) true))))
