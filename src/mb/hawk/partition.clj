(ns mb.hawk.partition
  (:require
   [clojure.math :as math]))

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

(defn- namespace->num-tests
  "Return a map of

    namespace string =>  number of tests in that namespace"
  [test-vars]
  (reduce
   (fn [m test-var]
     (update m (namespace* test-var) (fnil inc 0)))
   {}
   test-vars))

(defn- test-var->ideal-partition
  "Return a map of

    test-var => ideal partition number

  'Ideal partition number' is the partition it would live in ideally if we weren't worried about making sure namespaces
  are grouped together."
  [num-partitions test-vars]
  (let [target-partition-size (/ (count test-vars) num-partitions)]
    (into {}
          (map-indexed (fn [i test-var]
                         (let [ideal-partition (long (math/floor (/ i target-partition-size)))]
                           (assert (<= 0 ideal-partition (dec num-partitions)))
                           [test-var ideal-partition]))
                       test-vars))))

(defn- namespace->possible-partitions
  "Return a map of

    namespace string => set of possible partition numbers for its tests

  For most namespaces there should only be one possible partition but for some the ideal split happens in the middle of
  the namespace which means we have two possible candidate partitions to put it into."
  [num-partitions test-vars]
  (let [test-var->ideal-partition (test-var->ideal-partition num-partitions test-vars)]
    (reduce
     (fn [m test-var]
       (update m (namespace* test-var) #(conj (set %) (test-var->ideal-partition test-var))))
     {}
     test-vars)))

(defn- namespace->partition
  "Return a map of

    namespace string => canonical partition number for its tests

  If there are multiple possible candidate partitions for a namespace, choose the one that has the least tests in it."
  [num-partitions test-vars]
  (let [namespace->num-tests           (namespace->num-tests test-vars)
        namespace->possible-partitions (namespace->possible-partitions num-partitions test-vars)
        ;; process all the namespaces that have no question about what partition they should go into first so we have as
        ;; accurate a picture of the size of each partition as possible before dealing with the ambiguous ones
        namespaces                    (distinct (map namespace* test-vars))
        multiple-possible-partitions? (fn [nmspace]
                                        (> (count (namespace->possible-partitions nmspace))
                                           1))
        namespaces                     (concat (remove multiple-possible-partitions? namespaces)
                                               (filter multiple-possible-partitions? namespaces))]
    ;; Keep track of how many tests are in each partition so far
    (:namespace->partition
     (reduce
      (fn [m nmspace]
        (let [partition (first (sort-by (fn [partition]
                                          (get-in m [:partition->size partition]))
                                        (namespace->possible-partitions nmspace)))]
          (-> m
              (update-in [:partition->size partition] (fnil + 0) (namespace->num-tests nmspace))
              (assoc-in [:namespace->partition nmspace] partition))))
      {}
      namespaces))))

(defn- make-test-var->partition
  "Return a function with the signature

    (f test-var) => partititon-number"
  [num-partitions test-vars]
  (let [namespace->partition (namespace->partition num-partitions test-vars)]
    (fn test-var->partition [test-var]
      (get namespace->partition (namespace* test-var)))))

(defn- partition-tests-into-n-partitions
  "Split a sequence of `test-vars` into `num-partitions`, returning a map of

    partition number => sequence of tests

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

(defn- validate-partition-options [tests {num-partitions :partition/total, partition-index :partition/index, :as _options}]
  (assert (and num-partitions partition-index)
          ":partition/total and :partition/index must be set together")
  (assert (pos-int? num-partitions)
          "Invalid :partition/total - must be a positive integer")
  (assert (<= num-partitions (count tests))
          "Invalid :partition/total - cannot have more partitions than number of tests")
  (assert (int? partition-index)
          "Invalid :partition/index - must be an integer")
  (assert (<= 0 partition-index (dec num-partitions))
          (format "Invalid :partition/index - must be between 0 and %d" (dec num-partitions))))

(defn partition-tests
  "Return only `tests` to run for the current partition (if `:partition/total` and `:partition/index` are specified). If
  they are not specified this returns all `tests`."
  [tests {num-partitions :partition/total, partition-index :partition/index, :as options}]
  (if (or num-partitions partition-index)
    (do
      (validate-partition-options tests options)
      (let [partition-index->tests (partition-tests-into-n-partitions num-partitions tests)
            partition              (get partition-index->tests partition-index)]
        (printf "Running tests in partition %d of %d (%d tests of %d)...\n"
                (inc partition-index)
                num-partitions
                (count partition)
                (count tests))
        partition))
    tests))
