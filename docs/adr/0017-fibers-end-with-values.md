# 0017. A fiber ends with a value; a typed error does not cross a fiber as an exception

- **Status**: Accepted
- **Date**: 2026-10-17
- **Issue**: [#132](https://github.com/KD5UJC-XYZ/scarab/issues/132), [#154](https://github.com/KD5UJC-XYZ/scarab/issues/154)

## Context

A cats-mtl `Raise` that comes from `Handle.allow` is valid in the fibers of its scope. Raised in any
other fiber it is an exception, `cats.mtl.Handle$Submarine`, that no scope catches: the fiber dies
unobserved and whatever it held stays held. This is what stopped the mainnet follower in issue 130. The
fix of that issue carried a `PeerError` across the fibers of a connection inside an exception we
defined (`PeerFailure`), a deviation from "no `throw` for domain errors" that the owner accepted as a
bridge, not as the design.

The cause was that a `MuxChannel` and the clients over it held the `Raise` of whoever opened the
connection, so the fibers that used them (the keep-alive, TxSubmission2, the four servers, the
pipeline of a session) raised through a scope that was not theirs.

The owner's decision (2026-10-17) is the Haskell way. Inside a fiber the typed error becomes a value
(`Either[PeerError, A]`, `runExceptT`). Across the fiber boundary only values travel (`Deferred`, a
bounded `Queue`, `Fiber.joinWith`). The owning session turns the value back into a typed raise
(`Raise[F, PeerError].fromEither`, `either throwError pure`).

An independent audit (issue 154) found the same mechanism one level up: the catch-all around a session
classified every failure that was not a `PeerFailure` as the peer being unavailable, so a block our
rules refused, a storage failure and a bug in the node were all retried like a lost connection.

## Decision

1. **A capability is given where it is used.** `MuxChannel.send` and `recv`, the clients' operations
   (`ChainSyncClient`, `BlockFetchClient`, `KeepAliveClient.ping`) and `Peer.intersect`, `next` and
   `fetch` take the `Raise` of their caller as a `using` clause. A channel holds no error capability, so a
   fiber cannot raise through the scope of the fiber that made it.
2. **A fiber runs its body in an error scope of its own and finishes with an `Either`.** `Fibers.scoped`
   (network) runs `Handle.allow` and returns the error as a value, wrapping an exception that is not ours
   (a library, a bug) into a case the caller chose. `Fault.fiber` (node) is the same for the fibers of a
   session's pipeline. The fiber hands the value to its owner through a `Deferred`, the queue of batches or
   `Mux.closed`; the owner raises it again in its own fiber (`Fibers.land`, `Fault.land`). `PeerFailure`,
   `Peer.carrying`, `Peer.carryingNetwork` and `Peer.landed` are gone.
3. **A fiber that the owner joins at once may use the owner's capabilities** (`race`, `parSequence`,
   `Compute.within`, an `evalOn(...).start` that is joined): the exception is rethrown in the owner's
   fiber, inside the scope. A fiber that outlives the call (`background`, `supervise`, a `start` that is
   kept) may not. Where such a fiber is a child of a session and meets a peer error, it is wrapped in
   `Fault.fiber`.
4. **The close of a connection is typed.** `Mux.closed` is a `NetworkError`, and `abort` takes one.
   Whichever fiber meets the close raises the original cause, so an ingress overrun is classified as the
   peer's protocol violation wherever it is seen.
