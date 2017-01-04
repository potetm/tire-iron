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
            [cljs.repl :as repl]
            [cljs.util :as util])
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
           (java.io File
                    IOException)
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

(defn js-file->assumed-compiled [output-dir js-file]
  (let [f (io/file (closure/rel-output-path js-file))]
    (assoc js-file
      :url (.toURI
             (if (.isAbsolute f)
               f
               (.getAbsoluteFile (io/file output-dir
                                          f)))))))

;; copied from closure/target-file-for-cljs-ns for backward compatibility
(defn ^File target-file-for-cljs-ns
  [ns-sym output-dir]
  (util/to-target-file
    (util/output-directory {:output-dir output-dir})
    {:ns ns-sym}))

;; Must add deps because a new namespace needs to be added to the goog dependency
;; tree. Otherwise, you get an error when the new namespace is required anywhere.
(defn add-deps [{:keys [output-dir] :as opts}
                compiler-env
                nss]
  (when (seq nss)
    (binding [env/*compiler* compiler-env]
      (closure/deps-file opts
                         (map (partial js-file->assumed-compiled output-dir)
                              (remove #(or (= (:group %) :goog)
                                           (= :provides ["goog"]))
                                      (apply closure/add-dependencies opts
                                             (map #(closure/compiled-file
                                                     {:file (target-file-for-cljs-ns % output-dir)})
                                                  nss))))))))

(defn load-nss-sync [nss]
  (str/join "\n"
            ;; it's not clear to me what the API of the repl monkey-patched
            ;; goog.require is. It takes a second arg named "reload", and
            ;; the browser repl checks if (= reload "reload-all"). Other repls
            ;; only check truthiness. So my assumption is that it has the same
            ;; semantics as clojure.core/require, but with strings for easy
            ;; use from JS.
            ;;
            ;; cljs.compiler/load-libs confirms this assessment
            (map (comp #(str "goog.require('" % "', 'reload');")
                       comp/munge)
                 nss)))

(defn load-nss-html-async [nss]
  ;; This is somewhat convoluted. I'm sorry. I tried. But it's all for a reason.
  ;;
  ;; See goog-require.md for details.
  (let [loaded-libs (comp/munge 'cljs.core/*loaded-libs*)
        nss-array (str "["
                       (str/join ",\n"
                                 (map (comp #(str "\"" % "\"")
                                            comp/munge)
                                      nss))
                       "]")]
    (str "(function() {\n"
         "var nss, is_src_path, raw_paths, cache_busted_paths, deps, closure_import_script_backup, i, l;"
         "nss = " nss-array ";\n"

         "cache_busted_paths = [];\n"
         "for (i = 0, l = nss.length; i < l; i++) {\n"
         "  cache_busted_paths[i] = goog.tire_iron_name_to_path__(nss[i]);\n"
         "}\n"

         "is_src_path = {};\n"
         "for (i = 0, l = nss.length; i < l; i++) {\n"
         "  is_src_path[goog.basePath + goog.dependencies_.nameToPath[nss[i]]] = true;\n"
         "}\n"

         "deps = [];\n"
         "closure_import_script_backup = goog.global.CLOSURE_IMPORT_SCRIPT;\n"
         "try {\n"
         "  goog.global.CLOSURE_IMPORT_SCRIPT = function (src, opt_script) {\n"
         "    if (!is_src_path[src]) {\n"
         "      deps.push(src);\n"
         "    }\n"
         "  };\n"

         "  for (i = 0, l = nss.length; i < l; i++) {\n"
         "    goog.require(nss[i], 'reload');\n" ;; take advantage of cljs require implementation
         "  }\n"

         "  return goog.tire_iron_loadMany__(deps.concat(cache_busted_paths), {cleanupWhenDone: true}).addCallback(function() {\n"
         "    " loaded-libs " = cljs.core.apply.call(null, cljs.core.conj, " loaded-libs " || cljs.core.PersistentHashSet.EMPTY, nss);\n"
         "  });\n"
         "}\n finally {\n"
         "  goog.global.CLOSURE_IMPORT_SCRIPT = closure_import_script_backup;\n"
         "}\n"
         "})();")))

;; copied from cljs.repl/env->opts
(defn repl-env->build-opts [repl-opts repl-env]
  (merge (into {} repl-env)
         {:optimizations (:optimizations repl-env :none)
          :output-dir (:working-dir repl-env ".repl")}
         repl-opts))


(defn html-async-init-error? [ex-i]
  (let [{{:keys [value] :as e} :error} (ex-data ex-i)]
    ;; I tested this when I used "goog.net.jsloader.loadMany". These
    ;; errors are still fairly useful for posterity methinks.

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
    (when value
      (and (str/includes? value "TypeError")
           (or (str/includes? value "tire_iron_name_to_path__")
               (str/includes? value "tire_iron_scriptLoadingDeferred_")
               (str/includes? value "tire_iron_loadMany__"))))))

(defrecord DomAsyncRefresher []
  IRefresh
  ;; Well this funciton kind of got out of hand. See goog-require.md, source-maps.md,
  ;; and initialization.md in the discussion directory for details.
  (-initialize [this {:keys [repl-env analyzer-env repl-opts]}]
    (let [try-times-until (fn [times sleep-ms f]
                            (let [f #(try {:type :success
                                           :value (f)}
                                          (catch Exception e
                                            {:type :error
                                             :error e}))]
                              (loop [i 0
                                     last-err nil]
                                (if (< i times)
                                  (do (Thread/sleep sleep-ms)
                                      (let [{:keys [type value error]} (f)]
                                        (cond
                                          (= type :error) (recur (inc i)
                                                                 error)
                                          (and (= type :success)
                                               value) {:type :success
                                                       :value value}
                                          ;; falsy value returned
                                          :else (recur (inc i)
                                                       last-err))))
                                  {:type :failure
                                   :last-error last-err}))))
          initialized? (fn []
                         (= "true"
                            (eval-script repl-env
                                         (str "typeof goog.net !== 'undefined' &&"
                                              " typeof goog.net.jsloader !== 'undefined'"))))]
      (require-libs repl-env
                    analyzer-env
                    repl-opts
                    '[[goog.object]
                      [goog.Uri]
                      [goog.net.jsloader]])
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
      (let [{:keys [type last-error]} (try-times-until 50 100 initialized?)]
        (if (= :success type)
          (eval-form repl-env
                     analyzer-env
                     repl-opts
                     '(let [cache-busted (fn [n]
                                           (.makeUnique (js/goog.Uri.parse (str js/goog.basePath
                                                                                (js/goog.object.get js/goog.dependencies_.nameToPath
                                                                                                    n)))))
                            raw-path (fn [n]
                                       (str js/goog.basePath
                                            (js/goog.object.get js/goog.dependencies_.nameToPath
                                                                n)))
                            path (cache-busted "goog.object")]
                        ;; Since the first round of requests could be imminent and we're
                        ;; in async-land, default to raw-path until we're confident
                        ;; cache-busting is supported. See initialization.md.
                        (set! js/goog.tire_iron_name_to_path__
                              raw-path)
                        (doto (js/goog.net.jsloader.load path
                                                         (clj->js {"cleanupWhenDone" true}))
                          (.addCallback (fn []
                                          (comment (js/console.log "cache busting supported!"))
                                          (set! js/goog.tire_iron_name_to_path__
                                                cache-busted)))
                          (.addErrback (fn []
                                         (js/console.debug "The failed network call to" (.toString path) "was a test to see if your system supports source map reloading.")
                                         (js/console.debug "Unfortunately, your system does not support this feature.")
                                         (js/console.debug "This is a known issue with cljs.repl.browser/repl-env.")
                                         (js/console.debug "This will not affect your ability to use any other feature of tire-iron."))))))
          (binding [*out* *err*]
            (println (str "Unable to initialize tire-iron.\n"
                          "This should not be a common occurrance. Actually my hope was that it would never occur.\n"
                          "If this is an issue for you, I would like to know about it.\n"
                          "Please file an issue at https://github.com/potetm/tire-iron/issues\n"))
            (throw (ex-info "Unable to initialize." {} last-error)))))))
  (-refresh [this {:keys [repl-env
                          build-opts
                          compiler-env
                          before
                          after
                          state
                          all-nss
                          unload-nss
                          load-nss
                          load-deps-nss] :as opts}]
    (try
      ;; piggieback "helpfully" prints every error it encounters to the
      ;; repl for you. We don't want that if we're going to commit to handling
      ;; some errors automatically.
      (binding [*err* (io/writer (ByteStreams/nullOutputStream))]
        (eval-script repl-env
                     (str "(function() {\n"
                          (call-sym-script before)
                          (add-deps build-opts compiler-env load-deps-nss)
                          (unload-nss-script state all-nss unload-nss)
                          "var d = " (load-nss-html-async load-nss) ";\n"
                          "d.addCallback(function(ret) {\n"
                          (call-sym-script after)
                          "  });\n"
                          "})();")))
      (catch ExceptionInfo ei
        (if (html-async-init-error? ei)
          (do (comment (prn :re-initializing))
              (-initialize this opts)
              (eval-script repl-env
                           (str "(function() {\n"
                                ;; Don't call :before. If we're in this situation
                                ;; we know we've already called it and unloaded it.
                                ;; Don't call unload because it should already be done.
                                "var d = " (load-nss-html-async load-nss) ";\n"
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
                          load-nss
                          load-deps-nss]}]
    (eval-script repl-env
                 (str "(function() {\n"
                      (call-sym-script before)
                      (add-deps build-opts compiler-env load-deps-nss)
                      (unload-nss-script state all-nss unload-nss)
                      (load-nss-sync load-nss)
                      (call-sym-script after)
                      "})();"))))

;; Copied from clojure.tools.namespace/repl. This is definitionally
;; the same, but I need to get the affected namespaces.
(defn do-refresh-macros [current-ns]
  (binding [*ns* current-ns]
    (let [current-ns-name (ns-name *ns*)
          current-ns-refers (#'r/referred *ns*)
          current-ns-aliases (#'r/aliased *ns*)]
      (alter-var-root #'r/refresh-tracker dir/scan-dirs r/refresh-dirs {:platform find/clj})
      (alter-var-root #'r/refresh-tracker #'r/remove-disabled)
      (#'r/print-pending-reloads r/refresh-tracker)
      (let [affected-nss (distinct (concat (::track/unload r/refresh-tracker)
                                           (::track/load r/refresh-tracker)))]
        (alter-var-root #'r/refresh-tracker reload/track-reload)
        (in-ns current-ns-name)
        (let [result (#'r/print-and-return r/refresh-tracker)]
          (if (= :ok result)
            {::macro-reload-status :ok
             ::refreshed-namespaces affected-nss}
            ;; There was an error, recover as much as we can:
            (do (when-not (or (false? (::r/unload (meta *ns*)))
                              (false? (::r/load (meta *ns*))))
                  (#'r/recover-ns current-ns-refers current-ns-aliases))
                ;; Return the Exception to the REPL:
                {::macro-reload-status :error
                 ::error result})))))))

;; copied from cljs.closure for backward compatibility
(defn cljs-dependents-for-macro-namespaces
  [state namespaces]
  (map :name
       (let [namespaces-set (set namespaces)]
         (filter (fn [x] (not-empty
                           (set/intersection namespaces-set (-> x :require-macros vals set))))
                 (vals (:cljs.analyzer/namespaces @state))))))

(defn mark-for-reload
  "I'm not at all sure if this is legit, but it appears to work.

  Assumes the tracker graph is up-to-date, and adds the requested namespaces to
  the unload/load lists."
  [{{:keys [dependents dependencies]} ::track/deps :as tracker} namespaces]
  ;; Since the end goal is to get macro dependencies reloaded, and :require-macros
  ;; is not included in :dependencies, we need to merge all depmaps, and pretend
  ;; they *may* have dependencies. cljs can tell us which namespaces must be reloaded.
  (let [all-depmaps (merge (reduce-kv (fn [m k v]
                                        (assoc m k #{}))
                                      {}
                                      dependents)
                           dependencies)]
    (track/add tracker
               (select-keys all-depmaps
                            namespaces))))

;; Since compiling the deps file is super slow, figure out whether it must be
;; done using env/*compiler* cache.
(defn find-deps-changed [{:keys [::track/load
                                 ::track/unload
                                 ::file/filemap
                                 ::load-deps-nss
                                 ::deps] :as tracker}
                         {:keys [::ana/namespaces] :as env}]
  (let [new-deps (reduce (fn [idx n]
                           (let [{{:keys [imports
                                          requires
                                          uses]} n} namespaces]
                             (assoc idx
                               n (concat imports
                                         requires
                                         uses))))
                         {}
                         (vals filemap))]
    (assoc tracker
      ::load-deps-nss (distinct (concat load-deps-nss
                                        (filter (fn [n]
                                                  (not= (get new-deps n)
                                                        (get deps n)))
                                                (concat load
                                                        unload))))
      ::deps new-deps)))

(defn remove-disabled [tracker disable-unload disable-load]
  (-> tracker
      (update-in [::track/unload] (partial remove (set/union disable-unload
                                                             disable-load)))
      (update-in [::track/load] (partial remove disable-load))))

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
  (let [{:keys [output-dir] :as build-opts}
        (repl-env->build-opts repl-opts repl-env)

        {:keys [::macro-reload-status
                ::refreshed-namespaces]
         :as macro-status}
        (do-refresh-macros original-ns)]
    (if (= :ok macro-reload-status)
      (let [macro-cljs-dependents
            (cljs-dependents-for-macro-namespaces compiler-env
                                                  refreshed-namespaces)

            {:keys [::track/unload
                    ::track/load
                    ::file/filemap]}
            (swap! tracker (fn [t]
                             (-> t
                                 (dir/scan-dirs source-dirs {:platform find/cljs
                                                             :add-all? add-all?})
                                 (mark-for-reload macro-cljs-dependents)
                                 (remove-disabled disable-unload disable-load))))]
        (when (or (seq unload) (seq load))
          (prn :rebuilding)
          (doseq [n macro-cljs-dependents]
            (build/mark-cljs-ns-for-recompile! n output-dir))
          ;; build.api does some expensive input checks
          ;; Once we're here, no checks are needed.
          (closure/build (apply build/inputs source-dirs)
                         build-opts
                         compiler-env)
          (prn :requesting-reload load)
          ;; finding changed deps must happen after build so caches are up-to-date
          (let [{:keys [::load-deps-nss]}
                (swap! tracker find-deps-changed @compiler-env)]
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
                                 :load-nss load
                                 :load-deps-nss load-deps-nss})))
        (swap! tracker assoc
               ::track/load nil
               ::track/unload nil
               ::load-deps-nss nil)
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
                                              (.endsWith % ".cljc")
                                              ;; Allow them to watch macro files if they want.
                                              ;; It's no skin off my back.
                                              (.endsWith % ".clj"))
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
     1. Clojure build environment is refreshed (via `clojure.tools.namespace.repl/refresh`)
     2. ClojureScript files are re-compiled
     3. :before is called
     4. Vars are removed (the `:state` symbol is left untouched)
     5. Namespaces are re-loaded
     6. :after is called

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
             (reset! tracker (track/tracker))))
     #_#_'print-tracker
         (wrap (fn [_ _ _ _]
                 (clojure.pprint/pprint @tracker)))}))
