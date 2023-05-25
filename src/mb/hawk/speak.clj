(ns mb.hawk.speak
  (:require [clojure.java.shell :as sh]))

(defmulti handle-event!
  "Handles a test event by speaking(!?) it if appropriate"
  :type)

(defn- enabled? [] (some? (System/getenv "SPEAK_TEST_RESULTS")))

(defmethod handle-event! :default [_] nil)

(defmethod handle-event! :summary
  [{:keys [error fail]}]
  (when (enabled?)
    (apply sh/sh "say"
           (if (zero? (+ error fail))
             "all tests passed"
             "tests failed")
           (for [[n s] [[error "error"]
                        [fail  "failure"]]
                 :when (pos? n)]
             (str n " " s (when (< 1 n) "s"))))))
