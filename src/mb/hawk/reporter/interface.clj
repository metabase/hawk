(ns mb.hawk.reporter.interface
  (:require
   [eftest.report.pretty]
   [eftest.report.progress]))

(defmulti handle-event
  "a"
  {:arglists '([reporter event])}
  (fn [reporter event]
    [reporter (:type event)]))

(defmethod handle-event :default
  [_ _])

(defn reporter
  "Create a new test reporter/event handler, a function with the signature `(handle-event reporter event)`
  that gets called once for every [[clojure.test]] event, including stuff like `:begin-test-run`,
  `:end-test-var`, and `:fail`."
  [options]
  (let [stdout-reporter (case (:mode options)
                          (:cli/ci :repl) eftest.report.pretty/report
                          :cli/local      eftest.report.progress/report)
        reporters (descendants :hawk/reporter)]
    (fn [event]
      (doseq [reporter' reporters]
        (handle-event reporter' event))
      (stdout-reporter event))))

(defn register-reporter!
  "Register a reporter.

  `name` should be a namespaced keyword."
  [name]
  (derive name :hawk/reporter))
