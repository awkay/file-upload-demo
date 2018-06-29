(ns file-upload.server
  (:require
    [file-upload.file-upload :as fu]
    [fulcro.server :as server]
    [immutant.web :as web]
    [mount.core :refer [defstate]]
    [ring.middleware.content-type :refer [wrap-content-type]]
    [ring.middleware.gzip :refer [wrap-gzip]]
    [ring.middleware.params :refer [wrap-params]]
    [ring.middleware.multipart-params :refer [wrap-multipart-params]]
    [ring.middleware.not-modified :refer [wrap-not-modified]]
    [ring.middleware.resource :refer [wrap-resource]]
    [ring.util.response :refer [response file-response resource-response]]))

(defstate config
  :start (server/load-config {:config-path "config/dev.edn"}))

(def ^:private not-found-handler
  (fn [req]
    {:status  404
     :headers {"Content-Type" "text/plain"}
     :body    "NOPE"}))

(defstate server-parser :start (server/fulcro-parser))

(defn wrap-api [handler uri]
  (fn [request]
    (if (= uri (:uri request))
      (server/handle-api-request
        server-parser
        {}
        (:transit-params request))
      (handler request))))

(defstate middleware
  :start
  (-> not-found-handler
    (fu/wrap-file-uploads "/file-uploads")
    wrap-params
    wrap-multipart-params
    (wrap-api "/api")
    server/wrap-transit-params
    server/wrap-transit-response
    (wrap-resource "public")
    wrap-content-type
    wrap-not-modified
    wrap-gzip))

(defstate http-server
  :start
  (web/run middleware {:host "0.0.0.0"
                       :port (get config :port 3000)})
  :stop
  (web/stop http-server))

