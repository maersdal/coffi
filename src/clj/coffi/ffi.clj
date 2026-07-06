(ns coffi.ffi
  "Functions for creating handles to native functions and loading native libraries."
  (:require
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [coffi.mem :as mem]
   [insn.core :as insn])
  (:import
   (clojure.lang
    IDeref IFn IMeta IObj IReference)
   (java.io File InputStream)
   (java.lang.invoke
    MethodHandle
    MethodHandles
    MethodType)
   (java.lang.foreign
    AddressLayout
    Arena
    Linker
    Linker$Option
    FunctionDescriptor
    MemoryLayout
    MemorySegment
    PaddingLayout
    SegmentAllocator
    SequenceLayout
    StructLayout
    SymbolLookup
    UnionLayout
    ValueLayout$OfBoolean
    ValueLayout$OfByte
    ValueLayout$OfChar
    ValueLayout$OfDouble
    ValueLayout$OfFloat
    ValueLayout$OfInt
    ValueLayout$OfLong
    ValueLayout$OfShort)
   (java.security MessageDigest)
   (java.util LinkedHashMap)
   (java.util.concurrent ConcurrentHashMap)
   (java.util.regex Pattern)))

;; set! requires a thread binding, which is absent when this namespace is
;; initialized as an AOT-compiled class (e.g. GraalVM native-image build time)
(when (thread-bound? #'*warn-on-reflection*)
  (set! *warn-on-reflection* true))

;;; FFI Code loading and function access
;;
;; Libraries are loaded with SymbolLookup/libraryLookup, which ties the
;; lifetime of a library to an arena rather than to a classloader the way
;; System/load does. This allows libraries to be unloaded and reloaded
;; without restarting the JVM, and requires no AOT-compiled Java shim to
;; provide a stable classloader, so no prep step is needed to use coffi as a
;; git dependency.

(def ^:private system-lookup
  "Lookup for symbols in the standard system libraries, e.g. libc."
  (delay (.or (.defaultLookup (Linker/nativeLinker)) (SymbolLookup/loaderLookup))))

(def ^:private libraries
  "Registry of loaded libraries.

  Maps a library key (the canonical file path for [[load-library]], the
  platform library filename for [[load-system-library]]) to a map of
  `:arena`, `:lookup`, and `:content-hash`. Iteration order is load order,
  which is also symbol resolution order. All access must hold the lock on
  this object."
  (LinkedHashMap.))

(def ^:private symbol-cache
  "Cache of resolved symbol addresses, so that re-resolving a symbol on every
  call is just a map lookup. Invalidated whenever a library is loaded or
  unloaded."
  (ConcurrentHashMap.))

(defn- hash-file
  "Computes the SHA-256 digest of the contents of the file at `path`."
  ^bytes [path]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 65536)]
    (with-open [^InputStream in (io/input-stream path)]
      (loop []
        (let [read (.read in buffer)]
          (when-not (neg? read)
            (.update digest buffer 0 read)
            (recur)))))
    (.digest digest)))

(defn- put-library!
  "Loads the library `target` (a file path or platform library filename) and
  registers its lookup under `key`, replacing any previous entry. Must be
  called while holding the [[libraries]] lock."
  [key ^String target content-hash]
  (when-some [old (.remove ^LinkedHashMap libraries key)]
    (.close ^Arena (:arena old))
    (.clear ^ConcurrentHashMap symbol-cache))
  (let [arena (Arena/ofShared)]
    (try
      (.put ^LinkedHashMap libraries key
            {:arena arena
             :lookup (SymbolLookup/libraryLookup target arena)
             :content-hash content-hash})
      (.clear ^ConcurrentHashMap symbol-cache)
      nil
      (catch RuntimeException e
        (.close arena)
        (throw e)))))

(defn load-library
  "Loads the library at `path`.

  If a library was already loaded from `path` and its contents are unchanged,
  this is a no-op. If the contents have changed (e.g. it was recompiled), the
  old library is unloaded and replaced, so this can be called again after
  recompiling a library to pick up the new code. Fns created from symbol
  names (e.g. via [[defcfn]] or [[cfn]]) re-resolve their symbol on each call
  and keep working across reloads.

  Reloading has caveats which coffi cannot detect or prevent; see the
  \"Reloading Libraries\" section of the Getting Started article for the
  failure modes. In short: the fresh copy's static state is reset, pointers
  obtained from the old copy are dangling, libraries owning threads, signal
  handlers, or TLS destructors are not safely reloadable, and dependent
  libraries keep using the old copy until they are reloaded themselves."
  [path]
  (let [filepath (.getCanonicalPath (io/file path))]
    (locking libraries
      (let [content-hash (hash-file filepath)
            existing (.get ^LinkedHashMap libraries filepath)]
        (when-not (and existing
                       (MessageDigest/isEqual ^bytes (:content-hash existing)
                                              content-hash))
          (put-library! filepath filepath content-hash)))))
  nil)

(defn unload-library
  "Unloads the library previously loaded from `path`.

  On Windows the library file is locked while loaded, so it must be unloaded
  before it can be recompiled. Fns created from the library's symbols throw
  [[UnsatisfiedLinkError]] while it is unloaded; raw addresses and pointers
  obtained from it are dangling, and using them may crash the JVM.

  The OS may keep a library mapped despite unloading (e.g. when it is a
  dependency of another loaded library, or is pinned by TLS destructors); in
  that case a subsequent [[load-library]] can silently return the old code."
  [path]
  (let [filepath (.getCanonicalPath (io/file path))]
    (locking libraries
      (when-some [entry (.remove ^LinkedHashMap libraries filepath)]
        (.close ^Arena (:arena entry))
        (.clear ^ConcurrentHashMap symbol-cache))))
  nil)

