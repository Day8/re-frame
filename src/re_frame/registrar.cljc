(ns re-frame.registrar
  "In many places, re-frame asks you to associate an `id` (keyword)
  with a `handler` (function).  This namespace contains the
  central registry of such associations."
  (:require  [re-frame.interop :refer [debug-enabled?]]
             [re-frame.loggers :refer [console]]))


;; kinds of handlers
(def kinds #{:event :fx :cofx :sub :error})

;; This atom contains a register of all handlers.
;; Contains a two layer map, keyed first by `kind` (of handler), and then `id` of handler.
;; Leaf nodes are handlers.
(def kind->id->handler  (atom {}))


(defn get-handler

  ([kind]
   (get @kind->id->handler kind))

  ([kind id]
   (-> (get @kind->id->handler kind)
       (get id)))

  ([kind id required?]
   (let [handler (get-handler kind id)]
     (when debug-enabled?                          ;; This is in a separate `when` so Closure DCE can run ...
       (when (and required? (nil? handler))        ;; ...otherwise you'd need to type-hint the `and` with a ^boolean for DCE.
         (console :error "re-frame: no" (str kind) "handler registered for:" id)))
     handler)))


(defn register-handler
  [kind id handler-fn]
  (when debug-enabled?                                       ;; This is in a separate when so Closure DCE can run
    (when (get-handler kind id false)
      (console :warn "re-frame: overwriting" (str kind) "handler for:" id)))   ;; allow it, but warn. Happens on figwheel reloads.
  (swap! kind->id->handler assoc-in [kind id] handler-fn)
  handler-fn)    ;; note: returns the just registered handler


(defn clear-handlers
  ([]            ;; clear all kinds
   (reset! kind->id->handler {}))

  ([kind]        ;; clear all handlers for this kind
   (assert (kinds kind))
   (swap! kind->id->handler dissoc kind))

  ([kind id]     ;; clear a single handler for a kind
   (assert (kinds kind))
   (if (get-handler kind id)
     (swap! kind->id->handler update-in [kind] dissoc id)
     (console :warn "re-frame: can't clear" (str kind) "handler for" (str id ". Handler not found.")))))
