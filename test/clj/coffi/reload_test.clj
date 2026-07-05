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
    (t/is (.exists marker) "unloading must dlclose the library and run its destructor")))

(t/deftest missing-symbol-fails-at-construction
  (compile-lib! 4)
  (ffi/load-library lib-path)
  (t/is (thrown? UnsatisfiedLinkError
                 (ffi/cfn "no_such_symbol_anywhere" [] ::mem/int))))
