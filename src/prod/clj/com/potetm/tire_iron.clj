(ns com.potetm.tire-iron
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.namespace.dir :as dir]
            [clojure.tools.namespace.file :as file]
            [clojure.tools.namespace.find :as find]
            [clojure.tools.namespace.track :as track]
            [cljs.analyzer :as ana]
            [cljs.build.api :as build]
            [cljs.closure :as closure]
            [cljs.compiler :as comp]
            [cljs.env :as env]
            [cljs.repl :as repl])
  (:import (com.sun.nio.file SensitivityWatchEventModifier)
           (java.nio.file Files
                          FileSystem
                          FileSystems
                          FileVisitResult
                          LinkOption
                          Path
                          SimpleFileVisitor
                          StandardWatchEventKinds
                          WatchEvent
                          WatchEvent$Kind
                          WatchEvent$Modifier)
           (java.io IOException)
           (java.util.concurrent Executors
                                 ExecutorService
                                 ThreadFactory
                                 TimeUnit)))

(defonce ^ExecutorService watch-exec nil)

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

(defn nested-member-name [base nested-member]
  (let [base-str (str base)
        nested-member-str (str nested-member)]
    (when (and (str/starts-with? nested-member-str base-str)
               (not= nested-member-str base-str))
      (-> nested-member-str
          (str/replace (re-pattern (str base-str "[\\./]")) "")
          (str/split #"\.|/")
          first))))

(defn nested-namespace-keys [ns all-namespaces]
  (keep (partial nested-member-name ns)
        all-namespaces))

(defn nested-state-path-key [ns state-sym]
  (nested-member-name ns state-sym))

(defn compile-unload-ns [ns skip-keys]
  ;; We DO need to guarantee some location won't be cleared. Otherwise we might
  ;; blow away and rebuild their REPL connection.
  (let [ns (comp/munge ns)
        loaded-libs (comp/munge 'cljs.core/*loaded-libs*)
        skip-keys (str "{" (str/join ",\n"
                                     (map (comp #(str % ": true")
                                                comp/munge)
                                          skip-keys))
                       "}")]
    (str "(function(){\n"
         "var ns, ns_string, path, key, state_path_parts, on_state_path, skip_keys;\n"
         "ns_string = \"" ns "\";\n"
         "skip_keys = " skip-keys ";\n"
         "path = goog.dependencies_.nameToPath[ns_string];\n"
         "goog.object.remove(goog.dependencies_.visited, path);\n"
         "goog.object.remove(goog.dependencies_.written, path);\n"
         "goog.object.remove(goog.dependencies_.written, goog.basePath + path);\n"
         "if (" (object-path-exists? ns) ") {\n"
         "  ns = " ns ";\n"
         "  for (key in ns) {\n"
         "    if (!ns.hasOwnProperty(key)) { continue; }\n"
         "    if (!skip_keys[key]) {\n"
         "      delete ns[key];\n"
         "    }\n"
         "  }\n
         }\n"
         loaded-libs " = cljs.core.disj.call(null, " loaded-libs " || cljs.core.PersistentHashSet.EMPTY, ns_string);\n"
         "})();")))

(defn unload-nss-script [state-sym all-nss nss]
  (str/join "\n"
            (map #(compile-unload-ns %
                                     (concat (nested-namespace-keys % all-nss)
                                             (when-let [k (nested-state-path-key % state-sym)]
                                               [k])))
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
                                 (map (comp #(str "\"" % "\"")
                                            comp/munge)
                                      nss))
                       "]")
        nss-paths (str "["
                       (str/join ",\n"
                                 (map (comp #(str "goog.basePath + goog.dependencies_.nameToPath[\"" % "\"]")
                                            comp/munge)
                                      nss))
                       "]")]
    (str "(function() {\n"
         "var nss = " nss-array ";\n"
         "var nss_paths = " nss-paths ";\n"
         "return goog.net.jsloader.loadMany(nss_paths).addCallback(function() {\n"
         "  " loaded-libs " = cljs.core.apply.call(null, cljs.core.conj, " loaded-libs " || cljs.core.PersistentHashSet.EMPTY, nss);\n"
         "  });\n"
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
  (-refresh [this {:keys [repl-env before after state all-nss unload-nss load-nss]}]
    (eval-script repl-env
                 (str "(function() {\n"
                      (call-sym-script before)
                      (unload-nss-script state all-nss unload-nss)
                      "var d = " (load-nss-html-async load-nss) ";\n"
                      "d.addCallback(function(ret) {\n"
                      (call-sym-script after)
                      "  });\n"
                      "})();"))))

