(ns com.potetm.weasel-client
  (:require [weasel.repl :as repl]
            [com.potetm.weasel-other :as other-ns]))

(defonce state {:repl-conn (repl/connect "ws://localhost:9001")})

(defn before []
  (println "before"))

(defn after []
  (println "after"))

(defn dependent-fn []
  (other-ns/my-fun))

(defmulti test-multi :type)

(defmethod test-multi :foo [_]
  "MULTI!")
