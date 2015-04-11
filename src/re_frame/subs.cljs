(ns re-frame.subs
 (:require
  [re-frame.db    :refer [app-db]]
  [re-frame.utils :refer [first-in-vector warn]]))


;; maps from handler-id to handler-fn
(def ^:private key->fn (atom {}))

(defn clear-subs!
  "Unregister all subscriptions.
  May be useful for those applications using the reloaded workflow."
  []
  (reset! key->fn {}))

(defn register
  "register a hander function for an id"
  [key-v handler-fn]
  (if (contains? @key->fn key-v)
    (warn "re-frame: overwriting subscription-handler for: " key-v))   ;; allow it, but warn.
  (swap! key->fn assoc key-v handler-fn))


(defn subscribe
  "returns a reagent/reaction which observes a part of app-db"
  [v]
  (let [key-v       (first-in-vector v)
        handler-fn  (get @key->fn key-v)]
    (if (nil? handler-fn)
      (warn "re-frame: no subscription handler registered for: \"" key-v "\".  Returning a nil subscription.")
      (handler-fn app-db v))))
