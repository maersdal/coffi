;; Bytecode generation for downcall classes, the invokedynamic call
;; site machinery, and make-downcall. Loaded into coffi.ffi; see the
;; ns form in ffi.clj.
(in-ns 'coffi.ffi)

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
  will be popped. Long and double args are coerced with the `RT` number casts
  rather than an exact-class checkcast, matching [[coffi.mem/serialize*]]'s
  coercion for those types."
  [type]
  (cond
    (identical? ::mem/void type) [:pop]
    (identical? ::mem/pointer (mem/primitive-type type)) []
    :else
    (let [prim-type (some-> type mem/primitive-type)]
      (case prim-type
        ::mem/long [[:invokestatic clojure.lang.RT "longCast" [Object :long]]]
        ::mem/double [[:invokestatic clojure.lang.RT "doubleCast" [Object :double]]]
        (if-some [prim (some-> prim-type name keyword)]
          [[:checkcast (prim-classes prim-type)]
           [:invokevirtual (prim-classes prim-type) (unbox-fn-for-type prim-type) [prim]]]
          [])))))

(def ^:private prim-type-code
  "Codes of the types representable as args in a `clojure.lang.IFn$`
  primitive-invoke interface."
  {::mem/long "L"
   ::mem/double "D"})

(defn ^:no-doc prim-sig
  "Returns the `clojure.lang.IFn$` primitive interface for a downcall
  signature, or nil when it has none.

  Only signatures of one to four `::mem/long`/`::mem/double` args returning
  long, double, or void are representable. Primitive-interface support on
  downcall fns — letting prim-hinted callers skip boxing entirely — is
  modeled after dtype-next's typed library methods."
  ^Class [args ret]
  (when (and (<= 1 (count args) 4)
             (every? prim-type-code args)
             (or (identical? ::mem/void ret) (prim-type-code ret)))
    (Class/forName (str "clojure.lang.IFn$"
                        (apply str (map prim-type-code args))
                        (get prim-type-code ret "O")))))

(defn ^:no-doc prim-arglists
  "Returns a prim-tagged `:arglists` value for a downcall signature, or nil
  when the signature has no primitive-interface representation.

  Attached to vars def'd by [[defcfn]] for eligible signatures, so the
  Clojure compiler turns calls through the var into `invokePrim` calls —
  no boxed args or return. Inspired by dtype-next's typed library methods."
  [arg-types ret-type]
  (when (prim-sig arg-types ret-type)
    (list (with-meta
            (into []
                  (map-indexed
                   (fn [i t]
                     (with-meta (symbol (str "arg" i))
                       {:tag (if (identical? ::mem/double t) 'double 'long)})))
                  arg-types)
            (when-not (identical? ::mem/void ret-type)
              {:tag (if (identical? ::mem/double ret-type) 'double 'long)})))))

