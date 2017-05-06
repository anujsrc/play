(ns play.onyx-stream.session-windows
  (:require [clojure.core.async :refer [chan >!! <!! close!]]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.api]))

(def capacity 1000)

(def input-chan (chan capacity))
(def input-buffer (atom {}))

(def output-chan (chan capacity))

(defn inject-in-ch [event lifecycle]
  {:core.async/buffer input-buffer
   :core.async/chan input-chan})

(defn inject-out-ch [event lifecycle]
  {:core.async/chan output-chan})

(def in-calls
  {:lifecycle/before-task-start inject-in-ch})

(def out-calls
  {:lifecycle/before-task-start inject-out-ch})

(defn dump-window!
  [event window trigger {:keys [lower-bound upper-bound] :as window-data} state]
  (println (format "Window extent [%s - %s] contents: %s"
                   lower-bound upper-bound state)))

(defn job-config
  [{:keys [id batch-size] :as params}]
  {:id id
   :batch-size batch-size
   :env-config {:zookeeper/address "127.0.0.1:2188"
                :zookeeper/server? true
                :zookeeper.server/port 2188
                :onyx/tenancy-id id}
   :peer-config {:zookeeper/address "127.0.0.1:2188"
                 :onyx/tenancy-id id
                 :onyx.peer/job-scheduler :onyx.job-scheduler/balanced
                 :onyx.messaging/impl :aeron
                 :onyx.messaging/peer-port 40200
                 :onyx.messaging/bind-addr "localhost"}})

(defn create-job
  [{:keys [id batch-size] :as params}]
  (let [workflow [[:in :identity]
                  [:identity :out]]
        catalog [{:onyx/name :in
                  :onyx/plugin :onyx.plugin.core-async/input
                  :onyx/type :input
                  :onyx/medium :core.async
                  :onyx/batch-size batch-size
                  :onyx/max-peers 1
                  :onyx/doc "Reads segments from a core.async channel"}

                 {:onyx/name :identity
                  :onyx/fn :clojure.core/identity
                  :onyx/type :function
                  :onyx/batch-size batch-size}

                 {:onyx/name :out
                  :onyx/plugin :onyx.plugin.core-async/output
                  :onyx/type :output
                  :onyx/medium :core.async
                  :onyx/max-peers 1
                  :onyx/batch-size batch-size
                  :onyx/doc "Writes segments to a core.async channel"}]
        lifecycles [{:lifecycle/task :in
                     :lifecycle/calls ::in-calls}
                    {:lifecycle/task :in
                     :lifecycle/calls :onyx.plugin.core-async/reader-calls}
                    {:lifecycle/task :out
                     :lifecycle/calls ::out-calls}
                    {:lifecycle/task :out
                     :lifecycle/calls :onyx.plugin.core-async/writer-calls}]
        windows [{:window/id :collect-segments
                  :window/task :identity
                  :window/type :session
                  :window/aggregation :onyx.windowing.aggregation/conj
                  :window/window-key :event-time
                  :window/session-key :id
                  :window/timeout-gap [5 :minutes]}]
        triggers [{:trigger/window-id :collect-segments
                   :trigger/id :sync
                   :trigger/refinement :onyx.refinements/accumulating
                   :trigger/on :onyx.triggers/timer
                   :trigger/period [1 :seconds]
                   :trigger/sync ::dump-window!}]]
    {:workflow workflow
     :catalog catalog
     :lifecycles lifecycles
     :windows windows
     :triggers triggers
     :task-scheduler :onyx.task-scheduler/balanced}))

(defn start-env
  [{:keys [env-config] :as params}]
  (onyx.api/start-env env-config))

(defn start-peers
  [{:keys [peer-config] :as params}
   {:keys [workflow] :as job}]
  (let [peer-group (onyx.api/start-peer-group peer-config)
        n-peers (count (set (mapcat identity workflow)))
        v-peers (onyx.api/start-peers n-peers peer-group)]
    {:peer-group peer-group :v-peers v-peers}))

(defn submit-job
  [{:keys [peer-config] :as params} job]
  (onyx.api/submit-job peer-config job))

(defn send-data
  [params segments]
  (doseq [segment segments]
    (>!! input-chan segment)))

(defn get-result
  [{:keys [peer-config] :as params} active-job]
  ;; wait for trigger to fire
  (Thread/sleep 5000)
  ;; for await API to unblock
  (close! input-chan)
  (onyx.api/await-job-completion peer-config (:job-id active-job))
  (take-segments! output-chan 50))

(defn close-channels
  [params]
  ;;(close! input-chan)
  (close! output-chan))

(defn stop-peers
  [{:keys [peer-group v-peers] :as peer-config}]
  (doseq [v-peer v-peers]
    (onyx.api/shutdown-peer v-peer))
  (onyx.api/shutdown-peer-group peer-group))

(defn stop-env
  [env]
  (onyx.api/shutdown-env env))

(def input-segments
  [{:event-id 0 :id 1 :event-time #inst "2015-09-13T03:00:00.829-00:00"}
   {:event-id 1 :id 1 :event-time #inst "2015-09-13T03:03:00.829-00:00"}
   {:event-id 2 :id 1 :event-time #inst "2015-09-13T03:07:00.829-00:00"}
   {:event-id 3 :id 2 :event-time #inst "2015-09-13T03:11:00.829-00:00"}
   {:event-id 4 :id 2 :event-time #inst "2015-09-13T03:15:00.829-00:00"}
   {:event-id 5 :id 2 :event-time #inst "2015-09-13T03:35:00.829-00:00"}
   {:event-id 6 :id 1 :event-time #inst "2015-09-13T03:20:00.829-00:00"}])

(defn run-flow
  [params]
  (let [_ (println "Initializing Job Config...")
        config (job-config params)
        _ (println "Preparing Job Config...")
        job (create-job params)
        _ (println "Starting Onyx Environment...")
        env (start-env config)
        _ (println "Starting Peer Group and Peers...")
        peer-config (start-peers config job)
        _ (println "Running Job...")
        active-job (submit-job config job)]
    (merge {:env env :flow active-job} config job peer-config)))

(defn process-data
  [{:keys [flow] :as params}]
  (send-data params input-segments)
  (get-result params flow))

(defn stop-flow
  [{:keys [env] :as params}]
  (println "Closing Channels...")
  (close-channels params)
  (println "Stopping Peers and Peer Group...")
  (stop-peers params)
  (println "Stopping Onyx Environment...")
  (stop-env env))

(defn execute-flow
  [{:keys [id batch-size] :as params
    :or {id (java.util.UUID/randomUUID) batch-size 10}}]
  (let [flow (run-flow {:id id :batch-size batch-size})
        result (process-data flow)]
    ;;(println result)
    (stop-flow flow)))
