(ns file-upload.file-upload
  (:require
    #?@(:clj  [[fulcro.client.dom-server :as dom]
               [fulcro.server :refer [defquery-root defquery-entity defmutation]]
               [file-upload.temporary-file :as tmp]
               [ring.util.response :as resp :refer [response file-response resource-response]]]
        :cljs [[fulcro.client.dom :as dom]
               [fulcro.client.mutations :as m :refer [defmutation]]])
    [promenade.core :as prom]
    [clojure.pprint :refer [cl-format]]
    [fulcro.client.util :refer [transit-str->clj transit-clj->str]]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [taoensso.timbre :as timbre]
    [fulcro.client.network :as net]
    [clojure.spec.alpha :as s])
  #?(:clj (:import (java.util UUID))))

(let [kB 1024
      MB (* kB kB)
      GB (* MB kB)]
  (defn human-file-size
    "Returns a size scaled to human-convenient form:

    0 <= size <= 1024 => bytes
    1024 < size < 1024^2 => kB
    1024^2 < size < 1024^3 => MB
    1024^3 < size => GB
    "
    [size-in-bytes]
    (cond
      (<= 0 size-in-bytes kB) (str size-in-bytes " bytes")
      (<= kB size-in-bytes MB) (cl-format nil "~,2F kB" (/ size-in-bytes kB))
      (<= MB size-in-bytes GB) (cl-format nil "~,2F MB" (/ size-in-bytes MB))
      :else (cl-format nil "~,2F GB" (/ size-in-bytes GB)))))

(s/def ::id any?)
(s/def ::filename string?)
(s/def ::js-file (s/and map? #(contains? (meta %) :js-file)))
(s/def ::progress (s/and int? #(<= 0 % 100)))
(s/def ::abort-id uuid?)
(s/def ::status #{:uploading :network-error :too-large :complete})

(s/def ::file (s/keys
                :req [::id ::filename ::js-file]
                :opt [::progress ::status ::abort-id]))

(defn file-path
  "Returns a vector to the location of a file object (or field) in the state database."
  ([id] [::file-by-id id])
  ([id field] [::file-by-id id field]))

(defsc File [this {::keys [id progress filename size status abort-id]} {:keys [onCancel onDelete]}]
  {:query [::id ::filename ::js-file ::progress ::status ::size ::abort-id]
   :ident (fn [] (file-path id))}
  (let [progress-string (str progress "%")
        size-string     (human-file-size size)]
    ;; A default rendering, could add a computed prop to pass a rendering function in
    (dom/div {:style {:padding "1px"}}
      (dom/div :.ui.label
        (dom/div
          (str filename " (" size-string ") ")
          (cond
            (= :network-error status) (dom/div :.ui.left.pointing.red.basic.label "Upload Failed (network error).")
            (= :too-large status) (dom/div :.ui.left.pointing.red.basic.label "Failed. Too large."))
          (dom/i :.times.circle.icon {:onClick (fn []
                                                 (when onCancel
                                                   (onCancel id abort-id))
                                                 (when onDelete
                                                   (onDelete id)))}))
        (when (= :uploading status)
          (dom/div :.ui.bottom.attached.progress (cond-> {}
                                                   (= 100 progress) (assoc :className "success"))
            (dom/div :.bar {:style {:transitionDuration "300ms"
                                    :width              progress-string}})))))))

(let [factory (prim/factory File {:keyfn ::id})]
  (defn ui-file [props callbacks]
    (factory (prim/computed props callbacks))))

#?(:cljs
   (defn add-file*
     "Mutation helper: Add a file to the state database, and set it's status to either :uploading or :too-large
     based on size-limit."
     [state-map {::keys [id size] :as file} size-limit]
     (cond-> (prim/merge-component state-map File file)
       (>= size size-limit) (assoc-in (file-path id ::status) :too-large))))

#?(:cljs
   (s/fdef add-file*
     :args (s/cat :state map? :file ::file :size-limit pos-int?)
     :ret map?))

#?(:cljs
   (defn update-progress*
     "Mutation helper: Update the progress of the given file in the database."
     [state-map {::keys [id] :as params}]
     (let [progress (net/progress% params :sending)
           progress (if (js/isNaN progress) 100 progress)]
       (assoc-in state-map (file-path id ::progress) progress))))

#?(:cljs
   (s/fdef update-progress*
     :args (s/cat
             :state map?
             :params (s/keys :req [::id ::net/progress]))
     :ret map?))

#?(:cljs
   (defmutation update-progress
     "Mutation: Given a network event for a File upload, update the associated file with the progress."
     [params]
     (action [{:keys [state]}]
       (swap! state update-progress* params))))

#?(:cljs
   (defn new-file
     "Create a map representing a file for use with the file upload system.

     id - Typically a (prim/tempid)
     file - A js/File of the file (will be held in metadata to prevent state serialization problems)
     abort-id - An ID (uuid recommended) for use with aborting the network request if desired.
     "
     ([id file abort-id]
      (cond->
        {::id       id
         ::progress 0
         ::filename (.-name file)
         ::status   :uploading
         ::size     (.-size file)
         ::js-file  (with-meta {} {:js-file file})}
        abort-id (assoc ::abort-id abort-id)))
     ([id file]
      (new-file id file nil))))

#?(:cljs
   (s/fdef new-file
     :args (s/cat :id any? :file any?)
     :ret ::file))

