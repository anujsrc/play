(ns play.onyx.play-incdec
  (:require [clojure.core.async :refer [chan >!! <!! close!]]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.api]))

; Pre-requisites {{{1
; Start Zookeeper (external)
; Optionally
; Start ZK UI
; lein run ~/Documents/workspace/git/anujsrc/zk-web
; Start Onyx Dashboard
; (0.9.12.0: ~/Documents/study/onyx/onyx-dashboard/target)
; java -server -jar onyx-dashboard.jar "127.0.0.1:2181"
; Connect to REPL

; Multi-output Workflow Example {{{1
; :input -> :increment -> :output1
;        \
;         -> :decrement -> :output2

; Workflow {{{2
(def workflow
  [[:in :inc]
   [:in :dec]
   [:inc :out-1]
   [:dec :out-2]])

; Catalog {{{2
(def catalog
  [{:onyx/name :in
    :onyx/plugin :onyx.plugin.core-async/input
    :onyx/type :input
    :onyx/medium :core.async
    :onyx/max-peers 1
    :onyx/batch-size 10
    :onyx/doc "Reads segments from a core.async channel"}

   {:onyx/name :inc
    :onyx/fn :play.onyx.play-incdec/my-inc
    :onyx/type :function
    :onyx/batch-size 10}

   {:onyx/name :dec
    :onyx/fn :play.onyx.play-incdec/my-dec
    :onyx/type :function
    :onyx/batch-size 10}

   {:onyx/name :out-1
    :onyx/plugin :onyx.plugin.core-async/output
    :onyx/type :output
    :onyx/medium :core.async
    :onyx/max-peers 1
    :onyx/batch-size 10
    :onyx/doc "Writes segments to a core.async channel"}

   {:onyx/name :out-2
    :onyx/plugin :onyx.plugin.core-async/output
    :onyx/type :output
    :onyx/medium :core.async
    :onyx/max-peers 1
    :onyx/batch-size 10
    :onyx/doc "Writes segments to a core.async channel"}])

; Functions {{{2
(defn my-inc
  "Increments the param by 1"
  [{:keys [n] :as segment}]
  (assoc segment :n (inc n)))

(defn my-dec
  "Increments the param by 1"
  [{:keys [n] :as segment}]
  (assoc segment :n (dec n)))

; Channels {{{2
(def input-chan (chan 1000))
(def output-1-chan (chan 1000))
(def output-2-chan (chan 1000))

;  Lifecycles {{{2
(def lifecycles
  [{:lifecycle/task :in
    :lifecycle/calls :play.onyx.play-incdec/in-calls}
   {:lifecycle/task :in
    :lifecycle/calls :onyx.plugin.core-async/reader-calls}
   {:lifecycle/task :out-1
    :lifecycle/calls :play.onyx.play-incdec/out-1-calls}
   {:lifecycle/task :out-1
    :lifecycle/calls :onyx.plugin.core-async/writer-calls}
   {:lifecycle/task :out-2
    :lifecycle/calls :play.onyx.play-incdec/out-2-calls}
   {:lifecycle/task :out-2
    :lifecycle/calls :onyx.plugin.core-async/writer-calls}])

; Inject {{{3
(defn inject-in-ch
  [event lifecycle]
  {:core.async/chan input-chan})

(defn inject-out-1-ch
  [event lifecycle]
  {:core.async/chan output-1-chan})

(defn inject-out-2-ch
  [event lifecycle]
  {:core.async/chan output-2-chan})

(def in-calls
  {:lifecycle/before-task-start inject-in-ch})

(def out-1-calls
  {:lifecycle/before-task-start inject-out-1-ch})

(def out-2-calls
  {:lifecycle/before-task-start inject-out-2-ch})

; Setup Onyx Environment {{{1

; Tenancy ID
(def id (java.util.UUID/randomUUID))
(clojure.pprint/pprint id)

(def env-config
  {:zookeeper/address "127.0.0.1:2181"
   :zookeeper/server? false
   :zookeeper.server/port 2181
   :onyx/tenancy-id id})

(def peer-config
  {:zookeeper/address "127.0.0.1:2181"
   :onyx/tenancy-id id
   :onyx.peer/job-scheduler :onyx.job-scheduler/balanced
   :onyx.messaging/impl :aeron
   :onyx.messaging/peer-port 40200
   :onyx.messaging/bind-addr "localhost"})

; Start Onyx {{{1
(def env (onyx.api/start-env env-config))
(def peer-group (onyx.api/start-peer-group peer-config))
(def n-peers (count (set (mapcat identity workflow))))
(clojure.pprint/pprint n-peers)
(def v-peers (onyx.api/start-peers n-peers peer-group))

; Submit Job {{{1
(onyx.api/submit-job
  peer-config
  {:catalog catalog :workflow workflow :lifecycles lifecycles
   :task-scheduler :onyx.task-scheduler/balanced})

; Sample Test {{{1
(def input-segments
  [{:n 0}
   {:n 1}
   {:n 2}
   {:n 3}
   {:n 4}
   {:n 5}
   :done])

; Publish on channel
(doseq [segment input-segments]
  (>!! input-chan segment))

; Read result
(def result-1 (take-segments! output-1-chan))
(clojure.pprint/pprint result-1)

(def result-2 (take-segments! output-2-chan))
(clojure.pprint/pprint result-2)

; Shutdown Onyx {{{1
(close! input-chan)
(close! output-1-chan)
(close! output-2-chan)
(doseq [v-peer v-peers]
  (onyx.api/shutdown-peer v-peer))
(onyx.api/shutdown-peer-group peer-group)
(onyx.api/shutdown-env env)
