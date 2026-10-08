# Bounded Conway acquisition follower

`lab.BoundedChainFollower` composes the existing `ConnectionSession`,
`BlockFetchSession`, original-byte header/body comparison, and `BlockSource` store
adapter. It acquires a small candidate branch; it does **not** validate signatures,
leadership, chain selection, transactions, or a ledger transition. Its checkpoint
must never be treated as an adopted or validated ledger tip.

## API and resume

`checked(concreteAnchor, originals)` reconstructs an immutable checkpoint by checking
every original header envelope and raw block, including exact original header bytes,
derived point, parent, increasing slot, body size/hash, and successive block numbers.
The first block number cannot be compared with the anchor, which contains only a
slot and hash. The anchor is a caller assertion, not an authenticated checkpoint.
Origin and windows over eight blocks reject. No serializer or new persistence format
is introduced: callers retain the anchor and original pairs and explicitly recheck
them before resume.

`resource[F: Async](checkpoint, peerResource, policy)` returns a follower with `run`
and a readable `checkpoint`. The peer resource may be scripted or supplied by
`sessions(connectionResource, networkMagic)`; for TCP, pass the existing
`AsyncTcpTransport.resource[F](peer, checkedTcpLimits)` factory. Every acquisition
of this connection resource must return a fresh owned transport. ChainSync and each
BlockFetch use distinct connections and single protocol owners.

Each session offers checked points newest first, then the anchor. A returned point
must be in that set. Rollback trims only the retained acquisition branch; unknown
or older points fail closed. Reconnect offers the latest checked state, so an
intersection on an earlier retained point discards the abandoned suffix. Duplicates
and unannounced fork changes reject before a fetch. `AwaitReply` retains server
agency; the next receive does not send another request.

Every announcement requests exactly that point as both BlockFetch endpoints. One
block and `BatchDone`, followed by the session's drained-buffer check and successful
finish, are required before byte comparison and checkpoint advancement. EOF, an
extra block, trailing buffered data, identity mismatch or malformed body never
commits that block. An arbitrary future unread suffix is not observed after closing
the single-request connection. Ordinary peer/session failures retry only within the
configured reconnect allowance; structural follower failures stop immediately.

`run` returns `Outcome(checkpoint, reason, events, admittedBytes)`. `targetReached`
means the requested number of acquired blocks is retained; it is not a validation
claim. Exhausted peer retries return `peerFailure`. Event/input/time exhaustion and
structural errors return incomplete checked progress. Cancellation propagates and
releases owned sessions; `checkpoint` remains available on the follower for explicit
resume. Concurrent runs are serialized with a cancelable semaphore. The duration
budget starts after admission; it does not include waiting for another run.

## Storage and ownership

`checkpoint.source[F]` creates an immutable replay source for existing `Fetch.run`
and `NioSegmentStore`. Its identity hashes the anchor and the ordered digests of both
original envelopes and raw blocks. Use that identity when opening the existing
store and a matching selection beginning at the source predecessor. A changed
branch changes source identity and requires its own store. Never truncate or
rewrite a live append-only store in response to a peer rollback. This adapter works
for partial checked checkpoints too; source exhaustion does not claim completion.
The source reports the immutable raw block total, not cumulative network traffic.

## Bounds

Defaults: four retained blocks, 64 ChainSync events, two reconnects, 32 MiB admitted
payload, and 60 seconds per admitted invocation. Hard policy ceilings: eight blocks,
256 events, four reconnects, 64 MiB payload, and 120 seconds. Each header envelope
is at most 65,535 bytes and each raw block at most 1 MiB. Header decoding further
limits the embedded original header to 4,096 bytes. Retained originals are at most
eight pairs; one pending fetch and bounded decoder intermediates are additional.
These are object bounds, not a claim about total JVM heap.

Event/payload/reconnect budgets are shared across attempts in one run, and reset for
an explicit subsequent run. `admittedBytes` counts returned header envelopes and
successfully returned block payloads, including comparisons that later reject. It
does not count mux/CBOR framing or partial/failed fetch payloads. The existing
BlockFetch session independently caps each fetch at 5 MiB wire ingress and bounds
framing/decoder memory. Session handshake and agency deadlines are five seconds;
each fetch has a 15-second whole-request deadline. The outer run deadline also
covers connection acquisition and finalizers, subject to Cats Effect cancelable
resource semantics. There is no unbounded history, pipelining, background follower,
or public peer discovery.

## Verification and scope

The public synthetic suite covers multi-block acquisition and existing store
composition; fork rollback; disconnect/reintersection; explicit resume; duplicate,
stale, mismatched and truncated originals; event/input/time limits; cancellation;
and actual scripted-byte session completion, EOF, extra blocks and buffered suffixes.
The generated block signatures are placeholders and deliberately make no signature
or ledger-validity claim. The truncation loop checks multiple deterministic cuts.

Tests run on dev-host in `cardano-public-v023-check:local`, with network disabled,
2 CPUs, 2 GiB container memory, 256 PIDs, `ActiveProcessorCount=2`, and a 1,200 MiB
JVM heap. Dependencies are copied into this worktree's ignored `.cache`, not written
through the main checkout's cache. No host installation or public network fetch is
needed. The initial increment was unit-tested only. Subsequent approved
[live adapter acceptance](bounded-follower-live.md) captured four blocks with an
injected local disconnect and retained-tip reintersection; its narrower claims
and original source commit are recorded separately.

On 2026-10-08, `scalafmtCheckAll` and **78 tests** passed: 17 new follower tests,
four existing reference-capture tests, 52 network-runtime tests, and five fetcher
tests. This is an affected regression count, not a full public-suite claim. The
new wire script initially used a definite intersection list; correcting it to the
existing encoder's indefinite-list output made the positive session case pass.
Independent read-only review found no blocking integrity issue; its event-counter
finding was fixed, and its admission-time/byte-accounting caveats are documented
above. No `Main.scala`, shared launcher, or existing command has been changed.

For parent integration, construct the peer once as a resource factory (not an
already-acquired connection), then call the generic follower. For example, inside
the existing IO boundary with checked TCP limits and a concrete anchor:

```scala
val initial = BoundedChainFollower.checked(anchor, Vector.empty)
val peers = BoundedChainFollower.sessions[IO](
  AsyncTcpTransport.resource[IO](peer, limits), magic
)
IO.fromEither(initial.left.map(new IllegalArgumentException(_))).flatMap { start =>
  BoundedChainFollower.resource[IO](start, peers,
    BoundedChainFollower.Policy(target = 4)).use(_.run)
}
```

Check the returned reason before reporting completion; preserve the outcome's
anchor and original pairs for later explicit `checked` resume. The existing
single-block capture and transfer entry points remain available unchanged while
the parent decides the live-cluster integration surface.
