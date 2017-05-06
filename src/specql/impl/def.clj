(ns specql.impl.def
  "Containes the define-tables macro and support code."
  (:require [clojure.spec.alpha :as s]
            [specql.data-types :as d]
            [specql.transform :as xf]
            [specql.impl.catalog :refer :all]
            [specql.impl.registry :as registry :refer :all]
            [specql.impl.composite :as composite]
            [specql.impl.util :refer :all]))

(defn- validate-column-types [tables]
  (let [kw-types (->> tables
                      vals
                      (mapcat :columns))]
    (loop [kw-type {}
           [[kw {type :type}] & kw-types] kw-types]
      (when kw
        (let [previous-type (get kw-type kw)]
          (when previous-type
            (assert (= previous-type type)
                    (str "Type mismatch. Keyword " kw
                         " is already defined as \"" previous-type
                         "\" and now trying to define it as \"" type
                         "\". Check that two tables don't have the same column name"
                         " with different column types in the same namespace.")))
          (recur (assoc kw-type kw type)
                 kw-types))))))

(defn- validate-table-names
  "Validate that there are no columns with the same kw as a table."
  [tables]
  (let [all-column-keys (->> tables vals (mapcat (comp keys :columns)) (into #{}))]
    (doseq [[k _] tables]
      (assert (not (all-column-keys k))
              (str "Table/column name clash. Table " k " is also defined as a column.")))))

(defn- validate-table-info
  "Check that there are no name/type clashes in table info."
  [tables]
  (validate-column-types tables)
  (validate-table-names tables))

(defn- cljs?
  "Check if we are compiling cljs"
  []
  (some-> 'cljs.analyzer
          find-ns
          (ns-resolve '*cljs-file*)
          boolean?))

(defn- type-spec [db table-info {:keys [category type] :as column}]
  (if (= "A" category)
    (:element-type column)
    (or (composite-type table-info type)
        (and (:enum? column)
             (or
              ;; previously registered enum type
              (enum-type table-info type)
              ;; just the values as spec
              (enum-values db type)))

        ;; varchar/text field with max length set
        (and (#{"text" "varchar"} type)
             (pos? (:type-specific-data column))
             `(s/and ~(keyword "specql.data-types" type)
                     (fn [s#]
                       (<= (count s#) ~(- (:type-specific-data column) 4)))))

        (keyword "specql.data-types" type))))

(defn- column-specs [db table-info columns]
  (for [[kw {type :type
             category :category
             transform :xf/transform
             :as column}] columns
        :let [array? (= "A" category)
              type (if array?
                     (subs type 1)
                     type)
              type-spec (type-spec db table-info column)]
        :when type-spec]
    (let [type-spec (if transform
                      (xf/transform-spec transform type-spec)
                      type-spec)]
      `(s/def ~kw ~(cond
                     array?
                     `(s/coll-of ~type-spec)

                     (not (:not-null? column))
                     `(s/nilable ~type-spec)

                     :default
                     type-spec)))))

(defn- enum-spec [table-keyword {:keys [values] :as table}]
  (let [transform (get-in table [:rel ::xf/transform])
        values (if transform
                 (xf/transform-spec transform values)
                 values)]
    `(s/def ~table-keyword ~values)))

(defn- apply-enum-transformations
  "If enums are defined as types and they have transformations, apply the enum's
  transformation to all columns whose type is that enum."
  [table-info new-table-info]
  (map-vals
   new-table-info
   (fn [table]
     (update
      table :columns map-vals
      (fn [{enum? :enum? type :type transform ::xf/transform :as column}]
        (if (or (not enum?) transform)
          ;; Not an enum or already has a transform, return as is
          column
          ;; This is an enum, try to find transformation from enum
          ;; type definition (if any)
          (if-let [transform (some->> type
                                      (registry/enum-type table-info)
                                      table-info
                                      :rel ::xf/transform)]
            (assoc column ::xf/transform transform)
            column)))))))

(defmacro define-tables
  "See specql.core/define-tables for documentation."
  [db & tables]
  (let [db (eval db)]
    (let [table-info (into {}
                           (map (fn [[table-name table-keyword opts]]
                                  (let [ns (name (namespace table-keyword))]
                                    [table-keyword
                                     (-> (table-info db table-name)
                                         (assoc :insert-spec-kw
                                                (keyword ns (str (name table-keyword) "-insert")))
                                         (process-columns ns opts)
                                         (assoc :rel (eval opts)))])))
                           tables)
          new-table-info (reduce-kv
                          (fn [m k v]
                            (assoc m k
                                   (update v :columns
                                           (fn [columns]
                                             (reduce-kv
                                              (fn [cols key val]
                                                (assoc cols key
                                                       (registry/array-element-type table-info val)))
                                              {}
                                              columns)))))
                          {}
                          table-info)
          table-info (merge @table-info-registry new-table-info)
          new-table-info (apply-enum-transformations table-info new-table-info)
          cljs? (cljs?)]


      (validate-table-info table-info)
      `(do
         ;; Register table info so that it is available at runtime
         ;; Only for Clojure
         ~(when-not cljs?
            `(swap! table-info-registry merge ~new-table-info))

         ~@(for [[_ table-keyword] tables
                 :let [{columns :columns
                        insert-spec-kw :insert-spec-kw :as table}
                       (table-info table-keyword)
                       {required-insert true
                        optional-insert false} (group-by (comp required-insert? second) columns)]]
             (if (= :enum (:type table))
               (enum-spec table-keyword table)
               `(do
                  ;; Create the keys spec for the table
                  (s/def ~table-keyword
                    (s/keys :opt [~@(keys columns)]))

                  ;; Spec for a "full" row that has all NOT NULL values
                  (s/def ~insert-spec-kw
                    (s/keys :req [~@(keys required-insert)]
                            :opt [~@(keys optional-insert)]))

                  ;; Create specs for columns
                  ~@(column-specs db table-info columns))))))))
