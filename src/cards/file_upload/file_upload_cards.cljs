(ns file-upload.file-upload-cards
  (:require [devcards.core]
            [file-upload.file-upload :as fu]
            [fulcro.client.cards :refer [defcard-fulcro make-root]]
            [fulcro.client.network :as net]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [fulcro.client.mutations :as m]
            [fulcro.client :as fc]))

;; fulcro devcard automatically put the app into an atom named after the card
(declare live-file-upload-demo-fulcro-app)

(defmutation abort-upload
  "Global mutation for aborting requests (by abort ID, which we use a UUID for)"
  [{:keys [id]}]
  (action [env]
    (when (and (some-> live-file-upload-demo-fulcro-app deref) id)
      (fc/abort-request! @live-file-upload-demo-fulcro-app id))))

(def max-file-size (* 2 1024 1024))

(defmutation upload-file
  "Mutation: Start uploading a file. This mutation will always be something the app itself writes, because the UI
  will need to be updated. That said, adding the file to the client database is done via the helpers
  add-file* and file-path."
  [{::fu/keys [id size abort-id] :as file}]
  (action [{:keys [state]}]
    (swap! state (fn [s]
                   (-> s
                     ;; this part you'll always do. won't add the file if it is too big
                     (fu/add-file* file max-file-size)
                     ;; this part is specific to THIS demo UI...the file-path function gives the ident of the file
                     (prim/integrate-ident (fu/file-path id) :append [:COMPONENT :FILE-UPLOAD :files])))))
  (file-upload [{:keys [ast]}]
    (when-not (>= size max-file-size)                       ; don't even start networking if the file is too big.
      (-> ast
        (m/with-abort-id abort-id)
        (m/with-progressive-updates `(fu/update-progress ~file))))))

(defn delete-file-from-list*
  "Local mutation helper to remove a file from this demo's file list."
  [state-map id]
  (update-in state-map [:COMPONENT :FILE-UPLOAD :files] (fn [list] (vec (filter #(not= (fu/file-path id) %) list)))))

(defn delete-file*
  "Local mutation helper to remove a file from the file table."
  [state-map id]
  (update state-map (first (fu/file-path id)) dissoc id))

(defmutation delete-file
  "Mutation to delete file from UI and client database. Client-specific since it has to modify UI state that is unknown
  to file upload system."
  [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state (fn [s] (-> s
                           (delete-file-from-list* id)
                           (delete-file* id)))))
  ;; remote side runs if the ID is real, so the server can clean it up. We rename the mutation in order to leverage
  ;; the pre-written delete file mutation on the server (see server portion of file-upload namespace).
  (remote [{:keys [ast]}]
    (when-not (prim/tempid? id)
      (assoc ast :key `fu/delete-file :dispatch-key `fu/delete-file))))

(defsc FileUpload
  "A demo of how one might display a collection of files for upload."
  [this {:keys [id files] :as props}]
  {:query         [:id {:files (prim/get-query fu/File)}]
   :initial-state {:id "upload" :files []}
   :ident         (fn [] [:COMPONENT :FILE-UPLOAD])}
  (dom/div nil
    (dom/label :.ui.button {:htmlFor id}
      "Add Files"
      (dom/input {:id       id
                  :onChange (fn [evt]
                              (prim/transact! this
                                (mapv (fn [file] `(upload-file ~file)) (fu/evt->upload-files evt))))
                  :name     id
                  :multiple true
                  :type     "file"
                  :value    ""
                  :style    {:display "none"}}))
    (dom/div
      (map (fn [f] (fu/ui-file f {:onCancel (fn [id abort-id]
                                              (prim/transact! this `[(abort-upload {:id ~abort-id})]))
                                  :onDelete (fn [id]
                                              (prim/transact! this `[(delete-file {:id ~id})]))})) files))))

(defcard-fulcro live-file-upload-demo
  (make-root FileUpload {})
  {}
  {:inspect-data true
   ;; Must install remotes for the normal API requests, AND one for the file uploads. See file-upload namespace
   ;; for the middleware definitions
   :fulcro       {:networking {:remote      (net/fulcro-http-remote {:url "/api"})
                               :file-upload (net/fulcro-http-remote {:url                 "/file-uploads"
                                                                     :response-middleware fu/file-response-middleware
                                                                     :serial?             false ; let uploads go in parallel
                                                                     :request-middleware  fu/file-upload-middleware})}}})
