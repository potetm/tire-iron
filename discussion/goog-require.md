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
