(ns coffi.ffi-test
  (:require
   [clojure.test :as t]
   [coffi.ffi :as ffi :refer [defcfn]]
   [coffi.layout :as layout]
   [coffi.mem :as mem]
   [clojure.pprint]))

(ffi/load-library "target/ffi_test.so")

(t/deftest can-load-symbols
  (t/is (not (nil? (ffi/find-symbol "add_numbers")))))

(t/deftest generates-native-image-metadata
  ;; constructing a fn with a callback type records upcall + downcall
  ;; descriptors without anything being called
  (ffi/cfn "upcall_test" [[::ffi/fn [] ::mem/c-string]] ::mem/c-string)
  (let [file (ffi/write-native-image-metadata! "target/coffi-metadata-test")
        content ^String (slurp file)]
    (t/is (.contains content "\"foreign\""))
    ;; add_numbers: jint(jint, jint), constructed at ns load
    (t/is (.contains content "{\"returnType\": \"jint\", \"parameterTypes\": [\"jint\", \"jint\"]}"))
    ;; the callback type: void*() as an upcall
    (t/is (.contains content "\"upcalls\""))
    (t/is (.contains content "{\"returnType\": \"void*\", \"parameterTypes\": []}"))))

(t/deftest system-symbols-resolve-without-loading
  ;; libc/CRT symbols come from the default system lookup, with no library
  ;; loaded at all
  (t/is (= 5 ((ffi/cfn "strlen" [::mem/c-string] ::mem/long) "hello"))))

(t/deftest can-load-system-library
  (if (.startsWith (System/getProperty "os.name") "Windows")
    (do (ffi/load-system-library "kernel32")
        ;; repeated loads are no-ops
        (ffi/load-system-library "kernel32")
        (t/is (= (.pid (java.lang.ProcessHandle/current))
                 (Integer/toUnsignedLong
                  ((ffi/cfn "GetCurrentProcessId" [] ::mem/int))))))
    ;; on unix-likes a bare library name resolves only when the unversioned
    ;; .so/.dylib exists (e.g. from a dev package), so skip when absent
    (let [loaded? (try (ffi/load-system-library "z")
                       true
                       (catch RuntimeException _ false))]
      (if loaded?
        (t/is (some? (ffi/find-symbol "zlibVersion")))
        (println "libz not on the system load path; skipping system library test")))))

(t/deftest can-fetch-constant
  (t/is (= 42 (ffi/const "c" ::mem/int)))
  (t/is (= "Test string" (ffi/const "s" ::mem/c-string))))

(t/deftest can-call-primitive-fns
  (t/is (= 5 ((ffi/cfn "add_numbers" [::mem/int ::mem/int] ::mem/int) 2 3))))

(mem/defalias ::point
  [::mem/struct
   [[:x ::mem/float]
    [:y ::mem/float]]])

(t/deftest can-call-with-structs
  (t/is (= {:x 2.0 :y 2.0}
           ((ffi/cfn "add_points" [::point ::point] ::point) {:x 1 :y 2} {:x 1 :y 0}))))

(t/deftest can-call-deserialized-fn-pointers
  (t/is (= "Alternate string"
           (((ffi/cfn "get_downcall" [::mem/int] [::ffi/fn [] ::mem/c-string])
             1)))))

(t/deftest can-make-upcall
  (t/is (= ((ffi/cfn "upcall_test" [[::ffi/fn [] ::mem/c-string]] ::mem/c-string)
            (fn [] "hello from clojure from c from clojure"))
           "hello from clojure from c from clojure")))

(t/deftest can-make-upcall2
  (t/is (= ((ffi/cfn "upcall_test2" [[::ffi/fn [] ::mem/int]] ::mem/int)
            (fn [] 5))
           5)))

(t/deftest can-make-upcall-int-fn-string-ret
  (t/is (= ((ffi/cfn "upcall_test_int_fn_string_ret" [[::ffi/fn [] ::mem/int]] ::mem/c-string)
            (fn [] 2))
           "co'oi prenu")))

(mem/defalias ::alignment-test
  (layout/with-c-layout
    [::mem/struct
     [[:a ::mem/char]
      [:x ::mem/double]
      [:y ::mem/float]]]))

(t/deftest padding-matches
  (t/is (= (dissoc ((ffi/cfn "get_struct" [] ::alignment-test)) ::layout/padding)
           {:a \x
            :x 3.14
            :y 42.0})))

