(ns ^:parallel mb.hawk.parallel-test
  (:require
   [clojure.test :refer :all]
   [mb.hawk.parallel :as parallel]))

(deftest ns-parallel-test
  (is parallel/*parallel?*))

(deftest ^{:parallel false} var-not-parallel-test
  (is (not parallel/*parallel?*)))
