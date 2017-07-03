(ns javelin.core
  (:require [cljs-lambda.util :as lambda]
            [cljs-lambda.context :as ctx]
            [cljs-lambda.macros :refer-macros [deflambda]]
            [cljs.reader :refer [read-string]]
            [cljs.nodejs :as nodejs]
            [cljs.core.async :as async]
            [goog.crypt.base64 :as b64]
            [promesa.core :as p])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def markdown-it (nodejs/require "markdown-it"))
(def md (new markdown-it))
(def fs (nodejs/require "fs"))
(def s3 (nodejs/require "s3"))

(def config
  (-> (nodejs/require "fs")
      (.readFileSync "static/config.edn" "UTF-8")
      read-string))

(deflambda work-magic [{:keys [Records] :as event} ctx]
                                      ;  (js/console.log (clojure.string/join ["Records param: \n" Records]))
  ;; It is not clear to me if I need the doall here. In Clojure it would be needed to realize what would be
  ;; a lazy sequence.  The https://www.clojurescript.org/about/differences was not helpful.  And a naive
  ;; test suggested that it was *not* needed, maybe because it's a return value.
  ;; TODO figure this out, until then be safer with doall included
  (let [s3-client (.createClient s3 (clj->js {}))
        s3-params {:localFile "/tmp/document.html"
                   :s3Params {:Bucket "javelintest"
                              :Key "document.html"}}]
    (doall (map #(let [md-txt (.render md (str (b64/decodeString (-> % :kinesis :data))))]
                   (js/console.log md-txt)
                   (.writeFileSync fs "/tmp/document.html" md-txt)
                   (.uploadFile s3-client (clj->js s3-params)))
                Records))
    (p/promise             ; Um, a terrible hack, that I'll fix shortly
     (fn [resolve reject]
       (p/schedule 2000 (fn [] (resolve {:waited 2000})))))))