#?(:cljs
   (defn evt->upload-files
     "Returns a sequence of maps that describe the file uploads indicated by the file-input-change-event, and
     conform to the ::file spec. This is used to transform a normal DOM inputs onChange event to files."
     [file-input-change-event]
     (let [js-file-list (.. file-input-change-event -target -files)]
       (mapv (fn [file-idx]
               (let [fid      (prim/tempid)
                     js-file  (.item js-file-list file-idx)
                     abort-id (random-uuid)]
                 (new-file fid js-file abort-id)))
         (range (.-length js-file-list))))))

#?(:cljs
   (s/fdef evt->upload-files
     :args (s/cat :event any?)
     :ret (s/coll-of ::file)))

;; ================================================================================
;; Client Network Plumbing
;; ================================================================================

#?(:cljs
   (def file-upload-middleware
     "Middleware for converting a Fulcro request (containing a file upload mutation) into the proper
     request for transmission to the server as a multipart form."
     (fn [req]
       (let [mutation (some-> req :body prim/query->ast1)   ; converts the mutation to an AST for easy access to elements
             params   (:params mutation)
             {::keys [id filename js-file]} params          ;; the sole parameter is a ::file
             file     (some-> js-file meta :js-file)
             form     (js/FormData.)]
         (.append form "file" file)
         (.append form "id" (transit-clj->str id))          ; encode the fulcro tempid to a string
         (.append form "name" filename)
         ;; return a request that has the js/Form as the thing to send. This will cause a POST from XhrIO
         (assoc req :body form :headers {} :method :post)))))

#?(:cljs
   (def file-response-middleware
     "Middleware for converting the file upload server's response to something usable on the client (mainly
     tempid remapping). The server middleware will respond with tempid remapping (we send a tempid with the upload).
     If there is an error, we'll also see it here, but instead of letting it leak to the API error handling routines
     of Fulcro we instead just update app state with the result."
     (->
       (fn [resp]
         (let [ast     (prim/query->ast1 (:transaction resp)) ; what we originally sent (which has the tempid)
               {::keys [id]} (:params ast)
               ;; if ok, the body will have the server response, which will be:
               ;; {'upload {:tempids {old-id new-id}}} (the result of the "mutation"...the name doesn't matter)
               ;; If there is an error, we'll see what kind in error of resp
               {:keys [body error]} resp
               real-id (get-in body ['upload ::prim/tempids id])]
           ;; Keep the Fulcro network error stack out of the error handling path by returning a 200 status code
           ;; no matter what. ALSO, change the transaction to a QUERY that looks like a File query for the
           ;; file we're seeing a result for, but just for the ID and STATUS fields. When we modify the body
           ;; we therefore cause the Fulcro plumbing to merge this new status with the existing object in the
           ;; database. (query is something like [{[::file-by-id id] [::id ::status]}] and data will be something
           ;; like {[::file-by-id id] {::id 345 ::status :complete}.  When the body and tx are seen by fulcro it
           ;; just does a merge of it.  NOTE: the tempid remappings are keyed by a mutation name, and are kept
           ;; in the body. This causes tempid remapping as well.
           (cond-> (assoc resp :error :none :status-code 200 :transaction [{(file-path real-id) [::id ::status]}])
             ;; Associate actual failure data with the real File entry in the database
             (= error :network-error) (assoc :body (merge body {(file-path id) {::id id ::status :network-error}}))
             (= error :http-error) (assoc :body (merge body {(file-path id) {::id id ::status :network-error}}))
             ;; Or just the success if that's what we have.
             (and real-id (= error :none)) (assoc :body (merge body {(file-path real-id) {::id real-id ::status :complete}})))))
       (net/wrap-fulcro-response))))

;; ================================================================================
;; Server Network Plumbing
;; ================================================================================

#?(:clj
   (defmutation delete-file
     "Server-side mutation for removing a file from the temporary store"
     [{:keys [id]}]
     (action [env]
       (tmp/delete tmp/temporary-file-store id)
       nil)))

#?(:clj
   (defn wrap-file-uploads
     "Middleware for receiving file uploads. Most of the heavy lifting is already done by Ring (the file is already
     on your disk in some temp folder/file. We just pull out the parameters from the request and generate a response.
     Some kind of session validation and size security is also in order (TODO)."
     [handler path]
     (fn [{:keys [uri] :as req}]
       (if (= uri path)
         (let [id      (transit-str->clj (get-in req [:params "id"]))
               file    (get-in req [:params "file" :tempfile])
               name    (get-in req [:params "name"] "unknown")
               real-id (UUID/randomUUID)]
           (timbre/info (:params req))
           (prom/if-mlet [result (prom/!
                                   (do
                                     (timbre/info "File upload " real-id name file)
                                     ;; the idea is that you upload files, and then once uploads are complete you have file
                                     ;; objects on the client that know their "real ID" in the temporary file store. Then you'd
                                     ;; code something in your UI (like a "commit/save") that would send mutations with the
                                     ;; temporary storage ID to tell your server to move the disk file to a permanent storage
                                     ;; system and associate things in the database...that is left to the reader.
                                     (tmp/store tmp/temporary-file-store real-id name file)
                                     (-> (resp/response {'upload {::prim/tempids {id real-id}}})
                                       (resp/content-type "application/transit+json"))))]
             result
             (-> (resp/response {}) (resp/status 400))))
         (handler req)))))

