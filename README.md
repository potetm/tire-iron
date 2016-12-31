# Tire-iron
Bringing the Reloaded Workflow to ClojureScript

## Goals
1. Facilitate the [Reloaded Workflow](http://thinkrelevance.com/blog/2013/06/04/clojure-workflow-reloaded) in ClojureScript.
2. Provide full namespace reloading (including uninstalling old vars, reloading macros)
3. Encourage managed application startup (a la [Component](https://github.com/stuartsierra/component)).
4. Function out of the box in every ClojureScript environment
5. Add zero dependencies to your ClojureScript code (only requires built-in google-closure libraries).
6. Allow you to preserve application state during reloading
7. Permit automatic reloading on file change

## Installation
```clj
[com.potetm/tire-iron "0.2.0"]
```

tire-iron should not be included in your production code (e.g.
it should be in a [leiningen profile](https://github.com/technomancy/leiningen/blob/master/doc/PROFILES.md)).

The easiest way to get started with tire-iron is to use the [cljs-reloaded](https://github.com/potetm/cljs-reloaded-template)
Leiningen template. Just run `lein new cljs-reloaded my.group/my-awesome-project`, and you're off to the races!

## Overview
tire-iron works by creating `:special-fn`s that to you add to
your ClojureScript REPL like so:

```clj
(ns my-repl
  (:require [cljs.repl :as repl]
            [cljs.repl.browser :as browser]
            [com.potetm.tire-iron :as ti]))

(repl/repl (browser/REPL-env)
           :special-fns (ti/special-fns :source-dirs ["src"]
                                        :state 'my-ns/my-repl-conn))
```

This installs a few functions into your REPL. The most important
of these are `refresh` and `start-watch`.

```clj
To quit, type: :cljs/quit
=> nil
(refresh)
:reloading ()
:rebuilding
:requesting-reload (com.potetm.browser-other com.potetm.browser-client)
:ok
=> nil
browser.user=> (start-watch) ;; starts a file watcher that reloads on save
nil
browser.user=> ;; Update a source file and save it
Watcher Refreshing...
:reloading ()
:rebuilding
:requesting-reload (com.potetm.browser-client)
:ok
```

### A note about browser REPLs
Having multiple browser REPL connections will render your system unusable.
To prevent this you should always:

1. Put your REPL connection in your `:state` var. This preserves it during namespace unloading.
2. Use `defonce` for your `:state` var. This preserves it during namespace loading.

## The Deets
`com.potetm.tire-iron/special-fns` accepts the following arguments:

```
:source-dirs - A list of strings pointing to the source directories you would like to track
:add-all? - Boolean indicating whether all namespaces should be refreshed
:before - A symbol corresponding to a zero-arg client-side function that will be called before refreshing
:after - A symbol corresponding to a zero-arg client-side function that will be called after refreshing
:state - A symbol corresponding to a client-side var that will not be unloaded
:disable-unload - A list of symbols corresponding to namespaces that should not be unloaded
:disable-reload - A list of symbols corresponding to namespaces that should not be reloaded
```

`:add-all?`, `:before`, `:after`, and `:state` can be overridden in the REPL by supplying them
to `refresh` or `start-watch`. This can be used in a variety of ways. For example:
  * Starting an application from a Node REPL: `(refresh :before nil)`
  * Dropping all current state: `(refresh :state nil :add-all? true)`
    * Don't do this if your REPL connection is in your state var
  * Trying to recover from a bad environment state: `(refresh :before nil :after nil :add-all? true)`

Because `:special-fns` are just symbols that are handled specially by the REPL,
`refresh` cannot be used as part of a script. Hence `:before`, `:after`,
and `:state` arguments have been provided.

Refresh happens in the following order:
 1. Clojure build environment is refreshed (via `clojure.tools.namespace.REPL/refresh`)
 2. ClojureScript files are re-compiled
 3. `:before` is called
 4. Vars are removed (the `:state` symbol is left untouched)
 5. Namespaces are re-loaded
 6. `:after` is called

`com.potetm.tire-iron/special-fns` returns a map of the following fns for use in the cljs REPL.

```
'refresh         - Refreshes :source-dirs. Any passed args will override the values passed to `special-fns`.
'start-watch     - Start a watcher that will automatically call refresh when any file under :source-dirs changes.
'stop-watch      - Stop a running watcher.
'clear           - Clear the tracker state.
'disable-unload! - Add a namespace to the disabled unload list.
'disable-reload! - Add a namespace to the disabled reload list.
'print-disabled  - See the disabled lists.
```

*NOTE*: tire-iron has no access to the lifecycle of the REPL, so it cannot
automatically stop a watcher for you. Hence 'stop-watch has been provided for
you to manage it yourself. If you forget to stop a watch before you end your
REPL session, you can call the only other tire-iron API call at any time: `com.potetm.tire-iron/stop-watch`

### One "Gotcha"
Something you might not anticipate is the refreshing of your CLJ REPL namespaces during
tire-iron `refresh`. This is required in order to get macro refreshing. To avoid
issues with this, you should keep your _ClojureScript build environment_ separate from
any other environment you might have (e.g. server environment). **Tire-iron is intended
to be used within the context of your _ClojureScript build environment_.**

## Comparison with [Figwheel](https://github.com/bhauman/lein-figwheel)
### Project Goals
Figwheel is an excellent tool for those getting started with ClojureScript.
It goes out of its way to hide details that routinely cause friction, even among
experienced ClojureScript developers. However, the ease of getting started
comes at the cost of some increased complexity and decreased flexibility. Namely,
Figwheel:
  * Requires the use of Leiningen
  * Requires the use of cljsbuild
  * Is tightly integrated with the above
  * Adds a dependency on `core.async` to your ClojureScript code
  * Adds a number of dependencies to your build environment
  * Is only designed to work in the browser and in Node.
  * Because of its scope, there are a number of settings you must become familiar with
  * Is all-or-nothing proposition. E.g. You can't have code-reloading without using
    the provided server/REPL toolchain.

Tire-iron provides little in the way of easing the transition to ClojureScript.
However, it:
  * Makes no assumptions about your choice of build tool.
  * Only requires that your [REPL Env](https://github.com/clojure/clojurescript/wiki/Custom-REPLs)
    implements `cljs.repl/IJavaScriptEnv` and supports `:special-fns`. (Both are standard requirements.)
  * Makes no assumptions regarding your JS environment.
  * Adds two dependencies to your build environment:
    * [tools.namespace](https://github.com/clojure/tools.namespace)
    * [java.classpath](https://github.com/clojure/java.classpath/) (As a dependency of tools.namespace.)
  * Adds no dependencies to your ClojureScript code. (It only uses built-in google-closure libraries.)
  * Doesn't require any settings, and has very few settings overall.
  * Can be used á la carte.

I have attempted to partially mitigate the difficulty of getting started with tire-iron
by starting the [cljs-reloaded](https://github.com/potetm/cljs-reloaded-template) Leiningen
template. However, this approach is also fundamentally different from Figwheel in that it only
saves you from having to consider some complexity up front. It does not attempt to hide
any complexity from you.

### Feature Differences
#### Things Figwheel does that are outside the scope of Tire-iron
The following can be provided by other means, but will never be within the scope
of tire-iron itself.
* Message broadcasting
* REPL re-connection
* Interpretation of compiler errors
* Integration with [CLJS DevTools](https://github.com/binaryage/cljs-devtools)

#### Things Figwheel does that Tire-iron does not
* Respects `defonce` (This is the result of not removing vars prior to reloading.)
  * Tire-iron removes _all_ vars except the one specified via the `:state` setting.
* Reloads CSS
  * I'm considering this as a feature, but I'm not committed to it yet.
* Provides a user-friendly heads up display
  * I'm also considering this, but, again, I'm not committed to it.
* Only loads dependencies that have already been loaded
  * Right now, tire-iron works exactly as tools.namespace does. On the first
    call to `refresh` every dependency is reloaded. This is fairly fast, even in a
    large codebase. Subsequent calls to `refresh` trigger minimal reloads. I'm considering
    changing this, since CLJS REPLs tend to be slower than CLJ REPLs, but I would like
    to make sure it's worth the time and effort before committing to this.

#### Things Tire-iron does that Figwheel does not
* Unloading of namespaces (removing of defunct vars, clearing all state)
* Full reloading of macros
* On-demand reloading (via `refresh`)

## Acknowledgements
Though he was not directly involved with this project, tire-iron would not have been
possible without the work of [Bruce Hauman](https://github.com/bhauman) and
[Figwheel](https://github.com/bhauman/lein-figwheel).

## License

Copyright © 2016 Timothy Pote

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
