(ns mb.hawk.hooks
  (:require [methodical.core :as methodical]))

(methodical/defmulti before-run
  "Hooks to run before starting the test suite. A good place to do setup that needs to happen before running ANY tests.
  Add a new hook like this:

    (methodical/defmethod mb.hawk.hooks/before-run ::my-hook
      [_options]
      ...)

  `options` are the same options passed to the test runner as a whole, i.e. a combination of those specified in your
  `deps.edn` aliases as well as additional command-line options.

  The dispatch value is not particularly important -- one hook will run for each dispatch value -- but you should
  probably make it a namespaced keyword to avoid conflicts, and give it a docstring so people know why it's there. The
  orders the hooks are run in is indeterminate. The docstring for [[before-run]] is updated automatically as new hooks
  are added; you can check it to see which hooks are in use. Note that hooks will not be ran unless the namespace they
  live in is loaded; this may be affected by `:only` options passed to the test runner.

  Return values of methods are ignored; they are done purely for side effects."
  {:arglists '([options]), :defmethod-arities #{1}}
  :none
  :combo      (methodical/do-method-combination)
  :dispatcher (methodical/everything-dispatcher))

(methodical/defmethod before-run :default
  "Default hook for [[before-run]]; log a message about running before-run hooks."
  [_options]
  (println "Running before-run hooks..."))

(methodical/defmulti after-run
  "Hooks to run after finishing the test suite, regardless of whether it passed or failed. A good place to do cleanup
  after finishing the test suite. Add a new hook like this:

    (methodical/defmethod mb.hawk.hooks/after-run ::my-hook
      [_options]
      ...)

  `options` are the same options passed to the test runner as a whole, i.e. a combination of those specified in your
  `deps.edn` aliases as well as additional command-line options.

  The dispatch value is not particularly important -- one hook will run for each dispatch value -- but you should
  probably make it a namespaced keyword to avoid conflicts, and give it a docstring so people know why it's there. The
  orders the hooks are run in is indeterminate. The docstring for [[after-run]] is updated automatically as new hooks
  are added; you can check it to see which hooks are in use. Note that hooks will not be ran unless the namespace they
  live in is loaded; this may be affected by `:only` options passed to the test runner.

  Return values of methods are ignored; they are done purely for side effects."
  {:arglists '([options]), :defmethod-arities #{1}}
  :none
  :combo      (methodical/do-method-combination)
  :dispatcher (methodical/everything-dispatcher))

(methodical/defmethod after-run :default
  "Default hook for [[after-run]]; log a message about running after-run hooks."
  [_options]
  (println "Running after-run hooks..."))

(methodical/defmulti after-each
  "Hooks to run after each individual test var finishes (after the test itself, but before `:each` fixture teardown).
  Add a new hook like this:

    (methodical/defmethod mb.hawk.hooks/after-each ::my-hook
      [_options context]
      ...)

  `options` are the same options passed to the test runner as a whole, i.e. a combination of those specified in your
  `deps.edn` aliases as well as additional command-line options.

  `context` is a map describing the test that just ran:

  | Key              | Description                                                                                |
  |------------------|--------------------------------------------------------------------------------------------|
  | `:var`           | the test var that just ran                                                                 |
  | `:ns`            | the namespace of the test var                                                              |
  | `:report-events` | all `clojure.test` report event maps emitted during the test (`:pass`, `:fail`, `:error`, `:begin-test-var`, `:end-test-var`, ...), in order. Each event has `:testing-contexts` assoc'ed onto it: the value of `clojure.test/*testing-contexts*` (innermost first) when the event was emitted |
  | `:output`        | everything the test wrote to `*out*` or `*err*`, as a string                               |
  | `:summary`       | map of `:pass`/`:fail`/`:error` counts for this test var                                   |
  | `:duration-ms`   | wall-clock time the test var took, in milliseconds                                         |
  | `:parallel?`     | whether the test is a `^:parallel` test (and so may run concurrently with other tests)     |

  If a hook throws (or a `clojure.test` assertion inside it fails), it is reported as a test error/failure attributed
  to the test var -- it will fail the test suite and show up in the JUnit output -- and other after-each hooks for that
  test may not run. Hooks run on the same thread as the test, so for `^:parallel` tests they may run concurrently.

  Hooks run only for test vars that actually run: a var skipped because its `:each` fixture threw, or because an
  earlier failure tripped `:fail-fast?`, does not fire after-each hooks.

  Capturing test output and report events is skipped entirely when no after-each hooks are registered, so test runs
  without any hooks pay no overhead. Whether any hooks are registered is checked once at the start of each test run.

  Unlike [[before-run]] and [[after-run]] there is no default method (one would run once per test); register at least
  one hook to enable the machinery. The dispatch value is not particularly important -- one hook will run for each
  dispatch value -- but you should probably make it a namespaced keyword to avoid conflicts, and give it a docstring so
  people know why it's there. The orders the hooks are run in is indeterminate. The docstring for [[after-each]] is
  updated automatically as new hooks are added; you can check it to see which hooks are in use. Note that hooks will
  not be ran unless the namespace they live in is loaded; this may be affected by `:only` options passed to the test
  runner.

  Return values of methods are ignored; they are done purely for side effects."
  {:arglists '([options context]), :defmethod-arities #{2}}
  :none
  :combo      (methodical/do-method-combination)
  :dispatcher (methodical/everything-dispatcher))

(defn after-each-hooks-registered?
  "Whether any [[after-each]] hooks are currently registered. When this is false the test runner skips per-test
  output/event capture entirely. There is intentionally no default [[after-each]] method, so any registered method
  (including one on the `:default` dispatch value) counts as a hook."
  []
  (boolean
   (or (seq (methodical/primary-methods after-each))
       (seq (methodical/aux-methods after-each)))))
