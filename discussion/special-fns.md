Okay here's the problem:

Files are changed on the server. The client is the one that needs to
load those files. If you can get those files to the client, there's
a little routine in clojure.browser.repl/bootstrap to unload and load an
ns.

figwheel starts an http server up and pushes those files to the client via
websocket.  It would be nice to not have to do anything that heavy-handed.
In other words, it shouldn't require another server, it shouldn't require another
connection, it shouldn't require any more deps than clojure.tools.namespace.

Here's an idea:
cljs.repl/repl* allows you to pass in :special-fns. They use it for repl-specific
things like require, import, and load-namespace. I could add a function in there
that would call out to a namespace that would keep state (via tools.namespace.tracker)
and figure out what namespaces have changed. It would then emit code to reload
those namespaces.

I'm going to have to make sure the special-fn is properly wrapped to restore repl
state. See cljs.repl/wrap-self.

Downsides:
It would require you to have a watch to auto compile files, and it would require
that you wait until that compilation has happened to reload namespaces. I really
don't think there's a way around that. That's kind of the nature of cljs.

I guess I _could_ compile those files myself. Downside there is it's more than
I want to do. Upside is 1) you don't have to have a watch 2) you only compile
when you are ready to load the file. cljs.repl/load-file *is* public. (You don't
want to use that, but reimplementing some of it seems pretty trivial.)

Questions:
 * How does this play with ssl? (it will probably make the browser complain)
   * I'm not sure this is any different than figwheel. You have to run chrome
     with special flags to get it to work with ssl.

Bad ideas:
Add some kind of wrapper.
 There's no middleware in the cljs.repl, so I would have to roll my own.
 Nobody would use that.

Write your own read.
 Editing pre-parsed strings a) is bound to have problems and edge cases and
 b) doesn't allow you to break out to raw js if need be.

Write your own eval.
 cljs.repl/eval-cljs is private. I would have to re-implement it, which should
 be trivial, but isn't what I want to be doing. Compared to special-fns, which
 is an explicit hook to add random functionality, it's much lower level than
 I want to be.

Serve from a "static" file on the server.
 This would require kicking off some kind of process to keep that file up-to-date.
 Would would require knowing when it was last fetched. Which basically means another server.

Open another xpc connection.
 This is basically another server. The only upside here is it would require fewer
 dependencies.
