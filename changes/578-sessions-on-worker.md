### Removed (follower, #578)
- **A peer session no longer adopts blocks itself.** The follower's adoption inside a session, which only tests
  used, is removed: every block a session delivers is adopted by the node's selection worker, as in the running
  node. A session started without the worker stops the node as its own wiring fault (`NoSelectionWorker`). The
  follower, storage and session-failure suites run their sessions on the worker, over forged blocks where they
  used synthetic headers; tests that the worker suites already covered are removed.
