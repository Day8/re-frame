(ns re-frame.subs
 (:require
   [re-frame.db        :refer [app-db]]
   [re-frame.interop   :refer [add-on-dispose! debug-enabled? make-reaction ratom? deref? dispose! reagent-id]]
   [re-frame.loggers   :refer [console]]
   [re-frame.utils     :refer [first-in-vector]]
   [re-frame.registrar :refer [get-handler clear-handlers register-handler]]
   [re-frame.trace     :as trace :include-macros true]))

(def kind :sub)
(assert (re-frame.registrar/kinds kind))

;; -- cache -------------------------------------------------------------------
;;
;; De-duplicate subscriptions. If two or more equal subscriptions
;; are concurrently active, we want only one handler running.
;; Two subscriptions are "equal" if their query vectors test "=".
(def query->reaction (atom {}))

(defn clear-subscription-cache!
  "calls `on-dispose` for each cached item, 
   which will cause the value to be removed from the cache" 
  []
  (doseq [[k rxn] @query->reaction]
    (dispose! rxn))
  (if (not-empty @query->reaction)
    (console :warn "re-frame: The subscription cache isn't empty after being cleared")))

(defn clear-all-handlers!
  "Unregisters all existing subscription handlers"
  []
  (clear-handlers kind)
  (clear-subscription-cache!))

(defn cache-and-return
  "cache the reaction r"
  [query-v dynv r]
  (let [cache-key [query-v dynv]]
    ;; when this reaction is no longer being used, remove it from the cache
    (add-on-dispose! r #(trace/with-trace {:operation (first-in-vector query-v)
                                           :op-type   :sub/dispose
                                           :tags      {:query-v  query-v
                                                       :reaction (reagent-id r)}}
                                          (swap! query->reaction
                                                 (fn [query-cache]
                                                   (if (and (contains? query-cache cache-key) (identical? r (get query-cache cache-key)))
                                                     (dissoc query-cache cache-key)
                                                     query-cache)))))
    ;; cache this reaction, so it can be used to deduplicate other, later "=" subscriptions
    (swap! query->reaction (fn [query-cache]
                             (when debug-enabled?
                               (when (contains? query-cache cache-key)
                                 (console :warn "re-frame: Adding a new subscription to the cache while there is an existing subscription in the cache" cache-key)))
                             (assoc query-cache cache-key r)))
    (trace/merge-trace! {:tags {:reaction (reagent-id r)}})
    r)) ;; return the actual reaction

(defn cache-lookup
  ([query-v]
   (cache-lookup query-v []))
  ([query-v dyn-v]
   (get @query->reaction [query-v dyn-v])))


;; -- subscribe ---------------------------------------------------------------

(defn subscribe
  ([query]
   (trace/with-trace {:operation (first-in-vector query)
                      :op-type   :sub/create
                      :tags      {:query-v query}}
     (if-let [cached (cache-lookup query)]
       (do
         (trace/merge-trace! {:tags {:cached?  true
                                     :reaction (reagent-id cached)}})
         cached)

       (let [query-id   (first-in-vector query)
             handler-fn (get-handler kind query-id)]
         (trace/merge-trace! {:tags {:cached? false}})
         (if (nil? handler-fn)
           (do (trace/merge-trace! {:error true})
               (console :error (str "re-frame: no subscription handler registered for: " query-id ". Returning a nil subscription.")))
           (cache-and-return query [] (handler-fn app-db query)))))))

  ([query dynv]
   (trace/with-trace {:operation (first-in-vector query)
                      :op-type   :sub/create
                      :tags      {:query-v query
                                  :dyn-v   dynv}}
     (if-let [cached (cache-lookup query dynv)]
       (do
         (trace/merge-trace! {:tags {:cached?  true
                                     :reaction (reagent-id cached)}})
         cached)
       (let [query-id   (first-in-vector query)
             handler-fn (get-handler kind query-id)]
         (trace/merge-trace! {:tags {:cached? false}})
         (when debug-enabled?
           (when-let [not-reactive (not-empty (remove ratom? dynv))]
             (console :warn "re-frame: your subscription's dynamic parameters that don't implement IReactiveAtom:" not-reactive)))
         (if (nil? handler-fn)
           (do (trace/merge-trace! {:error true})
               (console :error (str "re-frame: no subscription handler registered for: " query-id ". Returning a nil subscription.")))
           (let [dyn-vals (make-reaction (fn [] (mapv deref dynv)))
                 sub      (make-reaction (fn [] (handler-fn app-db query @dyn-vals)))]
             ;; handler-fn returns a reaction which is then wrapped in the sub reaction
             ;; need to double deref it to get to the actual value.
             ;(console :log "Subscription created: " v dynv)
             (cache-and-return query dynv (make-reaction (fn [] @@sub))))))))))

