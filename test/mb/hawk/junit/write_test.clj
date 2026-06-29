(ns mb.hawk.junit.write-test
  (:require
   [clojure.test :refer :all]
   [clojure.xml :as xml]
   [mb.hawk.junit.write :as write])
  (:import
   (java.io ByteArrayInputStream StringWriter)
   (javax.xml.stream XMLOutputFactory)))

(defn- result->element
  "Run `result` through the (private) assertion writer and parse the single element it produces, returning an XML
  map of the shape `{:tag ..., :attrs {...}, :content [...]}`."
  [result]
  (let [sw (StringWriter.)
        w  (.createXMLStreamWriter (XMLOutputFactory/newInstance) sw)]
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

(deftest error-message-attribute-test
  (testing "an :error carries both `type` and `message` attributes on the <error> element"
    (let [{:keys [tag attrs]} (result->element (assoc base-result
                                                       :type :error
                                                       :message "boom"
                                                       :actual (ex-info "kaboom" {})))]
      (is (= :error tag))
      (is (= "clojure.lang.ExceptionInfo" (:type attrs)))
      (is (= "boom" (:message attrs)))))

  (testing "an :error without a :message still has its `type` attribute and no `message`"
    (let [{:keys [tag attrs]} (result->element (assoc base-result
                                                       :type :error
                                                       :message nil
                                                       :actual (ex-info "kaboom" {})))]
      (is (= :error tag))
      (is (= "clojure.lang.ExceptionInfo" (:type attrs)))
      (is (not (contains? attrs :message))))))
