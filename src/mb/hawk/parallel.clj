(ns mb.hawk.parallel
  "Code related to running parallel tests, and utilities for disallowing dangerous stuff inside them."
  (:require
   [clojure.test :as t]))

(defn- parallel-setting [metadata]
  (if-some [parallel (:parallel metadata)]
    parallel
    (when (:synchronized metadata)
      false)))

(defn parallel?
  "Whether `test-var` can be ran in parallel with other parallel tests.

  Metadata on the test takes precedence over metadata on its namespace. `^:synchronized` is shorthand for
  `^{:parallel false}` at either level."
  [test-var]
  (let [test-metadata (meta test-var)]
    (if-some [test-parallel (parallel-setting test-metadata)]
      test-parallel
      (parallel-setting (-> test-metadata :ns meta)))))

(def ^:dynamic *parallel?*
  "Whether test currently being ran is being ran in parallel."
  nil)

(defn assert-test-is-not-parallel
  "Throw an exception if we are inside a `^:parallel` test."
  [disallowed-message]
  (when *parallel?*
    (let [e (ex-info (format "%s is not allowed inside parallel tests." disallowed-message) {})]
      (t/is (throw e)))))
