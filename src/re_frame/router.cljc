(ns re-frame.router
  (:require [re-frame.events  :refer [handle]]
            [re-frame.interop :refer [after-render empty-queue next-tick]]
            [re-frame.loggers :refer [console]]
            [re-frame.trace   :as trace :include-macros true]))


;; -- Router Loop ------------------------------------------------------------
;;
;; A call to "re-frame.core/dispatch" places an event on a queue for processing.
;; A short time later, the handler registered to handle this event will be run.
;; What follows is the implementation of this process.
;;
;; The task is to process queued events in a perpetual loop, one after
;; the other, FIFO, calling the registered event-handler for each, being idle when
;; there are no events, and firing up when one arrives.
;;
;; But browsers only have a single thread of control and we must be
;; careful to not hog the CPU. When processing events one after another, we
;; must regularly hand back control to the browser, so it can redraw, process
;; websockets, etc. But not too regularly! If we are in a de-focused browser
;; tab, our app will be CPU throttled. Each time we get back control, we have
;; to process all queued events, or else something like a bursty websocket
;; (producing events) might overwhelm the queue. So there's a balance.
;;
;; The processing/handling of an event happens "asynchronously" sometime after
;; that event was enqueued via "dispatch". The original implementation of this router loop
;; used `core.async`. As a result, it was fairly simple, and it mostly worked,
;; but it did not give enough control. So now we hand-roll our own,
;; finite-state-machine and all.
;;
;; In what follows, the strategy is this:
;;   - maintain a FIFO queue of `dispatched` events.
;;   - when a new event arrives, "schedule" processing of this queue using
;;     goog.async.nextTick, which means it will happen "very soon".
;;   - when processing events, one after the other, do ALL the currently
;;     queued events. Don't stop. Don't yield to the browser. Hog that CPU.
;;   - but if any new events are dispatched during this cycle of processing,
;;     don't do them immediately. Leave them queued. Yield first to the browser,
;;     and do these new events in the next processing cycle. That way we drain
;;     the queue up to a point, but we never hog the CPU forever. In
;;     particular, we handle the case where handling one event will beget
;;     another event. The freshly begotten event will be handled next cycle,
;;     with yielding in-between.
;;   - In some cases, an event should not be handled until after the GUI has been
;;     updated, i.e., after the next Reagent animation frame. In such a case,
;;     the event should be dispatched with :flush-dom metadata like this:
;;       (dispatch ^:flush-dom [:event-id other params])
;;     Such an event will temporarily block all further processing because
;;     events are processed sequentially: we handle one event completely
;;     before we handle the ones behind it.
;;
;; Implementation notes:
;;   - queue processing can be in a number of states: scheduled, running, paused
;;     etc. So it is modeled as a Finite State Machine.
;;     See "-fsm-trigger" (below) for the states and transitions.
;;   - the scheduling is done via "goog.async.nextTick" which is pretty quick
;;   - when the event has :flush-dom metadata we schedule via
;;       "reagent.core.after-render"
;;     which will run event processing after the next Reagent animation frame.
;;

