(ns mb.hawk.junit.write-test
  (:require
   [clojure.test :refer :all]
   [clojure.xml :as xml]
   [mb.hawk.junit.write :as write])
  (:import
   (java.io ByteArrayInputStream StringWriter)
   (javax.xml.stream XMLOutputFactory XMLStreamWriter)))

(set! *warn-on-reflection* true)

(defn- result->element
  "Run `result` through the (private) assertion writer and parse the single element it produces, returning an XML
  map of the shape `{:tag ..., :attrs {...}, :content [...]}`."
  [result]
  (let [sw                 (StringWriter.)
        ^XMLStreamWriter w (.createXMLStreamWriter (XMLOutputFactory/newInstance) sw)]
    (.writeStartDocument w)
    (#'write/write-assertion-result!* w result)
    (.writeEndDocument w)
    (.flush w)
    (xml/parse (ByteArrayInputStream. (.getBytes (str sw) "UTF-8")))))

(def ^:private base-result
  {:file             "write_test.clj"
   :line             42
   :testing-contexts []
   :expected         1
   :actual           2
   :diffs            nil})

(deftest failure-message-attribute-test
  (testing "a :fail with a :message gets a `message` attribute on the <failure> element"
    (let [{:keys [tag attrs]} (result->element (assoc base-result :type :fail :message "should be equal"))]
      (is (= :failure tag))
      (is (= "should be equal" (:message attrs)))))

  (testing "a :fail without a :message has no `message` attribute"
    (let [{:keys [tag attrs]} (result->element (assoc base-result :type :fail :message nil))]
      (is (= :failure tag))
      (is (not (contains? attrs :message))))))

(def ^:private uncaught-message
  "The generic message `clojure.test` attaches to any exception that escapes a test var."
  "Uncaught exception, not in assertion.")

(deftest error-message-attribute-test
  (testing "an :error uses the exception's own message (not clojure.test's generic one) alongside `type`"
    (let [{:keys [tag attrs]} (result->element (assoc base-result
                                                      :type :error
                                                      :message uncaught-message
                                                      :actual (ex-info "kaboom" {})))]
      (is (= :error tag))
      (is (= "clojure.lang.ExceptionInfo" (:type attrs)))
      (is (= "kaboom" (:message attrs)))))

  (testing "an :error uses the *root cause* message when the exception has a cause chain"
    (let [{:keys [attrs]} (result->element (assoc base-result
                                                  :type :error
                                                  :message uncaught-message
                                                  :actual (ex-info "outer" {} (ex-info "deadlock - the real cause" {}))))]
      (is (= "deadlock - the real cause" (:message attrs)))))

  (testing "an :error falls back to the clojure.test :message when :actual is not a Throwable"
    (let [{:keys [attrs]} (result->element (assoc base-result
                                                  :type :error
                                                  :message "some non-exception message"
                                                  :actual :not-a-throwable))]
      (is (not (contains? attrs :type)))
      (is (= "some non-exception message" (:message attrs)))))

  (testing "an :error with no exception message and no :message has `type` but no `message`"
    (let [{:keys [attrs]} (result->element (assoc base-result
                                                  :type :error
                                                  :message nil
                                                  :actual (Throwable.)))]
      (is (= "java.lang.Throwable" (:type attrs)))
      (is (not (contains? attrs :message))))))

(defn- var-less-error->testcase
  "Run `result` through the (private) var-less error writer and parse the `<testcase>` element it produces."
  [result]
  (let [sw                 (StringWriter.)
        ^XMLStreamWriter w (.createXMLStreamWriter (XMLOutputFactory/newInstance) sw)]
    (.writeStartDocument w)
    (#'write/write-var-less-error!* w result)
    (.writeEndDocument w)
    (.flush w)
    (xml/parse (ByteArrayInputStream. (.getBytes (str sw) "UTF-8")))))

(deftest var-less-error-name-test
  (testing "a var-less error name is non-empty and never encodes a resolvable ns/var"
    (testing "with a namespace + fixture scope, the ns is included but not as an `ns/var` form"
      (let [nom (#'write/var-less-error-name {:testing-path ['metabase.foo-test :clojure.test/once-fixtures]
                                              :message      "Uncaught exception during fixture initialization."})]
        (is (seq nom))
        (is (re-find #"metabase\.foo-test" nom))
        (is (re-find #":once fixture" nom))
        ;; the collector attributes a failure by resolving `classname`/name to `ns/var`; the name must not look like one
        (is (not (re-find #"metabase\.foo-test/" nom)))))

    (testing "with no testing-path and no message, it still yields a non-empty name"
      (is (seq (#'write/var-less-error-name {}))))))

(deftest var-less-error-testcase-test
  (testing "a var-less error is emitted as a <testcase> with a non-empty name and NO classname"
    (let [{:keys [tag attrs content]}
          (var-less-error->testcase {:testing-path ['metabase.foo-test :clojure.test/once-fixtures]
                                     :message      "Uncaught exception during fixture initialization."
                                     :actual       (ex-info "fixture boom" {})})]
      (is (= :testcase tag))
      (is (seq (:name attrs)))
      ;; the crux of the contract: no resolvable var, so `parseJunit` yields `path: undefined` -> full rerun
      (is (not (contains? attrs :classname)))
      (let [{error-tag :tag, error-attrs :attrs} (first (filter :tag content))]
        (is (= :error error-tag))
        (is (= "clojure.lang.ExceptionInfo" (:type error-attrs)))
        (is (= "fixture boom" (:message error-attrs)))))))
