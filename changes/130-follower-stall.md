### Fixed (#130: the follower stalls after a peer reconnect)
- **A claim in the shared `BlockPool` ends with its session.** A mainnet follower with two peers stopped extending its
  chain after one peer's ChainSync timed out in `NextCanAwait` and the node reconnected: the process stayed up, idle,
  and logged nothing. The timed-out session's prefetch fiber was cancelled while its claim on a block range was open;
  the claim was only released on an error, not on a cancellation, so the other peer's session waited for it for ever
  inside the follower's adoption lock. Claims are now made through `BlockPool.session`, a resource that releases what
  the session still holds, however it ends; a failure or a cancellation of a range releases all of the range, not
  only the run in flight. `FollowerStallSuite` (two peers on in-memory connections, one of them stalls in
  `NextCanAwait` with a block range in flight) reproduces the stall before the change and passes after.
- **No fiber of a connection raises through a scope that is not its own.** The connection's fibers (multiplexer,
  KeepAlive, TxSubmission2, the pipeline's producer) now raise `PeerFailure`s, ordinary exceptions that cross fibers
  (`Peer.carrying`, `Peer.carryingNetwork`); the session raises them again through its `Handle[PeerError]` at
  `intersect`, `next` and `fetch` (`Peer.landed`). The KeepAlive pinger ends the connection on any failure (a connection
  lost under a waiting ping ended its fiber with a `cats.mtl.Handle$Submarine`). The inbound governor does the same.
  `FailsOnUncaught` runs a suite on a runtime whose failure reporter fails the test; `BackgroundFibersSuite` shows the
  pinger and that the harness sees a Submarine. A deviation from "no `throw` for domain errors" (ADR 0001 and the house
  rules): a `Raise` is handed to a channel when the connection is made and cannot know which fiber will use it, so the
  error travels as a value in an exception and is a typed raise again in the one fiber that owns the scope.
- **A stall watchdog** (`StallWatchdog`, `--stall-minutes N`, default 10, 0 off): no block extended for N minutes while
  a peer announced a longer chain logs one `stalled` event (`Chain.Stalled`, Warning: `idleSeconds`, `ourBlock`,
  `peerBlock`), counts `scarab_follower_stalls_total` and holds `scarab_follower_stalled` at 1 until the next block.
  The sessions report the tips their peers announce (`Peer.Observation.Announced`).
- **`--stall-exit-after <Ns|Nm|Nh>`** (off by default): a stall that lasts this long (longer than `--stall-minutes`,
  a peer's tip ahead, counted only while following) logs `stallExit`, closes down like a signal (shutdown snapshot)
  and exits with code 4, so a restarting supervisor resumes from the newest snapshot. The decision is the pure
  `StallWatchdog.State.checked` (`StallWatchdogSuite`).
- **Devnet `peercut`**: two upstream peers, one slow (`scripts/devnet/lagproxy.py`) and then silent mid-sync; the node
  must reach the reference tip, log the lost peer as a typed reconnect, and report no `Submarine`, no stall and no error.
