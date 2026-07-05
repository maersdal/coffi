(ns coffi.reload-test
  "Tests for unloading and reloading native libraries without restarting the JVM."
  (:require
   [clojure.java.shell :as sh]
   [clojure.test :as t]
   [coffi.ffi :as ffi]
   [coffi.mem :as mem]))

(def ^:private lib-path "target/reload_test_lib.so")
(def ^:private src-path "target/reload_test.c")

(defn- compile-lib!
  "Compiles a library at [[lib-path]] whose `reload_test_value` fn returns `value`."
  [value]
  (spit src-path (str "int reload_test_value(void) { return " value "; }\n"))
  (let [{:keys [exit err]} (sh/sh "clang" "-fpic" "-shared" src-path "-o" lib-path)]
    (when-not (zero? exit)
      (throw (ex-info "Failed to compile reload test library" {:exit exit :err err})))))

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

(t/deftest missing-symbol-fails-at-construction
  (compile-lib! 4)
  (ffi/load-library lib-path)
  (t/is (thrown? UnsatisfiedLinkError
                 (ffi/cfn "no_such_symbol_anywhere" [] ::mem/int))))
