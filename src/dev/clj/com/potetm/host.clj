(ns com.potetm.host
  (:require [cemerick.piggieback :as pb]
            [cljs.build.api :as build]
            [cljs.repl.browser :as browser]
            [cljs.repl :as repl]
            [clojure.java.io :as io]
            [com.potetm.tire-iron :as ti]))

(defn first-build []
  (build/build "src/dev/cljs"
               {:main 'com.potetm.client
                :output-to "target/public/js/client.js"
                :output-dir "target/public/js"
                :asset-path "js"
                :source-map true
                :optimizations :none
                :pretty-print true
                :verbose false
                :parallel-build true}))

(defn nrepl-start []
  (pb/cljs-repl (browser/repl-env :src "src/dev/cljs"
                                  :serve-static true
                                  :static-dir "target/public")
                :watch "src/dev/cljs"
                :analyze-path "src/dev/cljs"
                :output-dir "target/public/js"
                :special-fns (ti/special-fns
                               {:source-dirs ["src/dev/cljs"]
                                :state 'com.potetm.client/state
                                :before 'com.potetm.client/before
                                :after 'com.potetm.client/after})))

(defn repl-start []
  (repl/repl (browser/repl-env :src "src/dev/cljs"
                               :serve-static true
                               :static-dir "target/public")
             :watch "src/dev/cljs"
             :analyze-path "src/dev/cljs"
             :output-dir "target/public/js"
             :special-fns (ti/special-fns
                            {:source-dirs ["src/dev/cljs"]
                             :state 'com.potetm.client/state
                             :before 'com.potetm.client/before
                             :after 'com.potetm.client/after})))

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
