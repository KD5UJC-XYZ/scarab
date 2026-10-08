# Private-cluster bounded follower adapter

`lab.BoundedFollowerCapture` is a separate `IOApp` entry point. It reuses
`BoundedChainFollower.sessions`, the existing wire14 `ConnectionSession` and
`BlockFetchSession`, and `AsyncTcpTransport`. It needs no `Main.scala` or shared
launcher change. It is acquisition evidence only, never a ledger or consensus tip.

Arguments: `PORT MAGIC ANCHOR_SLOT ANCHOR_HASH FIRST_COUNT TOTAL_COUNT`.
The endpoint is fixed to IPv4 loopback by the existing reference-capture argument
parser. Counts must satisfy `1 <= FIRST_COUNT < TOTAL_COUNT <= 8`.

The parent can compile `app/compile app/runtimeClasspathFile` in the existing
isolated build container, then invoke the class directly in the same `/work` mount
layout as reference capture:

```sh
java -XX:ActiveProcessorCount=2 -Xmx1200m \
  -cp "$(cat app/target/runtime-classpath.txt)" \
  lab.BoundedFollowerCapture PORT MAGIC ANCHOR_SLOT ANCHOR_HASH 2 4
```

For the private Docker cluster, use the established reference-capture container
network namespace so loopback addresses the reference node. The parent owns the
cluster resource slot, image verification, anchor query, execution deadline,
outside-Git evidence redirection and cleanup. This adapter generates no keys and
starts no Docker containers. It has not been run live in this packet.

## Exercise and evidence

1. Capture `FIRST_COUNT` checked original header/block pairs. This phase permits
   no reconnect and has a 30-second active-run deadline.
2. Reconstruct the checkpoint using `checked(anchor, originals)`. Resume never
   accepts a point alone in place of original-byte checks.
3. Open a new peer and offer the checked candidate points. After the intersection
   response, inject one local exception before requesting the next header.
4. The follower releases that connection, opens another, and offers its retained
   checked points again. If the first intersection rolled back, the second offer
   reflects that trimmed acquisition state. Capture up to `TOTAL_COUNT` with one
   reconnect allowance and a 30-second active-run deadline.

The whole generic `capture[F: Async]` operation has a 65-second deadline. The
follower's event, payload, object, agency and per-fetch limits still apply separately
to each phase. Both outcomes are retained for evidence, at most 15 original pairs
between the initial and resumed branches. The phase-two intersection log has at
most two entries, each at most nine points. Original evidence is rendered as bounded
hex JSON lines; no file writer or persistence format is introduced.

The injected exception is **local orchestration**, not a reference-node crash or a
mid-packet TCP failure. It proves the reconnection path and retained-state offers
when exercised live; unit tests alone prove only scripted behavior. Partial-batch
failure remains covered by the follower's separate scripted transport suite.

Records contain the anchor, each phase's original header envelope/block hex, the
resume intersection offers and selections tagged by attempt, outcome reasons,
event/payload counters, and an acquisition-only summary. Selections make a fallback
to the anchor visible rather than mistaking reacquisition for retained progress.
`initialPrefixPresentAtEnd` compares actual original pairs; it does not prove that
the prefix was never dropped and reacquired. Success requires both
target outcomes, the injected failure, and two resume intersection attempts. Exit
code 2 indicates invalid input, exceptions, incomplete acquisition, or an incomplete
reconnect exercise. Original bytes may repeat between phases intentionally; this
allows each phase to be rechecked independently against the recorded anchor.

## Unit evidence

`BoundedFollowerCaptureSuite` exercises successful two-phase capture, exact retained
originals and identical resume offers, all three peer-resource releases, incomplete
first-phase short circuit, preallocation range rejection and CLI limits. Its
synthetic signatures make no validity claim. Tests run offline with the parent's
authorized 2 CPU / 2 GiB Docker limit and the isolated worktree cache.

Final adapter verification passed five tests and `scalafmtCheckAll`; the prior
combined adapter/follower run passed all four then-current adapter tests plus the
17 follower tests. The fifth regression specifically verifies selected anchor
fallback and the resulting trimmed candidate offer. Independent read-only review
found no blocking defect; its evidence findings were addressed with selected-point
records, reasons and counters. No live reference result is claimed.

JUnit receipt: `app/target/test-reports/TEST-lab.BoundedFollowerCaptureSuite.xml`.
The underlying follower receipt remains
`app/target/test-reports/TEST-lab.BoundedChainFollowerSuite.xml`. These generated
files are ignored and not committed.
