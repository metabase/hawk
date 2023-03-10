(ns mb.hawk.init
  "Code related to [[mb.hawk.core]] initialization and utils for enforcing that code isn't allowed to run while
  loading namespaces."
  (:require
   [clojure.pprint :as pprint]))

(def ^:dynamic *test-namespace-being-loaded*
  "Bound to the test namespace symbol that's currently getting loaded, if any."
  nil)

(defn assert-tests-are-not-initializing
  "Check that we are not in the process of loading test namespaces when starting up [[mb.hawk.core]]. For
  example, you probably don't want to be doing stuff like creating application DB connections as a side-effect of
  loading test namespaces."
  [disallowed-message]
  (when *test-namespace-being-loaded*
    (let [e (ex-info (str (format "%s happened as a side-effect of loading namespace %s."
                                  disallowed-message *test-namespace-being-loaded*)
                          " This is not allowed; make sure it's done in tests or fixtures only when running tests.")
                     {:namespace *test-namespace-being-loaded*})]
      (pprint/pprint (Throwable->map e))
      (throw e))))
