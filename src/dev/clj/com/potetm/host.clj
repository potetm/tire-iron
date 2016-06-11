(ns com.potetm.host
  (:require [cemerick.piggieback :as pb]
            [cljs.build.api :as build]
            [cljs.repl.browser :as browser]
            [cljs.repl.nashorn :as nashorn]
            [cljs.repl.node :as node]
            [cljs.repl.rhino :as rhino]
            [cljs.repl :as repl]
            [clojure.java.io :as io]
            [com.potetm.tire-iron :as ti]))

(defn browser-build []
  (build/build "src/dev/cljs-browser"
               {:main 'com.potetm.browser-client
                :output-to "target/public/js/client.js"
                :output-dir "target/public/js"
                :asset-path "js"
                :source-map true
                :optimizations :none
                :pretty-print true
                :verbose false
                :parallel-build true}))

(defn- browser-repl-info []
  {:env (browser/repl-env :src "src/dev/cljs-browser"
                          :working-dir "target/cljs-repl"
                          :serve-static true
                          :static-dir "target/public")
   :repl-args {:watch "src/dev/cljs-browser"
               :analyze-path "src/dev/cljs-browser"
               :output-dir "target/public/js"
               :special-fns (ti/special-fns
                              {:source-dirs ["src/dev/cljs-browser"]
                               :state 'com.potetm.browser-client/state
                               :before 'com.potetm.browser-client/before
                               :after 'com.potetm.browser-client/after})}})

(defn browser-repl []
  (let [{:keys [env repl-args]} (browser-repl-info)]
    (repl/repl* env repl-args)))

(defn browser-nrepl []
  (let [{:keys [env repl-args]} (browser-repl-info)]
    (apply pb/cljs-repl env (apply concat repl-args))))

(defn nashorn-start []
  (pb/cljs-repl (nashorn/repl-env)
                :watch "src/dev/cljs-nashorn"
                :analyze-path "src/dev/cljs-nashorn"
                :output-dir "target/public/js"
                :special-fns (ti/special-fns
                               ;; not as important since we don't have to maintain connection
                               ;; in nashorn, but still useful if you want to hold on to some
                               ;; state
                               {:source-dirs ["src/dev/cljs-nashorn"]
                                :state 'com.potetm.nashorn-client/state
                                :before 'com.potetm.nashorn-client/before
                                :after 'com.potetm.nashorn-client/after})))
(comment
  ;; These two seem to start up, but I can't even get namespaces
  ;; working properly, so, there's a lot of work to do if I'm ever
  ;; going to support them.
  ;;
  ;; TODO: Try with weasel
  (defn node-start []
    (pb/cljs-repl (node/repl-env)
                  :watch "src/dev/cljs"
                  :analyze-path "src/dev/cljs"
                  :output-dir "target/public/js"
                  :special-fns (ti/special-fns
                                 {:source-dirs ["src/dev/cljs"]
                                  :state 'com.potetm.client/state
                                  :before 'com.potetm.client/before
                                  :after 'com.potetm.client/after})))

  (defn rhino-start []
    (pb/cljs-repl (rhino/repl-env)
                  :watch "src/dev/cljs"
                  :analyze-path "src/dev/cljs"
                  :output-dir "target/public/js"
                  :special-fns (ti/special-fns
                                 {:source-dirs ["src/dev/cljs"]
                                  :state 'com.potetm.client/state
                                  :before 'com.potetm.client/before
                                  :after 'com.potetm.client/after}))))


(defn copy-index-to-target []
  (let [target-dir (io/file "target/public")]
    (.mkdirs target-dir)
    (io/copy (io/file "resources/dev/index.html")
             (io/file target-dir "index.html"))))

(defn clean []
  (letfn [(del [f]
            (when (.isDirectory f)
              (doseq [c (.listFiles f)]
                (del c)))
            (io/delete-file f true))]
    (del (io/file "target"))))
