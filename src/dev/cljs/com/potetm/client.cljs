(ns com.potetm.client
  (:require [clojure.browser.repl :as repl]))

(defonce state {:repl-conn (repl/connect "http://localhost:9000/repl")})

(defn before []
  (println "before"))

(defn after []
  (println "after"))
