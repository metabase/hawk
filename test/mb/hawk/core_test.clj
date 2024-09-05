(ns ^:exclude-tags-test ^:mic/test mb.hawk.core-test
  (:require
   [clojure.test :refer :all]
   [mb.hawk.core :as hawk]
   [mb.hawk.parallel-test]
   [mb.hawk.speak-test]))

(deftest ^:exclude-this-test find-tests-test
  (testing "symbol naming"
    (testing "namespace"
      (let [tests (hawk/find-tests 'mb.hawk.assert-exprs-test nil)]
        (is (seq tests))
        (is (every? var? tests))))
    (testing "var"
      (is (= [(resolve 'mb.hawk.assert-exprs-test/partial=-test)]
             (hawk/find-tests 'mb.hawk.assert-exprs-test/partial=-test nil)))))
  (testing "directory"
    (let [tests (hawk/find-tests "test/mb/hawk" nil)]
      (is (seq tests))
      (is (every? var? tests))
      (is (contains? (set tests) (resolve 'mb.hawk.core-test/find-tests-test)))
      (is (contains? (set tests) (resolve 'mb.hawk.assert-exprs-test/partial=-test))))
    (testing "Exclude directories"
      (is (empty? (hawk/find-tests nil {:exclude-directories ["src" "test"]}))))
    (testing "Namespace pattern"
      (is (some? (hawk/find-tests nil {:namespace-pattern "^mb\\.hawk\\.core-test$"})))
      (is (empty? (hawk/find-tests nil {:namespace-pattern "^mb\\.hawk\\.corn-test$"})))))
  (testing "everything"
    (let [tests (hawk/find-tests nil nil)]
      (is (seq tests))
      (is (every? var? tests))
      (is (contains? (set tests) (resolve 'mb.hawk.core-test/find-tests-test)))))
  (testing "sequence"
    (let [tests (hawk/find-tests ['mb.hawk.assert-exprs-test
                                  'mb.hawk.assert-exprs-test/partial=-test
                                  "test/mb/hawk"]
                                 nil)]
      (is (seq tests))
      (is (every? var? tests))
      (is (contains? (set tests) (resolve 'mb.hawk.core-test/find-tests-test))))))

(def ^:private this-ns (ns-name *ns*))

(deftest exclude-tags-test
  (are [options] (every? (set (hawk/find-tests this-ns options)) [#'find-tests-test #'exclude-tags-test])
    nil
    {:exclude-tags nil}
    {:exclude-tags []}
    {:exclude-tags #{}}
    {:exclude-tags [:another/tag]})

  (are [options] (not (some (set (hawk/find-tests this-ns options)) [#'find-tests-test #'exclude-tags-test]))
    {:exclude-tags [:exclude-tags-test]}
    {:exclude-tags #{:exclude-tags-test}}
    {:exclude-tags [:exclude-tags-test :another/tag]})

  (are [options] (let [tests (set (hawk/find-tests this-ns options))]
                   (and (not (contains? tests #'find-tests-test))
                        (contains? tests #'exclude-tags-test)))
    {:exclude-tags [:exclude-this-test]}
    {:exclude-tags #{:exclude-this-test}}
    {:exclude-tags [:exclude-this-test :another/tag]}))

(defn- partition-tests* [num-partitions tests]
  (into (sorted-map)
        (map (fn [i]
               [i (#'hawk/partition-tests
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
           (partition-tests* 10 (map #(symbol (format "n%02d/test" %)) (range 26)))))))

(deftest ^:parallel partition-should-not-split-in-the-middle-of-a-namespace-test
  (testing "Partitioning should not split in the middle of a namespace"
    (is (= '{0 [a/test-1 a/test-2 a/test-3]
             1 [b/test-1]}
           (partition-tests* 2 '[a/test-1 a/test-2 a/test-3 b/test-1])))))

(deftest ^:parallel partition-preserve-order-test
  (testing "Partitioning should preserve order of namespaces and vars"
    (is (= '{0 [b/test-1 b/test-2 b/test-3]
             1 [a/test-1 a/test-3 a/test-2]}
           (partition-tests* 2 '[b/test-1 b/test-2 b/test-3 a/test-1 a/test-3 a/test-2])))))

(deftest ^:parallel partition-test
  (is (= {0 [#'find-tests-test
             #'exclude-tags-test]
          1 [#'mb.hawk.speak-test/speak-results-test]
          2 [#'mb.hawk.parallel-test/ns-parallel-test
             #'mb.hawk.parallel-test/var-not-parallel-test]}
         (into (sorted-map)
               (map (fn [i]
                      [i (hawk/find-tests-with-options
                          {:only            [`find-tests-test
                                             'mb.hawk.speak-test/speak-results-test
                                             ;; this var intentionally comes after a different var in a different
                                             ;; namespace to make sure we partition things in a way that groups
                                             ;; namespaces together
                                             `exclude-tags-test
                                             'mb.hawk.parallel-test/ns-parallel-test
                                             'mb.hawk.parallel-test/var-not-parallel-test]
                           :partition/index i
                           :partition/total 3})]))
               (range 3)))))