(defn load-system-library
  "Loads the library named `libname` from the system's load path.

  `libname` is a bare library name; it is mapped to the platform's filename
  (e.g. `\"z\"` becomes `libz.so` or `z.dll`) and searched for on
  `java.library.path` first, then on the operating system's default library
  search path (`LD_LIBRARY_PATH`, `PATH`, system directories).

  Unlike [[load-library]], a system library is loaded at most once and stays
  loaded for the lifetime of the JVM; repeated calls are no-ops.

  This loads via the same mechanism as [[load-library]] (`dlopen` and
  equivalents) rather than `System/loadLibrary`, so JNI libraries which rely
  on `JNI_OnLoad` or registering natives are not initialized as JNI expects;
  for those, call `System/loadLibrary` directly."
  [libname]
  (let [mapped (System/mapLibraryName (name libname))
        on-library-path
        (some (fn [dir]
                (let [f (io/file ^String dir mapped)]
                  (when (.isFile f)
                    (.getCanonicalPath f))))
              (.split ^String (System/getProperty "java.library.path" "")
                      (Pattern/quote File/pathSeparator)))]
    (locking libraries
      (when-not (.containsKey ^LinkedHashMap libraries mapped)
        (put-library! mapped (or on-library-path mapped) nil))))
  nil)

(defn find-symbol
  "Gets the [[MemorySegment]] of a symbol from the loaded libraries.

  Searches the libraries loaded with [[load-library]] and
  [[load-system-library]] in load order, then the standard system libraries,
  e.g. libc. Returns nil when the symbol cannot be found. Resolutions are
  cached until the next library load or unload."
  [sym]
  (let [sym-name (name sym)]
    (or (.get ^ConcurrentHashMap symbol-cache sym-name)
        (locking libraries
          (when-some [address
                      (or (some (fn [entry]
                                  (.orElse (.find ^SymbolLookup (:lookup entry)
                                                  sym-name)
                                           nil))
                                (.values ^LinkedHashMap libraries))
                          (.orElse (.find ^SymbolLookup @system-lookup sym-name)
                                   nil))]
            (.put ^ConcurrentHashMap symbol-cache sym-name address)
            address)))))

(defn- require-symbol
  "Like [[find-symbol]], but throws an [[UnsatisfiedLinkError]] when the
  symbol cannot be resolved."
  ^MemorySegment [sym-name]
  (or (find-symbol sym-name)
      (throw (UnsatisfiedLinkError.
              (str "Could not resolve native symbol: " sym-name)))))

(defn- native-image-build-time?
  "Checks whether this is executing inside the GraalVM native-image build JVM.

  During an image build, downcall handles must not be created and native
  memory must not be resolved: both would bake build-machine addresses into
  the image heap. Downcall construction is deferred to image runtime instead."
  []
  (= "buildtime" (System/getProperty "org.graalvm.nativeimage.imagecode")))

(declare ^:private downcall-class-ctor ^:private upcall-class-ctor
         ^:private record-descriptor! ^:private function-descriptor)

(defn- fn-types
  "Returns every `[::coffi.ffi/fn ...]` type nested in `types`."
  [types]
  (filter #(and (vector? %) (= ::fn (first %)))
          (tree-seq coll? seq types)))

(defn- record-fn-type-descriptors!
  "Records upcall and downcall descriptors for every fn type nested in
  `types`, so native-image metadata covers callbacks that are constructed
  but not exercised in this session."
  [types]
  (doseq [[_fn arg-types ret-type] (fn-types types)]
    (record-descriptor! :upcalls (function-descriptor arg-types ret-type))
    (record-descriptor! :downcalls (function-descriptor arg-types ret-type))))

(defn- warm-fn-wrapper-classes!
  "Generates the upcall and downcall wrapper classes for every
  `[::coffi.ffi/fn ...]` type nested in `types`.

  Wrapper classes cannot be defined at native-image runtime, so any fn type
  that will be serialized (passed as a callback) or deserialized (received as
  a function pointer) at runtime must have its classes generated during the
  image build."
  [types]
  (doseq [[_fn arg-types ret-type] (fn-types types)]
    (upcall-class-ctor arg-types ret-type)
    (downcall-class-ctor arg-types ret-type)))

(defn- function-descriptor
  "Gets the [[FunctionDescriptor]] for a set of `args` and `ret` types."
  ([args] (function-descriptor args ::mem/void))
  ([args ret]
   (let [args-arr (into-array MemoryLayout (map mem/c-layout args))]
     (if-not (identical? ret ::mem/void)
       (FunctionDescriptor/of
        (mem/c-layout ret)
        args-arr)
       (FunctionDescriptor/ofVoid
        args-arr)))))

