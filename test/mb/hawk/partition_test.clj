(ns mb.hawk.partition-test
  (:require
   [clojure.test :refer :all]
   [mb.hawk.core :as hawk]
   [mb.hawk.core-test]
   [mb.hawk.parallel-test]
   [mb.hawk.partition :as hawk.partition]
   [mb.hawk.speak-test]))

(defn- partition-tests* [num-partitions tests]
  (into (sorted-map)
        (map (fn [i]
               [i (hawk.partition/partition-tests
                   tests
                   {:partition/index i, :partition/total num-partitions})]))
        (range num-partitions)))

(deftest ^:parallel partition-tests-test
  (is (= '{0 [a/test b/test]
           1 [c/test]
           2 [d/test]}
         (partition-tests* 3 '[a/test b/test c/test d/test])))
  (is (= '{0 [a/test b/test]
           1 [c/test d/test]
           2 [e/test]}
         (partition-tests* 3 '[a/test b/test c/test d/test e/test]))))

(deftest ^:parallel partition-tests-evenly-test
  (testing "make sure we divide things roughly evenly"
    (is (= '{0 [n00/test n01/test n02/test]
             1 [n03/test n04/test n05/test]
             2 [n06/test n07/test]
             3 [n08/test n09/test n10/test]
             4 [n11/test n12/test]
             5 [n13/test n14/test n15/test]
             6 [n16/test n17/test n18/test]
             7 [n19/test n20/test]
             8 [n21/test n22/test n23/test]
             9 [n24/test n25/test]}
           (partition-tests* 10 (map #(symbol (format "n%02d/test" %)) (range 26)))))
    ;; ideally the split happens in the middle of b here, but b should get put into 0 because 0 only has one other test
    ;; while 1 has two.
    (is (= '{0 [a/test-1 b/test-1 b/test-2 b/test-3]
             1 [c/test-1 c/test-2]}
           (partition-tests* 2 '[a/test-1 b/test-1 b/test-2 b/test-3 c/test-1 c/test-2])))))

(deftest ^:parallel partition-should-not-split-in-the-middle-of-a-namespace-test
  (testing "Partitioning should not split in the middle of a namespace"
    (is (= '{0 [a/test-1 a/test-2 a/test-3]
             1 [b/test-1]}
           (partition-tests* 2 '[a/test-1 a/test-2 a/test-3 b/test-1])))))

(deftest ^:parallel partition-preserve-order-test
  (testing "Partitioning should sort namespaces but preserve order of vars"
    (is (= '{0 [a/test-1 a/test-3 a/test-2]
             1 [b/test-1 b/test-2 b/test-3]}
           (partition-tests* 2 '[b/test-1 b/test-2 b/test-3 a/test-1 a/test-3 a/test-2])))))

(deftest ^:parallel partition-test
  (is (= {0 [#'mb.hawk.core-test/find-tests-test
             #'mb.hawk.core-test/exclude-tags-test]
          1 [#'mb.hawk.parallel-test/ns-parallel-test
             #'mb.hawk.parallel-test/var-not-parallel-test]
          2 [#'mb.hawk.speak-test/speak-results-test]}
         (into (sorted-map)
               (map (fn [i]
                      [i (hawk/find-tests-with-options
                          {:only            ['mb.hawk.core-test/find-tests-test
                                             'mb.hawk.speak-test/speak-results-test
                                             ;; this var intentionally comes after a different var in a different
                                             ;; namespace to make sure we partition things in a way that groups
                                             ;; namespaces together
                                             'mb.hawk.core-test/exclude-tags-test
                                             'mb.hawk.parallel-test/ns-parallel-test
                                             'mb.hawk.parallel-test/var-not-parallel-test]
                           :partition/index i
                           :partition/total 3})]))
               (range 3)))))
