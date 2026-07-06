(ns bench.core
  "JVM vs GraalVM native-image benchmarks over coffi FFI calls.

  cpu  — 3M small ack(2,3) calls (FFI call overhead) plus one ack(3,11)
         (~1e9 recursive calls of pure C CPU work).
  mem  — C fills 16MB native float buffers; each is deserialized into a
         boxed Clojure vector (4M Floats) with a sliding window of 3
         retained, producing sustained managed-heap churn.

  Peak RSS and consumed CPU are read from /proc/self at the end, so both
  execution modes are measured identically and include startup."
  (:require
   [clojure.string :as str]
   [coffi.ffi :as ffi :refer [defcfn]]
   [coffi.mem :as mem])
  (:gen-class))

(defcfn ack
  "ack" [::mem/long ::mem/long] ::mem/long)

(defcfn fill-floats
  "fill_floats" [::mem/pointer ::mem/long] ::mem/void)

(defn- read-proc
  "Reads a procfs file; procfs reports a zero size, which trips some reader
  paths, so read the raw bytes."
  ^String [path]
  (String. (java.nio.file.Files/readAllBytes (.toPath (java.io.File. ^String path)))
           "UTF-8"))

(defn- report-self!
  "Prints this process's peak RSS and consumed CPU from /proc/self."
  []
  (let [hwm-kb (some->> (read-proc "/proc/self/status")
                        (re-find #"VmHWM:\s+(\d+) kB")
                        second
                        parse-long)
        stat (str/split (read-proc "/proc/self/stat") #"\s+")
        ticks (+ (parse-long (nth stat 13)) (parse-long (nth stat 14)))]
    (println (format "peak_rss_mb=%d cpu_s=%.2f" (quot hwm-kb 1024) (/ ticks 100.0)))))

(defn- cpu-bench [^long small-iters ^long big-n]
  ;; phase 1: FFI call overhead — many tiny calls, work is negligible
  (let [start (System/nanoTime)
        small (loop [i 0, acc 0]
                (if (< i small-iters)
                  (recur (inc i) (+ acc (long (ack 2 3))))
                  acc))
        calls-ns (- (System/nanoTime) start)
        ;; phase 2: raw C compute — one deep recursion, one call boundary
        start (System/nanoTime)
        big (ack 3 big-n)
        compute-ns (- (System/nanoTime) start)]
    (println (format "phase=calls n=%d total_ms=%d per_call_ns=%d"
                     small-iters (quot calls-ns 1000000)
                     (quot calls-ns small-iters)))
    (println (format "phase=compute ms=%d" (quot compute-ns 1000000)))
    (println "checksum:" (+ small (long big)))))

(defn- mem-bench [^long n ^long iters]
  (loop [iter 0, retained (), checksum 0.0]
    (if (< iter iters)
      (let [v (with-open [arena (mem/confined-arena)]
                (let [seg (mem/alloc (* n mem/float-size) arena)]
                  (fill-floats seg n)
                  (mem/deserialize-from seg [::mem/array ::mem/float n])))]
        (recur (inc iter)
               (take 3 (cons v retained))
               (+ checksum (double (nth v 0)) (double (nth v (dec n))))))
      (println "checksum:" checksum "retained:" (count retained)))))

(defn- ack-clj
  "Ackermann in pure Clojure with primitive math; mirrors native/bench.c, so
  jvm-vs-native compiled-code quality can be compared with no FFI involved."
  ^long [^long m ^long n]
  (cond
    (zero? m) (inc n)
    (zero? n) (ack-clj (dec m) 1)
    :else (ack-clj (dec m) (ack-clj m (dec n)))))

(defn- cpu-pure-bench [^long big-n]
  (let [start (System/nanoTime)
        r (ack-clj 3 big-n)
        compute-ns (- (System/nanoTime) start)]
    (println (format "phase=compute ms=%d" (quot compute-ns 1000000)))
    (println "checksum:" r)))

(defn- mem-pure-bench
  "The mem benchmark's allocation churn with the native buffer replaced by
  pure Clojure generation; isolates GC and allocation performance."
  [^long n ^long iters]
  (loop [iter 0, retained (), checksum 0.0]
    (if (< iter iters)
      (let [v (mapv (fn [i] (float (* (bit-and (long i) 1023) 0.5))) (range n))]
        (recur (inc iter)
               (take 3 (cons v retained))
               (+ checksum (double (nth v 0)) (double (nth v (dec n))))))
      (println "checksum:" checksum "retained:" (count retained)))))

(defn- cpu-java-bench
  "Same calls phase as [[cpu-bench]], but through BenchStub's static final
  downcall handle (the jextract pattern) instead of coffi."
  [^long small-iters]
  (let [start (System/nanoTime)
        small (loop [i 0, acc 0]
                (if (< i small-iters)
                  (recur (inc i) (+ acc (BenchStub/ack 2 3)))
                  acc))
        calls-ns (- (System/nanoTime) start)]
    (println (format "phase=calls n=%d total_ms=%d per_call_ns=%d"
                     small-iters (quot calls-ns 1000000)
                     (quot calls-ns small-iters)))
    (println "checksum:" small)))

(defn -main [& [mode]]
  ;; the -pure modes involve no native calls at all
  (when-not (contains? #{"cpu-pure" "mem-pure"} mode)
    (ffi/load-library "native/libbench.so"))
  (case mode
    "cpu" (cpu-bench 1000000 11)
    "cpu-java" (cpu-java-bench 1000000)
    "cpu-pure" (cpu-pure-bench 11)
    "mem" (mem-bench 4000000 30)
    "mem-pure" (mem-pure-bench 4000000 30)
    ;; tiny run for the native-image tracing agent: exercises every
    ;; descriptor and code path without taking real time
    "warmup" (do (cpu-bench 10 3)
                 (cpu-java-bench 10)
                 (mem-bench 1000 3)))
  (report-self!)
  (System/exit 0))
