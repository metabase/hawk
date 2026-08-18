(ns ^:parallel mb.hawk.parallel-test
  (:require
   [clojure.test :refer :all]
   [mb.hawk.parallel :as parallel]))

(deftest ns-parallel-test
  (is parallel/*parallel?*))

(deftest ^:synchronized var-not-parallel-test
  (is (not parallel/*parallel?*)))

(deftest parallel-metadata-precedence-test
  (letfn [(test-var [test-metadata namespace-metadata]
            (with-meta (fn [])
              (assoc test-metadata :ns (with-meta 'test-namespace namespace-metadata))))]
    (are [test-metadata namespace-metadata expected]
         (= expected (parallel/parallel? (test-var test-metadata namespace-metadata)))
      {}                    {}                     nil
      {:synchronized true}  {}                     false
      {}                    {:synchronized true}   false
      {:synchronized true}  {:parallel true}       false
      {:parallel true}      {:synchronized true}   true
      {:parallel false}     {:parallel true}       false
      {:parallel true
       :synchronized true}  {}                     true)))
