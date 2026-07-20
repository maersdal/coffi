;; Serialization and deserialization multimethods and their
;; implementations for the primitive types. Loaded into coffi.mem;
;; see the ns form in mem.clj.
(in-ns 'coffi.mem)

(def primitive-types
  "A set of all primitive types."
  #{::byte ::short ::int ::long
    ::char ::float ::double ::pointer})

(defn primitive?
  "A predicate to determine if a given type is primitive."
  [type]
  (contains? primitive-types (type-dispatch type)))

(defmulti primitive-type
  "Gets the primitive type that is used to pass as an argument for the `type`.

  This is for objects which are passed to native functions as primitive types,
  but which need additional logic to be performed during serialization and
  deserialization.

  Implementations of this method should take into account that type arguments
  may not always be evaluated before passing to this function.

  Returns nil for any type which does not have a primitive representation."
  type-dispatch)

(defmethod primitive-type :default
  [_type]
  nil)

(defmethod primitive-type ::byte
  [_type]
  ::byte)

(defmethod primitive-type ::short
  [_type]
  ::short)

(defmethod primitive-type ::int
  [_type]
  ::int)

(defmethod primitive-type ::long
  [_type]
  ::long)

(defmethod primitive-type ::char
  [_type]
  ::char)

(defmethod primitive-type ::float
  [_type]
  ::float)

(defmethod primitive-type ::double
  [_type]
  ::double)

(defmethod primitive-type ::pointer
  [_type]
  ::pointer)

(defmethod primitive-type ::pointer?
  [_type]
  ::pointer)

(defmethod primitive-type ::void
  [_type]
  ::void)

(defmulti c-layout
  "Gets the layout object for a given `type`.

  If a type is primitive it will return the appropriate primitive
  layout (see [[c-prim-layout]]).

  Otherwise, it should return a [[GroupLayout]] for the given type."
  type-dispatch)

(defmethod c-layout :default
  [type]
  (c-layout (primitive-type type)))

(defmethod c-layout ::byte
  [_type]
  byte-layout)

(defmethod c-layout ::short
  [type]
  (if (sequential? type)
    (.withOrder short-layout ^ByteOrder (second type))
    short-layout))

(defmethod c-layout ::int
  [type]
  (if (sequential? type)
    (.withOrder int-layout ^ByteOrder (second type))
    int-layout))

(defmethod c-layout ::long
  [type]
  (if (sequential? type)
    (.withOrder long-layout ^ByteOrder (second type))
    long-layout))

(defmethod c-layout ::char
  [_type]
  char-layout)

(defmethod c-layout ::float
  [type]
  (if (sequential? type)
    (.withOrder float-layout ^ByteOrder (second type))
    float-layout))

(defmethod c-layout ::double
  [type]
  (if (sequential? type)
    (.withOrder double-layout ^ByteOrder (second type))
    double-layout))

(defmethod c-layout ::pointer
  [_type]
  pointer-layout)

(def java-prim-layout
  "Map of primitive type names to the Java types for a method handle."
  {::byte Byte/TYPE
   ::short Short/TYPE
   ::int Integer/TYPE
   ::long Long/TYPE
   ::char Byte/TYPE
   ::float Float/TYPE
   ::double Double/TYPE
   ::pointer MemorySegment
   ::void Void/TYPE})

(defn java-layout
  "Gets the Java class to an argument of this type for a method handle.

  If a type serializes to a primitive it returns return a Java primitive type.
  Otherwise, it returns [[MemorySegment]]."
  ^Class [type]
  (java-prim-layout (or (primitive-type type) type) MemorySegment))

(defn size-of
  "The size in bytes of the given `type`."
  ^long [type]
  (let [t (cond-> type
            (not (instance? MemoryLayout type)) c-layout)]
    (.byteSize ^MemoryLayout t)))

(defn align-of
  "The alignment in bytes of the given `type`."
  ^long [type]
  (let [t (cond-> type
            (not (instance? MemoryLayout type)) c-layout)]
    (.byteAlignment ^MemoryLayout t)))

(defn alloc-instance
  "Allocates a memory segment for the given `type`."
  (^MemorySegment [type] (alloc-instance type (auto-arena)))
  (^MemorySegment [type arena] (.allocate ^Arena arena ^long (size-of type) ^long (align-of type))))

(declare serialize serialize-into)

(defmulti serialize*
  "Constructs a serialized version of the `obj` and returns it.

  Any new allocations made during the serialization should be tied to the given
  `arena`, except in extenuating circumstances.

  This method should only be implemented for types that serialize to primitives."
  (fn
    #_{:clj-kondo/ignore [:unused-binding]}
    [obj type arena]
    (type-dispatch type)))

