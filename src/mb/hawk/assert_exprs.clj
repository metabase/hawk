(ns mb.hawk.assert-exprs
  "Custom implementations of [[clojure.test/is]] expressions (i.e., implementations of [[clojure.test/assert-expr]]).
  `re=`, `schema=`, `=?`, and more."
  (:require
   [clojure.data :as data]
   [clojure.test :as t]
   [clojure.walk :as walk]
   [mb.hawk.assert-exprs.approximately-equal :as approximately-equal]
   [schema.core :as s]))

(defmethod t/assert-expr 're= [msg [_ pattern actual]]
  `(let [pattern#  ~pattern
         actual#   ~actual
         matches?# (when (string? actual#)
                     (re-matches pattern# actual#))]
     (assert (instance? java.util.regex.Pattern pattern#))
     (t/do-report
      {:type     (if matches?# :pass :fail)
       :message  ~msg
       :expected pattern#
       :actual   actual#
       :diffs    (when-not matches?#
                   [[actual# [pattern# nil]]])})))

(defmethod t/assert-expr 'schema=
  [message [_ schema actual]]
  `(let [schema# ~schema
         actual# ~actual
         pass?#  (nil? (s/check schema# actual#))]
     (t/do-report
      {:type     (if pass?# :pass :fail)
       :message  ~message
       :expected (s/explain schema#)
       :actual   actual#
       :diffs    (when-not pass?#
                   [[actual# [(s/check schema# actual#) nil]]])})))

(defn derecordize
  "Convert all record types in `form` to plain maps, so tests won't fail."
  [form]
  (walk/postwalk
   (fn [form]
     (if (record? form)
       (into {} form)
       form))
   form))

(defn- remove-keys-not-in-expected
  "Remove all the extra stuff (i.e. extra map keys or extra sequence elements) from the `actual` diff that's not in the
  original `expected` form."
  [expected actual]
  (cond
    (and (map? expected) (map? actual))
    (into {}
          (comp (filter (fn [[k _v]]
                          (contains? expected k)))
                (map (fn [[k v]]
                       [k (remove-keys-not-in-expected (get expected k) v)])))
          actual)

    (and (sequential? expected)
         (sequential? actual))
    (cond
      (empty? expected) []
      (empty? actual)   []
      :else             (into
                         [(remove-keys-not-in-expected (first expected) (first actual))]
                         (when (next expected)
                           (remove-keys-not-in-expected (next expected) (next actual)))))

    :else
    actual))

(defn- partial=-diff [expected actual]
  (let [actual'                           (remove-keys-not-in-expected expected actual)
        [only-in-actual only-in-expected] (data/diff actual' expected)]
    {:only-in-actual   only-in-actual
     :only-in-expected only-in-expected
     :pass?            (if (coll? only-in-expected)
                          (empty? only-in-expected)
                          (nil? only-in-expected))}))

(defn partial=-report
  "Impl for `partial=`. Don't call this directly."
  [message expected actual]
  (let [expected                                        (derecordize expected)
        actual                                          (derecordize actual)
        {:keys [only-in-actual only-in-expected pass?]} (partial=-diff expected actual)]
    {:type     (if pass? :pass :fail)
     :message  message
     :expected expected
     :actual   actual
     :diffs    [[actual [only-in-expected only-in-actual]]]}))

(defmethod t/assert-expr 'partial=
  [message [_ expected actual :as form]]
  (assert (= (count (rest form)) 2) "partial= expects exactly 2 arguments")
  `(t/do-report
    (partial=-report ~message ~expected ~actual)))

(defn =?-report
  "Implementation for `=?` -- don't use this directly."
  [message multifn expected actual]
  (let [diff (if multifn
               (approximately-equal/=?-diff* multifn expected actual)
               (approximately-equal/=?-diff* expected actual))]
    {:type     (if (not diff) :pass :fail)
     :message  message
     :expected expected
     :actual   actual
     :diffs    [[actual [diff nil]]]}))

(defmethod t/assert-expr '=?
  [message [_ & form]]
  (let [[multifn expected actual] (case (count form)
                                    2 (cons nil form)
                                    3 form
                                    (throw (ex-info "=? expects either 2 or 3 arguments" {:form form})))]
    `(t/do-report (=?-report ~message ~multifn ~expected ~actual))))
