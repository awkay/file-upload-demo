(defproject file-upload "0.1.0-SNAPSHOT"
  :description "My Cool Project"
  :license {:name "MIT" :url "https://opensource.org/licenses/MIT"}
  :min-lein-version "2.7.0"

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [thheller/shadow-cljs "2.4.5"]
                 [fulcrologic/fulcro "2.5.11-SNAPSHOT"]
                 [promenade "0.5.2"]
                 [com.taoensso/timbre "4.10.0"]
                 [mount "0.1.12"]
                 [ring/ring-core "1.6.3"]
                 [org.immutant/web "2.1.10"]
                 [bk/ring-gzip "0.2.1"]]

  :uberjar-name "file_upload.jar"

  :source-paths ["src/main"]

  :profiles {:cljs {:source-paths ["src/main" "src/cards"]
                    :dependencies [[binaryage/devtools "0.9.10"]
                                   [org.clojure/core.async "0.4.474"]
                                   [fulcrologic/fulcro-inspect "2.2.0-beta6"]
                                   [devcards "0.2.4" :exclusions [cljsjs/react cljsjs/react-dom]]]}
             :dev  {:source-paths ["src/dev" "src/main" "src/cards"]

                    :dependencies [[org.clojure/tools.namespace "0.3.0-alpha4"]
                                   [org.clojure/tools.nrepl "0.2.13"]
                                   [com.cemerick/piggieback "0.2.2"]]
                    :repl-options {:init-ns          user
                                   :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}}})
