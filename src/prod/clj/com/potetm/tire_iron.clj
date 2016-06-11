(ns com.potetm.tire-iron
  (:require [clojure.tools.namespace.dir :as dir]
            [clojure.tools.namespace.find :as find]
            [clojure.tools.namespace.track :as track]
            [cljs.analyzer :as ana]
            [cljs.compiler :as comp]
            [cljs.env :as env]
            [cljs.repl :as repl]))

(defonce refresh-tracker (track/tracker))
(defonce refresh-dirs [])

(defn print-and-return [tracker]
  (if-let [e (::error tracker)]
    (do (repl/err-out (prn :error-while-loading
                           (::error-ns tracker)))
        e)
    :ok))

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

(defn compile-unload-ns [ns]
  (let [ns (comp/munge ns)
        loaded-libs (comp/munge 'cljs.core/*loaded-libs*)]
    (str "(function(){\n"
         "var ns = " ns ";\n"
         "var ns_string = \"" ns "\";\n"
         "var path = goog.dependencies_.nameToPath[ns_string];\n"
         "goog.object.remove(goog.dependencies_.visited, path);\n"
         "goog.object.remove(goog.dependencies_.written, path);\n"
         "goog.object.remove(goog.dependencies_.written, goog.basePath + path);\n"
         "for (var key in ns) {\n"
         "  if (!ns.hasOwnProperty(key)) { continue; }\n"
         ;; setting to null is critical to allow defonce to work as expected.
         ;; defonce checks typeof var == "undefined"
         ;; we need to allow certain vars to _not_ be redefined in order to
         ;; maintain only one repl connection.
         ;;
         ;; I'm not certain, but it seemed like multiple connections usually
         ;; resulted in stackoverflows in goog.require.
         "  ns[key] = null;\n"
         "}\n"
         loaded-libs " = cljs.core.disj.call(null, " loaded-libs ", ns_string);\n"
         "})();")))

(defn remove-lib [repl-env ns]
  (let [{:keys [status value] :as ret}
        (repl/-evaluate repl-env
                        "<cljs repl>"
                        1
                        (compile-unload-ns ns))]
    (case status
      :error (let [e (ex-info "Error removing lib."
                              {:type :js-eval-error
                               :namespace ns
                               :error ret})]
               (repl/err-out (pr e))
               (throw e))
      :exception (let [e (ex-info "Error removing lib."
                                  {:type :js-eval-exception
                                   :namespace ns
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

(defn track-reload-one
  "Executes the next pending unload/reload operation in the dependency
  tracker. Returns the updated dependency tracker. If reloading caused
  an error, it is captured as ::error and the namespace which caused
  the error is ::error-ns."
  [repl-env analyze-env repl-opts tracker]
  (let [{unload ::track/unload, load ::track/load} tracker]
    (cond
      (seq unload)
      (let [n (first unload)]
        (remove-lib repl-env n)
        (update-in tracker [::track/unload] rest))
      (seq load)
      (let [n (first load)]
        (try (require-lib repl-env analyze-env repl-opts n)
             (update-in tracker [::track/load] rest)
             (catch Throwable t
               (assoc tracker
                 ::error t ::error-ns n ::track/unload load))))
      :else
      tracker)))

(defn track-reload
  "Executes all pending unload/reload operations on dependency tracker
  until either an error is encountered or there are no more pending
  operations."
  [repl-env analyze-env opts tracker]
  (loop [tracker (dissoc tracker ::error ::error-ns)]
    (let [{error ::error, unload ::track/unload, load ::track/load} tracker]
      (if (and (not error)
               (or (seq load) (seq unload)))
        (recur (track-reload-one repl-env analyze-env opts tracker))
        tracker))))

(defn refresh* [{:keys [repl-env
                        analyzer-env
                        repl-opts
                        before
                        after
                        state
                        add-all?]}]
  (let [eval-form* (partial eval-form
                            repl-env
                            analyzer-env
                            repl-opts)]
    (doseq [s (filter some? [before after state])]
      (assert (symbol? s) "value must be a symbol")
      (assert (namespace s)
              "value must be a namespace-qualified symbol"))
    (when before
      (eval-form* `(if ~before
                     (~before)
                     (throw (js/Error.
                              (str "Cannot resolve :before symbol " ~before))))))
    (when state
      (eval-form* `(if ~state
                     (set! (.-tire_iron_state_ js/goog) ~state)
                     (throw (js/Error.
                              (str "Cannot resolve :state symbol " ~state))))))
    (alter-var-root #'refresh-tracker dir/scan-dirs refresh-dirs {:platform find/cljs
                                                                  :add-all? add-all?})
    (prn ::reloading (::track/load refresh-tracker))
    (alter-var-root #'refresh-tracker (partial track-reload repl-env analyzer-env repl-opts))
    (let [result (print-and-return refresh-tracker)]
      (if (= :ok result)
        (do
          (when state
            (eval-form* `(set! ~state (.-tire_iron_state_ js/goog))))
          (when after
            (eval-form* `(if ~after
                           (~after)
                           (throw (js/Error.
                                    (str "Cannot resolve :after symbol " ~after))))))
          ;; print the result because cljs-repl forces a nil return value
          (println result))
        (throw result)))))

(defn refresh [{:keys [before after state] :as closed-settings}]
  (fn [repl-env analyzer-env [_ & opts] repl-opts]
    (let [{:keys [before after state] :as passed-settings} (apply hash-map opts)]
      (refresh* (merge closed-settings
                       passed-settings
                       {:repl-env repl-env
                        :analyzer-env analyzer-env
                        :repl-opts repl-opts})))))

(defn recover-state [closed-state]
  (fn [repl-env analyzer-env [_ passed-state] repl-opts]
    (let [state (or passed-state closed-state)]
      (eval-form repl-env
                 analyzer-env
                 repl-opts
                 `(set! ~state (.-tire_iron_state_ js/goog))))))

(defn print-state [repl-env analyzer-env _ repl-opts]
  (println
    (eval-form repl-env
               analyzer-env
               repl-opts
               `(.-tire_iron_state_ js/goog))))

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

(defn set-refresh-dirs [dirs]
  (alter-var-root #'refresh-dirs (constantly dirs)))

(defn special-fns [{:keys [source-dirs
                           before
                           after
                           state]}]
  (set-refresh-dirs source-dirs)
  {'refresh (wrap (refresh {:before before
                            :after after
                            :state state}))
   'refresh-all (wrap (refresh {:before before
                                :after after
                                :state state
                                :add-all? true}))
   'blow-it-away (wrap (refresh {:add-all? true}))
   'print-tire-iron-state (wrap print-state)
   'recover-tire-iron-state (wrap (recover-state state))})
