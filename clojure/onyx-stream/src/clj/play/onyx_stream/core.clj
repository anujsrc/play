(ns play.onyx-stream.core
  (:require [play.onyx-stream.fixed-windows :as fw])
  (:require [play.onyx-stream.sliding-windows :as slw])
  (:require [play.onyx-stream.global-windows :as gw])
  (:require [play.onyx-stream.session-windows :as ssw]))

(defn -main
  "Executes window samples"
  [& args]
  (condp = (first args)
    "fw" (fw/execute-flow {})
    "slw" (slw/execute-flow {})
    "gw" (gw/execute-flow {})
    "ssw" (ssw/execute-flow {})
    (println "Usage: lein run <fw|slw|gw|ssw>")))
