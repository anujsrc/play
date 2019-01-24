;; -------------------------------------------
;; Taken from core.async examples walkthrough
;; -------------------------------------------
(ns play.async.example
  (:require [clojure.core.async :as a
             :refer [>! <! >!! <!! alts! alts!!
                     chan close! go thread timeout]]
            [clojure.tools.logging :as log]))

;;;; CHANNELS

;; Data is transmitted on queue-like channels. By default channels
;; are unbuffered (0-length) - they require producer and consumer to
;; rendezvous for the transfer of a value through the channel.

;; Use `chan` to make an unbuffered channel:

;;(chan)

;; Pass a number to create a channel with a fixed buffer:

;;(chan 10)

;; `close!` a channel to stop accepting puts. Remaining values are still
;; available to take. Drained channels return nil on take. Nils may
;; not be sent over a channel explicitly!

;;(let [c (chan)]
;;  (close! c))

;;;; ORDINARY THREADS

;; In ordinary threads, we use `>!!` (blocking put) and `<!!`
;; (blocking take) to communicate via channels.

(defn hello-by-main-thread
  [msg]
  (let [c (chan 10)]
    (>!! c msg)
    (print (<!! c))
    (close! c)))

;; Because these are blocking calls, if we try to put on an
;; unbuffered channel, we will block the main thread. We can use
;; `thread` (like `future`) to execute a body in a pool thread and
;; return a channel with the result. Here we launch a background task
;; to put "hello" on a channel, then read that value in the current thread.

(defn hello-by-thread*
  [msg]
  (let [c (chan)]
    (thread (>!! c msg))
    (print (<!! c))
    (close! c)))

;;;; GO BLOCKS AND IOC THREADS

;; The `go` macro asynchronously executes its body in a special pool
;; of threads. Channel operations that would block will pause
;; execution instead, blocking no threads. This mechanism encapsulates
;; the inversion of control that is external in event/callback
;; systems. Inside `go` blocks, we use `>!` (put) and `<!` (take).

;; Here we convert our prior channel example to use go blocks:

(defn hello-by-go
  [msg]
  (let [c (chan)]
    (go (>! c msg))
    (print (<!! (go (<! c))))
    (close! c)))

;; Instead of the explicit thread and blocking call, we use a go block
;; for the producer. The consumer uses a go block to take, then
;; returns a result channel, from which we do a blocking take.

;; Since go blocks are lightweight processes not bound to threads, we
;; can have LOTS of them! Here we create 1000 go blocks that say hi on
;; 1000 channels. We use alts!! to read them as they're ready.

(defn spam-1k
  [msg]
  (let [n 1000
        cs (repeatedly n chan)
        begin (System/currentTimeMillis)]
    (doseq [c cs] (go (>! c msg)))
    (dotimes [i n]
      (let [[v c] (alts!! cs)]
        (assert (= msg v))))
    (print "Read" n "msgs in" (- (System/currentTimeMillis) begin) "ms")))

;; `timeout` creates a channel that waits for a specified ms, then closes:

;;(let [t (timeout 100)
;;      begin (System/currentTimeMillis)]
;;  (<!! t)
;;  (println "Waited" (- (System/currentTimeMillis) begin)))

;; We can combine timeout with `alts!` to do timed channel waits.
;; Here we wait for 2 secs for a value to arrive on the channel, then
;; give up:

(defn give-up
  []
  (let [c (chan)
        begin (System/currentTimeMillis)]
    (alts!! [c (timeout 2000)])
    (println "Gave up after" (- (System/currentTimeMillis) begin))))

;;;; OTHER BUFFERS

;; Channels can also use custom buffers that have different policies

;; Use `dropping-buffer` to drop newest values when the buffer is full:

;;(chan (dropping-buffer 10))

;; Use `sliding-buffer` to drop oldest values when the buffer is full:

;;(chan (sliding-buffer 10))
