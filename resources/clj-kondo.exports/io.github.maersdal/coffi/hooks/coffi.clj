(ns ^:no-doc hooks.coffi
  (:require
   [clj-kondo.hooks-api :as api]
   [clojure.string :as str]))

(defn validate-type
  [node]
  (when-not (or (qualified-keyword? (api/sexpr node))
                (and (api/vector-node? node)
                     (qualified-keyword? (api/sexpr (first (:children node))))))
    (api/reg-finding!
     {:row (:row (meta node))
      :col (:col (meta node))
      :message "A type must be a qualified keyword or a vector with one as the first element."
      :type :coffi.ffi/invalid-syntax})))

(def ^:private unwrapped-return-value
  "Placeholder return value nodes for single unwrapped (non-compound)
  return types, so the stub lints as returning a value of said type
  instead of a vector of its args. Keyed by type name so both ::mem/int
  and aliases from other namespaces match."
  {"byte" (api/token-node 0)
   "short" (api/token-node 0)
   "int" (api/token-node 0)
   "long" (api/token-node 0)
   "float" (api/token-node 0.0)
   "double" (api/token-node 0.0)
   "char" (api/token-node \a)
   "c-string" (api/string-node "")
   "void" (api/token-node nil)})

(defn defstruct
  [{:keys [node]}]
  (try
    (let [[name-node fields-node] (rest (:children node))
          field-syms (take-nth 2 (:children fields-node))
          type-nodes (take-nth 2 (rest (:children fields-node)))]
      (run! validate-type type-nodes)
      ;; a struct behaves like a record: a positional constructor class
      ;; plus map-like field access
      {:node (api/list-node
              (list (api/token-node 'defrecord)
                    name-node
                    (api/vector-node (vec field-syms))))})
    (catch Exception _
      (api/reg-finding!
       {:row (:row (meta node))
        :col (:col (meta node))
        :message "Invalid syntax"
        :type :coffi.ffi/invalid-syntax}))))

(defn defstruct-from-layout
  [{:keys [node]}]
  (try
    (let [[name-node layout-node] (rest (:children node))
          fields (when (api/string-node? layout-node)
                   (->> (str/split-lines (api/sexpr layout-node))
                        (keep #(re-matches #"\s*(\d+)\s\|\s\s\s(\w.+)" %))
                        (mapv (fn [[_ _ decl]]
                                (-> ^String (last (str/split (str/trim decl) #"\s+"))
                                    (str/replace #"\[\d+\]" "")
                                    (str/replace "_" "-")
                                    symbol)))))]
      {:node (api/list-node
              (list (api/token-node 'defrecord)
                    name-node
                    (api/vector-node (mapv api/token-node fields))))})
    (catch Exception _
      (api/reg-finding!
       {:row (:row (meta node))
        :col (:col (meta node))
        :message "Invalid syntax"
        :type :coffi.ffi/invalid-syntax}))))

(defn deflibrary
  [{:keys [node]}]
  (try
    (let [[name-node & more] (rest (:children node))
          [doc-node & more] (if (api/string-node? (first more))
                              more
                              (cons nil more))
          [fndefs-node & opt-nodes] more
          _ (when-not (api/map-node? fndefs-node)
              (api/reg-finding!
               {:row (:row (meta (or fndefs-node node)))
                :col (:col (meta (or fndefs-node node)))
                :message "deflibrary requires a literal map of fn definitions."
                :type :coffi.ffi/invalid-syntax}))
          fn-nodes
          (when (api/map-node? fndefs-node)
            (for [[k-node v-node] (partition 2 (:children fndefs-node))]
              (if-not (and (keyword? (api/sexpr k-node))
                           (api/map-node? v-node))
                (do (api/reg-finding!
                     {:row (:row (meta k-node))
                      :col (:col (meta k-node))
                      :message "deflibrary entries must map keyword names to definition maps."
                      :type :coffi.ffi/invalid-syntax})
                    nil)
                (let [pairs (partition 2 (:children v-node))
                      get-val (fn [kw]
                                (some (fn [[k v]]
                                        (when (= kw (api/sexpr k)) v))
                                      pairs))
                      args-node (get-val :args)
                      ret-node (get-val :ret)
                      fn-doc-node (get-val :doc)
                      arglist (api/vector-node
                               (mapv api/token-node
                                     (repeatedly (count (:children args-node))
                                                 #(gensym "arg"))))
                      ret-value-node (when ret-node
                                       (let [ret (api/sexpr ret-node)]
                                         (when (qualified-keyword? ret)
                                           (unwrapped-return-value (name ret)))))]
                  (run! validate-type
                        (concat (:children args-node)
                                (when ret-node [ret-node])))
                  (api/list-node
                   (list*
                    (api/token-node 'defn)
                    (api/token-node (symbol (name (api/sexpr k-node))))
                    (concat
                     (when (and fn-doc-node (api/string-node? fn-doc-node))
                       [fn-doc-node])
                     [arglist
                      (if ret-value-node
                        ;; single unwrapped return type: return a value of
                        ;; said type so callers lint against it, keeping
                        ;; the args "used" via the ignored binding
                        (api/list-node
                         (list
                          (api/token-node 'let)
                          (api/vector-node [(api/token-node '_) arglist])
                          ret-value-node))
                        arglist)])))))))
          def-node (api/list-node
                    (concat
                     [(api/token-node 'def) name-node]
                     (when doc-node [doc-node])
                     [fndefs-node]))]
      {:node (api/list-node
              (list*
               (api/token-node 'do)
               def-node
               (concat (filter some? fn-nodes)
                       ;; analyze the option values (e.g. the :check-error
                       ;; fn) so they lint like ordinary code
                       (when (seq opt-nodes)
                         [(api/map-node (vec opt-nodes))]))))})
    (catch Exception _
      (api/reg-finding!
       {:row (:row (meta node))
        :col (:col (meta node))
        :message "Invalid syntax"
        :type :coffi.ffi/invalid-syntax}))))

(defn defcfn
  [{:keys [node]}]
  (try
    (let [[var-name-node & more] (rest (:children node))
          [docstring-node & more] (if (and (api/string-node? (first more))
                                           (not (api/vector-node? (second more))))
                                    more
                                    (cons nil more))
          [attr-map-node & more] (if (api/map-node? (first more))
                                   more
                                   (cons nil more))
          [symbol-node native-arglist-node return-type-node & more] more
          _ (when-not (or (and (api/token-node? symbol-node)
                               (simple-symbol? (api/sexpr symbol-node)))
                          (api/string-node? symbol-node))
              (api/reg-finding! {:row (:row (meta symbol-node))
                                 :col (:col (meta symbol-node))
                                 :message "Native symbol must be a string or symbol."
                                 :type :coffi.ffi/invalid-syntax}))
          _ (run! validate-type (cons return-type-node (:children native-arglist-node)))
          wrapper-nodes (when (seq more)
                          {:native-fn (first more)
                           :fn-tail (rest more)})
          _ (when (and (:native-fn wrapper-nodes)
                       (empty? (:fn-tail wrapper-nodes)))
              (api/reg-finding!
               {:row (:row (meta node))
                :col (:col (meta node))
                :message "A defcfn with a native-fn must have a function body."
                :type :coffi.ffi/invalid-syntax}))
          arglist-vec (api/vector-node
                       (mapv api/token-node
                             (repeatedly (count (:children native-arglist-node))
                                         #(gensym "arg"))))
          return-value-node (when-not wrapper-nodes
                              (let [ret (api/sexpr return-type-node)]
                                (when (qualified-keyword? ret)
                                  (unwrapped-return-value (name ret)))))
          fn-body (if wrapper-nodes
                    (:fn-tail wrapper-nodes)
                    (list
                     arglist-vec
                     (if return-value-node
                       ;; single unwrapped return type: return a value of
                       ;; said type so callers lint against it, keeping the
                       ;; args "used" via the ignored binding
                       (api/list-node
                        (list
                         (api/token-node 'let)
                         (api/vector-node [(api/token-node '_) arglist-vec])
                         return-value-node))
                       arglist-vec)))
          defn-node (api/list-node
                     (list*
                      (api/token-node 'defn)
                      var-name-node
                      (concat
                       (filter some? [docstring-node attr-map-node])
                       fn-body)))
          let-node (api/list-node
                    (list
                     (api/token-node 'let)
                     (api/vector-node
                      (cond->> nil
                        wrapper-nodes (concat [(:native-fn wrapper-nodes)
                                               (api/list-node
                                                (list
                                                 (api/token-node 'fn)
                                                 arglist-vec
                                                 arglist-vec))])
                        :always vec))
                     defn-node))]
      {:node let-node})
    (catch Exception _
      (api/reg-finding!
       {:row (:row (meta node))
        :col (:col (meta node))
        :message "Invalid syntax"
        :type :coffi.ffi/invalid-syntax}))))