;; -- reg-sub -----------------------------------------------------------------

(defn- map-vals
  "Returns a new version of 'm' in which 'f' has been applied to each value.
  (map-vals inc {:a 4, :b 2}) => {:a 5, :b 3}"
  [f m]
  (into (empty m)
        (map (fn [[k v]] [k (f v)]))
        m))

(defn map-signals
  "Runs f over signals. Signals may take several
  forms, this function handles all of them."
  [f signals]
  (cond
    (sequential? signals) (map f signals)
    (map? signals) (map-vals f signals)
    (deref? signals) (f signals)
    :else '()))

(defn to-seq
  "Coerces x to a seq if it isn't one already"
  [x]
  (if (sequential? x)
    x
    (list x)))

(defn- deref-input-signals
  [signals query-id]
  (let [dereffed-signals (map-signals deref signals)]
    (cond
      (sequential? signals) (map deref signals)
      (map? signals) (map-vals deref signals)
      (deref? signals) (deref signals)
      :else (console :error "re-frame: in the reg-sub for" query-id ", the input-signals function returns:" signals))
    (trace/merge-trace! {:tags {:input-signals (doall (to-seq (map-signals reagent-id signals)))}})
    dereffed-signals))

(defn reg-sub
  [query-id & args]
  (let [err-header       (str "re-frame: reg-sub for " query-id ", ")
        [input-args      ;; may be empty, or one signal fn, or pairs of  :<- / vector
         computation-fn] (let [[op f :as comp-f] (take-last 2 args)]
                           (if (or (= 1 (count comp-f))
                                   (fn? op)
                                   (vector? op))
                             [(butlast args) (last args)]
                             (let [args (drop-last 2 args)]
                               (case op
                                 ;; return a function that calls the computation fn
                                 ;;  on the input signal, removing the query vector
                                 :->
                                 [args (fn [db _]
                                         (f db))]
                                 ;; return a function that calls the computation fn
                                 ;;  on the input signal and the data in the query vector
                                 ;;  that is not the query-id
                                 :=>
                                 [args (fn [db [_ & qs]]
                                         (apply f db qs))]
                                 ;; an incorrect keyword was passed
                                 (console :error err-header "expected :-> or :=> as second to last argument, got:" op)))))
        inputs-fn      (case (count input-args)
                         ;; no `inputs` function provided - give the default
                         0 (fn
                             ([_] app-db)
                             ([_ _] app-db))

                         ;; a single `inputs` fn
                         1 (let [f (first input-args)]
                             (when-not (fn? f)
                               (console :error err-header "2nd argument expected to be an inputs function, got:" f))
                             f)

                         ;; one sugar pair
                         2 (let [[marker vec] input-args]
                             (when-not (= :<- marker)
                               (console :error err-header "expected :<-, got:" marker))
                             (fn inp-fn
                               ([_] (subscribe vec))
                               ([_ _] (subscribe vec))))

                         ;; multiple sugar pairs
                         (let [pairs   (partition 2 input-args)
                               markers (map first pairs)
                               vecs    (map second pairs)]
                           (when-not (and (every? #{:<-} markers) (every? vector? vecs))
                             (console :error err-header "expected pairs of :<- and vectors, got:" pairs))
                           (fn inp-fn
                             ([_] (map subscribe vecs))
                             ([_ _] (map subscribe vecs)))))]
    (register-handler
      kind
      query-id
      (fn subs-handler-fn
        ([db query-vec]
         (let [subscriptions (inputs-fn query-vec nil)
               reaction-id   (atom nil)
               reaction      (make-reaction
                               (fn []
                                 (trace/with-trace {:operation (first-in-vector query-vec)
                                                    :op-type   :sub/run
                                                    :tags      {:query-v    query-vec
                                                                :reaction   @reaction-id}}
                                                   (let [subscription (computation-fn (deref-input-signals subscriptions query-id) query-vec)]
                                                     (trace/merge-trace! {:tags {:value subscription}})
                                                     subscription))))]

           (reset! reaction-id (reagent-id reaction))
           reaction))
        ([db query-vec dyn-vec]
         (let [subscriptions (inputs-fn query-vec dyn-vec)
               reaction-id   (atom nil)
               reaction      (make-reaction
                               (fn []
                                 (trace/with-trace {:operation (first-in-vector query-vec)
                                                    :op-type   :sub/run
                                                    :tags      {:query-v   query-vec
                                                                :dyn-v     dyn-vec
                                                                :reaction  @reaction-id}}
                                                   (let [subscription (computation-fn (deref-input-signals subscriptions query-id) query-vec dyn-vec)]
                                                     (trace/merge-trace! {:tags {:value subscription}})
                                                     subscription))))]

           (reset! reaction-id (reagent-id reaction))
           reaction))))))
