(ns ^:exclude-tags-test ^:mic/test mb.hawk.core-test
  (:require
   [clojure.test :refer :all]
   [mb.hawk.core :as hawk]))

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

(deftest ^:parallel partition-tests-test
  (are [i expected] (= expected
                       (#'hawk/partition-tests
                        (range 4)
                        {:partition/index i, :partition/total 3}))
    0 [0 1]
    1 [2]
    2 [3])
  (are [i expected] (= expected
                       (#'hawk/partition-tests
                        (range 5)
                        {:partition/index i, :partition/total 3}))
    0 [0 1]
    1 [2 3]
    2 [4]))

(deftest ^:parallel partition-tests-determinism-test
  (testing "partitioning should be deterministic even if tests come back in a non-deterministic order for some reason"
    (are [i expected] (= expected
                         (#'hawk/partition-tests
                          (shuffle (map #(format "%02d" %) (range 26)))
                          {:partition/index i, :partition/total 10}))
      0 ["00" "01" "02"]
      1 ["03" "04" "05"]
      2 ["06" "07"]
      3 ["08" "09" "10"]
      4 ["11" "12"]
      5 ["13" "14" "15"]
      6 ["16" "17" "18"]
      7 ["19" "20"]
      8 ["21" "22" "23"]
      9 ["24" "25"])))

(deftest ^:parallel partition-test
  (are [index expected] (= expected
                           (hawk/find-tests-with-options {:only            `[find-tests-test
                                                                             exclude-tags-test
                                                                             partition-tests-test
                                                                             partition-test]
                                                          :partition/index index
                                                          :partition/total 3}))
    0 [#'exclude-tags-test #'find-tests-test]
    1 [#'partition-test]
    2 [#'partition-tests-test]))
