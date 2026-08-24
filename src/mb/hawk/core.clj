(ns mb.hawk.core
  (:require
   [clojure.java.classpath :as classpath]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.test :as t]
   [clojure.tools.namespace.file :as ns.file]
   [clojure.tools.namespace.find :as ns.find]
   [environ.core :as env]
   [mb.eftest.report.pretty]
   [mb.eftest.report.progress]
   [mb.eftest.runner]
   [mb.hawk.assert-exprs]
   [mb.hawk.hooks :as hawk.hooks]
   [mb.hawk.init :as hawk.init]
   [mb.hawk.junit :as hawk.junit]
   [mb.hawk.parallel :as hawk.parallel]
   [mb.hawk.partition :as hawk.partition]
   [mb.hawk.speak :as hawk.speak]
   [mb.hawk.util :as u])
  (:import
   (java.io StringWriter Writer)))

(set! *warn-on-reflection* true)

(comment mb.hawk.assert-exprs/keep-me)

;;;; Finding tests

(defmulti find-tests
  "Find test vars in `arg`, which can be a string directory or file name, symbol naming a specific namespace or test, or a
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

;; directory or file
(defmethod find-tests java.io.File
  [^java.io.File file {:keys [namespace-pattern exclude-directories], :as options}]
  (cond
    ;; handle regular files
    (.isFile file)
    ;; almost exact code from `ns.find/find-namespaces-in-dir`
    (let [[_ nom :as decl] (try (ns.file/read-file-ns-decl file)
                                (catch Exception _ nil))]
      (when (and decl nom (symbol? nom))
        (find-tests nom options)))

    ;; handle directories
    (.isDirectory file)
    (when (and (not (str/includes? (str file) ".gitlibs/libs"))
               (not (exclude-directory? file exclude-directories)))
      (println "Looking for test namespaces in directory" (str file))
      (->> (ns.find/find-namespaces-in-dir file)
           (filter #(include-namespace? % namespace-pattern))
           (mapcat #(find-tests % options))))))

(defn- load-test-namespace [ns-symb]
  (binding [hawk.init/*test-namespace-being-loaded* ns-symb]
    (require ns-symb)))

(defn- find-tests-for-var-symbol
  [symb]
  (load-test-namespace (symbol (namespace symb)))
  [(or (resolve symb)
       (throw (ex-info (format "Unable to resolve test named %s" symb) {:test-symbol symb})))])

(defn- skip-by-tags?
  "Whether we should skip a namespace or test var because it has tags in `:exclude-tags` or is missing tags in
  `:only-tags`. Prints debug message as a side-effect."
  [ns-or-var options]
  (let [tags-set      (fn [ns-or-var]
                        (not-empty (set (keys (meta ns-or-var)))))
        excluded-tag? (when-let [exclude-tags (not-empty (set (:exclude-tags options)))]
                        (when (not-empty (set/intersection exclude-tags (tags-set ns-or-var)))
                          :exclude))
        missing-tag?  (when (var? ns-or-var)
                        (let [varr ns-or-var]
                          (when-let [only-tags (not-empty (set (:only-tags options)))]
                            (when (not-empty (set/difference only-tags
                                                             (tags-set (:ns (meta varr)))
                                                             (tags-set varr)))
                              :only))))]
    (or excluded-tag? missing-tag?)))

(defn- ignored-var?
  [v options]
  (letfn [(var-name [v] (format "%s/%s" (-> v meta :ns ns-name) (-> v meta :name)))]
    (contains? (-> options :ignored :vars) (var-name v))))

(defn- find-tests-for-namespace-symbol
  [ns-symb options]
  (load-test-namespace ns-symb)
  (when-not (skip-by-tags? (find-ns ns-symb) options)
    (remove (some-fn #(skip-by-tags? % options)
                     #(ignored-var? % options))
            (mb.eftest.runner/find-tests-in-namespace ns-symb))))

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
        tests         (-> (find-tests only options)
                          (hawk.partition/partition-tests options))]
    (printf "Finding tests took %s.\n" (u/format-milliseconds (- (System/currentTimeMillis) start-time-ms)))
    (println "Running" (count tests) "tests")
    tests))

;;;; Running tests & reporting the output

(defonce ^:private orig-test-var t/test-var)

(def ^:private ^:dynamic *parallel-test-counter*
  nil)

(def ^:private ^:dynamic *after-each-options*
  "Bound to the test run options when at least one [[mb.hawk.hooks/after-each]] hook is registered, and `nil`
  otherwise. Capturing per-test output and report events only happens when this is non-nil, so runs without after-each
  hooks pay no overhead."
  nil)

(defn- tee-writer
  "Returns a Writer that forwards everything written to it to both `primary` and `copy`. Closing it flushes both but
  closes neither."
  ^Writer [^Writer primary ^Writer copy]
  (proxy [Writer] []
    (write
      ([x]
       (cond
         (integer? x) (do (.write primary (int x))
                          (.write copy (int x)))
         (string? x)  (do (.write primary ^String x)
                          (.write copy ^String x))
         :else        (do (.write primary ^chars x)
                          (.write copy ^chars x))))
      ([x off len]
       (if (string? x)
         (do (.write primary ^String x (int off) (int len))
             (.write copy ^String x (int off) (int len)))
         (do (.write primary ^chars x (int off) (int len))
             (.write copy ^chars x (int off) (int len))))))
    (flush []
      (.flush primary)
      (.flush copy))
    (close []
      (.flush primary)
      (.flush copy))))

(defn- run-test-with-after-each-hooks
  "Run `test-var`, capturing its [[clojure.test]] report events and `*out*`/`*err*` output, then invoke
  any [[hawk.hooks/after-each]] hooks with the run `options` and a context map describing the test that just ran.

  The hooks are run as the test var's reporting window closes -- right before its `:end-test-var` event reaches the
  real reporter -- rather than after [[orig-test-var]] returns. This matters: a hook exception (or a `clojure.test`
  assertion inside a hook) is reported as a `clojure.test` error while `test-var` is still the var being reported on,
  so it is counted and attributed to `test-var` everywhere, including in the JUnit output (which finalizes a test
  var's results when it sees `:end-test-var`)."
  [options test-var]
  (let [events      (atom [])
        buf         (StringWriter.)
        orig-report t/report
        start-ns    (System/nanoTime)
        run-hooks!  (fn []
                      (let [duration-ms (/ (- (System/nanoTime) start-ns) 1e6)
                            summary     (merge {:pass 0, :fail 0, :error 0}
                                               (select-keys (frequencies (map :type @events))
                                                            [:pass :fail :error]))]
                        ;; bind t/report back to the real reporter so anything the hook reports (including the
                        ;; hook-error event below) goes straight through instead of being recaptured into `events`
                        (binding [t/report orig-report]
                          (try
                            (hawk.hooks/after-each options {:var           test-var
                                                            :ns            (:ns (meta test-var))
                                                            :report-events @events
                                                            :output        (str buf)
                                                            :summary       summary
                                                            :duration-ms   duration-ms
                                                            :parallel?     hawk.parallel/*parallel?*})
                            (catch Throwable e
                              (orig-report {:type     :error
                                            :var      test-var
                                            :message  (format "Error in after-each hook for %s" test-var)
                                            :expected nil
                                            :actual   e}))))))]
    (binding [t/report (fn [event]
                         (swap! events conj (assoc event :testing-contexts t/*testing-contexts*))
                         ;; run hooks while the var's reporting window is still open (before :end-test-var is
                         ;; forwarded), so hook errors are attributed to this var
                         (when (= (:type event) :end-test-var)
                           (run-hooks!))
                         (orig-report event))
              *out*     (tee-writer *out* buf)
              *err*     (tee-writer *err* buf)]
      (orig-test-var test-var))))

(defn run-test
  "Run a single test `test-var`. Wraps/replaces [[clojure.test/test-var]]."
  [test-var]
  (binding [hawk.parallel/*parallel?* (hawk.parallel/parallel? test-var)]
    (some-> *parallel-test-counter* (swap! update
                                           (if hawk.parallel/*parallel?*
                                             :parallel
                                             :single-threaded)
                                           (fnil inc 0)))
    (if-let [options *after-each-options*]
      (run-test-with-after-each-hooks options test-var)
      (orig-test-var test-var))))

(alter-var-root #'t/test-var (constantly run-test))

(defn- reporter
  "Create a new test reporter/event handler, a function with the signature `(handle-event event)` that gets called once
  for every [[clojure.test]] event, including stuff like `:begin-test-run`, `:end-test-var`, and `:fail`."
  [options]
  (let [stdout-reporter (case (:mode options)
                          (:cli/ci :repl) mb.eftest.report.pretty/report
                          :cli/local      mb.eftest.report.progress/report)]
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
  "Run `test-vars` with `options`, which are passed directly to [[mb.eftest.runner/run-tests]].

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
     (binding [*parallel-test-counter* (atom {})
               ;; check for after-each hooks once per run rather than once per test; eftest conveys these bindings
               ;; to its worker threads
               *after-each-options*    (when (hawk.hooks/after-each-hooks-registered?)
                                         options)]
       (merge
        (mb.eftest.runner/run-tests
         test-vars
         (merge
          {:capture-output? false
           :multithread?    :vars
           :report          (reporter options)}
          options))
        @*parallel-test-counter*)))))

(defn- run-tests-n-times
  "[[run-tests]] but repeat `n` times.
  Returns the combined summary of all the individual test runs."
  [test-vars options n]
  (printf "Running tests %d times\n" n)
  (reduce (fn [acc test-result] (merge-with
                                 #(if (number? %2)
                                    (+ %1 %2)
                                    %2)
                                 acc
                                 test-result))
          {}
          (for [i (range 1 (inc n))]
            (do
             (println "----------------------------")
             (printf "Starting test iteration #%d\n" i)
             (run-tests test-vars options)))))

(defn- normalize-options
  "Ensure that ignored vars are a set for quick membership checks."
  [options]
  (cond-> options
    (-> options :ignored :vars seq) (update-in [:ignored :vars] set)))

(defn- find-and-run-tests-with-options
  "Entrypoint for the test runner. `options` are passed directly to `eftest`; see https://github.com/weavejester/eftest
  for full list of options."
  [options]
  (let [start-time-ms   (System/currentTimeMillis)
        options         (normalize-options options)
        test-vars       (find-tests-with-options options)
        _               (hawk.hooks/before-run options)
        [summary fail?] (try
                          (let [summary (if-let [n (get options :times)]
                                          (run-tests-n-times test-vars options n)
                                          (run-tests test-vars options))
                                fail?   (pos? (+ (:error summary) (:fail summary)))]
                            (pprint/pprint summary)
                            (printf "Ran %d tests in parallel, %d single-threaded.\n"
                                    (:parallel summary 0) (:single-threaded summary 0))
                            (printf "Finding and running tests took %s.\n"
                                    (u/format-milliseconds (- (System/currentTimeMillis) start-time-ms)))
                            (println (if fail? "Tests failed." "All tests passed."))
                            [summary fail?])
                          (finally
                            (hawk.hooks/after-run options)))]
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
