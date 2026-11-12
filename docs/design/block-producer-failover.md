# Block-producer failover (research)

**Status**: Proposal (research). Nothing here is implemented or approved. The first stage (S0) mirrors the
reference and follows the normal process; every later stage adds behaviour the Haskell node does not have
and therefore needs the owner's explicit approval first ([ADR 0001](../adr/0001-haskell-node-as-reference.md)).
Stage S5 would also amend [ADR 0024](../adr/0024-adopt-the-upstream-kes-agent.md), which decided against
remote signing. No issue is filed yet.

## The question

A stake pool is elected for a handful of slots per epoch. If its block producer is down, slow or
misconfigured at one of those slots, the block is gone; nothing makes it up later. Operators therefore
want a second producer that takes over automatically. Today that means scripts around SIGHUP, which can
leave two producers forging at once, and copies of the hot keys on more machines.

This document answers four things: what operators have run into, what the protocol actually does when a
pool signs twice, whether automatic failover can be made safe, and a staged design built from patterns
with a long record elsewhere (Raft-replicated compare-and-set, fencing tokens, slashing-protection
journals, doppelganger detection, threshold signing).

## What operators run into today

These are operator reports from the Cardano forum and community guides, plus the reference node's own
documentation. The forum reports are anecdotal; they are listed because they recur, not as facts about
the protocol (the protocol facts are in the next section, from source).

| Problem | What happens | Source |
|---|---|---|
| A cold standby is too slow | A producer started from scratch must replay or download its ledger state before it can forge. Hours of downtime around a hardware failure or upgrade is common. | forum threads on standby producers ([1], [2]) |
| Firewalling the standby no longer works | Older setups kept a second producer running and blocked it at the firewall. Under P2P, relays reuse the producer's inbound connection, so a firewall rule no longer reliably isolates it. The reference added *dynamic block forging* for this reason. | cardano-node wiki, [dynamic block forging][dbf] |
| Manual or scripted promotion | The supported mechanism is to start the spare with `--start-as-non-producing-node`, then copy the credentials into place and send SIGHUP; removing the files and sending SIGHUP stops forging. Heartbeat scripts automate this ([gist][gist]). Nothing stops a script from promoting the spare while the primary is still alive but unreachable. | [dbf], [1], [3] |
| Duplicate blocks | Two live producers with the same keys both forge in the pool's slots. Each has its own mempool, so the two blocks differ; at most one survives, and the pool is publicly seen to have equivocated. Whether the protocol punishes this is a recurring, unresolved question on the forum (it does not today; see below). | [4], [5] |
| Operational-certificate counter mistakes | Since Babbage a header's counter may be at most one above the chain's. Many pools issued certificates with the wrong counter around the Vasil hard fork and stopped forging until they noticed (`CounterOverIncrementedOCERT`, `kes-period-info`). | [6], [7] |
| Expired KES keys | A KES key lasts 62 periods of 129,600 slots (about 93 days on mainnet). A missed rotation stops forging silently until someone looks. | [7], [8] |
| Overrun slots | A loaded host that finishes a slot's work late does not forge that slot. Scarab measured about one leader slot in a hundred lost this way at 1 CPU on preprod ([block forging](../compatibility/block-forging.md)). | Scarab evidence |
| Correlated software failure | On 2025-11-21 a malformed delegation transaction split mainnet between node versions for most of a day; pools forged on both forks until operators upgraded. A cluster whose members all run one build fails together. | [Intersect incident report][nov25] |

Two things stand out. First, most lost blocks are not caused by a dead machine; they come from
configuration (counters, KES expiry) and from load (overruns, propagation). Second, the failover that
exists is a manual protocol run by scripts, with no fencing, so "automatic" today means "can double-forge".

## What the protocol does when a pool signs twice

From the reference sources Scarab mirrors:

- **Header validity.** A header's operational-certificate counter must be the chain's current counter for
  the pool or exactly one above it (Praos `currentIssueNo`; Scarab `OCertRules.checkCounter` with
  `bounded = true`, refusing `n < m` and `n > m + 1`).
- **Chain selection.** Chains of equal length are compared by their tips. When both tips were issued by the
  same pool *in the same slot*, the higher counter wins; otherwise the lower VRF output wins (restricted to
  tips at most 5 slots apart from Conway); anything else keeps the current chain
  (ouroboros-consensus 4.2.0.x `Protocol/Praos/Common.hs` lines 125-168, as cited by
  `consensus/.../ChainSelection.scala`).
