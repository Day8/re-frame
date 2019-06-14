(ns re-frame.core
  (:require
    [re-frame.events           :as events]
    [re-frame.subs             :as subs]
    [re-frame.interop          :as interop]
    [re-frame.db               :as db]
    [re-frame.fx               :as fx]
    [re-frame.cofx             :as cofx]
    [re-frame.router           :as router]
    [re-frame.loggers          :as loggers]
    [re-frame.registrar        :as registrar]
    [re-frame.interceptor      :as interceptor]
    [re-frame.std-interceptors :as std-interceptors :refer [db-handler->interceptor
                                                             fx-handler->interceptor
                                                             ctx-handler->interceptor]]
    [clojure.set               :as set]))


;; -- API ---------------------------------------------------------------------
;;
;; This namespace represents the re-frame API
;;
;; Below, you'll see we've used this technique:
;;   (def  api-name-for-fn    deeper.namespace/where-the-defn-is)
;;
;; So, we promote a `defn` in a deeper namespace "up" to the API
;; via a `def` in this namespace.
;;
;; Turns out, this approach makes it hard:
;;   - to auto-generate API docs
;;   - for IDEs to provide code completion on functions in the API
;;
;; Which is annoying. But there are pros and cons and we haven't
;; yet revisited the decision.  To compensate, we've added more nudity
;; to the docs.
;;


;; -- dispatch ----------------------------------------------------------------
(def dispatch       router/dispatch)
(def dispatch-sync  router/dispatch-sync)


;; -- subscriptions -----------------------------------------------------------
(def reg-sub        subs/reg-sub)
(def subscribe      subs/subscribe)

(def clear-sub (partial registrar/clear-handlers subs/kind))  ;; think unreg-sub
(def clear-subscription-cache! subs/clear-subscription-cache!)

(defn reg-sub-raw
  "This is a low level, advanced function.  You should probably be
  using reg-sub instead.
  Docs in https://github.com/Day8/re-frame/blob/master/docs/SubscriptionFlow.md"
  [query-id handler-fn]
  (registrar/register-handler subs/kind query-id handler-fn))


;; -- effects -----------------------------------------------------------------
(def reg-fx      fx/reg-fx)
(def clear-fx    (partial registrar/clear-handlers fx/kind))  ;; think unreg-fx

;; -- coeffects ---------------------------------------------------------------
(def reg-cofx    cofx/reg-cofx)
(def inject-cofx cofx/inject-cofx)
(def clear-cofx (partial registrar/clear-handlers cofx/kind)) ;; think unreg-cofx


;; -- Events ------------------------------------------------------------------

(defn reg-event-db
  "Register the given event `handler` (function) for the given `id`. Optionally, provide
  an `interceptors` chain.
  `id` is typically a namespaced keyword  (but can be anything)
  `handler` is a function: (db event) -> db
  `interceptors` is a collection of interceptors. Will be flattened and nils removed.
  `handler` is wrapped in its own interceptor and added to the end of the interceptor
   chain, so that, in the end, only a chain is registered.
   Special effects and coeffects interceptors are added to the front of this
   chain."
  ([id handler]
    (reg-event-db id nil handler))
  ([id interceptors handler]
   (events/register id [cofx/inject-db fx/do-fx interceptors (db-handler->interceptor handler)])))


(defn reg-event-fx
  "Register the given event `handler` (function) for the given `id`. Optionally, provide
  an `interceptors` chain.
  `id` is typically a namespaced keyword  (but can be anything)
  `handler` is a function: (coeffects-map event-vector) -> effects-map
  `interceptors` is a collection of interceptors. Will be flattened and nils removed.
  `handler` is wrapped in its own interceptor and added to the end of the interceptor
   chain, so that, in the end, only a chain is registered.
   Special effects and coeffects interceptors are added to the front of the
   interceptor chain.  These interceptors inject the value of app-db into coeffects,
   and, later, action effects."
  ([id handler]
   (reg-event-fx id nil handler))
  ([id interceptors handler]
   (events/register id [cofx/inject-db fx/do-fx interceptors (fx-handler->interceptor handler)])))


(defn reg-event-ctx
  "Register the given event `handler` (function) for the given `id`. Optionally, provide
  an `interceptors` chain.
  `id` is typically a namespaced keyword  (but can be anything)
  `handler` is a function: (context-map event-vector) -> context-map

  This form of registration is almost never used. "
  ([id handler]
   (reg-event-ctx id nil handler))
  ([id interceptors handler]
   (events/register id [cofx/inject-db fx/do-fx interceptors (ctx-handler->interceptor handler)])))

(def clear-event (partial registrar/clear-handlers events/kind)) ;; think unreg-event-*

;; -- error handler ----------------------------------------------------------