(defonce ^:private ffm-descriptors
  ;; Every function descriptor used for a downcall or upcall in this JVM
  ;; session, so native-image reachability metadata can be generated from a
  ;; live session; see [[write-native-image-metadata!]].
  (atom {:downcalls #{} :upcalls #{}}))

(defn- record-descriptor!
  "Records a function descriptor under `kind` and returns it."
  [kind ^FunctionDescriptor fdesc]
  (swap! ffm-descriptors update kind conj fdesc)
  fdesc)

(defn- layout->canonical
  "Gets the canonical type name of a memory layout, in the form used by
  native-image reachability metadata."
  [layout]
  (condp instance? layout
    AddressLayout "void*"
    ValueLayout$OfBoolean "bool"
    ValueLayout$OfByte "jbyte"
    ValueLayout$OfChar "jchar"
    ValueLayout$OfShort "jshort"
    ValueLayout$OfInt "jint"
    ValueLayout$OfLong "jlong"
    ValueLayout$OfFloat "jfloat"
    ValueLayout$OfDouble "jdouble"
    PaddingLayout (str "padding(" (.byteSize ^MemoryLayout layout) ")")
    SequenceLayout (str "sequence(" (.elementCount ^SequenceLayout layout) ", "
                        (layout->canonical (.elementLayout ^SequenceLayout layout)) ")")
    StructLayout (str "struct(" (str/join ", " (map layout->canonical
                                                    (.memberLayouts ^StructLayout layout))) ")")
    UnionLayout (str "union(" (str/join ", " (map layout->canonical
                                                  (.memberLayouts ^UnionLayout layout))) ")")))

(defn- descriptor->metadata-entry
  "Gets the reachability-metadata JSON object for a function descriptor."
  [^FunctionDescriptor fdesc]
  (let [ret (.returnLayout fdesc)]
    (str "{"
         "\"returnType\": \""
         (if (.isPresent ret) (layout->canonical (.get ret)) "void")
         "\", "
         "\"parameterTypes\": ["
         (str/join ", " (map #(str "\"" (layout->canonical %) "\"")
                             (.argumentLayouts fdesc)))
         "]}")))

(defn write-native-image-metadata!
  "Writes GraalVM native-image reachability metadata for every FFM downcall
  and upcall descriptor coffi has created in this JVM session.

  Writes `<dir>/reachability-metadata.json` containing the `foreign` section
  and returns the file path. Pass the directory to native-image via
  `-H:ConfigurationFileDirectories` (alongside any other config directories,
  comma-separated).

  Descriptors are recorded when fns are *constructed* — for [[defcfn]] that
  is namespace load — so requiring the namespaces that define the program's
  native fns is enough; nothing needs to execute. This gives more complete
  coverage than the native-image tracing agent, which records callback
  (upcall) descriptors only when a callback is actually serialized during
  the traced run."
  [dir]
  (let [{:keys [downcalls upcalls]} @ffm-descriptors
        entries (fn [descriptors]
                  (str/join ",\n      " (sort (map descriptor->metadata-entry descriptors))))
        file (io/file dir "reachability-metadata.json")]
    (io/make-parents file)
    (spit file (str "{\n  \"foreign\": {\n"
                    "    \"downcalls\": [\n      " (entries downcalls) "\n    ],\n"
                    "    \"upcalls\": [\n      " (entries upcalls) "\n    ]\n"
                    "  }\n}\n"))
    (str file)))

(defn- downcall-handle
  "Gets the [[MethodHandle]] for the function at the `sym`."
  [sym function-descriptor]
  (record-descriptor! :downcalls function-descriptor)
  (.downcallHandle (Linker/nativeLinker) sym function-descriptor
                   (make-array Linker$Option 0)))

(defn- unbound-downcall-handle
  "Gets a [[MethodHandle]] which takes the target address as its first argument."
  [function-descriptor]
  (record-descriptor! :downcalls function-descriptor)
  (.downcallHandle (Linker/nativeLinker) ^FunctionDescriptor function-descriptor
                   (make-array Linker$Option 0)))

(def ^:private load-instructions
  "Mapping from primitive types to the instruction used to load them onto the stack."
  {::mem/byte :bload
   ::mem/short :sload
   ::mem/int :iload
   ::mem/long :lload
   ::mem/char :cload
   ::mem/float :fload
   ::mem/double :dload
   ::mem/pointer :aload})

(def ^:private prim-classes
  "Mapping from primitive types to their box classes."
  {::mem/byte Byte
   ::mem/short Short
   ::mem/int Integer
   ::mem/long Long
   ::mem/char Character
   ::mem/float Float
   ::mem/double Double})

(defn- to-object-asm
  "Constructs a bytecode sequence to box a primitive on the top of the stack.

  If the `type` is not primitive, then no change will occur. If it is void, a
  null reference will be pushed to the stack."
  [type]
  (cond
    (identical? ::mem/void type) [:ldc nil]
    (identical? ::mem/pointer (mem/primitive-type type)) []
    :else
    (let [prim-type (some-> type mem/primitive-type)]
      (if-some [prim  (some-> prim-type name keyword)]
       ;; Box primitive
       [:invokestatic (prim-classes prim-type) "valueOf" [prim (prim-classes prim-type)]]
       ;; Return object without change
       []))))

(defn- insn-layout
  "Gets the type keyword or class for referring to the type in bytecode."
  [type]
  (or (when-some [prim (mem/primitive-type type)]
        (when (not= prim ::mem/pointer)
          (keyword (name prim))))
      (mem/java-layout type)))

(def ^:private unbox-fn-for-type
  "Map from type name to the name of its unboxing function."
  {::mem/byte "byteValue"
   ::mem/short "shortValue"
   ::mem/int "intValue"
   ::mem/long "longValue"
   ::mem/char "charValue"
   ::mem/float "floatValue"
   ::mem/double "doubleValue"})

(defn- to-prim-asm
  "Constructs a bytecode sequence to unbox a primitive type on top of the stack.

  If the `type` is not primitive, then no change will occur. If it is void, it
  will be popped."
  [type]
  (cond
    (identical? ::mem/void type) [:pop]
    (identical? ::mem/pointer (mem/primitive-type type)) []
    :else
    (let [prim-type (some-> type mem/primitive-type)]
      (if-some [prim (some-> prim-type name keyword)]
        [[:checkcast (prim-classes prim-type)]
         [:invokevirtual (prim-classes prim-type) (unbox-fn-for-type prim-type) [prim]]]
        []))))

(defn- downcall-class-ctor*
  "Returns a function to construct a downcall class for the given `args` and `ret` types.

  A downcall class is an implementation of [[IFn]] which calls a closed over
  method handle without reflection, unboxing primitives when needed."
  [args ret]
  (let [klass (insn/define
                {:flags #{:public :final}
                 :version 8
                 :super clojure.lang.AFunction
                 :fields [{:name "downcall_handle"
                           :type MethodHandle
                           :flags #{:final}}]
                 :methods [{:name :init
                            :flags #{:public}
                            :desc [MethodHandle :void]
                            :emit [[:aload 0]
                                   [:dup]
                                   [:invokespecial :super :init [:void]]
                                   [:aload 1]
                                   [:putfield :this "downcall_handle" MethodHandle]
                                   [:return]]}
                           {:name :invoke
                            :flags #{:public}
                            :desc (repeat (cond-> (inc (count args))
                                            (not (mem/primitive-type ret)) inc)
                                          Object)
                            :emit [[:aload 0]
                                   [:getfield :this "downcall_handle" MethodHandle]
                                   (when-not (mem/primitive-type ret)
                                     [[:aload 1]
                                      [:checkcast SegmentAllocator]])
                                   (map-indexed
                                    (fn [idx arg]
                                      [[:aload (cond-> (inc idx)
                                                 (not (mem/primitive-type ret)) inc)]
                                       (to-prim-asm arg)])
                                    args)
                                   [:invokevirtual MethodHandle "invokeExact"
                                    (cond->>
                                        (conj (mapv insn-layout args)
                                              (insn-layout ret))
                                      (not (mem/primitive-type ret)) (cons SegmentAllocator))]
                                   (to-object-asm ret)
                                   [:areturn]]}]})
        ctor (.getConstructor klass
                              (doto ^"[Ljava.lang.Class;" (make-array Class 1)
                                (aset 0 MethodHandle)))]
    (fn [^MethodHandle h]
      (.newInstance ctor
                    (doto (object-array 1)
                      (aset 0 h))))))

(def ^:private downcall-class-ctor
  "Returns a function to construct a downcall class for the given memoized `args` and `ret` types.

  A downcall class is an implementation of [[IFn]] which calls a closed over
  method handle without reflection, unboxing primitives when needed."
  (memoize downcall-class-ctor*))

(defn- resolving-downcall-class-ctor*
  "Returns a function to construct a resolving downcall class for the given
  `args` and `ret` types.

  Like [[downcall-class-ctor*]], but the class holds an [[IFn]] symbol
  resolver alongside an unbound downcall handle: each invocation resolves the
  target address (a cheap cache lookup) and passes it as the handle's first
  argument. This keeps fns working across library reloads without composing
  method handles, which GraalVM native-image would have to interpret."
  [args ret]
  (let [klass (insn/define
                {:flags #{:public :final}
                 :version 8
                 :super clojure.lang.AFunction
                 :fields [{:name "downcall_handle"
                           :type MethodHandle
                           :flags #{:final}}
                          {:name "symbol_resolver"
                           :type IFn
                           :flags #{:final}}]
                 :methods [{:name :init
                            :flags #{:public}
                            :desc [MethodHandle IFn :void]
                            :emit [[:aload 0]
                                   [:dup]
                                   [:dup]
                                   [:invokespecial :super :init [:void]]
                                   [:aload 1]
                                   [:putfield :this "downcall_handle" MethodHandle]
                                   [:aload 2]
                                   [:putfield :this "symbol_resolver" IFn]
                                   [:return]]}
                           {:name :invoke
                            :flags #{:public}
                            :desc (repeat (cond-> (inc (count args))
                                            (not (mem/primitive-type ret)) inc)
                                          Object)
                            :emit [[:aload 0]
                                   [:getfield :this "downcall_handle" MethodHandle]
                                   [:aload 0]
                                   [:getfield :this "symbol_resolver" IFn]
                                   [:invokeinterface IFn "invoke" [Object]]
                                   [:checkcast MemorySegment]
                                   (when-not (mem/primitive-type ret)
                                     [[:aload 1]
                                      [:checkcast SegmentAllocator]])
                                   (map-indexed
                                    (fn [idx arg]
                                      [[:aload (cond-> (inc idx)
                                                 (not (mem/primitive-type ret)) inc)]
                                       (to-prim-asm arg)])
                                    args)
                                   [:invokevirtual MethodHandle "invokeExact"
                                    (cond->>
                                        (conj (mapv insn-layout args)
                                              (insn-layout ret))
                                      (not (mem/primitive-type ret)) (cons SegmentAllocator)
                                      :always (cons MemorySegment))]
                                   (to-object-asm ret)
                                   [:areturn]]}]})
        ctor (.getConstructor klass
                              (doto ^"[Ljava.lang.Class;" (make-array Class 2)
                                (aset 0 MethodHandle)
                                (aset 1 IFn)))]
    (fn [^MethodHandle h ^IFn resolver]
      (.newInstance ctor
                    (doto (object-array 2)
                      (aset 0 h)
                      (aset 1 resolver))))))

(def ^:private resolving-downcall-class-ctor
  "Returns a function to construct a resolving downcall class for the given
  memoized `args` and `ret` types.

  See [[resolving-downcall-class-ctor*]]."
  (memoize resolving-downcall-class-ctor*))

(defn- downcall-fn
  "Creates a function to call `handle` without reflection."
  [handle args ret]
  ((downcall-class-ctor args ret) ^MethodHandle handle))

(defn ensure-symbol
  "Returns the argument if it is a [[MemorySegment]], otherwise
  calls [[find-symbol]] on it."
  ^MemorySegment [symbol-or-addr]
  (if (instance? MemorySegment symbol-or-addr)
    symbol-or-addr
    (find-symbol symbol-or-addr)))

(defn make-downcall
  "Constructs a downcall function reference to `symbol-or-addr` with the given `args` and `ret` types.

  The function returned takes only arguments whose types match exactly
  the [[java-layout]] for that type, and returns an argument with exactly
  the [[java-layout]] of the `ret` type. This function will perform no
  serialization or deserialization of arguments or the return type.

  If the `ret` type is non-primitive, then the returned function will take a
  first argument of a [[SegmentAllocator]].

  If `symbol-or-addr` is a symbol name rather than an address, the returned
  function re-resolves the symbol on every call (a cheap cache lookup), so it
  stays valid when the library is reloaded with [[load-library]].

  Under a GraalVM native-image build, construction generates the wrapper
  class (baking it into the image) but defers symbol resolution and downcall
  handle creation until the image runs; the function descriptor must be
  registered in the image's reachability metadata."
  [symbol-or-addr args ret]
  (if (instance? MemorySegment symbol-or-addr)
    (-> (downcall-handle symbol-or-addr (function-descriptor args ret))
        (downcall-fn args ret))
    (let [sym-name (name symbol-or-addr)]
      ;; no symbol probe at construction: the library may be loaded later
      ;; (e.g. in -main of an AOT-compiled program); a missing symbol throws
      ;; UnsatisfiedLinkError at call time instead
      (record-fn-type-descriptors! [args ret])
      (let [ctor (resolving-downcall-class-ctor args ret)
            resolver (fn resolve-symbol [] (require-symbol sym-name))
            make (fn make-downcall-fn []
                   (ctor (unbound-downcall-handle (function-descriptor args ret))
                         resolver))]
        (if (native-image-build-time?)
          (do (warm-fn-wrapper-classes! [args ret])
              (if (Boolean/getBoolean "coffi.ffi.eager-native-image-handles")
                ;; GraalVM 25.1+ supports creating UNBOUND downcall handles
                ;; at image build time (they hold no native addresses; the
                ;; target address is passed per call — exactly coffi's
                ;; design). A baked handle constant-folds into a direct
                ;; stub call instead of going through method-handle
                ;; interpretation. Opt in with
                ;; -J-Dcoffi.ffi.eager-native-image-handles=true on the
                ;; native-image command line; older GraalVM versions fail
                ;; the image build with this enabled.
                (make)
                ;; default: downcall handle creation is deferred to first
                ;; call at image runtime, compatible with all GraalVM
                ;; versions but leaving handle invocation unoptimized
                (let [f (delay (make))]
                  (fn [& call-args] (apply @f call-args)))))
          (make))))))

(defn make-varargs-factory
  "Returns a function for constructing downcalls with additional types for arguments.

  The `required-args` are the types of the first arguments passed to the
  downcall handle, and the values passed to the returned function are only the
  varargs types.

  The returned function is memoized, so that only one downcall function will be
  generated per combination of argument types.

  See [[make-downcall]]."
  [symbol required-args ret]
  (memoize
   (fn [& types]
     (let [args (concat required-args types)]
       (make-downcall symbol args ret)))))

(def ^:private primitive-cast-sym
  "Map from non-pointer primitive types to functions that cast to the appropriate
  java primitive."
  {::mem/byte `byte
   ::mem/short `short
   ::mem/int `int
   ::mem/long `long
   ::mem/char `char
   ::mem/float `float
   ::mem/double `double})

(defn- inline-serde-wrapper
  "Builds a form that returns a function that calls `downcall` with serdes.

  The return type and any arguments that are primitives will not
  be (de)serialized except to be cast. If all arguments and return are
  primitive, the `downcall` is returned directly. In cases where arguments must
  be serialized, a new [[mem/confined-arena]] is generated."
  [downcall arg-types ret-type]
  (let [;; Complexity of types
        const-args? (or (vector? arg-types) (nil? arg-types))
        simple-args? (when const-args?
                       (and (every? mem/primitive? arg-types)
                            ;; NOTE(Joshua): Pointer types with serdes (e.g. [::mem/pointer ::mem/int])
                            ;; still require an arena, making them not qualify as "simple".
                            (every? keyword? (filter (comp #{::mem/pointer} mem/primitive-type) arg-types))))
        const-ret? (s/valid? ::mem/type ret-type)
        primitive-ret? (and const-ret?
                            (or (and (mem/primitive? ret-type)
                                     ;; NOTE(Joshua): Pointer types with serdes require deserializing the
                                     ;; return value, but don't require passing an arena to the downcall,
                                     ;; making them cause the return to not be primitive, but it may still
                                     ;; be "simple".
                                     (or (keyword? ret-type) (not (#{::mem/pointer} (mem/primitive-type ret-type)))))
                                (#{::mem/void} ret-type)))
        simple-ret? (and const-ret? (mem/primitive-type ret-type))
        no-serde? (and const-args? (empty? arg-types)
                       primitive-ret?)]
    (if no-serde?
      `(let [downcall# ~downcall]
         ;; NOTE(Joshua): These are here to ensure that evaluation order is
         ;; preserved as equivalent to a function call.
         ~arg-types
         ~ret-type
         downcall#)
      (let [;; All our symbols
            arena (gensym "arena")
            downcall-sym (gensym "downcall")
            args-sym (when-not const-args?
                       (gensym "args"))
            args-types-sym (when-not const-args?
                             (gensym "args-types"))
            arg-syms (when const-args?
                       (repeatedly (count arg-types) #(gensym "arg")))
            arg-type-syms (when const-args?
                            (repeatedly (count arg-types) #(gensym "arg-type")))
            ret-type-sym (gensym "ret-type")

            ;; Helper Functions
            make-serialized-binding
            ;; Given a symbol and its type, make a partial binding to serialize and shadow it
            (fn [sym type type-sym]
              (some->>
               (cond
                 (not (s/valid? ::mem/type type))
                 `(mem/serialize ~sym ~type-sym ~arena)

                 (and (mem/primitive? type)
                      (not (#{::mem/pointer} (mem/primitive-type type))))
                 (list (primitive-cast-sym (mem/primitive-type type)) sym)

                 ;; cast null pointers to something understood by panama
                 (#{::mem/pointer} type)
                 `(or ~sym mem/null)

                 (mem/primitive-type type)
                 `(mem/serialize* ~sym ~type-sym ~arena)

                 :else
                 `(let [alloc# (mem/alloc-instance ~type-sym)]
                    (mem/serialize-into ~sym ~type-sym alloc# ~arena)
                    alloc#))
               (list sym)))

            arg-serializers
            ;; Binding forms that rebind the arg symbols to their serialized counterparts
            (when const-args?
              (->> (map make-serialized-binding
                        arg-syms arg-types arg-type-syms)
                   (filter some?)))

            wrap-serialize
            ;; Wrap an expression to shadow args to their serialized counterparts
            (fn [expr]
              (cond
                (and const-args?
                     (zero? (count arg-types)))
                expr

                const-args?
                (if (seq arg-serializers)
                  `(let [~@(mapcat identity arg-serializers)]
                     ~expr)
                  expr)

                :else
                `(let [~args-sym (map (fn [obj# type#]
                                        (mem/serialize obj# type# ~arena))
                                      ~args-sym ~args-types-sym)]
                   ~expr)))

            make-call (fn [args & {:keys [allocator?]}]
                        ;; NOTE(Joshua): If `args` is a symbol, that means we're
                        ;; taking restargs, and so the downcall must be applied
                        (-> `(~@(when (symbol? args) [`apply])
                              ~downcall-sym
                              ~@(when allocator? [`(mem/arena-allocator ~arena)])
                              ~@(if (symbol? args)
                                  [args]
                                  args))
                            wrap-serialize))

            deserialize-prim (fn [expr]
                               `(mem/deserialize* ~expr ~ret-type-sym))
            deserialize-segment (fn [expr]
                                  `(mem/deserialize-from ~expr ~ret-type-sym))
            deserialize-ret (fn [expr]
                              (cond
                                (and (or (mem/primitive? ret-type)
                                         (#{::mem/void} ret-type))
                                     (not (#{::mem/pointer} (mem/primitive-type ret-type))))
                                expr

                                (mem/primitive-type ret-type)
                                (deserialize-prim expr)

                                :else
                                (deserialize-segment expr)))

            wrap-arena (fn [expr]
                           `(with-open [~arena (mem/confined-arena)]
                              ~expr))
            wrap-fn (fn [call needs-arena?]
                      `(fn [~@(if const-args? arg-syms ['& args-sym])]
                         ~(cond-> call needs-arena? wrap-arena)))]
        `(let [;; NOTE(Joshua): To ensure all arguments are evaluated once and
               ;; in-order, they must be bound here
               ~downcall-sym ~downcall
               ~@(if const-args?
                   (mapcat vector arg-type-syms arg-types)
                   [args-types-sym arg-types])
               ~ret-type-sym ~ret-type]
           ~(if const-ret?
              (-> (make-call (if const-args? arg-syms args-sym)
                             :allocator? (not (mem/primitive-type ret-type)))
                  deserialize-ret
                  (wrap-fn (or (not simple-args?)
                               (not simple-ret?))))
              (let [prim-call (-> (make-call (if const-args? arg-syms args-sym)
                                             :allocator? false)
                                  deserialize-prim)
                    non-prim-call (-> (make-call (if const-args? arg-syms args-sym)
                                                 :allocator? true)
                                      deserialize-segment)]
                `(if (mem/primitive-type ~ret-type-sym)
                   ~(wrap-fn prim-call (not simple-args?))
                   ~(wrap-fn non-prim-call true)))))))))

(defn make-serde-wrapper
  "Constructs a wrapper function for the `downcall` which serializes the arguments
  and deserializes the return value."
  {:inline (fn [downcall arg-types ret-type]
             (inline-serde-wrapper downcall arg-types ret-type))}
  [downcall arg-types ret-type]
  (if (mem/primitive-type ret-type)
    (fn native-fn [& args]
      (with-open [arena (mem/confined-arena)]
        (mem/deserialize*
         (apply downcall (map #(mem/serialize %1 %2 arena) args arg-types))
         ret-type)))
    (fn native-fn [& args]
      (with-open [arena (mem/confined-arena)]
        (mem/deserialize-from
         (apply downcall (mem/arena-allocator arena)
                (map #(mem/serialize %1 %2 arena) args arg-types))
         ret-type)))))

(defn make-serde-varargs-wrapper
  "Constructs a wrapper function for the `varargs-factory` which produces
  functions that serialize the arguments and deserialize the return value."
  [varargs-factory required-args ret-type]
  (memoize
   (fn [& types]
     (let [args-types (concat required-args types)]
       (make-serde-wrapper
        (apply varargs-factory types)
        args-types
        ret-type)))))

(defn cfn
  "Constructs a Clojure function to call the native function referenced by `symbol`.

  The function returned will serialize any passed arguments into the `args`
  types, and deserialize the return to the `ret` type.

  If your `args` and `ret` are constants, then it is more efficient to
  call [[make-downcall]] followed by [[make-serde-wrapper]] because the latter
  has an inline definition which will result in less overhead from serdes."
  ;; TODO(Joshua): Add an inline arity for when the args and ret types are constant
  [symbol args ret]
  (-> symbol
      (make-downcall args ret)
      (make-serde-wrapper args ret)))

(defn vacfn-factory
  "Constructs a varargs factory to call the native function referenced by `symbol`.

  The function returned takes any number of type arguments and returns a
  specialized Clojure function for calling the native function with those
  arguments."
  [symbol required-args ret]
  (-> symbol
      (make-varargs-factory required-args ret)
      (make-serde-varargs-wrapper required-args ret)))

;;; Function types

(def ^:private return-for-type
  "Map from type name to the return instruction for that type."
  {::mem/byte :breturn
   ::mem/short :sreturn
   ::mem/int :ireturn
   ::mem/long :lreturn
   ::mem/char :creturn
   ::mem/float :freturn
   ::mem/double :dreturn
   ::mem/void :return})

(def ^:private double-sized?
  "Set of primitive types which require 2 indices in the constant pool."
  #{::mem/double ::mem/long})

(defn- method-type
  "Gets the [[MethodType]] for a set of `args` and `ret` types."
  ([args] (method-type args ::mem/void))
  ([args ret]
   (MethodType/methodType
    ^Class (mem/java-layout ret)
    ^"[Ljava.lang.Class;" (into-array Class (map mem/java-layout args)))))

(defn- upcall-class-ctor*
  "Returns the constructor fn and unbound `upcall` method handle for an
  upcall class for the given `arg-types` and `ret-type`.

  An upcall class is a class with a single method, `upcall`, which boxes any
  primitives passed to it and calls a closed over [[IFn]]. The method handle
  is resolved when the class is generated, so that no name-based member
  resolution happens at call time (which would require reflection metadata
  under GraalVM native-image)."
  [arg-types ret-type]
  (let [klass (insn/define
                {:flags #{:public :final}
                 :version 8
                 :fields [{:name "upcall_ifn"
                           :type IFn
                           :flags #{:final}}]
                 :methods [{:name :init
                            :flags #{:public}
                            :desc [IFn :void]
                            :emit [[:aload 0]
                                   [:dup]
                                   [:invokespecial :super :init [:void]]
                                   [:aload 1]
                                   [:putfield :this "upcall_ifn" IFn]
                                   [:return]]}
                           {:name :upcall
                            :flags #{:public}
                            :desc (conj (mapv insn-layout arg-types)
                                        (insn-layout ret-type))
                            :emit [[:aload 0]
                                   [:getfield :this "upcall_ifn" IFn]
                                   (loop [types arg-types
                                          acc []
                                          idx 1]
                                     (if (seq types)
                                       (let [prim (mem/primitive-type (first types))]
                                         (recur (rest types)
                                                (conj acc [[(load-instructions prim :aload) idx]
                                                           (to-object-asm (first types))])
                                                (cond-> (inc idx)
                                                  (double-sized? prim)
                                                  inc)))
                                       acc))
                                   [:invokeinterface IFn "invoke" (repeat (inc (count arg-types)) Object)]
                                   (to-prim-asm ret-type)
                                   [(return-for-type ret-type :areturn)]]}]})
        ctor (.getConstructor klass
                              (doto ^"[Ljava.lang.Class;" (make-array Class 1)
                                (aset 0 IFn)))]
    {:ctor (fn [^IFn f]
             (.newInstance ctor
                           (doto (object-array 1)
                             (aset 0 f))))
     :handle (.findVirtual (MethodHandles/lookup) klass "upcall"
                           (method-type arg-types ret-type))}))

(def ^:private upcall-class-ctor
  "Returns the constructor fn and unbound `upcall` method handle for an
  upcall class for the given memoized `arg-types` and `ret-type`.

  See [[upcall-class-ctor*]]."
  (memoize upcall-class-ctor*))

(defn- upcall
  "Constructs an instance of an upcall class, closing over `f`.

  See [[upcall-class-ctor]]."
  [f arg-types ret-type]
  ((:ctor (upcall-class-ctor arg-types ret-type)) ^IFn f))

(defn- upcall-handle
  "Constructs a method handle for invoking `f`, a function of `arg-count` args."
  [f arg-types ret-type]
  (.bindTo ^MethodHandle (:handle (upcall-class-ctor arg-types ret-type))
           (upcall f arg-types ret-type)))

(defmethod mem/primitive-type ::fn
  [_type]
  ::mem/pointer)

(defn- upcall-serde-wrapper
  "Creates a function that wraps `f` which deserializes the arguments and
  serializes the return type in the [[global-arena]]."
  [f arg-types ret-type]
  (fn [& args]
    (mem/serialize
     (apply f (map mem/deserialize args arg-types))
     ret-type
     (mem/global-arena))))

(defmethod mem/serialize* ::fn
  [f [_fn arg-types ret-type & {:keys [raw-fn?]} :as typ] arena]
  (if-let [address (::address (meta f))]
    (do (assert (= typ (::type (meta f)))
                "The type of a deserialized function must match the type it is re-serialized to.")
        address)
    (.upcallStub
     (Linker/nativeLinker)
     ^MethodHandle (cond-> f
                     (not raw-fn?) (upcall-serde-wrapper arg-types ret-type)
                     :always (upcall-handle arg-types ret-type))
     ^FunctionDescriptor (record-descriptor!
                          :upcalls (function-descriptor arg-types ret-type))
     ^Arena arena
     (make-array Linker$Option 0))))

(defmethod mem/deserialize* ::fn
  [addr [_fn arg-types ret-type & {:keys [raw-fn?] :as typ}]]
  (when-not (mem/null? addr)
    (vary-meta
      (-> ^MemorySegment addr
          (downcall-handle (function-descriptor arg-types ret-type))
          (downcall-fn arg-types ret-type)
          (cond-> (not raw-fn?) (make-serde-wrapper arg-types ret-type)))
      assoc
      ::address addr
      ::type typ)))

;;; Static memory access

(defn const
  "Gets the value of a constant stored in `symbol-or-addr`."
  [symbol-or-addr type]
  (mem/deserialize (ensure-symbol symbol-or-addr) [::mem/pointer type]))

(s/def ::defconst-args
  (s/cat :var-name simple-symbol?
         :docstring (s/? string?)
         :symbol-or-addr any?
         :type ::mem/type))

(defmacro defconst
  "Defines a var named by `symbol` to be the value of the given `type` from `symbol-or-addr`."
  {:arglists '([symbol docstring? symbol-or-addr type])}
  [& args]
  (let [args (s/conform ::defconst-args args)]
    `(let [symbol# (ensure-symbol ~(:symbol-or-addr args))]
       (def ~(:var-name args)
         ~@(when-let [doc (:docstring args)]
             (list doc))
         (const symbol# ~(:type args))))))
(s/fdef defconst
  :args ::defconst-args)

(deftype StaticVariable [seg type meta]
  IDeref
  (deref [_]
    (mem/deserialize-from seg type))

  IObj
  (withMeta [_ meta-map]
    (StaticVariable. seg type (atom meta-map)))
  IMeta
  (meta [_]
    @meta)
  IReference
  (resetMeta [_ meta-map]
    (reset! meta meta-map))
  (alterMeta [_ f args]
    (apply swap! meta f args)))

(defn freset!
  "Sets the value of `static-var` to `newval`, running it through [[serialize]]."
  [^StaticVariable static-var newval]
  (mem/serialize-into
   newval (.-type static-var)
   (.-seg static-var)
   (mem/global-arena))
  newval)

(defn fswap!
  "Non-atomically runs the function `f` over the value stored in `static-var`.

  The value is deserialized before passing it to `f`, and serialized before
  putting the value into `static-var`."
  [static-var f & args]
  (freset! static-var (apply f @static-var args)))

(defn static-variable-segment
  "Gets the backing [[MemorySegment]] from `static-var`.

  This is primarily useful when you need to pass the static variable's address
  to a native function which takes an [[Addressable]]."
  ^MemorySegment [static-var]
  (.-seg ^StaticVariable static-var))

(defn static-variable
  "Constructs a reference to a mutable value stored in `symbol-or-addr`.

  The returned value can be dereferenced, and has metadata.

  See [[freset!]], [[fswap!]]."
  [symbol-or-addr type]
  (StaticVariable. (.reinterpret ^MemorySegment (ensure-symbol symbol-or-addr)
                                 ^long (mem/size-of type))
                   type (atom nil)))

(defmacro defvar
  "Defines a var named by `symbol` to be a reference to the native memory from `symbol-or-addr`."
  {:arglists '([symbol docstring? symbol-or-addr type])}
  [& args]
  (let [args (s/conform ::defconst-args args)]
    `(let [symbol# (ensure-symbol ~(:symbol-or-addr args))]
       (def ~(:var-name args)
         ~@(when-let [doc (:docstring args)]
             (list doc))
         (static-variable symbol# ~(:type args))))))
(s/fdef defvar
  :args ::defconst-args)

(s/def :coffi.ffi.symbolspec/symbol string?)
(s/def :coffi.ffi.symbolspec/type keyword?)
(s/def ::symbolspec
  (s/keys :req-un [:coffi.ffi.symbolspec/type :coffi.ffi.symbolspec/symbol]))

(defmulti reify-symbolspec
  "Takes a spec for a symbol reference and returns a live value for that type."
  :type)
(s/fdef reify-symbolspec
  :args (s/cat :spec ::symbolspec))

(defmethod reify-symbolspec :function
  [spec]
  (cond->
      (make-downcall (:symbol spec)
                     (:function/args spec)
                     (:function/ret spec))
    (not (:function/raw-fn? spec))
    (make-serde-wrapper
     (:function/args spec)
     (:function/ret spec))))

(defmethod reify-symbolspec :varargs-factory
  [spec]
  (cond->
      (make-varargs-factory (:symbol spec)
                            (:function/args spec)
                            (:function/ret spec))
    (not (:function/raw-fn? spec))
    (make-serde-varargs-wrapper
     (:function/args spec)
     (:function/ret spec))))

(defmethod reify-symbolspec :const
  [spec]
  (const (:symbol spec)
         (:const/type spec)))

(defmethod reify-symbolspec :static-var
  [spec]
  (static-variable (:symbol spec)
                   (:static-var/type spec)))

(s/def ::libspec
  (s/map-of keyword? ::symbolspec))

(defn reify-libspec
  "Loads all the symbols specified in the `libspec`.

  The value of each key of the passed map is transformed as
  by [[reify-symbolspec]]."
  [libspec]
  (reduce-kv
   (fn [m k v]
     (assoc m k
            (reify-symbolspec v)))
   {}
   libspec))
(s/fdef reify-libspec
  :args (s/cat :libspec ::libspec)
  :ret (s/map-of keyword? any?))

(s/def ::defcfn-args
  (s/and
   (s/cat :name simple-symbol?
          :doc (s/? string?)
          :attr-map (s/? map?)
          :symbol (s/nonconforming
                   (s/or :string string?
                         :symbol simple-symbol?))
          :native-arglist (s/coll-of ::mem/type :kind vector?)
          :return-type ::mem/type
          :wrapper (s/?
                    (s/cat
                     :native-fn simple-symbol?
                     :fn-tail (let [fn-tail (s/cat :arglist (s/coll-of simple-symbol? :kind vector?)
                                                   :body (s/* any?))]
                                (s/alt
                                 :single-arity fn-tail
                                 :multi-arity (s/+ (s/spec fn-tail)))))))
   #(if (:wrapper %)
      (not= (:name %) (-> % :wrapper :native-fn))
      true)))

(defmacro defcfn
  "Defines a Clojure function which maps to a native function.

  `name` is the symbol naming the resulting var.
  `symbol` is a symbol or string naming the library symbol to link against.
  `arg-types` is a vector of qualified keywords representing the argument types.
  `ret-type` is a single qualified keyword representing the return type.
  `fn-tail` is the body of the function (potentially with multiple arities)
  which wraps the native one. Inside the function, `native-fn` is bound to a
  function that will serialize its arguments, call the native function, and
  deserialize its return type. If any body is present, you must call this
  function in order to call the native code.

  If no `fn-tail` is provided, then the resulting function will simply serialize
  the arguments according to `arg-types`, call the native function, and
  deserialize the return value.

  The number of args in the `fn-tail` need not match the number of `arg-types`
  for the native function. It need only call the native wrapper function with
  the correct arguments.

  See [[serialize]], [[deserialize]], [[make-downcall]]."
  {:arglists '([name docstring? attr-map? symbol arg-types ret-type]
               [name docstring? attr-map? symbol arg-types ret-type native-fn & fn-tail])
   :style/indent [:defn]}
  [& args]
  (let [args (s/conform ::defcfn-args args)
        native-sym (gensym "native")
        [arity fn-tail] (-> args :wrapper :fn-tail)
        fn-tail (case arity
                  :single-arity (cons (:arglist fn-tail) (:body fn-tail))
                  :multi-arity (map #(cons (:arglist %) (:body %)) fn-tail)
                  nil)
        arglists (map first (case arity
                              :single-arity [fn-tail]
                              :multi-arity fn-tail
                              nil))]
    `(let [~(or (-> args :wrapper :native-fn)
                native-sym)
           (-> (make-downcall ~(name (:symbol args)) ~(:native-arglist args) ~(:return-type args))
               (make-serde-wrapper ~(:native-arglist args) ~(:return-type args)))
           fun# ~(if (:wrapper args)
                   `(fn ~(:name args)
                      ~@fn-tail)
                   native-sym)]
       (def
         ~(with-meta (:name args)
            (merge (update (meta (:name args)) :arglists
                           (fn [old-list]
                             (list
                              'quote
                              (or old-list
                                  (seq arglists)
                                  (list
                                   (mapv (fn [type]
                                           (-> (cond-> type
                                                 (vector? type) first)
                                               name
                                               symbol))
                                         (:native-arglist args)))))))
                   (:attr-map args)))
         ~@(when-let [doc (:doc args)]
             (list doc))
         fun#))))
(s/fdef defcfn
  :args ::defcfn-args)
