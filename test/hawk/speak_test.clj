(ns hawk.speak-test
  (:require [clojure.java.shell :as sh]
            [clojure.test :refer :all]
            hawk.speak))

(deftest speak-results-test
  (are [summary expected] (let [sh-args (atom nil)]
                            (with-redefs [hawk.speak/enabled? (constantly true)
                                          sh/sh (fn [& args] (reset! sh-args (vec args)))]
                              (hawk.speak/handle-event! (assoc summary :type :summary))
                              (println "sh-args =" @sh-args)
                              (= expected @sh-args)))
    {:error 0 :fail 0} ["say" "all tests passed"]
    {:error 1 :fail 0} ["say" "tests failed" "1 error"]
    {:error 2 :fail 0} ["say" "tests failed" "2 errors"]
    {:error 2 :fail 1} ["say" "tests failed" "2 errors" "1 failure"]
    {:error 0 :fail 2} ["say" "tests failed" "2 failures"]))
