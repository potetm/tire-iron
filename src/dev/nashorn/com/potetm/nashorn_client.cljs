(ns com.potetm.nashorn-client
  (:require [com.potetm.nashorn-other :as other-ns]))

;; this cannot be defonce in nashorn.
;; in nashorn: `typeof my.existing.namespace.my_deffed_var => "object"`
;; This breaks `defonce` which uses `exists?` which checks for `typeof my.var == 'undefined'`
(def state {:app-state (atom "maih state")})

(defn before []
  (println "before"))

(defn after []
  (println "after"))

(defn dependent-fn []
  (other-ns/my-fun))

;; Multimethods don't work in nashorn because they use defonce. See above.
;; TODO: File an issue w/ cljs to get that fixed.
(comment
  (defmulti test-multi :type)

  (defmethod test-multi :foo [_]
    "MULTI!"))
