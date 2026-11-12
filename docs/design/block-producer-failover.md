# Block-producer failover (research)

**Status**: Proposal (research). Nothing here is implemented or approved. The first stage (S0) mirrors the
reference and follows the normal process; every later stage adds behaviour the Haskell node does not have
and therefore needs the owner's explicit approval first ([ADR 0001](../adr/0001-haskell-node-as-reference.md)).
Stage S0 is tracked in [#906](https://github.com/KD5UJC-XYZ/scarab/issues/906).

## The question

A stake pool is elected for a handful of slots per epoch. If its block producer is down, slow or
misconfigured at one of those slots, the block is gone; nothing makes it up later. Operators therefore
want a second producer that takes over automatically. Today that means scripts around SIGHUP, which can
leave two producers forging at once, and copies of the hot keys on more machines.

This document answers four things: what operators have run into, what the protocol actually does when a
pool signs twice, whether automatic failover can be made safe, and a staged design built from patterns
with a long record elsewhere (Raft-replicated compare-and-set, fencing tokens, slashing-protection
journals, doppelganger detection). The goal is automatic failover kept as simple as safety allows.

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
| Overrun slots | A loaded host that finishes a slot's work late does not forge that slot. On a loaded devnet host at 1 CPU (`devnet.py pool --scala-cpus 1`), Scarab lost 11 of 1,594 scheduled slots this way ([evidence log 3](../compatibility/evidence-log-3.md)). | Scarab evidence |
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
  every same-slot tie against counter `n`, and once on the chain makes counter `n` invalid on that chain
  (irreversibly only once that block is stable, `k` blocks deep). This is how a
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
  occur, as long as the cluster keeps its one replicated history (S3). Re-sending the identical block is
  allowed. Recovery that cannot keep that history is either externally fenced or explicitly weaker (S3,
  loss of quorum).
- **R2 Liveness, conditional.** The pool forges in a leader slot when a majority of the cluster and one
  ready producer are up, network and processing delays stay within their measured bounds, clock skew is
  within its limit, the forger is on a recent tip the majority shares, and the claim, signature and
  diffusion fit before the slot's forging deadline. Outside those bounds a slot may be lost; it is never
  forged twice (R1).
- **R3 Cold key offline.** Nothing in the cluster holds or needs the cold key.
- **R4 No wire deviation.** Blocks, headers and signatures are byte-for-byte what a single reference node
  would produce; the network cannot tell a cluster from one node.
- **R5 Forward security kept.** KES evolutions are erased on every member that holds them.
- **R6 Fewest hot-key copies** needed for R2.
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
| Key distribution by agent | upstream `kes-agent` with bootstrap peers | how the standby "keeps its KES key up to date" |

Two lessons from Ethereum carry over directly. Nimbus reports accidental duplicate validators as a primary
cause of slashings, and Teku states its slashing protection does nothing when the same key runs on
several nodes: a local journal is not enough, the journal must be shared. And Horcrux's own guide (a
Cosmos signer cluster) warns that teams have double-signed while *migrating* into a cluster: the migration
path needs as much design as the steady state.

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
  `setBlockForging nodeKernel []`, so the node stops forging. Lines 664-667 map a `CredentialsReadError`
  (the bulk credentials file) to the same case;
- lines 396-398: with `--start-as-non-producing-node` (`ncStartAsNonProducingNode`) the node does not call
  `setBlockForging` at start-up, so it has credentials configured but forging disabled until a SIGHUP finds
  the files (lines 248-252 trace that start-up state).

