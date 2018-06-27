(ns user
  (:require
    [clojure.tools.namespace.repl :as tools-ns :refer [set-refresh-dirs]]
    file-upload.server
    [mount.core :as mount]))
;; === SHADOW REPL ===

(comment
  ;; evaluate any one of these in your nREPL to
  ;; choose a (running and connected) shadown-CLJS nREPL
  (do
    (require '[shadow.cljs.devtools.api :as shadow])
    (shadow/nrepl-select :main))


  (do
    (require '[shadow.cljs.devtools.api :as shadow])
    (shadow/nrepl-select :test))

  (do
    (require '[shadow.cljs.devtools.api :as shadow])
    (shadow/nrepl-select :cards)))


;; ==================== SERVER ====================

(set-refresh-dirs "src/dev" "src/main" )


(defn go []
  (mount/start))

(defn restart []
  (mount/stop)
  (tools-ns/refresh :after 'user/go ))
