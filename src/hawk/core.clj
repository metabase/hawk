(ns hawk.core
  "Simple wrapper to let us use eftest with the Clojure CLI. Pass `:only` to specify where to look for tests (see dox
  for [[find-tests]] for more info.)

  ### Test modes:

  The Hawk test runner can one in one of three modes.

  * `:repl`      -- running locally in a REPL
  * `:cli/ci`    -- running in a CI environment like CircleCI or GitHub actions with `clojure` or `clj`
  * `:cli/local` -- running *locally* with `clojure` or `clj`

  Which mode determines different behaviors, e.g. when running from a `:repl` we should not call `System/exit` when
  tests fail; when running from `:cli/local` we should print use the pretty progress-bar reporter, etc.

  You can specify the mode with env var `HAWK_MODE` or Java system property `hawk.mode`, or pass in `:mode` to the
  options map. If the env var `CI` or system property `ci` is set, `:cli/ci` will be assumed, but `HAWK_MODE` will be
  used preferentially."
  (:require
   [clojure.java.classpath :as classpath]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.test :as t]
   [clojure.tools.namespace.find :as ns.find]
   [eftest.report.pretty]
   [eftest.report.progress]
   [eftest.runner]
   [environ.core :as env]
   [hawk.assert-exprs]
   [hawk.init :as test-runner.init]
   [hawk.junit :as test-runner.junit]
   [hawk.parallel :as test-runner.parallel]
   [hawk.util :as u]))

(comment hawk.assert-exprs/keep-me)

;;;; Finding tests

(defmulti find-tests
  "Find test vars in `arg`, which can be a string directory name, symbol naming a specific namespace or test, or a
  collection of one or more of the above."
  {:arglists '([arg])}
  type)

;; collection of one of the things below
(defmethod find-tests clojure.lang.Sequential
  [coll]
  (mapcat find-tests coll))

;; directory name
(defmethod find-tests String
  [dir-name]
  (find-tests (io/file dir-name)))

(def ^:private excluded-directories
  "When searching the classpath for tests (i.e., if no `:only` options were passed), don't look for tests in any
  directories with these name (as the last path component)."
  #{"src"
    "test_config"
    "resources"
    "test_resources"
    "resources-ee"
    "classes"})

