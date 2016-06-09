(ns com.potetm.client
  (:require [clojure.browser.repl :as repl]))

(defonce state {:repl-conn (repl/connect "http://localhost:9000/repl")})

(enable-console-print!)

(defn before []
  (println "before")
  (pr state))

(defn after []
  (println "after")
  (pr state))
