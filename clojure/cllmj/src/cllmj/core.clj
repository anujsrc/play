(ns cllmj.core
  "CLLMj Core Implementation"
  (:require [libpython-clj2.require :refer [require-python]]
            [libpython-clj2.python :refer [py.] :as py])
  (:gen-class))

;; initialize
(when-not *compile-files*
  (require-python '[builtins :as builtins]))

(defonce transformers 
  (delay (py/import-module "transformers")))

(defn get-tokenizer
  "Gets pretrained tokenizer from the given model"
  [model-uri]
  (-> (py/get-attr @transformers "AutoTokenizer")
      (py. "from_pretrained" model-uri)))

(defn get-model
  "Gets the pretrained model as 'AutoModelForCausalLM'"
  ([model-uri]
   (-> (py/get-attr @transformers "AutoModelForCausalLM")
       (py/call-attr-kw "from_pretrained"
                        [model-uri]
                        {:torch_dtype "auto"
                         :device_map "auto"
                         :trust_remote_code false})))
  ([model-uri params]
   (-> (py/get-attr @transformers "AutoModelForCausalLM")
       (py/call-attr-kw "from_pretrained" [model-uri] params))))

(defn create-text-generation-pipeline
  "Creates a new transformer pipeline, defaults to text-generation task"
  ([model tokenizer]
   (py/call-attr-kw @transformers "pipeline"
                     ["text-generation"]
                     {:model model
                      :tokenizer tokenizer
                      :return_full_text false
                      :max_new_tokens 500
                      :do_sample false}))
  ([model tokenizer params]
   (py/call-attr-kw 
    @transformers "pipeline" ["text-generation"] 
    (merge {:model model :tokenizer tokenizer} params))))

(defn create-task-pipeline
  "Creates a new transformer pipeline, defaults to text-generation task"
  ([model tokenizer task]
   (py/call-attr-kw @transformers "pipeline"
                    [task]
                    {:model model
                     :tokenizer tokenizer
                     :return_full_text false
                     :max_new_tokens 500
                     :do_sample false}))
  ([model tokenizer task params]
   (py/call-attr-kw 
    @transformers "pipeline" [task] 
    (merge {:model model :tokenizer tokenizer} params))))