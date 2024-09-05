(ns mb.hawk.core
  (:require
   [clojure.java.classpath :as classpath]
   [clojure.java.io :as io]
   [clojure.math :as math]
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
   [mb.hawk.hooks :as hawk.hooks]
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

(defn- excluded-tags
  "Return the set of all tags in an element's metadata that are also in the `:exclude-tags` options."
  [element options]
  (when-let [excluded-tags (not-empty (set (:exclude-tags options)))]
    (let [ns-tags (-> element meta keys set)]
      (not-empty (set/intersection excluded-tags ns-tags)))))

(defn- filter-tests-by-tag
  "Filter out the test cases with tags that are also in the `:exclude-tags` options."
  [tests options]
  (filter (fn [test]
            (if-let [excluded-tags (not-empty (excluded-tags test options))]
              (println (format
                        "Skipping test `%s` due to excluded tag(s): %s"
                        (:name (meta test))
                        (->> excluded-tags sort (str/join ","))))
              test))
          tests))

(defn- find-tests-for-namespace-symbol
  [ns-symb options]
  (load-test-namespace ns-symb)
  (if-let [excluded-tags (not-empty (excluded-tags (find-ns ns-symb) options))]
    (println (format
              "Skipping tests in `%s` due to excluded tag(s): %s"
              ns-symb
              (->> excluded-tags sort (str/join ","))))
    (filter-tests-by-tag
     (eftest.runner/find-tests ns-symb)
     options)))

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

(defn- namespace*
  "Like [[clojure.core/namespace]] but handles vars."
  [x]
  (cond
    (instance? clojure.lang.Named x) (namespace x)
    (var? x)                         (namespace (symbol x))
    :else                            nil))

(defn- ensure-test-namespaces-are-contiguous
  "Make sure `test-vars` have all the tests for each namespace next to one another so when we split we can do so without
  splitting in the middle of a namespace. Does not otherwise change the order of the tests or namespaces."
  [test-vars]
  (let [namespace->sort-position (into {}
                                       (map-indexed
                                        (fn [i nmspace]
                                          [nmspace i]))
                                       (distinct (map namespace* test-vars)))
        test-var->sort-position  (into {}
                                       (map-indexed
                                        (fn [i varr]
                                          [varr i]))
                                       test-vars)]
    (sort-by (juxt #(namespace->sort-position (namespace* %))
                   test-var->sort-position)
             test-vars)))

(defn- make-test-var->partition [num-partitions test-vars]
  (let [;; first figure out approximately how big each partition should be.
        target-partition-size          (/ (count test-vars) num-partitions)
        ;; then for each test var figure out what partition it would live in ideally if we weren't worried about making
        ;; sure namespaces are grouped together.
        test-var->ideal-partition      (into {}
                                             (map-indexed (fn [i test-var]
                                                            (let [ideal-partition (long (math/floor (/ i target-partition-size)))]
                                                              (assert (<= 0 ideal-partition (dec num-partitions)))
                                                              [test-var ideal-partition]))
                                                          test-vars))
        ;; For each namespace figure out how many tests are in each and what the possible partitions we can put that
        ;; namespace into. For most namespaces there should only be one possible partition but for some the ideal split
        ;; happens in the middle of the namespace which means we have two possible candidate partitions to put it into.
        namespace->num-tests           (reduce
                                        (fn [m test-var]
                                          (update m (namespace* test-var) (fnil inc 0)))
                                        {}
                                        test-vars)
        namespace->possible-partitions (reduce
                                        (fn [m test-var]
                                          (update m (namespace* test-var) #(conj (set %) (test-var->ideal-partition test-var))))
                                        {}
                                        test-vars)
        ;; Decide the canonical partition for each namespace. Keep track of how many tests are in each partititon. If
        ;; there are multiple possible candidate partitions for a namespace, choose the one that has the least tests in
        ;; it.
        namespace->partition           (:namespace->partition
                                        (reduce
                                         (fn [m nmspace]
                                           (let [partition (first (sort-by (fn [partition]
                                                                             (get-in m [:partition->size partition]))
                                                                           (namespace->possible-partitions nmspace)))]
                                             (-> m
                                                 (update-in [:partition->size partition] (fnil + 0) (namespace->num-tests nmspace))
                                                 (assoc-in [:namespace->partition nmspace] partition))))
                                         {}
                                         ;; process namespaces in the order they appear in test-vars
                                         (distinct (map namespace* test-vars))))]
    (fn test-var->partition [test-var]
      (get namespace->partition (namespace* test-var)))))

(defn- partition-tests-into-n-partitions
  "Split a sequence of `test-vars` into `num-partitions` (returning a map of partition number => sequence of tests).
  Attempts to divide tests up into partitions that are as equal as possible, but keeps tests in the same namespace
  grouped together."
  [num-partitions test-vars]
  {:post [(= (count %) num-partitions)]}
  (let [test-vars           (ensure-test-namespaces-are-contiguous test-vars)
        test-var->partition (make-test-var->partition num-partitions test-vars)]
    (reduce
     (fn [m test-var]
       (update m (test-var->partition test-var) #(conj (vec %) test-var)))
     (sorted-map)
     test-vars)))

(defn- partition-tests [tests {num-partitions :partition/total, partition-index :partition/index, :as _options}]
  (if (or num-partitions partition-index)
    (do
      (assert (and num-partitions partition-index)
              ":partition/total and :partition/index must be set together")
      (assert (pos-int? num-partitions)
              "Invalid :partition/total - must be a positive integer")
      (assert (<= num-partitions (count tests))
              "Invalid :partition/total - cannot have more partitions than number of tests")
      (assert (int? partition-index)
              "Invalid :partition/index - must be an integer")
      (assert (<= 0 partition-index (dec num-partitions))
              (format "Invalid :partition/index - must be between 0 and %d" (dec num-partitions)))
      (let [partition-index->tests (partition-tests-into-n-partitions num-partitions tests)
            partition              (get partition-index->tests partition-index)]
        (printf "Running tests in partition %d of %d (%d tests of %d)...\n"
                (inc partition-index)
                num-partitions
                (count partition)
                (count tests))
        partition))
    tests))

(defn find-tests-with-options
  "Find tests using the options map as passed to `clojure -X`."
  [{:keys [only], :as options}]
  (println "Running tests with options" (pr-str options))
  (when only
    (println "Running tests in" (pr-str only)))
  (let [start-time-ms (System/currentTimeMillis)
        tests         (-> (find-tests only options)
                          (partition-tests options))]
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
          (for [i (range 1 (inc n))]
            (do
             (println "----------------------------")
             (printf "Starting test iteration #%d\n" i)
             (run-tests test-vars options)))))

(defn- find-and-run-tests-with-options
  "Entrypoint for the test runner. `options` are passed directly to `eftest`; see https://github.com/weavejester/eftest
  for full list of options."
  [options]
  (let [start-time-ms   (System/currentTimeMillis)
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
