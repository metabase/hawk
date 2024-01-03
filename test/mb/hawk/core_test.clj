(ns ^:exclude-tags-test ^:mic/test mb.hawk.core-test
  (:require
   [clojure.test :refer :all]
   [mb.hawk.core :as hawk]))

(deftest find-tests-test
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

(deftest run-tests-n-times-test
  (let [single-run-summary {:test            10
                            :pass            5
                            :fail            3
                            :error           2
                            :duration        1
                            :single-threaded 1}]
    (with-redefs [hawk/run-tests (fn [& _args] single-run-summary)]
      (testing "run single time"
       (is (= single-run-summary
              (hawk/find-and-run-tests-repl {:times 1}))))

      (testing "run multiple will combine the summary of all the runs"
       (is (= (apply merge-with + (repeat 3 single-run-summary))
              (hawk/find-and-run-tests-repl {:times 3})))))))