(defn- prim-invoke-desc
  "The insn method descriptor of `invokePrim` for a prim-eligible signature."
  [args ret]
  (conj (mapv #(if (identical? ::mem/double %) :double :long) args)
        (case ret
          ::mem/double :double
          ::mem/void Object
          :long)))

(defn- prim-arg-loads
  "Bytecode loading `invokePrim`'s primitive arguments, starting at local 1."
  [args]
  (first
   (reduce (fn [[out idx] arg]
             [(conj out [(if (identical? ::mem/double arg) :dload :lload) idx])
              (+ idx 2)])
           [[] 1]
           args)))

(defn- prim-return-asm
  "Bytecode returning `invokePrim`'s result for a prim-eligible signature."
  [ret]
  (case ret
    ::mem/double [[:dreturn]]
    ::mem/void [[:ldc nil] [:areturn]]
    [[:lreturn]]))

(defn- downcall-class-ctor*
  "Returns a function to construct a downcall class for the given `args` and `ret` types.

  A downcall class is an implementation of [[IFn]] which calls a closed over
  method handle without reflection, unboxing primitives when needed. For
  prim-eligible signatures (see [[prim-sig]]) the class also implements the
  matching `clojure.lang.IFn$` interface, so prim-hinted callers box
  nothing."
  [args ret]
  (let [prim-iface (prim-sig args ret)
        klass (insn/define
                {:flags #{:public :final}
                 :version 8
                 :super clojure.lang.AFunction
                 :interfaces (when prim-iface [prim-iface])
                 :fields [{:name "downcall_handle"
                           :type MethodHandle
                           :flags #{:final}}]
                 :methods
                 (cond->
                     [{:name :init
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
                              [:areturn]]}]
                   prim-iface
                   (conj {:name "invokePrim"
                          :flags #{:public}
                          :desc (prim-invoke-desc args ret)
                          :emit [[:aload 0]
                                 [:getfield :this "downcall_handle" MethodHandle]
                                 (prim-arg-loads args)
                                 [:invokevirtual MethodHandle "invokeExact"
                                  (conj (mapv insn-layout args)
                                        (insn-layout ret))]
                                 (prim-return-asm ret)]}))})
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
  method handles, which GraalVM native-image would have to interpret.

  For prim-eligible signatures (see [[prim-sig]]) the class also implements
  the matching `clojure.lang.IFn$` interface: caller bytecode compiled
  against a prim-tagged var contains `invokePrim` calls, and the same AOT
  classes must keep working when this class is what the var holds at
  native-image runtime."
  [args ret]
  (let [prim-iface (prim-sig args ret)
        klass (insn/define
                {:flags #{:public :final}
                 :version 8
                 :super clojure.lang.AFunction
                 :interfaces (when prim-iface [prim-iface])
                 :fields [{:name "downcall_handle"
                           :type MethodHandle
                           :flags #{:final}}
                          {:name "symbol_resolver"
                           :type IFn
                           :flags #{:final}}]
                 :methods
                 (cond->
                     [{:name :init
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
                              [:areturn]]}]
                   prim-iface
                   (conj {:name "invokePrim"
                          :flags #{:public}
                          :desc (prim-invoke-desc args ret)
                          :emit [[:aload 0]
                                 [:getfield :this "downcall_handle" MethodHandle]
                                 [:aload 0]
                                 [:getfield :this "symbol_resolver" IFn]
                                 [:invokeinterface IFn "invoke" [Object]]
                                 [:checkcast MemorySegment]
                                 (prim-arg-loads args)
                                 [:invokevirtual MethodHandle "invokeExact"
                                  (cons MemorySegment
                                        (conj (mapv insn-layout args)
                                              (insn-layout ret)))]
                                 (prim-return-asm ret)]}))})
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

(defn- deferred-downcall-class-ctor*
  "Returns a function wrapping an [[IDeref]] of a downcall fn in an [[IFn]]
  which dereferences it on every call.

  Used on the native-image runtime path, where downcall handle creation is
  deferred to first call. A generated class rather than a plain fn so that
  prim-eligible signatures implement the matching `clojure.lang.IFn$`
  interface — AOT-compiled callers of prim-tagged vars contain `invokePrim`
  calls, which must keep working against the deferred wrapper."
  [args ret]
  (let [prim-iface (prim-sig args ret)
        ;; matching the other downcall classes, this count includes the
        ;; return value alongside the parameters
        obj-arity (cond-> (inc (count args))
                    (not (mem/primitive-type ret)) inc)
        klass (insn/define
                {:flags #{:public :final}
                 :version 8
                 :super clojure.lang.AFunction
                 :interfaces (when prim-iface [prim-iface])
                 :fields [{:name "delegate"
                           :type IDeref
                           :flags #{:final}}]
                 :methods
                 (cond->
                     [{:name :init
                       :flags #{:public}
                       :desc [IDeref :void]
                       :emit [[:aload 0]
                              [:dup]
                              [:invokespecial :super :init [:void]]
                              [:aload 1]
                              [:putfield :this "delegate" IDeref]
                              [:return]]}
                      {:name :invoke
                       :flags #{:public}
                       :desc (repeat obj-arity Object)
                       :emit [[:aload 0]
                              [:getfield :this "delegate" IDeref]
                              [:invokeinterface IDeref "deref" [Object]]
                              [:checkcast IFn]
                              (map (fn [idx] [:aload idx]) (range 1 obj-arity))
                              [:invokeinterface IFn "invoke" (repeat obj-arity Object)]
                              [:areturn]]}]
                   prim-iface
                   (conj {:name "invokePrim"
                          :flags #{:public}
                          :desc (prim-invoke-desc args ret)
                          :emit [[:aload 0]
                                 [:getfield :this "delegate" IDeref]
                                 [:invokeinterface IDeref "deref" [Object]]
                                 [:checkcast prim-iface]
                                 (prim-arg-loads args)
                                 [:invokeinterface prim-iface "invokePrim"
                                  (prim-invoke-desc args ret)]
                                 (prim-return-asm ret)]}))})
        ctor (.getConstructor klass
                              (doto ^"[Ljava.lang.Class;" (make-array Class 1)
                                (aset 0 IDeref)))]
    (fn [^IDeref delegate]
      (.newInstance ctor
                    (doto (object-array 1)
                      (aset 0 delegate))))))

(def ^:private deferred-downcall-class-ctor
  "Memoized [[deferred-downcall-class-ctor*]]."
  (memoize deferred-downcall-class-ctor*))

(def ^:private indy-callsites
  "The [[MutableCallSite]] of every [[callsite-downcall-fn*]]-generated
  class, keyed by the boxed `Integer` id embedded in the class's
  `invokedynamic` instruction; read by [[callsite-for-id]] when the JVM
  links the instruction."
  (ConcurrentHashMap.))

(def ^:private indy-callsite-ids
  (java.util.concurrent.atomic.AtomicInteger.))

(defn- callsite-for-id
  "Returns the registered downcall call site with `id`. Called by name from
  the `invokedynamic` bootstrap method of generated downcall classes."
  [id]
  (.get ^ConcurrentHashMap indy-callsites id))

(def ^:private indy-bootstrap-class
  "Class holding the `invokedynamic` bootstrap method for generated downcall
  classes. The bootstrap ignores the standard lookup arguments and returns
  the call site registered under the instruction's id constant."
  (delay
    (insn/define
      {:flags #{:public :final}
       :version 8
       :methods [{:name "bootstrap"
                  :flags #{:public :static}
                  :desc [MethodHandles$Lookup String MethodType :int CallSite]
                  :emit [[:ldc "coffi.ffi"]
                         [:ldc "callsite-for-id"]
                         [:invokestatic clojure.java.api.Clojure "var"
                          [Object Object IFn]]
                         [:iload 3]
                         [:invokestatic Integer "valueOf" [:int Integer]]
                         [:invokeinterface IFn "invoke" [Object Object]]
                         [:checkcast CallSite]
                         [:areturn]]}]})))

(defn- link-downcall-site!
  "Slow path of a lazily linked downcall call site: resolves `sym-name`,
  retargets `site` to `unbound` bound to the resolved address, and completes
  the pending call with `args`.

  Runs on a downcall fn's first call and on its first call after any library
  load or unload. Resolution and retargeting happen under the [[libraries]]
  lock so a concurrent load or unload cannot leave the site targeting a
  stale address.

  By default the resolved address is rebased to the global scope before
  binding, so calls pay no per-call liveness check; the trade is that
  loading or unloading a library while another thread is inside a call into
  it unmaps the code mid-call — undefined behavior. Under
  `coffi.ffi.protected-downcalls` the handle is bound to the address as
  resolved, scoped to its library's arena: every call then pays the FFM
  liveness protocol (a scope acquire/release, ~7ns, more under contention)
  which makes [[unload-library]] and reloads wait for in-flight calls
  before unmapping."
  [^MutableCallSite site fdesc sym-name ^objects args]
  (let [^MethodHandle bound
        (locking libraries
          (let [addr ^MemorySegment (require-symbol sym-name)
                addr (if (Boolean/getBoolean "coffi.ffi.protected-downcalls")
                       addr
                       (MemorySegment/ofAddress (.address addr)))
                bound ^MethodHandle (downcall-handle addr fdesc)]
            (.setTarget site bound)
            bound))]
    (.invokeWithArguments bound ^java.util.List (java.util.Arrays/asList args))))

(defn- callsite-downcall-fn*
  "Creates an [[IFn]] that calls the native function `sym-name` through
  a [[MutableCallSite]].

  The generated class calls the site via an `invokedynamic` instruction
  whose bootstrap returns the site, so the JIT treats the current target —
  the downcall handle bound to the resolved address — as a constant and
  compiles calls down to a direct native call, the same machine code as a
  downcall handle in a hand-written `static final` field. Library loads and
  unloads retarget every site back to its resolving fallback
  (see [[reset-downcall-sites!]]), deoptimizing any compiled calls, and the
  next call relinks — so reload support costs nothing per call in steady
  state.

  Steady-state calls run at parity with a hand-written `static final`
  downcall handle; the only optional per-call cost is the liveness check
  enabled by `coffi.ffi.protected-downcalls`, which makes unloading a
  library wait for calls in flight on other threads
  (see [[link-downcall-site!]])."
  [sym-name args ret]
  (let [fdesc (function-descriptor args ret)
        ;; created only for its type — the bound handle's, sans the leading
        ;; address parameter — so linking can be deferred past construction
        unbound ^MethodHandle (unbound-downcall-handle fdesc)
        type (.dropParameterTypes (.type unbound) 0 1)
        site (MutableCallSite. ^MethodType type)
        slow (fn link-and-call [args-arr]
               (link-downcall-site! site fdesc sym-name args-arr))
        fallback (-> (.findVirtual (MethodHandles/lookup) IFn "invoke"
                                   (MethodType/genericMethodType 1))
                     (.bindTo slow)
                     (.asCollector (class (object-array 0)) (.parameterCount type))
                     (.asType type))
        id (.incrementAndGet ^java.util.concurrent.atomic.AtomicInteger
                             indy-callsite-ids)]
    (.setTarget site fallback)
    ;; registered before the class can execute its invokedynamic, whose
    ;; bootstrap looks the site up by id
    (.put ^ConcurrentHashMap indy-callsites (Integer/valueOf id) site)
    (locking libraries
      (.add ^java.util.ArrayList downcall-sites [site fallback]))
    (let [prim-iface (prim-sig args ret)
          boot [:invokestatic @indy-bootstrap-class "bootstrap"
                [MethodHandles$Lookup String MethodType :int CallSite]]
          klass (insn/define
                  {:flags #{:public :final}
                   :version 8
                   :super clojure.lang.AFunction
                   :interfaces (when prim-iface [prim-iface])
                   :methods
                   (cond->
                       [{:name :init
                         :flags #{:public}
                         :desc [:void]
                         :emit [[:aload 0]
                                [:invokespecial :super :init [:void]]
                                [:return]]}
                        {:name :invoke
                         :flags #{:public}
                         :desc (repeat (cond-> (inc (count args))
                                         (not (mem/primitive-type ret)) inc)
                                       Object)
                         :emit [(when-not (mem/primitive-type ret)
                                  [[:aload 1]
                                   [:checkcast SegmentAllocator]])
                                (map-indexed
                                 (fn [idx arg]
                                   [[:aload (cond-> (inc idx)
                                              (not (mem/primitive-type ret)) inc)]
                                    (to-prim-asm arg)])
                                 args)
                                [:invokedynamic "downcall"
                                 (cond->>
                                     (conj (mapv insn-layout args)
                                           (insn-layout ret))
                                   (not (mem/primitive-type ret)) (cons SegmentAllocator))
                                 boot
                                 [(Integer/valueOf id)]]
                                (to-object-asm ret)
                                [:areturn]]}]
                     ;; a second invokedynamic against the same bootstrap id:
                     ;; two instructions, one shared MutableCallSite
                     prim-iface
                     (conj {:name "invokePrim"
                            :flags #{:public}
                            :desc (prim-invoke-desc args ret)
                            :emit [(prim-arg-loads args)
                                   [:invokedynamic "downcall"
                                    (conj (mapv insn-layout args)
                                          (insn-layout ret))
                                    boot
                                    [(Integer/valueOf id)]]
                                   (prim-return-asm ret)]}))})]
      (.newInstance (.getConstructor ^Class klass (make-array Class 0))
                    (object-array 0)))))

(def ^:private callsite-downcall-fn
  "Memoized [[callsite-downcall-fn*]]: one class and call site per distinct
  `[sym-name args ret]`, so re-evaluating a [[defcfn]] (e.g. reloading its
  namespace at the REPL) reuses the existing call site instead of defining a
  new class."
  (memoize callsite-downcall-fn*))

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
  function calls through a [[MutableCallSite]] which is lazily linked to the
  resolved symbol on first call and retargeted whenever a library is loaded
  or unloaded, so it stays valid across [[load-library]] reloads. The JIT
  compiles the linked site down to a direct native call with no per-call
  overhead — hand-written-downcall performance. By default this assumes
  libraries are not unloaded or reloaded while *other threads* are inside
  calls into them; set the `coffi.ffi.protected-downcalls` system property
  to true to make every call acquire its library's scope (~7ns/call, more
  under contention), which makes unloads and reloads wait for calls in
  flight instead.

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
      (if (native-image-build-time?)
        (let [ctor (resolving-downcall-class-ctor args ret)
              resolver (fn resolve-symbol [] (require-symbol sym-name))
              make (fn make-downcall-fn []
                     (ctor (unbound-downcall-handle (function-descriptor args ret))
                           resolver))]
          (warm-fn-wrapper-classes! [args ret])
          (if (Boolean/getBoolean "coffi.ffi.eager-native-image-handles")
            ;; GraalVM 25.1+ supports creating UNBOUND downcall
            ;; handles at image build time (they hold no native
            ;; addresses; the target address is passed per call —
            ;; exactly coffi's design). A baked handle constant-folds
            ;; into a direct stub call instead of going through
            ;; method-handle interpretation (~420 ns vs ~4 µs per
            ;; call). Opt in with
            ;; -J-Dcoffi.ffi.eager-native-image-handles=true on the
            ;; native-image command line. NB: that is the GraalVM
            ;; version, not the JDK version — the 25i1 image tags are
            ;; GraalVM 25.1.x and work; GraalVM 25.0.x (also on JDK
            ;; 25) fails the image build with a linkToNative parsing
            ;; error during analysis, hence opt-in.
            (make)
            ;; default: downcall handle creation is deferred to first
            ;; call at image runtime, compatible with all GraalVM
            ;; versions but leaving handle invocation unoptimized
            ((deferred-downcall-class-ctor args ret) (delay (make)))))
        (callsite-downcall-fn sym-name args ret)))))

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

