(ns com.potetm.client
  (:require [clojure.browser.repl :as repl]))

(defonce state {:repl-conn (repl/connect "http://localhost:9000/repl")})

(enable-console-print!)

(defn before []
  (js/console.log "before"))

(defn after []
  (js/console.log "after"))
