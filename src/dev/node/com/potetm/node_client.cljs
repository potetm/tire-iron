(ns com.potetm.node-client
  (:require [com.potetm.node-other :as other-ns])
  (:require-macros [com.potetm.tire-iron.cljs-macros :as m]))

(def state {:app-state (atom "maih state")})

(m/foo-tha-foo)

(defn before []
  (println "before"))

(defn after []
  (println "after"))

(defn dependent-fn []
  (other-ns/my-fun))

(defmulti test-multi :type)

(defmethod test-multi :foo [_]
  "MULTI!")
