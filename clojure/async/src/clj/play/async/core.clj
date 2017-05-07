(ns play.async.core
  (:require [clojure.core.async :as a :refer [>!]]
            [clojure.tools.logging :as log]))

(defmacro add-shutdown-hook
  "Adds a shutdown hook wrapping the body into a closure."
  [& body]
  `(.addShutdownHook
    (Runtime/getRuntime)
    (Thread. #(try ~@body
                   (catch Exception e#
                     (log/error e# "Error in shutdown hook."))))))

(defmacro try-catch
  [& body]
  `(try
     ~@body
     (catch Exception e# (log/error "Channel ERR" e#))))

(defn ttake!!
  [chan timeout default]
  (let [tch (a/timeout timeout)
        [v ch] (a/alts!! [chan tch])]
    (if (= ch tch) default v)))

(defn consume-all
  [channel poll]
  (let [flag (atom (ttake!! channel poll ::timeout))
        msgs-left (atom [])]
    (while (and (not (nil? @flag)) (not= ::timeout @flag))
      (swap! msgs-left conj @flag)
      (reset! flag (ttake!! channel poll ::timeout)))
    @msgs-left))

(defn close-channel*
  "Closes the channel and hands over the remaining msgs to close-fn"
  [channel poll msgh on-close]
  (let [msgs (consume-all channel poll)]
    (log/info "[Left]" msgs "Count:" (count msgs))
    (a/close! channel)
    (when on-close (try-catch (on-close @msgh)))
    (reset! msgh [])))

(defn channel-operate
  [channel delta cap msgh & {:keys [poll on-timeout on-overflow on-close] :or {poll 100}}]
  (let [now #(.getTime (java.util.Date.))
        latest (atom 0)]
    (add-shutdown-hook
      (do (log/info "[Shutdown] Closing Channel")
          (close-channel* channel poll msgh on-close)))
    (a/thread
      (loop [msg (ttake!! channel poll ::timeout) msgs []]
        (when msg
          (let [msgz (if (= msg ::timeout) msgs (swap! msgh conj msg))]
            (cond
              ; check `delta`
              (and on-timeout (>= (- (now) @latest) delta))
              (do
                (reset! latest (now))
                (reset! msgh [])
                (when (seq msgz) (try-catch (on-timeout msgz)))
                (recur (ttake!! channel poll ::timeout) []))
              ; check `cap`
              (and on-overflow (>= (count msgz) cap))
              (do
                (reset! latest (now))
                (reset! msgh [])
                (when (seq msgz) (try-catch (on-overflow msgz)))
                (recur (ttake!! channel poll ::timeout) []))
              :default
              (recur (ttake!! channel poll ::timeout) msgz)))))
      (close-channel* channel poll msgh on-close))
    channel))

(defn put-msg
  [channel msg]
  (a/go (>! channel msg)))

(defn process
  [params]
  (let [conf (read-string params)
        buffer (conf :buffer 1000)
        ; timeout in msec
        timeout (conf :timeout 5000)
        thres (conf :threshold 5)
        ; poll in msec
        poll (conf :poll 100)
        channel (a/chan (a/sliding-buffer buffer))
        msgh (atom [])
        channel (channel-operate channel timeout thres msgh
                                 :poll poll
                                 :on-timeout #(log/info "[Timeout]" % "Count:" (count %))
                                 :on-overflow #(log/info "[Overflow]" % "Count:" (count %))
                                 :on-close #(log/info "[Close]" % "Count:" (count %)))]
    ; Let the channel breathe
    (Thread/sleep (conf :breathe 5000))
    (doseq [idx (range (conf :payload 10))]
      (put-msg channel idx))))

(defn -main
  ([]
   (process "{}"))
  ([params]
   (process params)))

; debug
(comment
  ; Send sample messages
  (a/go (>! channel "Hello1"))
  (a/go (>! channel "Hello2"))
  (a/go (>! channel "Hello3"))
  (a/go (>! channel "Hello4"))
  (a/go (>! channel "Hello5"))
  (a/go (>! channel "Hello6"))
  (a/go (>! channel "Hello7")))