5. **A session ends with a closed set of outcomes.** `SessionEnd` is `Returned | NoMoreIntersection |
   RolledBackPastIntersection(point) | Failed(PeerError) | Rejected(ValidationError) |
   Unavailable(EvaluationUnavailable) | Storage(StorageError) | Internal(NodeError) | Defect(Throwable) |
   Cancelled | Stopped(clean: Boolean)`. Each session gives rules, stores and the follower its own caller
   capability. A retained whole-run `Handle` also catches failures from resources whose scope owns their
   lifetime. The typed failure is placed by `SessionEnd.of`, which has no default case. An exception that is
   not ours is `Defect`, never an unavailable peer. `FailurePolicy.disposition` gives each a policy: a peer
   that broke the protocol, sent an invalid header or an invalid block is forgotten; a failed connection is
   tried again after a delay; storage, an internal failure, a defect and implementation unavailability stop
   the node with exit codes 5, 6, 7 and 8, as the reference stops its node for a storage failure
   (`consensusRethrowPolicy`, `ShutdownNode`).
   **An exception is placed by where it came from, not by its label.** A decoder that throws on a
   peer's bytes is not the node's defect. `MuxChannel.recv` runs the decoder once under
   `Either.catchNonFatal` and raises `NetworkError.DecoderDefect(protocol, cause)` through its caller's
   `Raise`, in the fiber that called it, so no fiber is made and decision 1 holds. The decode sites in
   the node (`Header.fromNtN`, both `BlockPool.Delivered.checked` calls) do the same through
   `Peer.decodingInput` and raise `PeerError.InputDefect(site, cause)`. `Peer.networkFault` maps the
   first to the second, and `FailurePolicy.classify` makes it `Transient`: the connection closes, the
   peer is charged a failure with the error delay and the node goes on. The cause is kept, as for every
   defect. It is not `Misbehaved`: a decoder that throws instead of refusing may be our own bug on valid
   data, and forgetting the peer would then drop every honest peer that sends it. This is the reference's
   policy: an exception that no rethrow policy names is `ShutdownPeer`, the unit of the monoid, and
   `ourBug` and `theyBuggyOrEvil` are both `ShutdownPeer` (ouroboros-network `2fb69829`
   `RethrowPolicy.hs` 61 to 70 and 99 to 103, with `ConnectionHandler.hs` 294 to 307, and
   ouroboros-consensus `82ecba329` `Node/RethrowPolicy.hs` 131 to 139); only storage is `ShutdownNode`
   (55 to 90). Making every `NetworkError.Defect` the peer's would undo issue 154, because the node's
   own code produces `Defect` too (a fiber scope's wrapped exception, the rules, the storage). So
   `NetworkError.Defect`, `fromThrowable`, `SessionEnd.Defect` (rules and storage) and `Fault.Defect`
   still reach `Fatal.Defect`, and the storage, internal and unavailable cases still stop the node. A
   peer must not be able to stop the whole process by what it sends, which is the reasoning of the
   process-wide denial of service below.
6. **The tip is published after the blocks are stored.** `Follower.commit` persists, then replaces the
   chain, as the reference writes a block to the VolatileDB before chain selection moves the tip. A
   failure of the storage leaves the published chain as it was.
7. **The ingress queues of the multiplexer are counted in bytes and never wait.** Each mini-protocol has
   the limit of the reference (`IngressLimits`); a peer with more unread bytes queued than the limit
   allows ends the connection with `IngressLimit`, a protocol violation (the peer is forgotten). What
   holds a fast peer back is the protocol and the session's pipeline (`Peer.Depth` batches ahead of the
   one being applied), and the queue of the writer, which is bounded in messages.

## Consequences

- The rule is in `AGENTS.md`: a fiber never raises through a capability it did not create.
  `FailsOnUncaught` stays as the guard in the suites that start fibers: no unobserved fiber failure may
  occur.
- Every `Peer` implementation takes the `Raise` as a `using` clause; the tests that script peers say so.
- A block that another session delivered through the shared `BlockPool` and our rules refuse is the
  deliverer's fault, not the fault of the session that took it: the pool remembers who delivered each
  block, the session that took it ends as `Failed(Diverged)` (tried again, it fetches the block itself),
  and the deliverer is told (`BlockPool.Claims.blamed`) and ends as `Failed(InvalidBlock)`.
