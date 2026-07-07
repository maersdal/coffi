#!/usr/bin/env bb
;; Runs HEAD's benchmark code (bench.core) against two coffi source trees —
;; this checkout and an older one — and prints them side by side. JVM-only:
;; pre-pure-clojure coffi generates bytecode at runtime (insn), which cannot
;; be built into a native image, so there is no old-native mode to compare.
;;
;;   bb compare.bb /path/to/old-coffi-checkout
;;
;; The old checkout's Java sources (src/java, present on pre-pure-clojure
;; refs) are compiled into its target/classes if missing.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str])

(def old-root
  (let [p (or (first *command-line-args*) "/coffi-old")]
    (str (fs/absolutize p))))

(when-not (fs/exists? (fs/path old-root "deps.edn"))
  (println "no coffi checkout found at" old-root)
  (System/exit 1))

(def old-label (or (System/getenv "OLD_REF") old-root))

(def old-sdeps
  (pr-str {:aliases {:old {:override-deps {'org.suskalo/coffi {:local/root old-root}}}}}))

(fs/create-dirs "classes")

(println "=== compiling bench shared library ===")
(shell "gcc" "-O2" "-shared" "-fPIC" "native/bench.c" "-o" "native/libbench.so")

(println "=== compiling java stubs ===")
(shell "javac" "-d" "classes" "java/BenchStub.java")

;; pre-pure-clojure coffi has a :deps/prep-lib step that just compiles
;; src/java into target/classes; doing the javac directly avoids depending on
;; the old ref's ancient tools.build
(let [java-dir (fs/path old-root "src/java")]
  (when (and (fs/exists? java-dir)
             (not (fs/exists? (fs/path old-root "target/classes"))))
    (println "=== compiling old coffi java sources ===")
    (apply shell "javac" "-d" (str (fs/path old-root "target/classes"))
           (map str (fs/glob java-dir "**/*.java")))))

(def cp-new (str/trim (:out (shell {:out :string} "clojure" "-Spath"))))
(def cp-old (str/trim (:out (shell {:out :string} "clojure" "-Sdeps" old-sdeps "-A:old" "-Spath"))))

(defn run-bench
  "Runs one benchmark mode on the given classpath, echoing its output, and
  returns the in-process work_ms and (for calls modes) per_call_ns."
  [cp mode]
  ;; the library is loaded before bench.core so its defcfn forms can resolve:
  ;; pre-pure-clojure defcfn binds its symbol eagerly at namespace load,
  ;; before -main's own load-library call (which is a no-op re-load on both
  ;; versions)
  (let [out (:out (shell {:out :string}
                         "java" "--enable-native-access=ALL-UNNAMED"
                         "-Xss16m" "-Xmx4g" "-cp" cp
                         "clojure.main" "-e"
                         (format "(require 'coffi.ffi)
                                  (coffi.ffi/load-library \"native/libbench.so\")
                                  (require 'bench.core)
                                  (bench.core/-main %s)"
                                 (pr-str mode))))]
    (print out)
    (flush)
    {:work (some-> (re-find #"work_ms=(\d+)" out) second parse-long)
     :per-call (some-> (re-find #"per_call_ns=(\d+)" out) second parse-long)}))

;; cpu-java and mem-pure don't touch coffi — they're noise controls: a delta
;; there is machine/JIT variance, not a coffi difference
(def modes ["cpu" "cpu-java" "mem" "mem-pure"])

(def results
  (vec (for [mode modes]
         (do (println (str "--- " mode " / old ---"))
             (let [old (run-bench cp-old mode)]
               (println (str "--- " mode " / new ---"))
               {:mode mode :old old :new (run-bench cp-new mode)})))))

(println)
(println (format "RESULTS old=%s new=HEAD (JVM only, in-process work_ms)" old-label))
(let [fmt "%-10s %10s %10s %8s %13s %13s %8s"]
  (println (apply format fmt ["mode" "old ms" "new ms" "new/old" "old ns/call" "new ns/call" "new/old"]))
  (doseq [{:keys [mode old new]} results]
    (let [ratio (fn [o n] (if (and o n (pos? o)) (format "%.2fx" (/ n (double o))) "-"))]
      (println (format fmt mode
                       (str (:work old)) (str (:work new))
                       (ratio (:work old) (:work new))
                       (str (or (:per-call old) "-")) (str (or (:per-call new) "-"))
                       (ratio (:per-call old) (:per-call new)))))))
