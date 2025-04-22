(ns cllmj.books.hands-on-llms.ch01
  "Hands On Large Language Models - Chapter-1"
  (:require [cllmj.core :as cllmj]))

(defn joke-around
  "Generating your first text - a funny joke"
  [model-uri]
  (as-> (cllmj/create-text-generation-pipeline 
         (cllmj/get-model model-uri) 
         (cllmj/get-tokenizer model-uri)) $
    ($ [{"role" "user", 
         "content" "Create a funny joke about Clojure"}])))

(comment
  (def model-uri "/workspaces/play/clojure/cllmj/resources/models/qwen2-0.5b-instruct")
  (->> (joke-around model) first :generated_text)
  )