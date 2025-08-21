(ns ^:exclude-tags-test ^:mic/test mb.hawk.core-test
  (:require
   [clojure.set :as set]
   [clojure.test :refer :all]
   [mb.hawk.core :as hawk]))

(deftest simple
  ;; need a test that already exists without moving them or circularity
  (is (= 1 1)))

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
  (testing "ignore var options"
    ;; note this is a set here. but that gets normalized and the cli can pass a sequence
    (let [tests (set (hawk/find-tests nil {:ignored {:vars #{"mb.hawk.core-test/simple"}}}))
          expected (into #{}
                         (comp
                          (keep (fn [[_l v]]
                                  (when (and (-> v meta :test)
                                             (not= (-> v meta :name) 'simple))
                                    (symbol (format "%s/%s"
                                                    (-> v meta :ns ns-name)
                                                    (-> v meta :name))))))
                          (map resolve))
                         (ns-publics 'mb.hawk.core-test))]
      (is (set/subset? expected tests))
      (is (not (contains? tests #'mb.hawk.core-test/simple)))
      (is (contains? tests #'mb.hawk.core-test/find-tests-test))))
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

(deftest only-tags-test
  (are [options] (= []
                    (hawk/find-tests this-ns options))
    {:only-tags [:another/tag]}
    {:only-tags [:another/tag :exclude-tags-test]}
    {:only-tags [:another/tag :exclude-this-test]})
  (are [options] (= (hawk/find-tests this-ns {})
                    (hawk/find-tests this-ns options))
    {:only-tags [:exclude-tags-test]}
    {:only-tags #{:exclude-tags-test}})
  (are [options] (= [#'find-tests-test]
                    (hawk/find-tests this-ns options))
    {:only-tags [:exclude-this-test]}
    {:only-tags #{:exclude-this-test}}
    {:only-tags [:exclude-this-test :exclude-tags-test]}
    {:only-tags #{:exclude-this-test :exclude-tags-test}})
  (are [options] (= [#'find-tests-test]
                    (hawk/find-tests this-ns options))
    {:only-tags [:exclude-this-test]}
    {:only-tags #{:exclude-this-test}}))