(defrecord SyncRefresher []
  IRefresh
  (-initialize [this _opts])
  (-refresh [this {:keys [repl-env before after state all-nss unload-nss load-nss]}]
    (eval-script repl-env
                 (str (call-sym-script before)
                      (unload-nss-script state all-nss unload-nss)
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
                        add-all?
                        compiler-env]
                 :or
                 {compiler-env env/*compiler*}}]
  (doseq [s (filter some? [before after state])]
    (assert (symbol? s) "value must be a symbol")
    (assert (namespace s)
            "value must be a namespace-qualified symbol"))
  (swap! tracker dir/scan-dirs source-dirs {:platform find/cljs
                                            :add-all? add-all?})
  (let [{:keys [::track/unload
                ::track/load
                ::file/filemap]}
        (swap! tracker remove-disabled disable-unload disable-load)]
    (when (seq load)
      (prn :rebuilding)
      ;; build.api does some expensive input checks
      ;; Once we're here, no checks are needed.
      (closure/build (apply build/inputs source-dirs)
                     repl-opts
                     compiler-env))
    (when (or (seq unload) (seq load))
      (prn :requesting-reload load)
      (-refresh refresher {:repl-env repl-env
                           :analyzer-env analyzer-env
                           :repl-opts repl-opts
                           :before before
                           :after after
                           :state state
                           :all-nss (vals filemap)
                           :unload-nss unload
                           :load-nss load})))
  (swap! tracker assoc ::track/load nil ::track/unload nil)
  (println :ok))

(defn recursive-register [watcher root]
  (Files/walkFileTree
    root
    (proxy [SimpleFileVisitor] []
      (preVisitDirectory [^Path dir attrs]
        (.register dir
                   watcher
                   (into-array WatchEvent$Kind
                               [StandardWatchEventKinds/ENTRY_CREATE
                                StandardWatchEventKinds/ENTRY_DELETE
                                StandardWatchEventKinds/ENTRY_MODIFY])
                   (into-array WatchEvent$Modifier
                               [SensitivityWatchEventModifier/HIGH]))
        FileVisitResult/CONTINUE))))

(defn start-watch [^ExecutorService exec ^FileSystem fs refresh-opts]
  (.submit exec
           ^Callable
           (fn []
             (try
               (with-open [ws (.newWatchService fs)]
                 (doseq [^Path root (map #(.getPath fs % (make-array String 0))
                                         (:source-dirs refresh-opts))]
                   (recursive-register ws root))
                 (while true
                   (when-let [k (.poll ws 300 TimeUnit/MILLISECONDS)]
                     (when-let [events (and (.reset k)
                                            (seq (.pollEvents k)))]
                       (doseq [^WatchEvent e events]
                         (if (= (.kind e) StandardWatchEventKinds/ENTRY_CREATE)
                           (let [f (.context e)]
                             (if (Files/isDirectory f (make-array LinkOption 0))
                               (recursive-register ws f)))))
                       (when (some (comp #(or (.endsWith % ".cljs")
                                              (.endsWith % ".cljc"))
                                         str
                                         #(.context %))
                                   events)
                         (println "Watcher Refreshing...")
                         (refresh* refresh-opts))))))
               (catch IOException io
                 (comment (println "REPL shutdown detected.")))
               (catch InterruptedException ie
                 (comment (println "Interrupted")))
               (catch Exception e
                 (binding [*out* *err*]
                   (println (.getMessage e))
                   (println (.printStackTrace e *out*))))
               (finally
                 (println "Shutting down watcher.")
                 (.shutdown exec))))))

(defn stop-watch []
  (.shutdownNow watch-exec))

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
        initial-build-complete? (promise)
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
                 (do @initial-build-complete?
                     (refresh* (merge {:refresher r
                                       :tracker tracker
                                       :source-dirs source-dirs
                                       :disable-unload @disable-unload
                                       :disable-load @disable-load
                                       :repl-env repl-env
                                       :analyzer-env analyzer-env
                                       :repl-opts repl-opts}
                                      closed-settings
                                      passed-settings)))
                 (binding [*out* *err*]
                   (println "tire-iron not initialized"))))))

     'start-watch (wrap (fn [repl-env analyzer-env [_ & opts] repl-opts]
                          (let [passed-settings (apply hash-map opts)
                                exec (Executors/newSingleThreadExecutor
                                       (reify ThreadFactory
                                         (newThread [this runnable]
                                           (doto (Thread. runnable
                                                          "tire-iron-watcher-thread")
                                             (.setDaemon true)))))]
                            (if-let [r @refresher]
                              (do @initial-build-complete?
                                  (alter-var-root #'watch-exec (constantly exec))
                                  (start-watch watch-exec
                                               (FileSystems/getDefault)
                                               (merge {:refresher r
                                                       :tracker tracker
                                                       :source-dirs source-dirs
                                                       :disable-unload @disable-unload
                                                       :disable-load @disable-load
                                                       :repl-env repl-env
                                                       :analyzer-env analyzer-env
                                                       :repl-opts repl-opts
                                                       :compiler-env env/*compiler*}
                                                      closed-settings
                                                      passed-settings)))
                              (binding [*out* *err*]
                                (println "tire-iron not initialized"))))))

     'stop-watch (wrap (fn [_ _ _ _]
                         (stop-watch)))

     'init
     (wrap (fn [repl-env analyzer-env _ repl-opts]
             (let [env env/*compiler*
                   ^Runnable build #(try
                                      (closure/build (apply build/inputs source-dirs)
                                                     repl-opts
                                                     env)
                                      (finally
                                        (deliver initial-build-complete? true)))
                   ;; Kick off a build. That will speed up the first reload which
                   ;; takes the longest to build.
                   _ (.start (Thread. build))
                   refresher (or @refresher
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
