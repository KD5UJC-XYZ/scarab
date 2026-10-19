# Deterministic adversarial schedules

- **Status**: As built for #78 (2026-10-18)
- **Scope**: bounded node-to-node schedule tests; they are regression evidence, not a proof of the consensus protocol

`node/src/test/scala/xyz/kd5ujc/scarab/node/AdversarialScheduleSuite.scala` runs sixteen fixed
`TestControl` seeds against three independently configured `Follower`, `PeerNetwork` and
`BlockPool` runtimes. Each runtime has its own volatile chain and `ChainServer`; blocks move through
the production ChainSync and BlockFetch servers and clients over `InMemoryPeers` and real in-memory
bearers. The fixture uses signed Praos blocks accepted by `MirrorRules`, with a shared prefix and
two independently signed, bounded suffixes. The active suffix fork is no deeper than the fixture's
`securityParam` (`k`).

For each seed, one runtime's ChainSync replies are withheld for a bounded virtual delay and then
released. The suite records named observations while withheld, after release/convergence, and after
endpoint recovery. At each observation it checks a common history prefix of at least
`min(history lengths) - k`, using the fixture's `securityParam` for `k`; it also rechecks fresh tips
after recovery. A node started at genesis must grow while honest sources are available. The schedule
closes and restores one peer endpoint and checks that the real governor opens another bearer to it.
Each node uses the production `HeaderClock` wrapper with an injected `SlotClock`; the clocks differ
by 0, -1000 and -500 milliseconds. Failure output includes the seed, checkpoint, node observations
and schedule actions.

The suite asserts only these outcomes for the checked schedules: all three observed histories
converge after release and recovery; the common-prefix bound holds at each recorded checkpoint; a
node started at genesis grows while honest sources are available; peer close/recovery succeeds; the
opened in-memory bearer ends are closed by their resources; each runtime reports no `Fatal` outcomes;
no uncaught fiber failure escapes `FailsOnUncaught`; and retained completed blocks stay under
`BlockPool.Retained`. After network scopes close, `BlockPool.take` is bounded for every distinct
known fixture hash, then a fresh session must claim all of them, checking that these candidate hashes
have no orphaned pending claim while draining any completed retained value. A negative-control test
injects a typed fatal into the exact capture sink and confirms the no-fatal invariant rejects it.
The existing malformed-block network regression in `FollowerStallSuite` also runs under fixed-seed
`TestControl` and checks the exact invalid bytes, typed peer rejection, cleanup and continued progress
from the honest peer.

Transport regressions in `MuxSuite` and `MuxDeadlinesSuite` complement the node schedules with
oversized ingress, fully decoded messages exceeding their state byte limit, and slow-loris progress
against fixed deadlines over real in-memory bearers. They run as separate transport tests in `check`.

The schedules do not claim that every adversarial strategy is modeled, establish a security
theorem, or test an Antithesis run. The Cardano Foundation's
[public Antithesis assets](https://github.com/cardano-foundation/cardano-node-antithesis) are
inspectable, but this work had no verified Antithesis tenant or run access. The local harness remains
the reproducible gate.
