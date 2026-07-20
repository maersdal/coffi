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
    CallSite
    MethodHandle
    MethodHandles
    MethodHandles$Lookup
    MethodType
    MutableCallSite)
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
  "Cache of resolved symbol addresses, so that repeated resolution — e.g. by
  the native-image call path, which re-resolves on every call — is just a
  map lookup. Invalidated whenever a library is loaded or unloaded."
  (ConcurrentHashMap.))

(def ^:private downcall-sites
  "Registry of `[site fallback]` pairs, one per reload-aware downcall
  [[MutableCallSite]], so library loads and unloads can retarget every site
  back to its resolving fallback. All access must hold the lock
  on [[libraries]]."
  (java.util.ArrayList.))

(defn- reset-downcall-sites!
  "Retargets every registered downcall call site back to its resolving
  fallback and syncs the change to all threads, so the next call through
  each site re-resolves its symbol. Must be called while holding the lock
  on [[libraries]], whenever a library is loaded or unloaded."
  []
  (let [n (.size ^java.util.ArrayList downcall-sites)
        sites ^"[Ljava.lang.invoke.MutableCallSite;" (make-array MutableCallSite n)]
    (dotimes [i n]
      (let [[^MutableCallSite site ^MethodHandle fallback]
            (.get ^java.util.ArrayList downcall-sites i)]
        (.setTarget site fallback)
        (aset sites i site)))
    (MutableCallSite/syncAll sites))
  nil)

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
    (.clear ^ConcurrentHashMap symbol-cache)
    (reset-downcall-sites!))
  (let [arena (Arena/ofShared)]
    (try
      (.put ^LinkedHashMap libraries key
            {:arena arena
             :lookup (SymbolLookup/libraryLookup target arena)
             :content-hash content-hash})
      (.clear ^ConcurrentHashMap symbol-cache)
      (reset-downcall-sites!)
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
  names (e.g. via [[defcfn]] or [[cfn]]) relink to the fresh library on
  their next call and keep working across reloads. By default a reload does
  not wait for calls in flight on other threads — reloading a library while
  it is being called into concurrently is undefined behavior unless the
  `coffi.ffi.protected-downcalls` system property is set
  (see [[make-downcall]]).

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
  obtained from it are dangling, and using them may crash the JVM. By
  default unloading does not wait for calls in flight on other threads —
  unloading a library while it is being called into concurrently is
  undefined behavior unless the `coffi.ffi.protected-downcalls` system
  property is set (see [[make-downcall]]).

  The OS may keep a library mapped despite unloading (e.g. when it is a
  dependency of another loaded library, or is pinned by TLS destructors); in
  that case a subsequent [[load-library]] can silently return the old code."
  [path]
  (let [filepath (.getCanonicalPath (io/file path))]
    (locking libraries
      (when-some [entry (.remove ^LinkedHashMap libraries filepath)]
        (.close ^Arena (:arena entry))
        (.clear ^ConcurrentHashMap symbol-cache)
        (reset-downcall-sites!))))
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


;; the rest of the namespace is split across topic files to keep each
;; file reviewable; everything still lives in coffi.ffi
(load "ffi/codegen")
(load "ffi/wrappers")
