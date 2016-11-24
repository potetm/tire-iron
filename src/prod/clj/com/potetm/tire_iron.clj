(ns com.potetm.tire-iron
  (:require [clojure.set :as set]
            [clojure.tools.namespace.dir :as dir]
            [clojure.tools.namespace.find :as find]
            [clojure.tools.namespace.track :as track]
            [cljs.analyzer :as ana]
            [cljs.compiler :as comp]
            [cljs.env :as env]
            [cljs.repl :as repl]
            [clojure.string :as str]))

(defonce refresh-tracker (track/tracker))
(defonce disabled-unload-namespaces #{})
(defonce disabled-load-namespaces #{'cljs.core
                                    'clojure.browser.repl
                                    'clojure.browser.net})

(defn eval-form [repl-env analyze-env repl-opts form]
  (repl/evaluate-form repl-env
                      analyze-env
                      "<cljs repl>"
                      (with-meta form
                                 {:merge true
                                  :line 1
                                  :column 1})
                      identity
                      repl-opts))

(defn ns-parts [munged-ns]
  (let [split (str/split (str munged-ns) #"\.")
        part-count (count split)]
    (loop [parts []
           n 1]
      (if (<= n part-count)
        (recur (conj parts (str/join "."
                                     (take n split)))
               (inc n))
        parts))))

(defn compile-ns-exists-check [ns]
  (str/join " && "
            (map (fn [ns-part]
                   (str "typeof " ns-part " !== 'undefined' "
                        "&& " ns-part " !== null "))
                 (ns-parts ns))))

(defn compile-unload-ns [ns]
  (let [ns (comp/munge ns)
        loaded-libs (comp/munge 'cljs.core/*loaded-libs*)]
    (str "(function(){\n"
         "var ns, ns_string, path, key, value;\n"
         "ns_string = \"" ns "\";\n"
         "path = goog.dependencies_.nameToPath[ns_string];\n"
         "goog.object.remove(goog.dependencies_.visited, path);\n"
         "goog.object.remove(goog.dependencies_.written, path);\n"
         "goog.object.remove(goog.dependencies_.written, goog.basePath + path);\n"
         "if (" (compile-ns-exists-check ns) ") {\n"
         "  ns = " ns ";\n"
         "  for (key in ns) {\n"
         "    if (!ns.hasOwnProperty(key)) { continue; }\n"
         "    value = ns[key];"
         ;; defmulti declares a defonce to hold its state. As noted below,
         ;; we purposely set defonce'd members to null so they aren't redefined.
         ;; Not redefining a multi causes defmethod failures on reload.
         ;;
         ;; Luckily, there is a handy `cljs.core/remove-all-methods` function to clear
         ;; a multimethod's state. The trick then is to figure out which members
         ;; are multimethods. The check below seems reliable.
         "    if (typeof value.constructor !== 'undefined' && \n
                    value.constructor.cljs$lang$ctorStr === 'cljs.core/MultiFn') {\n"
         "      cljs.core.remove_all_methods.call(null, value);\n"
         "    } else {\n"
         ;; setting to null is critical to allow defonce to work as expected.
         ;; defonce checks typeof var == "undefined"
         ;; we need to allow certain vars to _not_ be redefined in order to
         ;; maintain only one repl connection.
         ;;
         ;; I'm not certain, but it seemed like multiple connections usually
         ;; resulted in stackoverflows in goog.require.
         "      ns[key] = null;\n"
         "    }\n"
         "  }\n
         }\n"
         loaded-libs " = cljs.core.disj.call(null, " loaded-libs ", ns_string);\n"
         "})();")))

(defn compile-unload-nss [nss]
  (str/join "\n"
            (map compile-unload-ns
                 nss)))

(defn compile-load-nss [nss]
  (str "goog.net.jsloader.loadMany([\n"
       (str/join ",\n"
                 (map (comp #(str "goog.basePath + goog.dependencies_.nameToPath[\"" % "\"]")
                            comp/munge)
                      nss))
       "]);\n"))

(defn eval-script [repl-env script]
  (let [{:keys [status value] :as ret}
        (repl/-evaluate repl-env
                        "<cljs repl>"
                        1
                        script)]
    (case status
      :error (let [e (ex-info "Error executing script"
                              {:type :js-eval-error
                               :error ret})]
               (repl/err-out (pr e))
               (throw e))
      :exception (let [e (ex-info "Error executing script"
                                  {:type :js-eval-exception
                                   :error ret})]
                   (repl/err-out (pr e))
                   (throw e))
      :success value)))

(defn require-lib [repl-env analyze-env repl-opts target-ns]
  (let [is-self-require? (= target-ns ana/*cljs-ns*)
        [in-ns restore-ns]
        (if is-self-require?
          ['cljs.user ana/*cljs-ns*]
          [ana/*cljs-ns* nil])]
    (binding [ana/*reload-macros* true]
      (eval-form repl-env
                 analyze-env
                 repl-opts
                 `(~'ns ~in-ns
                    (:require [~target-ns]))))
    (when is-self-require?
      (set! ana/*cljs-ns* restore-ns))))

(defn remove-disabled [tracker]
  (-> tracker
      (update-in [::track/unload] (partial remove (set/union disabled-unload-namespaces
                                                             disabled-load-namespaces)))
      (update-in [::track/load] (partial remove disabled-load-namespaces))))

(defn refresh* [{:keys [repl-env
                        source-dirs
                        before
                        after
                        state
                        add-all?]}]
  (doseq [s (filter some? [before after state])]
    (assert (symbol? s) "value must be a symbol")
    (assert (namespace s)
            "value must be a namespace-qualified symbol"))
  (alter-var-root #'refresh-tracker dir/scan-dirs source-dirs {:platform find/cljs
                                                               :add-all? add-all?})
  (alter-var-root #'refresh-tracker remove-disabled)
  (prn :requesting-reload (::track/load refresh-tracker))
  (eval-script repl-env
               (str "(function() {\n"
                    "if(typeof goog.net.jsloader == 'undefined') {\n"
                    "  throw new Error(\"Tire iron not initialized\");"
                    "}\n"
                    (when before
                      (let [b (comp/munge before)]
                        (str "if (typeof " b " !== 'undefined') {\n"
                             "  " b ".call(null);\n"
                             "} else {\n"
                             "  throw new Error(\"Cannot resolve :before symbol: " b "\");\n"
                             "}\n")))
                    (when state
                      (let [s (comp/munge state)]
                        (str "if (typeof " s " !== 'undefined') {\n"
                             "  goog.tire_iron_state_ = " s ";\n"
                             "} else {\n"
                             "  throw new Error(\"Cannot resolve :state symbol: " s "\");\n"
                             "}\n")))
                    (compile-unload-nss (::track/unload refresh-tracker))
                    "var d = " (compile-load-nss (::track/load refresh-tracker)) ";\n"
                    (str "d.addCallback(function(res) {\n"
                         (when state
                           (let [s (comp/munge state)]
                             (str "if (typeof " s " !== 'undefined') {\n"
                                  "  " s " = goog.tire_iron_state_;\n"
                                  "} else {\n"
                                  "  throw new Error(\"Cannot resolve :state symbol: " s "\");\n"
                                  "}\n")))
                         (when after
                           (let [a (comp/munge after)]
                             (str "if (typeof " a " !== 'undefined') {\n"
                                  "  " a ".call(null);\n"
                                  "} else {\n"
                                  "  throw new Error(\"Cannot resolve :before symbol: " a "\");\n"
                                  "}\n")))
                         "})\n")
                    "})();"))
  (alter-var-root #'refresh-tracker (fn [tracker]
                                      (assoc tracker
                                        ::track/load nil
                                        ::track/unload nil)))
  (println :reload-requested))

(defn recover-state [repl-env analyzer-env repl-opts state]
  (eval-form repl-env
             analyzer-env
             repl-opts
             `(set! ~state (.-tire_iron_state_ js/goog))))

(defn print-state [repl-env analyzer-env repl-opts]
  (println
    (eval-form repl-env
               analyzer-env
               repl-opts
               `(.-tire_iron_state_ js/goog))))

(defn maybe-quoted->symbol [symbol-or-list]
  (if (list? symbol-or-list)
    (second symbol-or-list)
    symbol-or-list))

(defn disable-unload!
  ([ns]
   (alter-var-root #'disabled-unload-namespaces conj (maybe-quoted->symbol ns))))

(defn disable-reload!
  ([ns]
   (alter-var-root #'disabled-load-namespaces conj (maybe-quoted->symbol ns))))

(defn print-disabled []
  (prn "disabled unload" disabled-unload-namespaces)
  (prn "disabled reload" disabled-load-namespaces))

(defn wrap [f]
  (fn g
    ([repl-env analyzer-env form]
     (g repl-env analyzer-env form nil))
    ([repl-env analyzer-env form repl-opts]
     (let [backup-comp @env/*compiler*]
       (try
         (apply f [repl-env analyzer-env form repl-opts])
         (catch Exception e ;;Exception
           (reset! env/*compiler* backup-comp)
           (throw e)))))))

(defn special-fns
  "args:
     :source-dirs - A list of strings pointing to the source directories you would like to watch
     :add-all? - Boolean indicating whether all namespaces should be refreshed
     :before - A symbol corresponding to a zero-arg client-side function that will be called before refreshing
     :after - A symbol corresponding to a zero-arg client-side function that will be called after refreshing
     :state - A symbol corresponding to a client-side var that holds any state you would like to persisent between refreshes

   All of these values can be overridden in the repl by supplying them to 'refresh.

   Refresh happens in the following order:
     1. :before is called
     2. :state is copied to a private location on the client
     3. refresh happens
     4. :state is copied back to the original location
     5. :after is called

   Returns a map of the following fns for use in the cljs repl.

   'refresh: refreshes :source-dirs. Accepts the same args as `special-fns`.
             Any passed args will override the values passed to `special-fns`.
   'print-tis: prints the last state tire iron stored during refresh
   'recover-tis: replaces :state var with the last state tire iron stored during refresh.
                 Accepts an optional symbol pointing to an arbitrary target var."
  [& {:keys [source-dirs
             before
             after
             state
             add-all?]
      :as closed-settings
      :or
      {source-dirs ["src"]}}]
  {'refresh
   (wrap (fn [repl-env analyzer-env [_ & opts] repl-opts]
           (let [{:keys [source-dirs
                         before
                         after
                         state
                         add-all?] :as passed-settings} (apply hash-map opts)]
             (refresh* (merge closed-settings
                              passed-settings
                              {:repl-env repl-env
                               :analyzer-env analyzer-env
                               :repl-opts repl-opts})))))

   'print-tis
   (wrap (fn [repl-env analyzer-env _ repl-opts]
           (print-state repl-env
                        analyzer-env
                        repl-opts)))

   'recover-tis
   (wrap (fn [repl-env analyzer-env [_ state-sym] repl-opts]
           (recover-state repl-env
                          analyzer-env
                          repl-opts
                          (or state-sym
                              state))))

   'print-disabled
   (wrap (fn [& _]
           (print-disabled)))

   'disable-unload!
   (wrap (fn [_ _ [_ my-ns] _]
           (disable-unload! my-ns)))

   'disable-reload!
   (wrap (fn [_ _ [_ my-ns] _]
           (disable-reload! my-ns)))

   'init
   (wrap (fn [repl-env analyzer-env _ repl-opts]
           (require-lib repl-env
                        analyzer-env
                        repl-opts
                        'goog.net.jsloader)))

   'clear
   (wrap (fn [_ _ _ _]
           (alter-var-root #'refresh-tracker
                           (constantly (track/tracker)))))})
