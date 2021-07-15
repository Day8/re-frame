(ns re-frame.subs-test
  (:require [cljs.test         :as test :refer-macros [is deftest testing]]
            [reagent.ratom     :as r :refer-macros [reaction]]
            [re-frame.subs     :as subs]
            [re-frame.db       :as db]
            [re-frame.core     :as re-frame]))

(test/use-fixtures :each {:before (fn [] (subs/clear-all-handlers!))})

;=====test basic subscriptions  ======

(deftest test-reg-sub
  (re-frame/reg-sub-raw
    :test-sub
    (fn [db [_]] (reaction (deref db))))

  (let [test-sub (subs/subscribe [:test-sub])]
    (is (= @db/app-db @test-sub))
    (reset! db/app-db 1)
    (is (= 1 @test-sub))))

(deftest test-chained-subs
  (re-frame/reg-sub-raw
    :a-sub
    (fn [db [_]] (reaction (:a @db))))

  (re-frame/reg-sub-raw
    :b-sub
    (fn [db [_]] (reaction (:b @db))))

  (re-frame/reg-sub-raw
    :a-b-sub
    (fn [db [_]]
      (let [a (subs/subscribe [:a-sub])
            b (subs/subscribe [:b-sub])]
        (reaction {:a @a :b @b}))))

  (let [test-sub (subs/subscribe [:a-b-sub])]
    (reset! db/app-db {:a 1 :b 2})
    (is (= {:a 1 :b 2} @test-sub))
    (swap! db/app-db assoc :b 3)
    (is (= {:a 1 :b 3} @test-sub))))

(deftest test-sub-parameters
  (re-frame/reg-sub-raw
    :test-sub
    (fn [db [_ b]] (reaction [(:a @db) b])))

  (let [test-sub (subs/subscribe [:test-sub :c])]
    (reset! db/app-db {:a 1 :b 2})
    (is (= [1 :c] @test-sub))))


(deftest test-sub-chained-parameters
  (re-frame/reg-sub-raw
    :a-sub
    (fn [db [_ a]] (reaction [(:a @db) a])))

  (re-frame/reg-sub-raw
    :b-sub
    (fn [db [_ b]] (reaction [(:b @db) b])))

  (re-frame/reg-sub-raw
    :a-b-sub
    (fn [db [_ c]]
      (let [a (subs/subscribe [:a-sub c])
            b (subs/subscribe [:b-sub c])]
        (reaction {:a @a :b @b}))))

  (let [test-sub (subs/subscribe [:a-b-sub :c])]
    (reset! db/app-db {:a 1 :b 2})
    (is (= {:a [1 :c], :b [2 :c]} @test-sub))))

(deftest test-nonexistent-sub
  (is (nil? (re-frame/subscribe [:non-existence]))))

;============== test cached-subs ================
(def side-effect-atom (atom 0))

(deftest test-cached-subscriptions
  (reset! side-effect-atom 0)

  (re-frame/reg-sub-raw
    :side-effecting-handler
    (fn side-effect
      [db [_] [_]]
      (swap! side-effect-atom inc)
      (reaction @db)))

 (let [test-sub (subs/subscribe [:side-effecting-handler])]
    (reset! db/app-db :test)
    (is (= :test @test-sub))
    (is (= @side-effect-atom 1))
    (subs/subscribe [:side-effecting-handler])  ;; this should be handled by cache
    (is (= @side-effect-atom 1))
    (subs/subscribe [:side-effecting-handler :a]) ;; should fire again because of the param
    (is (= @side-effect-atom 2))
    (subs/subscribe [:side-effecting-handler :a]) ;; this should be handled by cache
    (is (= @side-effect-atom 2))))

;============== test clear-subscription-cache! ================

(deftest test-clear-subscription-cache!
  (re-frame/reg-sub
   :clear-subscription-cache!
   (fn clear-subs-cache [db _] 1))

  (testing "cold cache"
    (is (nil? (subs/cache-lookup [:clear-subscription-cache!]))))
  (testing "cache miss"
    (is (= 1 @(subs/subscribe [:clear-subscription-cache!])))
    (is (some? (subs/cache-lookup [:clear-subscription-cache!]))))
  (testing "clearing"
    (subs/clear-subscription-cache!)
    (is (nil? (subs/cache-lookup [:clear-subscription-cache!])))))

