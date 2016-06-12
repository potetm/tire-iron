(ns com.potetm.cljs-nrepl
  (:require [cemerick.piggieback :as pb]
            [cljs.build.api :as build]
            [cljs.repl :as repl]
            [cljs.repl.browser :as browser]
            [cljs.repl.nashorn :as nashorn]
            [cljs.repl.node :as node]
            [cljs.repl.rhino :as rhino]
            [clojure.java.io :as io]
            [com.potetm.tire-iron :as ti]))

(defn browser-build []
  (build/build "src/dev/browser"
               {:main 'com.potetm.browser-client
                :output-to "target/public/js/client.js"
                :output-dir "target/public/js"
                :asset-path "js"
                :source-map true
                :optimizations :none
                :pretty-print true
                :verbose false
                :parallel-build true}))

(defn browser-repl-info []
  ;; Fun note: don't use :optimizations :none for the browser repl.
  ;; (The other repls don't even appear to support it. Probably because payload is less of a deal.)
  ;; It assumes you'll be able to load goog.base yourself. Which you can't.
  ;; Even if you use a :main, you're yelled at when you load the repl js.
  ;; (I think it's because you're writing to the document in an iframe, but I'm not sure.)
  {:env (browser/repl-env :src "src/dev/browser"
                          :working-dir "target/cljs-repl"
                          :serve-static true
                          :static-dir "target/public")
   :repl-args {:watch "src/dev/browser"
               :analyze-path "src/dev/browser"
               :output-dir "target/public/js"
               :special-fns (ti/special-fns
                              {:source-dirs ["src/dev/browser"]
                               :state 'com.potetm.browser-client/state
                               :before 'com.potetm.browser-client/before
                               :after 'com.potetm.browser-client/after})}})

(defn browser-repl []
  (let [{:keys [env repl-args]} (browser-repl-info)]
    (repl/repl* env repl-args)))

(defn browser-nrepl []
  (let [{:keys [env repl-args]} (browser-repl-info)]
    (apply pb/cljs-repl env (apply concat repl-args))))

(defn nashorn-nrepl []
  (pb/cljs-repl (nashorn/repl-env)
                :watch "src/dev/nashorn"
                :analyze-path "src/dev/nashorn"
                :output-dir "target/public/js"
                :special-fns (ti/special-fns
                               {:source-dirs ["src/dev/nashorn"]
                                :state 'com.potetm.nashorn-client/state
                                :before 'com.potetm.nashorn-client/before
                                :after 'com.potetm.nashorn-client/after})))
(defn node-nrepl []
  (pb/cljs-repl (node/repl-env)
                :watch "src/dev/node"
                :analyze-path "src/dev/node"
                :output-dir "target/public/js"
                :special-fns (ti/special-fns
                               {:source-dirs ["src/dev/node"]
                                :state 'com.potetm.node-client/state
                                :before 'com.potetm.node-client/before
                                :after 'com.potetm.node-client/after})))

(defn rhino-nrepl []
  (pb/cljs-repl (rhino/repl-env)
                :watch "src/dev/rhino"
                :analyze-path "src/dev/rhino"
                :output-dir "target/public/js"
                :special-fns (ti/special-fns
                               {:source-dirs ["src/dev/rhino"]
                                :state 'my-ns.rhino-client/state
                                :before 'my-ns.rhino-client/before
                                :after 'my-ns.rhino-client/after})))

;; TODO: Try with weasel & austin

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
    (del (io/file "target"))
    (del (io/file "nashorn_code_cache"))))
