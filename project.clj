(defproject tire-iron "0.1.0-SNAPSHOT"
  :description "Reloaded Workflow for ClojureScript"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.8.51"]
                 [org.clojure/tools.namespace "0.3.0-alpha3"]]
  :source-paths ["src/prod/clj"]
  :resource-paths ["resources/prod"]
  :profiles {:cljs-repl {:dependencies [[com.cemerick/piggieback "0.2.1"]]
                         :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
                         :source-paths ["src/dev/clj"
                                        "src/dev/cljs"]
                         :resource-paths ["resources/dev"]}})
