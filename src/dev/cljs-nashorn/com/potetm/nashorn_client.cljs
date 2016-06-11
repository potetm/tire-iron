(ns com.potetm.nashorn-client
  (:require [com.potetm.other-ns :as other-ns]))

(def state {:app-state (atom "maih state")})

(defn before []
  (println "before"))

(defn after []
  (println "after"))

(defn dependant-fn []
  (other-ns/my-fun))
