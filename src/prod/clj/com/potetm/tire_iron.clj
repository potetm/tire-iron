(ns com.potetm.tire-iron
  (:require [clojure.set :as set]
            [clojure.tools.namespace.dir :as dir]
            [clojure.tools.namespace.find :as find]
            [clojure.tools.namespace.track :as track]
            [cljs.analyzer :as ana]
            [cljs.build.api :as build]
            [cljs.closure :as closure]
            [cljs.compiler :as comp]
            [cljs.env :as env]
            [cljs.repl :as repl]
            [clojure.string :as str]))

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

(defn compile-unload-ns [state-sym ns]
  ;; We DO need to guarantee some location won't be cleared. Otherwise we might
  ;; blow away and rebuild their REPL connection.
  (let [ns (comp/munge ns)
        loaded-libs (comp/munge 'cljs.core/*loaded-libs*)]
    (str "(function(){\n"
         "var ns, ns_string, path, key, state_path_parts, on_state_path;\n"
         "ns_string = \"" ns "\";\n"
         "state_path_parts = ["
         (str/join ","
                   (map #(str "\"" % "\"")
                        (object-path-parts (comp/munge state-sym))))
         "];\n"
         "on_state_path = function(k) {\n"
         "  var i, full_name;\n"
         "  full_name = ns_string + '.' + k;\n"
         "  for (i = 0; i < state_path_parts.length; i++) {\n"
         "    if (state_path_parts[i] === full_name) {\n"
         "      return true;"
         "    }\n"
         "  }\n"
         "  return false;\n"
         "}\n;"
         "path = goog.dependencies_.nameToPath[ns_string];\n"
         "goog.object.remove(goog.dependencies_.visited, path);\n"
         "goog.object.remove(goog.dependencies_.written, path);\n"
         "goog.object.remove(goog.dependencies_.written, goog.basePath + path);\n"
         "if (" (object-path-exists? ns) ") {\n"
         "  ns = " ns ";\n"
         "  for (key in ns) {\n"
         "    if (!ns.hasOwnProperty(key)) { continue; }\n"
         "    if (!on_state_path(key)) {\n"
         "      delete ns[key];\n"
         "    }\n"
         "  }\n
         }\n"
         loaded-libs " = cljs.core.disj.call(null, " loaded-libs ", ns_string);\n"
         "})();")))

(defn unload-nss-script [state-sym nss]
  (str/join "\n"
            (map (partial compile-unload-ns state-sym)
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

(defn remove-disabled [tracker disable-unload disable-load]
  (-> tracker
      (update-in [::track/unload] (partial remove (set/union disable-unload
                                                             disable-load)))
      (update-in [::track/load] (partial remove disable-load))))

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
                      (unload-nss-script state unload-nss)
                      "var d = " (load-nss-html-async load-nss) ";\n"
                      "d.addCallback(function(ret) {\n"
                      (call-sym-script after)
                      "  });\n"
                      "})();"))))

(defrecord SyncRefresher []
  IRefresh
  (-initialize [this _opts])
  (-refresh [this {:keys [repl-env before after state unload-nss load-nss]}]
    (eval-script repl-env
                 (str (call-sym-script before)
                      (unload-nss-script state unload-nss)
                      (load-nss-sync load-nss)
                      (call-sym-script after)))))

(defn refresh* [{:keys [refresher
                        tracker
                        disable-unload
                        disable-load
                        repl-env
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
  (swap! tracker dir/scan-dirs source-dirs {:platform find/cljs
                                            :add-all? add-all?})
  (let [{:keys [::track/unload
                ::track/load]}
        (swap! tracker remove-disabled disable-unload disable-load)]
    (when (seq load)
      (prn :rebuilding)
      ;; build.api does some expensive input checks
      ;; Once we're here, no checks are needed.
      (closure/build (apply build/inputs source-dirs)
                     repl-opts
                     env/*compiler*))
    (when (or (seq unload) (seq load))
      (prn :requesting-reload load)
      (-refresh refresher {:repl-env repl-env
                           :analyzer-env analyzer-env
                           :repl-opts repl-opts
                           :before before
                           :after after
                           :state state
                           :unload-nss unload
                           :load-nss load})))
  (swap! tracker assoc ::track/load nil ::track/unload nil)
  (println :ok))

(defn maybe-quoted->symbol [symbol-or-list]
  (if (list? symbol-or-list)
    (second symbol-or-list)
    symbol-or-list))

(defn wrap [f]
  (fn g
    ([repl-env analyzer-env form]
     (g repl-env analyzer-env form nil))
    ([repl-env analyzer-env form repl-opts]
     (let [backup-comp @env/*compiler*]
       (try
         (apply f [repl-env analyzer-env form repl-opts])
         (catch Exception e
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

   Everything but :source-dirs can be overridden in the repl by supplying them to 'refresh.

   The :state var will not be touched during unloading. However the namespace will
   be reloaded, so put it in a `defonce`. You must put your repl connection in
   the state var if you're using a browser repl!

   Refresh happens in the following order:
     1. :before is called
     2. refresh happens
     3. :after is called

   Returns a map of the following fns for use in the cljs repl.

   'init    - Must be called prior to refresh.
   'refresh - refreshes :source-dirs.
              Any passed args will override the values passed to `special-fns`.
   'clear   - Clear the tracker state.
   'disable-unload! - Add a namespace to the disabled unload list.
   'disable-reload! - Add a namespace to the disabled reload list.
   'print-disabled  - See the disabled lists."
  [& {:keys [source-dirs
             before
             after
             state
             add-all?
             disable-unload
             disable-load
             refresher]
      :as closed-settings
      :or
      {source-dirs ["src"]}}]
  (let [refresher (atom refresher)
        tracker (atom (track/tracker))
        disable-unload (atom (into #{} disable-unload))
        disable-load (atom (into #{'cljs.core
                                   'clojure.browser.repl
                                   'clojure.browser.net}
                                 disable-load))
        closed-settings (select-keys closed-settings
                                     [:before
                                      :after
                                      :state
                                      :add-all?])]
    {'refresh
     (wrap (fn [repl-env analyzer-env [_ & opts] repl-opts]
             (let [passed-settings (apply hash-map opts)]
               (if-let [r @refresher]
                 (refresh* (merge {:refresher r
                                   :tracker tracker
                                   :source-dirs source-dirs
                                   :disable-unload @disable-unload
                                   :disable-load @disable-load
                                   :repl-env repl-env
                                   :analyzer-env analyzer-env
                                   :repl-opts repl-opts}
                                  closed-settings
                                  passed-settings))
                 (binding [*out* *err*]
                   (println "tire-iron not initialized"))))))

     'init
     (wrap (fn [repl-env analyzer-env _ repl-opts]
             (let [refresher (or @refresher
                                 (reset! refresher
                                         (determine-refresher repl-env)))]
               (-initialize refresher
                            {:repl-env repl-env
                             :analyzer-env analyzer-env
                             :repl-opts repl-opts}))))

     'print-disabled
     (wrap (fn [& _]
             (prn :disabled-unload @disable-unload)
             (prn :disabled-reload @disable-load)))

     'disable-unload!
     (wrap (fn [_ _ [_ my-ns] _]
             (swap! disable-unload conj (maybe-quoted->symbol my-ns))))

     'disable-reload!
     (wrap (fn [_ _ [_ my-ns] _]
             (swap! disable-load conj (maybe-quoted->symbol my-ns))))

     'clear
     (wrap (fn [_ _ _ _]
             (reset! tracker (track/tracker))))}))
