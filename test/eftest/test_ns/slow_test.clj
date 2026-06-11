(ns ^:hawk.tests/skip eftest.test-ns.slow-test
  (:require
   [clojure.test :refer :all]))

(deftest a-slow-test
  (is (true? (do (Thread/sleep 10) true))))
