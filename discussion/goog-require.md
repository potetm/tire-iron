Okay this one's a bit of a doozy.

clojure.browser.repl/bootstrap monkey patches goog.isProvided_ because of the
error that's thrown if a namespace is already provided. The problem with
that approach is that goog.require uses goog.isProvided_ as a cache check,
which we want to one degree or another.

goog.provide checks for the existence of an object at the namespace location.
So we might want to consider a) doing some other check and monkey patching that in
or b) figuring out how others appear to be handling this. I tinkered w/ figwheel
for quite a while, but couldn't figure out how they prevent duplicate loading
of namespaces during refresh. They do something, but in the end they appear to fall
back on goog.require, which I would think would trigger a re-download in a compiled
namespace.

We already load libs in dependency order, so we don't want
to re-download every time someone says goog.require('foo'). I think fundamentally,
we don't want goog.require to be available willy nilly. Not sure though.
It's not like bootstrap doesn't monkeypatch it to hell already. But everyone
expects that by now. But downloaded libs WILL call it. And we need to handle that.

Right now I'm considering changing goog.isProvided_ to answer the question
as we would like it answered (i.e. by checking goog.dependences.(written/visited)).
It's not like answering `true` will break anything (as evidenced by bootstrap setting
it to (constantly true)). I'm not sure what the occasional `false` will yield.
Likely nothing.

---

Okay I think this will work:

We wrap goog.require yet again. I'm not happy about it, but it seems to be the most
straightforward thing. We check `cljs.core/*loaded-libs*` (which we manage in tire-iron
already) for the required namespace, no-op if it's in there, proxy forward if it's
not.

This has the benefit of not overloading yet again the meaning of isProvided_. This will
also play nice w/ other overloads of goog.require. This is arguably what needs to
be done in the first place.

Nope that doesn't work.

We pass the list of libs to load to goog.net.jsloader.loadMany, which doesn't give us
the opportunity to interleave loads with calls to update central state (in this case,
`*loaded-libs*`.

We don't use `goog.require` in the first place (even though the bootstrapped version
ensures sequential ordering) because we need a hook to call `:after` after loading.

I want everything to work "normally" except when reloading. In other words, I want
to not affect functionality except when reloading is occurring.

---

Okay I solved the mystery of the figwheel reloading process. Super thanks to the
browser debugger.

It goes like this:

-> receive reload msg
-> figwheel.client.file-reloading/figwheel-require
     figwheel-require does "unprovide"
-> goog.require
     does "provide"
-> CLOSURE_IMPORT_SCRIPT (aka figwheel.client.file-reloading/queued-file-reload)
     puts to the reload queue
-> figwheel.client.file-reloading/reload-file*
-> goog.net.jsloader/load

So they get proper "require"ing _and_ hooks into the require process for post-load
operations.

---

Okay.

I'm not sure what to think about this right now, but it appears to work.

`goog.require` sets up a few caches to prevent duplicate loading. That's why you would
see `clojures.browser.repl` and figwheel do the following dance:

```
path = goog.dependencies_.nameToPath[ns_string];
goog.object.remove(goog.dependencies_.visited, path);
goog.object.remove(goog.dependencies_.written, path);
goog.object.remove(goog.dependencies_.written, goog.basePath + path);
```

Because I'm going through `goog.net.jsloader`, nothing I'm doing uses those locations.
So some of the friction I'm feeling is the fact that I was cargo-culting that code around,
then not refreshing the caches on load. So one obvious option is to stop cargo culting
that code and just use `jsloader`. I'm not sure what that might break, but the upsides are:
  * I get better control over the reloading process (via `Defferred`)
  * I don't mess with the current `goog.require`, meaning everything that did work still does

Assuming that doesn't fundamentally break something, this seems like the best option.

---

What goog.require appears to do:
  1. Create a dependency-ordered list of scripts that need to be loaded
    * It skips dependencies that have already been loaded by checking
      * `goog.dependencies_.visited`
      * `goog.dependencies_.written`
    * During this, it marks the fact that dependencies are slated for loading
      in `goog.dependencies_.visited`
  2. Marks all of the the dependencies as loading in `goog.dependencies_.written`
  3. Writes all of the script tags
    * During this it re-marks the dependencies as loading in `goog.dependencies_.written`
      But this time it prepends `goog.basePath` to the load path.

So basically, it writes the script tags, but caches information along the way.

I would be a little wary of taking advantage of these implementation details, but
the browser repl already tweaks the following:
  * `goog.dependencies_.visited` - clears the cache
  * `goog.dependencies_.written` - clears the cache
  * `goog.require` - monkey patches to clear the above caches
  * `goog.writeScriptTag_`
     * This is the fn that writes the tag to the document. It's not meant to be
       monkey patched, but closure does provide an official path to override it:
       `goog.global.CLOSURE_IMPORT_SCRIPT`

So ClojureScript is already taking advantage of a lot of implementation details.

My solution is actually ignoring all of those details. The only assumption I'm making
is that by managing dependency ordering and script tag writing myself, I won't be
breaking anything. Given the way it works, the worst case is that you would get a
duplicate load when a new dependency goes through `goog.require`, because I didn't
write it to `goog.dependencies_.visited` and `goog.dependencies_.written`. Even
then, I'm having trouble coming up with a scenario where that's a particular problem.
If you get in a weird state because during the loading process you re-compile a
file that's getting a duplicate load, the subsequent refresh will fix it. (Assuming
that you're compiling via `refresh`. tire-iron is not designed to run with
the built-in cljs watcher running.)

The alternative to all of this is to basically re-implement what figwheel does.
Override `goog.global.CLOSURE_IMPORT_SCRIPT` to put to a queue I can manage and
create hooks for. That seems like a lot of additional complexity with the only
discernible benefit being that Closure was designed to run that way.

Unless I discover that going that route provides something more than caching,
dependency ordering, and script-writing, I'm going to stick with what I have.