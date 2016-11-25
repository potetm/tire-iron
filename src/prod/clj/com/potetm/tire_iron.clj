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

(defprotocol IRefresh
  (-initialize [this opts])
  (-refresh [this opts]))

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

(defn object-path-parts [munged-path]
  (let [split (str/split (str munged-path) #"\.")
        part-count (count split)]
    (loop [parts []
           n 1]
      (if (<= n part-count)
        (recur (conj parts (str/join "."
                                     (take n split)))
               (inc n))
        parts))))

(defn parts-exist? [parts]
  (str/join " && "
            (map (fn [part]
                   (str "typeof " part " !== 'undefined' "
                        "&& " part " !== null "))
                 parts)))

(defn object-path-exists? [munged-path]
  (parts-exist? (object-path-parts munged-path)))

(defn call-sym-script [sym]
  (let [sym (comp/munge sym)]
    (when-not (str/blank? (str sym))
      (str "if (" (object-path-exists? sym) ") {\n"
           "  " sym ".call(null);\n"
           "} else {\n"
           "  throw new Error(\"Cannot resolve symbol: " sym "\");\n"
           "}\n"))))

(defn set-ti-state-from-sym-script [state-sym]
  (let [state-sym (comp/munge state-sym)]
    (when-not (str/blank? (str state-sym))
      (str "if (" (object-path-exists? state-sym) ") {\n"
           "  goog.tire_iron_state_ = " state-sym ";\n"
           "} else {\n"
           "  throw new Error(\"Cannot resolve :state symbol: " state-sym "\");\n"
           "}\n"))))

(defn set-sym-from-ti-state-script [state-sym]
  (let [state-sym (comp/munge state-sym)]
    (when-not (str/blank? (str state-sym))
      (str "if (" (parts-exist? (butlast (object-path-parts state-sym))) ") {\n"
           "  " state-sym " = goog.tire_iron_state_;\n"
           "} else {\n"
           "  throw new Error(\"Cannot resolve :state symbol: " state-sym "\");\n"
           "}\n"))))

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
         "if (" (object-path-exists? ns) ") {\n"
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
         "    if (typeof value !== 'undefined' && \n
                         value !== null && \n
                    typeof value.constructor !== 'undefined' && \n
                           value.constructor !== null && \n
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

(defn unload-nss-script [nss]
  (str/join "\n"
            (map compile-unload-ns
                 nss)))

(defn load-nss-sync [nss]
  (str/join "\n"
            (map (comp #(str "goog.require('" % "');")
                       comp/munge)
                 nss)))

(defn load-nss-html-async [nss]
  (let [loaded-libs (comp/munge 'cljs.core/*loaded-libs*)
        nss-array (str "["
                       (str/join ",\n"
                                 (map (comp #(str "goog.basePath + goog.dependencies_.nameToPath[\"" % "\"]")
                                            comp/munge)
                                      nss))
                       "]")]
    (str "(function() {\n"
         "var nss = " nss-array ";"
         "return goog.net.jsloader.loadMany(nss).addCallback(function() {"
         "  cljs.core.apply.call(null, cljs.core.conj, " loaded-libs ", nss);"
         "  });"
         "})();")))

(defn remove-disabled [tracker]
  (-> tracker
      (update-in [::track/unload] (partial remove (set/union disabled-unload-namespaces
                                                             disabled-load-namespaces)))
      (update-in [::track/load] (partial remove disabled-load-namespaces))))

(defrecord HtmlAsyncRefresher []
  IRefresh
  (-initialize [this {:keys [repl-env analyzer-env repl-opts]}]
    (require-lib repl-env
                 analyzer-env
                 repl-opts
                 'goog.net.jsloader))
  (-refresh [this {:keys [repl-env before after state unload-nss load-nss]}]
    (eval-script repl-env
                 (str "(function() {\n"
                      (call-sym-script before)
                      (set-ti-state-from-sym-script state)
                      (unload-nss-script unload-nss)
                      "var d = " (load-nss-html-async load-nss) ";\n"
                      "d.addCallback(function(ret) {\n"
                      (set-sym-from-ti-state-script state)
                      (call-sym-script after)
                      "  });\n"
                      "})();"))))

(defrecord SyncRefresher []
  IRefresh
  (-initialize [this _opts])
  (-refresh [this {:keys [repl-env before after state unload-nss load-nss]}]
    (eval-script repl-env
                 (str (call-sym-script before)
                      (set-ti-state-from-sym-script state)
                      (unload-nss-script unload-nss)
                      (load-nss-sync load-nss)
                      (set-sym-from-ti-state-script state)
                      (call-sym-script after)))))

(defn refresh* [refresher {:keys [repl-env
                                  analyzer-env
                                  repl-opts
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
  (-refresh refresher {:repl-env repl-env
                       :analyzer-env analyzer-env
                       :repl-opts repl-opts
                       :before before
                       :after after
                       :state state
                       :unload-nss (::track/unload refresh-tracker)
                       :load-nss (::track/load refresh-tracker)})
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

(defn disable-unload! [ns]
  (alter-var-root #'disabled-unload-namespaces conj (maybe-quoted->symbol ns)))

(defn disable-reload! [ns]
  (alter-var-root #'disabled-load-namespaces conj (maybe-quoted->symbol ns)))

(defn clear! []
  (alter-var-root #'refresh-tracker (constantly (track/tracker))))

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

(defn determine-refresher [repl-env]
  (if (= "true"
         (eval-script repl-env
                      "!!goog.global.document"))
    (->HtmlAsyncRefresher)
    (->SyncRefresher)))

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
             add-all?
             refresher]
      :as closed-settings
      :or
      {source-dirs ["src"]}}]
  (let [refresher (atom refresher)]
    {'refresh
     (wrap (fn [repl-env analyzer-env [_ & opts] repl-opts]
             (let [{:keys [source-dirs
                           before
                           after
                           state
                           add-all?] :as passed-settings} (apply hash-map opts)]
               (if-let [r @refresher]
                 (refresh* @refresher
                           (merge closed-settings
                                  passed-settings
                                  {:repl-env repl-env
                                   :analyzer-env analyzer-env
                                   :repl-opts repl-opts}))
                 (throw (ex-info "tire-iron not initialized"
                                 {}))))))

     'init
     (wrap (fn [repl-env analyzer-env _ repl-opts]
             (let [refresher (or @refresher
                                 (reset! refresher
                                         (determine-refresher repl-env)))]
               (-initialize refresher
                            {:repl-env repl-env
                             :analyzer-env analyzer-env
                             :repl-opts repl-opts}))))

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

     'clear
     (wrap (fn [_ _ _ _]
             (clear!)))}))
