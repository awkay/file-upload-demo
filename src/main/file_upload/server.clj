(ns file-upload.server
  (:require
    [promenade.core :as prom]
    [clojure.java.io :as io]
    [fulcro.server :as server]
    [fulcro.client.util :refer [transit-str->clj]]
    [immutant.web :as web]
    [mount.core :as mount :refer [defstate ]]
    [ring.middleware.content-type :refer [wrap-content-type]]
    [ring.middleware.gzip :refer [wrap-gzip]]
    [ring.middleware.params :refer [wrap-params]]
    [ring.middleware.multipart-params :refer [wrap-multipart-params]]
    [ring.middleware.not-modified :refer [wrap-not-modified]]
    [ring.middleware.resource :refer [wrap-resource]]
    [ring.util.response :as resp :refer [response file-response resource-response]]
    [taoensso.timbre :as timbre])
  (:import (java.util UUID)))

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

(defn wrap-file-uploads [handler path]
  (fn [{:keys [uri] :as req}]
    (if (= uri path)
      (let [id      (transit-str->clj (get-in req [:params "id"] 1))
            file    (get-in req [:params "file" :tempfile])
            name    (get-in req [:params "name"] "unknown")
            real-id (UUID/randomUUID)]
        (prom/if-mlet [result (prom/!
                                (do
                                  (timbre/info "File upload " real-id name file)
                                  ;(tmp/store temporary-file-store real-id name file)
                                  (-> (resp/response {'upload {:tempids {id real-id}}})
                                    (resp/content-type "application/transit+json"))))]
          result
          (-> (resp/response {}) (resp/status 400))))
      (handler req))))

(defstate middleware
  :start
  (-> not-found-handler
    (wrap-file-uploads "/file-uploads")
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

