(ns file-upload.temporary-file
  (:require [mount.core :refer [defstate]]
            [taoensso.timbre :as timbre])
  (:import (java.io File)))

(defprotocol TemporaryFileStore
  (store [this id ^String name ^File file] "Remember the given temporary file via the supplied ID")
  (delete [this id] "Delete the given file from storage, if it still exists.")
  (^File retrieve [this id] "Retrieve the temporary file for the given ID, if it has not yet been GC'd. Returns a map with :name and :file or nil"))

(defstate temporary-file-store
  :start
  (let [storage (atom {})]
    (reify
      TemporaryFileStore
      (delete [this id]
        (let [^File file (get-in @storage [id :file])
              ok?        (.delete file)]
          (timbre/info "Deleted" file "?" ok?)
          (swap! storage dissoc id)))
      (store [this id name file]
        (swap! storage assoc id {:name name
                                 :file file}))
      (retrieve [this id] (get @storage id)))))
