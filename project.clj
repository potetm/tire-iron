(defproject com.potetm/tire-iron "0.3.0"
  :description "Reloaded Workflow for ClojureScript"
  :url "https://github.com/potetm/tire-iron"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/tools.namespace "0.3.0-alpha3"]]

  :source-paths ["src/prod/clj"]
  :resource-paths ["resources/prod"]
  :profiles {:provided {:dependencies [[org.clojure/clojure "1.8.0"]
                                       [org.clojure/clojurescript "1.9.293"]]}
             :dev {:source-paths ["src/dev/clj"
                                  "src/dev/build"]
                   :resource-paths ["resources/dev"]}
             :cljs-repl [:dev
                         {:source-paths ["src/dev/browser"
                                         "src/dev/weasel"
                                         "src/dev/nashorn"
                                         "src/dev/node"
                                         "src/dev/rhino"]}]
             :cljs-nrepl [:cljs-repl
                          {:dependencies [[com.cemerick/piggieback "0.2.1"]
                                          [weasel "0.7.0"]]
                           :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}}]})
