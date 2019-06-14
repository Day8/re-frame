## The re-frame API

Orientation:
  1. The API is provided by `re-frame.core`: 
     - at some point, it would be worth your time [to browse it](/src/re_frame/core.cljc)
     - to use re-frame, you'll need to `require` it, perhaps like this ...
     ```clj
     (ns  my.namespace
       (:require [re-frame.core :as rf]))
                 
     ... now use rf/reg-event-fx or rf/subscribe
     ```
  2. The API is small. Writing an app, you use less than 10 API functions. Maybe just 5.
  3. There's no auto-generated docs [because of this problem](/src/re_frame/core.cljc#L23-L36) 
     but, as a substitute, 
     the links below take you to the doc-strings of often-used API functions.

## Doc Strings For The Significant API Functions 

The core API consists of: 
  - [dispatch](/src/re_frame/router.cljc#L229-L239) or [dispatch-sync](/src/re_frame/router.cljc#L247-L259).
  - [reg-event-db](/src/re_frame/core.cljc#L71-L80) or [reg-event-fx](/src/re_frame/core.cljc#L87-L97) 
  - [reg-sub](/src/re_frame/subs.cljc#L201-L309) and [subscribe](/src/re_frame/subs.cljc#L74-L115) working together

Occasionally, you'll need to use:  
  - [reg-fx](/src/re_frame/fx.cljc#L17-L40)
  - [reg-cofx](/src/re_frame/cofx.cljc#L14-L22) and [inject-cofx](/src/re_frame/cofx.cljc#L29-L80) working together
  - [reg-event-error-handler](/src/re_frame/core.cljc#L120-L131)
     
And, finally, there are the builtin Interceptors:
  - [path](/src/re_frame/std_interceptors.cljc#L152-L176)
  - [after](/src/re_frame/std_interceptors.cljc#L295-L316)
  - [debug](/src/re_frame/std_interceptors.cljc#L13-L38)
  - and browse [the others](/src/re_frame/std_interceptors.cljc)
  

## Built-in Effect Handlers

The following built-in effects are also a part of the API:  

#### :dispatch-later

`dispatch` one or more events after given delays. Expects a collection
of maps with two keys: `:ms` and `:dispatch`

usage:
```clj
{:dispatch-later [{:ms 200 :dispatch [:event-id "param"]}    
                  {:ms 100 :dispatch [:also :this :in :100ms]}]}
```

Which means: in 200ms do this: `(dispatch [:event-id "param"])` and in 100ms ...

Note: nil entries in the collection are ignored which means events can be added
conditionally:

```clj
{:dispatch-later [ (when (> 3 5) {:ms 200 :dispatch [:conditioned-out]})
                   {:ms 100 :dispatch [:another-one]}]}
```

*** 

#### :dispatch

`dispatch` one event. Expects a single vector.

usage:
```clj
{:dispatch [:event-id "param"] }
```

***
 
#### :dispatch-n

`dispatch` more than one event. Expects a collection events. 

usage:
```clj
{:dispatch-n (list [:do :all] [:three :of] [:these])}
```
Note 1: The events supplied will be dispatched in the order provided.
Note 2: nil events are ignored which means events can be added
conditionally:
```clj
{:dispatch-n (list (when (> 3 5) [:conditioned-out])
                   [:another-one])}
```

*** 
#### :deregister-event-handler

removes a previously registered event handler. Expects either a single id
(typically a keyword), or a seq of ids.

usage:
```clj
{:deregister-event-handler :my-id)}
```
or:
```clj
{:deregister-event-handler [:one-id :another-id]}
```
*** 
#### :db

reset! app-db with a new value. Expects a map. 

usage:
```clj
{:db  {:key1 value1 :key2 value2}}
```

*** 

Previous:  [Infographic: A re-frame Epoch](AnEpoch.md)&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
Up:  [Index](README.md)&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
Next: [Infographic: Event Processing](EventHandlingInfographic.md)&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;


<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->
<!-- END doctoc generated TOC please keep comment here to allow auto update -->