- **Two blocks from one pool in one slot have the same VRF output.** The leader VRF is evaluated over the
  slot and the epoch nonce with the pool's VRF key; the block's contents do not enter it. So the VRF
  tiebreak cannot separate them, and with equal counters nothing does: each node keeps whichever it saw
  first, and the network is split until the next block lands on one of them.
- **There is no slashing in Praos.** Duplicates cost the pool nothing beyond the loser being orphaned,
  wasted diffusion, a short fork for everyone, and a public, attributable record of equivocation.
- **Leios raises the price.** In the Leios design (CIP, summarised in [leios.md](../leios.md)) a node votes
  for an endorser block only if it saw no equivocating header for that slot. A pool that double-forges
  under Leios loses the certification of its endorser block. Equivocation rules for Peras votes are not
  settled in this research; any protocol that adds signed messages per pool adds ways to equivocate.
- **The counter is a fencing token the network enforces.** A header with counter `n + 1` is accepted, wins
  every same-slot tie against counter `n`, and once on the chain makes counter `n` invalid. This is how a
  compromised hot key is revoked (ouroboros-consensus PR [#1610][pr1610], an explanation of issue numbers).
  It needs the cold key, which must stay offline, so it is a break-glass tool, not an automatic one. The
  same document warns that incrementing more than once inside a stability window can be undone by a short
  fork and leave the pool unable to forge for a while.

**Consequence for the design.** Today a duplicate is cheap and a miss costs a block, which tempts
"forge on both and let the chain decide". That is the wrong default: it is visible equivocation, it gets
more expensive with Leios, and it needs more copies of the hot key. The design below is safety-first —
never two *different* blocks for one slot — and gets its availability from making takeover fast and
reproducible rather than from racing.

## Requirements

- **R1 Safety.** At most one distinct block per leader slot, whatever crashes, partitions or clock errors
  occur. Re-sending the identical block is allowed.
- **R2 Liveness.** The pool forges in each leader slot while a majority of the cluster and one ready
  producer are up.
- **R3 Cold key offline.** Nothing in the cluster holds or needs the cold key.
- **R4 No wire deviation.** Blocks, headers and signatures are byte-for-byte what a single reference node
  would produce; the network cannot tell a cluster from one node.
- **R5 Forward security kept.** KES evolutions are erased on every member that holds them.
- **R6 Fewest hot-key copies** needed for R2, and a path to zero full copies.
- **R7 Observable.** Every member reports whether it could forge the next leader slot, before that slot.
- **R8 Off by default.** A single node with no cluster configuration behaves exactly as today.

## Patterns this builds on

| Pattern | Where it is proven | Use here |
|---|---|---|
| Replicated compare-and-set over a consensus log (Raft) | etcd (Kubernetes, Patroni), Apache Ratis, Consul | the per-slot claim that makes R1 hold |
| Fencing tokens | Chubby sequencers; Kleppmann's fencing tokens | the claim itself is the token; the opcert counter is the network's own |
| Slashing-protection journal | Ethereum EIP-3076, TMKMS state files, Horcrux high-watermark | the signer never signs for a slot it already claimed with other contents |
| Doppelganger detection | Lighthouse, Nimbus, Teku | stop the cluster when the chain shows a block for our pool that the cluster did not claim |
| Self-fencing watchdog | Patroni, Pacemaker | a member that cannot reach a quorum stops being eligible |
| Threshold Schnorr signing | FROST, [RFC 9591][rfc9591]; Horcrux (Cosmos); Obol and SSV distributed validators (Ethereum) | optional stage: no member holds a full KES key |
| Key distribution by agent | upstream `kes-agent` with bootstrap peers | how the standby "keeps its KES key up to date" |

Two lessons from Ethereum carry over directly. Nimbus reports accidental duplicate validators as a primary
cause of slashings, and Teku states its slashing protection does nothing when the same key runs on
several nodes: a local journal is not enough, the journal must be shared. And Horcrux's own guide warns
that teams have double-signed while *migrating* into a threshold setup: the migration path needs as much
design as the steady state.

## The key observation: the schedule is known in advance

A pool can compute its own leader slots for an epoch once the epoch nonce is fixed, which happens when the
candidate nonce freezes, `randomnessStabilisationWindow` slots before the epoch begins. A small pool has
one or a few leader slots per epoch of 432,000. Failover therefore matters only around known instants,
and a cluster can prepare for each one:

- check, before each leader slot, that at least two members are ready (synced to the tip, KES key valid
  for that slot's period, counter in range, clock within bounds) and alert when not;
- schedule upgrades and restarts between leader slots;
- put the coordination round trip, which every safe scheme needs, only on leader slots, where it costs a
  few milliseconds a handful of times per epoch.

## Design

### S0 — Parity: dynamic block forging, exactly as the reference

Scarab reloads its KES key and certificate on SIGHUP, but **an absent credential file is treated as a
refused reload and the old credentials stay in force** (`node/.../forge/HotKeys.scala`,
`HotKeys.reloadFailure`: `NoSuchFileException` is an operator refusal). The reference does the opposite:

- `cardano-node` 11.1.3 `Cardano/Node/Run.hs` lines 626-635 (`updateBlockForging`): if re-reading the
  credentials fails with `FileDoesNotExistError`, it traces `DisabledBlockForging` and calls
  `setBlockForging nodeKernel []`, so the node stops forging;
- lines 248-252: `--start-as-non-producing-node` (`ncStartAsNonProducingNode`) starts a node with
  credentials configured but forging disabled until a SIGHUP finds the files.

Scarab has no `--start-as-non-producing-node`. So the community's existing runbook — move the files away,
send SIGHUP — **does not stop a Scarab primary**, and a script that then promotes a spare produces exactly
the duplicate it was written to avoid. This is a reference deviation in its own right and should be fixed
first, independently of any cluster work: mirror the flag and the absent-file rule, keep Scarab's
existing checks on a reload that does find files. It is the prerequisite for everything below, because it
gives the cluster a reference-identical way to turn forging on and off.

### S1 — The KES agent client (already designed)

[KES agent mode](kes-agent.md) (#38) is the upstream answer to "keep the standby's KES key current". An
agent holds the key in mlocked memory, evolves it, and pushes it to every connected node; backup agents
re-key each other through `agentBootstrapConnections`. KES evolution is deterministic, so a standby that
holds the same key and certificate needs nothing else synchronised to sign correctly. The cluster below
reuses this unchanged: each producer-capable member runs an agent, the agents bootstrap from one another,
and a rotation done once reaches every member.

What the agent does *not* provide is the decision of who forges. That is the cluster's job.

### S2 — The forge gate (no behaviour change)

Introduce one capability between the forger and signing:

```scala
trait ForgeGate[F[_]]:
  /** Ask for the right to sign `intent` for its slot. Returns the intent to sign: the one asked for,
    * or the one already claimed for that slot (which must then be reproduced, not replaced).
    */
  def claim(intent: ForgeIntent)(using Raise[F, GateError]): F[Claimed]
```

`ForgeIntent` holds the slot, the unsigned header body and the block body. The default interpreter grants
every claim, which is today's behaviour (R8). A second interpreter keeps a local, fsynced journal of
claims, so a node restarted inside a slot can never sign a different block for a slot it already claimed;
this is EIP-3076's single-node protection, and it is useful even without a cluster.

### S3 — A coordinated cluster: claim, then sign

**Membership.** Three voters in separate failure domains: two producer-capable members (a Scarab node, an
agent, the VRF key, the opcert) and a witness that holds no key and runs no node, or three
producer-capable members. Consensus is Raft; Scarab should not implement Raft itself. The two candidates
are an external etcd cluster (three small processes; its transactions give compare-and-set directly) and
an embedded JVM library such as Apache Ratis. This is an owner decision.

**The claim protocol.** For each leader slot `s`:

1. Every producer-capable member evaluates leadership with its own VRF key, as today.
2. The preferred forger for `s` is the Raft leader if it reported itself ready; otherwise the ready member
   with the best connectivity. This is only a preference.
3. The preferred forger builds the block at the start of `s` and commits
   `Claim(s) = ForgeIntent(s, header body, block body)` with a compare-and-set that succeeds only if no
   claim for `s` exists. It signs **only after the commit succeeds**, then diffuses the block.
4. Any other ready member waits a takeover delay `δ` into the slot. If no claim for `s` exists, it tries
   the same compare-and-set for its own intent. If a claim exists, it reads it.
5. A member that finds a claim for `s` it did not make never builds its own block for `s`. If the
   chain has not seen the block by `δ`, it **reproduces the claimed block** and diffuses it.

Step 5 works because, for a given key and message, every signature in a Praos header is deterministic:
the leader VRF proof (ECVRF draft-03 derives its nonce from the key and input) and the KES signature
(Sum6 over Ed25519, which is deterministic per RFC 8032). Given the claimed header body and the same KES
evolution, any member produces the identical header, and therefore the identical block. A primary that
crashes between claiming and diffusing therefore costs a delay of `δ`, not a block, and never a duplicate.

**Why this meets R1 without timing assumptions.** A member signs only an intent that is the committed
claim for that slot, and compare-and-set on a Raft log admits one claim per slot. Partitions, paused
processes and wrong clocks can delay a claim or lose the slot; they cannot produce a second distinct
block. A primary cut off from the majority cannot commit, so it does not sign (self-fencing falls out of
the protocol rather than from a watchdog timer). Clocks matter only for liveness and for being
on time, as they do for one node.

**Cost.** One majority commit per leader slot, between building and signing. Within one region this is a
few milliseconds; across regions, tens of milliseconds. It must be measured against the forging budget and
recorded in [performance.md](../performance.md) before the stage is accepted.

**Takeover delay.** `δ` trades a late block, which is more likely to lose a height battle, against
needless contention. Because the claim decides, a short `δ` is safe; a reasonable starting point is a few
hundred milliseconds, measured on a devnet.

**Loss of quorum.** With no majority, nobody forges. That is the price of R1. A documented break-glass
mode lets the operator run one member alone after confirming the others are down, and S6 below gives the
network-enforced fallback when a member is lost rather than down.

### S4 — Doppelganger guard and readiness

- **Doppelganger guard.** Every member already sees every header through ChainSync. A header issued by the
  pool's cold key for a slot whose hash is not the cluster's claim for that slot means some other process
  holds the keys: a forgotten old producer, or a compromise. The cluster disables forging everywhere and
  alerts. This is the Ethereum doppelganger idea adapted to sparse slots: Cardano cannot wait two epochs
  for silence, because the twin only shows itself when it forges, so the check runs continuously.
- **Readiness.** Each member publishes, ahead of each leader slot, whether it could forge it (tip lag,
  KES window, counter against the chain, clock offset, peers). The node reports it through the existing
  metrics and the `/forging` endpoint, and alerts when fewer than two members are ready.
- **Tip agreement.** Members compare their selected tips. Members on different forks, as during the
  2025-11-21 split, are reported, because a takeover then forges on another fork.
- **Joining.** A new member joins with forging disabled and becomes eligible only after it has read the
  claim journal.

### S5 — Threshold KES (optional; needs an ADR amendment)

S3 still puts a full KES key on each producer-capable member. Threshold signing removes it:

- A Sum6 KES verification key is a hash tree over 64 Ed25519 leaf keys, and a Sum6 signature is one leaf
  Ed25519 signature plus the verification keys along the path. Generate each leaf key by a 2-of-3
  distributed key generation, compute the public tree, and have the cold key sign the root in the opcert
  as usual.
- Sign each period's leaf with FROST(Ed25519, SHA-512), which RFC 9591 states verifies under RFC 8032.
  The block is indistinguishable from one signed by a single key (R4); this must be confirmed by
  verifying threshold signatures with the reference's `cardano-crypto-class` verifier in the conformance
  suite, because libsodium's checks are stricter than RFC 8032's minimum.
- Forward security holds as long as two of the three members erase each finished leaf's share.
- Each signer refuses a second distinct message for a slot, so even a buggy coordinator cannot make the
  cluster equivocate (the Horcrux design). The first FROST round (nonce commitments) is precomputed, so
  signing costs one round trip.
- FROST signatures are not deterministic, so S3's step 5 changes: the claim journal stores the final
  signature and a takeover re-sends it. Nonce reuse leaks the key share, so used commitments are journaled.
- The VRF key stays whole on producer-capable members. A leaked VRF key reveals the pool's schedule (and so
  when to attack it) but cannot forge without KES. A threshold VRF is possible but not needed first.

This conflicts with [ADR 0024](../adr/0024-adopt-the-upstream-kes-agent.md) (no remote signing, upstream
agent only): the upstream agent cannot hold key shares, so S5 needs its own key format and signer service
and an independent cryptographic review. It is the largest stage and the one with the most to gain for
security; it should not start before S3 has run in production.

### S6 — Break-glass fencing with the opcert counter (operator procedure)

If a producer is lost rather than down — stolen, compromised, or unreachable but possibly running — the
operator issues a certificate with counter `n + 1` for a fresh KES key and installs it on the cluster. The
network then prefers the cluster's blocks in any same-slot tie and, once one is on the chain, rejects the
lost machine's. A certificate for `n + 1` can be prepared with the cold key ahead of time and kept offline
as an escrow. The procedure must follow the warning in [#1610][pr1610]: one increment per stability
window.

## Failure cases

| Case | S3 outcome |
|---|---|
| Primary crashes before claiming | backup claims after `δ` and forges; block is later by `δ` |
| Primary crashes after claiming, before diffusing | backup reproduces the identical block after `δ` |
| Primary partitioned from the majority | its claim cannot commit, so it does not sign; backup forges |
| Witness or one member down | majority remains; forging continues |
| Two members down | no forging (R1 over R2); break-glass if confirmed |
| Backup is behind the tip | it is not ready and does not claim; if it is the only one, readiness alerts before the slot |
| Clock skew on one member | it may claim late or not at all; cannot cause a duplicate |
| Old producer left running with the keys | doppelganger guard stops the cluster at its first block and alerts |
| Hot key stolen | S6; under S5 a single member compromise yields no usable key |
| Members on different forks | tip-agreement alert; claim still unique per slot |
| Raft data lost on a majority | treated as a new cluster: members stay disabled until the journal is rebuilt from the chain and the operator confirms |

## Things worth knowing that were not asked

1. **Fix S0 first.** The SIGHUP gap above is a safety problem for any operator who brings an existing
   failover runbook to Scarab, cluster or not.
2. **Failover is not where most blocks are lost.** Overruns, height battles from slow propagation, and
   counter or KES mistakes cost more blocks than dead machines. Readiness checks (S4) catch the
   configuration cases; the load cases need forging-latency metrics, a garbage collector with short pauses
   for the JVM, and good relay topology. A failover feature sells better alongside "and the node tells you
   a week ahead that your KES key expires".
3. **The arithmetic is about tails.** A pool expecting `b` blocks per epoch loses about `b × T / 432,000`
   blocks to an outage of `T` seconds. With `b = 1`, a three-hour outage costs about 0.025 blocks on
   average; a weekend hardware swap (48 hours) about 0.4; with `b = 30`, three hours costs about 0.75. The
   value is in the rare long outage and in large pools — and for a small pool, one missed block can be an
   epoch with no rewards for its delegators, which is what they notice.
4. **More copies of the key is a cost.** A three-member cluster triples where the hot key lives until S5.
   The opcert counter (S6) is the recovery path, and it needs a procedure written before it is needed.
5. **Diversity cuts both ways.** Members on different node builds would have survived the 2025-11-21 split
   better, but would forge on different forks during one. Tip agreement (S4) is the guard. A Haskell node
   cannot take part in the claim protocol, so a mixed cluster could only use S0-style SIGHUP control,
   without R1's guarantee.
6. **Test it the way consensus protocols are tested.** Write the claim protocol as a TLA+ model before
   coding S3 and check R1 under crashes and partitions. Then a devnet scenario that kills members with
   `kill -9` at random points around leader slots and partitions them, asserting at most one block per
   pool per slot and counting filled leader slots, on the existing
   [deterministic adversarial schedules](adversarial-schedules.md).
7. **Migration is where Horcrux users double-signed.** Moving a running pool into a cluster, and S3 into
   S5, each need a written procedure that never has two signers enabled at once, checked on a devnet.

## Delivery

| Stage | What | Reference | Gate |
|---|---|---|---|
| S0 | `--start-as-non-producing-node`; absent credential file disables forging on SIGHUP | mirrors cardano-node 11.1.3 | level by CONTRIBUTING; devnet `kes-rotate` plus a disable/enable scenario |
| S1 | KES agent client | [kes-agent.md](kes-agent.md) | as designed there |
| S2 | `ForgeGate` capability, local claim journal | additive, approval needed | `check`; restart-inside-slot test |
| S3 | Raft claim protocol, takeover, reproduction | additive, approval needed | TLA+ model; devnet failover scenario; latency in performance.md |
| S4 | Doppelganger guard, readiness, tip agreement | additive, approval needed | devnet with a rogue producer |
| S5 | Threshold KES | amends ADR 0024 | cryptographic review; reference-verifier conformance |
| S6 | Break-glass counter procedure | operator documentation | devnet rehearsal |

## Decisions for the owner

1. Is failover in scope for Scarab at all, given that it adds behaviour the reference lacks (S2 to S5)?
2. S0: treat the absent-file difference as a reference deviation to fix now (recommended).
3. Raft: external etcd or an embedded library.
4. Minimum topology to support: two producers and a witness, or three producers.
5. Whether S5 (threshold KES, amending ADR 0024) is a goal, and when.

## Sources

Reference sources are cited inline at their pins. Community and prior-art sources:

- [1] Cardano forum, [Block Producer – Failover Approach with a BP Standby](https://forum.cardano.org/t/block-producer-failover-approach-with-a-bp-standby/68598)
- [2] Cardano forum, [HA Stake Pool Cluster](https://forum.cardano.org/t/ha-stake-pool-cluster/38855)
- [3] Cardano forum, [Operating a hot-spare block producer with P2P enabled](https://forum.cardano.org/t/operating-a-hot-spare-block-producer-with-p2p-enabled/129170)
- [4] Cardano forum, [Running dual block producers to produce forks](https://forum.cardano.org/t/running-dual-block-producers-to-produce-forks/100001)
- [5] cardano-node issue [#1132](https://github.com/input-output-hk/cardano-node/issues/1132)
- [6] Cardano forum, [Not getting the Vasil node.counter memo](https://forum.cardano.org/t/not-getting-the-vasil-node-counter-memo-a-story-for-other-spos/111937)
- [7] cardano-node wiki, [KES period](https://github.com/input-output-hk/cardano-node-wiki/wiki/7_KES_period)
- [8] Cardano forum, [KES key expired](https://forum.cardano.org/t/kes-key-expired/98936)
- [dbf]: cardano-node wiki, [Dynamic block forging](https://github.com/input-output-hk/cardano-node-wiki/wiki/dynamic-block-forging)
- [gist]: a community [failover script](https://gist.github.com/adamsthws/bfa3f71f7bb801d95e97f8af1704ddcd) (SIGHUP after swapping the certificate)
- [nov25]: Intersect, [Incident report: network partition analysis and resolution strategy](https://intersectmbo.org/news/incident-report-network-partition-analysis-and-resolution-strategy)
- [pr1610]: ouroboros-consensus PR [#1610](https://github.com/IntersectMBO/ouroboros-consensus/pull/1610), operational certificate issue numbers and tiebreakers
- [rfc9591]: [RFC 9591](https://datatracker.ietf.org/doc/rfc9591/), FROST
- [EIP-3076](https://eips.ethereum.org/EIPS/eip-3076), slashing-protection interchange; Nimbus on
  [doppelganger detection](https://our.status.im/nimbus-update-v1-0-7-release/); Teku on
  [slashing protection](https://docs.teku.consensys.io/development/concepts/slashing-protection)
- Horcrux [signing design](https://github.com/jakubbujny/horcrux/blob/main/docs/signing.md) (Raft high-watermark,
  threshold Ed25519)

[1]: https://forum.cardano.org/t/block-producer-failover-approach-with-a-bp-standby/68598
[2]: https://forum.cardano.org/t/ha-stake-pool-cluster/38855
[3]: https://forum.cardano.org/t/operating-a-hot-spare-block-producer-with-p2p-enabled/129170
[4]: https://forum.cardano.org/t/running-dual-block-producers-to-produce-forks/100001
[5]: https://github.com/input-output-hk/cardano-node/issues/1132
[6]: https://forum.cardano.org/t/not-getting-the-vasil-node-counter-memo-a-story-for-other-spos/111937
[7]: https://github.com/input-output-hk/cardano-node-wiki/wiki/7_KES_period
[8]: https://forum.cardano.org/t/kes-key-expired/98936
[dbf]: https://github.com/input-output-hk/cardano-node-wiki/wiki/dynamic-block-forging
[gist]: https://gist.github.com/adamsthws/bfa3f71f7bb801d95e97f8af1704ddcd
[nov25]: https://intersectmbo.org/news/incident-report-network-partition-analysis-and-resolution-strategy
[pr1610]: https://github.com/IntersectMBO/ouroboros-consensus/pull/1610
[rfc9591]: https://datatracker.ietf.org/doc/rfc9591/