(defmethod serialize* :default
  [obj type _arena]
  (throw (ex-info "Attempted to serialize a non-primitive type with primitive methods"
                  {:type type
                   :object obj})))

(defmethod serialize* ::byte
  [obj _type _arena]
  (byte obj))

(defmethod serialize* ::short
  [obj _type _arena]
  (short obj))

(defmethod serialize* ::int
  [obj _type _arena]
  (int obj))

(defmethod serialize* ::long
  [obj _type _arena]
  (long obj))

(defmethod serialize* ::char
  [obj _type _arena]
  (char obj))

(defmethod serialize* ::float
  [obj _type _arena]
  (float obj))

(defmethod serialize* ::double
  [obj _type _arena]
  (double obj))

(defn- null-pointer-error
  "Error for a NULL or nil value in a non-nullable [[::pointer]].

  Non-nullable by default with an explicit nullable twin is modeled after
  dtype-next's `:pointer`/`:pointer?` distinction: a NULL crossing the
  boundary unnoticed usually surfaces later as a segfault, so plain
  [[::pointer]] fails fast instead."
  [obj type direction]
  (ex-info (str (case direction
                  :serialize "nil/NULL passed as a non-nullable pointer"
                  :deserialize "native code returned NULL for a non-nullable pointer")
                "; use :coffi.mem/pointer? if NULL is expected here")
           {:type type
            :object obj}))

(defmethod serialize* ::pointer
  [obj type arena]
  (if-not (null? obj)
    (if (sequential? type)
      (let [segment (alloc-instance (second type) arena)]
        (serialize-into obj (second type) segment arena)
        segment)
      obj)
    (throw (null-pointer-error obj type :serialize))))

(defmethod serialize* ::pointer?
  [obj type arena]
  (if-not (null? obj)
    (if (sequential? type)
      (let [segment (alloc-instance (second type) arena)]
        (serialize-into obj (second type) segment arena)
        segment)
      obj)
    null))

(defmethod serialize* ::void
  [_obj _type _arena]
  nil)