;; Events can have metadata which says to pause event processing.
;; event metadata -> "run later" functions
(def later-fns
  {:flush-dom (fn [f] (after-render #(next-tick f)))   ;; one tick after the end of the next animation frame
   :yield     next-tick})               ;; almost immediately

(defn rebuild-queue
  [runnables queue]
  (let [first-event          (peek runnables)
        meta-of-first-event  (meta first-event)
        keys-to-remove       (keys later-fns)
        without-later-fns    (reduce #(dissoc %1 %2) keys-to-remove meta-of-first-event)
        new-first-event      (with-meta first-event without-later-fns)]
    (into #queue [new-first-event] (pop runnables) queue)))

;; Event Queue Abstraction
(defprotocol IEventQueue

  ;; -- API
  (push [this event])
  (add-post-event-callback [this id callback-fn])
  (remove-post-event-callback [this id])
  (purge [this])

  ;; -- Implementation via a Finite State Machine
  (-fsm-trigger [this trigger arg])

  ;; -- Finite State Machine actions
  (-add-event [this event])
  (-process-event [this])
  (-run-next-tick [this])
  (-run-queue [this])
  (-exception [this ex])
  (-pause [this later-fn])
  (-resume [this])
  (-call-post-event-callbacks [this event]))


;; Concrete implementation of IEventQueue
(deftype EventQueue [#?(:cljs ^:mutable fsm-state               :clj ^:volatile-mutable fsm-state)
                     #?(:cljs ^:mutable queue                   :clj ^:volatile-mutable queue)
                     #?(:cljs ^:mutable post-event-callback-fns :clj ^:volatile-mutable post-event-callback-fns)]
  IEventQueue

  ;; -- API ------------------------------------------------------------------

  (push [this event]         ;; presumably called by dispatch
    (-fsm-trigger this :add-event event))

  ;; register a callback function which will be called after each event is processed
  (add-post-event-callback [_ id callback-fn]
    (if (contains? post-event-callback-fns id)
      (console :warn "re-frame: overwriting existing post event call back with id:" id))
    (->> (assoc post-event-callback-fns id callback-fn)
         (set! post-event-callback-fns)))

  (remove-post-event-callback [_ id]
    (if-not (contains? post-event-callback-fns id)
      (console :warn "re-frame: could not remove post event call back with id:" id)
      (->> (dissoc post-event-callback-fns id)
           (set! post-event-callback-fns))))

  (purge [_]
    (set! queue empty-queue))

  ;; -- FSM Implementation ---------------------------------------------------

  (-fsm-trigger
    [this trigger arg]

    ;; The following "case" implements the Finite State Machine.
    ;; Given a "trigger", and the existing FSM state, it computes the
    ;; new FSM state and the transition action (function).

    (locking this
      (trace/with-trace {:op-type ::fsm-trigger}
        (let [[new-fsm-state action-fn]
              (case [fsm-state trigger]

                ;; You should read the following "case" as:
                ;; [current-FSM-state trigger] -> [new-FSM-state action-fn]
                ;;
                ;; So, for example, the next line should be interpreted as:
                ;; if you are in state ":idle" and a trigger ":add-event"
                ;; happens, then move the FSM to state ":scheduled" and execute
                ;; that two-part "do" function.
                [:idle :add-event] [:scheduled #(do (-add-event this arg)
                                                    (-run-next-tick this))]

                ;; State: :scheduled  (the queue is scheduled to run, soon)
                [:scheduled :add-event] [:scheduled #(-add-event this arg)]
                [:scheduled :run-queue] [:running #(-run-queue this)]

                ;; State: :running (the queue is being processed one event after another)
                [:running :add-event] [:running #(-add-event this arg)]
                [:running :pause] [:paused #(-pause this arg)]
                [:running :exception] [:idle #(-exception this arg)]
                [:running :finish-run] (if (empty? queue)     ;; FSM guard
                                         [:idle]
                                         [:scheduled #(-run-next-tick this)])

                ;; State: :paused (:flush-dom metadata on an event has caused a temporary pause in processing)
                [:paused :add-event] [:paused #(-add-event this arg)]
                [:paused :resume] [:running #(-resume this)]

                (throw (ex-info (str "re-frame: router state transition not found. " fsm-state " " trigger)
                                {:fsm-state fsm-state, :trigger trigger})))]

          ;; The "case" above computed both the new FSM state, and the action. Now, make it happen.

          (trace/merge-trace! {:operation [fsm-state trigger]
                               :tags      {:current-state fsm-state
                                           :new-state     new-fsm-state}})
          (set! fsm-state new-fsm-state)
          (when action-fn (action-fn))))))

  (-add-event
    [_ event]
    (set! queue (conj queue event)))

  (-process-event
    [this event-v]
    (handle event-v)
    (-call-post-event-callbacks this event-v)

  (-run-next-tick
    [this]
    (next-tick #(-fsm-trigger this :run-queue nil)))

  ;; Process all the events currently in the queue, but not any new ones.
  ;; Be aware that events might have metadata which will pause processing.
  ;;
  ;; Work with a snapshot of the queue so that if an exception occurs during
  ;; event processing the real queue has already been purged.
  (-run-queue
    [this]
    (let [runnables queue] ;; snapshot the queue
      (purge this) ;; purge the queue
      (try
        (loop [runnables runnables]
          (when-not (empty? runnables)
            (if-let [later-fn (some later-fns (-> runnables peek meta keys))]  ;; any metadata which causes pausing?
              (do
                (set! queue (rebuild-queue runnables queue))
                (-fsm-trigger this :pause later-fn))
              (do (-process-event this (peek runnables))
                  (recur (pop runnables))))))
        (finally
          ;; An exception may have occurred during event processing leaving the fsm-state in running so we ensure
          ;; that it is always set back to :idle.
          (if (= fsm-state :running)
            (-fsm-trigger this :finish-run nil))))))

  (-exception
    [this ex]
    (purge this)   ;; purge the queue
    (throw ex))

  (-pause
    [this later-fn]
    (later-fn #(-fsm-trigger this :resume nil)))

  (-call-post-event-callbacks
    [_ event-v]
    (doseq [callback (vals post-event-callback-fns)]
      (callback event-v queue)))

  (-resume
    [this]
    (-run-queue this))

;; ---------------------------------------------------------------------------
;; Event Queue
;; When "dispatch" is called, the event is added into this event queue.  Later,
;;  the queue will "run" and the event will be "handled" by the registered function.
;;
(def event-queue (->EventQueue :idle empty-queue {}))


;; ---------------------------------------------------------------------------
;; Dispatching
;;

(defn dispatch
  [event]
  (if (nil? event)
      (throw (ex-info "re-frame: you called \"dispatch\" without an event vector." {}))
      (push event-queue event))
  nil)                                           ;; Ensure nil return. See https://github.com/day8/re-frame/wiki/Beware-Returning-False

(defn dispatch-sync
  [event-v]
  (handle event-v)
  (-call-post-event-callbacks event-queue event-v)  ;; slightly ugly hack. Run the registered post event callbacks.
  nil)                                              ;; Ensure nil return. See https://github.com/day8/re-frame/wiki/Beware-Returning-False
