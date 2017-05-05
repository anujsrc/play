(defproject onyx-stream "0.1.0-SNAPSHOT"
  :description "Play with Onyx Stream and Windows"
  :url "https://github.com/anujsrc/play"
  :license {:name "Apache-2.0"
            :url "https://github.com/anujsrc/play/blob/master/LICENSE"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.clojure/core.async "0.3.442"]
                 [org.onyxplatform/onyx "0.10.0-beta12"]
                 [cheshire "5.7.1"]]
  :source-paths ["src/clj"]
  :java-source-paths ["src/jvm"]
  :resource-paths ["resources" "conf"]
  :test-paths ["test/clj" "test/jvm"]
  :main play.onyx-stream.core)