(defmulti serialize-into
  "Writes a serialized version of the `obj` to the given `segment`.

  Any new allocations made during the serialization should be tied to the given
  `arena`, except in extenuating circumstances.

  This method should be implemented for any type which does not
  override [[c-layout]].

  For any other type, this will serialize it as [[serialize*]] before writing
  the result value into the `segment`."
  {:arglists '([obj type segment arena])}
  (fn
    #_{:clj-kondo/ignore [:unused-binding]}
    [obj type segment arena]
    (type-dispatch type)))

(defmethod serialize-into :default
  [obj type segment arena]
  (if-some [prim-layout (primitive-type type)]
    (serialize-into (serialize* obj type arena) prim-layout segment arena)
    (throw (ex-info "Attempted to serialize an object to a type that has not been overridden"
                    {:type type
                     :object obj}))))

(defmethod serialize-into ::byte
  [obj _type segment _arena]
  (write-byte segment obj))

(defmethod serialize-into ::short
  [obj type segment _arena]
  (if (sequential? type)
    (write-short segment 0 (second type) (short obj))
    (write-short segment (short obj))))

(defmethod serialize-into ::int
  [obj type segment _arena]
  (if (sequential? type)
    (write-int segment 0 (second type) (int obj))
    (write-int segment (int obj))))

(defmethod serialize-into ::long
  [obj type segment _arena]
  (if (sequential? type)
    (write-long segment 0 (second type) (long obj))
    (write-long segment (long obj))))

(defmethod serialize-into ::char
  [obj _type segment _arena]
  (write-char segment (char obj)))

(defmethod serialize-into ::float
  [obj type segment _arena]
  (if (sequential? type)
    (write-float segment 0 (second type) (float obj))
    (write-float segment (float obj))))

(defmethod serialize-into ::double
  [obj type segment _arena]
  (if (sequential? type)
    (write-double segment 0 (second type) (double obj))
    (write-double segment (double obj))))

(defmethod serialize-into ::pointer
  [obj type segment arena]
  ;; only reject a raw nil (a user value): a NULL *segment* here may be the
  ;; legitimate output of a higher-level type's serialize* (e.g. c-string),
  ;; routed through this method by the :default implementation
  (when (nil? obj)
    (throw (null-pointer-error obj type :serialize)))
  (write-address
   segment
   (cond-> obj
     (sequential? type) (serialize* type arena))))

(defmethod serialize-into ::pointer?
  [obj type segment arena]
  (write-address segment (serialize* obj type arena)))

(defn serialize
  "Serializes an arbitrary type.

  For types which have a primitive representation, this serializes into that
  representation. For types which do not, it allocates a new segment and
  serializes into that."
  ([obj type] (serialize obj type (auto-arena)))
  ([obj type arena]
   (if (primitive-type type)
     (serialize* obj type arena)
     (let [segment (alloc-instance type arena)]
       (serialize-into obj type segment arena)
       segment))))

(declare deserialize deserialize*)

(defmulti deserialize-from
  "Deserializes the given segment into a Clojure data structure.

  For types that serialize to primitives, a default implementation will
  deserialize the primitive before calling [[deserialize*]]."
  {:arglists '([segment type])}
  (fn
    #_{:clj-kondo/ignore [:unused-binding]}
    [segment type]
    (type-dispatch type)))

(defmethod deserialize-from :default
  [segment type]
  (if-some [prim (primitive-type type)]
    (-> segment
        (deserialize-from prim)
        (deserialize* type))
    (throw (ex-info "Attempted to deserialize a non-primitive type that has not been overriden"
                    {:type type
                     :segment segment}))))

(defmethod deserialize-from ::byte
  [segment _type]
  (read-byte segment))

(defmethod deserialize-from ::short
  [segment type]
  (if (sequential? type)
    (read-short segment 0 (second type))
    (read-short segment)))

(defmethod deserialize-from ::int
  [segment type]
  (if (sequential? type)
    (read-int segment 0 (second type))
    (read-int segment)))

(defmethod deserialize-from ::long
  [segment type]
  (if (sequential? type)
    (read-long segment 0 (second type))
    (read-long segment)))

(defmethod deserialize-from ::char
  [segment _type]
  (read-char segment))

(defmethod deserialize-from ::float
  [segment type]
  (if (sequential? type)
    (read-float segment 0 (second type))
    (read-float segment)))

(defmethod deserialize-from ::double
  [segment type]
  (if (sequential? type)
    (read-double segment 0 (second type))
    (read-double segment)))

;; no NULL check here: higher-level nullable types (e.g. c-string) read
;; their possibly-NULL address through this method via the :default
;; implementation; the typed-deref path checks in deserialize* instead
(defmethod deserialize-from ::pointer
  [segment type]
  (cond-> (read-address segment)
    (sequential? type) (deserialize* type)))

(defmethod deserialize-from ::pointer?
  [segment type]
  (deserialize* (read-address segment) type))

(defmulti deserialize*
  "Deserializes a primitive object into a Clojure data structure.

  This is intended for use with types that are returned as a primitive but which
  need additional processing before they can be returned."
  (fn
    #_{:clj-kondo/ignore [:unused-binding]}
    [obj type]
    (type-dispatch type)))

(defmethod deserialize* :default
  [obj type]
  (throw (ex-info "Attempted to deserialize a non-primitive type with primitive methods"
                  {:type type
                   :segment obj})))

(defmethod deserialize* ::byte
  [obj _type]
  obj)

(defmethod deserialize* ::short
  [obj _type]
  obj)

(defmethod deserialize* ::int
  [obj _type]
  obj)

(defmethod deserialize* ::long
  [obj _type]
  obj)

(defmethod deserialize* ::char
  [obj _type]
  obj)

(defmethod deserialize* ::float
  [obj _type]
  obj)

(defmethod deserialize* ::double
  [obj _type]
  obj)

(defmethod deserialize* ::pointer
  [addr type]
  (if-not (null? addr)
    (if (sequential? type)
      (let [target-type (second type)]
        (deserialize-from
         (.reinterpret ^MemorySegment addr
                       ^long (size-of target-type))
         target-type))
      addr)
    (throw (null-pointer-error addr type :deserialize))))

(defmethod deserialize* ::pointer?
  [addr type]
  (when-not (null? addr)
    (if (sequential? type)
      (let [target-type (second type)]
        (deserialize-from
         (.reinterpret ^MemorySegment addr
                       ^long (size-of target-type))
         target-type))
      addr)))

(defmethod deserialize* ::void
  [_obj _type]
  nil)

(defn deserialize
  "Deserializes an arbitrary type.

  For types which have a primitive representation, this deserializes the
  primitive representation. For types which do not, this deserializes out of
  a segment."
  [obj type]
  (when-not (identical? ::void type)
    (if (primitive-type type)
      (deserialize* obj type)
      (deserialize-from obj type))))

(defn seq-of
  "Constructs a lazy sequence of `type` elements deserialized from `segment`."
  [type segment]
  (map #(deserialize % type) (slice-segments segment (size-of type))))

