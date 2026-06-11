(ns mb.eftest.report-test
  (:require
   [clojure.test :refer :all]
   [mb.eftest.output-capture :as output-capture]
   [mb.eftest.report :as report]
   [mb.eftest.report.pretty :as pretty]
   [puget.printer :as puget]))

(def ^:private this-ns *ns*)

(deftest file-and-line-in-pretty-fail-report
  (let [pretty-nil (puget/pprint-str nil {:print-color true
                                          :print-meta false})
        result (with-out-str
                 (binding [*test-out* *out*
                           pretty/*fonts* {}
                           report/*testing-path* [this-ns #'file-and-line-in-pretty-fail-report]
                           *report-counters* (ref *initial-report-counters*)]
                   (output-capture/with-test-buffer
                     (pretty/report {:type :fail
                                     :file "report_test.clj"
                                     :line 999
                                     :message "foo"}))))]
    (is (= (str "\nFAIL in mb.eftest.report-test/file-and-line-in-pretty-fail-report"
                " (report_test.clj:999)\n"
                "foo\n"
                "expected: "
                pretty-nil
                "\n  actual: "
                pretty-nil
                "\n")
           result))))
