(ns coffi.mem
  "Functions for managing native allocations, memory arenas, and (de)serialization.

  For any new type to be implemented, three multimethods must be overriden, but
  which three depends on the native representation of the type.

  If the native representation of the type is a primitive (whether or not other
  data beyond the primitive is associated with it, as e.g. a pointer),
  then [[primitive-type]] must be overriden to return which primitive type it is
  serialized as, then [[serialize*]] and [[deserialize*]] should be overriden.

  If the native representation of the type is a composite type, like a union,
  struct, or array, then [[c-layout]] must be overriden to return the native
  layout of the type, and [[serialize-into]] and [[deserialize-from]] should be
  overriden to allow marshaling values of the type into and out of memory
  segments."
  (:require
   [clojure.set :as set]
   [clojure.spec.alpha :as s])
  (:import
   (java.lang.foreign
    AddressLayout
    Arena
    MemoryLayout
    MemoryLayout$PathElement
    MemorySegment
    MemorySegment$Scope
    SegmentAllocator
    ValueLayout
    ValueLayout$OfByte
    ValueLayout$OfShort
    ValueLayout$OfInt
    ValueLayout$OfLong
    ValueLayout$OfChar
    ValueLayout$OfFloat
    ValueLayout$OfDouble)
   (java.lang.ref Cleaner)
   (java.util.function Consumer)
   (java.nio ByteOrder))
  (:refer-clojure :exclude [defstruct]))

;; set! requires a thread binding, which is absent when this namespace is
;; initialized as an AOT-compiled class (e.g. GraalVM native-image build time)
(when (thread-bound? #'*warn-on-reflection*)
  (set! *warn-on-reflection* true))

(defn confined-arena
  "Constructs a new arena for use only in this thread.

  The memory allocated within this arena is cheap to allocate, like a native
  stack.

  The memory allocated within this arena will be cleared once it is closed, so
  it is usually a good idea to create it in a [[with-open]] clause."
  (^Arena []
   (Arena/ofConfined)))

(defn shared-arena
  "Constructs a new shared memory arena.

  This arena can be shared across threads and memory allocated in it will only
  be cleaned up once any thread accessing the arena closes it."
  (^Arena []
   (Arena/ofShared)))

(defn auto-arena
  "Constructs a new memory arena that is managed by the garbage collector.

  The arena may be shared across threads, and all resources created with it will
  be cleaned up at the same time, when all references have been collected.

  This type of arena cannot be closed, and therefore should not be created in
  a [[with-open]] clause."
  ^Arena []
  (Arena/ofAuto))

(defn global-arena
  "Constructs the global arena, which will never reclaim its resources.

  This arena may be shared across threads, but is intended mainly in cases where
  memory is allocated with [[alloc]] but is either never freed or whose
  management is relinquished to a native library, such as when returned from a
  callback."
  ^Arena []
  (Arena/global))

(defn arena-allocator
  "Constructs a [[SegmentAllocator]] from the given [[Arena]].

  This is primarily used when working with unwrapped downcall functions. When a
  downcall function returns a non-primitive type, it must be provided with an
  allocator."
  ^SegmentAllocator [^Arena arena]
  (reify SegmentAllocator
    (^MemorySegment allocate [_this ^long byte-size ^long byte-alignment]
      (.allocate arena ^long byte-size ^long byte-alignment))))

(defn alloc
  "Allocates `size` bytes.

  If an `arena` is provided, the allocation will be reclaimed when it is closed."
  (^MemorySegment [size] (alloc size (auto-arena)))
  (^MemorySegment [size arena] (.allocate ^Arena arena (long size)))
  (^MemorySegment [size alignment arena] (.allocate ^Arena arena (long size) (long alignment))))

(defn alloc-with
  "Allocates `size` bytes using the `allocator`."
  (^MemorySegment [allocator size]
   (.allocate ^SegmentAllocator allocator (long size)))
  (^MemorySegment [allocator size alignment]
   (.allocate ^SegmentAllocator allocator (long size) (long alignment))))

(defn address-of
  "Gets the address of a given segment as a number."
  ^long [addressable]
  (.address ^MemorySegment addressable))

(def ^MemorySegment null
  "The NULL pointer object.

  While this object is safe to pass to functions which serialize to a pointer,
  it's generally encouraged to simply pass `nil`. This value primarily exists to
  make it easier to write custom types with a primitive pointer representation."
  MemorySegment/NULL)

(defn null?
  "Checks if a memory address is null."
  [addr]
  (or (.equals null addr) (not addr)))

(defn address?
  "Checks if an object is a memory address.

  `nil` is considered an address."
  [addr]
  (or (nil? addr) (instance? MemorySegment addr)))

(defn slice
  "Get a slice over the `segment` with the given `offset`."
  (^MemorySegment [segment offset]
   (.asSlice ^MemorySegment segment (long offset)))
  (^MemorySegment [segment offset size]
   (.asSlice ^MemorySegment segment (long offset) (long size))))

(defn reinterpret
  "Reinterprets the `segment` as having the passed `size`.

  If `arena` is passed, the scope of the `segment` is associated with the arena,
  as well as its access constraints. If `cleanup` is passed, it will be a
  1-argument function of a fresh memory segment backed by the same memory as the
  returned segment which should perform any required cleanup operations. It will
  be called when the `arena` is closed."
  (^MemorySegment [^MemorySegment segment size]
   (.reinterpret segment (long size) (auto-arena) nil))
  (^MemorySegment [^MemorySegment segment size ^Arena arena]
   (.reinterpret segment (long size) arena nil))
  (^MemorySegment [^MemorySegment segment size ^Arena arena cleanup]
   (.reinterpret segment (long size) arena
                 (reify Consumer
                   (accept [_this segment]
                     (cleanup segment))))))

(defn as-segment
  "Dereferences an `address` into a memory segment associated with the `arena` (default global)."
  (^MemorySegment [^long address]
   (MemorySegment/ofAddress address))
  (^MemorySegment [^long address size]
   (reinterpret (MemorySegment/ofAddress address) size))
  (^MemorySegment [^long address size ^Arena arena]
   (reinterpret (MemorySegment/ofAddress address) (long size) arena nil))
  (^MemorySegment [^long address size ^Arena arena cleanup]
   (reinterpret (MemorySegment/ofAddress address) (long size) arena cleanup)))

(defn copy-segment
  "Copies the content to `dest` from `src`.

  Returns `dest`."
  ^MemorySegment [^MemorySegment dest ^MemorySegment src]
  (.copyFrom dest src))

(defn clone-segment
  "Clones the content of `segment` into a new segment of the same size."
  (^MemorySegment [segment] (clone-segment segment (auto-arena)))
  (^MemorySegment [^MemorySegment segment ^Arena arena]
   (copy-segment ^MemorySegment (alloc (.byteSize segment) arena) segment)))

(defn slice-segments
  "Constructs a lazy seq of `size`-length memory segments, sliced from `segment`."
  [^MemorySegment segment size]
  (let [num-segments (quot (.byteSize segment) size)]
    (map #(slice segment (* % size) size)
         (range num-segments))))

(def ^ByteOrder big-endian
  "The big-endian [[ByteOrder]].

  See [[little-endian]], [[native-endian]]."
  ByteOrder/BIG_ENDIAN)

(def ^ByteOrder little-endian
  "The little-endian [[ByteOrder]].

  See [[big-endian]], [[native-endian]]"
  ByteOrder/LITTLE_ENDIAN)

(def ^ByteOrder native-endian
  "The [[ByteOrder]] for the native endianness of the current hardware.

  See [[big-endian]], [[little-endian]]."
  (ByteOrder/nativeOrder))

(def ^ValueLayout$OfByte byte-layout
  "The [[MemoryLayout]] for a byte in [[native-endian]] [[ByteOrder]]."
  ValueLayout/JAVA_BYTE)

(def ^ValueLayout$OfShort short-layout
  "The [[MemoryLayout]] for a c-sized short in [[native-endian]] [[ByteOrder]]."
  ValueLayout/JAVA_SHORT)

(def ^ValueLayout$OfInt int-layout
  "The [[MemoryLayout]] for a c-sized int in [[native-endian]] [[ByteOrder]]."
  ValueLayout/JAVA_INT)

(def ^ValueLayout$OfLong long-layout
  "The [[MemoryLayout]] for a c-sized long in [[native-endian]] [[ByteOrder]]."
  ValueLayout/JAVA_LONG)

(def ^ValueLayout$OfByte char-layout
  "The [[MemoryLayout]] for a c-sized char in [[native-endian]] [[ByteOrder]]."
  ValueLayout/JAVA_BYTE)

(def ^ValueLayout$OfFloat float-layout
  "The [[MemoryLayout]] for a c-sized float in [[native-endian]] [[ByteOrder]]."
  ValueLayout/JAVA_FLOAT)

(def ^ValueLayout$OfDouble double-layout
  "The [[MemoryLayout]] for a c-sized double in [[native-endian]] [[ByteOrder]]."
  ValueLayout/JAVA_DOUBLE)

(def ^AddressLayout pointer-layout
  "The [[MemoryLayout]] for a native pointer in [[native-endian]] [[ByteOrder]]."
  ValueLayout/ADDRESS)

(def short-size
  "The size in bytes of a c-sized short."
  (.byteSize short-layout))

(def int-size
  "The size in bytes of a c-sized int."
  (.byteSize int-layout))

(def long-size
  "The size in bytes of a c-sized long."
  (.byteSize long-layout))

(def float-size
  "The size in bytes of a c-sized float."
  (.byteSize float-layout))

(def double-size
  "The size in bytes of a c-sized double."
  (.byteSize double-layout))

(def pointer-size
  "The size in bytes of a c-sized pointer."
  (.byteSize pointer-layout))

(def short-alignment
  "The alignment in bytes of a c-sized short."
  (.byteAlignment short-layout))

(def int-alignment
  "The alignment in bytes of a c-sized int."
  (.byteAlignment int-layout))

(def long-alignment
  "The alignment in bytes of a c-sized long."
  (.byteAlignment long-layout))

(def float-alignment
  "The alignment in bytes of a c-sized float."
  (.byteAlignment float-layout))

(def double-alignment
  "The alignment in bytes of a c-sized double."
  (.byteAlignment double-layout))

(def pointer-alignment
  "The alignment in bytes of a c-sized pointer."
  (.byteAlignment pointer-layout))

(def ^:private primitive-tag?
  '#{byte bytes short shorts int ints long longs
     float floats double doubles
     bool bools char chars})

