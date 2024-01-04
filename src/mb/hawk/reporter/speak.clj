(ns mb.hawk.reporter.speak
  (:require
   [clojure.java.shell :as sh]
   [mb.hawk.reporter.interface :as hawk.reporter]))

(hawk.reporter/register-reporter! :hawk/speak)

(defn- enabled? [] (some? (System/getenv "SPEAK_TEST_RESULTS")))

(defmethod hawk.reporter/handle-event [:hawk/speak :summary]
  [_ {:keys [error fail]}]
  (when (enabled?)
    (apply sh/sh "say"
           (if (zero? (+ error fail))
             "all tests passed"
             "tests failed")
           (for [[n s] [[error "error"]
                        [fail  "failure"]]
                 :when (pos? n)]
             (str n " " s (when (< 1 n) "s"))))))
