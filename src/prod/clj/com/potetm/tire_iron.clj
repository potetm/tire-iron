(ns com.potetm.tire-iron
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.namespace.dir :as dir]
            [clojure.tools.namespace.file :as file]
            [clojure.tools.namespace.find :as find]
            [clojure.tools.namespace.reload :as reload]
            [clojure.tools.namespace.repl :as r]
            [clojure.tools.namespace.track :as track]
            [cljs.analyzer :as ana]
            [cljs.build.api :as build]
            [cljs.closure :as closure]
            [cljs.compiler :as comp]
            [cljs.env :as env]
            [cljs.repl :as repl])
  (:import (clojure.lang ExceptionInfo)
           (com.google.common.io ByteStreams)
           (com.sun.nio.file SensitivityWatchEventModifier)
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
  "-refresh is responsible for making some attempt to recover in the event
  initialization gets undone, which will happen on page refresh.

  The goal is to make initialization transparent to the user. I'm not yet sure
  how feasible that is, so the underlying API is going to be init/refresh until I'm
  confident we can make refresh a standalone concept."
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

(defn require-libs [repl-env analyze-env repl-opts target-nss]
  (let [is-self-require? (some #{ana/*cljs-ns*} target-nss)
        [in-ns restore-ns]
        (if is-self-require?
          ['cljs.user ana/*cljs-ns*]
          [ana/*cljs-ns* nil])]
    (binding [ana/*reload-macros* true]
      (eval-form repl-env
                 analyze-env
                 repl-opts
                 `(~'ns ~in-ns
                    (:require ~@target-nss))))
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

;; These js-file and deps fns rely on quite a few non-API calls.
;; They'll be pretty brittle in the face of change :/
;; That said, I've no reason to believe these calls change a whole lot.
(defn js-file->assumed-compiled [output-dir js-file]
  (let [f (io/file (closure/rel-output-path js-file))]
    (assoc js-file
      :url (.toURI
             (if (.isAbsolute f)
               f
               (.getAbsoluteFile (io/file output-dir
                                          f)))))))

(defn deps-as-js-files [opts compiler-env ns]
  (remove (comp #{:seed}
                :type)
          (closure/add-dependencies opts
                                    {:requires [(name ns)]
                                     :type :seed
                                     :uri (:uri (closure/source-for-namespace ns
                                                                              compiler-env))})))

(defn add-deps [{:keys [output-dir] :as opts}
                compiler-env
                nss]
  ;; lol. clojure/cljs-dependencies (called by clojure/add-dependencies) blindly
  ;; does a deref on env/*compiler* and invokes it. Crazytown.
  (binding [env/*compiler* compiler-env]
    (apply str
           (map (comp (partial closure/add-dep-string opts)
                      (partial js-file->assumed-compiled output-dir))
                (distinct (mapcat (partial deps-as-js-files opts compiler-env)
                                  nss))))))

(defn load-nss-sync [opts compiler-env nss]
  (str (add-deps opts compiler-env nss)
       "\n"
       (str/join "\n"
                 ;; it's not clear to me what the API of the repl monkey-patched
                 ;; goog.require is. It takes a second arg named "reload", and
                 ;; the browser repl checks if (= reload "reload-all"). Other repls
                 ;; only check truthiness. So my assumption is that it has the same
                 ;; semantics as clojure.core/require, but with strings for easy
                 ;; use from JS.
                 (map (comp #(str "goog.require('" % "', 'reload');")
                            comp/munge)
                      nss))))

(defn load-nss-html-async [build-opts compiler-env nss]
  (let [loaded-libs (comp/munge 'cljs.core/*loaded-libs*)
        nss-array (str "["
                       (str/join ",\n"
                                 (map (comp #(str "\"" % "\"")
                                            comp/munge)
                                      nss))
                       "]")
        nss-paths (str "["
                       (str/join ",\n"
                                 (map (fn [n]
                                        (str "goog.tire_iron_name_to_path__('" (comp/munge n) "')"))
                                      nss))
                       "]")]
    (str "(function() {\n"
         (add-deps build-opts compiler-env nss)
         "var nss = " nss-array ";\n"
         "var nss_paths = " nss-paths ";\n"
         "return goog.tire_iron_loadMany__(nss_paths, {cleanupWhenDone: true}).addCallback(function() {\n"
         "  " loaded-libs " = cljs.core.apply.call(null, cljs.core.conj, " loaded-libs " || cljs.core.PersistentHashSet.EMPTY, nss);\n"
         "  });\n"
         "})();")))

(defn remove-disabled [tracker disable-unload disable-load]
  (-> tracker
      (update-in [::track/unload] (partial remove (set/union disable-unload
                                                             disable-load)))
      (update-in [::track/load] (partial remove disable-load))))

(defn html-async-init-error? [ex-i]
  (let [{{:keys [value] :as e} :error} (ex-data ex-i)]
    ;; chrome
    ;;  "TypeError: Cannot read property 'loadMany' of undefined"
    ;; firefox
    ;;  "TypeError: goog.net.jsloader is undefined"
    ;; safari
    ;;  "TypeError: undefined is not an object (evaluating 'goog.net.jsloader.loadMany')"
    ;; ie11
    ;;  "TypeError: Unable to get property 'loadMany' of undefined or null reference"
    ;; ie10
    ;;  "TypeError: Unable to get property 'loadMany' of undefined or null reference"
    (or (and (str/includes? value "TypeError")
             (or (str/includes? value "loadMany")
                 (str/includes? value "goog.net.jsloader")
                 (str/includes? value "tire_iron_name_to_path__")
                 (str/includes? value "tire_iron_scriptLoadingDeferred_")
                 (str/includes? value "tire_iron_loadMany__"))))))

(defrecord DomAsyncRefresher []
  IRefresh
  (-initialize [this {:keys [repl-env analyzer-env repl-opts]}]
    (require-libs repl-env
                  analyzer-env
                  repl-opts
                  '[goog.net.jsloader
                    goog.Uri
                    goog.object])
    ;; Older versions of cljs include a closure-library that has a version of loadMany
    ;; that doesn't return a deferred. Copy the version we want here for backward
    ;; compatibility.
    (eval-script repl-env
                 (str "goog.tire_iron_scriptLoadingDeferred_;\n"
                      "goog.tire_iron_loadMany__ = function(uris, opt_options) {\n"
                      "  if (!uris.length) {\n"
                      "    return goog.async.Deferred.succeed(null);\n"
                      "  }\n"
                      "\n"
                      "  var isAnotherModuleLoading = goog.net.jsloader.scriptsToLoad_.length;\n"
                      "  goog.array.extend(goog.net.jsloader.scriptsToLoad_, uris);\n"
                      "  if (isAnotherModuleLoading) {\n"
                      "    return goog.tire_iron_scriptLoadingDeferred_;\n"
                      "  }\n"
                      "\n"
                      "  uris = goog.net.jsloader.scriptsToLoad_;\n"
                      "  var popAndLoadNextScript = function() {\n"
                      "    var uri = uris.shift();\n"
                      "    var deferred = goog.net.jsloader.load(uri, opt_options);\n"
                      "    if (uris.length) {\n"
                      "      deferred.addBoth(popAndLoadNextScript);\n"
                      "    }\n"
                      "    return deferred;\n"
                      "  };\n"
                      "  goog.tire_iron_scriptLoadingDeferred_ = popAndLoadNextScript();\n"
                      "  return goog.tire_iron_scriptLoadingDeferred_;\n"
                      "};\n"))
    (eval-form repl-env
               analyzer-env
               repl-opts
               '(do (set! js/goog.tire_iron_name_to_path__
                          (fn [n]
                            (.makeUnique (js/goog.Uri.parse (str js/goog.basePath
                                                                 (js/goog.object.get js/goog.dependencies_.nameToPath
                                                                                     n))))))
                    (let [path (js/goog.tire_iron_name_to_path__ "goog.object")]
                      (doto (js/goog.net.jsloader.load path
                                                       (clj->js {"cleanupWhenDone" true}))
                        (.addCallback (fn []
                                        (comment (js/console.log "cache busting supported!"))))
                        (.addErrback (fn []
                                       (js/console.debug "The failed network call to" (.toString path) "was a test to see if your system supports source map reloading.")
                                       (js/console.debug "Unfortunately, your system does not support this feature.")
                                       (js/console.debug "This is a known issue with cljs.repl.browser/repl-env.")
                                       (js/console.debug "This will not affect your ability to use any other feature of tire-iron.")
                                       (set! js/goog.tire_iron_name_to_path__
                                             (fn [n]
                                               (str js/goog.basePath
                                                    (js/goog.object.get js/goog.dependencies_.nameToPath
                                                                        n)))))))))))
  (-refresh [this {:keys [repl-env
                          build-opts
                          compiler-env
                          before
                          after
                          state
                          all-nss
                          unload-nss
                          load-nss] :as opts}]
    (try
      ;; piggieback "helpfully" prints every error it encounters to the
      ;; repl for you. We don't want that if we're going to commit to handling
      ;; some errors automatically.
      (binding [*err* (io/writer (ByteStreams/nullOutputStream))]
        (eval-script repl-env
                     (str "(function() {\n"
                          (call-sym-script before)
                          (unload-nss-script state all-nss unload-nss)
                          "var d = " (load-nss-html-async build-opts compiler-env load-nss) ";\n"
                          "d.addCallback(function(ret) {\n"
                          (call-sym-script after)
                          "  });\n"
                          "})();")))
      (catch ExceptionInfo ei
        (if (html-async-init-error? ei)
          (do (prn :re-initializing)
              (-initialize this opts)
              (eval-script repl-env
                           (str "(function() {\n"
                                ;; Don't call :before. If we're in this situation
                                ;; we know we've already called it and unloaded it.
                                ;; Don't call unload because it should already be done.
                                "var d = " (load-nss-html-async build-opts compiler-env load-nss) ";\n"
                                "d.addCallback(function(ret) {\n"
                                (call-sym-script after)
                                "  });\n"
                                "})();")))
          (throw ei))))))

(defrecord SyncRefresher []
  IRefresh
  (-initialize [this _opts])
  (-refresh [this {:keys [repl-env
                          build-opts
                          compiler-env
                          before
                          after
                          state
                          all-nss
                          unload-nss
                          load-nss]}]
    (eval-script repl-env
                 (str (call-sym-script before)
                      (unload-nss-script state all-nss unload-nss)
                      (load-nss-sync build-opts compiler-env load-nss)
                      (call-sym-script after)))))

;; copied from cljs.repl/env->opts
(defn repl-env->build-opts [repl-opts repl-env]
  (merge (into {} repl-env)
         {:optimizations (:optimizations repl-env :none)
          :output-dir (:working-dir repl-env ".repl")}
         repl-opts))

;; Copied from clojure.tools.namespace/repl. This is definitionally
;; the same, but I need to get the affected namespaces.
(defn do-refresh-macros [current-ns]
  (let [current-ns-refers (#'r/referred current-ns)
        current-ns-aliases (#'r/aliased current-ns)]
    (alter-var-root #'r/refresh-tracker dir/scan-dirs r/refresh-dirs {:platform find/clj})
    (alter-var-root #'r/refresh-tracker #'r/remove-disabled)
    (#'r/print-pending-reloads r/refresh-tracker)
    (let [affected-nss (distinct (concat (::track/unload r/refresh-tracker)
                                         (::track/load r/refresh-tracker)))]
      (alter-var-root #'r/refresh-tracker reload/track-reload)
      (let [result (#'r/print-and-return r/refresh-tracker)]
        (if (= :ok result)
          {::macro-reload-status :ok
           ::affected-namespaces affected-nss}
          ;; There was an error, recover as much as we can:
          (do (when-not (or (false? (::r/unload (meta current-ns)))
                            (false? (::r/load (meta current-ns))))
                (#'r/recover-ns current-ns-refers current-ns-aliases))
              ;; Return the Exception to the REPL:
              {::macro-reload-status :error
               ::error result}))))))

;; copied from cljs.closure for backward compatibility
(defn cljs-dependents-for-macro-namespaces
  [state namespaces]
  (map :name
       (let [namespaces-set (set namespaces)]
         (filter (fn [x] (not-empty
                           (set/intersection namespaces-set (-> x :require-macros vals set))))
                 (vals (:cljs.analyzer/namespaces @state))))))

(defn refresh-macros-and-mark-dependents
  "This could be done via tools.namespace, but the compiler needs to be notified
  that these namespaces need to be re-compiled.

  As it is, this *must* be run prior to dir/scan-dirs."
  [original-ns compiler-env]
  (let [{:keys [::macro-reload-status
                ::affected-namespaces] :as result}
        (binding [*ns* original-ns]
          (do-refresh-macros original-ns))]
    (when (= macro-reload-status :ok)
      ;; There is already a function for this: cljs.closure/mark-cljs-ns-for-recompile!
      ;; However it sets last modified to 5000, and tools.namespace filters on
      ;; last modified > last checked (clojure.tools.namespace.dir/modified-files).
      ;; cljs seems to only care if last modified output != last modified input
      ;; (cljs.util/changed?). So this ought to do.
      (doseq [dep (cljs-dependents-for-macro-namespaces compiler-env
                                                        affected-namespaces)]
        ;; The cljs version also uses `target-file-for-cljs-ns`, which seems like a
        ;; hack. The source transitively changed, not output file. Not sure what
        ;; the reasoning was there. Either way, tools.namespace needs the source
        ;; file changed.
        (let [f (io/file (:uri (closure/source-for-namespace dep compiler-env)))]
          (when (.exists f)
            (.setLastModified f (System/currentTimeMillis))))))
    result))

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
                        compiler-env
                        original-ns]
                 :or
                 {compiler-env env/*compiler*}}]
  (doseq [s (filter some? [before after state])]
    (assert (symbol? s) "value must be a symbol")
    (assert (namespace s)
            "value must be a namespace-qualified symbol"))
  (let [build-opts (repl-env->build-opts repl-opts repl-env)
        {:keys [::macro-reload-status]
         :as macro-status}
        ;; must run this first so deps will be touched
        (refresh-macros-and-mark-dependents original-ns
                                            compiler-env)]
    (if (= :ok macro-reload-status)
      (let [_ (swap! tracker dir/scan-dirs source-dirs {:platform find/cljs
                                                        :add-all? add-all?})
            {:keys [::track/unload
                    ::track/load
                    ::file/filemap]}
            (swap! tracker remove-disabled disable-unload disable-load)]
        (when (seq load)
          (prn :rebuilding)
          ;; build.api does some expensive input checks
          ;; Once we're here, no checks are needed.
          (closure/build (apply build/inputs source-dirs)
                         build-opts
                         compiler-env))
        (when (or (seq unload) (seq load))
          (prn :requesting-reload load)
          (-refresh refresher {:compiler-env compiler-env
                               :repl-env repl-env
                               :analyzer-env analyzer-env
                               :repl-opts repl-opts
                               :build-opts build-opts
                               :before before
                               :after after
                               :state state
                               :all-nss (vals filemap)
                               :unload-nss unload
                               :load-nss load}))
        (swap! tracker assoc ::track/load nil ::track/unload nil)
        (println :ok))
      (println macro-status))))

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
                         (try
                           (refresh* refresh-opts)
                           (catch IOException io
                             ;; Repl disconnect is most likely to manifest as
                             ;; IOException. (I know weasel diconnect throws this.)
                             (println "Disconnect detected.")
                             (throw io))
                           (catch InterruptedException ie
                             (comment (println "Interrupted"))
                             (throw ie))
                           (catch Exception e
                             (binding [*out* *err*]
                               (println (class e))
                               (println (.getMessage e))
                               (.printStackTrace e *out*)))))))))
               (finally
                 (println "Shutting down watcher.")
                 (.shutdown exec))))))

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
    (->DomAsyncRefresher)
    (->SyncRefresher)))

(defn init [{:keys [repl-opts
                    repl-env
                    analyzer-env
                    refresher]}]
  (let [refresher (or @refresher
                      (reset! refresher
                              (determine-refresher repl-env)))]
    (-initialize refresher
                 {:repl-env repl-env
                  :analyzer-env analyzer-env
                  :repl-opts repl-opts})))

(defn stop-watch
  "Stop a watch that was started during a now stopped REPL session."
  []
  (when watch-exec
    (.shutdownNow watch-exec)))

(defn special-fns
  "args:
     :source-dirs - A list of strings pointing to the source directories you would like to watch
     :add-all? - Boolean indicating whether all namespaces should be refreshed
     :before - A symbol corresponding to a zero-arg client-side function that will be called before refreshing
     :after - A symbol corresponding to a zero-arg client-side function that will be called after refreshing
     :state - A symbol corresponding to a client-side var that holds any state you would like to persisent between refreshes

   :before, :after, and :state can be overridden in the repl by supplying them to 'refresh or 'start-watch.

   The :state var will not be touched during unloading. However the namespace will
   be reloaded, so put it in a `defonce`. You must put your repl connection in
   the state var if you're using a browser repl!

   Refresh happens in the following order:
     1. :before is called
     2. refresh happens
     3. :after is called

   Returns a map of the following fns for use in the cljs repl.

   'refresh - Refreshes :source-dirs.
              Any passed args will override the values passed to `special-fns`.
   'clear   - Clear the tracker state.
   'start-watch - Start a watcher* that will automatically call refresh when any
                  file under :source-dirs changes.
   'stop-watch  - Stop a running watcher.
   'disable-unload! - Add a namespace to the disabled unload list.
   'disable-reload! - Add a namespace to the disabled reload list.
   'print-disabled  - See the disabled lists.

   *NOTE: tire-iron has no access to the lifecycle of the REPL, so it cannot
   automatically stop a watcher for you. Hence 'stop-watch has been provided for
   you to manage it yourself. If you forget to stop a watch before you end your
   repl session, you can call the only other API call in this namespace: stop-watch.
   "
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
  (let [initialized? (atom false)
        refresher (atom refresher)
        tracker (atom (track/tracker))
        disable-unload (atom (into #{} disable-unload))
        disable-load (atom (into #{'cljs.core
                                   'clojure.browser.repl
                                   'clojure.browser.net}
                                 disable-load))
        select-overridable-keys #(select-keys % [:before
                                                 :after
                                                 :state
                                                 :add-all?])
        closed-settings (select-overridable-keys closed-settings)]
    {'refresh
     (wrap (fn [repl-env analyzer-env [_ & opts] repl-opts]
             (let [passed-settings (apply hash-map opts)]
               (when-not @initialized?
                 (init {:repl-opts repl-opts
                        :repl-env repl-env
                        :analyzer-env analyzer-env
                        :refresher refresher})
                 (reset! initialized? true))
               (refresh* (merge {:refresher @refresher
                                 :tracker tracker
                                 :source-dirs source-dirs
                                 :disable-unload @disable-unload
                                 :disable-load @disable-load
                                 :repl-env repl-env
                                 :analyzer-env analyzer-env
                                 :repl-opts repl-opts
                                 :original-ns *ns*}
                                closed-settings
                                (select-overridable-keys passed-settings))))))

     'start-watch
     (wrap (fn [repl-env analyzer-env [_ & opts] repl-opts]
             (if (and watch-exec (not (.isShutdown watch-exec)))
               (binding [*out* *err*]
                 (println "Watch already running. Did you mean to (stop-watch) first?"))
               (let [passed-settings (apply hash-map opts)
                     exec (Executors/newSingleThreadExecutor
                            (reify ThreadFactory
                              (newThread [this runnable]
                                (doto (Thread. runnable
                                               "tire-iron-watcher-thread")
                                  (.setDaemon true)))))]
                 (alter-var-root #'watch-exec (constantly exec))
                 (when-not @initialized?
                   (init {:repl-opts repl-opts
                          :repl-env repl-env
                          :analyzer-env analyzer-env
                          :refresher refresher})
                   (reset! initialized? true))
                 (start-watch watch-exec
                              (FileSystems/getDefault)
                              (merge {:refresher @refresher
                                      :tracker tracker
                                      :source-dirs source-dirs
                                      :disable-unload @disable-unload
                                      :disable-load @disable-load
                                      :repl-env repl-env
                                      :analyzer-env analyzer-env
                                      :repl-opts repl-opts
                                      :compiler-env env/*compiler*
                                      :original-ns *ns*}
                                     closed-settings
                                     (select-overridable-keys passed-settings)))))))

     'stop-watch (wrap (fn [_ _ _ _]
                         (stop-watch)))

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
