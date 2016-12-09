Okay, so my thought at this point is that we want to speed up the refresh
process as much as possible. By far the slowest part of the process is the
rebuilding. The most viable way I see to do this is to build in the background
and then allow refreshes when the building is done. Coordinating that will
be a pain, but the benefits might be pretty big. If we do that, we can also
add a feature where we rebuild automatically. You should also be able to turn
that on and off.

The main roadblock here is lifecycle management of the builder thread. Ideally
it would end the second the REPL is shut down.


One way to manage the lifecycle:
  * Start off by doing the initial build (the slowest part) in a separate thread
    once `(init)` is called.
    * This gives them the fastest turnaround for their first (refresh), which, again, would be the slowest (refresh)
  * Create an *optional* `(watch)` command.
    * This kicks off a watch/build/refresh loop in the background
    * There is a `(stop-watch)` command that stops the loop
    * Make sure it runs in an external executor that can be accessed from outside the repl
      * Make sure it runs in a deamon thread in case someone's running the repl
        from a script (like the docs say to).
    * Create a `(ti/stop-watch)` fn that you can use to shut down the loop after the repl is shut down
      * This is for when someone forgets to call `(stop-watch)` inside the repl
        and the loop won't stop. We'll still try and detect when the repl is shut
        down from inside the loop, but I'm not confident we can do that reliably.
        So this leaves an escape hatch to clean up the process.
