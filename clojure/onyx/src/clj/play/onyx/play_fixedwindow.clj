(ns play.onyx.play-fixedwindow
  (:require [clojure.core.async :refer [chan >!! <!! close!]]
            [clojure.tools.logging :as log]
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

; Workflow {{{2
(def workflow
  [[:in :identity]
   [:identity :out]])

; Catalog {{{2
(def catalog
  [{:onyx/name :in
    :onyx/plugin :onyx.plugin.core-async/input
    :onyx/type :input
    :onyx/medium :core.async
    :onyx/batch-size 10
    :onyx/max-peers 1
    :onyx/doc "Reads segments from a core.async channel"}

   {:onyx/name :identity
    :onyx/fn :clojure.core/identity
    :onyx/uniqueness-key :n
    :onyx/type :function
    :onyx/batch-size 10}

   {:onyx/name :out
    :onyx/plugin :onyx.plugin.core-async/output
    :onyx/type :output
    :onyx/medium :core.async
    :onyx/max-peers 1
    :onyx/batch-size 10
    :onyx/doc "Writes segments to a core.async channel"}])

; Channels {{{2
(def input-chan (chan 1000))
(def output-chan (chan 1000))

;  Lifecycles {{{2
(def lifecycles
  [{:lifecycle/task :in
    :lifecycle/calls ::in-calls}
   {:lifecycle/task :in
    :lifecycle/calls :onyx.plugin.core-async/reader-calls}
   {:lifecycle/task :out
    :lifecycle/calls ::out-calls}
   {:lifecycle/task :out
    :lifecycle/calls :onyx.plugin.core-async/writer-calls}])

(def windows
  [{:window/id :collect-segments
    :window/task :identity
    :window/type :fixed
    :window/aggregation :onyx.windowing.aggregation/conj
    :window/window-key :event-time
    :window/range [5 :minutes]}])

(def triggers
  [{:trigger/window-id :collect-segments
    :trigger/refinement :onyx.refinements/accumulating
    :trigger/on :onyx.triggers/timer
    :trigger/period [1 :seconds]
    :trigger/sync ::dump-window!}])

; Inject {{{3
(defn inject-in-ch [event lifecycle]
  {:core.async/chan input-chan})

(defn inject-out-ch [event lifecycle]
  {:core.async/chan output-chan})

(def in-calls
  {:lifecycle/before-task-start inject-in-ch})

(def out-calls
  {:lifecycle/before-task-start inject-out-ch})

(defn dump-window! [event window-id lower-bound upper-bound state]
  (log/info (format "Window extent %s, [%s - %s] contents: %s"
                    window-id lower-bound upper-bound state))
  (spit "/tmp/fcw.txt" (format "Window extent %s, [%s - %s] contents: %s"
                               window-id lower-bound upper-bound state)))

; Setup Onyx Environment {{{1

; Tenancy ID
(def id (java.util.UUID/randomUUID))
(clojure.pprint/pprint id)

(def env-config
  {:zookeeper/address "127.0.0.1:2181"
   :zookeeper/server? false
   :zookeeper.server/port 2181
   :onyx.bookkeeper/server? true
   :onyx.bookkeeper/local-quorum? true
   :onyx.bookkeeper/local-quorum-ports [3196 3197 3198]
   :onyx.bookkeeper/delete-server-data? true
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
 {:workflow workflow
  :catalog catalog
  :lifecycles lifecycles
  :windows windows
  :triggers triggers
  :task-scheduler :onyx.task-scheduler/balanced})

; Sample Test {{{1
(def input-segments
  [{:n 0 :event-time #inst "2015-09-13T03:00:00.829-00:00"}
   {:n 1 :event-time #inst "2015-09-13T03:03:00.829-00:00"}
   {:n 2 :event-time #inst "2015-09-13T03:07:00.829-00:00"}
   {:n 3 :event-time #inst "2015-09-13T03:11:00.829-00:00"}
   {:n 4 :event-time #inst "2015-09-13T03:15:00.829-00:00"}
   {:n 5 :event-time #inst "2015-09-13T03:02:00.829-00:00"}])

; Publish on channel
(doseq [segment input-segments]
  (>!! input-chan segment))

;; Sleep until the trigger timer fires.
(Thread/sleep 3000)

(>!! input-chan :done)
(close! input-chan)

; Read result
(def result (take-segments! output-chan))
(clojure.pprint/pprint result)

; Shutdown Onyx {{{1
(close! input-chan)
(close! output-chan)
(doseq [v-peer v-peers]
  (onyx.api/shutdown-peer v-peer))
(onyx.api/shutdown-peer-group peer-group)
(onyx.api/shutdown-env env)
