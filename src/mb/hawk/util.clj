(ns mb.hawk.util)

(defn format-nanoseconds
  "Format a time interval in nanoseconds to something more readable. (µs/ms/etc.)"
  ^String [nanoseconds]
  ;; The basic idea is to take `n` and see if it's greater than the divisior. If it is, we'll print it out as that
  ;; unit. If more, we'll divide by the divisor and recur, trying each successively larger unit in turn. e.g.
  ;;
  ;; (format-nanoseconds 500)    ; -> "500 ns"
  ;; (format-nanoseconds 500000) ; -> "500 µs"
  (loop [n nanoseconds, [[unit divisor] & more] [[:ns 1000]
                                                 [:µs 1000]
                                                 [:ms 1000]
                                                 [:s 60]
                                                 [:mins 60]
                                                 [:hours 24]
                                                 [:days 7]
                                                 [:weeks (/ 365.25 7)]
                                                 [:years Double/POSITIVE_INFINITY]]]
    (if (and (> n divisor)
             (seq more))
      (recur (/ n divisor) more)
      (format "%.1f %s" (double n) (name unit)))))

(defn format-microseconds
  "Format a time interval in microseconds into something more readable."
  ^String [microseconds]
  (format-nanoseconds (* 1000.0 microseconds)))

(defn format-milliseconds
  "Format a time interval in milliseconds into something more readable."
  ^String [milliseconds]
  (format-microseconds (* 1000.0 milliseconds)))
