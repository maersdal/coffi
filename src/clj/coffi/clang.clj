(ns coffi.clang
  "Dev-time helpers turning clang record-layout dumps into coffi struct types.

  Instead of hand-transcribing offsets from C headers, dump the real layouts
  the compiler uses:

  ```console
  clang mylib.c -I... -Xclang -fdump-record-layouts > layouts.txt
  ```

  and paste the member lines of a record into [[defstruct-from-layout]] (or
  [[struct-type]] for the data representation). Every parsed member offset
  is verified against the layout coffi computes, so a padding or alignment
  mismatch fails at namespace load instead of corrupting memory at call
  time.

  Parsing approach and the offset cross-check are modeled after
  dtype-next's `tech.v3.datatype.ffi.clang`."
  (:require
   [clojure.string :as str]
   [coffi.layout :as layout]
   [coffi.mem :as mem])
  (:import
   (java.lang.foreign Linker MemoryLayout)))

(set! *warn-on-reflection* true)

;; a member line is `<offset> | <declaration>` with the declaration exactly
;; three spaces after the pipe; deeper indentation is a nested record's
;; expansion, which the parent record represents as one member
(def ^:private member-line-regex #"\s*(\d+)\s\|\s\s\s(\w.+)")

(def ^:private array-suffix-regex #"\[(\d+)\]")

(def ^:private fixed-width-types
  {"char" ::mem/byte
   "signed" ::mem/byte
   "int8_t" ::mem/byte
   "uint8_t" ::mem/byte
   "short" ::mem/short
   "int16_t" ::mem/short
   "uint16_t" ::mem/short
   "int" ::mem/int
   "int32_t" ::mem/int
   "uint32_t" ::mem/int
   "int64_t" ::mem/long
   "uint64_t" ::mem/long
   "float" ::mem/float
   "double" ::mem/double})

(def ^:private canonical-widths
  "Byte widths of the platform-dependent C integer types, as the native
  linker defines them for this platform (e.g. `long` is 4 bytes on Windows
  x64 and 8 on unix x64)."
  (delay
    (into {}
          (map (fn [[n ^MemoryLayout l]] [n (.byteSize l)]))
          (.canonicalLayouts (Linker/nativeLinker)))))

(defn- platform-int-type
  [type-name]
  (when-some [width (get @canonical-widths type-name)]
    (case (long width)
      4 ::mem/int
      8 ::mem/long
      nil)))

(defn- parse-member-type
  "Parses the type of one clang member declaration line into a coffi type."
  [^String decl tokens {:keys [struct-resolver on-unknown]}]
  (cond
    ;; any pointer — including function pointers `(*)(` — is just a pointer
    (str/includes? decl "*") ::mem/pointer
    (str/starts-with? decl "struct ")
    (if struct-resolver
      (struct-resolver (second tokens))
      (throw (ex-info "Nested struct member requires a :struct-resolver option"
                      {:line decl})))
    (str/starts-with? decl "enum ") ::mem/int
    :else
    (let [[base unsigned?] (if (contains? #{"unsigned" "signed"} (first tokens))
                             [(str/join " " (rest (butlast tokens))) (= (first tokens) "unsigned")]
                             [(str/join " " (butlast tokens)) false])
          base (str/trim base)]
      (or (fixed-width-types base)
          (when (contains? #{"long" "unsigned long" "long int"} base)
            (platform-int-type "long"))
          (when (contains? #{"long long" "unsigned long long"} base)
            ::mem/long)
          (platform-int-type base)
          (when (and unsigned? (fixed-width-types (str/join " " (rest tokens))))
            (fixed-width-types (str/join " " (rest tokens))))
          (if on-unknown
            (on-unknown decl)
            (throw (ex-info "Unrecognized member type in clang layout line"
                            {:line decl
                             :base-type base})))))))

(defn- parse-member-line
  [offset ^String decl opts]
  (let [tokens (str/split decl #"\s+")
        member-name (-> (last tokens)
                        (str/replace array-suffix-regex "")
                        (cond-> (not (:keep-names? opts)) (str/replace "_" "-"))
                        keyword)
        base-type (parse-member-type decl tokens opts)
        array-len (some-> (re-find array-suffix-regex decl) second parse-long)]
    {:name member-name
     :offset (parse-long offset)
     :type (if array-len
             [::mem/array base-type array-len]
             base-type)}))

(defn parse-layout
  "Parses the member lines of one record from a clang
  `-fdump-record-layouts` dump into `{:name :offset :type}` maps.

  Only top-level member lines (exactly three spaces of declaration indent)
  are considered; nested record expansions and the summary line are
  ignored, so the record's chunk of the dump can be pasted wholesale.

  Options:

  * `:struct-resolver` — fn of a C struct name returning the coffi type for
    members declared `struct foo bar;`.
  * `:on-unknown` — fn of the declaration line returning a coffi type, for
    types this parser does not recognize; parsing throws without it.
  * `:keep-names?` — keep C member names verbatim instead of kebab-casing
    underscores."
  [layout & {:as opts}]
  (into []
        (keep (fn [line]
                (when-let [[_ offset decl] (re-matches member-line-regex line)]
                  (parse-member-line offset decl opts))))
        (str/split-lines layout)))

(defn verify-layout!
  "Checks every parsed member's clang offset against the offset coffi
  computes for `struct-type`, throwing on the first mismatch.

  Returns `struct-type`. A mismatch means the generated struct type does
  not describe the memory the C compiler lays out — failing here at load
  time is what prevents silently corrupt field access later."
  [struct-type members]
  (doseq [{:keys [name offset]} members]
    (let [computed (mem/struct-field-offset struct-type name)]
      (when-not (= (long offset) (long computed))
        (throw (ex-info "Struct member offset differs from clang's layout"
                        {:struct struct-type
                         :member name
                         :clang-offset offset
                         :coffi-offset computed})))))
  struct-type)

(defn struct-type
  "Builds a C-aligned `[::mem/struct ...]` type from a clang record-layout
  dump, verifying every member offset against clang's.

  Takes the same options as [[parse-layout]]."
  [layout & {:as opts}]
  (let [members (parse-layout layout opts)]
    (-> [::mem/struct (mapv (juxt :name :type) members)]
        layout/with-c-layout
        (verify-layout! members))))

(defmacro defstruct-from-layout
  "Defines a struct (as [[coffi.mem/defstruct]]) from a clang record-layout
  dump, verifying every member offset against clang's at load time.

  `layout` must be a literal string or a symbol resolving to one at macro
  expansion. Members declared `struct foo` resolve to `::foo` in the
  current namespace by default; pass `:struct-resolver` to override.
  Takes the other [[parse-layout]] options as well.

  ```clojure
  (defstruct-from-layout Packet
    \"     0 |   AVBufferRef * buf
          8 |   int64_t pts
         16 |   int64_t dts\")
  ```"
  {:style/indent [:defn]}
  [name layout & {:as opts}]
  (let [layout (if (symbol? layout)
                 (some-> (resolve layout) deref)
                 layout)
        _ (when-not (string? layout)
            (throw (ex-info "defstruct-from-layout requires a literal layout string or a symbol resolving to one"
                            {:name name
                             :layout layout})))
        opts (merge {:struct-resolver (let [ns-str (str *ns*)]
                                        #(keyword ns-str %))}
                    opts)
        members (parse-layout layout opts)
        fields (mapcat (fn [{:keys [name type]}]
                         [(symbol (clojure.core/name name)) type])
                       members)]
    `(do
       (mem/defstruct ~name [~@fields])
       (verify-layout! ~(keyword (str *ns*) (clojure.core/name name))
                       '~members))))
