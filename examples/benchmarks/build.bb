#!/usr/bin/env bb
;; Builds bench.core both as AOT classes (JVM mode) and a native image, then
;; runs the cpu and mem benchmarks in both modes. Wall time is measured here;
;; each run prints its own peak RSS and CPU seconds from /proc/self.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str])

(fs/create-dirs "classes")
(fs/create-dirs "graal-config")

(println "=== compiling bench shared library ===")
(shell "gcc" "-O2" "-shared" "-fPIC" "native/bench.c" "-o" "native/libbench.so")

(println "=== compiling java stubs ===")
(shell "javac" "-d" "classes" "java/BenchStub.java" "java/Reference.java")

(println "=== machine reference scores ===")
;; fixed zero-dependency workload; lets readers rescale published numbers to
;; their own machine (see README and Dockerfile.reference)
(def ref-out (:out (shell {:out :string} "java" "-Xmx2g" "-cp" "classes" "Reference")))
(print ref-out)
(flush)
(def ref-score
  (parse-long (second (re-find #"REFERENCE .*score=(\d+)" ref-out))))

(println "=== AOT compiling ===")
;; direct linking is the standard configuration for Clojure native-image
;; builds; without it every call goes through a Var, which C2 devirtualizes
;; with runtime profiles but Graal AOT cannot — skipping it would handicap
;; specifically the native side. The same classes run on the JVM side.
(shell "clojure" "-J--enable-native-access=ALL-UNNAMED"
       "-J-Dclojure.compiler.direct-linking=true"
       "-M" "-e" "(compile 'bench.core)")

(def cp
  (let [jars (->> (str/split (str/trim (:out (shell {:out :string} "clojure" "-Spath"))) #":")
                  (filter #(str/ends-with? % ".jar")))]
    (str/join ":" (cons "classes" jars))))

(println "=== coffi-generated foreign metadata ===")
;; BenchStub's descriptor (jlong(jlong,jlong)) is covered too: registration
;; is per descriptor, and coffi's ack fn records the identical one
(shell "clojure" "-J--enable-native-access=ALL-UNNAMED" "-M" "-e"
       "(require 'bench.core) (println (coffi.ffi/write-native-image-metadata! \"graal-config\"))")

(def flags
  ["--no-fallback"
   "--features=clj_easy.graal_build_time.InitClojureClasses"
   "--initialize-at-build-time=org.objectweb.asm,bench"
   "--enable-native-access=ALL-UNNAMED"
   "-H:+UnlockExperimentalVMOptions"
   "-H:ConfigurationFileDirectories=graal-config"
   "-H:+ReportExceptionStackTraces"
   ;; the ackermann benchmarks recurse ~16k deep; frame sizes vary by
   ;; edition/GC, so pin a roomy runtime default stack
   "-R:StackSize=16m"])

;; the mem benchmarks are GC benchmarks: results scale with the heap ceiling,
;; so pin the same max heap on both modes instead of letting JVM ergonomics
;; (1/4 of RAM) and native-image defaults each pick their own
(def max-heap "-Xmx4g")

;; PGO=1 builds an instrumented binary, runs the benchmarks to collect a
;; profile, and feeds it to the optimizing build (Oracle GraalVM only)
(def pgo-modes
  ;; every benchmarked mode, including cpu-java: paths missing from the
  ;; profile get marked cold and end up slower than with no PGO at all
  ["cpu-pure" "mem-pure" "cpu" "cpu-java" "mem"])

(def pgo-flag
  (when (System/getenv "PGO")
    (println "=== native-image (PGO instrumented) ===")
    (apply shell "native-image" "-cp" cp "bench.core" "-o" "bench-instr"
           "--pgo-instrument" flags)
    (println "=== PGO profiling runs ===")
    ;; one profile file per run: each run overwrites its dump file, so give
    ;; them distinct names and merge via --pgo's comma list
    (doseq [mode pgo-modes]
      (shell {:out :string} "./bench-instr" (str "-XX:ProfilesDumpFile=" mode ".iprof")
             max-heap mode))
    (str "--pgo=" (str/join "," (map #(str % ".iprof") pgo-modes)))))

(println "=== native-image ===")
;; EXTRA_NATIVE_OPTS: e.g. "-O3 -march=native" or "--gc=G1" (Oracle GraalVM)
(def extra-opts
  (when-let [s (not-empty (str/trim (or (System/getenv "EXTRA_NATIVE_OPTS") "")))]
    (str/split s #"\s+")))
(apply shell "native-image" "-cp" cp "bench.core" "-o" "bench"
       (concat flags (when pgo-flag [pgo-flag]) extra-opts))

(defn run-timed
  "Runs `cmd`, echoing its output, and returns {:wall ms :work ms} — wall
  measured around the process, work parsed from the work_ms= line the
  benchmark prints around the workload itself (wall − work = startup)."
  [& cmd]
  (let [start (System/nanoTime)
        out (:out (apply shell {:out :string} cmd))
        wall (quot (- (System/nanoTime) start) 1000000)]
    (print out)
    (flush)
    {:wall wall
     :work (some-> (re-find #"work_ms=(\d+)" out) second parse-long)}))

(def results
  (vec (for [mode ["cpu" "cpu-java" "cpu-pure" "mem" "mem-pure"]]
         (do (println (str "--- " mode " / jvm ---"))
             (let [jvm (run-timed "java" "--enable-native-access=ALL-UNNAMED"
                                  "-Xss16m" max-heap "-cp" cp "bench.core" mode)]
               (println (str "--- " mode " / native ---"))
               ;; native-image binaries take -Xmx as a runtime option
               {:mode mode :jvm jvm :native (run-timed "./bench" max-heap mode)})))))

;; ref = time divided by this machine's reference score, comparable across
;; machines; factor is within-row, fastest implementation = 1x
(defn print-table [title k]
  (println)
  (println title)
  (let [fmt "%-10s %8s %6s %7s %11s %6s %7s"
        cell (fn [ms best]
               [(str ms)
                (format "%.1f" (/ ms (double ref-score)))
                (format "%.1fx" (/ ms (double best)))])]
    (println (apply format fmt ["mode" "jvm ms" "ref" "factor" "native ms" "ref" "factor"]))
    (doseq [r results]
      (let [jvm (get-in r [:jvm k])
            native (get-in r [:native k])
            best (min jvm native)]
        (println (apply format fmt (:mode r)
                        (concat (cell jvm best) (cell native best))))))))

(print-table (format "RESULTS score=%d (wall ms incl. startup | ref = ms/score | factor vs row's fastest)"
                     ref-score)
             :wall)
(print-table "RESULTS-WORK (same, sans startup: in-process work_ms — the comparison for long-running JVMs)"
             :work)
