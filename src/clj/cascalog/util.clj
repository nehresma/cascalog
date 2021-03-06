;;    Copyright 2010 Nathan Marz
;; 
;;    This program is free software: you can redistribute it and/or modify
;;    it under the terms of the GNU General Public License as published by
;;    the Free Software Foundation, either version 3 of the License, or
;;    (at your option) any later version.
;; 
;;    This program is distributed in the hope that it will be useful,
;;    but WITHOUT ANY WARRANTY; without even the implied warranty of
;;    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;;    GNU General Public License for more details.
;; 
;;    You should have received a copy of the GNU General Public License
;;    along with this program.  If not, see <http://www.gnu.org/licenses/>.

(ns cascalog.util
  (:use [clojure.set :only (difference)])
  (:require [clojure.string :as s])
  (:import [java.util UUID Collection]))

(defmacro defalias
  "Defines an alias for a var: a new var with the same root binding (if
  any) and similar metadata. The metadata of the alias is its initial
  metadata (as provided by def) merged into the metadata of the original."
  ([name orig]
     `(do
        (alter-meta!
         (if (.hasRoot (var ~orig))
           (def ~name (.getRawRoot (var ~orig)))
           (def ~name))
         ;; When copying metadata, disregard {:macro false}.
         ;; Workaround for http://www.assembla.com/spaces/clojure/tickets/273
         #(conj (dissoc % :macro)
                (apply dissoc (meta (var ~orig)) (remove #{:macro} (keys %)))))
        (var ~name)))
  ([name orig doc]
     (list `defalias (with-meta name (assoc (meta name) :doc doc)) orig)))

;; name-with-attributes by Konrad Hinsen:
(defn name-with-attributes
  "To be used in macro definitions.
   Handles optional docstrings and attribute maps for a name to be defined
   in a list of macro arguments. If the first macro argument is a string,
   it is added as a docstring to name and removed from the macro argument
   list. If afterwards the first macro argument is a map, its entries are
   added to the name's metadata map and the map is removed from the
   macro argument list. The return value is a vector containing the name
   with its extended metadata map and the list of unprocessed macro
   arguments."
  [name macro-args]
  (let [[docstring macro-args] (if (string? (first macro-args))
                                 [(first macro-args) (next macro-args)]
                                 [nil macro-args])
    [attr macro-args]          (if (map? (first macro-args))
                                 [(first macro-args) (next macro-args)]
                                 [{} macro-args])
    attr                       (if docstring
                                 (assoc attr :doc docstring)
                                 attr)
    attr                       (if (meta name)
                                 (conj (meta name) attr)
                                 attr)]
    [(with-meta name attr) macro-args]))

