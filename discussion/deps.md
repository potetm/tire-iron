Managing dependencies turned out to be a little tricker than
I'd hoped.

My initial pass was a pretty naive implementation pieced together
from what cljs.repl/load-namespace does. That was pretty abysmally
slow (100s of ms for very small project, and I got it to hang
for a _long_ time on a large project).

I tried to optimize this by assuming that everything I needed
would be cached in `env/*compiler*` (`::closure/compiled-cljs`)
and adding dependency information for each namespace direct
from that cache. This implementation had an error in that it did
not add dependency information for the rest of the dependency
chain.

I then figured out that `cljs_deps.js` was getting updated on re-compile,
and I attempted to serve direct from that file. The problem with
this is the existence of `cljs_deps.js` is predicated on a very
particular build setting. It must have a `:main` defined. This will
not be true for node REPLs. As far as I can tell, `:main` doesn't
work with node at all. Not to mention, I've not knowingly put any
restrictions on build options besides requiring `:optimizations :none`.

I think what I'm going to do is re-build the dependency file and
serve it in my refresh code. This will be the most reliable way
to transmit dependencies. Unfortunately this will be somewhat slow.
The fastest I could get it was ~70ms for a very small project. I'll
test on a larger project. There's surely ways to optimize it, but
I'm not certain offhand.

---

So that all worked. I'm able to get ~70ms for a small project. On
a large project it took a few seconds. Not completely awful
comparatively.

I'll continue considering ways to improve dep resolution. My initial thought
is: This isn't always necessary. You have some of the information to
figure out if dependencies actually changed. (You don't have `:import`
information...)

However, I've realized a pretty massive hole in this. If they add a new
dependency on a non-project namespace (say `clojure.data`), I need to
load that namespace _before_ loading their namespace. This should be do-able
since I have all the information I need in the tracker...

The tracker doesn't do imports, because in Javaland, imports are
nothing more than fancy aliases. In JSland, they still need to be downloaded.
Since I've opted to go around goog.require, I need to handle _all_
dependencies myself.

Blerg. This sucks.

Time to consider if I can go through goog.require yet again....
