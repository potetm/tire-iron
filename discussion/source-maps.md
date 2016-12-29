This phrase from the [REPL manifesto](https://github.com/clojure/clojurescript/wiki/Custom-REPLs/3d4d0d3cb9984af382e67f0109d132e5d50fd6bb#expectations)
sent me down the rabbit hole of making sure we properly support source maps.
> While it is OK to stream compiled forms the user has entered this should be avoided
> at all costs for loading namespaces - REPLs should rely on the target environment
> to interpret goog.require. This has many benefits including precise source mapping
> information.

As far as I can tell only Chrome really supports CLJS source maps at the time
of this writing. Firefox has some support built in, but it doesn't appear to allow
you to, say, click to jump to the source or to set breakpoints in the source.

This should be as easy as adding `:source-map-timestamp true` to your build parameters.

Unfortunately, there's [a bug in Chrome](https://bugs.chromium.org/p/chromium/issues/detail?id=438251)
that prevents proper reloading of source maps that's been open for a couple of
years.

It appears that figwheel gets around this by adding a cache busting query string
to requests for compiled JS files which forces Chrome to reload source map data
for a file.

There is, however, and additional problem: The browser repl doesn't properly handle
query strings when it's serving local files. You get a 404. (For the record, curling
a file via `file://` with nonsense query params works fine. This is a proper bug.)

For some reason, I'm not able to get the browser repl working when I set
`:serve-static false`. I might want to look into that again and actually document
what's going on there.[1]

Either way, I should probably to assume `:serve-static true` is a fairly normal
operating mode.

Everything works fine if you run through a weasel repl. (Because, again, the file
system properly drops nonsense query params.)

So I need to handle this somehow.

[1] - After looking into this a little bit more, I'm pretty sure `:serve-static`
isn't really a flag for the browser repl. It doesn't appear to be read anywhere.
So it _always_ serves static files. Furthermore, because `clojure.browser.net/xpc-connection`
gets its [peer poll uri](https://developer.pubref.org/static/apidoc/global/closure/goog/net/xpc/xpc.js.html)
for the iframe from the window uri, `:serve-static false` _cannot_ work. All files
must flow throw the repl server.

I put together a patch for this. Just need to find the time to write it up
and submit it.

---

Decision time:

I'm going to hack in a workaround for the BrowserEnv. I considered making a protocol
so that others could work around it, but that's basically a really convoluted way
of providing something that could be an option flag. If it turns out that I can't
reliably provide source map reloading, then I'll put in a flag and document it.
However, my suspicion is that I _can_ provide it in a way that's transparent to the user.
Right now the only outstanding issue I'm aware of is the BrowserEnv query params
issue. So I'll commit to providing that, but silently work around the BrowserEnv
issue until it's patched in cljs.

One thing to consider about this decision: If I rely on a brand new cljs fix, I would
be breaking support for older versions of cljs. One option is to check
`cljs.util/*clojurescript-version*` to see if I can support the user's version.
That actually seems pretty reasonable. It's unfortunate that I have to work around
that for forever, but the alternative is a more convoluted API for my users.

Such is life.

---

Omg. I forgot that piggieback wraps repl envs. So I can't reliably check for BrowserEnv
directly. I also can't test just for piggieback delegating repls either, because
they're dynamically generated and could be in any namespace. In addition, they are
types, not records, so in order to test if the nested env is a BrowserEnv, I would
have to call `(.repl_env pb-env)`, meaning I'm at risk of a null pointer.

I could try/catch the null pointer.

This is turning into hacks upon hacks.

---

Okay. I'm going to commit to providing this whenever their repl env supports it.

I'll add some steps to initialization to determine if we can cache-bust and install
a buster if we can.

The cost of this is a touch of complexity and a few more network calls on initialization.
It'll add some goog dependencies to the JS env, but I'm not at all opposed to that.

The upside is it will accurately detect whether their system will support cache-busting.
So when/if this the browser repl is fixed, users' repls will suddenly support source
maps with no other work required. Another upside is that this has absolutely no
effect on our ability to refresh code. This is a nice-to-have feature that can
be easily discarded if their system doesn't support it.

The reason we have to install a cache-buster in the JS environment is:
  * We _cannot_ know for sure if we can cache bust unless we try it
  * Because the browser is async, we can't just try to download, wait for a response,
    and store some state server-side. We would have to come up with some rendezvous
    scheme, which would invariably break eventually.

### TODO
File a bug to allow browser-env to serve timestamped mappings files.

While you're at it, you might as well file a report to add
an ack routine to the browser repl's XPC setup, per
https://books.google.com/books?id=p7uyWPcVGZsC&lpg=PA174&ots=x8aIPxR6yL&dq=xpc%20connection%20google%20closure&pg=PA175#v=onepage&q=xpc%20connection%20google%20closure&f=false
That ought to make setup more reliable. Might be the fix for
http://dev.clojure.org/jira/browse/CLJS-1479

Aaaand while you're at that, you might as well file a report
that would permit the browser repl to actually run w/o serving
static files (only with a local server for REPL interactions).
(BTW, this would allow cache-busted files to work as well.)
