(ns file-upload.intro
  (:require [devcards.core :as dc]
            [fulcro.client.cards :refer [defcard-fulcro make-root]]
            [fulcro.client.dom :as dom :refer [div span]]
            [goog.object :as gobj]
            [fulcro.client.primitives :as prim :refer [defui defsc InitialAppState initial-state]]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [cljs.spec.alpha :as s]
            [fulcro.client.network :as net]
            [fulcro.client.util :as util]))

(s/def ::id any?)
(s/def ::filename string?)
(s/def ::js-file (s/and map? #(contains? (meta %) :js-file)))
(s/def ::progress (s/and int? #(<= 0 % 100)))
(s/def ::status #{:uploading :failed :complete})

(s/def ::file (s/keys
                :req [::id ::filename ::js-file]
                :opt [::progress ::status]))

(defn file-path
  ([id] [::file-by-id id])
  ([id field] [::file-by-id id field]))

(defn update-progress* [state-map {::keys [id] :as params}]
  (let [progress (net/progress% params :sending)
        progress (if (js/isNaN progress) 100 progress)]
    (assoc-in state-map (file-path id ::progress) progress)))

(s/fdef update-progress*
  :args (s/cat
          :state map?
          :params (s/keys :req [::id ::net/progress]))
  :ret map?)

(defn- render-file [{::keys [filename progress status]}]
  (let [progress-string (str progress "%")]
    (dom/div :.ui.grid
      (dom/div :.row
        (dom/div :.six.wide.column filename)
        (if (= :uploading status)
          (dom/div :.ten.wide.column
            (dom/div :.ui.progress (cond-> {}
                                     (= 100 progress) (assoc :className "success"))
              (dom/div :.bar {:style {:transitionDuration "300ms"
                                      :width              progress-string}}
                (dom/div :.progress progress-string))
              (dom/div :.label "Uploading file"))))))))

(s/fdef render-file
  :args (s/cat :file ::file)
  :ret any?)

(defsc File [this {::keys [id] :as props}]
  {:query [::id ::filename ::js-file ::progress ::status]
   :ident (fn [] (file-path id))}
  (render-file props))

(def ui-file (prim/factory File {:keyfn ::id}))

(defsc ProgressControl [this {:keys [ui/progress] :as props}]
  {:query         [:ui/progress]
   :ident         (fn [] [:COMP :PROGRESSCONRTOL])
   :initial-state {:ui/progress 20}}
  (dom/div
    (ui-file {::id 1 ::js-file (with-meta {} {:js-file nil}) ::filename "sample.jpg" ::progress progress} nil "TODO")
    (dom/button :.ui.button {:onClick #(m/set-integer! this :ui/progress :value (- progress 20))} "Less")
    (dom/button :.ui.button {:onClick #(m/set-integer! this :ui/progress :value (+ progress 20))} "More")))

(def ui-progress-control (prim/factory ProgressControl))

(defcard-fulcro file-sample (make-root ProgressControl {}))

(defmutation update-progress [params]
  (action [{:keys [state]}]
    (js/console.log :update params)
    (swap! state update-progress* params)))

(defn new-file [id name file]
  {::id       id
   ::progress 0
   ::filename name
   ::status   :uploading
   ::js-file  (with-meta {} {:js-file file})})

(s/fdef new-file
  :args (s/cat :id any? :name string? :file any?)
  :ret ::file)

(defmutation upload-file [{:keys [id file]}]
  (action [{:keys [state]}]
    (let [name (some-> file meta :js-file .-name)]
      (swap! state (fn [s] (-> s
                             (prim/merge-component File (new-file id name file))
                             (prim/integrate-ident (file-path id) :append [:COMPONENT :FILE-UPLOAD :files]))))))
  (file-upload [{:keys [ast]}]
    (m/with-progressive-updates ast `(update-progress {::id ~id}))))

(def file-upload-middleware
  (fn [req]
    (let [mutation (some-> req :body prim/query->ast1)
          params   (:params mutation)
          id       (:id params)
          js-file  (some-> params :file meta :js-file)
          name     (some-> js-file (.-name))
          form     (js/FormData.)]
      (.append form "file" js-file)
      (.append form "id" (util/transit-clj->str id))
      (.append form "name" name)
      (assoc req :body form :headers {} :method :post))))

(defsc FileUpload [this {:keys [id files] :as props}]
  {:query         [:id {:files (prim/get-query File)}]
   :initial-state {:id "upload" :files []}
   :ident         (fn [] [:COMPONENT :FILE-UPLOAD])}
  (dom/div nil
    (dom/label {:htmlFor id}
      "Add Files"
      (dom/input {:id       id
                  :onChange (fn [evt]
                              (let [js-file-list (.. evt -target -files)]
                                (prim/transact! this
                                  (mapv (fn [file-idx]
                                          (let [fid     (prim/tempid)
                                                js-file (with-meta {} {:js-file (.item js-file-list file-idx)})]
                                            `(upload-file ~{:id fid :file js-file})))
                                    (range (.-length js-file-list))))))
                  :name     id
                  :type     "file"
                  :value    ""
                  :style    {:display "none"}})
      (dom/ul
        (map ui-file files)))))

(def file-response-middleware
  (->
    (fn [resp]
      (let [ast     (prim/query->ast1 (:transaction resp))
            {:keys [id]} (:params ast)
            {:keys [body]} resp
            real-id (get-in body ['upload :tempids id])]
        (assoc resp :transaction [{(file-path real-id) [::id ::status]}]
                    :body (merge body {(file-path real-id) {::id real-id ::status :complete}}))))
    (net/wrap-fulcro-response)))

(defcard-fulcro live-file-upload-demo
  (make-root FileUpload {})
  {}
  {:inspect-data true
   :fulcro       {:networking {:remote      (net/fulcro-http-remote {})
                               :file-upload (net/fulcro-http-remote {:url                 "/file-uploads"
                                                                     :response-middleware file-response-middleware
                                                                     :request-middleware  file-upload-middleware})}}})