;============== test register-pure macros ================

(deftest test-reg-sub-macro
  (subs/reg-sub
    :test-sub
    (fn [db [_]] db))

  (let [test-sub (subs/subscribe [:test-sub])]
    (is (= @db/app-db @test-sub))
    (reset! db/app-db 1)
    (is (= 1 @test-sub))))

(deftest test-reg-sub-macro-singleton
  (subs/reg-sub
    :a-sub
    (fn [db [_]] (:a db)))

  (subs/reg-sub
    :a-b-sub
    (fn [_ _ _]
      (subs/subscribe [:a-sub]))
    (fn [a [_]]
      {:a a}))

  (let [test-sub (subs/subscribe [:a-b-sub])]
    (reset! db/app-db {:a 1 :b 2})
    (is (= {:a 1} @test-sub))
    (swap! db/app-db assoc :b 3)
    (is (= {:a 1} @test-sub))))

(deftest test-reg-sub-macro-vector
  (subs/reg-sub
    :a-sub
    (fn [db [_]] (:a db)))

  (subs/reg-sub
    :b-sub
    (fn [db [_]] (:b db)))

  (subs/reg-sub
    :a-b-sub
    (fn [_ _ _]
      [(subs/subscribe [:a-sub])
       (subs/subscribe [:b-sub])])
    (fn [[a b] [_]]
      {:a a :b b}))

  (let [test-sub (subs/subscribe [:a-b-sub])]
    (reset! db/app-db {:a 1 :b 2})
    (is (= {:a 1 :b 2} @test-sub))
    (swap! db/app-db assoc :b 3)
    (is (= {:a 1 :b 3} @test-sub))))

(deftest test-reg-sub-macro-map
  (subs/reg-sub
    :a-sub
    (fn [db [_]] (:a db)))

  (subs/reg-sub
    :b-sub
    (fn [db [_]] (:b db)))

  (subs/reg-sub
    :a-b-sub
    (fn [_ _ _]
      {:a (subs/subscribe [:a-sub])
       :b (subs/subscribe [:b-sub])})
    (fn [{:keys [a b]} [_]]
      {:a a :b b}))

  (let [test-sub (subs/subscribe [:a-b-sub])]
    (reset! db/app-db {:a 1 :b 2})
    (is (= {:a 1 :b 2} @test-sub))
    (swap! db/app-db assoc :b 3)
    (is (= {:a 1 :b 3} @test-sub))))

(deftest test-sub-macro-parameters
  (subs/reg-sub
    :test-sub
    (fn [db [_ b]] [(:a db) b]))

  (let [test-sub (subs/subscribe [:test-sub :c])]
    (reset! db/app-db {:a 1 :b 2})
    (is (= [1 :c] @test-sub))))

(deftest test-sub-macros-chained-parameters
  (subs/reg-sub
    :a-sub
    (fn [db [_ a]] [(:a db) a]))

  (subs/reg-sub
    :b-sub
    (fn [db [_ b]] [(:b db) b]))

  (subs/reg-sub
    :a-b-sub
    (fn [[_ c] _]
      [(subs/subscribe [:a-sub c])
       (subs/subscribe [:b-sub c])])
    (fn [[a b] [_ c]] {:a a :b b}))

  (let [test-sub (subs/subscribe [:a-b-sub :c])]
    (reset! db/app-db {:a 1 :b 2})
    (is (= {:a [1 :c] :b [2 :c]} @test-sub))))

(deftest test-sub-macros-<-
  "test the syntactical sugar"
  (subs/reg-sub
    :a-sub
    (fn [db [_]] (:a db)))

  (subs/reg-sub
        :a-b-sub
        :<- [:a-sub]
        (fn [a [_]] {:a a}))

  (let [test-sub (subs/subscribe [:a-b-sub])]
    (reset! db/app-db {:a 1 :b 2})
    (is (= {:a 1} @test-sub))))

