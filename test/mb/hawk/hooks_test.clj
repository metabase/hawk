(ns mb.hawk.hooks-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [mb.hawk.core :as hawk]
   [mb.hawk.hooks :as hawk.hooks]
   [methodical.core :as methodical])
  (:import
   (java.io StringWriter)
   (java.util.concurrent BrokenBarrierException CyclicBarrier TimeUnit)))

(set! *warn-on-reflection* true)

(defn- silent-reporter [_event] nil)

(defn- counting-reporter
  "A reporter that records every event into `events-atom` and also increments the `clojure.test` report counters (the
  way the real eftest reporters do), so that the summary returned by [[hawk/run-tests]] reflects pass/fail/error
  counts."
  [events-atom]
  (fn [event]
    (swap! events-atom conj event)
    (when (#{:pass :fail :error} (:type event))
      (inc-report-counter (:type event)))))

(defn- make-test-var
  "Intern `test-fn` as a test var (as if defined with `deftest`) in scratch namespace `ns-symb`."
  [ns-symb var-symb test-fn & {:as extra-meta}]
  (let [v (intern (create-ns ns-symb) var-symb test-fn)]
    (alter-meta! v merge {:test test-fn} extra-meta)
    v))

(defn- do-with-after-each-hook
  "Run `thunk` with `hook-fn` registered as an [[mb.hawk.hooks/after-each]] hook on `dispatch-val` (default `::hook`)."
  ([hook-fn thunk]
   (do-with-after-each-hook ::hook hook-fn thunk))
  ([dispatch-val hook-fn thunk]
   (methodical/add-primary-method! #'hawk.hooks/after-each dispatch-val hook-fn)
   (try
     (thunk)
     (finally
       (methodical/remove-primary-method! #'hawk.hooks/after-each dispatch-val)))))

(deftest after-each-hooks-registered?-test
  (is (false? (hawk.hooks/after-each-hooks-registered?)))
  (do-with-after-each-hook
   (fn [_options _context] nil)
   (fn []
     (is (true? (hawk.hooks/after-each-hooks-registered?)))))
  (is (false? (hawk.hooks/after-each-hooks-registered?)))
  (testing "a hook registered on the :default dispatch value still counts (there is no built-in :default method)"
    (do-with-after-each-hook
     :default
     (fn [_options _context] nil)
     (fn []
       (is (true? (hawk.hooks/after-each-hooks-registered?)))))
    (is (false? (hawk.hooks/after-each-hooks-registered?)))))

(deftest after-each-context-test
  (let [scratch-ns 'mb.hawk.hooks-test.context-scratch
        v          (make-test-var scratch-ns 'passing-test
                                  (fn []
                                    (testing "context"
                                      (println "hello from out")
                                      (binding [*out* *err*]
                                        (println "hello from err"))
                                      (is (= 1 1))
                                      (is (= 2 2)))))
        hook-calls (atom [])
        err-buf    (StringWriter.)]
    (try
      (let [out-str (with-out-str
                      (binding [*err* err-buf]
                        (do-with-after-each-hook
                         (fn [options context]
                           (swap! hook-calls conj {:options options, :context context}))
                         (fn []
                           (hawk/run-tests [v] {:report silent-reporter, ::custom-option 42})))))]
        (testing "captured test output should still make it to the original *out*/*err*"
          (is (str/includes? out-str "hello from out"))
          (is (str/includes? (str err-buf) "hello from err"))))
      (is (= 1 (count @hook-calls)))
      (let [{:keys [options context]} (first @hook-calls)]
        (testing "options"
          (is (= 42 (::custom-option options)))
          (is (= :repl (:mode options))))
        (testing "context"
          (is (= v (:var context)))
          (is (= (find-ns scratch-ns) (:ns context)))
          (is (= [:begin-test-var :pass :pass :end-test-var]
                 (map :type (:report-events context))))
          (testing "report events should include the testing context they were emitted in"
            (is (every? #(= ["context"] (:testing-contexts %))
                        (filter #(= :pass (:type %)) (:report-events context)))))
          (is (str/includes? (:output context) "hello from out"))
          (is (str/includes? (:output context) "hello from err"))
          (is (= {:pass 2, :fail 0, :error 0} (:summary context)))
          (is (number? (:duration-ms context)))
          (is (not (:parallel? context)))))
      (finally
        (remove-ns scratch-ns)))))

(deftest after-each-failing-and-erroring-tests-test
  (let [scratch-ns 'mb.hawk.hooks-test.failing-scratch
        failing    (make-test-var scratch-ns 'failing-test
                                  (fn [] (is (= 1 2) "numbers should be equal")))
        erroring   (make-test-var scratch-ns 'erroring-test
                                  (fn [] (throw (ex-info "boom" {}))))
        contexts   (atom {})]
    (try
      (do-with-after-each-hook
       (fn [_options context]
         (swap! contexts assoc (:name (meta (:var context))) context))
       (fn []
         (hawk/run-tests [failing erroring] {:report silent-reporter})))
      (testing "failing test"
        (let [context (get @contexts 'failing-test)]
          (is (= {:pass 0, :fail 1, :error 0} (:summary context)))
          (let [fail-event (first (filter #(= :fail (:type %)) (:report-events context)))]
            (is (= "numbers should be equal" (:message fail-event)))
            (is (contains? fail-event :expected))
            (is (contains? fail-event :actual)))))
      (testing "erroring test"
        (let [context (get @contexts 'erroring-test)]
          (is (= {:pass 0, :fail 0, :error 1} (:summary context)))
          (let [error-event (first (filter #(= :error (:type %)) (:report-events context)))]
            (is (= "boom" (ex-message (:actual error-event)))))))
      (finally
        (remove-ns scratch-ns)))))

(deftest after-each-hook-error-test
  (let [scratch-ns  'mb.hawk.hooks-test.hook-error-scratch
        v1          (make-test-var scratch-ns 'test-1 (fn [] (is (= 1 1))))
        v2          (make-test-var scratch-ns 'test-2 (fn [] (is (= 1 1))))
        seen-events (atom [])
        ran         (atom [])
        summary     (do-with-after-each-hook
                     (fn [_options context]
                       (swap! ran conj (:name (meta (:var context))))
                       (throw (ex-info "hook boom" {})))
                     (fn []
                       (try
                         (hawk/run-tests [v1 v2] {:report (counting-reporter seen-events)})
                         (finally
                           (remove-ns scratch-ns)))))]
    (testing "an exception in an after-each hook should be reported as a test error"
      (let [error-events (filter #(= :error (:type %)) @seen-events)]
        (is (= 2 (count error-events)))
        (is (every? #(re-find #"after-each hook" (str (:message %))) error-events))
        (is (= "hook boom" (ex-message (:actual (first error-events)))))))
    (testing "the hook error should fail the suite (be counted in the summary)"
      (is (= 2 (:error summary))))
    (testing "the hook error :error event should be reported before the var's :end-test-var, so it is attributed to
             the var (e.g. in JUnit output) instead of landing after the var's reporting window has closed"
      (doseq [v [v1 v2]]
        (let [types (->> @seen-events
                         (filter #(= v (:var %)))
                         (map :type)
                         (filter #{:error :end-test-var}))]
          (is (= [:error :end-test-var] types)
              (format "hook :error should precede :end-test-var for %s" v)))))
    (testing "a hook error for one test should not prevent later tests from running"
      (is (= '[test-1 test-2] @ran)))))

(deftest after-each-hook-assertion-attributed-test
  (testing "a hook runs while the test var is still the one being reported on, so a clojure.test assertion (or error)
           inside the hook is attributed to that var -- this is what keeps var-aware reporters like JUnit from
           mis-attributing or crashing on a hook failure"
    (let [scratch-ns   'mb.hawk.hooks-test.hook-assertion-scratch
          v            (make-test-var scratch-ns 'the-test (fn [] (is (= 1 1))))
          testing-vars (atom ::unset)
          seen-events  (atom [])]
      (try
        (do-with-after-each-hook
         (fn [_options _context]
           ;; clojure.test attributes a report event to the var in (last *testing-vars*); verify it is still set
           (reset! testing-vars (vec *testing-vars*))
           (is (= :a :b) "assertion inside hook"))
         (fn []
           (hawk/run-tests [v] {:report (counting-reporter seen-events)})))
        (testing "the test var is the current (innermost) var in *testing-vars* while the hook runs"
          ;; NOTE: because this test runs `hawk/run-tests` nested inside a test that is itself run by hawk,
          ;; *testing-vars* also contains the outer test var; in a normal (non-nested) run it is exactly `[v]`.
          ;; `clojure.test`/JUnit attribute to the innermost var, which is what we assert here.
          (is (= v (first @testing-vars))))
        (testing "the hook's failing assertion is reported (and the run did not crash)"
          (let [fail-events (filter #(= :fail (:type %)) @seen-events)]
            (is (= 1 (count fail-events)))
            (is (= "assertion inside hook" (:message (first fail-events))))))
        (finally
          (remove-ns scratch-ns))))))

(deftest no-hooks-fast-path-test
  (let [scratch-ns 'mb.hawk.hooks-test.fast-path-scratch
        outer-out  *out*
        seen-out   (atom nil)
        v          (make-test-var scratch-ns 'out-test
                                  (fn []
                                    (reset! seen-out *out*)
                                    (is (= 1 1))))]
    (try
      (is (false? (hawk.hooks/after-each-hooks-registered?)))
      (hawk/run-tests [v] {:report silent-reporter})
      (testing "*out* should not get wrapped when no after-each hooks are registered"
        (is (identical? outer-out @seen-out)))
      (finally
        (remove-ns scratch-ns)))))

(deftest after-each-parallel-test
  (let [scratch-ns 'mb.hawk.hooks-test.parallel-scratch
        ;; a barrier forces both ^:parallel tests to be in-flight at the same time, so the test fails loudly if they
        ;; are not actually run concurrently, and proves the per-test output buffers stay isolated under concurrency
        ^CyclicBarrier barrier (CyclicBarrier. 2)
        await!     (fn [] (try
                            (.await barrier 10 TimeUnit/SECONDS)
                            (catch BrokenBarrierException _ nil)))
        body       (fn [c] (fn []
                             (await!)
                             (dotimes [_ 100] (print c))
                             (is (= 1 1))))
        v1         (make-test-var scratch-ns 'parallel-test-1 (body "1") :parallel true)
        v2         (make-test-var scratch-ns 'parallel-test-2 (body "2") :parallel true)
        contexts   (atom {})]
    (try
      (with-out-str
        (do-with-after-each-hook
         (fn [_options context]
           (swap! contexts assoc (:name (meta (:var context))) context))
         (fn []
           (hawk/run-tests [v1 v2] {:report silent-reporter}))))
      (let [context-1 (get @contexts 'parallel-test-1)
            context-2 (get @contexts 'parallel-test-2)]
        (is (true? (:parallel? context-1)))
        (is (true? (:parallel? context-2)))
        (testing "each test's captured output should be isolated from the other parallel test's output"
          (is (= (apply str (repeat 100 "1")) (:output context-1)))
          (is (= (apply str (repeat 100 "2")) (:output context-2)))))
      (finally
        (remove-ns scratch-ns)))))