(defn reg-event-error-handler
  "Register the given event error `handler` (function) that will catch unhandled exceptions
  thrown in the interceptors/handler chain.
  A single error handler can exist at a given time: a previously registered error handler
  would be overwritten by the new one provided to this function.
  The error handler receives an ExceptionInfo object with keys
  #{:direction   ;; :before or :after
    :interceptor ;; id of the interceptor where the original exception was thrown
    :exception   ;; original exception
   }"
  [handler]
  (registrar/register-handler :error :event-handler handler))

;; -- interceptors ------------------------------------------------------------

;; Standard interceptors.
;; Detailed docs on each in std-interceptors.cljs
(def debug       std-interceptors/debug)
(def path        std-interceptors/path)
(def enrich      std-interceptors/enrich)
(def trim-v      std-interceptors/trim-v)
(def after       std-interceptors/after)
(def on-changes  std-interceptors/on-changes)


;; Utility functions for creating your own interceptors
;;
;;  (def my-interceptor
;;     (->interceptor                ;; used to create an interceptor
;;       :id     :my-interceptor     ;; an id - decorative only
;;       :before (fn [context]                         ;; you normally want to change :coeffects
;;                  ... use get-coeffect  and assoc-coeffect
;;                       )
;;       :after  (fn [context]                         ;; you normally want to change :effects
;;                 (let [db (get-effect context :db)]  ;; (get-in context [:effects :db])
;;                   (assoc-effect context :http-ajax {...}])))))
;;
(def ->interceptor   interceptor/->interceptor)
(def get-coeffect    interceptor/get-coeffect)
(def assoc-coeffect  interceptor/assoc-coeffect)
(def get-effect      interceptor/get-effect)
(def assoc-effect    interceptor/assoc-effect)
(def enqueue         interceptor/enqueue)


;; --  logging ----------------------------------------------------------------
;; Internally, re-frame uses the logging functions: warn, log, error, group and groupEnd
;; By default, these functions map directly to the js/console implementations,
;; but you can override with your own fns (set or subset).
;; Example Usage:
;;   (defn my-fn [& args]  (post-it-somewhere (apply str args)))  ;; here is my alternative
;;   (re-frame.core/set-loggers!  {:warn my-fn :log my-fn})       ;; override the defaults with mine
(def set-loggers! loggers/set-loggers!)

;; If you are writing an extension to re-frame, like perhaps
;; an effects handler, you may want to use re-frame logging.
;;
;; usage: (console :error "Oh, dear God, it happened: " a-var " and " another)
;;        (console :warn "Possible breach of containment wall at: " dt)
(def console loggers/console)


;; -- unit testing ------------------------------------------------------------

(defn make-restore-fn
  "Checkpoints the state of re-frame and returns a function which, when
  later called, will restore re-frame to that checkpointed state.

  Checkpoint includes app-db, all registered handlers and all subscriptions.
  "
  []
  (let [handlers @registrar/kind->id->handler
        app-db   @db/app-db
				subs-cache @subs/query->reaction]
    (fn []
			;; call `dispose!` on all current subscriptions which
			;; didn't originally exist.
      (let [original-subs (set (vals subs-cache))
            current-subs  (set (vals @subs/query->reaction))]
        (doseq [sub (set/difference current-subs original-subs)]
          (interop/dispose! sub)))

      ;; Reset the atoms
      ;; We don't need to reset subs/query->reaction, as
      ;; disposing of the subs removes them from the cache anyway
      (reset! registrar/kind->id->handler handlers)
      (reset! db/app-db app-db)
      nil)))

(defn purge-event-queue
  "Remove all events queued for processing"
  []
  (router/purge re-frame.router/event-queue))

;; -- Event Processing Callbacks  ---------------------------------------------

(defn add-post-event-callback
  "Registers a function `f` to be called after each event is processed
   `f` will be called with two arguments:
    - `event`: a vector. The event just processed.
    - `queue`: a PersistentQueue, possibly empty, of events yet to be processed.

   This is useful in advanced cases like:
     - you are implementing a complex bootstrap pipeline
     - you want to create your own handling infrastructure, with perhaps multiple
       handlers for the one event, etc.  Hook in here.
     - libraries providing 'isomorphic javascript' rendering on  Nodejs or Nashorn.

  'id' is typically a keyword. Supplied at \"add time\" so it can subsequently
  be used at \"remove time\" to get rid of the right callback.
  "
  ([f]
   (add-post-event-callback f f))   ;; use f as its own identifier
  ([id f]
   (router/add-post-event-callback re-frame.router/event-queue id f)))


(defn remove-post-event-callback
  [id]
  (router/remove-post-event-callback re-frame.router/event-queue id))


;; --  Deprecation ------------------------------------------------------------
;; Assisting the v0.7.x ->  v0.8.x transition.
(defn register-handler
  [& args]
  (console :warn  "re-frame:  \"register-handler\" has been renamed \"reg-event-db\" (look for registration of" (str (first args)) ")")
  (apply reg-event-db args))

(defn register-sub
  [& args]
  (console :warn  "re-frame:  \"register-sub\" is deprecated. Use \"reg-sub-raw\" (look for registration of" (str (first args)) ")")
  (apply reg-sub-raw args))
