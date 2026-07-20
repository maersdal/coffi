(ns coffi.clang-test
  (:require
   [clojure.test :as t]
   [coffi.clang :as clang]
   [coffi.mem :as mem]))

;; a representative chunk of `clang -Xclang -fdump-record-layouts` output:
;; pointers, fixed-width ints, plain ints (with padding before the next
;; pointer), an array, and a function pointer
(def packet-layout
  "         0 |   AVBufferRef * buf
          8 |   int64_t pts
         16 |   int64_t dts
         24 |   uint8_t * data
         32 |   int size
         36 |   int stream_index
         40 |   int flags
         48 |   AVPacketSideData * side_data
         56 |   int side_data_elems
         64 |   int64_t duration")

(t/deftest parses-member-names-offsets-and-types
  (let [members (clang/parse-layout packet-layout)]
    (t/is (= [:buf :pts :dts :data :size :stream-index :flags
              :side-data :side-data-elems :duration]
             (map :name members)))
    (t/is (= [0 8 16 24 32 36 40 48 56 64] (map :offset members)))
    (t/is (= [::mem/pointer ::mem/long ::mem/long ::mem/pointer ::mem/int
              ::mem/int ::mem/int ::mem/pointer ::mem/int ::mem/long]
             (map :type members)))))

(t/deftest keep-names-option-preserves-underscores
  (t/is (= :stream_index
           (-> (clang/parse-layout packet-layout :keep-names? true)
               (nth 5)
               :name))))

(t/deftest parses-arrays-and-function-pointers
  (let [members (clang/parse-layout
                 "  0 |   int32_t values[4]
                   16 |   void (*)(void *) callback")]
    (t/is (= [::mem/array ::mem/int 4] (:type (first members))))
    (t/is (= ::mem/pointer (:type (second members))))))

(t/deftest platform-dependent-long-resolves-via-linker
  ;; `long` is 8 bytes on unix x64, 4 on windows x64 — either way it must
  ;; agree with what the native linker reports
  (let [[{:keys [type]}] (clang/parse-layout "  0 |   long value")]
    (t/is (contains? #{::mem/int ::mem/long} type))
    (t/is (= (mem/size-of type)
             (.byteSize ^java.lang.foreign.MemoryLayout
                        (get (.canonicalLayouts (java.lang.foreign.Linker/nativeLinker))
                             "long"))))))

(t/deftest struct-type-builds-verified-c-layout
  (let [stype (clang/struct-type packet-layout)]
    (t/is (= 48 (mem/struct-field-offset stype :side-data)))
    (t/is (= 36 (mem/struct-field-offset stype :stream-index)))))

(t/deftest offset-mismatch-fails-fast
  ;; claims the field after the int sits at 44, but C alignment pads the
  ;; pointer to 48 — the cross-check must catch the disagreement
  (t/is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"offset differs"
         (clang/struct-type
          "  0 |   int a
             4 |   char b
             44 |   char * c"))))

(t/deftest unknown-types-throw-without-handler
  (t/is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Unrecognized"
         (clang/parse-layout "  0 |   __m128i vec")))
  (t/is (= [::mem/pointer]
           (map :type (clang/parse-layout "  0 |   __m128i vec"
                                          :on-unknown (constantly ::mem/pointer))))))

(clang/defstruct-from-layout LayoutPoint
  "  0 |   float x
     4 |   float y")

(t/deftest defstruct-from-layout-defines-usable-struct
  (t/is (= {:x 1.0 :y 2.0}
           (-> (LayoutPoint. 1.0 2.0)
               (mem/serialize ::LayoutPoint)
               (mem/deserialize ::LayoutPoint)))))
