(ns javelin.core
  (:require [cljs-lambda.util :as lambda]
            [cljs-lambda.context :as ctx]
            [cljs-lambda.macros :refer-macros [deflambda]]
            [cljs.reader :refer [read-string]]
            [cljs.nodejs :as nodejs]
            [cljs.core.async :as async]
            [goog.crypt.base64 :as b64]
            [clojure.string :as str]
            [goog.string :as gstring]
            [goog.string.format]
            [promesa.core :as p])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def markdown-it (nodejs/require "markdown-it"))
(def md (new markdown-it))

(def s3 (nodejs/require "s3"))
(def s3-client (.createClient s3 (clj->js {})))

(def fs (nodejs/require "fs"))          ; We need fs later to read write and /tmp
(def config
  (-> (.readFileSync fs "static/config.edn" "UTF-8")
      read-string))

;(def bb (nodejs/require "bluebird"))

;; The counter for the current document file name suffix is in an s3
;; object
;; TODO We'll need to hash it? or try serializing with a global semaphore.
(def current-tmp "/tmp/current.edn")
(def s3-current-counter-params {:localFile current-tmp
                                :s3Params {:Bucket "javelintest"
                                           :Key "current.edn"}})

;; TODO use the Bluebird Promise.promisify facility to transform this callback API into a Promise one.
;; FIXME why do I call the file "current" but the keyword is :last-post.  They should be the same.
;; Learning note: the instantiation of the downloader object needs to also be wrapped by the p/promise
;; so errors on instantiation that may be thrown are caught.
(defn fetch-current
  "Fetches the current.edn file from s3, asynchronously. Resolves to the number of the last post."
  []
  (p/promise (fn [resolve reject]
               (let [downloader (.downloadFile s3-client (clj->js s3-current-counter-params))]
                 (.on downloader "end" #(let [current-obj (->> (.readFileSync fs current-tmp "utf8")
                                                               read-string)
                                              value (:last-post (js->clj current-obj))]
                                          (js/console.log (str/join ["Got current.edn. Last-post = " value]))
                                          (resolve value)))
                 (.on downloader "error" #(do
                                            (js/console.log (str/join ["Got error = " %]))
                                            (reject {:error %})))))))

(defn post-doc
  "Computes html and posts record to s3 using document number docnum, asynchronously.  Returns a promise."
  [record docnum]
  (let [my-name (gstring/format "document%07d.html" docnum)
        local-file (str/join ["/tmp/" my-name]) 
        s3-params {:localFile local-file
                   :s3Params {:Bucket "javelintest"
                              :Key my-name}}
        my-text (.render md (str (b64/decodeString (-> record :kinesis :data))))
        _ignore (.writeFileSync fs local-file my-text)
        uploader (.uploadFile s3-client (clj->js s3-params))]
    (js/console.log my-text)

    (p/promise (fn [resolve reject]
                 (.on uploader "end" (fn [] (resolve :success)))
                 (.on uploader "error" (fn [r] (reject {:error r})))))))

(defn post-docs
  "Walks Records, calling post-doc, asynchronously.  Returns a list of status."
  [current {:keys [Records] :as event} ctx]
  (let [next (+ current 1)
        last (+ current (count Records))]
    ;; It is not clear to me if I need the doall here. In Clojure it would be needed to force what would be
    ;; a lazy sequence.  The https://www.clojurescript.org/about/differences was not helpful.  And a naive
    ;; test suggested that it was *not* needed, maybe because it's a return value.
    ;; TODO figure this out, until then be safer with doall included
    (p/all (doall (map post-doc Records (range next (+ 1 last)))))))

(defn verify-docs
  "Checks that this is a list of status :success. 
   Returns the length of the list if ok. Raises an exception otherwise."
  [docs-status]
  (if (every? #(= :success %) docs-status)
    (count docs-status)
    (throw (js/Error. "Save doc(s) failed. " docs-status))))

(defn post-current
  "Posts (writes) back a new current.edn file to s3, asynchronously."
  [new-current]
  ;; Store back current number
  (.writeFileSync fs current-tmp (pr-str {:last-post new-current}))
  (p/promise (fn [resolve reject]
               (let [uploader (.uploadFile s3-client (clj->js s3-current-counter-params))]
                 (.on uploader "end" (fn [] (resolve :success)))
                 (.on uploader "error" (fn [r] (reject {:error r})))))))
   
(defn test-async-DELETEME 
  [{:keys [msecs] :or {msecs 1000}} ctx]
  (p/promise
   (fn [resolve]
     (p/schedule msecs #(resolve {:waited msecs})))))

;(def s3-downloadFile-async (.promisify bb (. s3-client downloadFile)))

(defn fetch-current-TOY-DELETEME
  "Fetches the current.edn file from s3, asynchronously. Resolves to the number of the last post."
  []
  (js/console.log "in fetch-current")
  (-> (s3-downloadFile-async (clj->js s3-current-counter-params))
      (p/then (fn []
                (js/console.log (str/join ["file" (-> (.readFileSync fs current-tmp)
                                                      read-string)]))))
      (p/catch (fn [error] (js/console.log error))))
  )

(deflambda work-magic [event ctx]
  ;; There are three async operations that cascade upon one another
  ;; 1. Fetching current.edn from s3   TODO this is not multi-lambda instance safe!  Fix it.
  ;; 2. (this can be parallelized) Posting each document to s3
  ;; 3. Posting the new current.edn to s3
  (js/console.log "hello")
  (fetch-current)
;;  (test-async-DELETEME {:msecs 300})
  ;; (let [retval (p/alet [current        (p/await (fetch-current))
  ;;                       posted-docs    (p/await (post-docs current event ctx))
  ;;                       verified-docs  (verify-docs posted-docs)
  ;;                       posted-current (p/await (post-current (+ current verified-docs)))]
  ;;                      (js/console.log "current " current)   
  ;;                      posted-current)]
  ;;   @retval)                            ; IM HERE Huh, why can I not resolve the p/alet implicit promise?
)
