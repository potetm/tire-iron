(ns com.potetm.browser-client
  (:require [clojure.browser.repl :as repl]
            [com.potetm.browser-other :as other-ns]))

;; Still need to defonce. Even though we take care to preserve this var
;; during unloading, you need to make sure it's not reset during ns loading.
(defonce state {:repl-conn (repl/connect "http://localhost:9000/repl")})

(defn before []
  (println "before"))

(defn after []
  (println "after"))

(defn dependent-fn []
  (other-ns/my-fun))

(defmulti test-multi :type)

(defmethod test-multi :foo [_]
  "MULTI!")
