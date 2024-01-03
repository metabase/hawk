(ns mb.hawk.core
  (:require
   [clojure.java.classpath :as classpath]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.test :as t]
   [clojure.tools.namespace.find :as ns.find]
   [eftest.report.pretty]
   [eftest.report.progress]
   [eftest.runner]
   [environ.core :as env]
   [mb.hawk.assert-exprs]
   [mb.hawk.init :as hawk.init]
   [mb.hawk.junit :as hawk.junit]
   [mb.hawk.parallel :as hawk.parallel]
   [mb.hawk.speak :as hawk.speak]
   [mb.hawk.util :as u]))

(set! *warn-on-reflection* true)

(comment mb.hawk.assert-exprs/keep-me)

;;;; Finding tests

(defmulti find-tests
  "Find test vars in `arg`, which can be a string directory name, symbol naming a specific namespace or test, or a
  collection of one or more of the above."
  {:arglists '([arg options])}
  (fn [arg _options]
    (type arg)))

;; collection of one of the things below
(defmethod find-tests clojure.lang.Sequential
  [coll options]
  (mapcat #(find-tests % options) coll))

;; directory name
(defmethod find-tests String
  [dir-name options]
  (find-tests (io/file dir-name) options))

(defn- exclude-directory? [dir exclude-directories]
  (when (some (fn [directory]
                (str/starts-with? (str dir) directory))
              exclude-directories)
    (println "Excluding directory" (pr-str (str dir)))
    true))

(defn- include-namespace? [ns-symbol namespace-pattern]
  (if namespace-pattern
    (re-matches (re-pattern namespace-pattern) (name ns-symbol))
    true))

;; directory
(defmethod find-tests java.io.File
  [^java.io.File file {:keys [namespace-pattern exclude-directories], :as options}]
  (when (and (.isDirectory file)
             (not (str/includes? (str file) ".gitlibs/libs"))
             (not (exclude-directory? file exclude-directories)))
    (println "Looking for test namespaces in directory" (str file))
    (->> (ns.find/find-namespaces-in-dir file)
         (filter #(include-namespace? % namespace-pattern))
         (mapcat #(find-tests % options)))))

(defn- load-test-namespace [ns-symb]
  (binding [hawk.init/*test-namespace-being-loaded* ns-symb]
    (require ns-symb)))

(defn- find-tests-for-var-symbol
  [symb]
  (load-test-namespace (symbol (namespace symb)))
  [(or (resolve symb)
       (throw (ex-info (format "Unable to resolve test named %s" symb) {:test-symbol symb})))])

(defn- excluded-namespace-tags
  "Return a set of all tags in a namespace metadata that are also in the `:exclude-tags` options."
  [ns-symb options]
  (when-let [excluded-tags (not-empty (set (:exclude-tags options)))]
    (let [ns-tags (-> ns-symb find-ns meta keys set)]
      (not-empty (set/intersection excluded-tags ns-tags)))))

(defn- find-tests-for-namespace-symbol
  [symb options]
  (load-test-namespace symb)
  (if-let [excluded-tags (not-empty (excluded-namespace-tags symb options))]
    (println (format
              "Skipping tests in `%s` due to excluded tag(s): %s"
              symb
              (->> excluded-tags sort (str/join ","))))
    (eftest.runner/find-tests symb)))

;; a test namespace or individual test
(defmethod find-tests clojure.lang.Symbol
  [symb options]
  (if (namespace symb)
    ;; a actual test var e.g. `metabase.whatever-test/my-test`
    (find-tests-for-var-symbol symb)
    ;; a namespace e.g. `metabase.whatever-test`
    (find-tests-for-namespace-symbol symb options)))

;; default -- look in all dirs on the classpath
(defmethod find-tests nil
  [_nil options]
  (find-tests (classpath/system-classpath) options))

(defn find-tests-with-options
  "Find tests using the options map as passed to `clojure -X`."
  [{:keys [only], :as options}]
  (println "Running tests with options" (pr-str options))
  (when only
    (println "Running tests in" (pr-str only)))
  (let [start-time-ms (System/currentTimeMillis)
        tests         (find-tests only options)]
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
  (binding [hawk.parallel/*parallel?* (hawk.parallel/parallel? test-var)]
    (some-> *parallel-test-counter* (swap! update
                                           (if hawk.parallel/*parallel?*
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
      (hawk.junit/handle-event! event)
      (hawk.speak/handle-event! event)
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
    (run (find-tests 'metabase.bad-test nil))

    ;; run tests in a directory
    (run (find-tests \"test/hawk/query_processor_test\" nil))"
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
             :multithread?    :vars
             :report          (reporter options)}
            options))
          @*parallel-test-counter*))))))

(defn- ordinal-str
  [n]
  (let [suffix (if (and (>= n 11) (<= (mod n 100) 13))
                 "th"
                 (nth ["th" "st" "nd" "rd" "th"] (min (mod n 10) 4)))]
    (str n suffix)))

(defn- run-tests-n-times
  "[[run-tests]] but repeat `n` times.
  Returns the combined summary of all the individual test runs."
  [test-vars options n]
  (printf "Running tests %d times\n" n)
  (apply merge-with (fn [x y] (if (number? x)
                                (+ x y)
                                y))
         (for [i (range 1 (inc n))]
           (do
            (println "----------------------------")
            (printf "Running tests the %s %s\n" (ordinal-str i) (if (> 1 i) "times" "time"))
            (run-tests test-vars options)))))

(defn- find-and-run-tests-with-options
  "Entrypoint for the test runner. `options` are passed directly to `eftest`; see https://github.com/weavejester/eftest
  for full list of options."
  [options]
  (let [start-time-ms (System/currentTimeMillis)
        test-vars     (find-tests-with-options options)
        summary       (if-let [n (get options :times)]
                        (run-tests-n-times test-vars options n)
                        (run-tests test-vars options))
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
