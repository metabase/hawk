(ns mb.hawk.junit.write
  "Logic related to writing test results for a namespace to a JUnit XML file. See
  https://stackoverflow.com/a/9410271/1198455 for the JUnit output spec."
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.stacktrace :as stacktrace]
   [clojure.string :as str]
   [pjstadig.print :as p])
  (:import
   (java.util.concurrent Executors ThreadFactory ThreadPoolExecutor TimeUnit)
   (javax.xml.stream XMLOutputFactory XMLStreamWriter)
   (org.apache.commons.io FileUtils)))

(set! *warn-on-reflection* true)

(def ^String ^:private output-dir "target/junit")

(defn clean-output-dir!
  "Clear any files in the output dir; create it if needed."
  []
  (let [file (io/file output-dir)]
    (when (and (.exists file)
               (.isDirectory file))
      (FileUtils/deleteDirectory file))
    (.mkdirs file)))

;; TODO -- not sure it makes sense to do this INSIDE OF CDATA ELEMENTS!!!
(defn- escape-unprintable-characters
  [s]
  (str/join (for [^char c s]
              (if (and (Character/isISOControl c)
                       (not (Character/isWhitespace c)))
                (format "&#%d;" (int c))
                c))))

(defn- decolorize [s]
  (some-> s (str/replace #"\[[;\d]*m" "")))

(defn- decolorize-and-escape
  "Remove ANSI color escape sequences, then encode things as character entities as needed"
  ^String [s]
  (-> s decolorize escape-unprintable-characters))

(defn- print-result-description [{:keys [file line message testing-contexts], :as _result}]
  (println (format "%s:%d" file line))
  (doseq [s (reverse testing-contexts)]
    (println (str/trim (decolorize-and-escape (str s)))))
  (when message
    (println (decolorize-and-escape message))))

(defn- print-expected [expected actual]
  (p/rprint "expected: ")
  (pprint/pprint expected)
  (p/rprint "  actual: ")
  (pprint/pprint actual)
  (p/clear))

(defn- write-result-output!
  [^XMLStreamWriter w {:keys [expected actual diffs], :as result}]
  (.writeCharacters w "\n")
  (let [s (with-out-str
            (println)
            (print-result-description result)
            ;; this code is adapted from `pjstadig.util`
            (p/with-pretty-writer
              (fn []
                (if (seq diffs)
                  (doseq [[actual [a b]] diffs]
                    (print-expected expected actual)
                    (p/rprint "    diff:")
                    (if a
                      (do (p/rprint " - ")
                          (pprint/pprint a)
                          (p/rprint "          + "))
                      (p/rprint " + "))
                    (when b
                      (pprint/pprint b))
                    (p/clear))
                  (print-expected expected actual)))))]
    (.writeCData w (decolorize-and-escape s))))

(defn- write-attributes! [^XMLStreamWriter w m]
  (doseq [[k v] m]
    (.writeAttribute w (name k) (str v))))

(defn- write-element! [^XMLStreamWriter w ^String element-name attributes write-children!]
  (.writeCharacters w "\n")
  (.writeStartElement w element-name)
  (when (seq attributes)
    (write-attributes! w attributes))
  (write-children!)
  (.writeCharacters w "\n")
  (.writeEndElement w))

(defn- root-cause-message
  "Message of the root cause of `e` (the deepest exception in its cause chain). `clojure.test` reports an uncaught
  exception with the generic message \"Uncaught exception, not in assertion.\", so for `:error` results the
  exception's own message is far more useful as the `message` attribute."
  [^Throwable e]
  (ex-message (last (take-while some? (iterate ex-cause e)))))

(defn- error-element-attributes
  "Attributes for an `<error>` element built from an `:error` result: the exception's class as `type` and its
  root-cause message as `message`. Prefers the exception's own (root cause) message over `clojure.test`'s generic
  \"Uncaught exception...\" message, falling back to the latter when `actual` is not a Throwable or the exception has
  no message."
  [{:keys [actual message]}]
  (let [message (or (when (instance? Throwable actual)
                      (root-cause-message actual))
                    message)]
    (cond-> nil
      (instance? Throwable actual) (assoc :type (.getCanonicalName (class actual)))
      message                      (assoc :message (decolorize-and-escape message)))))

(defmulti ^:private write-assertion-result!*
  {:arglists '([^XMLStreamWriter w result])}
  (fn [_ result] (:type result)))

(defmethod write-assertion-result!* :pass
  [_ _]
  nil)

(defmethod write-assertion-result!* :fail
  [w {:keys [message], :as result}]
  (write-element!
   w "failure"
   (when message
     {:message (decolorize-and-escape message)})
   (fn []
     (write-result-output! w result))))

(defmethod write-assertion-result!* :error
  [w result]
  (write-element!
   w "error"
   (error-element-attributes result)
   (fn []
     (write-result-output! w result))))

(defn- write-assertion-result! [w result]
  (try
    (write-assertion-result!* w result)
    (catch Throwable e
      (throw (ex-info (str "Error writing XML for test assertion result: " (ex-message e))
                      {:result result}
                      e)))))

(defn- write-var-result! [^XMLStreamWriter w result]
  (try
    (.writeCharacters w "\n")
    (write-element!
     w "testcase"
     {:classname  (name (ns-name (:ns result)))
      :name       (name (symbol (:var result)))
      :time       (/ (:duration-ms result) 1000.0)
      :assertions (:assertion-count result)}
     (fn []
       (doseq [result (:results result)]
         (write-assertion-result! w result))))
    (catch Throwable e
      (throw (ex-info (str "Error writing XML for test var result: " (ex-message e))
                      {:result result}
                      e)))))

;; write one output file for each test namespace.

(defn- write-ns-result!*
  ([{test-namespace :ns, :as result}]
   (let [filename (str (munge (ns-name (the-ns test-namespace))) ".xml")]
     (with-open [w (.createXMLStreamWriter (XMLOutputFactory/newInstance)
                                           (io/writer (io/file output-dir filename)
                                                      :encoding "UTF-8"))]
       (.writeStartDocument w)
       (write-ns-result!* w result)
       (.writeEndDocument w))))

  ([w {test-namespace :ns, :as result}]
   (try
     (write-element!
      w "testsuite"
      {:name      (name (ns-name test-namespace))
       :time      (/ (:duration-ms result) 1000.0)
       :timestamp (str (:timestamp result))
       :tests     (:test-count result)
       :errors    (:error-count result)
       :failures  (:failure-count result)}
      (fn []
        (doseq [result (:results result)]
          (write-var-result! w result))))
     (catch Throwable e
       (throw (ex-info (str "Error writing XML for test namespace result: " (ex-message e))
                       {:result result}
                       e))))))

;;;; Var-less errors
;;;;
;;;; Some `:error`s belong to no test var (and sometimes no namespace) -- a `:once`/`:each` fixture-init throw, or a
;;;; namespace load/compile error. `clojure.test`/eftest still counts them toward the run's error total, and
;;;; `mb.hawk.core` derives the process exit code from that total, so an error like this *fails the run*. But the
;;;; namespace->var keyed writer above has nowhere to put an error with no var, so historically these were dropped
;;;; from JUnit entirely: the exit code and the JUnit output disagreed, and any consumer reconstructing the failed
;;;; set from JUnit (e.g. to compute a narrow rerun selector) would silently miss the error.
;;;;
;;;; We collect them here and emit them into their own file at `:summary`. Each becomes a `<testcase>` with a
;;;; non-empty `name` but deliberately NO `classname` -- see `var-less-error-name`.

(defonce ^:private var-less-errors (atom []))

(defn reset-var-less-errors!
  "Discard var-less errors accumulated by a previous run. Called at `:begin-test-run`."
  []
  (reset! var-less-errors []))

(defn record-var-less-error!
  "Remember a var-less `:error` result so it can be written to JUnit output at the end of the run."
  [result]
  (swap! var-less-errors conj result))

(defn- var-less-error-name
  "A non-empty, human-readable `name` for a var-less error's `<testcase>`. Includes the namespace and fixture scope
  when known (purely for readability). It deliberately does NOT encode a resolvable namespace+var: there is no var,
  so a consumer that reconstructs a rerun selector from JUnit must treat this error as unattributable (and rerun
  everything) rather than target a nonexistent var."
  [{:keys [testing-path message]}]
  (let [[test-ns scope] testing-path
        scope-str       (case scope
                          :clojure.test/once-fixtures ":once fixture"
                          :clojure.test/each-fixtures  ":each fixture"
                          nil)
        where           (when test-ns
                          (str test-ns (when scope-str (str " " scope-str))))]
    (str (or (not-empty message) "Uncaught error with no associated test var")
         (when where (format " (%s)" where)))))

(defn- write-var-less-error!* [^XMLStreamWriter w {:keys [actual] :as result}]
  (write-element!
   w "testcase"
   ;; NOTE: intentionally no `classname` -- see `var-less-error-name`.
   {:name (decolorize-and-escape (var-less-error-name result))}
   (fn []
     (write-element!
      w "error"
      (error-element-attributes result)
      (fn []
        (when (instance? Throwable actual)
          (.writeCharacters w "\n")
          (.writeCData w (decolorize-and-escape
                          (with-out-str (stacktrace/print-cause-trace actual))))))))))

(defn write-var-less-errors!
  "Write any var-less errors collected during the run to their own JUnit file. Emitting them keeps JUnit output
  consistent with the run's error total (and exit code) so downstream consumers don't silently lose them. Does
  nothing when there were no var-less errors."
  []
  (let [errors @var-less-errors]
    (when (seq errors)
      (with-open [w (.createXMLStreamWriter (XMLOutputFactory/newInstance)
                                            (io/writer (io/file output-dir "mb_hawk_var_less_errors.xml")
                                                       :encoding "UTF-8"))]
        (.writeStartDocument w)
        (write-element!
         w "testsuite"
         {:name     "mb.hawk.var-less-errors"
          :tests    (count errors)
          :errors   (count errors)
          :failures 0}
         (fn []
           (doseq [error errors]
             (write-var-less-error!* w error))))
        (.writeEndDocument w)))))

(defonce ^:private thread-pool (atom nil))

(defn create-thread-pool!
  "Create a thread pool to write JUnit output with. JUnit output is written in background threads so tests are not
  slowed down by it."
  []
  (let [[^ThreadPoolExecutor old-val] (reset-vals! thread-pool (Executors/newCachedThreadPool
                                                                (reify ThreadFactory
                                                                  (newThread [_ r]
                                                                    (doto (Thread. r)
                                                                      (.setName "JUnit XML output writer")
                                                                      (.setDaemon true))))))]
    (when old-val
      (.shutdown old-val))))

(defn write-ns-result!
  "Submit a background thread task to write the JUnit output for the tests in a namespace when an `:end-test-ns` event
  is encountered."
  [result]
  (when @thread-pool
    (let [^Callable thunk (fn []
                            (write-ns-result!* result))]
      (.submit ^ThreadPoolExecutor @thread-pool thunk))))

(defn wait-for-writes-to-finish
  "Wait up to 10 seconds for the thread pool that writes results to finish."
  []
  (when @thread-pool
    (.shutdown ^ThreadPoolExecutor @thread-pool)
    (.awaitTermination ^ThreadPoolExecutor @thread-pool 10 TimeUnit/SECONDS)
    (reset! thread-pool nil)))
