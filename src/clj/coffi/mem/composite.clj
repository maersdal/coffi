;; Composite types: c-strings, unions, structs, arrays, enums,
;; flagsets, padding, type aliases, and the defstruct machinery.
;; Loaded into coffi.mem; see the ns form in mem.clj.
(in-ns 'coffi.mem)

;;; Raw composite types
;; TODO(Joshua): Ensure that all the raw values don't have anything happen on
;; serialize in the inlining of [[coffi.ffi/make-serde-wrapper]]

(defmethod c-layout ::raw
  [[_raw type]]
  (c-layout type))

(defmethod serialize-into ::raw
  [obj _type segment _arena]
  (if (instance? MemorySegment obj)
    (copy-segment segment obj)
    obj))

(defmethod deserialize-from ::raw
  [segment _type]
  (if (instance? MemorySegment segment)
    (clone-segment segment)
    segment))

;;; C String type

(defmethod primitive-type ::c-string
  [_type]
  ::pointer)

(defmethod serialize* ::c-string
  [obj _type ^Arena arena]
  (if obj
    (.allocateFrom arena ^String obj)
    null))

(defmethod deserialize* ::c-string
  [addr _type]
  (when-not (null? addr)
    (.getString (.reinterpret ^MemorySegment addr Integer/MAX_VALUE) 0)))

;;; Union types

(defmethod c-layout ::union
  [[_union types & {:as _opts} :as _type]]
  (let [items (if (map? types)
                (map
                  (fn [[field-name field]]
                    (.withName ^MemoryLayout (c-layout field) (name field-name)))
                  types)
                (map c-layout types))]
    (MemoryLayout/unionLayout
     (into-array MemoryLayout items))))

(defmethod serialize-into ::union
  [obj [_union _types & {:keys [dispatch extract]} :as type] segment arena]
  (when-not dispatch
    (throw (ex-info "Attempted to serialize a union with no dispatch function"
                    {:type type
                     :value obj})))
  (let [type (dispatch obj)]
    (serialize-into
     (if extract
       (extract type obj)
       obj)
     type
     segment
     arena)))

(defmethod deserialize-from ::union
  [segment type]
  (clone-segment (slice segment 0 (size-of type))))

;;; Struct types

(defmethod c-layout ::struct
  [[_struct fields]]
  (let [fields (for [[field-name field] fields]
                 (.withName ^MemoryLayout (c-layout field)
                            (name field-name)))]
    (MemoryLayout/structLayout
     (into-array MemoryLayout fields))))

(defmethod serialize-into ::struct
  [obj [_struct fields] segment arena]
  (loop [offset 0
         fields fields]
    (when (seq fields)
      (let [[field type] (first fields)
            size (size-of type)]
        (serialize-into
         (get obj field) type
         (slice segment offset size) arena)
        (recur (long (+ offset size)) (rest fields))))))

(defmethod deserialize-from ::struct
  [segment [_struct fields]]
  (loop [offset 0
         fields fields
         obj {}]
    (if (seq fields)
      (let [[field type] (first fields)
            size (size-of type)]
        (recur
         (long (+ offset size))
         (rest fields)
         (assoc obj field (deserialize-from
                           (slice segment offset size)
                           type))))
      obj)))

(defn struct-field-offset
  ;; TODO(Joshua): Implement an inline arity for this function when dealing with
  ;; a constant type and field to prevent repeated lookups
  "Given a `struct-def`, returns the byte offset of the `field`."
  [struct-def field]
  (let [layout ^MemoryLayout (c-layout struct-def)
        path-elts
        ^"[Ljava.lang.foreign.MemoryLayout$PathElement;"
        (into-array MemoryLayout$PathElement
                    [(MemoryLayout$PathElement/groupElement (name field))])]
    (.byteOffset layout path-elts)))

;;; Padding type

(defmethod c-layout ::padding
  [[_padding size]]
  (MemoryLayout/paddingLayout size))

(defmethod serialize-into ::padding
  [_obj [_padding _size] _segment _arena]
  nil)

(defmethod deserialize-from ::padding
  [_segment [_padding _size]]
  nil)

;;; Array types

(defmethod c-layout ::array
  [[_array type count]]
  (MemoryLayout/sequenceLayout
   count
   (c-layout type)))

(defmethod serialize-into ::array
  [obj [_array type count] segment arena]
  (dorun
   (map #(serialize-into %1 type %2 arena)
        obj
        (slice-segments (slice segment 0 (* count (size-of type)))
                        (size-of type)))))

(def ^:private primitive-array-type
  "Map from primitive types to the primitive array constructor function for that type."
  {::byte byte-array
   ::short short-array
   ::int int-array
   ::long long-array
   ::float float-array
   ::double double-array})

(defn- bulk-primitive-array
  "Bulk-copies `count` elements of primitive `type` out of `segment`.

  A single intrinsified copy, rather than a slice allocation and a
  deserialization dispatch per element."
  [segment type count]
  (let [sliced ^MemorySegment (slice segment 0 (* (long count) (size-of type)))]
    (condp identical? type
      ::byte (.toArray sliced ^ValueLayout$OfByte byte-layout)
      ::short (.toArray sliced ^ValueLayout$OfShort short-layout)
      ::int (.toArray sliced ^ValueLayout$OfInt int-layout)
      ::long (.toArray sliced ^ValueLayout$OfLong long-layout)
      ::float (.toArray sliced ^ValueLayout$OfFloat float-layout)
      ::double (.toArray sliced ^ValueLayout$OfDouble double-layout))))

(defmethod deserialize-from ::array
  [segment [_array type count & {:keys [raw?]}]]
  (if (contains? primitive-array-type type)
    (let [arr (bulk-primitive-array segment type count)]
      (if raw? arr (vec arr)))
    (let [segments (slice-segments (slice segment 0 (* count (size-of type)))
                                   (size-of type))]
      (if raw?
        ((primitive-array-type type object-array)
         (sequence (map #(deserialize-from % type)) segments))
        (mapv #(deserialize-from % type) segments)))))

;;; Enum types

(defmethod primitive-type ::enum
  [[_enum _variants & {:keys [repr]}]]
  (if repr
    (primitive-type repr)
    ::int))

(defn- enum-variants-map
  "Constructs a map from enum variant objects to their native representations.

  Enums are mappings from Clojure objects to numbers, with potential default
  values for each element based on order.

  If `variants` is a map, then every variant has a value provided already (a
  guarantee of maps in Clojure's syntax) and we are done.

  If `variants` is a vector then we assume C-style implicit enum values,
  counting from 0. If an element of `variants` itself is a vector, it must be a
  vector tuple of the variant object to the native representation, with further
  counting continuing from that value."
  [variants]
  (if (map? variants)
    variants
    (first
     (reduce
      (fn [[m next-id] variant]
        (if (vector? variant)
          [(conj m variant) (inc (second variant))]
          [(assoc m variant next-id) (inc next-id)]))
      [{} 0]
      variants))))

(defmethod serialize* ::enum
  [obj [_enum variants & {:keys [repr]}] arena]
  (serialize* ((enum-variants-map variants) obj)
              (or repr ::int)
              arena))

(defmethod deserialize* ::enum
  [obj [_enum variants & {:keys [_repr]}]]
  ((set/map-invert (enum-variants-map variants)) obj))

;;; Flagsets

(defmethod primitive-type ::flagset
  [[_flagset _bits & {:keys [repr]}]]
  (if repr
    (primitive-type repr)
    ::int))

(defmethod serialize* ::flagset
  [obj [_flagset bits & {:keys [repr]}] arena]
  (let [bits-map (enum-variants-map bits)]
    (reduce #(bit-set %1 (get bits-map %2)) (serialize* 0 (or repr ::int) arena) obj)))

(defmethod deserialize* ::flagset
  [obj [_flagset bits & {:keys [repr]}]]
  (let [bits-map (set/map-invert (enum-variants-map bits))]
    (reduce #(if-not (zero? (bit-and 1 (bit-shift-right obj %2)))
               (conj %1 (bits-map %2))
               %1)
            #{}
            (range (* 8 (size-of (or repr ::int)))))))

(s/def ::type
  (s/spec
   (s/nonconforming
    (s/or :simple-type qualified-keyword?
          :complex-type (s/cat :base-type qualified-keyword?
                               :type-args (s/* any?))))))

(defmacro defalias
  "Defines a type alias from `new-type` to `aliased-type`.

  This creates needed serialization and deserialization implementations for the
  aliased type."
  {:style/indent [:defn]}
  [new-type aliased-type]
  (if (and (s/valid? ::type aliased-type)
           (primitive-type aliased-type))
    `(let [aliased# ~aliased-type]
       (defmethod primitive-type ~new-type
         [_type#]
         (primitive-type aliased#))
       (defmethod serialize* ~new-type
         [obj# _type# arena#]
         (serialize* obj# aliased# arena#))
       (defmethod deserialize* ~new-type
         [obj# _type#]
         (deserialize* obj# aliased#)))
    `(let [aliased# ~aliased-type]
       (defmethod c-layout ~new-type
         [_type#]
         (c-layout aliased#))
       (defmethod serialize-into ~new-type
         [obj# _type# segment# arena#]
         (serialize-into obj# aliased# segment# arena#))
       (defmethod deserialize-from ~new-type
         [segment# _type#]
         (deserialize-from segment# aliased#)))))
(s/fdef defalias
  :args (s/cat :new-type qualified-keyword?
               :aliased-type any?))

(defn- coffitype->typename [in]
  (let [[indirect-type type n & {:keys [raw?] :as opts}] (if (vector? in) in [:- in])
        arr? (= indirect-type ::array)
        ptr? (= indirect-type ::pointer)
        array-types  {::byte   'bytes
                      ::short  'shorts
                      ::int    'ints
                      ::long   'longs
                      ::char   'chars
                      ::float  'floats
                      ::double 'doubles}
        single-types {::byte     'byte
                      ::short    'short
                      ::int      'int
                      ::long     'long
                      ::char     'char
                      ::float    'float
                      ::double   'double
                      ::c-string 'String}]
    (cond (and arr? raw?) (get array-types type 'objects)
          (and arr?)      `clojure.lang.IPersistentVector
          (and ptr?)      `java.lang.foreign.MemorySegment
          :default        (get single-types type type))))

(defn- coffitype->array-fn [type]
  (get
   {:coffi.mem/byte   `byte-array
    :coffi.mem/short  `short-array
    :coffi.mem/int    `int-array
    :coffi.mem/long   `long-array
    :coffi.mem/char   `char-array
    :coffi.mem/float  `float-array
    :coffi.mem/double `double-array}
   type
   `object-array))

(defn- coffitype->array-write-fn [type]
  ({:coffi.mem/byte   `write-bytes
    :coffi.mem/short  `write-shorts
    :coffi.mem/int    `write-ints
    :coffi.mem/long   `write-longs
    :coffi.mem/char   `write-chars
    :coffi.mem/float  `write-floats
    :coffi.mem/double `write-doubles} type))

(defn- coffitype->array-read-fn [type]
  ({:coffi.mem/byte   `read-bytes
    :coffi.mem/short  `read-shorts
    :coffi.mem/int    `read-ints
    :coffi.mem/long   `read-longs
    :coffi.mem/char   `read-chars
    :coffi.mem/float  `read-floats
    :coffi.mem/double `read-doubles} type))

(defmulti  generate-deserialize (fn [& xs] (if (vector? (first xs)) (ffirst xs) (first xs))))

(defmethod generate-deserialize :coffi.mem/byte     [_type offset segment-source-form] `(read-byte    ~segment-source-form ~offset))
(defmethod generate-deserialize :coffi.mem/short    [_type offset segment-source-form] `(read-short   ~segment-source-form ~offset))
(defmethod generate-deserialize :coffi.mem/int      [_type offset segment-source-form] `(read-int     ~segment-source-form ~offset))
(defmethod generate-deserialize :coffi.mem/long     [_type offset segment-source-form] `(read-long    ~segment-source-form ~offset))
(defmethod generate-deserialize :coffi.mem/char     [_type offset segment-source-form] `(read-char    ~segment-source-form ~offset))
(defmethod generate-deserialize :coffi.mem/float    [_type offset segment-source-form] `(read-float   ~segment-source-form ~offset))
(defmethod generate-deserialize :coffi.mem/double   [_type offset segment-source-form] `(read-double  ~segment-source-form ~offset))
(defmethod generate-deserialize :coffi.mem/pointer  [_type offset segment-source-form] `(read-address ~segment-source-form ~offset))
(defmethod generate-deserialize :coffi.mem/c-string [_type offset segment-source-form]
  `(.getString (.reinterpret (.get ~(with-meta segment-source-form {:tag 'java.lang.foreign.MemorySegment}) pointer-layout ~offset) Integer/MAX_VALUE) 0))

(defn- generate-deserialize-array-as-array-bulk [array-type n offset segment-source-form]
  (list (coffitype->array-read-fn array-type) segment-source-form n offset))

(defn- generate-deserialize-array-as-array-inline [array-type n offset segment-source-form]
  (let [a (gensym 'array)]
    (concat
     `(let [~a (~(coffitype->array-fn array-type) ~n)])
     (map
      #(list `aset a % (generate-deserialize array-type (+ offset (* (size-of array-type) %)) segment-source-form))
      (range n))
     [a])))

(defn- generate-deserialize-array-as-array-loop [array-type n offset segment-source-form]
  (let [a (gensym 'array)]
    (concat
     `(let [~a (~(coffitype->array-fn array-type) ~n)])
     [(list `dotimes ['m n]
       (list `aset a 'm (generate-deserialize array-type `(+ ~offset (* ~(size-of array-type) ~'m)) segment-source-form)))]
     [a])))

(defn- generate-deserialize-array-as-array [array-type n offset segment-source-form]
  (if (coffitype->array-read-fn array-type)
    (generate-deserialize-array-as-array-bulk array-type n offset segment-source-form) ;bulk-copy available
    (let [inline-cutoff 32] ;this magic value has been benchmarked, but it may need adjusting for specific architectures
      (if (< n inline-cutoff)
        (generate-deserialize-array-as-array-inline array-type n offset segment-source-form)
        (generate-deserialize-array-as-array-loop array-type n offset segment-source-form)))))

(defn- generate-deserialize-array-as-vector-loop [array-type n offset segment-source-form]
  (let [loop-deserialize (generate-deserialize array-type `(+ ~offset (* ~(size-of array-type) ~'i)) segment-source-form)]
    `(loop [~'i 0 ~'v (transient [])]
       (if (< ~'i ~n)
         (recur (unchecked-inc ~'i) (conj! ~'v ~loop-deserialize))
         (persistent! ~'v)))))

(defn- generate-deserialize-array-as-vector-inline [array-type n offset segment-source-form]
  (vec (map #(generate-deserialize array-type (+ offset (* (size-of array-type) %)) segment-source-form) (range n))))

(defn- generate-deserialize-array-as-vector [array-type n offset segment-source-form]
  (let [inline-cutoff 64] ;this magic value has been benchmarked, but it may need adjusting for specific architectures
    (if (< n inline-cutoff)
      (generate-deserialize-array-as-vector-inline array-type n offset segment-source-form)
      (generate-deserialize-array-as-vector-loop array-type n offset segment-source-form))))

(defmethod generate-deserialize :coffi.mem/array    [[_ array-type n & {:keys [raw?]}] offset segment-source-form]
  (if raw?
    (generate-deserialize-array-as-array array-type n offset segment-source-form)
    (generate-deserialize-array-as-vector array-type n offset segment-source-form)))

(defn- typelist [fields]
  (->>
   (partition 2 2 (interleave (reductions + 0 (map (comp size-of second) fields)) fields))
   (filter (fn [[_ [_ field-type]]] (not (and (vector? field-type) (= "padding" (name (first field-type)))))))))

(defn register-new-struct-deserialization [typename [_struct fields]]
  (defmethod generate-deserialize typename [_type global-offset segment-source-form]
    (->> (typelist fields)
         (map-indexed
          (fn [index [offset [_ field-type]]]
            (generate-deserialize field-type (if (number? global-offset) (+ global-offset offset) `(+ ~global-offset ~offset)) segment-source-form)))
         (cons (symbol (str (name typename) "."))))))

(defmulti  generate-serialize (fn [& xs] (if (vector? (first xs)) (ffirst xs) (first xs))))

(defmethod generate-serialize :coffi.mem/byte     [_type source-form offset segment-source-form] `(write-byte    ~segment-source-form ~offset ~source-form))
(defmethod generate-serialize :coffi.mem/short    [_type source-form offset segment-source-form] `(write-short   ~segment-source-form ~offset ~source-form))
(defmethod generate-serialize :coffi.mem/int      [_type source-form offset segment-source-form] `(write-int     ~segment-source-form ~offset ~source-form))
(defmethod generate-serialize :coffi.mem/long     [_type source-form offset segment-source-form] `(write-long    ~segment-source-form ~offset ~source-form))
(defmethod generate-serialize :coffi.mem/char     [_type source-form offset segment-source-form] `(write-char    ~segment-source-form ~offset ~source-form))
(defmethod generate-serialize :coffi.mem/float    [_type source-form offset segment-source-form] `(write-float   ~segment-source-form ~offset ~source-form))
(defmethod generate-serialize :coffi.mem/double   [_type source-form offset segment-source-form] `(write-double  ~segment-source-form ~offset ~source-form))
(defmethod generate-serialize :coffi.mem/pointer  [_type source-form offset segment-source-form] `(write-address ~segment-source-form ~offset ~source-form))
(defmethod generate-serialize :coffi.mem/c-string [_type source-form offset segment-source-form] `(write-address ~segment-source-form ~offset (.allocateFrom (Arena/ofAuto) ~source-form)))

(defn- generate-serialize-array-as-array-bulk [member-type length source-form offset segment-source-form]
  (list (coffitype->array-write-fn member-type) segment-source-form length offset source-form))

(defn- generate-serialize-array-as-array-loop [member-type length source-form offset segment-source-form]
  (let [obj (with-meta (gensym 'src-array) {:tag (coffitype->typename [::array member-type length :raw? true])})]
    (list `let [obj source-form]
     (list `dotimes ['n length]
      (generate-serialize member-type `(aget ~obj ~'n) `(+ ~offset (* ~(size-of member-type) ~'n)) segment-source-form)))))

(defn- generate-serialize-array-as-array-inline [member-type length source-form offset segment-source-form]
  (let [obj (with-meta (gensym 'src-array) {:tag (coffitype->typename [::array member-type length :raw? true])})]
    (concat
     (list `let [obj source-form])
     (map
      #(generate-serialize member-type (list `aget obj %) (+ offset (* (size-of member-type) %)) segment-source-form)
      (range length)))))

(defn- generate-serialize-array-as-array [member-type length source-form offset segment-source-form]
  (if (coffitype->array-write-fn member-type)
    (generate-serialize-array-as-array-bulk member-type length source-form offset segment-source-form)
    (let [inline-cutoff 32] ;this magic value has been benchmarked, but it may need adjusting for specific architectures
      (if (< length inline-cutoff)
        (generate-serialize-array-as-array-inline member-type length source-form offset segment-source-form)
        (generate-serialize-array-as-array-loop member-type length source-form offset segment-source-form)))))

(defn- generate-serialize-vector-as-array-bulk [member-type length source-form offset segment-source-form]
  (list (coffitype->array-write-fn member-type) segment-source-form length offset (list (coffitype->array-fn member-type) length source-form)))

(defn- generate-serialize-vector-as-array-loop [member-type length source-form offset segment-source-form]
  (let [obj (with-meta (gensym 'src-array) {:tag (coffitype->typename [::array member-type length :raw? false])})]
    (list `let [obj source-form]
     (list `dotimes ['n length]
      (generate-serialize member-type `(nth ~obj ~'n) `(+ ~offset (* ~(size-of member-type) ~'n)) segment-source-form)))))

(defn- generate-serialize-vector-as-array-inline [member-type length source-form offset segment-source-form]
  (let [obj (with-meta (gensym 'src-array) {:tag (coffitype->typename [::array member-type length :raw? false])})]
    (concat
     (list `let [obj source-form])
     (map
      #(generate-serialize member-type (list `aget obj %) (+ offset (* (size-of member-type) %)) segment-source-form)
      (range length)))))

(defn generate-serialize-vector-as-array [member-type length source-form offset segment-source-form]
  (let [cutoff 1024 ;this magic value has been benchmarked, but it may need adjusting for specific architectures
        obj (with-meta (gensym 'src-array) {:tag (coffitype->typename [::array member-type length :raw? false])})]
    (if (or (<= length cutoff) (not (coffitype->array-write-fn member-type)))
      (generate-serialize-vector-as-array-loop member-type length source-form offset segment-source-form)
      (generate-serialize-vector-as-array-bulk member-type length source-form offset segment-source-form))))

(defmethod generate-serialize :coffi.mem/array   [[_arr member-type length & {:keys [raw?]}] source-form offset segment-source-form]
  (if raw?
    (generate-serialize-array-as-array member-type length source-form offset segment-source-form)
    (generate-serialize-vector-as-array member-type length source-form offset segment-source-form)))

(defn register-new-struct-serialization [typename [_struct fields]]
  (let [fieldnames (filter #(not= "padding" (name %)) (map first fields))]
    (defmethod generate-serialize typename [_type source-form global-offset segment-source-form]
      (->> (typelist fields)
           (map-indexed
            (fn [index [offset [_ field-type]]]
              (generate-serialize field-type (list (symbol (str "." (name (nth fieldnames index)))) 'source-obj) (if (number? global-offset) (+ global-offset offset) `(+ ~global-offset ~offset)) segment-source-form)))
           (concat [`let ['source-obj source-form]])))))

(gen-interface
 :name coffi.mem.IStructImpl :methods
 [[vec_length [] int]
  [vec_assoc [Object Object] clojure.lang.Associative]
  [vec_assocN [int Object] clojure.lang.IPersistentVector]
  [vec_peek [] Object]
  [vec_pop [] clojure.lang.IPersistentVector]
  [vec_nth [int] Object]
  [vec_nth [int Object] Object]
  [vec_cons [Object] clojure.lang.IPersistentCollection]
  [vec_equiv [Object] boolean]
  [vec_empty [] clojure.lang.IPersistentVector]
  [vec_iterator [] java.util.Iterator]
  [vec_forEach [java.util.function.Consumer] void]
  [vec_seq [] clojure.lang.ISeq]
  [vec_rseq [] clojure.lang.ISeq]

  [struct_count [] int]
  [struct_containsKey [Object] boolean]
  [struct_valAt [Object] Object]
  [struct_valAt [Object Object] Object]
  [struct_entryAt [Object] clojure.lang.IMapEntry]
  [nthKey [int] clojure.lang.Keyword]

  [map_assoc [Object Object] clojure.lang.Associative]
  [map_assocEx [Object Object] clojure.lang.IPersistentMap]
  [map_without [Object] clojure.lang.IPersistentMap]
  [map_cons [Object] clojure.lang.IPersistentCollection]
  [map_equiv [Object] boolean]
  [map_empty [] clojure.lang.IPersistentMap]
  [map_iterator [] java.util.Iterator]
  [map_forEach [java.util.function.Consumer] void]
  [map_seq [] clojure.lang.ISeq]
  ;java.util.map fns
  [map_containsValue [Object] boolean]
  [map_entrySet [] java.util.Set]
  [map_get [Object] Object]
  [map_isEmpty [] boolean]
  [map_keySet [] java.util.Set]
  [map_size [] int]
  [map_values [] java.util.Collection]
  [map_forEach [java.util.function.BiConsumer] void]])


(defmacro ^:no-doc for-each-fixed-length [n]
  `(defn ~(with-meta (symbol (str "for-each-fixed-" n)) {:no-doc true}) ~[(with-meta 'offset {:tag int}) (with-meta 'action {:tag 'java.util.function.Consumer}) (with-meta 's {:tag 'coffi.mem.IStructImpl})]
     ~(cons `do (map (fn [i] (list '.accept (with-meta 'action {:tag 'java.util.function.Consumer}) (list '.vec_nth (with-meta 's {:tag 'coffi.mem.IStructImpl}) i))) (range n)))))

(for-each-fixed-length 1)
(for-each-fixed-length 2)
(for-each-fixed-length 3)
(for-each-fixed-length 4)
(for-each-fixed-length 5)
(for-each-fixed-length 6)
(for-each-fixed-length 7)
(for-each-fixed-length 8)
(for-each-fixed-length 9)
(for-each-fixed-length 10)
(for-each-fixed-length 11)
(for-each-fixed-length 12)
(for-each-fixed-length 13)
(for-each-fixed-length 14)
(for-each-fixed-length 15)
(for-each-fixed-length 16)

(deftype StructVecIterator [^coffi.mem.IStructImpl struct-obj ^int size ^{:volatile-mutable true :tag int} i]
  java.util.Iterator
  (forEachRemaining [this action]
    (case (- size i)
      1  (for-each-fixed-1  i action struct-obj)
      2  (for-each-fixed-2  i action struct-obj)
      3  (for-each-fixed-3  i action struct-obj)
      4  (for-each-fixed-4  i action struct-obj)
      5  (for-each-fixed-5  i action struct-obj)
      6  (for-each-fixed-6  i action struct-obj)
      7  (for-each-fixed-7  i action struct-obj)
      8  (for-each-fixed-8  i action struct-obj)
      9  (for-each-fixed-9  i action struct-obj)
      10 (for-each-fixed-10 i action struct-obj)
      11 (for-each-fixed-11 i action struct-obj)
      12 (for-each-fixed-12 i action struct-obj)
      13 (for-each-fixed-13 i action struct-obj)
      14 (for-each-fixed-14 i action struct-obj)
      15 (for-each-fixed-15 i action struct-obj)
      16 (for-each-fixed-16 i action struct-obj)
      (loop [index i] (if (< index size) (do (.accept action (.vec_nth struct-obj index)) (recur (inc index))) nil))))
  (hasNext [this] (< i size))
  (next [this] (let [ret (.vec_nth struct-obj i) _ (set! i (unchecked-add-int 1 i))] ret)))

(gen-interface :name coffi.mem.IStruct :methods [[asVec [] clojure.lang.IPersistentVector] [asMap [] clojure.lang.IPersistentMap]])

(deftype StructVecSeq [^clojure.lang.IPersistentVector v ^int i]
  clojure.lang.ISeq clojure.lang.Indexed clojure.lang.Sequential
  (first [this] (.nth v i))
  (next  [this] (if (< i (dec (.count v))) (StructVecSeq. v (inc i)) nil))
  (more  [this] (if (< i (dec (.count v))) (StructVecSeq. v (inc i)) '()))
  (cons  [this o] (clojure.lang.Cons. o this))
  (count [this] (- (.count v) i))
  (empty [this] nil)
  (equiv [this o] (= (subvec v i) o))
  (nth   [this j] (.nth v (+ i j)))
  (nth   [this j o] (.nth v (+ i j) o))
  (seq   [this] this))

(deftype StructMapSeq [^coffi.mem.IStructImpl s ^int i]
  clojure.lang.ISeq clojure.lang.Indexed clojure.lang.Sequential
  (first [this] (clojure.lang.MapEntry/create (.nthKey s i) (.vec_nth s i)))
  (next  [this] (if (< i (dec (.struct_count s))) (StructMapSeq. s (inc i)) nil))
  (more  [this] (if (< i (dec (.struct_count s))) (StructMapSeq. s (inc i)) '()))
  (cons  [this o] (clojure.lang.Cons. o this))
  (count [this] (- (.struct_count s) i))
  (empty [this] nil)
  (equiv [this o] (if (not= (count o) (.struct_count s)) false (loop [os (seq o) index i] (if (< index (- (.struct_count s) i)) (if (= [(.nthKey s index) (.vec_nth s index)] (first os)) (recur (next os) (inc index)) false) true))))
  (nth   [this j] (clojure.lang.MapEntry/create (.nthKey s (+ i j)) (.vec_nth s (+ i j))))
  (nth   [this j o] (if (< (+ i j) (.struct_count s)) (clojure.lang.MapEntry/create (.nthKey s (+ i j)) (.vec_nth s (+ i j))) o))
  (seq   [this] this))

(deftype VecWrap [^coffi.mem.IStructImpl org]
  coffi.mem.IStruct clojure.lang.IPersistentVector Iterable
  (length      [this]     (.vec_length org))
  (assoc       [this k v] (.vec_assoc org k v))
  (assocN      [this i v] (.vec_assocN org i v))
  (peek        [this]     (.vec_peek org))
  (pop         [this]     (.vec_pop org))
  (nth         [this i]   (.vec_nth org i))
  (nth         [this i o] (.vec_nth org i o))
  (cons        [this o]   (.vec_cons org o))
  (equiv       [this o]   (.vec_equiv org o))
  (empty       [this]     (.vec_empty org))
  (iterator    [this]     (.vec_iterator org))
  (forEach     [this c]   (.vec_forEach org c))
  (seq         [this]     (StructVecSeq. this 0))
  (rseq        [this]     (.vec_rseq org))
  (count       [this]     (.struct_count org))
  (containsKey [this k]   (.struct_containsKey org k))
  (valAt       [this k]   (.struct_valAt org k))
  (valAt       [this k o] (.struct_valAt org k o))
  (entryAt     [this k]   (.struct_entryAt org k))
  (asMap       [this]     org)
  (asVec       [this]     this))

(deftype MapWrap [^coffi.mem.IStructImpl org]
  coffi.mem.IStruct clojure.lang.IPersistentMap clojure.lang.MapEquivalence java.util.Map
  (cons        [this o]   (.map_cons org o))
  (equiv       [this o]   (.map_equiv org o))
  (empty       [this]     (.map_empty org))
  (iterator    [this]     (.map_iterator org))
  (^void forEach [this ^java.util.function.Consumer c] (.map_forEach org c))
  (^void forEach [this ^java.util.function.BiConsumer c] (.map_forEach org c))
  (seq         [this]     (StructMapSeq. org 0))
  (assoc       [this k v] (.map_assoc org k v))
  (count       [this]     (.struct_count org))
  (containsKey [this k]   (.struct_containsKey org k))
  (valAt       [this k]   (.struct_valAt org k))
  (valAt       [this k o] (.struct_valAt org k o))
  (entryAt     [this k]   (.struct_entryAt org k))
  (assocEx     [this k v] (.map_assocEx org k v))
  (without     [this k]   (.map_without org k))
  ;java.util.map implementations
  (containsValue [this k] (.map_containsValue org k))
  (entrySet      [this]   (.map_entrySet org))
  (get           [this k] (.map_get org k))
  (isEmpty       [this]   false)
  (keySet        [this]   (.map_keySet org))
  (size          [this]   (.map_size org))
  (values        [this]   (.map_values org))
  ;conversion methods
  (asMap       [this]     this)
  (asVec       [this]     org)
  )

(defn as-vec [^coffi.mem.IStruct struct] (.asVec struct))
(defn as-map [^coffi.mem.IStruct struct] (.asMap struct))


(defn- generate-struct-type [typename typed-member-symbols]
  (let [members (map (comp keyword str) typed-member-symbols)
        as-vec (vec (map (comp symbol name) members))
        as-map (into {} (map (fn [m] [m (symbol (name m))]) members))]
    (letfn [(vec-length    [] (list 'length      ['this]           (count members)))
            (vec-assoc     [] (list 'assoc       ['this 'i 'value] (list `assoc as-vec 'i 'value)))
            (vec-assocN    [] (list 'assocN      ['this 'i 'value] (list `assoc 'i as-vec 'value)))
            (vec-peek      [] (list 'peek        ['this]           (first as-vec)))
            (vec-pop       [] (list 'pop         ['this]           (vec (rest as-vec))))
            (vec-nth       [] (list 'nth         ['this 'i]        (concat [`case 'i] (interleave (range) as-vec))))
            (vec-nth-2     [] (list 'nth         ['this 'i 'o]     (concat [`case 'i] (interleave (range) as-vec) ['o])))
            (vec-cons      [] (list 'cons        ['this 'o]        (vec (cons 'o as-vec))))
            (vec-equiv     [] (list 'equiv       ['this 'o]        (list `= as-vec 'o)))
            (vec-empty     [] (list 'empty       ['this]           []))
            (vec-iterator  [] (list 'iterator    ['this]           (list `StructVecIterator. 'this (count members) 0)))
            (vec-foreach   [] (concat ['forEach  ['this 'action]]  (partition 2 (interleave (repeat 'action) as-vec))))
            (vec-seq       [] (list 'seq         ['this]           (list `StructVecSeq. 'this 0)))
            (vec-rseq      [] (list 'rseq        ['this]           (list `seq (vec (reverse as-vec)))))

            (s-count       [] (list 'count       ['this]           (count members)))
            (s-containsKey [] (list 'containsKey ['this 'k]        (list `if (list `number? 'k) (list `and (list `>= 'k 0) (list `< 'k (count members)) true) (list `case 'k (seq members) true false))))
            (s-valAt       [] (list 'valAt       ['this 'k]        (concat [`case 'k] (interleave (range) as-vec) (interleave members as-vec) [nil])))
            (s-valAt-2     [] (list 'valAt       ['this 'k 'o]     (concat [`case 'k] (interleave (range) as-vec) (interleave members as-vec) ['o])))
            (s-entryAt     [] (list 'entryAt     ['this 'k]        (list `let ['val-or-nil (concat [`case 'k] (interleave (range) as-vec) (interleave members as-vec) [nil])] (list `if 'val-or-nil (list `clojure.lang.MapEntry/create 'k 'val-or-nil) nil))))

            (map-assoc     [] (list 'assoc       ['this 'i 'value] (list `assoc as-map 'i 'value)))
            (map-assocEx   [] (list 'assocEx     ['this 'i 'value] (list `if (list (set members) 'i) (list `throw (list `Exception. "key already exists")) (assoc as-map 'i 'value))))
            (map-without   [] (list 'without     ['this 'k]        (list `dissoc as-map (list `if (list `number? 'k) (list (vec members) 'k) 'k))))
            (map-cons      [] (list 'cons        ['this 'o]        `(if (instance? clojure.lang.MapEntry ~'o) ~(conj as-map [`(.key ~(with-meta 'o {:tag 'clojure.lang.MapEntry})) `(.val ~(with-meta 'o {:tag 'clojure.lang.MapEntry}))]) (if (instance? clojure.lang.IPersistentVector ~'o) ~(conj as-map [`(.nth ~(with-meta 'o {:tag 'clojure.lang.IPersistentVector}) 0) `(.nth ~(with-meta 'o {:tag 'clojure.lang.IPersistentVector}) 1)]) (.cons ~(with-meta 'o {:tag 'clojure.lang.IPersistentMap}) ~as-map)))))
            (map-equiv     [] (list 'equiv       ['this 'o]        (list `= as-map 'o)))
            (map-empty     [] (list 'empty       ['this]           {}))
            (map-iterator  [] (list 'iterator    ['this]           (list '.iterator as-map)))
            (map-foreachConsumer   [] (concat [(with-meta 'forEach {:tag 'void})  ['this (with-meta 'action {:tag 'java.util.function.Consumer}) ]]  (partition 2 (interleave (repeat 'action) as-map))))
            (map-foreachBiConsumer   [] (concat [(with-meta 'forEach {:tag 'void})  ['this (with-meta 'action {:tag 'java.util.function.BiConsumer})]]  (partition 3 (flatten (interleave (repeat 'action) (seq as-map))))))
            (map-seq       [] (list 'seq         ['this]           (list `StructMapSeq. 'this 0)))
            (invoke1       [] (list 'invoke      ['this 'arg1]       (concat [`case 'arg1] (interleave (range) as-vec) (interleave members as-vec) [nil])))
            (invoke2       [] (list 'invoke      ['this 'arg1 'arg2] (concat [`case 'arg1] (interleave (range) as-vec) (interleave members as-vec) ['arg2])))
            (applyTo       [] (list 'applyTo      ['this 'arglist] (concat [`case (list `first 'arglist)] (interleave (range) as-vec) (interleave members as-vec) [(list `if (list `.next 'arglist) (list `.first (list `.next 'arglist)) nil)])))
            ;structimpl utility function
            (s-nth-key     [] (list 'nthKey     ['this 'i]        (concat [`case 'i] (interleave (range) members))))
            ;java.util.Map implementations
            (map-contains-value [] (list 'containsValue ['this 'val] (list `some (set as-vec) 'val)))
            (map-entrySet       [] (list 'entrySet ['this] (set (map (fn [[k v]] (list `clojure.lang.MapEntry/create k v)) (partition 2 (interleave members as-vec))))))
            (map-get            [] (cons 'get (rest (s-valAt))))
            (map-isEmpty        [] (list 'isEmpty ['this] false))
            (map-keySet         [] (list 'keySet ['this] (set members)))
            (map-size           [] (list 'size ['this] (count members)))
            (map-values         [] (list 'values ['this] as-vec))

            (map-methods   [] [(map-without) (map-cons) (map-equiv) (map-empty) (map-iterator) (map-foreachConsumer) #_(map-foreachBiConsumer) (map-seq) (map-assoc) (map-assocEx) (map-contains-value) (map-entrySet) (map-get) (map-isEmpty) (map-keySet) (map-size) (map-values)])
            (vec-methods   [] [(vec-length) (vec-assoc) (vec-assocN) (vec-peek) (vec-pop) (vec-nth) (vec-nth-2) (vec-cons) (vec-equiv) (vec-empty) (vec-iterator) (vec-foreach) (vec-seq) (vec-rseq)])
            (struct-methods [] [(s-count) (s-containsKey) (s-valAt) (s-valAt-2) (s-entryAt)])
            (prefix-methods [prefix ms] (map (fn [[method-name & tail]] (cons (with-meta (symbol (str prefix method-name)) (meta method-name)) tail)) ms))
            (impl-methods [] (concat (prefix-methods "map_" (map-methods)) (prefix-methods "vec_" (vec-methods)) (prefix-methods "struct_" (struct-methods))))]
      `(deftype ~(symbol (name typename)) ~(vec typed-member-symbols)
         coffi.mem.IStruct
         ~@(struct-methods)
         coffi.mem.IStructImpl
         ~@(impl-methods)
         clojure.lang.IPersistentMap
         clojure.lang.MapEquivalence
         java.util.Map
         ~@(map-methods)
         clojure.lang.IFn
         ~(s-nth-key)
         ~(invoke1)
         ~(invoke2)

         (~'asMap [~'this] ~'this)
         (~'asVec [~'this] (VecWrap. ~'this))))))

(defmacro defstruct
  "Defines a struct type. all members need to be supplied in pairs of `member-name coffi-type`.

  This creates needed serialization and deserialization implementations for the new type.

  The typenames have to be coffi typenames, such as `:coffi.mem/int` or `[:coffi.mem/array :coffi.mem/byte 3]`.
  Arrays are wrapped with vectors by default. If you want to use raw java arrays the array type has to be supplied with the option `:raw? true`, for example like this `[:coffi.mem/array :coffi.mem/byte 3 :raw? true]`
  "
  {:style/indent [:defn]}
  [typename members]
  (let [invalid-typenames (filter #(try (c-layout (second %)) nil (catch Exception e (second %))) (partition 2 members))]
    (cond
      (odd? (count members)) (throw (Exception. "uneven amount of members supplied. members have to be typed and are required to be supplied in the form of `member-name typename`. the typename has to be coffi typename, like `:coffi.mem/int` or `[:coffi.mem/array :coffi.mem/byte 3]`"))
      (seq invalid-typenames) (throw (Exception. (str "invalid typename/s " (print-str invalid-typenames) ". typename has to be coffi typename, like `:coffi.mem/int` or `[:coffi.mem/array :coffi.mem/byte 3]`. The type/s you referenced also might not be defined. In case of a custom type, ensure that you use the correctly namespaced keyword to refer to it.")))
      :else
      (let [coffi-typename (keyword (str *ns*) (str typename))
            typed-symbols (->>
                           members
                           (partition 2 2)
                           (map (fn [[sym type]] (with-meta sym {:tag (coffitype->typename type)})))
                           (vec))
            struct-layout-raw [::struct
                               (->>
                                members
                                (partition 2 2)
                                (map vec)
                                (map #(update % 0 keyword))
                                (map vec)
                                (vec))]
            struct-layout ((requiring-resolve 'coffi.layout/with-c-layout) struct-layout-raw)
            segment-form (with-meta 'segment {:tag 'java.lang.foreign.MemorySegment})]
        (if (resolve typename) (ns-unmap *ns* typename))
        (defmethod c-layout coffi-typename [_] (c-layout struct-layout))
        (register-new-struct-deserialization coffi-typename struct-layout)
        (register-new-struct-serialization   coffi-typename struct-layout)
        `(do
           ~(generate-struct-type typename typed-symbols)
           (defmethod c-layout ~coffi-typename [~'_] (c-layout ((requiring-resolve 'coffi.layout/with-c-layout) ~struct-layout-raw)))
           (register-new-struct-deserialization ~coffi-typename ((requiring-resolve 'coffi.layout/with-c-layout) ~struct-layout-raw))
           (register-new-struct-serialization   ~coffi-typename ((requiring-resolve 'coffi.layout/with-c-layout) ~struct-layout-raw))
           (defmethod deserialize-from ~coffi-typename ~[segment-form '_type]
             ~(generate-deserialize coffi-typename 0 segment-form))
           (defmethod serialize-into ~coffi-typename ~[(with-meta 'source-obj {:tag typename}) '_type segment-form '_]
             ~(generate-serialize coffi-typename (with-meta 'source-obj {:tag typename}) 0 segment-form))
           ;; pprint support is best-effort: requiring clojure.pprint drags it
           ;; into AOT compilation, where its top-level set! breaks GraalVM
           ;; native-image build-time initialization
           (when-some [simple-dispatch# (try @(requiring-resolve 'clojure.pprint/simple-dispatch)
                                             (catch Exception ~'_ nil))]
             (.addMethod ^clojure.lang.MultiFn simple-dispatch# ~typename
                         (fn [~'obj] (simple-dispatch# (into {} ~'obj)))))
           (defmethod clojure.core/print-method ~typename [~'obj ~'writer] (print-simple (into {} ~'obj) ~'writer)))))))


