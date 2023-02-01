(ns ^:exclude-tags-test ^:mic/test hawk.core-test
  (:require
   [clojure.test :refer :all]
   [hawk.core :as hawk]))

(deftest find-tests-test
  (testing "symbol naming"
    (testing "namespace"
      (let [tests (hawk/find-tests 'hawk.assert-exprs-test nil)]
        (is (seq tests))
        (is (every? var? tests))))
    (testing "var"
      (is (= [(resolve 'hawk.assert-exprs-test/partial=-test)]
             (hawk/find-tests 'hawk.assert-exprs-test/partial=-test nil)))))
  (testing "directory"
    (let [tests (hawk/find-tests "test/hawk" nil)]
      (is (seq tests))
      (is (every? var? tests))
      (is (contains? (set tests) (resolve 'hawk.core-test/find-tests-test)))
      (is (contains? (set tests) (resolve 'hawk.assert-exprs-test/partial=-test))))
    (testing "Exclude directories"
      (is (empty? (hawk/find-tests nil {:exclude-directories ["src" "test"]}))))
    (testing "Namespace pattern"
      (is (some? (hawk/find-tests nil {:namespace-pattern "^hawk\\.core-test$"})))
      (is (empty? (hawk/find-tests nil {:namespace-pattern "^hawk\\.corn-test$"})))))
  (testing "everything"
    (let [tests (hawk/find-tests nil nil)]
      (is (seq tests))
      (is (every? var? tests))
      (is (contains? (set tests) (resolve 'hawk.core-test/find-tests-test)))))
  (testing "sequence"
    (let [tests (hawk/find-tests ['hawk.assert-exprs-test
                                  'hawk.assert-exprs-test/partial=-test
                                  "test/hawk"]
                                 nil)]
      (is (seq tests))
      (is (every? var? tests))
      (is (contains? (set tests) (resolve 'hawk.core-test/find-tests-test))))))

(deftest exclude-tags-test
  (are [options] (contains? (set (hawk/find-tests nil options))
                            #'find-tests-test)
    nil
    {:exclude-tags nil}
    {:exclude-tags []}
    {:exclude-tags #{}}
    {:exclude-tags [:another/tag]})
  (are [options] (not (contains? (set (hawk/find-tests nil options))
                                 #'find-tests-test))
    {:exclude-tags [:exclude-tags-test]}
    {:exclude-tags #{:exclude-tags-test}}
    {:exclude-tags [:exclude-tags-test :another/tag]})
  (is (not (contains? (set (hawk/find-tests nil {:exclude-tags [:exclude-tags-test]}))
                      #'find-tests-test))))
