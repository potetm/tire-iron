# tire-iron
Bringing the Reloaded Workflow to ClojureScript

## Motivations
1. Facilitate the [Reloaded Workflow](http://thinkrelevance.com/blog/2013/06/04/clojure-workflow-reloaded) in ClojureScript.
2. Facilitate sane, managed application startup (a la [Component](https://github.com/stuartsierra/component)).
3. Provide full namespace reloading (uninstall old vars)
4. Function out of the box in every ClojureScript environment
5. Permit preserving application state during reloading
6. Minimize dependencies and complexity (only requires tools.namespace and a cljs REPL)

## Installation
```
[com.potetm/tire-iron "0.1.0-SNAPSHOT"]
```

tire-iron should not be included in your production code (e.g.
it should be in a [leiningen profile](https://github.com/technomancy/leiningen/blob/master/doc/PROFILES.md)).

## Overview
tire-iron works by creating `:special-fn`s that to you add to
your ClojureScript REPL like so:

```clj
(ns my-repl
  (:require [cljs.repl :as repl]
            [com.potetm.tire-iron :as ti]))

(repl/repl :special-fns (ti/special-fns :source-dirs ["src"]
                                        :state 'my-ns/my-repl-conn))
```

This installs a few functions into your REPL. The most important
of these are `ini` and `refresh`.

```clj
To quit, type: :cljs/quit
=> nil
(init)
=> nil
(refresh)
:rebuilding
:requesting-reload (com.potetm.browser-other com.potetm.browser-client)
:ok
=> nil
```

### A note about browser REPLs
Having multiple browser REPL connections will render your system unusable.
To prevent this you should always:
1. Put your REPL connection in your `:state` var
2. Use `defonce` for your `:state` var

## The Deets
`com.potetm.tire-iron/special-fns` accepts the following arguments:

```
:source-dirs - A list of strings pointing to the source directories you would like to watch
:add-all? - Boolean indicating whether all namespaces should be refreshed
:before - A symbol corresponding to a zero-arg client-side function that will be called before refreshing
:after - A symbol corresponding to a zero-arg client-side function that will be called after refreshing
:state - A symbol corresponding to a client-side var that holds any state you would like to persisent between refreshes
```

All of these values can be overridden in the REPL by supplying them in
the same manner to `refresh`.

Since `:special-fns` are just symbols that are handled specially by the REPL,
`refresh` cannot be used as part of a script. Hence the reason why the `:before`, `:after`,
and `:state` arguments have been provided.

Refresh happens in the following order:
 1. :before is called
 2. refresh happens
 3. :after is called

`com.potetm.tire-iron/special-fns` returns a map of the following fns for use in the cljs repl.

```
'init            - Must be called prior to refresh.
'refresh         - refreshes :source-dirs. Any passed args will override the values passed to `special-fns`.
'clear           - Clear the tracker state.
'disable-unload! - Add a namespace to the disabled unload list.
'disable-reload! - Add a namespace to the disabled reload list.
'print-disabled  - See the disabled lists.
```

## Example System
## License

Copyright © 2016 Timothy Pote

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
