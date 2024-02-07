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
  live in is loaded; this may be affected by `:only` options passed to the test runner."
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
  live in is loaded; this may be affected by `:only` options passed to the test runner."
  {:arglists '([options]), :defmethod-arities #{1}}
  :none
  :combo      (methodical/do-method-combination)
  :dispatcher (methodical/everything-dispatcher))

(methodical/defmethod after-run :default
  "Default hook for [[after-run]]; log a message about running after-run hooks."
  [_options]
  (println "Running after-run hooks..."))
