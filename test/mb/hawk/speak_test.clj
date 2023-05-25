(ns mb.hawk.speak-test
  (:require [clojure.java.shell :as sh]
            [clojure.test :refer :all]
            [mb.hawk.speak :as hawk.speak]))

(deftest speak-results-test
  (are [error fail expected] (let [sh-args (atom nil)]
                            (with-redefs [hawk.speak/enabled? (constantly true)
                                          sh/sh               (fn [& args] (reset! sh-args (vec args)))]
                              (hawk.speak/handle-event! {:type :summary :error error :fail fail})
                              (= (into ["say"] expected) @sh-args)))
    0 0 ["all tests passed"]
    1 0 ["tests failed" "1 error"]
    2 0 ["tests failed" "2 errors"]
    2 1 ["tests failed" "2 errors" "1 failure"]
    0 2 ["tests failed" "2 failures"]))