Scarab has no `--start-as-non-producing-node`. So the community's existing runbook — move the files away,
send SIGHUP — **does not stop a Scarab primary**, and a script that then promotes a spare produces exactly
the duplicate it was written to avoid. Scarab's KES write-back ([ADR 0015](../adr/0015-kes-key-written-back-and-checked-reload.md))
appears to make it worse: with the main key file moved away, the next evolution goes through the
write-back's recovery path and recreates the key file at the configured path. This is a reference deviation
in its own right, with no approval on record, and should be fixed first, independently of any cluster
work: mirror the flag and the absent-file rule, and keep the owner's approved checks on a reload that does
find files ([decisions](../decisions.md), 2026-10-25, #474 row 12) and the write-back (row 11). It is the prerequisite for everything below, because it
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

**The invariants.** Safety follows from the uniqueness of the committed signing intent, not from the
uniqueness of the signer:

1. **One replicated history per pool.** Every signing decision is a record in one Raft log. A producer
   never signs without a committed claim.
2. **One immutable claim per slot.** `Claim(s)` holds the complete canonical intent: everything needed to
   reproduce the exact block (the unsigned header body and the block body). Committing the same claim
   again is idempotent; a different claim for a slot that has one is refused.
3. **Deterministic signing and reproduction.** Every producer-capable member produces the same signed
   block from a committed claim, or refuses to forge.
4. **No automatic fresh-cluster recovery.** Quorum is restored through Raft's membership protocol. A
   history that cannot be recovered is replaced only under the governed procedure in "Loss of quorum".
5. **A quorum-validated linearizable doppelganger check** with an activation boundary (S4).

**The protocol.** For each leader slot `s`, with a forging deadline `D(s)` after which no member claims,
signs or diffuses for `s`:

1. Every producer-capable member evaluates leadership with its own VRF key, as today.
2. The preferred forger for `s` is the Raft leader if it reported itself ready; otherwise the ready member
   with the best connectivity. This is only a preference.
3. The preferred forger builds the block at the start of `s` and commits `Claim(s)` with a compare-and-set
   that succeeds only if no claim for `s` exists and `s` is above the watermark. It signs **only after the
   commit succeeds**, then diffuses the block.
4. Any other ready member waits a takeover delay `δ` into the slot. If no claim for `s` exists, it tries
   the same compare-and-set for its own intent. If a claim exists, it reads it.
5. A member that finds a claim for `s` it did not make never builds its own block for `s`. If the chain
   has not seen the block by `δ`, it **reproduces the claimed block** and diffuses it.
6. Past `D(s)` every member stops working on `s`: a slot that did not make its deadline is lost, as the
   reference passes over a slot its forging loop overran. The deadline also stops a member that was paused
   and resumes late from acting on `s`; it is a timing guard, and R1 does not rest on it.

Step 5 works because, for a given key and message, every signature in a Praos header is deterministic:
the leader VRF proof (ECVRF draft-03 derives its nonce from the key and input) and the KES signature
(Sum6 over Ed25519, which is deterministic per RFC 8032). Given the claimed header body and the same KES
evolution, any member produces the identical header, and therefore the identical block. A primary that
crashes between claiming and diffusing therefore costs a delay of `δ`, not a block, and never a duplicate.
This property carries R1, so it is established by conformance tests rather than assumed: identical bytes
from every member, across KES evolutions, key reloads and restarts, and from the agent path and the file
path alike. A member that cannot reproduce a claim exactly refuses to forge it.

**Claim retention.** Claims are never simply deleted. The replicated state keeps a monotone watermark:
claims at or below it may be pruned, and a claim for any slot at or below it is refused, as EIP-3076 and
Horcrux keep a low watermark. Otherwise a member paused after building its block could resume after the
claim was pruned, find no claim, and sign a second block. The watermark only advances past slots whose
deadline has passed by a margin, for example below the immutable tip. It lives **inside** the replicated
state machine, so a Raft snapshot or log compaction carries it with the claims it summarises.

**Rejoining.** A member whose durable history cannot be shown continuous (a disk restored from backup or
replaced, a snapshot older than one it has acknowledged) never rejoins under its old identity: it is wiped
and added back as a new member through Raft's membership change, with forging disabled until it has caught
up with the committed log.

**Why this meets R1 without timing assumptions.** A member signs only an intent that is the committed claim
for that slot, and compare-and-set on one Raft log, with the watermark, admits one claim per slot.
Partitions, paused processes and wrong clocks can delay a claim or lose the slot; they cannot produce a
second distinct block. A primary cut off from the majority cannot commit, so it does not sign
(self-fencing falls out of the protocol rather than from a watchdog timer). Clocks matter only for liveness
and for being on time, as they do for one node.

**Cost.** One majority commit per leader slot, between building and signing. Within one region this is a
few milliseconds; across regions, tens of milliseconds. It must be measured against the forging budget and
recorded in [performance.md](../performance.md) before the stage is accepted.

**Takeover delay and deadline.** `δ` trades a late block, which is more likely to lose a height battle,
against needless contention; `D(s)` bounds how late a block is still worth sending. Because the claim
decides, any `δ` is safe. Both are chosen from measurements, not guessed: the high-percentile latency of
the claim commit, signing and diffusion, and the missed-slot rate they produce on a devnet under load.

**Loss of quorum.** With no majority, nobody forges. That is the price of R1. Recovery, in order of
preference:

1. **Restore the majority.** Bring members back with their durable state, or wiped and re-added through
   the membership protocol, until the one history has a quorum again. R1 is kept.
2. **Replace the cluster, only after durable external fencing.** If the history cannot be recovered, a
   new cluster may start only once there is **positive evidence**, from outside the cluster, that every
   former signer is stopped **for good**: its process verified terminated *and* prevented from restarting
   (its service disabled, its key material and Raft state destroyed), or its machine powered off and
   decommissioned under a controlled procedure (for example through the hosting provider's console or a
   switched power unit). Network isolation alone does not qualify: an isolated producer keeps its key and
   its Raft state, and once connectivity returns, an old majority can go on committing claims beside the
   new cluster. Removing the KES files or stopping the agent is not enough either, because a running node
   holds the current key in memory. An
   operator's belief that members are down is not evidence, and wiping a disk does not stop a process still
   running from memory: a partitioned majority of the old cluster could go on committing claims. With that
   evidence for every former signer, R1 is kept. The procedure lists the evidence it needs per member, and
   the replacement cluster is not released until each item is recorded.
3. **The credential fence (S5), explicitly weaker.** New credentials with counter `n + 1`, held only by
   the new cluster. Until a block with the new certificate is on the chain *and stable*, an old member
   that is still running can forge validly, including in the same slot; same-slot ties prefer the new
   counter. This bounds a breach of R1 rather than preventing it, and the operator is told so.

Otherwise the cluster stays failed closed. A standalone mode, if one is offered at all, runs one member
without the claim protocol; it is documented and logged as **giving up R1**, never presented as a fence.

### S4 — Doppelganger guard and readiness

- **Doppelganger guard.** Every member already sees every header through ChainSync. A header issued by the
  pool's cold key must equal the block reproduced from the committed claim for its slot. ChainSync and the
  claim log are read independently, so a member may see its own block before its local copy of the log
  has caught up; absence from a stale local view is not evidence of a twin. Before declaring a
  doppelganger, the member does a **quorum-validated linearizable read** of the claim: the leader confirms
  with a majority that it is still leader before answering (Raft's read-index; etcd's linearizable read,
  which is its default, never its serializable read). A leader's local view alone is not enough, because a
  deposed leader can still answer from it. If that read cannot complete, for example while the member is
  cut off from the majority, it suspends its own forging and waits rather than concluding anything.
  Only a header that contradicts a confirmed claim, or has no claim at all on a confirmed read, for a slot
  the guard judges (see the activation boundary below), means some other process holds the keys: a forgotten old producer, an unfenced old cluster, or a compromise. Then the
  cluster disables forging everywhere and alerts. This is the Ethereum doppelganger idea adapted to
  sparse slots: Cardano cannot wait two epochs for silence, because the twin only shows itself when it
  forges, so the check runs continuously. A witness holds no key and cannot reproduce a block, so the
  check runs on producer-capable members.
- **Activation boundary.** When clustered forging is enabled, the cluster commits an activation slot `A`
  to the replicated state, so it survives restarts and member replacement. The guard judges only headers
  for slots at or after `A` and above the claim watermark. Blocks from before `A` (forged by the pool's
  previous single producer, or replayed while a member resynchronises) and blocks below the watermark are
  history, not new forging activity. A header for a slot it has already judged, delivered again after a
  rollback, is judged again against the same claim, and an identical block passes. This is a **detection
  limit**, not a safety gap: a rogue producer's block for a slot at or below the watermark, if it appears
  only later, is not flagged. R1 rests on the unique claim (S3); the guard is a supplementary alarm.
- **Readiness.** Each member publishes, ahead of each leader slot, whether it could forge it (tip lag,
  KES window, counter against the chain, clock offset, peers). The node reports it through the existing
  metrics and the `/forging` endpoint, and alerts when fewer than two members are ready.
- **Tip agreement.** Members compare their selected tips. Members on different forks, as during the
  2025-11-21 split, are reported, because a takeover then forges on another fork.
- **Joining.** A new member joins with forging disabled and becomes eligible only after it has caught up
  with the committed claim log (see "Rejoining" in S3).

### S5 — Credential fence with the opcert counter (operator procedure)

If a producer is lost rather than down — stolen, compromised, or unreachable but possibly running — the
operator issues a certificate with counter `n + 1` for a fresh KES key and installs it on the cluster. The
network then prefers the cluster's blocks in any same-slot tie and, once one is on the chain and stable,
rejects the lost machine's. Before that block is stable a rollback can remove it, and with it the
revocation, so the old certificate is treated as superseded only once the new one is in a block at least
`k` deep (the immutable part of the chain). Preparing such a certificate ahead of time is of limited use: it goes stale at every
routine rotation (which also moves the chain's counter), its KES window runs from the period it names, and
its fresh KES secret is one more copy of a hot key to protect. A rehearsed procedure with the offline cold
key is the better tool. It must follow the warning in [#1610][pr1610]: one increment per stability window.
It is also the weaker recovery path after losing quorum (S3), with the same caveat: until a block with
the new certificate is stable, the old signer remains valid.

## Verification

The safety argument is short: at most one committed claim per slot, members sign only committed claims,
and signing is deterministic, so there is at most one distinct block per slot. The risk is in the edge
cases around it (crashes between steps, lagging views, recovery), which is where every review of this
document found a gap. Verification therefore has four layers, chosen to be the most rigorous set that adds
no toolchain beyond one pinned JAR.

| Approach | What it establishes | Gap |
|---|---|---|
| Random property tests (ScalaCheck) | R1 on the interleavings it happens to sample | can miss the rare interleaving |
| Exhaustive search over a model written in Scala | every interleaving up to the bounds | a checker of our own, whose bugs pass silently; a model written beside the code tends to share its assumptions |
| **TLA+ checked by TLC** | every interleaving up to the bounds, by an independent and widely used checker, on a specification written apart from the code | bounded, not a proof for all sizes; small instances find most protocol bugs |
| A machine-checked proof (TLAPS, Lean) | R1 for any number of members and slots | a heavy toolchain, and a failed proof gives no counterexample |

**V1. The specification.** A TLA+ specification of the claim protocol and the doppelganger guard, with a
TLC configuration that names its bounds (for example three members, three slots, two crashes) and its
invariants:

- **R1**: no two distinct signed blocks for one slot;
- **no false alarm**: in a run where every member follows the protocol, the guard never fires.

Its actions are those of S3 and S4: build, claim, sign, diffuse, take over by reproduction, crash at any
step, pause and resume, partition and heal, advance the watermark, wipe and re-add a member, a
quorum-validated read, chain rollback, activation, and the three recovery paths after a loss of quorum.
Raft is abstracted as a linearizable compare-and-set store, so the specification establishes the claim
protocol's safety *given* that abstraction. The events where the abstraction does not hold — a restored
disk, a rolled-back snapshot, a lost majority — are modelled explicitly as the store forgetting or forking
its history, together with the rules S3 applies to them: wiping and re-adding a member, durable external
fencing, and the credential fence. Each recovery path then either keeps R1 or yields its expected
counterexample. Deterministic signing is an axiom of the specification, backed by V3.

**V2. The checker in CI.** TLC is one Java archive, pinned by version and checksum with the repository's
other pins, and run by a guard script in the existing JDK image (no new image, and no `java` on the host,
per AGENTS.md). The script fails on any invariant violation and prints the counterexample trace. It also
checks the paths this document calls weaker — replacing a cluster without evidence that every former
signer is durably stopped (including with only network isolation, healed later), and the credential fence
before its certificate is stable — and **requires** a
counterexample for each, so that a later edit cannot relabel a weak path as safe without the checker
objecting.

**V3. The link to the code**, in Scala:

- **model-based tests**: ScalaCheck generates sequences of the specification's actions and runs them
  against the real `ForgeGate` cluster interpreter, checking R1 on what it actually signs;
- **conformance tests of deterministic reproduction**: identical header and block bytes from every member,
  across KES evolutions, key reloads and restarts, on the file path and the agent path;
- **trace validation** (stretch goal): the devnet failover scenario logs its claim, sign and diffuse
  events, and a check confirms that each run is a behaviour the specification allows.

**V4. Not planned: a proof for all sizes.** A TLAPS or Lean proof would live in a separate repository so
that Scarab's build never depends on it, and is worth doing only if V1 to V3 leave a specific doubt.

V1 and V2 come before any S2 or S3 code: a counterexample changes the design, and is cheapest found
before the code exists. S0 and S1 mirror the reference and do not wait for them.

## Failure cases

| Case | S3 outcome |
|---|---|
| Primary crashes before claiming | backup claims after `δ` and forges; block is later by `δ` |
| Primary crashes after claiming, before diffusing | backup reproduces the identical block after `δ` |
| Primary partitioned from the majority | its claim cannot commit, so it does not sign; backup forges |
| Witness or one member down | majority remains; forging continues |
| Two members down | no forging (R1 over R2); recovery by restoring the majority, never by an unfenced standalone member |
| Paused or partitioned member resumes | it signs only a committed claim, which is the block any other member would reproduce; past `D(s)` it does not act |
| Disk restored from backup, or replaced | the member rejoins as a new Raft member after a wipe, disabled until caught up |
| Forger crashes between claiming and diffusing | a takeover reproduces the same block before `D(s)`; otherwise the slot is lost |
| Member sees its own block before its log catches up | quorum-validated linearizable claim read before any doppelganger verdict; if the read cannot complete it suspends its own forging |
| Cluster enabled on a pool that already forged, or a member resynchronises old blocks | the guard judges only slots at or after the committed activation slot and above the watermark |
| Backup is behind the tip | it is not ready and does not claim; if it is the only one, readiness alerts before the slot |
| Clock skew on one member | it may claim late or not at all; cannot cause a duplicate |
| Old producer left running with the keys | doppelganger guard stops the cluster at its first block and alerts |
| Hot key stolen | the credential fence (S5) |
| Members on different forks | tip-agreement alert; claim still unique per slot |
| Raft data lost on a majority | the cluster fails closed; a replacement cluster starts only after every former producer is durably fenced, stopped for good rather than isolated (R1 kept), or under the credential fence (a breach bounded to the window before the new certificate is stable) |

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
4. **More copies of the key is a cost.** A three-member cluster triples where the hot key lives. A witness
   that holds no key keeps it to two. The opcert counter (S5) is the recovery path, and it needs a procedure
   written before it is needed.
5. **Diversity cuts both ways.** Members on different node builds would have survived the 2025-11-21 split
   better, but would forge on different forks during one. Tip agreement (S4) is the guard. A Haskell node
   cannot take part in the claim protocol, so a mixed cluster could only use S0-style SIGHUP control,
   without R1's guarantee.
6. **Test it the way consensus protocols are tested.** Check the specification before writing the code
   ([Verification](#verification)). Then a devnet scenario that kills members with `kill -9` at random
   points around leader slots and partitions them, asserting at most one block per pool per slot and
   counting filled leader slots, on the existing
   [deterministic adversarial schedules](adversarial-schedules.md).
7. **Migration is where Horcrux users double-signed.** Moving a running pool into a cluster needs a written
   procedure that never has two signers enabled at once, checked on a devnet.

## Delivery

| Stage | What | Reference | Gate |
|---|---|---|---|
| S0 | `--start-as-non-producing-node`; absent credential file disables forging on SIGHUP ([#906](https://github.com/KD5UJC-XYZ/scarab/issues/906)) | mirrors cardano-node 11.1.3 | level by CONTRIBUTING; devnet `kes-rotate` plus a disable/enable scenario |
| S1 | KES agent client | [kes-agent.md](kes-agent.md) | as designed there |
| V | TLA+ specification of S3 and S4, TLC pinned and run in CI (V1, V2) | verification only | TLC finds no violation of R1 or of no-false-alarm within the bounds, and finds the expected counterexample on each weaker path |
| S2 | `ForgeGate` capability, local claim journal | additive, approval needed | `check`; restart-inside-slot test |
| S3 | Raft claim protocol, deadline, takeover by reproduction | additive, approval needed | V passing; model-based tests and conformance tests of deterministic reproduction (V3); devnet failover scenario including member resurrection; high-percentile claim, sign and diffusion latency and the missed-slot rate in performance.md before `δ` and `D(s)` are chosen |
| S4 | Doppelganger guard, readiness, tip agreement | additive, approval needed | devnet with a rogue producer |
| S5 | Credential-fence procedure | operator documentation | devnet rehearsal |

## Decisions for the owner

1. Is failover in scope for Scarab at all, given that it adds behaviour the reference lacks (S2 to S4)?
2. S0: treat the absent-file difference as a reference deviation to fix now (recommended).
3. Raft: external etcd or an embedded library.
4. Minimum topology to support: two producers and a witness, or three producers.
5. Recovery after losing quorum: whether to offer replacement after external fencing and the weaker
   credential fence, and whether a standalone mode that gives up R1 exists at all.
6. Verification: where the TLA+ specification lives in the repository, and whether CI checks it on every
   change or only when the specification changes.

## Optional extension: threshold KES

Threshold signing of the KES key (no member holds a full key; for example FROST, [RFC 9591][rfc9591]) is an
optional extension, not part of this design (owner, 2026-11-12), to revisit only once it is proven safe. It
would close one gap: the cluster replicates the whole KES key to each producer-capable member. It opens
more: FROST signatures are not deterministic, so S3's takeover by reproduction no longer works; a
coordinator that crashes after assembling a signature but before recording it leaves a slot that cannot be
recovered without risking a second, different block; and it needs its own key format and signer service,
amending [ADR 0024](../adr/0024-adopt-the-upstream-kes-agent.md), which decided against remote signing.

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
