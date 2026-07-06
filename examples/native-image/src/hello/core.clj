(ns hello.core
  "coffi + GraalVM native-image example: calls libc, then loads a shared
  library at runtime and exercises structs, pointers, and callbacks.

  The one rule: load-library must run at image runtime (here, in -main) —
  never at the top level of a namespace, which executes at image build time."
  (:require
   [coffi.ffi :as ffi :refer [defcfn]]
   [coffi.mem :as mem :refer [defalias]])
  (:gen-class))

(defcfn strlen
  "strlen" [::mem/c-string] ::mem/long)

(defalias ::point
  [::mem/struct [[:x ::mem/float] [:y ::mem/float]]])

(defcfn make-point
  "make_point" [::mem/float ::mem/float] ::point)

(defcfn point-sum
  "point_sum" [::point] ::mem/float)

(defcfn write-int
  "write_int" [::mem/pointer ::mem/int] ::mem/void)

(defcfn apply-cb
  "apply_cb" [[::ffi/fn [::mem/int] ::mem/int] ::mem/int] ::mem/int)

(defn -main [& _]
  (ffi/load-library "native/libdemo.so")
  (println "strlen:    " (strlen "hello, native world"))
  (println "make-point:" (make-point 1.5 2.5))
  (println "point-sum: " (point-sum {:x 1.0 :y 2.0}))
  (with-open [arena (mem/confined-arena)]
    (let [out (mem/alloc-instance ::mem/int arena)]
      (write-int out 41)
      (println "write-int: " (mem/deserialize-from out ::mem/int))))
  (println "apply-cb:  " (apply-cb inc 20))
  (System/exit 0))