(deftest test-sub-macros-chained-parameters-<-
  "test the syntactical sugar"
  (subs/reg-sub
    :a-sub
    (fn [db [_]] (:a db)))

  (subs/reg-sub
    :b-sub
    (fn [db [_]] (:b db)))

  (subs/reg-sub
        :a-b-sub
        :<- [:a-sub]
        :<- [:b-sub]
        (fn [[a b] [_ c]] {:a a :b b}))

  (let [test-sub (subs/subscribe [:a-b-sub :c])]
    (reset! db/app-db {:a 1 :b 2})
    (is (= {:a 1 :b 2} @test-sub))))

(deftest test-sub-macros-->
  "test the syntactical sugar for input signal"
  (subs/reg-sub
   :a-sub
   :-> :a)

  (subs/reg-sub
   :b-sub
   :-> :b)

  (subs/reg-sub
   :c-sub
   :-> :c)

  (subs/reg-sub
   :d-sub
   :-> :d)

  (subs/reg-sub
   :d-first-sub
   :<- [:d-sub]
   :-> first)

  ;; variant of :d-first-sub without an input parameter
  (subs/reg-sub
   :e-first-sub
   :-> (comp first :e))

  ;; test for equality
  (subs/reg-sub
   :c-foo?-sub
   :<- [:c-sub]
   :-> #{:foo})

  (subs/reg-sub
   :a-b-sub
   :<- [:a-sub]
   :<- [:b-sub]
   :-> (partial zipmap [:a :b]))

  (let [test-sub   (subs/subscribe [:a-b-sub])
        test-sub-c (subs/subscribe [:c-foo?-sub])
        test-sub-d (subs/subscribe [:d-first-sub])
        test-sub-e (subs/subscribe [:e-first-sub])]
    (is (= nil @test-sub-c))
    (reset! db/app-db {:a 1 :b 2 :c :foo :d [1 2] :e [3 4]})
    (is (= {:a 1 :b 2} @test-sub))
    (is (= :foo @test-sub-c))
    (is (= 1 @test-sub-d))
    (is (= 3 @test-sub-e))))

(deftest test-sub-macros-=>
  "test the syntactical sugar for input signals and query vector arguments"
  (subs/reg-sub
   :a-sub
   :-> :a)

  (subs/reg-sub
   :b-sub
   :-> :b)

  (subs/reg-sub
   :test-a-sub
   :<- [:a-sub]
   :=> vector)

  ;; test for equality of input signal and query parameter
  (subs/reg-sub
   :test-b-sub
   :<- [:b-sub]
   :=> =)
  
  (let [test-a-sub (subs/subscribe [:test-a-sub :c])
        test-b-sub (subs/subscribe [:test-b-sub 2])]
    (reset! db/app-db {:a 1 :b 2})
    (is (= [1 :c] @test-a-sub))
    (is (= true @test-b-sub))))

(deftest test-registering-subs-doesnt-create-subscription
  (let [sub-called? (atom false)]
    (with-redefs [subs/subscribe (fn [& args] (reset! sub-called? true))]
      (subs/reg-sub
        :a-sub
        (fn [db [_]] (:a db)))

      (subs/reg-sub
        :b-sub
        (fn [db [_]] (:b db)))

      (subs/reg-sub
        :fn-sub
        (fn [[_ c] _]
          [(subs/subscribe [:a-sub c])
           (subs/subscribe [:b-sub c])])
        (fn [db [_]] (:b db)))

      (subs/reg-sub
        :a-sugar-sub
        :<- [:a-sub]
        (fn [[a] [_ c]] {:a a}))

      (subs/reg-sub
        :a-b-sub
        :<- [:a-sub]
        :<- [:b-sub]
        (fn [[a b] [_ c]] {:a a :b b})))

    (is (false? @sub-called?))))

;; Dynamic subscriptions

(deftest test-dynamic-subscriptions
  (subs/reg-sub
    :dyn-sub
    (fn [db ev dynv]
      (first dynv)))

  (testing "happy case"
    (is (= 1 @(subs/subscribe [:dyn-sub] [(r/atom 1)]))))
  (testing "subscription that doesn't exist"
    (is (nil? (subs/subscribe [:non-existent] [(r/atom 1)]))))
  (testing "Passing a non-reactive value to a dynamic subscription"
    (is (thrown-with-msg? js/Error #"No protocol method IDeref" @(subs/subscribe [:dyn-sub] [1])))))