(defmacro defnk
 "Define a function accepting keyword arguments. Symbols up to the first
 keyword in the parameter list are taken as positional arguments.  Then
 an alternating sequence of keywords and defaults values is expected. The
 values of the keyword arguments are available in the function body by
 virtue of the symbol corresponding to the keyword (cf. :keys destructuring).
 defnk accepts an optional docstring as well as an optional metadata map."
 [fn-name & fn-tail]
 (let [[fn-name [args & body]] (name-with-attributes fn-name fn-tail)
       [pos kw-vals]           (split-with symbol? args)
       syms                    (map #(-> % name symbol) (take-nth 2 kw-vals))
       values                  (take-nth 2 (rest kw-vals))
       sym-vals                (apply hash-map (interleave syms values))
       de-map                  {:keys (vec syms)
                                :or   sym-vals}]
   `(defn ~fn-name
      [~@pos & options#]
      (let [~de-map (apply hash-map options#)]
        ~@body))))

(defn multifn? [x]
  (instance? clojure.lang.MultiFn x))

(defn throw-illegal [str]
  (throw (IllegalArgumentException. str)))

(defn throw-runtime [str]
  (throw (RuntimeException. str)))

(defn try-update-in
  [m key-vec f & args]
  (reduce #(%2 %1) m
          (for [k key-vec]
            #(if (get % k)
               (apply update-in % [k] f args)
               %))))

(defn merge-to-vec
  "Returns a vector representation of the union of all supplied
  items. Entries in xs can be collections or individual items. For
  example,

  (merge-to-vec [1 2] :help 2 1)
  => [1 2 :help]"
  [& xs]
  (->> xs
       (map #(if (coll? %) (set %) #{%}))
       (reduce #(concat % (difference %2 %)))
       (vec)))

(defn transpose [m]
  (apply map list m))

(defn find-first
  "Returns the first item of coll for which (pred item) returns logical true.
  Consumes sequences up to the first match, will consume the entire sequence
  and return nil if no match is found."
  [pred coll]
  (first (filter pred coll)))

(def ^{:doc "Accepts a predicate and a sequence, and returns:

   [(filter pred xs) (remove pred xs)]"}
  separate
  (juxt filter remove))

(defn substitute-if
  "Returns [newseq {map of newvals to oldvals}]"
  [pred subfn aseq]
  (reduce (fn [[newseq subs] val]
            (let [[newval sub] (if (pred val)
                                 (let [subbed (subfn val)] [subbed {subbed val}])
                                 [val {}])]
              [(conj newseq newval) (merge subs sub)]))
          [[] {}] aseq))

(defn try-resolve [obj]
  (when (symbol? obj) (resolve obj)))

(defn wipe
  "Returns a new collection generated by dropping the item at position
  `idx` from `coll`."
  [coll idx]
  (concat (take idx coll) (drop (inc idx) coll)))

(defn collectify [obj]
  (if (or (sequential? obj) (instance? Collection obj)) obj [obj]))

(defn multi-set
  "Returns a map of elem to count"
  [aseq]
  (apply merge-with +
         (map #(hash-map % 1) aseq)))

(defn remove-first [f coll]
  (let [i (map-indexed vector coll)
        ri (find-first #(f (second %)) i)]
    (when-not ri (throw-illegal "Couldn't find an item to remove"))
    (map second (remove (partial = ri) i))))

(defn uuid []
  (str (UUID/randomUUID)))

(defn all-pairs
  "[1 2 3] -> [[1 2] [1 3] [2 3]]"
  [coll]
  (let [pair-up (fn [v vals]
                  (map (partial vector v) vals))]
    (apply concat (for [i (range (dec (count coll)))]
                    (pair-up (nth coll i) (drop (inc i) coll))
                    ))))

(defn unweave
  "[1 2 3 4 5 6] -> [[1 3 5] [2 4 6]]"
  [coll]
  (when (odd? (count coll))
    (throw-illegal "Need even number of args to unweave"))
  [(take-nth 2 coll) (take-nth 2 (rest coll))])

(defn duplicates
  "Returns a vector of all values for which duplicates appear in the
  supplied collection. For example:

  (duplicates [1 2 2 1 3])
  ;=> [1 2]"
  [coll]
  (loop [[x & more] coll, test-set #{}, dups #{}]
    (if-not x
      (vec dups)
      (recur more
             (conj test-set x)
             (if (test-set x) (conj dups x) dups)))))

(defn pairs2map [pairs]
  (apply hash-map (flatten pairs)))

(defn reverse-map
  "{:a 1 :b 1 :c 2} -> {1 [:a :b] 2 :c}"
  [amap]
  (reduce (fn [m [k v]]
            (let [existing (get m v [])]
              (assoc m v (conj existing k))))
          {} amap))

(defn some? [pred coll]
  ((complement nil?) (some pred coll)))

(defmacro dofor [& body]
  `(doall (for ~@body)))

(defn count= [& args]
  (apply = (map count args)))

(defn not-count= [& args]
  (not (apply count= args)))

(defmacro if-ret [form else-form]
  `(if-let [ret# ~form]
     ret#
     ~else-form))

(defn- clean-nil-bindings [bindings]
  (let [pairs (partition 2 bindings)]
    (mapcat identity (filter #(first %) pairs))))

(defn meta-conj
  "Returns the supplied symbol with the supplied `attr` map conj-ed
  onto the symbol's current metadata."
  [sym attr]
  (with-meta sym (if (meta sym)
                   (conj (meta sym) attr)
                   attr)))

(defn set-namespace-value
  "Merges the supplied kv-pair into the metadata of the namespace in
  which the function is called."
  [key-name newval]
  (alter-meta! *ns* merge {key-name newval}))

(defn mk-destructured-seq-map [& bindings]
  ;; lhs needs to be symbolified
  (let [bindings (clean-nil-bindings bindings)
        to-sym (fn [s] (if (keyword? s) s (symbol s)))
        [lhs rhs] (unweave bindings)
        lhs  (for [l lhs] (if (sequential? l) (vec (map to-sym l)) (symbol l)))
        rhs (for [r rhs] (if (sequential? r) (vec r) r))
        destructured (vec (destructure (interleave lhs rhs)))
        syms (first (unweave destructured))
        extract-code (vec (for [s syms] [(str s) s]))]
    (eval
     `(let ~destructured
        (into {} ~extract-code)))))

(def default-serializations
  ["org.apache.hadoop.io.serializer.WritableSerialization"
   "cascading.tuple.hadoop.BytesSerialization"
   "cascading.tuple.hadoop.TupleSerialization"])

(defn serialization-entry
  [serial-vec]
  (->> serial-vec
       (map (fn [x]
              (cond (string? x) x
                    (class? x) (.getName x))))
       (merge-to-vec default-serializations)
       (s/join ",")))

(defn merge-serialization-strings
  [& ser-strings]
  (->> ser-strings
       (filter identity)
       (mapcat #(s/split % #","))
       (serialization-entry)))

(defn conf-merge
  "TODO: Come up with a more general version of this, similar to
  merge-with, that takes a map of key-func pairs, and merges with
  those functions."
  [& maps]
  (reduce (fn [m1 m2]
            (let [m2 (try-update-in m2 ["io.serializations"]
                                    merge-serialization-strings
                                    (get m1 "io.serializations"))]
              (conj (or m1 {}) m2)))
          maps))
