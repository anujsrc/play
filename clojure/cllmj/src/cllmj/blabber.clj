(ns cllmj.blabber
  "Generating your first text"
  (:require [clojure.string :as s]
            [libpython-clj2.require :refer [require-python]] 
            [libpython-clj2.python :refer [py. py.. py.-] :as py]))

;; initialize
(require-python '[builtins :as builtins])
(defonce transformers (py/import-module "transformers"))

(defn generate
  "Generator that blabbers"
  [model-uri q]
  ;; transformers.pipeline('text-generation', model='gpt2')
  (as-> (py. transformers pipeline "text-generation" model-uri) $
    ($ q)))

(defn ask
  "Reponds based on the prompt using instruct models"
  [model-uri prompt]
  (let [;; transformers.AutoTokenizer.from_pretrained("model")
        tokenizer (-> (py/get-attr transformers "AutoTokenizer")
                      (py. "from_pretrained" model-uri))
        ;; transformers.AutoModelForCausalLM.from_pretrained("model", 
                                      ;; torch_dtype="auto", 
                                      ;; device_map="auto", 
                                      ;; trust_remote_code=False)
        auto-model (-> (py/get-attr transformers "AutoModelForCausalLM")
                       (py/call-attr-kw "from_pretrained" 
                                        [model-uri] 
                                        {:torch_dtype "auto" 
                                         :device_map "auto"
                                         :trust_remote_code false}))
        messages [{"role" "system",
                   "content" "You are a helpful assistant."}
                  {"role" "user",
                   "content" prompt}]
        text (py/call-attr-kw tokenizer "apply_chat_template" 
                              [messages] 
                              {:tokenize false 
                               :add_generation_prompt true})
        model-inputs (-> (py/cfn tokenizer [text] :return_tensors "pt") 
                         (py. "to" "cpu"))
        generate-ids (py/call-attr-kw auto-model "generate" 
                                      [(py/get-attr model-inputs "input_ids")] 
                                      {:max_new_tokens 512})
        ;; output_ids[len(input_ids):] for input_ids, output_ids in 
                ;;zip(model_inputs.input_ids, generated_ids)
        response-ids (for [pair (builtins/zip 
                                 (py/get-attr model-inputs "input_ids")
                                 generate-ids)] 
                       (let [[input-ids output-ids] pair 
                             input-len (builtins/len input-ids)] 
                         (py/get-item output-ids 
                                      (builtins/slice input-len nil))))]
    (py/with-gil-stack-rc-context 
      (->> (py/call-attr-kw tokenizer "batch_decode" 
                            response-ids {:skip_special_tokens true}) 
           (s/join "") 
           (py/->jvm)))))