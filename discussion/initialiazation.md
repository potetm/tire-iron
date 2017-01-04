As discussed in [goog-require.md](./goog-require.md), we have two methods of
refreshing: dom-async and sync. Again, sync is not a problem. However,
dom-async requires a little bit of initialization. Namely, we need to:

1. Download the `goog.net.jsloader` and `goog.Uri` namespaces
2. Install some functions
3. Run a routine to figure out if [cache-busting](./source-maps.md) is supported

The problems with this center around asynchrony. We need downloading
namespaces to happen first, _then_ we can run the cache-busting support
check.[1]

This is a classic chicken and egg problem. I'm downloading `jsloader` because I
need callbacks after downloads are complete. And I need it while downloading
`jsloader` as well.

Possible solutions:

1. Ping the browser in a loop until `jsloader` downloads.
  * This is going to be slower than is necessary. It also requires handling the
    case where we never hear back from the client, which will be a pretty
    abysmal case in the eyes of the user.
2. Send the namespaces _and all of their dependencies_ down via the repl
  * This is quite a lot of work when the reality is the user will almost
    certainly have most required dependencies already
  * This also requires that we resolve these dependencies via cljs. This is
    definitely not a public API, and I'm getting pretty scared that I've gone
    too far into their internals already.
3. Hijack `goog.require` (as we do in the `refresh` process), and deliver a
   minimal set of namespaces as a prelude to the cache-busting check script.
  * This would work almost perfectly. However, line numbers for those
    namespaces would be off forever. So unlike the "maybe miss source file
    remaps for a single round of requests," this one is certain, and it never
    resolves.

None of these are excellent. I'm kind of hoping I come up with something else.
However, in the meantime, I'm going to go with Option 1. We can make the sleep
time fairly small (maybe 10s of ms) to mitigate waiting. We can also make the
abysmal case better by giving a decent explanation of what's going on.

---
[1]
Ideally we would also be able to pause things while we check cache-busting
support. However, we can get away with assuming it's not supported until we
confirm that it is. This is a tradeoff of increased responsiveness for the
possibility of firing off one round of requests without cache-busting turned on.
