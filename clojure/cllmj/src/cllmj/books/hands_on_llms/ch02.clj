(ns cllmj.books.hands-on-llms.ch02
  "Hands On Large Language Models - Chapter-2"
  (:require [cllmj.core :as cllmj]
            [libpython-clj2.python :refer [py. py.-] :as py]))

(defn get-tensor
  "Gets the tensor for the given prompt"
  ([model-uri prompt]
   (-> (cllmj/get-tokenizer model-uri) 
       (py/cfn [prompt] :return_tensors "pt")
       (py.- "input_ids")))
  ([model-uri] 
   (get-tensor  
    model-uri
    (str "Write an email apologizing to Sarah for the "
         "tragic gardening mishap. Explain how it happened."
         "<|assistant|>"))))

(defn generate-tokens
  "Generates tokens of given size"
  ([model-uri max-new-tokens]
   (generate-tokens 
    model-uri max-new-tokens 
    (str "Write an email apologizing to Sarah for the "
         "tragic gardening mishap. Explain how it happened."
         "<|assistant|>")))
  ([model-uri max-new-tokens prompt]
   (let [tokenizer (cllmj/get-tokenizer model-uri)]
     (as-> (cllmj/get-model model-uri) $ 
       (py. $ "generate" 
            :input_ids (get-tensor model-uri prompt) 
            :max_new_tokens max-new-tokens)
       (py. tokenizer "decode" (first $))))))

(comment
  (def model-uri "/workspaces/play/clojure/cllmj/resources/models/qwen2-0.5b-instruct")
  )