- G7-F1 passes rule, store, persistence and migration capabilities per operation. A session allocates
  a fresh node-error scope and gives it to the follower; the whole-run handle remains a lifetime
  backstop. HTTP, submission and relay callbacks allocate their own storage scopes, and streams
  carry typed results from a producer whose scope lasts through consumption. Their fatal reporter
  stops the node; failed callbacks wait cancelably for shutdown, without blaming the requesting peer.
  Local state queries instead preserve #158's resource-scoped pinned backing view: acquisition
  shares the follower's publication mutex, and backend failures remain values until the acquired
  view and sibling protocols have been released. `Main.landLocalBackend` then reports storage
  failures as `Fatal.Storage`. An incoherent point/backing generation retains
  `NetworkError.StateAcquireFailure` through cleanup; the listener wraps it in
  `ChainError.StateAcquire` and reports `Fatal.Internal` (exit 6), without client blame or retry.
  G7-F2 gives periodic/shutdown snapshots their own storage-error scopes, with caller-scoped
  capabilities along the snapshot call path and `SnapshotFailure` values at the event boundary.
  G7-F4 gives TCP/Unix handlers their own scopes and a required typed failure reporter; unexpected
  handler defects reach `Fatal.Defect`. See the [as-built contracts](../design/domain-errors.md#snapshot-and-listener-scopes-g7-f2g7-f4).
  G7-F3 gives forging and reload terminal failure values and prevents signing after a key durability
  failure. G7-F5 retains bounded source attribution after a cache-producing session ends, with a
  governor observation that can distrust a disconnected or reconnected source. See the
  [as-built contracts](../design/domain-errors.md#caller-capabilities-forging-and-cached-block-attribution-g7-f1f3f5)
  for the bounds and policy differences from the reference.

- An evaluator or decoder implementation limit is `Unavailable`, carrying the closed ledger failure,
  not a consensus rejection. It stops the node with exit 8, bypassing peer blame and reconnect policy.
  Expected transport `IOException`s remain connection failures; an unexpected network exception of
  unknown origin preserves its cause as `NetworkError.Defect` until the node's `Fatal.Defect` boundary
  (exit 7), and one thrown by a decoder on a peer's bytes is `NetworkError.DecoderDefect`, a failure of
  that connection (decision 5). The bearer's reader fiber is not yet covered: an exception thrown while
  it reads or routes remains `Defect` and fatal, a gap recorded here until the reader can name its input.
  A fatal outcome marks and releases the connection before reporting the fatal event; it produces no
  `Lost` observation or `Transient` projection. Exits 5 (storage) and 6 (internal) are unchanged.
- **Recovery addendum (#152).** `Follower` restarts intersection when its candidate leaves the
  retained window. `Peer.intersect` drains outstanding replies and prefetch work before issuing
  `FindIntersect` on the same connection; typed failures and original defects still land in the
  caller's scope. This follows the reference's `findIntersectionTop` restart. The
  [as-built recovery contract](../design/network-limits.md#same-connection-chainsync-recovery-152)
  records the pinned upstream source, terminal cases and regression controls. This recovery is
  distinct from error ownership; the peercut gate asserts the closed governor policy.

- External local submissions use a closed `Response` and `End` value. An implementation limit has no
  `ApplyTxErr`/predicate-failure encoding: only that submission connection closes, and a fresh client
  can still use the listener. The adapter keeps `LedgerFailure.Unavailable` typed until the transport
  boundary; the server hands its service outcome to its owner as a value, without a captured `Raise`.
  Stopping the whole node for an external request would permit a process-wide denial of service. This
  differs from block adoption, which must stop when no consensus verdict can be obtained.
  Mempool add and revalidation exclude typed `Unavailable` from recently refused IDs; a refreshed
  caller-provided `Ask[F, MempoolEnv]` can supply recovered decoder/evaluator capabilities and retry.
- `FatalReporter` atomically claims the first terminal outcome. It logs before signalling shutdown,
  suppresses duplicate observations of a failing connection, and guarantees the shutdown signal even
  if logging fails or is cancelled.

Evaluator unavailability during stored-block replay is reported by the same typed fatal event and
exit code 8 as live adoption. No ledger verdict exists in either path. A regression stops both
phase-1 and phase-2 injected replay outages, then reopens and replays the unchanged stored chain
with healthy rules. Other startup error policies are unchanged.

## Amendment (2026-10-23): no verdict, for the implementation or for one block

An evaluation that supplies no verdict is not one thing. Before this amendment every
`LedgerFailure.Unavailable` stopped the node with exit 8, and the follower first recorded the block
as invalid. Stopping is right for a missing implementation, which no input can work around. For a
defect that one block's own scripts trigger in the evaluator (Scalus throwing on bytes it was given)
it is the wrong policy: the defect says nothing about the other blocks, a restart does not change it,
and recording the block as invalid is a verdict the implementation does not have.

`BackendFailure.reach` now says whose failure it is, by an exhaustive match with no default:

- **Implementation** — a linkage error (a native or a class that is missing, an initialiser that
  failed), an unknown builtin, a virtual machine error other than a stack overflow, wherever it is
  in the chain of causes (Scalus keeps a builtin's exception in `BuiltinError.cause`), and the
  builtins and natives this platform lacks (`BuiltinUnavailable`, `NoResult`; before Scalus 1.4.0
  also `BinaryDstUnavailable` and `NativeHashToGroupUnavailable`, removed with the raw-DST paths by
  [ADR 0020](0020-plutus-native-crypto-through-scalus-crypto-jni.md)). Nothing can be trusted to get
  a verdict: the node stops with exit 8, as before.
- **Input** — any other exception the evaluator or decoder throws, a stack overflow, a budget beyond
  64 bits. Only the block or transaction that carries the input lacks a verdict.

The script-failure allowlist (`ScalusEvaluator.referenceFailure`) is unchanged: neither reach is ever
a script failure, and nothing is added to it. The three outcomes of an evaluation are therefore a
verdict (passed, or failed as the reference fails), no verdict for this input, and no verdict at all.

For a block, no verdict for its input is `SessionEnd.Held` and `Disposition.Hold`, an outcome and a
disposition added to those of decision 5 (`SessionEnd.of` still has no default case):

- the block is not adopted and **not recorded as invalid** (`InvalidBlocks`), and nobody is punished
  or blamed for it (`BlockPool.Claims` is not consulted). The follower re-raises any
  `LedgerFailure.Unavailable` instead of treating it as a refusal, so an implementation limit is not
  recorded as invalid either;
- the session ends and the peer is tried again after the delay of a lost connection
  (`PeerError.Unavailability.SessionEnded`, `Transient`), never forgotten;
- the node goes on. The shared selection runner (`BlockSelection`) leaves the chain as it is and
  takes the next admission;
- the follower emits `FollowerEvent.Held` (log `Chain.BlockHeld`, metric
  `scarab_blocks_held_total`), and the stall watchdog names the held block when the stall it causes
  begins (`Chain.StalledBehind`). The node stays behind that block until a build that decides it is
  installed; nothing about the block is remembered that would keep that build from adopting it.

Other paths: block production leaves out a mempool transaction that triggers such a defect
(`BlockBuilder.select`) and treats a held block of its own like one its rules refuse (its
transactions leave the pool, an empty block is tried); local submission and the mempool keep the
existing behaviour for every `Unavailable` (the submission connection closes, the transaction is
not remembered as refused). Replay of the stored chain stops with exit 8 for both reaches: a stored
block was adopted by an earlier run, the chain cannot be rebuilt past it, and holding it would only
hide that.

Tests: the injection suite places `NullPointerException`, `ClassCastException`, `MatchError`, a
borer `Overflow`, `StackOverflowError` and `RuntimeException` at the decoder and at the machine
(Input) with linkage, initialiser, out-of-memory and unknown-builtin controls (Implementation); the
`MachineError` subclasses of the pinned Scalus jar are enumerated and pinned; `SessionFailureSuite`
holds a block without an invalid record, blame or stop; `StallWatchdogSuite` names it.

**Addendum (#393): a crypto provider defect is not held.** A verifier that throws or breaks its
contract (Ed25519, VRF, KES, certificate signature) gives no verdict, and the reference treats it as
the exception it is. On the block path it ends the linked add-block runner and stops the node
(ouroboros-consensus 82ecba32 `Storage/ChainDB/Impl/Background.hs` 107-108 and 142): the rules
return `ProviderDefect`, `SessionEnd.of` places it as `SessionEnd.Unverifiable`, and the
disposition is `StopNode(Fatal.Unverifiable)` (exit 7), with no `InvalidBlocks` entry and no peer
blamed. On the header-first path it ends that peer's session and nothing else
(`Node/RethrowPolicy.hs` 36-48 and 128-140, `ShutdownPeer`): header-first validation raises it as
`PeerError.Unverifiable` through the caller's `Raise`, classified `Transient`. The hold of this
amendment is for the evaluator only. The as-built contract and the reference correspondence,
including the mempool and local submission, are in [domain errors](../design/domain-errors.md).
