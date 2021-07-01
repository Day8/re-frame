### Question

How do I access the value of a subscription from within a Reagent or JavaScript event (such as on-click)?

### The Wrong Way

You should NOT do this:
```clj
[:input {:type "button" 
         :value "Click me!"
         :on-click #(let [sub-val  @(subscribe [:something])]   ;;<--- Eeek
       ....)}]
```

because that `subscribe`:
1. might create a memory leak (the subscription might not be "freed")
2. makes the event handler impure (it grabs a global value)


### The Better Way

If your `on-click` function requires access to re-frame data, the first thing you should do is convert it to a re-frame event and dispatch that instead.

```clj
(re-frame.core/reg-event-fx
  :input/on-click
  (fn [coeffects event]
    ....
    ))
```

```clj
[:input {:type "button" 
        :value "Click me!"
        :on-click #(dispatch [:input/on-click %])}]
```

Since you have access to the `coeffects` now, you may be able to get the data you need directly rather than using a subscription.

If you still need to use a subscription, then visit [How can I use a subscription in an Event Handler](UseASubscriptionInAnEventHandler.md) to see how to inject the subscription value directly into your `coeffects` and avoid the possibility of a memory leak.

***

Up:  [FAQ Index](README.md)&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;


<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->
<!-- END doctoc generated TOC please keep comment here to allow auto update -->