(defmacro once-only
  {:style/indent [:defn]
   :private true}
  [[& names] & body]
  (let [gensyms (repeatedly (count names) gensym)]
    `(let [~@(interleave gensyms (repeat (count names) `(gensym)))]
       `(let [~~@(mapcat #(-> (if (primitive-tag? (:tag (meta %2)))
                                [%1 ``(~'~(:tag (meta %2)) ~~%2)]
                                [`(with-meta ~%1 {:tag '~(:tag (meta %2))}) %2]))
                         gensyms names)]
          ~(let [~@(mapcat #(-> [(with-meta %1 {}) %2]) names gensyms)]
             ~@body)))))

(defn read-byte
  "Reads a [[byte]] from the `segment`, at an optional `offset`."
  {:inline
   (fn read-byte-inline
     ([segment]
      `(let [segment# ~segment]
         (.get ^MemorySegment segment# ^ValueLayout$OfByte byte-layout 0)))
     ([segment offset]
      `(let [segment# ~segment
             offset# ~offset]
         (.get ^MemorySegment segment# ^ValueLayout$OfByte byte-layout offset#))))}
  ([^MemorySegment segment]
   (.get segment ^ValueLayout$OfByte byte-layout 0))
  ([^MemorySegment segment ^long offset]
   (.get segment ^ValueLayout$OfByte byte-layout offset)))

(defn read-short
  "Reads a [[short]] from the `segment`, at an optional `offset`.

  If `byte-order` is not provided, it defaults to [[native-endian]]."
  {:inline
   (fn read-short-inline
     ([segment]
      `(let [segment# ~segment]
         (.get ^MemorySegment segment# ^ValueLayout$OfShort short-layout 0)))
     ([segment offset]
      `(let [segment# ~segment
             offset# ~offset]
         (.get ^MemorySegment segment# ^ValueLayout$OfShort short-layout offset#)))
     ([segment offset byte-order]
      `(let [segment# ~segment
             offset# ~offset
             byte-order# ~byte-order]
         (.get ^MemorySegment segment# (.withOrder ^ValueLayout$OfShort short-layout ^ByteOrder byte-order#) offset#))))}
  ([^MemorySegment segment]
   (.get segment ^ValueLayout$OfShort short-layout 0))
  ([^MemorySegment segment ^long offset]
   (.get segment ^ValueLayout$OfShort short-layout offset))
  ([^MemorySegment segment ^long offset ^ByteOrder byte-order]
   (.get segment (.withOrder ^ValueLayout$OfShort short-layout byte-order) offset)))

(defn read-int
  "Reads a [[int]] from the `segment`, at an optional `offset`.

  If `byte-order` is not provided, it defaults to [[native-endian]]."
  {:inline
   (fn read-int-inline
     ([segment]
      `(let [segment# ~segment]
         (.get ^MemorySegment segment# ^ValueLayout$OfInt int-layout 0)))
     ([segment offset]
      `(let [segment# ~segment
             offset# ~offset]
         (.get ^MemorySegment segment# ^ValueLayout$OfInt int-layout offset#)))
     ([segment offset byte-order]
      `(let [segment# ~segment
             offset# ~offset
             byte-order# ~byte-order]
         (.get ^MemorySegment segment# (.withOrder ^ValueLayout$OfInt int-layout ^ByteOrder byte-order#) offset#))))}
  ([^MemorySegment segment]
   (.get segment ^ValueLayout$OfInt int-layout 0))
  ([^MemorySegment segment ^long offset]
   (.get segment ^ValueLayout$OfInt int-layout offset))
  ([^MemorySegment segment ^long offset ^ByteOrder byte-order]
   (.get segment (.withOrder ^ValueLayout$OfInt int-layout byte-order) offset)))

(defn read-long
  "Reads a [[long]] from the `segment`, at an optional `offset`.

  If `byte-order` is not provided, it defaults to [[native-endian]]."
  {:inline
   (fn read-long-inline
     ([segment]
      `(let [segment# ~segment]
         (.get ^MemorySegment segment# ^ValueLayout$OfLong long-layout 0)))
     ([segment offset]
      `(let [segment# ~segment
             offset# ~offset]
         (.get ^MemorySegment segment# ^ValueLayout$OfLong long-layout offset#)))
     ([segment offset byte-order]
      `(let [segment# ~segment
             offset# ~offset
             byte-order# ~byte-order]
         (.get ^MemorySegment segment# (.withOrder ^ValueLayout$OfLong long-layout ^ByteOrder byte-order#) offset#))))}
  (^long [^MemorySegment segment]
   (.get segment ^ValueLayout$OfLong long-layout 0))
  (^long [^MemorySegment segment ^long offset]
   (.get segment ^ValueLayout$OfLong long-layout offset))
  (^long [^MemorySegment segment ^long offset ^ByteOrder byte-order]
   (.get segment (.withOrder ^ValueLayout$OfLong long-layout byte-order) offset)))

(defn read-char
  "Reads a [[char]] from the `segment`, at an optional `offset`."
  {:inline
   (fn read-char-inline
     ([segment]
      `(let [segment# ~segment]
         (char (Byte/toUnsignedInt (.get ^MemorySegment segment# ^ValueLayout$OfByte byte-layout 0)))))
     ([segment offset]
      `(let [segment# ~segment
             offset# ~offset]
         (char (Byte/toUnsignedInt (.get ^MemorySegment segment# ^ValueLayout$OfByte byte-layout offset#))))))}
  ([^MemorySegment segment]
   (char (Byte/toUnsignedInt (.get segment ^ValueLayout$OfChar byte-layout 0))))
  ([^MemorySegment segment ^long offset]
   (char (Byte/toUnsignedInt (.get segment ^ValueLayout$OfChar byte-layout offset)))))

(defn read-float
  "Reads a [[float]] from the `segment`, at an optional `offset`.

  If `byte-order` is not provided, it defaults to [[native-endian]]."
  {:inline
   (fn read-float-inline
     ([segment]
      `(let [segment# ~segment]
         (.get ^MemorySegment segment# ^ValueLayout$OfFloat float-layout 0)))
     ([segment offset]
      `(let [segment# ~segment
             offset# ~offset]
         (.get ^MemorySegment segment# ^ValueLayout$OfFloat float-layout offset#)))
     ([segment offset byte-order]
      `(let [segment# ~segment
             offset# ~offset
             byte-order# ~byte-order]
         (.get ^MemorySegment segment# (.withOrder ^ValueLayout$OfFloat float-layout ^ByteOrder byte-order#) offset#))))}
  ([^MemorySegment segment]
   (.get segment ^ValueLayout$OfFloat float-layout 0))
  ([^MemorySegment segment ^long offset]
   (.get segment ^ValueLayout$OfFloat float-layout offset))
  ([^MemorySegment segment ^long offset ^ByteOrder byte-order]
   (.get segment (.withOrder ^ValueLayout$OfFloat float-layout byte-order) offset)))

(defn read-double
  "Reads a [[double]] from the `segment`, at an optional `offset`.

  If `byte-order` is not provided, it defaults to [[native-endian]]."
  {:inline
   (fn read-double-inline
     ([segment]
      `(let [segment# ~segment]
         (.get ^MemorySegment segment# ^ValueLayout$OfDouble double-layout 0)))
     ([segment offset]
      `(let [segment# ~segment
             offset# ~offset]
         (.get ^MemorySegment segment# ^ValueLayout$OfDouble double-layout offset#)))
     ([segment offset byte-order]
      `(let [segment# ~segment
             offset# ~offset
             byte-order# ~byte-order]
         (.get ^MemorySegment segment# (.withOrder ^ValueLayout$OfDouble double-layout ^ByteOrder byte-order#) offset#))))}
  (^double [^MemorySegment segment]
   (.get segment ^ValueLayout$OfDouble double-layout 0))
  (^double [^MemorySegment segment ^long offset]
   (.get segment ^ValueLayout$OfDouble double-layout offset))
  (^double [^MemorySegment segment ^long offset ^ByteOrder byte-order]
   (.get segment (.withOrder ^ValueLayout$OfDouble double-layout byte-order) offset)))

(defn read-address
  "Reads an address from the `segment`, at an optional `offset`, wrapped in a [[MemorySegment]]."
  {:inline
   (fn read-address-inline
     ([segment]
      `(let [segment# ~segment]
         (.get ^MemorySegment segment# ^AddressLayout pointer-layout 0)))
     ([segment offset]
      `(let [segment# ~segment
             offset# ~offset]
         (.get ^MemorySegment segment# ^AddressLayout pointer-layout offset#))))}
  (^MemorySegment [^MemorySegment segment]
   (.get segment ^AddressLayout pointer-layout 0))
  (^MemorySegment [^MemorySegment segment ^long offset]
   (.get segment ^AddressLayout pointer-layout offset)))

(defn write-byte
  "Writes a [[byte]] to the `segment`, at an optional `offset`."
  {:inline
   (fn write-byte-inline
     ([segment value]
      (once-only [^java.lang.foreign.MemorySegment segment ^byte value]
        `(.set ~segment ^ValueLayout$OfByte byte-layout 0 ~value)))
     ([segment offset value]
      (once-only [^java.lang.foreign.MemorySegment segment ^long offset ^byte value]
        `(.set ~segment ^ValueLayout$OfByte byte-layout ~offset ~value))))}
  ([^MemorySegment segment value]
   (.set segment ^ValueLayout$OfByte byte-layout 0 ^byte value))
  ([^MemorySegment segment ^long offset value]
   (.set segment ^ValueLayout$OfByte byte-layout offset ^byte value)))

(defn write-short
  "Writes a [[short]] to the `segment`, at an optional `offset`.

  If `byte-order` is not provided, it defaults to [[native-endian]]."
  {:inline
   (fn write-short-inline
     ([segment value]
      (once-only [^java.lang.foreign.MemorySegment segment ^short value]
        `(.set ~segment ^ValueLayout$OfShort short-layout 0 ~value)))
     ([segment offset value]
      (once-only [^java.lang.foreign.MemorySegment segment ^long offset ^short value]
        `(.set ~segment ^ValueLayout$OfShort short-layout ~offset ~value)))
     ([segment offset byte-order value]
      (once-only [^java.lang.foreign.MemorySegment segment ^long offset
                  ^java.nio.ByteOrder byte-order ^short value]
        `(.set ~segment (.withOrder ^ValueLayout$OfShort short-layout ~byte-order) ~offset ~value))))}
  ([^MemorySegment segment value]
   (.set segment ^ValueLayout$OfShort short-layout 0 ^short value))
  ([^MemorySegment segment ^long offset value]
   (.set segment ^ValueLayout$OfShort short-layout offset ^short value))
  ([^MemorySegment segment ^long offset ^ByteOrder byte-order value]
   (.set segment (.withOrder ^ValueLayout$OfShort short-layout byte-order) offset ^short value)))

(defn write-int
  "Writes a [[int]] to the `segment`, at an optional `offset`.

  If `byte-order` is not provided, it defaults to [[native-endian]]."
  {:inline
   (fn write-int-inline
     ([segment value]
      (once-only [^java.lang.foreign.MemorySegment segment ^int value]
        `(.set ~segment ^ValueLayout$OfInt int-layout 0 ~value)))
     ([segment offset value]
      (once-only [^java.lang.foreign.MemorySegment segment ^long offset ^int value]
        `(.set ~segment ^ValueLayout$OfInt int-layout ~offset ~value)))
     ([segment offset byte-order value]
      (once-only [^java.lang.foreign.MemorySegment segment ^long offset
                  ^java.nio.ByteOrder byte-order ^int value]
        `(.set ~segment (.withOrder ^ValueLayout$OfInt int-layout ~byte-order) ~offset ~value))))}
  ([^MemorySegment segment value]
   (.set segment ^ValueLayout$OfInt int-layout 0 ^int value))
  ([^MemorySegment segment ^long offset value]
   (.set segment ^ValueLayout$OfInt int-layout offset ^int value))
  ([^MemorySegment segment ^long offset ^ByteOrder byte-order value]
   (.set segment (.withOrder ^ValueLayout$OfInt int-layout byte-order) offset ^int value)))

(defn write-long
  "Writes a [[long]] to the `segment`, at an optional `offset`.

  If `byte-order` is not provided, it defaults to [[native-endian]]."
  {:inline
   (fn write-long-inline
     ([segment value]
      (once-only [^java.lang.foreign.MemorySegment segment ^long value]
        `(.set ~segment ^ValueLayout$OfLong long-layout 0 ~value)))
     ([segment offset value]
      (once-only [^java.lang.foreign.MemorySegment segment ^long offset ^long value]
        `(.set ~segment ^ValueLayout$OfLong long-layout ~offset ~value)))
     ([segment offset byte-order value]
      (once-only [^java.lang.foreign.MemorySegment segment ^long offset
                  ^java.nio.ByteOrder byte-order ^long value]
        `(.set ~segment (.withOrder ^ValueLayout$OfLong long-layout ~byte-order) ~offset ~value))))}
  (^long [^MemorySegment segment ^long value]
   (.set segment ^ValueLayout$OfLong long-layout 0 value))
  (^long [^MemorySegment segment ^long offset ^long value]
   (.set segment ^ValueLayout$OfLong long-layout offset value))
  (^long [^MemorySegment segment ^long offset ^ByteOrder byte-order ^long value]
   (.set segment (.withOrder ^ValueLayout$OfLong long-layout byte-order) offset value)))

(defn write-char
  "Writes a [[char]] to the `segment`, at an optional `offset`."
  {:inline
   (fn write-char-inline
     ([segment value]
      (once-only [^java.lang.foreign.MemorySegment segment ^char value]
        `(.set ~segment ^ValueLayout$OfByte byte-layout 0 (unchecked-byte (unchecked-int ~value)))))
     ([segment offset value]
      (once-only [^java.lang.foreign.MemorySegment segment ^long offset ^char value]
        `(.set ~segment ^ValueLayout$OfByte byte-layout ~offset (unchecked-byte (unchecked-int ~value))))))}
  ([^MemorySegment segment value]
   (.set
    segment
    ;; HACK(Joshua): The Clojure runtime doesn't have an unchecked-byte cast for
    ;;               characters, so this double cast is necessary unless I emit
    ;;               my own bytecode with insn.
    ^ValueLayout$OfByte byte-layout 0
    (unchecked-byte (unchecked-int ^char value))))
  ([^MemorySegment segment ^long offset value]
   (.set segment ^ValueLayout$OfByte byte-layout offset (unchecked-byte (unchecked-int ^char value)))))

(defn write-float
  "Writes a [[float]] to the `segment`, at an optional `offset`.

  If `byte-order` is not provided, it defaults to [[native-endian]]."
  {:inline
   (fn write-float-inline
     ([segment value]
      (once-only [^java.lang.foreign.MemorySegment segment ^float value]
        `(.set ~segment ^ValueLayout$OfFloat float-layout 0 ~value)))
     ([segment offset value]
      (once-only [^java.lang.foreign.MemorySegment segment ^long offset ^float value]
        `(.set ~segment ^ValueLayout$OfFloat float-layout ~offset ~value)))
     ([segment offset byte-order value]
      (once-only [^java.lang.foreign.MemorySegment segment ^long offset
                  ^java.nio.ByteOrder byte-order ^float value]
        `(.set ~segment (.withOrder ^ValueLayout$OfFloat float-layout ~byte-order) ~offset ~value))))}
  ([^MemorySegment segment value]
   (.set segment ^ValueLayout$OfFloat float-layout 0 ^float value))
  ([^MemorySegment segment ^long offset value]
   (.set segment ^ValueLayout$OfFloat float-layout offset ^float value))
  ([^MemorySegment segment ^long offset ^ByteOrder byte-order value]
   (.set segment (.withOrder ^ValueLayout$OfFloat float-layout byte-order) offset ^float value)))

(defn write-double
  "Writes a [[double]] to the `segment`, at an optional `offset`.

  If `byte-order` is not provided, it defaults to [[native-endian]]."
  {:inline
   (fn write-double-inline
     ([segment value]
      (once-only [^java.lang.foreign.MemorySegment segment ^double value]
        `(.set ~segment ^ValueLayout$OfDouble double-layout 0 ~value)))
     ([segment offset value]
      (once-only [^java.lang.foreign.MemorySegment segment ^long offset ^double value]
        `(.set ~segment ^ValueLayout$OfDouble double-layout ~offset ~value)))
     ([segment offset byte-order value]
      (once-only [^java.lang.foreign.MemorySegment segment ^long offset
                  ^java.nio.ByteOrder byte-order ^double value]
        `(.set ~segment (.withOrder ^ValueLayout$OfDouble double-layout ~byte-order) ~offset ~value))))}
  (^double [^MemorySegment segment ^double value]
   (.set segment ^ValueLayout$OfDouble double-layout 0 value))
  (^double [^MemorySegment segment ^long offset ^double value]
   (.set segment ^ValueLayout$OfDouble double-layout offset value))
  (^double [^MemorySegment segment ^long offset ^ByteOrder byte-order ^double value]
   (.set segment (.withOrder ^ValueLayout$OfDouble double-layout byte-order) offset value)))

(defn write-address
  "Writes the address of the [[MemorySegment]] `value` to the `segment`, at an optional `offset`."
  {:inline
   (fn write-address-inline
     ([segment value]
      (once-only [^java.lang.foreign.MemorySegment segment
                  ^java.lang.foreign.MemorySegment value]
        `(.set ~segment ^AddressLayout pointer-layout 0 ~value)))
     ([segment offset value]
      (once-only [^java.lang.foreign.MemorySegment segment ^long offset
                  ^java.lang.foreign.MemorySegment value]
        `(.set ~segment ^AddressLayout pointer-layout ~offset ~value))))}
  ([^MemorySegment segment ^MemorySegment value]
   (.set segment ^AddressLayout pointer-layout 0 value))
  ([^MemorySegment segment ^long offset ^MemorySegment value]
   (.set segment ^AddressLayout pointer-layout offset value)))

(defn write-bytes
  "Writes n elements from a [[byte]] array to the `segment`, at an optional `offset`."
  {:inline
   (fn write-bytes-inline
     ([segment n value]
      (once-only [^java.lang.foreign.MemorySegment segment ^bytes value]
        `(MemorySegment/copy ~value 0 ~segment ^ValueLayout$OfByte byte-layout 0 ~n)))
     ([segment n offset value]
      (once-only [^java.lang.foreign.MemorySegment segment ^long offset ^bytes value]
        `(MemorySegment/copy ~value 0 ~segment ^ValueLayout$OfByte byte-layout ~offset ~n))))}
  ([^MemorySegment segment n ^bytes value]
   (MemorySegment/copy value 0 segment ^ValueLayout$OfByte byte-layout 0 ^int n))
  ([^MemorySegment segment n offset ^bytes value]
   (MemorySegment/copy value 0 segment ^ValueLayout$OfByte byte-layout ^long offset ^int n)))

(defn write-shorts
  "Writes n elements from a [[short]] array to the `segment`, at an optional `offset`.

  If `byte-order` is not provided, it defaults to [[native-endian]]."
  {:inline
   (fn write-shorts-inline
     ([segment n value]
      (once-only [^java.lang.foreign.MemorySegment segment ^shorts value]
        `(MemorySegment/copy ~value 0 ~segment ^ValueLayout$OfShort short-layout 0 ~n)))
     ([segment n offset value]
      (once-only [^java.lang.foreign.MemorySegment segment ^long offset ^shorts value]
        `(MemorySegment/copy ~value 0 ~segment ^ValueLayout$OfShort short-layout ~offset ~n))))}
  ([^MemorySegment segment n ^shorts value]
   (MemorySegment/copy value 0 segment ^ValueLayout$OfShort short-layout 0 ^int n))
  ([^MemorySegment segment n ^long offset ^shorts value]
   (MemorySegment/copy value 0 segment ^ValueLayout$OfShort short-layout ^long offset ^int n)))

(defn write-ints
  "Writes n elements from an [[int]] array to the `segment`, at an optional `offset`.

  If `byte-order` is not provided, it defaults to [[native-endian]]."
  {:inline
   (fn write-ints-inline
     ([segment n value]
      (once-only [^java.lang.foreign.MemorySegment segment ^ints value]
        `(MemorySegment/copy ~value 0 ~segment ^ValueLayout$OfInt int-layout 0 ~n)))
     ([segment n offset value]
      (once-only [^java.lang.foreign.MemorySegment segment ^long offset ^ints value]
        `(MemorySegment/copy ~value 0 ~segment ^ValueLayout$OfInt int-layout ~offset ~n))))}
  ([^MemorySegment segment n ^ints value]
   (MemorySegment/copy value 0 segment ^ValueLayout$OfInt int-layout 0 ^int n))
  ([^MemorySegment segment n ^long offset ^ints value]
   (MemorySegment/copy value 0 segment ^ValueLayout$OfInt int-layout ^long offset ^int n)))

(defn write-longs
  "Writes n elements from a [[long]] array to the `segment`, at an optional `offset`.

  If `byte-order` is not provided, it defaults to [[native-endian]]."
  {:inline
   (fn write-longs-inline
     ([segment n value]
      (once-only [^java.lang.foreign.MemorySegment segment ^longs value]
        `(MemorySegment/copy ~value 0 ~segment ^ValueLayout$OfLong long-layout 0 ~n)))
     ([segment n offset value]
      (once-only [^java.lang.foreign.MemorySegment segment ^long offset ^longs value]
        `(MemorySegment/copy ~value 0 ~segment ^ValueLayout$OfLong long-layout ~offset ~n))))}
  ([^MemorySegment segment n ^longs value]
   (MemorySegment/copy value 0 segment ^ValueLayout$OfLong long-layout 0 ^int n))
  ([^MemorySegment segment n ^long offset ^longs value]
   (MemorySegment/copy value 0 segment ^ValueLayout$OfLong long-layout ^long offset ^int n)))

(defn write-chars
  "Writes n elements from a [[char]] array to the `segment`, at an optional `offset`.

  If `byte-order` is not provided, it defaults to [[native-endian]]."
  {:inline
   (fn write-chars-inline
     ([segment n value]
      (once-only [^java.lang.foreign.MemorySegment segment ^chars value]
        `(MemorySegment/copy (bytes (byte-array (map unchecked-int ~value))) 0 ~segment ^ValueLayout$OfChar char-layout 0 ~n)))
     ([segment n offset value]
      (once-only [^java.lang.foreign.MemorySegment segment ^long offset ^chars value]
        `(MemorySegment/copy (bytes (byte-array (map unchecked-int ~value))) 0 ~segment ^ValueLayout$OfChar char-layout ~offset ~n))))}
  ([^MemorySegment segment n ^chars value]
   (MemorySegment/copy (bytes (byte-array (map unchecked-int value))) 0 segment ^ValueLayout$OfChar char-layout 0 ^int n))
  ([^MemorySegment segment n ^long offset ^chars value]
   (MemorySegment/copy (bytes (byte-array (map unchecked-int value))) 0 segment ^ValueLayout$OfChar char-layout ^long offset ^int n )))

(defn write-floats
  "Writes n elements from a [[float]] array to the `segment`, at an optional `offset`.

  If `byte-order` is not provided, it defaults to [[native-endian]]."
  {:inline
   (fn write-floats-inline
     ([segment n value]
      (once-only [^java.lang.foreign.MemorySegment segment ^floats value]
        `(MemorySegment/copy ~value 0 ~segment ^ValueLayout$OfFloat float-layout 0 ~n)))
     ([segment n offset value]
      (once-only [^java.lang.foreign.MemorySegment segment ^long offset ^floats value]
        `(MemorySegment/copy ~value 0 ~segment ^ValueLayout$OfFloat float-layout ~offset ~n))))}
  ([^MemorySegment segment n ^floats value]
   (MemorySegment/copy value 0 segment ^ValueLayout$OfFloat float-layout 0 ^int n))
  ([^MemorySegment segment n ^long offset ^floats value]
   (MemorySegment/copy value 0 segment ^ValueLayout$OfFloat float-layout ^long offset ^int n)))

(defn write-doubles
  "Writes n elements from a [[double]] array to the `segment`, at an optional `offset`.

  If `byte-order` is not provided, it defaults to [[native-endian]]."
  {:inline
   (fn write-doubles-inline
     ([segment n value]
      (once-only [^java.lang.foreign.MemorySegment segment ^doubles value]
        `(MemorySegment/copy ~value 0 ~segment ^ValueLayout$OfDouble double-layout 0 ~n)))
     ([segment n offset value]
      (once-only [^java.lang.foreign.MemorySegment segment ^long offset ^doubles value]
        `(MemorySegment/copy ~value 0 ~segment ^ValueLayout$OfDouble double-layout ~offset ~n))))}
  ([^MemorySegment segment n ^doubles value]
   (MemorySegment/copy value 0 segment ^ValueLayout$OfDouble double-layout 0 ^int n))
  ([^MemorySegment segment n ^long offset ^doubles value]
   (MemorySegment/copy value 0 segment ^ValueLayout$OfDouble double-layout ^long offset ^int n)))


(defn read-bytes
  "reads `n` elements from a `segment` to a [[byte]] array, at an optional `offset`."
  {:inline
   (fn read-bytes-inline
     ([segment n]
      `(let [n# ~n
             segment# ~segment
             arr# (byte-array ~n)]
         (MemorySegment/copy ^MemorySegment segment# ^ValueLayout$OfByte byte-layout 0 arr# 0 n#)
         arr#))
     ([segment n offset]
      `(let [n# ~n
             segment# ~segment
             offset# ~offset
             arr# (byte-array ~n)]
         (MemorySegment/copy ^MemorySegment segment# ^ValueLayout$OfByte byte-layout offset# arr# 0 n#)
         arr#))
     ([segment n offset byte-order]
      `(let [n# ~n
             segment# ~segment
             offset# ~offset
             byte-order# ~byte-order
             arr# (byte-array ~n)]
         (MemorySegment/copy ^MemorySegment segment# (.withOrder ^ValueLayout$OfByte byte-layout ^ByteOrder byte-order#) offset# arr# 0 n#)
         arr#)))}
  ([^MemorySegment segment n]
   (let [arr (byte-array n)]
     (MemorySegment/copy segment ^ValueLayout$OfByte byte-layout 0 arr 0 ^int n)
     arr))
  ([^MemorySegment segment n ^long offset]
   (let [arr (byte-array n)]
     (MemorySegment/copy segment ^ValueLayout$OfByte byte-layout offset arr 0 ^int n)
     arr))
  ([^MemorySegment segment n ^long offset ^ByteOrder byte-order]
   (let [arr (byte-array n)]
     (MemorySegment/copy segment (.withOrder ^ValueLayout$OfByte byte-layout byte-order) offset arr 0 ^int n)
     arr)))

(defn read-shorts
  "reads `n` elements from a `segment` to a [[short]] array, at an optional `offset`."
  {:inline
   (fn read-shorts-inline
     ([segment n]
      `(let [n# ~n
             segment# ~segment
             arr# (short-array ~n)]
         (MemorySegment/copy ^MemorySegment segment# ^ValueLayout$OfShort short-layout 0 arr# 0 n#)
         arr#))
     ([segment n offset]
      `(let [n# ~n
             segment# ~segment
             offset# ~offset
             arr# (short-array ~n)]
         (MemorySegment/copy ^MemorySegment segment# ^ValueLayout$OfShort short-layout offset# arr# 0 n#)
         arr#))
     ([segment n offset byte-order]
      `(let [n# ~n
             segment# ~segment
             offset# ~offset
             byte-order# ~byte-order
             arr# (short-array ~n)]
         (MemorySegment/copy ^MemorySegment segment# (.withOrder ^ValueLayout$OfShort short-layout ^ByteOrder byte-order#) offset# arr# 0 n#)
         arr#)))}
  ([^MemorySegment segment n]
   (let [arr (short-array n)]
     (MemorySegment/copy segment ^ValueLayout$OfShort short-layout 0 arr 0 ^int n)
     arr))
  ([^MemorySegment segment n ^long offset]
   (let [arr (short-array n)]
     (MemorySegment/copy segment ^ValueLayout$OfShort short-layout offset arr 0 ^int n)
     arr))
  ([^MemorySegment segment n ^long offset ^ByteOrder byte-order]
   (let [arr (short-array n)]
     (MemorySegment/copy segment (.withOrder ^ValueLayout$OfShort short-layout byte-order) offset arr 0 ^int n)
     arr)))

(defn read-longs
  "reads `n` elements from a `segment` to a [[long]] array, at an optional `offset`."
  {:inline
   (fn read-longs-inline
     ([segment n]
      `(let [n# ~n
             segment# ~segment
             arr# (long-array ~n)]
         (MemorySegment/copy ^MemorySegment segment# ^ValueLayout$OfLong long-layout 0 arr# 0 n#)
         arr#))
     ([segment n offset]
      `(let [n# ~n
             segment# ~segment
             offset# ~offset
             arr# (long-array ~n)]
         (MemorySegment/copy ^MemorySegment segment# ^ValueLayout$OfLong long-layout offset# arr# 0 n#)
         arr#))
     ([segment n offset byte-order]
      `(let [n# ~n
             segment# ~segment
             offset# ~offset
             byte-order# ~byte-order
             arr# (long-array ~n)]
         (MemorySegment/copy ^MemorySegment segment# (.withOrder ^ValueLayout$OfLong long-layout ^ByteOrder byte-order#) offset# arr# 0 n#)
         arr#)))}
  ([^MemorySegment segment n]
   (let [arr (long-array n)]
     (MemorySegment/copy segment ^ValueLayout$OfLong long-layout 0 arr 0 ^int n)
     arr))
  ([^MemorySegment segment n ^long offset]
   (let [arr (long-array n)]
     (MemorySegment/copy segment ^ValueLayout$OfLong long-layout offset arr 0 ^int n)
     arr))
  ([^MemorySegment segment n ^long offset ^ByteOrder byte-order]
   (let [arr (long-array n)]
     (MemorySegment/copy segment (.withOrder ^ValueLayout$OfLong long-layout byte-order) offset arr 0 ^int n)
     arr)))

(defn read-ints
  "reads `n` elements from a `segment` to a [[int]] array, at an optional `offset`."
  {:inline
   (fn read-ints-inline
     ([segment n]
      `(let [n# ~n
             segment# ~segment
             arr# (int-array ~n)]
         (MemorySegment/copy ^MemorySegment segment# ^ValueLayout$OfInt int-layout 0 arr# 0 n#)
         arr#))
     ([segment n offset]
      `(let [n# ~n
             segment# ~segment
             offset# ~offset
             arr# (int-array ~n)]
         (MemorySegment/copy ^MemorySegment segment# ^ValueLayout$OfInt int-layout offset# arr# 0 n#)
         arr#))
     ([segment n offset byte-order]
      `(let [n# ~n
             segment# ~segment
             offset# ~offset
             byte-order# ~byte-order
             arr# (int-array ~n)]
         (MemorySegment/copy ^MemorySegment segment# (.withOrder ^ValueLayout$OfInt int-layout ^ByteOrder byte-order#) offset# arr# 0 n#)
         arr#)))}
  ([^MemorySegment segment n]
   (let [arr (int-array n)]
     (MemorySegment/copy segment ^ValueLayout$OfInt int-layout 0 arr 0 ^int n)
     arr))
  ([^MemorySegment segment n ^long offset]
   (let [arr (int-array n)]
     (MemorySegment/copy segment ^ValueLayout$OfInt int-layout offset arr 0 ^int n)
     arr))
  ([^MemorySegment segment n ^long offset ^ByteOrder byte-order]
   (let [arr (int-array n)]
     (MemorySegment/copy segment (.withOrder ^ValueLayout$OfInt int-layout byte-order) offset arr 0 ^int n)
     arr)))

(defn read-chars
  "reads `n` elements from a `segment` to a [[char]] array, at an optional `offset`."
  {:inline
   (fn read-chars-inline
     ([segment n]
      `(let [n# ~n
             segment# ~segment
             arr# (char-array ~n)]
         (MemorySegment/copy ^MemorySegment segment# ^ValueLayout$OfChar char-layout 0 arr# 0 n#)
         arr#))
     ([segment n offset]
      `(let [n# ~n
             segment# ~segment
             offset# ~offset
             arr# (char-array ~n)]
         (MemorySegment/copy ^MemorySegment segment# ^ValueLayout$OfChar char-layout offset# arr# 0 n#)
         arr#))
     ([segment n offset byte-order]
      `(let [n# ~n
             segment# ~segment
             offset# ~offset
             byte-order# ~byte-order
             arr# (char-array ~n)]
         (MemorySegment/copy ^MemorySegment segment# (.withOrder ^ValueLayout$OfChar char-layout ^ByteOrder byte-order#) offset# arr# 0 n#)
         arr#)))}
  ([^MemorySegment segment n]
   (let [arr (char-array n)]
     (MemorySegment/copy segment ^ValueLayout$OfChar char-layout 0 arr 0 ^int n)
     arr))
  ([^MemorySegment segment n ^long offset]
   (let [arr (char-array n)]
     (MemorySegment/copy segment ^ValueLayout$OfChar char-layout offset arr 0 ^int n)
     arr))
  ([^MemorySegment segment n ^long offset ^ByteOrder byte-order]
   (let [arr (char-array n)]
     (MemorySegment/copy segment (.withOrder ^ValueLayout$OfChar char-layout byte-order) offset arr 0 ^int n)
     arr)))

(defn read-floats
  "reads `n` elements from a `segment` to a [[float]] array, at an optional `offset`."
  {:inline
   (fn read-floats-inline
     ([segment n]
      `(let [n# ~n
             segment# ~segment
             arr# (float-array ~n)]
         (MemorySegment/copy ^MemorySegment segment# ^ValueLayout$OfFloat float-layout 0 arr# 0 n#)
         arr#))
     ([segment n offset]
      `(let [n# ~n
             segment# ~segment
             offset# ~offset
             arr# (float-array ~n)]
         (MemorySegment/copy ^MemorySegment segment# ^ValueLayout$OfFloat float-layout offset# arr# 0 n#)
         arr#))
     ([segment n offset byte-order]
      `(let [n# ~n
             segment# ~segment
             offset# ~offset
             byte-order# ~byte-order
             arr# (float-array ~n)]
         (MemorySegment/copy ^MemorySegment segment# (.withOrder ^ValueLayout$OfFloat float-layout ^ByteOrder byte-order#) offset# arr# 0 n#)
         arr#)))}
  ([^MemorySegment segment n]
   (let [arr (float-array n)]
     (MemorySegment/copy segment ^ValueLayout$OfFloat float-layout 0 arr 0 ^int n)
     arr))
  ([^MemorySegment segment n ^long offset]
   (let [arr (float-array n)]
     (MemorySegment/copy segment ^ValueLayout$OfFloat float-layout offset arr 0 ^int n)
     arr))
  ([^MemorySegment segment n ^long offset ^ByteOrder byte-order]
   (let [arr (float-array n)]
     (MemorySegment/copy segment (.withOrder ^ValueLayout$OfFloat float-layout byte-order) offset arr 0 ^int n)
     arr)))

(defn read-doubles
  "reads `n` elements from a `segment` to a [[double]] array, at an optional `offset`."
  {:inline
   (fn read-doubles-inline
     ([segment n]
      `(let [n# ~n
             segment# ~segment
             arr# (double-array ~n)]
         (MemorySegment/copy ^MemorySegment segment# ^ValueLayout$OfDouble double-layout 0 arr# 0 n#)
         arr#))
     ([segment n offset]
      `(let [n# ~n
             segment# ~segment
             offset# ~offset
             arr# (double-array ~n)]
         (MemorySegment/copy ^MemorySegment segment# ^ValueLayout$OfDouble double-layout offset# arr# 0 n#)
         arr#))
     ([segment n offset byte-order]
      `(let [n# ~n
             segment# ~segment
             offset# ~offset
             byte-order# ~byte-order
             arr# (double-array ~n)]
         (MemorySegment/copy ^MemorySegment segment# (.withOrder ^ValueLayout$OfDouble double-layout ^ByteOrder byte-order#) offset# arr# 0 n#)
         arr#)))}
  ([^MemorySegment segment n]
   (let [arr (double-array n)]
     (MemorySegment/copy segment ^ValueLayout$OfDouble double-layout 0 arr 0 ^int n)
     arr))
  ([^MemorySegment segment n ^long offset]
   (let [arr (double-array n)]
     (MemorySegment/copy segment ^ValueLayout$OfDouble double-layout offset arr 0 ^int n)
     arr))
  ([^MemorySegment segment n ^long offset ^ByteOrder byte-order]
   (let [arr (double-array n)]
     (MemorySegment/copy segment (.withOrder ^ValueLayout$OfDouble double-layout byte-order) offset arr 0 ^int n)
     arr)))

(defn- type-dispatch
  "Gets a type dispatch value from a (potentially composite) type."
  [type]
  (cond
    (qualified-keyword? type) type
    (sequential? type) (keyword (first type))
    :else (throw (ex-info "Invalid type object" {:type type}))))


;; the rest of the namespace is split across topic files to keep each
;; file reviewable; everything still lives in coffi.mem
(load "mem/serde")
(load "mem/composite")
