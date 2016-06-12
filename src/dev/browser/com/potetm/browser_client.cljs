(ns com.potetm.browser-client
  (:require [clojure.browser.repl :as repl]
            [com.potetm.nashorn-other :as other-ns]))

(defonce state {:repl-conn (repl/connect "http://localhost:9000/repl")})

(defn before []
  (println "before"))

(defn after []
  (println "after"))

(defn dependent-fn []
  (other-ns/my-fun))