;; directory
(defmethod find-tests java.io.File
  [^java.io.File file]
  (when (and (.isDirectory file)
             (not (some (partial = (.getName file)) excluded-directories)))
    (println "Looking for test namespaces in directory" (str file))
    (->> (ns.find/find-namespaces-in-dir file)
         (filter #(re-matches  #"^hawk.*test$" (name %)))
         (mapcat find-tests))))

;; a test namespace or individual test
(defmethod find-tests clojure.lang.Symbol
  [symb]
  (letfn [(load-test-namespace [ns-symb]
            (binding [test-runner.init/*test-namespace-being-loaded* ns-symb]
              (require ns-symb)))]
    (if-let [symbol-namespace (some-> (namespace symb) symbol)]
      ;; a actual test var e.g. `metabase.whatever-test/my-test`
      (do
        (load-test-namespace symbol-namespace)
        [(or (resolve symb)
             (throw (ex-info (format "Unable to resolve test named %s" symb) {:test-symbol symb})))])
      ;; a namespace e.g. `metabase.whatever-test`
      (do
        (load-test-namespace symb)
        (eftest.runner/find-tests symb)))))

;; default -- look in all dirs on the classpath
(defmethod find-tests nil
  [_]
  (find-tests (classpath/system-classpath)))

(defn find-tests-with-options
  "Find tests using the options map as passed to `clojure -X`."
  [{:keys [only], :as options}]
  (println "Running tests with options" (pr-str options))
  (when only
    (println "Running tests in" (pr-str only)))
  (let [start-time-ms (System/currentTimeMillis)
        tests         (find-tests only)]
    (printf "Finding tests took %s.\n" (u/format-milliseconds (- (System/currentTimeMillis) start-time-ms)))
    (println "Running" (count tests) "tests")
    tests))

;;;; Running tests & reporting the output

(defonce ^:private orig-test-var t/test-var)

(def ^:private ^:dynamic *parallel-test-counter*
  nil)

(defn run-test
  "Run a single test `test-var`. Wraps/replaces [[clojure.test/test-var]]."
  [test-var]
  (binding [test-runner.parallel/*parallel?* (test-runner.parallel/parallel? test-var)]
    (some-> *parallel-test-counter* (swap! update
                                           (if test-runner.parallel/*parallel?*
                                             :parallel
                                             :single-threaded)
                                           (fnil inc 0)))
    (orig-test-var test-var)))

(alter-var-root #'t/test-var (constantly run-test))

(defn- reporter
  "Create a new test reporter/event handler, a function with the signature `(handle-event event)` that gets called once
  for every [[clojure.test]] event, including stuff like `:begin-test-run`, `:end-test-var`, and `:fail`."
  [options]
  (let [stdout-reporter (case (:mode options)
                          (:cli/ci :repl) eftest.report.pretty/report
                          :cli/local      eftest.report.progress/report)]
    (fn handle-event [event]
      (test-runner.junit/handle-event! event)
      (stdout-reporter event))))

(def ^:private env-mode
  (cond
    (env/env :hawk-mode)
    (keyword (env/env :hawk-mode))

    (env/env :ci)
    :cli/ci))

(defn run-tests
  "Run `test-vars` with `options`, which are passed directly to [[eftest.runner/run-tests]].

  To run tests from the REPL, use this function.

    ;; run tests in a single namespace
    (run (find-tests 'metabase.bad-test))

    ;; run tests in a directory
    (run (find-tests \"test/hawk/query_processor_test\"))"
  ([test-vars]
   (run-tests test-vars nil))

  ([test-vars options]
   (let [options (merge {:mode :repl}
                        options)]
     (when-not (every? var? test-vars)
       (throw (ex-info "Invalid test vars" {:test-vars test-vars, :options options})))
     ;; don't randomize test order for now please, thanks anyway
     (with-redefs [eftest.runner/deterministic-shuffle (fn [_ test-vars] test-vars)]
       (binding [*parallel-test-counter* (atom {})]
         (merge
          (eftest.runner/run-tests
           test-vars
           (merge
            {:capture-output? false
             :multithread?    true
             :report          (reporter options)}
            options))
          @*parallel-test-counter*))))))

(defn- find-and-run-tests-with-options
  "Entrypoint for the test runner. `options` are passed directly to `eftest`; see https://github.com/weavejester/eftest
  for full list of options."
  [options]
  (let [start-time-ms (System/currentTimeMillis)
        summary       (run-tests (find-tests-with-options options) options)
        fail?         (pos? (+ (:error summary) (:fail summary)))]
    (pprint/pprint summary)
    (printf "Ran %d tests in parallel, %d single-threaded.\n" (:parallel summary 0) (:single-threaded summary 0))
    (printf "Finding and running tests took %s.\n" (u/format-milliseconds (- (System/currentTimeMillis) start-time-ms)))
    (println (if fail? "Tests failed." "All tests passed."))
    (case (:mode options)
      (:cli/local :cli/ci) (System/exit (if fail? 1 0))
      :repl                summary)))

(defn find-and-run-tests-repl
  "REPL entrypoint. Find and run tests with options."
  [options]
  (let [options (merge
                 {:mode :repl}
                 (when env-mode
                   {:mode env-mode})
                 options)]
    (find-and-run-tests-with-options options)))

(defn find-and-run-tests-cli
  "`clojure -X` entrypoint. Find and run tests with `options`."
  [options]
  (let [options (merge
                 {:mode :cli/local}
                 (when env-mode
                   {:mode env-mode})
                 options)]
    (find-and-run-tests-with-options options)))
