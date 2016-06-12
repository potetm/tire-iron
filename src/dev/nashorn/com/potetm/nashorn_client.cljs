(ns com.potetm.nashorn-client
  (:require [com.potetm.nashorn-other :as other-ns]))

(def state {:app-state (atom "maih state")})

(defn before []
  (println "before"))

(defn after []
  (println "after"))

(defn dependent-fn []
  (other-ns/my-fun))
