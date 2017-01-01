Macros need to be refreshed *within the running JVM*. This can be done reliably
with `tools.namespace`. The question is: How do we implement it? We could keep
our own tracker, separate from any tracker the user might have running in their
repl, but the underlying fact is that you're mutating their repl environment.

It's probably better to not play around with that fact and just run through the
main `tools.namespace/repl` API. I think the majority of the time, it will do
exactly what the user expects. The only time it might get weird is if they're
running some kind of background process in their repl, and their clojure
environment starts to change underneath them.

Perhaps there's a way to allow users to opt out of a JVM Env refresh. This will
be helpful if they discover that it's not what they expected.

On the other hand, I'm not sure if that use case is a good one. They're
creating a clj repl environment that, apparently, is mixed in with some other
environment. While I'm sure some developers will do that, it's generally better
to clearly separate your build and runtime environments.[1] The cljs repl is
fundamentally tied to your build environment in a production system. The
environment it runs in should reflect that.

In addition, one of the main goals of tire-iron is to provide an easy way to
quickly and reliably reproduce an environment as close to production as
possible. I want people to be confident that what they experience during
development aligns with production behavior. Muddying the dependencies between
build and runtime environments violates that goal.

I don't think I'm gonna support that.

You should make a cljs repl using cljs build-time tools. Use another JVM for
other activities. At least until we get dependency isolation within a JVM.

Doing this, however, introduces one other small complication: I have to
coordinate two trackers (the one tracking cljs/cljc source files and the one
tracking the clj/cljc classpath files.) I could try and combine them, but that
cljs shouldn't need to be on the classpath, and doing so might make it
difficult to properly track dependencies. For example, someone has a
namespace that is named the same in clj and cljs.

It just seems more straightforward to refresh the build environment, then
refresh the compiled output separately.

There are a few ways to do this. I could manually merge tracker changes
between clj and cljs trackers. However, because the cljs build optimizes out
unnecessary re-compiles, those files might be missed on cljs/build.

The cljs watcher does a neat trick: it touches files that are dependents of
macro files, which triggers a re-compile during the build phase. Since I
*must* trigger a rebuild of dependent files as well, that seems like a good
approach. This will also trigger a refresh because tools.namespace also tracks
via last modified date.

---

[1] - The reason for this is that dependencies could be overridden in
development that would never happen in production. In theory, if you could keep
those dependencies completely separate, say with jigsaw, it might be possible
to reliably host a build-time and runtime environment in the same JVM.

---

Okay, after thinking about it some more, the trick of touching _source_ files
is a bad trick. The cljs watcher touches _output_ files to mark them for
re-compile. That won't work for me, because I need to notify the tracker that
a file has changed. So I initially just marked the source files instead.
This is bad for two reasons:

  1. I'm modifying source files. No matter how much I try and minimize the
     importance of the modified date, this is just wrong.
  2. (Due to #1) If you have a watcher running, updating a macro and calling
     `refresh` will trigger the watcher to re-refresh. This isn't horrible,
     but it is a bug, and I don't want users to have to figure out what's
     going on there.

So I'm going back to the first solution: Merge changes between the clj/cljs
trackers. I also need to do what the cljs watcher does and touch _output_
files for recompile by the build. So it's more computer work overall, but
it's the right thing to do.
