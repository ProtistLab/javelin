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

(declare fetch-last-post post-doc post-docs verify-docs post-last-post)

(def markdown-it (nodejs/require "markdown-it"))
(def s3 (nodejs/require "s3"))
(def fs (nodejs/require "fs"))          ; To read and write /tmp

(def md (new markdown-it))
(def s3-client (.createClient s3 (clj->js {})))

;;
;; This is the function that AWS Lambda will call.
;;
(deflambda work-magic [event ctx]
  ;; There are three async operations that cascade upon one another
  ;; 1. Fetching last-post.edn from s3   TODO this is not multi-lambda instance safe!  Fix it.
  ;; 2. (this can be parallelized) Posting each document to s3
  ;; 3. Posting the new last-post.edn to s3
  (let [retval (p/alet [last-post        (p/await (fetch-last-post))
                        posted-docs      (p/await (post-docs last-post event ctx))
                        verified-docs    (p/await (verify-docs posted-docs))
                        posted-last-post (p/await (post-last-post (+ last-post verified-docs)))]
                       posted-last-post)]
    retval))

;;
;; SUPPORT FUNCTIONS
;;

;; The counter for the last-post filename's suffix is in an s3 object.
;; TODO We'll need to hash it? or try serializing with a global semaphore.
(def last-post-tmp "/tmp/last-post.edn")
(def s3-last-post-counter-params {:localFile last-post-tmp
                                  :s3Params {:Bucket "javelintest"
                                             :Key "last-post.edn"}})

;; TODO use the Bluebird Promise.promisify facility to transform this callback API into a Promise one.
;; Learning note: the instantiation of the downloader object needs to also be wrapped by the p/promise
;; so errors on instantiation that may be thrown are caught.
(defn fetch-last-post
  "Fetches the last-post.edn file from s3, asynchronously. Resolves to the number of the last post."
  []
  (p/promise (fn [resolve reject]
               (let [downloader (.downloadFile s3-client (clj->js s3-last-post-counter-params))]
                 (.on downloader "end" #(let [last-post-obj (->> (.readFileSync fs last-post-tmp "utf8")
                                                                 read-string)
                                              value (:last-post (js->clj last-post-obj))]
                                          (js/console.log (str/join ["Old last-post = " value]))
                                          (resolve value)))
                 (.on downloader "error" #(do
                                            (js/console.log (str/join ["Got error = " %]))
                                            (reject {:error %})))))))

(defn post-doc
  "Computes html and posts record to s3 using document number docnum, asynchronously.  Resolves to a keyword :success."
  [record docnum]
  (p/promise (fn [resolve reject]
               (let [my-name (gstring/format "document%06d.html" docnum)
                     local-file (str/join ["/tmp/" my-name]) 
                     s3-params {:localFile local-file
                                :s3Params {:Bucket "javelintest"
                                           :Key my-name}}
                     my-text (.render md (str (b64/decodeString (-> record :kinesis :data))))
                     _ignore (.writeFileSync fs local-file my-text)
                     uploader (.uploadFile s3-client (clj->js s3-params))]
                 (.on uploader "end" (fn [] (resolve :success)))
                 (.on uploader "error" (fn [r] (reject {:error r})))))))

(defn post-docs
  "Walks Records, calling post-doc, asynchronously.  Resolves to a list of statuses."
  [last-post {:keys [Records] :as event} ctx]
  (let [next (+ last-post 1)
        new-last (+ last-post (count Records))]
    (p/all (map post-doc Records (range next (+ 1 new-last))))))

(defn verify-docs
  "Checks that this is a list of status :success, asynchronously.
   Resolves to the length of the list if ok. Rejects otherwise.
   Wrapped in a promise so it can participate in a p/alet."
  [docs-status]
  (p/promise (fn [resolve reject]
               (if (every? #(= :success %) docs-status)
                 (resolve (count docs-status))
                 (reject {:failed-post-docs docs-status})))))

(defn post-last-post
  "Posts (writes) back a new last-post.edn file to s3, asynchronously.
   Resolves to the new last-post number."
  [new-last-post]
  ;; Store back last-post number
  (p/promise (fn [resolve reject]
               (.writeFileSync fs last-post-tmp (pr-str {:last-post new-last-post}))
               (let [uploader (.uploadFile s3-client (clj->js s3-last-post-counter-params))]
                 (.on uploader "end" (fn []
                                       (js/console.log (str/join ["New last-post = " new-last-post]))
                                       (resolve new-last-post)))
                 (.on uploader "error" (fn [r] (reject {:error r})))))))
   

