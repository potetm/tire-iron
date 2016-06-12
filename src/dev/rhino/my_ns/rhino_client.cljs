(ns my-ns.rhino-client
  (:require [my-ns.rhino-other :as other-ns]))

;; It turns out it's extremely important to not have start a ns with "com"
;; (as in "com.foo.bar"). Rhino automatically puts objects starting with "com"
;; in as packages, rendering the whole the unusable.

(def state {:app-state (atom "maih state")})

(defn before []
  (println "before"))

(defn after []
  (println "after"))

(defn dependent-fn []
  (other-ns/my-fun))