(t/deftest static-variables-are-mutable
  (let [mut-str (ffi/static-variable "mut_str" ::mem/c-string)]
    (ffi/freset! mut-str nil)
    (t/is (nil? @mut-str))
    (ffi/freset! mut-str "Hello world!")
    (t/is (= "Hello world!" @mut-str)))
  (ffi/freset! (ffi/static-variable "counter" ::mem/int) 1)
  (t/is (= ((ffi/cfn "get_string1" [] ::mem/c-string))
           "Goodbye friend.")))

(t/deftest can-call-with-trailing-string-arg
  (t/is
   (= (try ((ffi/cfn "test_call_with_trailing_string_arg"
                     [::mem/int ::mem/int ::mem/c-string]
                     ::mem/void)
            1 2 "third arg")
           :ok
           (catch Throwable _t
             :err))
      :ok)))

(ffi/defvar freed? "freed" ::mem/int)

(def get-variable-length-array* (ffi/make-downcall "get_variable_length_array" [::mem/pointer] ::mem/int))
(def free-variable-length-array* (ffi/make-downcall "free_variable_length_array" [::mem/pointer] ::mem/void))

(t/deftest get-variable-length-array
  (let [floats
        (with-open [stack (mem/confined-arena)]
          (let [out-floats (mem/alloc mem/pointer-size stack)
                num-floats (get-variable-length-array* out-floats)
                floats-addr (mem/read-address out-floats)
                floats-slice (mem/reinterpret floats-addr (unchecked-multiply-int mem/float-size num-floats))]
            (try
              (loop [floats (transient [])
                     index 0]
                (if (>= index num-floats)
                  (persistent! floats)
                  (recur (conj! floats (mem/read-float floats-slice (unchecked-multiply-int index mem/float-size)))
                         (unchecked-inc-int index))))
              (finally
                (free-variable-length-array* floats-addr)))))]
    (t/is (not (zero? @freed?)))
    (t/is (= floats (mapv #(* (float 1.5) %) (range (count floats)))))))

(mem/defstruct Point [x ::mem/float y ::mem/float])

(t/deftest can-call-with-defstruct
  (t/is (= {:x 2.0 :y 2.0}
           ((ffi/cfn "add_points" [::Point ::Point] ::Point) (Point. 1 2) (Point. 1 0)))))

(mem/defstruct AlignmentTest [a ::mem/char x ::mem/double y ::mem/float])

(t/deftest padding-matches-defstruct
  (t/is (= ((ffi/cfn "get_struct" [] ::AlignmentTest))
           {:a \x
            :x 3.14
            :y 42.0})))

(mem/defstruct ComplexType [x ::Point y ::mem/byte z [::mem/array ::mem/int 4 :raw? true] w ::mem/c-string])

(t/deftest can-call-with-complex-defstruct
  (t/are [x y] (= x (y ((ffi/cfn "complexTypeTest" [::ComplexType] ::ComplexType)
                        (ComplexType. (Point. 2 3) 4 (int-array [5 6 7 8]) "hello from clojure"))))
    {:x {:x 3.0 :y 4.0} :y 3 :w "hello from c"} #(dissoc % :z)
    [5 6 7 8] (comp vec :z)))

(mem/defstruct ComplexTypeWrapped [x ::Point y ::mem/byte z [::mem/array ::mem/int 4] w ::mem/c-string])

(t/deftest can-call-with-wrapped-complex-defstruct
  (t/are [x y] (= x (y ((ffi/cfn "complexTypeTest" [::ComplexTypeWrapped] ::ComplexTypeWrapped)
                        (ComplexTypeWrapped. (Point. 2 3) 4 (int-array [5 6 7 8]) "hello from clojure"))))
    {:x {:x 3.0 :y 4.0} :y 3 :w "hello from c"} #(dissoc % :z)
    [5 6 7 8] (comp vec :z)))

(defcfn is-42?
  "is_42" [[::mem/pointer ::mem/pointer]] ::mem/int
  native-is-42?
  [number]
  (with-open [arena (mem/confined-arena)]
    (let [int-ptr (mem/alloc-instance ::mem/int arena)
          _ (mem/serialize-into (int number) ::mem/int int-ptr arena)]
      (native-is-42? int-ptr))))

(t/deftest double-pointer-serialize
  (t/is (not (zero? (is-42? 42))))
  (t/is (zero? (is-42? 41))))

;;; Nullable pointer semantics (::mem/pointer fails fast, ::mem/pointer? is
;;; nullable; distinction modeled after dtype-next's :pointer/:pointer?)

(t/deftest non-nullable-pointer-return-throws-on-null
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-nullable"
                          ((ffi/cfn "get_null_ptr" [] ::mem/pointer)))))

(t/deftest nullable-pointer-return-is-nil
  (t/is (nil? ((ffi/cfn "get_null_ptr" [] ::mem/pointer?)))))

(t/deftest non-nullable-pointer-arg-throws-on-nil
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-nullable"
                          ((ffi/cfn "ptr_identity" [::mem/pointer] ::mem/pointer?) nil))))

(t/deftest nullable-pointer-roundtrips-nil-through-native-call
  (t/is (nil? ((ffi/cfn "ptr_identity" [::mem/pointer?] ::mem/pointer?) nil))))

(t/deftest nullable-pointer-passes-non-null-through-native-call
  (with-open [arena (mem/confined-arena)]
    (let [seg (mem/alloc-instance ::mem/int arena)
          ret ((ffi/cfn "ptr_identity" [::mem/pointer?] ::mem/pointer?) seg)]
      (t/is (= (mem/address-of seg) (mem/address-of ret))))))

;;; Primitive invoke path (inspired by dtype-next's typed library methods):
;;; wrapper-less defcfns with all-long/double signatures def the raw
;;; downcall class, which implements the matching clojure.lang.IFn$
;;; interface, and the var carries prim-tagged arglists so callers compile
;;; to boxless invokePrim calls

(defcfn add-longs "add_longs" [::mem/long ::mem/long] ::mem/long)
(defcfn add-doubles "add_doubles" [::mem/double ::mem/double] ::mem/double)

(t/deftest prim-eligible-defcfn-implements-prim-interface
  (t/is (instance? clojure.lang.IFn$LLL add-longs))
  (t/is (instance? clojure.lang.IFn$DDD add-doubles)))

(t/deftest prim-eligible-defcfn-has-prim-tagged-arglists
  (let [[arglist] (:arglists (meta #'add-longs))]
    (t/is (= '[long long] (map (comp :tag meta) arglist)))
    (t/is (= 'long (:tag (meta arglist))))))

(t/deftest prim-path-calls-work
  (t/is (= 5 (add-longs 2 3)))
  (t/is (= 5.5 (add-doubles 2.25 3.25))))

(t/deftest prim-eligible-boxed-path-still-works
  (t/is (= 5 (apply add-longs [2 3])))
  ;; the boxed invoke coerces numbers like the serde wrapper did, rather
  ;; than requiring the exact box class
  (t/is (= 5 (apply add-longs [(int 2) (short 3)])))
  (t/is (= 5.5 (apply add-doubles [(float 2.25) 3.25]))))

(t/deftest prim-ineligible-defcfn-unchanged
  ;; pointer args keep the serde wrapper: no prim interface, plain arglists
  (t/is (not (instance? clojure.lang.IFn$LLL is-42?))))

;;; deflibrary: whole-library definitions as data (modeled after
;;; dtype-next's define-library)

(ffi/deflibrary test-lib
  "Data-driven bindings for the test library."
  {:lib-add {:symbol "add_longs"
             :args [::mem/long ::mem/long]
             :ret ::mem/long
             :doc "Adds two longs."}
   :failing-op {:args []
                :ret ::mem/long
                :check-error? true}
   :null-getter {:symbol "get_null_ptr"
                 :ret ::mem/pointer?}}
  :check-error (fn [ret fn-kw]
                 (if (and (number? ret) (neg? ret))
                   (throw (ex-info "native call failed" {:fn fn-kw :ret ret}))
                   ret)))

(t/deftest deflibrary-defines-working-fns
  (t/is (= 5 (lib-add 2 3)))
  (t/is (nil? (null-getter))))

(t/deftest deflibrary-kebab-names-map-to-snake-symbols
  ;; :failing-op binds to native failing_op with no explicit :symbol; the
  ;; check-error test below proves the call reaches the right native fn
  (t/is (some? (resolve 'coffi.ffi-test/failing-op))))

(t/deftest deflibrary-prim-path-applies
  (t/is (instance? clojure.lang.IFn$LLL lib-add)))

(t/deftest deflibrary-check-error-wraps-marked-fns
  (let [e (try (failing-op) (catch clojure.lang.ExceptionInfo e e))]
    (t/is (= {:fn :failing-op :ret -12} (ex-data e)))))

(t/deftest deflibrary-var-holds-definitions
  (t/is (= [::mem/long ::mem/long] (get-in test-lib [:lib-add :args])))
  (t/is (= "Data-driven bindings for the test library."
           (:doc (meta #'test-lib)))))

(t/deftest deflibrary-docstrings-carry-through
  (t/is (= "Adds two longs." (:doc (meta #'lib-add)))))
