(ns play.onyx.play-wordcount
  (:require [clojure.string :as s]
            [clojure.core.async :refer [chan >!! <!! close!]]
            [clojure.tools.logging :as log]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.api])
  (:import [java.util UUID]))

; Pre-requisites {{{1
; Start Zookeeper (external)
; Optionally
; Start ZK UI
; lein run ~/Documents/workspace/git/anujsrc/zk-web
; Start Onyx Dashboard
; (0.9.12.0: ~/Documents/study/onyx/onyx-dashboard/target)
; java -server -jar onyx-dashboard.jar "127.0.0.1:2181"
; Connect to REPL

; Word Count Workflow Example {{{1

; Workflow {{{2
(def workflow
  [[:in :words]
   [:words :odd]
   [:words :even]
   ;[:odd :out-odd]
   ;[:even :out-even]])
   [:odd :count-odd]
   [:even :count-even]
   [:count-odd :out-odd]
   [:count-even :out-even]])

; Catalog {{{2
(def catalog
  [{:onyx/name :in
    :onyx/plugin :onyx.plugin.core-async/input
    :onyx/type :input
    :onyx/medium :core.async
    :onyx/max-peers 1
    :onyx/batch-size 10
    :onyx/doc "Reads segments from a core.async channel"}

   {:onyx/name :words
    :onyx/fn :play.onyx.play-wordcount/words
    :onyx/type :function
    :onyx/batch-size 10}

   {:onyx/name :odd
    :onyx/fn :play.onyx.play-wordcount/filter-word
    :onyx/type :function
    :parameterized.core/isodd true
    :onyx/params [:parameterized.core/isodd]
    :onyx/batch-size 10}

   {:onyx/name :even
    :onyx/fn :play.onyx.play-wordcount/filter-word
    :onyx/type :function
    :filter-word.param/isodd false
    :onyx/params [:filter-word.param/isodd]
    :onyx/batch-size 10}

   {:onyx/name :count-odd
    :onyx/fn :clojure.core/identity
    :onyx/type :function
    :onyx/group-by-key :word
    :onyx/flux-policy :kill
    :onyx/min-peers 1
    :onyx/uniqueness-key :id
    :onyx/batch-size 10}

   {:onyx/name :count-even
    :onyx/fn :clojure.core/identity
    :onyx/type :function
    :onyx/group-by-key :word
    :onyx/flux-policy :kill
    :onyx/min-peers 1
    :onyx/uniqueness-key :id
    :onyx/batch-size 10}

   {:onyx/name :out-odd
    :onyx/plugin :onyx.plugin.core-async/output
    :onyx/type :output
    :onyx/medium :core.async
    :onyx/max-peers 1
    :onyx/batch-size 10
    :onyx/doc "Writes segments to a core.async channel"}

   {:onyx/name :out-even
    :onyx/plugin :onyx.plugin.core-async/output
    :onyx/type :output
    :onyx/medium :core.async
    :onyx/max-peers 1
    :onyx/batch-size 10
    :onyx/doc "Writes segments to a core.async channel"}])

; Functions {{{2
(defn words
  "Lowercase the sentence and generate words"
  [{:keys [sentence] :as segment}]
  (mapv #(let [w (s/lower-case %)]
           (assoc {} :id (str (UUID/randomUUID)) :word w :count (count w)))
        (clojure.string/split sentence #"\s+")))

(defn filter-word [isodd segment]
  (filter #((if isodd odd? even?) (:count %)) (vector segment)))

; Channels {{{2
(def input-chan (chan 1000))
(def output-odd-chan (chan 1000))
(def output-even-chan (chan 1000))

; Lifecycles {{{2
(def lifecycles
  [{:lifecycle/task :in
    :lifecycle/calls :play.onyx.play-wordcount/in-calls}
   {:lifecycle/task :in
    :lifecycle/calls :onyx.plugin.core-async/reader-calls}
   {:lifecycle/task :out-odd
    :lifecycle/calls :play.onyx.play-wordcount/out-odd-calls}
   {:lifecycle/task :out-odd
    :lifecycle/calls :onyx.plugin.core-async/writer-calls}
   {:lifecycle/task :out-even
    :lifecycle/calls :play.onyx.play-wordcount/out-even-calls}
   {:lifecycle/task :out-even
    :lifecycle/calls :onyx.plugin.core-async/writer-calls}])

; Inject {{{3
(defn inject-in-ch
  [event lifecycle]
  {:core.async/chan input-chan})

(defn inject-out-odd-ch
  [event lifecycle]
  {:core.async/chan output-odd-chan})

(defn inject-out-even-ch
  [event lifecycle]
  {:core.async/chan output-even-chan})

(def in-calls
  {:lifecycle/before-task-start inject-in-ch})

(def out-odd-calls
  {:lifecycle/before-task-start inject-out-odd-ch})

(def out-even-calls
  {:lifecycle/before-task-start inject-out-even-ch})

(def windows
  [{:window/id :word-counter-odd
    :window/task :count-odd
    :window/type :global
    :window/aggregation :onyx.windowing.aggregation/count
    :window/window-key :event-time}
   {:window/id :word-counter-even
    :window/task :count-even
    :window/type :global
    :window/aggregation :onyx.windowing.aggregation/count
    :window/window-key :event-time}])

(def triggers
  [{:trigger/window-id :word-counter-odd
    :trigger/refinement :onyx.refinements/accumulating
    :trigger/on :onyx.triggers/timer
    :trigger/period [3 :seconds]
    :trigger/sync :play.onyx.play-wordcount/dump-window!}
   {:trigger/window-id :word-counter-even
    :trigger/refinement :onyx.refinements/accumulating
    :trigger/on :onyx.triggers/timer
    :trigger/period [3 :seconds]
    :trigger/sync :play.onyx.play-wordcount/dump-window!}])

(defn dump-window!
  [event window trigger {:keys [group-key] :as opts} state]
  (spit "/tmp/play_wordcount.txt"
        (str (System/currentTimeMillis) ":" group-key "->" state "\n") :append true))

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
  {:catalog catalog :workflow workflow :lifecycles lifecycles
   :windows windows :triggers triggers :task-scheduler :onyx.task-scheduler/balanced})

; Sample Test {{{1
(def input-segments
  [{:id 0 :event-time 0 :sentence "Hello there, how is life"}
   {:id 1 :event-time 0 :sentence "hello this is a simple line"}
   :done])

; Publish on channel
(doseq [segment input-segments]
  (>!! input-chan segment))

; Read result
(def results-odd (take-segments! output-odd-chan))
(clojure.pprint/pprint results-odd)

(def results-even (take-segments! output-even-chan))
(clojure.pprint/pprint results-even)

; Shutdown Onyx {{{1
(close! input-chan)
(close! output-odd-chan)
(close! output-even-chan)
(doseq [v-peer v-peers]
  (onyx.api/shutdown-peer v-peer))
(onyx.api/shutdown-peer-group peer-group)
(onyx.api/shutdown-env env)
