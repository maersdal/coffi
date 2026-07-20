;; Serde wrappers, upcalls and function types, cfn, static memory
;; access, defcfn, and deflibrary. Loaded into coffi.ffi; see the ns
;; form in ffi.clj.
(in-ns 'coffi.ffi)

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
                              nil))
        ;; for wrapper-less prim-eligible signatures serde is coercion the
        ;; downcall class already performs, so the raw downcall — which
        ;; implements the matching clojure.lang.IFn$ interface — is def'd
        ;; directly, and the var gets prim-tagged arglists so callers
        ;; compile to boxless invokePrim calls (dtype-next-inspired)
        prim-lists (when-not (:wrapper args)
                     (prim-arglists (:native-arglist args) (:return-type args)))]
    `(let [~(or (-> args :wrapper :native-fn)
                native-sym)
           ~(if prim-lists
              `(make-downcall ~(name (:symbol args)) ~(:native-arglist args) ~(:return-type args))
              `(-> (make-downcall ~(name (:symbol args)) ~(:native-arglist args) ~(:return-type args))
                   (make-serde-wrapper ~(:native-arglist args) ~(:return-type args))))
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
                                  prim-lists
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

(defn- deflibrary-fn-form
  "Builds the [[defcfn]] form for one `[fn-key fdef]` entry of [[deflibrary]]."
  [lib-name check-error-sym fn-key fdef]
  (when-not (and (map? fdef) (keyword? fn-key))
    (throw (ex-info "deflibrary function definitions must be a map of keyword names to definition maps"
                    {:library lib-name
                     :entry [fn-key fdef]})))
  (let [{:keys [args ret doc check-error?]
         :or {args [] ret ::mem/void}} fdef
        _ (when-not (vector? args)
            (throw (ex-info "deflibrary :args must be a vector of types"
                            {:library lib-name
                             :fn fn-key
                             :args args})))
        native (or (:symbol fdef) (str/replace (name fn-key) "-" "_"))
        fn-name (symbol (name fn-key))]
    (if (and check-error? check-error-sym)
      (let [natfn (gensym "native")
            arg-syms (mapv #(gensym (str "arg" % "-")) (range (count args)))]
        `(defcfn ~fn-name ~@(when doc [doc]) ~native ~args ~ret
           ~natfn ~arg-syms
           (~check-error-sym (~natfn ~@arg-syms) ~fn-key)))
      `(defcfn ~fn-name ~@(when doc [doc]) ~native ~args ~ret))))

(defmacro deflibrary
  "Defines a var for every function in `fn-defs`, plus `name` holding the
  definitions themselves as a data artifact.

  One `defcfn` is generated per entry, so everything about single
  definitions — serdes, the primitive invoke path, reload behavior —
  applies unchanged. Defining whole libraries as data is modeled after
  dtype-next's `define-library`.

  `fn-defs` is a literal map of keyword fn names to definition maps:

  * `:args` — vector of coffi types (default `[]`).
  * `:ret` — return type (default `::mem/void`).
  * `:symbol` — the native symbol (default: the fn name with dashes
    replaced by underscores, e.g. `:add-longs` → `\"add_longs\"`).
  * `:doc` — docstring for the generated var.
  * `:check-error?` — when true, the library's `:check-error` wraps this
    fn's calls.

  Options after `fn-defs`:

  * `:check-error` — a fn of `[return-value fn-key]` called around every
    call of the entries marked `:check-error?`; it returns the (possibly
    replaced) return value or throws. Centralizes a library's error
    discipline instead of hand-writing a wrapper body per defcfn.

  Example:

  ```clojure
  (deflibrary libm
    \"Bindings to the C math library.\"
    {:fabs {:args [::mem/double] :ret ::mem/double :doc \"Absolute value.\"}
     :cosh {:args [::mem/double] :ret ::mem/double}})
  ```"
  {:arglists '([name docstring? fn-defs & {:keys [check-error]}])
   :style/indent [:defn]}
  [name & args]
  (let [[doc args] (if (string? (first args))
                     [(first args) (rest args)]
                     [nil args])
        [fn-defs & {:keys [check-error]}] args]
    (when-not (map? fn-defs)
      (throw (ex-info "deflibrary requires a literal map of fn definitions"
                      {:library name
                       :fn-defs fn-defs})))
    (let [ce-sym (when check-error
                   (symbol (str (clojure.core/name name) "-check-error")))]
      `(do
         ~@(when check-error
             [`(def ~(with-meta ce-sym {:private true}) ~check-error)])
         ~@(map (fn [[k fdef]] (deflibrary-fn-form name ce-sym k fdef))
                fn-defs)
         (def ~(with-meta name (cond-> {} doc (assoc :doc doc)))
           '~fn-defs)))))
