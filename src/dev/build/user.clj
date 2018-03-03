(ns user
  (:require [cemerick.piggieback :as pb]
            [cljs.build.api :as build]
            [cljs.repl :as repl]
            [cljs.repl.browser :as browser]
            [cljs.repl.nashorn :as nashorn]
            [cljs.repl.node :as node]
            [cljs.repl.rhino :as rhino]
            [clojure.java.io :as io]
            [clojure.tools.namespace.repl :as r]
            [com.potetm.tire-iron :as ti]
            [weasel.repl.websocket :as weasel]
            [weasel.repl.server :as wserver])
  (:import (java.io File)))

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
   ;; However, you DO want these args to be identical to your build args.
   ;; This ensures that re-compiles are exactly equivalent to your initial build.
   :repl-args {:main 'com.potetm.browser-client
               :output-to "target/public/js/client.js"
               :output-dir "target/public/js"
               :asset-path "js"
               :source-map true
               :optimizations :none
               :pretty-print true
               :verbose false
               :parallel-build true
               :analyze-path "src/dev/browser"
               :special-fns (ti/special-fns
                              :source-dirs ["src/dev/browser"]
                              :state 'com.potetm.browser-client/state
                              :before 'com.potetm.browser-client/before
                              :after 'com.potetm.browser-client/after)}})

(defn browser-repl []
  (let [{:keys [env repl-args]} (browser-repl-info)]
    (repl/repl* env repl-args)))

(defn browser-nrepl []
  (let [{:keys [env repl-args]} (browser-repl-info)]
    (apply pb/cljs-repl env (apply concat repl-args))))

(defn nashorn-nrepl []
  (pb/cljs-repl (nashorn/repl-env)
                :analyze-path "src/dev/nashorn"
                :output-dir "target/public/js"
                :special-fns (ti/special-fns
                               :source-dirs ["src/dev/nashorn"]
                               :state 'com.potetm.nashorn-client/state
                               :before 'com.potetm.nashorn-client/before
                               :after 'com.potetm.nashorn-client/after)))

(defn node-nrepl []
  (pb/cljs-repl (node/repl-env)
                :target :node
                :analyze-path "src/dev/node"
                :output-dir "target/public/js"
                :special-fns (ti/special-fns
                               :source-dirs ["src/dev/node"]
                               :state 'com.potetm.node-client/state
                               :before 'com.potetm.node-client/before
                               :after 'com.potetm.node-client/after)))

(defn rhino-nrepl []
  (pb/cljs-repl (rhino/repl-env)
                :analyze-path "src/dev/rhino"
                :output-dir "target/public/js"
                :special-fns (ti/special-fns
                               :source-dirs ["src/dev/rhino"]
                               :state 'my-ns.rhino-client/state
                               :before 'my-ns.rhino-client/before
                               :after 'my-ns.rhino-client/after)))

(defn weasel-build []
  (build/build "src/dev/weasel"
               {:main 'com.potetm.weasel-client
                :output-to "target/public/js/client.js"
                :output-dir "target/public/js"
                :asset-path "js"
                :source-map true
                :source-map-timestamp true
                :optimizations :none
                :pretty-print true
                :verbose false
                :parallel-build true}))

(defn weasel-nrepl []
  (pb/cljs-repl (weasel/repl-env :src "src/dev/weasel"
                                 :working-dir "target/cljs-repl"
                                 :serve-static true
                                 :static-dir "target/public")
                :analyze-path "src/dev/weasel"
                :main 'com.potetm.weasel-client
                :output-to "target/public/js/client.js"
                :output-dir "target/public/js"
                :asset-path "js"
                :source-map true
                :source-map-timestamp true
                :optimizations :none
                :pretty-print true
                :verbose false
                :parallel-build true
                :special-fns (ti/special-fns
                               :source-dirs ["src/dev/weasel"
                                             ;; you can watch macros files too!
                                             "src/dev/clj"]
                               :state 'com.potetm.weasel-client/state
                               :before 'com.potetm.weasel-client/before
                               :after 'com.potetm.weasel-client/after)))

(defn stop-weasel []
  (wserver/stop))

;; TODO: Try with austin

(defn copy-index-to-target []
  (let [target-dir (io/file "target/public")]
    (.mkdirs target-dir)
    (io/copy (io/file "resources/dev/index.html")
             (io/file target-dir "index.html"))))

(defn clean []
  (letfn [(del [^File f]
            (when (.isDirectory f)
              (doseq [c (.listFiles f)]
                (del c)))
            (io/delete-file f true))]
    (del (io/file "target"))
    (del (io/file "nashorn_code_cache"))))

(defn fresh-browser-repl []
  (r/refresh)
  (clean)
  (copy-index-to-target)
  (browser-build)
  (browser-nrepl))

(defn fresh-weasel-repl []
  (r/refresh)
  (clean)
  (copy-index-to-target)
  (weasel-build)
  (weasel-nrepl))

(defn fresh-node-repl []
  (r/refresh)
  (clean)
  (node-nrepl))
