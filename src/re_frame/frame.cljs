(ns re-frame.frame
  (:require [re-frame.utils :refer [get-event-id get-subscription-id simple-inflection frame-summary-description]]
            [re-frame.logging :refer [log warn error default-loggers]]))

; re-frame meat reimplemented in terms of pure functions (with help of transducers)

(defn frame-transducer-factory [frame]                      ; <- returns a function which is able to build transducers
  (fn [db-selector]                                         ; <- returns a transducer parametrized with db-selector
    (fn [reducing-fn]                                       ; <- this is a transducer
      (fn
        ([] (reducing-fn))                                  ; transduction init, see [1]
        ([result] (reducing-fn result))                     ; transduction completion, see [1]
        ([state event]                                      ; transduction step, see [1]
         (let [event-id (get-event-id event)
               handler-fn (event-id (:handlers frame))]
           (if (nil? handler-fn)
             (do
               (error frame "re-frame: no event handler registered for: \"" event-id "\". Ignoring.")
               state)
             (let [old-db (db-selector state)               ; db-selector is responsible for retrieving actual db from current state
                   new-db (handler-fn old-db event)]        ; calls selected handler (including all composed middlewares)
               (if (nil? new-db)                            ; TODO: this test should be optional, there could be valid use-cases for nil db
                 (do
                   (error frame "re-frame: your handler returned nil. It should return the new db state. Ignoring.")
                   state)
                 (reducing-fn state new-db))))))))))        ; reducing function prepares new transduction state

; see http://clojure.org/transducers[1]
(defn get-frame-transducer
  "Returns a transducer: state, event -> state.
This transducer resolves event-id, selects matching handler and calls it with old db state to produce a new db state.
Transducer must have no knowledge of underlying app-db-atom, reagent, core.async or anything else out there.
All business of queuing events and application of their effects must be baked into reducing-fn and provided from outside
by the process doing actual transduction. See event processing helpers below for an example."
  ([frame] (get-frame-transducer frame identity))
  ([frame db-selector] ((frame-transducer-factory frame) db-selector)))

(defprotocol IFrame)

(defrecord Frame [handlers subscriptions loggers]
  IFrame)

(extend-protocol IPrintWithWriter
  Frame
  (-pr-writer [this writer opts]
    (-write writer (str "#<Frame " (frame-summary-description this)))
    (-write writer " | handlers ")
    (pr-writer (keys (.-handlers this)) writer opts)
    (-write writer " | subscriptions ")
    (pr-writer (keys (.-subscriptions this)) writer opts)
    (-write writer ">")))

(defn make-frame
  "Constructs an independent frame instance."
  ([] (make-frame nil))
  ([handlers] (make-frame handlers nil))
  ([handlers subscriptions] (make-frame handlers subscriptions default-loggers))
  ([handlers subscriptions loggers]
   {:pre [(or (map? handlers) (nil? handlers))
          (or (map? subscriptions) (nil? subscriptions))
          (map? loggers)]}
   (Frame. handlers subscriptions loggers)))

(defn subscribe
  "Returns a reagent/reaction which observes state."
  [frame subscription-spec]
  (let [subscription-id (get-subscription-id subscription-spec)
        handler-fn (get-in frame [:subscriptions subscription-id])]
    (if (nil? handler-fn)
      (do
        (error frame "re-frame: no subscription handler registered for: \"" subscription-id "\".  Returning a nil subscription.")
        nil)
      (handler-fn subscription-spec))))

(defn register-subscription-handler
  "Registers a subscription handler function for an id."
  [frame subscription-id handler-fn]
  (let [existing-subscriptions (get frame :subscriptions)]
    (if (contains? existing-subscriptions subscription-id)
      (warn frame "re-frame: overwriting subscription handler for: " subscription-id)))
  (assoc-in frame [:subscriptions subscription-id] handler-fn))

(defn unregister-subscription-handler
  "Unregisters subscription handler function previously registered via register-subscription-handler."
  [frame subscription-id]
  (let [existing-subscriptions (get frame :subscriptions)]
    (if-not (contains? existing-subscriptions subscription-id)
      (warn frame "re-frame: unregistering subscription handler \"" subscription-id "\" which does not exist.")))
  (update frame :subscriptions dissoc subscription-id))

(defn clear-subscription-handlers
  "Unregisters all subscription handlers."
  [frame]
  (assoc frame :subscriptions nil))

(defn register-event-handler
  "Register a handler for an event."
  ([frame event-id handler-fn]
   (let [existing-handlers (get frame :handlers)]
     (if (contains? existing-handlers event-id)
       (warn frame "re-frame: overwriting an event handler for: " event-id))
     (assoc-in frame [:handlers event-id] handler-fn))))

(defn unregister-event-handler
  "Unregisters event handler function previously registered via register-event-handler."
  [frame event-id]
  (let [existing-handlers (get frame :handlers)]
    (if (contains? existing-handlers event-id)
      (warn frame "re-frame: unregistering event handler \"" event-id "\" which does not exist.")))
  (update frame :handlers dissoc event-id))

(defn clear-event-handlers
  "Unregisters all event handlers."
  [frame]
  (assoc frame :handlers nil))

(defn set-loggers
  "Resets loggers."
  [frame new-loggers]
  (assoc frame :loggers new-loggers))

;; -- event processing -----------------------------------------------------------------------

(defn process-events-on-atom [frame db-atom events]
  (let [reducing-fn (fn
                      ([db-atom] db-atom)                   ; completion
                      ([db-atom new-db]                     ; apply new-state to atom
                       (let [old-db @db-atom]
                         (if-not (identical? old-db new-db)
                           (reset! db-atom new-db)))
                       db-atom))
        xform (get-frame-transducer frame deref)]
    (transduce xform reducing-fn db-atom events)))

(defn process-events-on-atom-with-coallesced-write [frame db-atom events]
  (let [old-db @db-atom
        reducing-fn (fn
                      ([final-db]
                       (if-not (identical? old-db final-db)
                         (reset! db-atom final-db)))        ; completion
                      ([_old-db new-db] new-db))            ; simply carry-on with new-state
        xform (get-frame-transducer frame identity)]
    (transduce xform reducing-fn old-db events)))

(defn process-event [frame old-db event]
  (let [reducing-fn (fn [_old-state new-state] new-state)
        xform (get-frame-transducer frame identity)]
    ((xform reducing-fn) old-db event)))

(defn process-event-on-atom [frame db-atom event]
  (let [old-db @db-atom
        new-db (process-event frame old-db event)]
    (if-not (identical? old-db new-db)
      (reset! db-atom new-db))))
