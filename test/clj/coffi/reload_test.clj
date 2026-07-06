(ns coffi.reload-test
  "Tests for unloading and reloading native libraries without restarting the JVM."
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]
   [clojure.test :as t]
   [coffi.ffi :as ffi]
   [coffi.mem :as mem]))

(def ^:private lib-path "target/reload_test_lib.so")
(def ^:private src-path "target/reload_test.c")

(defn- compile-src!
  "Compiles the C `source` into a library at [[lib-path]]."
  [source]
  (spit src-path source)
  (let [{:keys [exit err]} (sh/sh "clang" "-fpic" "-shared" src-path "-o" lib-path)]
    (when-not (zero? exit)
      (throw (ex-info "Failed to compile reload test library" {:exit exit :err err})))))

(defn- compile-lib!
  "Compiles a library at [[lib-path]] whose `reload_test_value` fn returns `value`."
  [value]
  (compile-src! (str "int reload_test_value(void) { return " value "; }\n")))

(defn- musl-libc?
  "Checks whether this system uses musl libc (e.g. Alpine Linux), whose
  `dlclose` is deliberately a no-op: libraries are never unloaded and native
  destructors only run at process exit."
  []
  (boolean (some #(re-find #"^ld-musl" (.getName ^java.io.File %))
                 (.listFiles (io/file "/lib")))))

(defn- ensure-unloaded
  [f]
  (try (f)
       (finally (ffi/unload-library lib-path))))

(t/use-fixtures :each ensure-unloaded)

(t/deftest reload-picks-up-recompiled-code
  (compile-lib! 1)
  (ffi/load-library lib-path)
  (let [f (ffi/cfn "reload_test_value" [] ::mem/int)]
    (t/is (= 1 (f)))
    (ffi/unload-library lib-path)
    (compile-lib! 2)
    (ffi/load-library lib-path)
    (t/is (= 2 (f)) "the same fn object calls the recompiled code")))

(t/deftest calling-while-unloaded-throws
  (compile-lib! 1)
  (ffi/load-library lib-path)
  (let [f (ffi/cfn "reload_test_value" [] ::mem/int)]
    (t/is (= 1 (f)))
    (ffi/unload-library lib-path)
    (t/is (thrown? UnsatisfiedLinkError (f)))))

(t/deftest loading-unchanged-library-is-a-noop
  (compile-lib! 3)
  (ffi/load-library lib-path)
  (let [f (ffi/cfn "reload_test_value" [] ::mem/int)]
    (t/is (= 3 (f)))
    (ffi/load-library lib-path)
    (t/is (= 3 (f)) "loading an unchanged file must not disturb existing fns")))

(t/deftest path-spellings-share-one-entry
  (compile-lib! 5)
  (ffi/load-library lib-path)
  (let [f (ffi/cfn "reload_test_value" [] ::mem/int)
        equivalent-path "target/../target/reload_test_lib.so"]
    (t/is (= 5 (f)))
    (ffi/load-library equivalent-path)
    (t/is (= 5 (f)) "loading via an equivalent path spelling is a no-op")
    (ffi/unload-library equivalent-path)
    (t/is (thrown? UnsatisfiedLinkError (f))
          "unloading via an equivalent path spelling unloads the same library")))

(t/deftest unload-runs-native-destructors
  (let [marker (io/file "target/reload_test_dtor.txt")]
    (io/delete-file marker true)
    (compile-src!
     (str "#include <stdio.h>\n"
          "__attribute__((destructor))\n"
          "static void on_unload(void) {\n"
          "  FILE* f = fopen(\"target/reload_test_dtor.txt\", \"w\");\n"
          "  if (f) { fputs(\"closed\\n\", f); fclose(f); }\n"
          "}\n"
          "int reload_test_value(void) { return 9; }\n"))
    (ffi/load-library lib-path)
    (t/is (= 9 ((ffi/cfn "reload_test_value" [] ::mem/int))))
    (t/is (not (.exists marker)) "destructor must not run while the library is loaded")
    (ffi/unload-library lib-path)
    (if (musl-libc?)
      (t/is (not (.exists marker))
            "musl never unloads libraries, so the destructor must not have run")
      (t/is (.exists marker)
            "unloading must dlclose the library and run its destructor"))))

(t/deftest missing-symbol-fails-at-call
  (compile-lib! 4)
  (ffi/load-library lib-path)
  ;; construction succeeds even while the symbol is missing (the library may
  ;; be loaded later, e.g. in -main of an AOT-compiled program)
  (let [f (ffi/cfn "no_such_symbol_anywhere" [] ::mem/int)]
    (t/is (thrown? UnsatisfiedLinkError (f)))))

(t/deftest defcfn-fns-survive-reload
  (compile-lib! 7)
  (ffi/load-library lib-path)
  (ffi/defcfn reload-val "reload_test_value" [] ::mem/int)
  (t/is (= 7 (reload-val)))
  (ffi/unload-library lib-path)
  (compile-lib! 8)
  (ffi/load-library lib-path)
  (t/is (= 8 (reload-val)) "the same defcfn'd var calls the recompiled code"))

(def ^:private point-t
  [::mem/struct [[:x ::mem/float] [:y ::mem/float]]])

(defn- rich-source
  "C source exercising structs, pointers, strings, and callbacks, with `v`
  mixed into every result to distinguish library versions."
  [v]
  (str "typedef struct { float x; float y; } point;\n"
       "int reload_test_value(void) { return " v "; }\n"
       "point reload_make_point(float x, float y) {\n"
       "  point p; p.x = x + " v "; p.y = y + " v "; return p;\n"
       "}\n"
       "float reload_point_sum(point p) { return p.x + p.y + " v "; }\n"
       "void reload_write_int(int* out) { *out = " v "; }\n"
       "long long reload_str_len(const char* s) {\n"
       "  long long n = 0; while (s[n]) n++; return n + " v ";\n"
       "}\n"
       "int reload_call_cb(int (*f)(int)) { return f(" v "); }\n"))

(t/deftest structs-pointers-strings-callbacks-survive-reload
  (compile-src! (rich-source 10))
  (ffi/load-library lib-path)
  (let [make-point (ffi/cfn "reload_make_point" [::mem/float ::mem/float] point-t)
        point-sum (ffi/cfn "reload_point_sum" [point-t] ::mem/float)
        write-int (ffi/cfn "reload_write_int" [::mem/pointer] ::mem/void)
        str-len (ffi/cfn "reload_str_len" [::mem/c-string] ::mem/long)
        call-cb (ffi/cfn "reload_call_cb" [[::ffi/fn [::mem/int] ::mem/int]] ::mem/int)
        check
        (fn [v]
          (t/is (= {:x (float (+ 1 v)) :y (float (+ 2 v))}
                   (make-point 1 2))
                "struct returned by value")
          (t/is (= (float (+ 3 v))
                   (point-sum {:x 1.0 :y 2.0}))
                "struct passed by value")
          (with-open [arena (mem/confined-arena)]
            (let [out (mem/alloc-instance ::mem/int arena)]
              (write-int out)
              (t/is (= v (mem/deserialize-from out ::mem/int))
                    "write through an out-pointer")))
          (t/is (= (+ 5 v) (str-len "hello"))
                "string argument")
          (t/is (= (* 2 v) (call-cb (fn [x] (* 2 x))))
                "clojure fn as callback"))]
    (check 10)
    (ffi/unload-library lib-path)
    (compile-src! (rich-source 20))
    (ffi/load-library lib-path)
    (check 20)))
