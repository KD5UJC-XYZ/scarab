<!-- SPDX-License-Identifier: Apache-2.0 -->
# A differential campaign against the reference

**Status**: Proposal, 2026-11-16. Not implemented. Its author read every claim about the reference at the pins its
[companion page](differential-campaign-reference.md) lists; two independent reviews checked them and their corrections
are made here, and the re-check that [CONTRIBUTING](../../CONTRIBUTING.md#levels) asks for before approval is pending.
**No persisted format changes**: a kept input lives in a directory of its own, off the persisted-format surface (§7.4,
[FMT-1](../design-rules.md#fmt-1)). Tracking: [#922](https://github.com/KD5UJC-XYZ/scarab/issues/922).

In-tree code is cited by path, symbol and line at `fdd5834f2`. The claims about the reference (R1 to R25) and what
is not checked (N1 to N16), with their pins, are in [§3's own page](differential-campaign-reference.md).

## 1. What the campaign is for

An independent tester judges a node by whether they can find one transaction, one block or one sequence on which it
disagrees with the reference about validity or about the chain's tip. The conformance suites, the replays of recorded
chains and the nightly devnets compare what happens to occur; nothing in the tree searches for a disagreement on
purpose. The campaign does: it generates and mutates transactions and blocks, has both nodes judge each one, compares
the verdicts and the tips, and keeps every disagreement, minimised, as a regression input once it may be published
(§8).

A disagreement about a verdict is never an Improvement: an Improvement needs I1 to hold, and I1 is the reference's
verdict ([REF-1](../design-rules.md#ref-1), [REF-4](../design-rules.md#ref-4)). So every disagreement is a Gap in
Scarab, a defect of the reference that Scarab must still mirror at the verdict, or a defect of the campaign.

Out of scope: performance, network faults (the [adversarial schedules](adversarial-schedules.md) and the Antithesis
trial), and the Leios and Dijkstra builds. Decoding alone stays with the decoding oracles
([the playbook](../playbooks/confirm-with-the-decoding-oracle.md)), which the campaign uses as its first judge (§5.1).

## 2. What exists

### 2.1 The devnet clusters

- **A cluster** is one container, `<name>-ref`, running every Haskell node from `REFERENCE_IMAGE`,
  `cardano-reference-11.1.3:local` (`scripts/devnet/util.py` 17), or the pools from another release's node when one is
  given (`start_reference`, `scripts/devnet/cluster.py` 25-36). `up` makes three block-producing pools with
  `cardano-testnet create-env` (`cluster.py` 142-143); the walk from Byron to Conway is `ERA_PLAN` (`cluster.py`
  176-177). `create-env` makes fresh keys and a genesis on every run (the refusal corpus's recorded Shelley genesis
  files share no genesis delegate and differ in their system start), so no seed reproduces a transaction's bytes: a
  run's record must hold the bytes and the genesis, as the refusal corpus does (§2.2).
- **At most one Scarab node** runs per cluster, `<name>-scala`, in the reference container's network namespace
  (`scala_container`, `cluster.py` 625-647; `SINGLE_FOLLOWER`, `util.py` 28-62). In `follow`, `soak`, `e2e` and `ntc`
  it follows one reference node, node 1 unless `--node` names another (`scripts/devnet/follower.py` 30-33;
  `scripts/devnet/cli.py` 221); in `pool` it forges in place of one Haskell pool
  (`scripts/devnet/scenarios/pool.py` 586-608).
- **Transactions** reach a node through `cardano-cli` in the reference container (`scripts/devnet/load.py` 132-139;
  `EraLoad.submit`, `scripts/devnet/eraload.py` 296-327) or raw over node-to-client LocalTxSubmission
  (`LocalStateQuery.submit`, `scripts/devnet/ntcquery.py` 465-476), which returns the raw `ApplyTxErr` of a refusal.
  `scripts/devnet/eratx.py` builds and signs a transaction of any Shelley-based era with its own Ed25519 (`build_tx`,
  361-366; `envelope`, 448).
- **One transaction to both nodes** happens in two places only. `ntc`'s `submissions` sends each of a fixed list of
  refused Conway transactions (`scripts/devnet/ntccases.py` 127-227) raw and through `cardano-cli` to reference node 1
  and to Scarab, retrying until one tip brackets all four submissions on both nodes, and requires equal `ApplyTxErr`
  bytes and equal printed text unless a case declares a tracked divergence, which none of the fixed cases does
  (`scripts/devnet/scenarios/ntc.py` 348-380, `judge_refusal` 322-345). The `pool`
  scenario's `--plutus-load` compares only the constructor path of the first failure of its refused Plutus
  transactions, with no bracket (`scala_judgement`, `scripts/devnet/scenarios/pool_plutus.py` 131-155). An accepted
  transaction gets no independent second verdict in a cluster: Scarab forwards it to the reference node over
  TxSubmission2 (`ntc.py` 24-25).
- **Scarab's LocalTxSubmission and mempool serve Conway only**
  ([`fixtures/reference/refusals/README.md`](../../fixtures/reference/refusals/README.md) 63-66).

### 2.2 The refusal corpus: the reference judges live, Scarab replays offline

`devnet.py refusal-corpus` submits transactions to reference node 1 over LocalTxSubmission and keeps a batch only if
the tip and the mempool's slot held still and the mempool was empty before and after (`judged`,
`scripts/devnet/refusalcorpus.py` 205-219). It records each raw refusal, the tip, `DebugNewEpochState` and
`GetUTxOWhole` in the reference's encoding, the genesis files and the provenance: reference build, image, Scarab commit
(`refusalcorpus.py` 249-258, 319-357). `ReferenceRefusalSuite` replays every case offline: a Conway case through
Scarab's own node-to-client path on an empty mempool, an earlier era's through that era's LEDGER rule, comparing the
refusal byte for byte ([its README](../../fixtures/reference/refusals/README.md) 55-66). This is the campaign's oracle
for transactions in every era, path R in §4 (scoped before Conway).

### 2.3 Tips and ledger state

A tip is read with `cardano-cli query tip` or LocalStateQuery `GetChainPoint` (`point_of`, `ntc.py` 154-166) and from
Scarab's `follower.jsonl` events or query port. Each scenario compares tips its own way (`ntc.settle`, `ntc.py`
183-191; `pool.settled_tips`, `pool.py` 426-435); no tip-equality assertion was found in `e2e`. The exact comparison
of ledger state is `e2e`'s same-point `DebugNewEpochState` and `GetUTxOWhole` (`scripts/devnet/compare.py` 377-380,
463-602; `scripts/devnet/ledgercompare.py` 49-96) and the same-point node-to-client battery
(`scripts/devnet/ntceras.py`).

### 2.4 Seeds

Seeds choose the load (`random.Random(args.seed)`, `scripts/devnet/load.py` 74, 208, with `--seed` at
`scripts/devnet/cli.py` 242), the era load (`EraLoad.__init__`, `scripts/devnet/eraload.py` 211-214) and the fault
schedule: `faultplan.generate` is a pure function of the seed and the cluster's parameters, written to
`fault-schedule.json` before the run, and replayed by seed or by file (`scripts/devnet/faultplan.py` 121-150, 157-162;
`scripts/devnet/scenarios/faults.py` 242-250, 323-327). A seed makes the choices reproducible, not the live
execution: the replay repeats the schedule, not the execution ([adversarial schedules](adversarial-schedules.md)),
and a load's sequence also follows the cluster's timing. The nightly fault seed is the workflow's run number
(`.github/workflows/faults.yml` 51), which is public. `e2e.yml` passes no seed.

### 2.5 The fuzz project (K3)

`Targets.all` (`fuzz/src/test/scala/xyz/kd5ujc/scarab/fuzz/Targets.scala` 2453-2465) registers decoder targets per era
for bodies, witnesses, whole transactions and headers (1284-1319), and `tx-submitted` (1125-1129),
`local-tx-submission` (842-862) and `block` (1011-1022). Seeds are committed blocks, early-era transactions and CBOR
sampled from the pinned CDDL (`Corpus.scala` 19-32, 216-273); `Mutate.scala` applies one to four byte-level operators
(70-180) and re-signs nothing. Runs replay per target, seed and case (`Harness.scala` 123-141). The properties are
Scarab's own ([fuzzing](../running/fuzzing.md)): the campaign gets seeds, a mutator and replay, and no verdict of the
reference.

### 2.6 The Plutus generators (#43 WP8)

`TermGen` makes UPLC programs per language and protocol, with `Data` arguments and a budget, from a seed
(`uplc/src/test/scala/xyz/kd5ujc/scarab/uplc/oracle/TermGen.scala` 19-41). `scripts/oracle/plutus/generated/run.py`
has the pinned evaluator judge them offline (46-60, 94-134), and `GeneratedOracleSuite` replays the verdicts exactly
([`fixtures/reference/uplc-generated/README.md`](../../fixtures/reference/uplc-generated/README.md)). It gives the
campaign scripts and the generate, judge, replay shape; no script context and no transaction around the script.

### 2.7 The reference oracles

`scripts/oracle/` holds offline harnesses built from released packages or pinned checkouts, most run with no network
(`scripts/oracle/plutus/generated/run.py` 52-53 does not pass `--network none`). The
decoding oracles (`conway-decoding`, `header-decoding`) take a JSON file of encodings and give each one's verdict and
complete failure; they run no ledger rule ([the playbook](../playbooks/confirm-with-the-decoding-oracle.md)). The
others judge one predicate, one API or one rule (`dijkstra-rules`, Dijkstra only). The `ledger-govcert` exporters run
the reference's own Conway LEDGER and LEDGERS on states its generators make
(`scripts/oracle/ledger-govcert/pv11-generators-bootstrap.patch` 371-374,
`scripts/oracle/ledger-govcert/multi-ledgers.patch` 75-100). No general oracle was found that applies LEDGER or BBODY of
Shelley to Conway to a supplied state and transaction; a running reference node does: the refusal corpus (§2.2), `ntc`
and `pool --plutus-load`.

### 2.8 The conformance runners

`scripts/conformance/` runs the node-diversity conformance report's scenarios, whose ledger fixtures carry their
dataset's own expectations, Conway only, and no Haskell verdict
(`tools/src/main/scala/xyz/kd5ujc/scarab/ledger/LedgerRulesFixture.scala`). The upstream conformance adapters replay
committed outputs of the reference's own generators ([upstream conformance](upstream-conformance.md)). Both are
sources of seed transactions with a state, and these Scala runners judge no new input; the exporters behind the
adapters (§2.7) generate and judge new Conway cases with the reference's own rules.

### 2.9 Builders of blocks, and serving them

- `ForgedChain` runs the production `Forger` per slot over an in-memory Conway genesis with test keys
  (`node/src/test/scala/xyz/kd5ujc/scarab/node/forge/ForgedChain.scala` 142-150, 392-405); its blocks do not belong to
  any devnet's genesis. `BlockBuilder` assembles a header body and a Conway block as pure functions
  (`node/src/main/scala/xyz/kd5ujc/scarab/node/forge/BlockBuilder.scala`, `headerBody` 265, `block` 293-297), and the
  KES signature is made only in `Forger`. Earlier eras' blocks are written only by tests, not forged for a devnet:
  encodings of each era's shape that no rule runs on (`DetachedRecordsReferenceSuite.block`,
  `ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/DetachedRecordsReferenceSuite.scala` 190-206) and signed Byron
  blocks (`ByronTestKit.signedBlock`, `ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/byron/ByronTestKit.scala`
  195-230).
- The devnet's pool keys are known to the harness: `faults.write_keys` copies a pool's VRF and KES keys and its
  certificate from the `create-env` directory (`faults.py` 231-239).
- `ChainServer` serves only what the node itself validated
  (`node/src/main/scala/xyz/kd5ujc/scarab/node/relay/ChainServer.scala` 20-23). In tests, `ServedChain` feeds raw
  blocks to it and `InMemoryPeers` serves them over in-memory bearers, Scarab to Scarab
  (`node/src/test/scala/xyz/kd5ujc/scarab/node/ServedChain.scala`; `InMemoryPeers.scala`).
- `scripts/devnet/lagproxy.py` forwards bytes unchanged, with delays (21-35); no ChainSync or BlockFetch server was
  found in `scripts/`. Blocks reach a Haskell node from Scarab only as Scarab validated them: those it forges
  (`pool`) and, in `relay`, the reference pools' blocks it serves on (`scripts/devnet/scenarios/relay.py` 323-346).

### 2.10 What is missing

| Missing | Needed for |
|---|---|
| A seeded, recorded generator over a state both nodes share, with a mutator that re-signs and re-hashes (`load.py` 109-138 and `eraload.py` sign valid transactions from the live UTxO, without that contract) | every transaction probe past the witness checks |
| A bracketed submission primitive that records both verdicts, for accepted transactions too | path L (§4) |
| A node-to-node peer the campaign controls, which serves a forged or mutated chain to a Haskell node and to Scarab, and builds blocks of the eras before Conway | every block probe; the era-transition probes |
| A reader of each node's verdict on a block: the reference's `InvalidBlock` trace is silent by default (`scripts/devnet/reference_tracing.py` 3-16); no Scarab event was found that names a refused block's full hash, and only `scarab_validation_errors_total` counts refused bodies (`MeteredRules.scala` 35-40) | block verdicts |
| A seeded run record, a minimiser against both nodes, a private runner and store, and a publication that cannot carry an outcome before clearance | §7, §8 |

## 3. What the reference does

In [its own page](differential-campaign-reference.md): a transaction from a local client (§3.1) and from a peer
(§3.2), a header (§3.3) and a block from a peer (§3.4), the claims with their pins (§3.5, R1 to R25) and what is not
checked (§3.6, N1 to N16). This document cites them by their IDs.

## 4. The shape of the campaign

**Two judging paths.**

- **Path R** (the reference live, Scarab offline): the refusal corpus's method (§2.2), extended to generated inputs
  and to accepted ones. The reference judges each probe over LocalTxSubmission in a bracketed context (§6); the record
  holds the state, the UTxO, the genesis, the probe and every earlier probe accepted since the state was captured, in
  order. Scarab replays offline. For Conway this is exact LocalTxSubmission parity: Scarab's own node-to-client path
  judges the probe on a mempool that holds the earlier accepted probes (§2.2). Scarab's mempool serves Conway only
  (§2.1); before it, the refusal corpus's replay runs the era's LEDGER rule alone, as the first transaction of a block
  (`replayPreConway`, `node/src/test/scala/xyz/kd5ujc/scarab/node/ntc/ReferenceRefusalSuite.scala` 243-279): a scoped
  ledger comparison, not LocalTxSubmission parity. An era reaches parity through an era-specific offline admission
  adapter, an S1 deliverable, that does what the reference's mempool does: the phase-1 measure first, its failure
  refused before any rule (R4, R21); then the era's rules in the mempool's environment, each transaction at index
  `minBound` on the state the accepted transactions before it left (R21); and the refusal written as LocalTxSubmission
  sends it (R2). Until an era's adapter passes that era's refusal corpus, its path-R results are reported as scoped
  ([CNF-2](../design-rules.md#cnf-2)). Path R does not exercise Scarab's running node.
- **Path L** (both live): each probe goes to a Scarab node and a reference node, and both verdicts are read live. It
  is the only path for blocks, for decode failures at the connection and for Scarab's node-to-client stack. Before
  Conway it is incomplete while Scarab's mempool serves Conway only
  ([CNF-2](../design-rules.md#cnf-2); §10, Q8).

**Two clusters.**

- **The shared cluster** is the devnet's (§2.1): Haskell pools forge, Scarab follows. It serves path R, and path L for
  refusals, bracketed as §6 says (`ntc` brackets tips only). Accepted transactions have no independent second verdict
  there (§2.1).
- **The injector cluster** has one reference node and one Scarab node, neither forging, connected to no one but the
  campaign's peer, the injector: a `tools` program (§10, Q5) that holds every pool's keys of a `create-env` genesis,
  forges the chain both nodes follow, and serves it over node-to-node ChainSync and BlockFetch. The injector is the
  only source of blocks, so no tip moves between two probes unless the campaign moves it, a validity probe is
  always an extension of both nodes' selected chain, and so preferable to it (R13), and a transaction accepted by one
  node is never forwarded to the other.

**The verdict** of one node on one probe is a closed type:

- `Accepted`: `MsgAcceptTx`, or the tip is the probe block;
- `Refused(reason)`: `MsgRejectTx` with its raw `ApplyTxErr`, or `InvalidBlock` with the block's error;
- `Ended(cause)`: the connection ended: a decode failure, or a header refused (R9, R11);
- `NoVerdict(why)`: the tip moved during the probe, a mempool snapshot did not match the record, the block was not
  judged (R13), the refusal is timing-dependent (R5), or no settled reading came within the wait.

Two verdicts agree when both are `Accepted`, both `Refused` with equal reasons as §6 compares them, or both `Ended`
with the same cause. A `NoVerdict` on either side is inconclusive, counted, and never agreement
([CNF-2](../design-rules.md#cnf-2)).

**Block probes are of two kinds.** A *validity probe* is the next block on both nodes' selected chain, so chain
selection must judge it (R13); its verdict is of the type above. A *chain-selection probe* serves competing chains
and observes only the tip each node selects, `Selected(point)`; it never claims that a block a node did not select
was validated, since chain selection may store such a block without judging it (R13).

## 5. Inputs

### 5.1 Transactions

Generation is a pure function of the seed and the context: the genesis, the chain, the UTxO the campaign's keys own and
the protocol parameters, all in the record. Each probe spends inputs no pending probe spends, unless it is a sequence
probe built to conflict.

- **Per era**, Shelley to Conway, through path R in the shared cluster; Conway also through path L.
- **Phase 1**: a valid base transaction (payments, multi-assets, certificates, withdrawals, metadata, governance
  procedures, validity intervals, required signers, reference inputs, collateral), then one change per probe.
- **Boundary values**, each with its twin on the other side of the limit: the fee at the minimum and one below; an
  output at its minimum value and one below; the transaction at the maximum size and one above; the validity interval
  starting and ending at the slot after the tip (R3); collateral at its percentage and one below; units at the
  per-transaction maximum and one above; reference scripts at their size limit; deposits and refunds off by one.
- **Malformed encodings**: `Mutate.scala`'s operators (§2.5) on the body, the witnesses and the envelope; non-canonical
  integers and lengths, indefinite forms, duplicate map keys, sets with and without tag 258, trailing bytes, and byte
  strings of the wrong length.
- **Re-signing.** A mutation that changes the body is signed again over the bytes as sent
  ([BYT-1](../design-rules.md#byt-1)), and the script data hash and the auxiliary data hash are recomputed, so that a
  probe reaches the check it targets; a probe that targets a witness or a hash leaves it wrong on purpose.
- **Phase 2**: scripts from WP8's `TermGen` (§2.6) in each purpose (spending, minting, certifying, rewarding, voting,
  proposing), with datums, redeemers, units at and around the budget, each cost model, and both validity flags; a
  failing script with the flag set to false is judged in a block (§5.2), since its mempool verdict is N1.
- **Seeds** from the kept corpora: the refusal corpus, `ntccases`, the upstream conformance transactions and the
  Plutus boundary transactions, mutated as above.

A decode-level probe is judged first by the decoding oracle offline; only one that decodes on both sides, or whose
connection-level outcome is the question, goes to path L.

### 5.2 Blocks

Through path L in the injector cluster only. The injector builds the base chain with `BlockBuilder` and the forger's
signing, in slots its pools lead, each slot started at least the clock skew ago (R11), each block within its
predecessor's forecast range ([leader schedule, C16](leader-schedule.md#29-the-claims)). A probe is the next block,
built and then changed:

- **Headers**: in a TPraos era, the header's protocol version above the node's maximum and at it (R10, R20); in a
  Praos era, the header's declared version changed, a probe of whatever reads it (N14), since the envelope compares
  the ledger view's version, which the campaign does not raise above the node's maximum; the header's size at
  `maxBHSize` and one above; the declared body size at `maxBBSize` and one above (R10); the VRF output, the leader
  proof, the KES period and the operational certificate's counter, each wrong with the rest re-signed; the previous
  hash, the block number and the slot, each wrong.
- **Bodies**: the body hash and the body size disagreeing with the header (R17); the block's units and reference
  script size at their limits and one above; a transaction refused only by MEMPOOL (R6), which a block may carry; a
  failing script with the validity flag false and true; a transaction from the wrong era.
- **Sizes**: the largest body the parameters allow and one byte more.
- **Era transitions**: the first block of the new era, a block of the old era after the boundary (never adopted, R25),
  and a transaction of the old era submitted after it (R2; when the reference answers "wrong era" is N9).
- **Forks**, as chain-selection probes (§4): two chains of different length served one after the other, then of
  equal length (N5), within `k`; what is compared is the tip each node selects.
- **Undecodable blocks**: through a raw-bytes path of the injector, as `FollowerStallSuite`'s garbling wrapper does in
  memory, since `ChainServer` serves only blocks whose header it decodes (`headerOfBlock`, `ChainServer.scala` 221).

A block probe's verdict is read from the tip and the `InvalidBlock` trace, which the reference node is configured to
show as `reference_tracing.py` does; Scarab's side needs a log event that names the refused block in full (§2.10).
After a refused probe the injector serves the base chain on; the nodes' invalid sets keep the probe, so it is never
served again.

### 5.3 The first target: hashes of the wrong length outside delegation certificates

The follow-up on #922 asks for what #927 did not cover: a hash of the wrong length in an address, a witness, a
governance procedure, a required signer and a script hash, and the public Preview block whose certificate carries a
56-byte pool id. It is the campaign's first target because its seeds and oracle already exist
(`delegation_hash_cases.py` in `scripts/oracle/conway-decoding/`, and `DelegationHashesReferenceSuite`).

1. For each place a hash is read in a Conway transaction (payment and stake credentials in outputs and reward
   accounts, policy ids, script hashes in credentials, required signers, datum, script data and auxiliary data
   hashes, voters, committee credentials, governance action ids and anchors, pool registration fields), one case one
   byte short, one byte long and, for a pool id, at 56 bytes, each with a control of the right length.
2. The decoding oracle judges each offline (N7), and Scarab's decoder must match it, as #927's suites do.
3. Each case that decodes on both sides goes through path R and path L.
4. The Preview block is decoded by the oracle and by Scarab. Its ledger verdict needs the Preview state at its
   predecessor, which no devnet holds; until a recorded chain supplies it, the block's ledger verdict is incomplete.

## 6. Comparison

| Compared | How | Exact or projected |
|---|---|---|
| Transaction verdict, path R and L | the reply, and the complete `ApplyTxErr` bytes ([CNF-3](../design-rules.md#cnf-3)) | exact |
| `cardano-cli`'s presentation | the reference image's `cardano-cli` against each socket, as `ntc` compares it | exact where `ntc` covers it (N3) |
| A decode failure at the connection | `Ended` on both, with the cause's class | projected (N2) |
| Block verdict | adopted, or refused at the header or in the body, with the error's constructor path | projected: the reference presents a block's error as `Show` text in its trace (R16) and sends it to no peer (R12, R14), and Scarab's presentation of it is its own; exact once [#796](https://github.com/KD5UJC-XYZ/scarab/issues/796) mirrors it |
| Chain-selection probe | the tip each node selects, never a verdict on a block it did not select | exact |
| Tips | slot and hash, after each block probe and at the end of each sequence | exact |
| Ledger state | same-point `DebugNewEpochState` and `GetUTxOWhole`, and the node-to-client battery (§2.3), at the end of each block sequence and at each epoch boundary | exact |

A projected comparison reports its scope beside its result and never counts as an exact pass
([CNF-2](../design-rules.md#cnf-2)). Reading a refusal's constructor path out of `Show` text is a parser, with its
table ([STR-2](../design-rules.md#str-2)).

**Keeping nondeterminism out.**

- **The judging context.** A transaction's verdict depends on the mempool's own state, not on the wall clock: the
  ledger state of the tip it last synced to, ticked to the slot after (R3), and the transactions it holds, in order
  (R21). A tip read alone does not show it, since the mempool can still judge against the previous tip (R19). Each
  probe is bracketed, on each node that judges it, by the tip and by a LocalTxMonitor snapshot: the snapshot's slot
  is the slot after the tip, and its transactions, read in order, are those the record expects. Both hold before and
  after the submission, or the probe is `NoVerdict`; `judged` checks the slot and the count this way on the reference
  (`scripts/devnet/refusalcorpus.py` 213-218). In the injector cluster nothing moves the tip or the mempools between
  probes, and the check confirms it.
- **A switch between blocks of the same slot.** A snapshot shows the slot after the mempool's tip but not its hash,
  and the mempool resyncs on the hash as well as the slot (R22): after a switch from one block to another of the same
  slot, the tips and the snapshot can read alike while the mempool still judges against the block it left. A local
  ChainSync follower cannot show it, since it does not see every change of the selection (R23). Such a switch always
  rolls back, so each node's own chain-selection log does: the reference traces every switch that rolls back as
  `SwitchedToAFork`, with its new tip, under default options and with no limiter (R24), and Scarab emits `rolledBack`
  when it commits one (`Follower.committing`, `node/src/main/scala/xyz/kd5ujc/scarab/node/Follower.scala` 571-597;
  that no other path changes its selection is an S1 check). A plain extension, which the reference's limiter may drop
  from the log, cannot be such a switch. In the shared cluster, after any switch in a node's log, that node's context
  is unsynchronised until its snapshot's slot has moved past the switch's new tip, which shows a resync to a later
  tip. A probe is judged only once each node's log has been read a fixed margin past the bracket's last read, and a
  probe inside a window is `NoVerdict`. The margin covers the moment between a switch's commit and its log line, which
  the source does not bound (R24): the shared cluster's contexts are trace-checked, not proven, so a disagreement
  found there counts only once it is confirmed in a clean context (§7.3). Exact contexts are the injector cluster's:
  there the injector serves no such switch outside a chain-selection probe, and after one judges no transaction until
  both snapshots have moved past it. S1 tests the rule on recorded logs, among them a log whose extensions the limiter
  dropped, a switch a lagging follower would have missed, and a switch whose line arrives within the margin.
- **Every attempt is recorded**, with its bracket and its reply, including one whose bracket then moved and one whose
  reply never came: an attempt is part of the context whether or not it was judged.
- **Reset and reconstruction.** After a bracket that moved, a reply that never came (a timeout, a lost connection) or
  a block adopted by either node, the context is rebuilt before the next probe. The campaign waits until each
  mempool has synced to its node's tip, and reads its transactions; a transaction whose outcome is unknown counts as
  possibly accepted until a snapshot or the chain shows whether it is held or included, and nothing that depends on
  it is generated meanwhile; path R captures the state and UTxO again, and the record starts a new context.
- **What fresh inputs do not isolate.** Spending inputs no other probe spends keeps out conflicts over the UTxO, not
  over the rest of the state: registrations and their deposits, delegations, reward balances and withdrawals, pool and
  DRep state, governance proposals, votes and deposits, the treasury and the committee, and the parameters, which
  change at an epoch boundary. A probe is judged in the context of every probe accepted before it in its context, in
  order, as the record holds them, and a context ends at an epoch boundary.
- **Time.** Generated work stays far below the reference's validation timeout, and a `MempoolTxTooSlow` refusal is
  `NoVerdict(timing)` (R5; §10, Q7). Block probes use only slots that started at least the clock skew ago (R11).
- **The network.** A verdict is read only once settled: the tip is the probe, its `InvalidBlock` is traced, or the
  connection ended, within a bounded wait. A forgiven invalid tip (R12, R15) is read from the trace, not from a
  disconnect.
- **The order of a sequence.** The first disagreement ends the sequence; a disagreement about the tip ends the run,
  since the two nodes no longer share a context.

## 7. Determinism, minimisation and fixtures

### 7.1 Seeds and the record

A run has one seed, drawn from a source the public cannot see (§8); each probe's seed is derived from it and the
probe's index. Generation is a pure function of the seed and the context, and the run writes, before it submits a
probe, the record: the genesis files, the block bytes the cluster's chain or the injector served, each probe's bytes
and seed, and every attempt with its bracket and its reply. The keys are throwaway devnet keys: they go with the
record to the private store (§8), so that a disagreement can be shrunk again later, and never into the repository
([FMT-3](../design-rules.md#fmt-3)).

### 7.2 Replay

A probe is replayed from the record, not from the seed alone, since a cluster's genesis and keys are fresh each run
(§2.1): the injector serves the recorded chain to two fresh nodes and then the probe; path R replays offline from the
recorded state. The seed reproduces the choices only within the recorded context.

### 7.3 Minimisation

A disagreeing probe is shrunk against both nodes. A candidate is simpler when it has fewer or smaller components
(inputs, outputs, certificates, witnesses, assets, script terms, CBOR items); a structure-level candidate is signed
again as in §5.1. A candidate is kept when §6's comparison still reports a disagreement for it, and the disagreement
keeps its class: the same pair of verdict kinds and the same refusal constructor path on each side. Every block
candidate is a block neither node has judged before, so no node answers it from its invalid set (R14). Shrinking stops
at a fixed point or at its budget, and reports the smallest candidate with the number of steps. A transaction
candidate is judged as any probe: in path R the reference judges it live at its own bracketed state and Scarab replays
it offline. A block candidate needs the injector cluster at the probe's predecessor. Shrinking runs inside the run
that found the disagreement, with its cluster and keys, from a share of the budget reserved for it; a disagreement
left unshrunk is shrunk later from the record and the keys. The smallest candidate is confirmed once more before it is
reported. For a transaction, Scarab's side is replayed offline from the record, and the reference judges it again live
in a new bracketed context; a candidate whose context cannot be made again (it rests on earlier accepted probes that
are now on the chain) is reported unconfirmed until the injector cluster can rebuild it. A block candidate is
confirmed in an injector cluster restored from the recorded chain, with nodes that have judged no earlier candidate.

### 7.4 Kept inputs

A disagreement, once fixed and disclosed (its advisory published), and with the owner's explicit authorization to
disclose it (§8), is kept as a regression input in a new directory of the campaign's own under
[`fixtures/reference/`](../../fixtures/reference/), one subdirectory per input:

- the input's bytes, the reference's verdict as recorded, and the context in the reference's own encoding (state and
  UTxO CBOR as the refusal corpus keeps them, the genesis files, and for a block the chain up to its predecessor);
- a provenance file (reference build and image digest, Scarab commit, the record's hash, the date) and a README, with
  any third-party data in `NOTICE` ([FMT-3](../design-rules.md#fmt-3));
- a Scala suite that replays Scarab's side in `check` against the recorded verdict, as `ReferenceRefusalSuite` does.

The persisted-format surface takes only `fixtures/snapshot-*` and `fixtures/network-identity-*`
(`scripts/check_storage_format.py` 36-37), and the snapshot goldens name `fixtures/eras/genesis` and
`fixtures/preview/genesis`; the campaign writes in none of them and regenerates no native snapshot. **If a kept
input would need a native snapshot fixture, a change to one, or any other entry of [FMT-1](../design-rules.md#fmt-1),
stop and ask: that is a storage format change.**

## 8. Disclosure

The repository, its workflow logs and its artifacts are public. A disagreement in either direction, Scarab accepting
what the reference refuses or refusing what it accepts, can split a node from the network, so every disagreement the
campaign finds is reported privately first, as [SECURITY.md](../../SECURITY.md#reporting) says, including one that
SECURITY.md would let go to a public issue (§10, Q2). Its triage has three outcomes, all private until disclosed:

- **Scarab is wrong**: a Gap; it is fixed through the private advisory's process, and its kept input lands only once
  the fix is disclosed and its disclosure authorized (§7.4).
- **The reference is wrong** (a [REF-2](../design-rules.md#ref-2) defect): Scarab still gives the reference's verdict
  (I1); the maintainers report the defect upstream privately, and nothing of it enters the repository before upstream
  discloses it.
- **The campaign is wrong** (a generator or bracket defect): fixed in the campaign once shown to be the campaign's.

**Discovery runs only in private.** A run that searches for disagreements, a discovery run, executes only on
infrastructure the maintainers control, outside the public repository's Actions (§10, Q3). Everything about its
execution stays there: when it runs and which families and eras it covers, its start, end and durations, its exit
status, its failures, timeouts and cleanup, its logs, its artifacts, its record and the minimiser's trail. Its entry
point refuses to start under the public repository's Actions, and it writes only to its run directory and the private
store. Public CI runs no discovery: it replays kept inputs, each one fixed and disclosed, with its disclosure
authorized (§7.4).

**Nothing that depends on outcomes is published while a finding of its period is private**, and that includes the fact
that a finding exists. A finding is cleared when its advisory is published, when upstream has disclosed it for a
defect of the reference, or when it is closed as no defect. Publication has two stages.

**Before clearance: the announcement.** On a fixed date after each period, whatever its runs found, one announcement
is published, written from a closed type whose only inputs are the period's configuration, fixed before the period
began, and `main`'s public history, from which the commit rule picks the commits:

- the period's first and last dates, and the budget of a run (its wall-time limit, CPUs and memory);
- the reference build, and the digest of every image the runs use (the reference node's, Scarab's JDK image's, each
  oracle's);
- the configuration identifier: the SHA-256 of the campaign's public configuration record, a file in the repository that
  holds the budget, the cells and their quotas, the images and the commit rule, and no seed and no schedule;
- the commits, chosen by the rule fixed beforehand (the head of `main` at a fixed time each night), not by the runs.

It says that the period ran under that configuration, and nothing more: no outcome, no coverage, no run count and no
seed. Whether a run completed, failed or was repeated is an outcome.

**After clearance: the results.** Once every finding of a period is cleared, and the disclosure of each disclosed one
authorised, its results are published: per cell (§9.1) the probes tried, the conclusive verdicts of each class, the
inconclusive attempts and whether the cell met its minimums; the runs and their results; and the seeds. Truthful
coverage stays private until then. A seed is 256 bits from the operating system's random source, and is released only
once every finding derivable from its run is cleared. Results are released in period order, on the first fixed release
date that is a fixed embargo after the period's end and on which that period and every earlier one are cleared.

**The disclosure gate** is part of S1's jump gate and of every later change to the runner, to publication or to the
public configuration record. Its observable channels are the announcements, the results, the evidence rows, the
public configuration record and each of its revisions, the commits of kept inputs, and public CI's replay of them;
on each it checks the values, the cardinality and the timing of what appears, not only its fields' names. It runs the
whole pipeline on pairs of test histories that share their public history (`main` and the configuration record) and
their clock, and differ in private outcomes: no finding, one finding, several, a failed run, a timed-out run, a
cleanup error, a recovery run. Some pairs span a revision of the configuration record, one of them prompted by an
uncleared finding, and the publication of a kept input of an earlier, cleared finding. It has two parts:

- *Before authorised disclosure*, each pair must give byte-identical output on every channel at identical times,
  until one of its findings is cleared and its disclosure authorised.
- *After it*, results rightly differ, so three things are tested apart from byte equality: nothing of a period (its
  results, a seed, a kept input) appears before its clearance and authorisation; once they hold, its results appear
  on the first release date due (§8) and on no other; and what appears equals the private record.

Each test run also leaves nothing outside its run directory and the private store.

Node logs are the subtle leak: the reference's refusal trace carries the transaction and its error (R18; what its log
prints of it is N13), and Scarab's logs may carry its own. They stay on the private runner. Neither `e2e.yml`'s
pattern, which prints a scenario's output and uploads every node log and run summary even when a run fails
(`.github/workflows/e2e.yml` 85-112), nor `faults.yml`'s seed from the public run number is copied where the public
can read it.

The record and the minimal inputs go to the private store, encrypted at rest; the advisory draft holds what its fix
needs.

**Dated evidence rows** follow the two stages (§9.2): on the announcement's date, a row that says the period ran, with
its configuration; after clearance, a row with its results.

## 9. Stages, budget and evidence

### 9.1 Stages

Each stage is one or more pull requests at the level of what it touches; none changes Scarab's behaviour, and a fix
the campaign leads to is its own change at its own level.

| Stage | Delivers | Jump gate |
|---|---|---|
| S0 | This document; its claims checked independently. | The review's check of §3 at the pins, and the owner's answers to §10. |
| S1 | Path R in the shared cluster, on probes from the existing builders (`eraload.py` valid load; `refusalcases.py`, `refusalcoverage.py` refusals): §6's bracketed judge with accepted probes, the record of every attempt, Conway's offline replay through Scarab's node-to-client path, the offline admission adapters of Shelley to Babbage (§4), the minimiser (§7.3), the private runner and store, the announcement and results types and the disclosure gate. Confirms N1, N2, N10, N12, N13 and N15; answers Q10. | The committed refusal corpus judged again, every case conclusive and in agreement, each era before Conway through its adapter; every S1 cell of the coverage matrix (below), the measure and sequence cells replayed offline to the same verdicts; the same-slot rule's tests (§6), and no probe of the run judged inside a window; a negative control (one check loosened on a never-merged branch, as in #927) found, minimised, confirmed and reported privately, with nothing published; the disclosure gate (§8). |
| S2 | Phase-1 generation and mutation per era with re-signing; the decoding oracle as the offline first judge; the first target (§5.3); path L for Conway refusals in the shared cluster; kept inputs and their suite. Confirms N3 and N7. | Every S2 cell of the coverage matrix; every disagreement triaged privately (§8); the first target's cases judged on both paths. |
| S3 | Phase 2: WP8 scripts in every purpose, datums and redeemers, units at the limits, every cost model. | Every S3 cell of the coverage matrix. |
| S4 | The injector and the injector cluster, with a block builder for the eras before Conway: the base chain, header, body, size, protocol-version, era-transition and fork probes, transactions in blocks, the block verdict readers on both sides, the tip and ledger-state comparisons. Confirms N4 to N6, N9, N11, N14 and N16. | A base chain over two epochs and one era transition adopted alike, tips and same-point state equal; every S4 cell of the coverage matrix; S1's negative control repeated for a block rule, minimised and confirmed in a fresh injector cluster. |
| S5 | Nightly discovery on the private runner under §8, with its budget; the announcements, the results after clearance and their dated evidence rows; the compatibility register component for the campaign. | Seven nightly runs within budget, every cell of the coverage matrix met, and no disagreement the triage traced to the campaign. Then #922's acceptance. |

A gate's evidence is an outcome: it is published under §8's clearance rule, never before.

**Coverage matrix.** A cell is what a gate counts: the stage's key (an era, or for S3 a language, a protocol version
and a purpose) and one family. Its classes are the verdicts its family is built to reach: `Accepted`, `Refused`,
`Ended` (§4) and, for a chain-selection family, `Selected`. A probe counts for a class when both nodes give that class
and agree (§4). Each class a cell has needs a nonzero minimum: 20 agreeing verdicts of `Accepted`, `Refused` or
`Ended`, and 10 forks with equal selected tips for `Selected`, unless the table gives another in brackets. S2's
families group §5.1's phase-1 changes, boundary values and malformed encodings. The inconclusive ceiling is 5% of the
cell's attempts, retries included: every attempt counts once, a probe retried after a moved bracket once per attempt,
and an attempt is inconclusive when it ends `NoVerdict`. A cell short of any class's minimum, or over the ceiling, is
`incomplete` ([CNF-2](../design-rules.md#cnf-2)), and a gate does not pass while a cell it names is incomplete. The
minimums and the ceiling are configuration (§10, Q6).

| Stage | Key | Families | Classes |
|---|---|---|---|
| S1 | era, Shelley to Conway | valid load (`eraload.py`) | `Accepted` |
| S1 | era | refusals (`refusalcases.py`, `refusalcoverage.py`) | `Refused` |
| S1 | era | a probe failing the phase-1 measure and another rule | `Refused` (5) |
| S1 | era | a sequence of five accepted probes, then a probe that conflicts with them | `Accepted`, `Refused` (3 sequences) |
| S2 | era | payments and values; certificates; withdrawals; validity intervals; fees and sizes; collateral (Alonzo on); governance (Conway) | `Accepted`, `Refused` |
| S2 | Conway | the first target (§5.3) | `Accepted`, and `Refused` or `Ended` as S2 finds (N2, N7) |
| S2 | era | malformed encodings | `Accepted`, `Refused`, `Ended` |
| S3 | language, protocol version, purpose (V1 and V2: spending, minting, certifying, rewarding; V3 also voting and proposing) | scripts from `TermGen` | `Accepted`, `Refused` |
| S4 | era | header fields (TPraos version, size, declared body size, VRF, leader proof, KES, certificate, previous hash, block number, slot) | `Accepted`, `Ended` |
| S4 | era | bodies and sizes; transactions in blocks | `Accepted`, `Refused` |
| S4 | era boundary | the new era's first block, valid | `Accepted` (3) |
| S4 | era boundary | a block of the old era after the boundary, which the reference never adopts (R25) | `Refused` or `Ended`, as N16 finds (3) |
| S4 | era | a Praos header's declared version | the classes S4 finds (N14), each with its minimum |
| S4 | era | undecodable blocks | `Ended` (N6) |
| S4 | era | forks of different and of equal length | `Selected` |

### 9.2 Budget and the evidence row

Once S5 is stable, discovery runs nightly on the private runner, one job of at most 90 minutes on 4 CPUs (the limit
`e2e.yml` and `fuzz.yml` set), at least 60 of them probing. A private schedule, published nowhere, rotates the
families and eras so that every cell (§9.1) meets its fixed quota in every period, with slack for the runs a finding
cuts short; a weekly run is long enough to cross an epoch boundary and an era transition. The budget, the period, the
quotas and the embargo are configuration, in the public configuration record (§8); a change to them is a change to
this section. The record changes only at a period's start, with that period's announcement. A change prompted by a
finding not yet cleared (a family set aside, a quota moved) applies only privately until the finding is cleared and
authorised for disclosure, and shows in the period's results; so the record's revisions are the same whether or not a
finding prompted one.

The evidence rows follow [How evidence is recorded](../../CONTRIBUTING.md#how-evidence-is-recorded) and the [evidence
log](../compatibility.md#evidence-log), in §8's two stages. The first, on the announcement's date, is the
announcement: the command (the campaign's entry point with the configuration identifier, and no seed), the period, the
commits, the budget and the image digests, and that the period ran. The second, after clearance, adds the results: per
cell the probes, the conclusive verdicts of each class, the inconclusive attempts and whether it met its minimums; the
runs; the seeds. Raw run artifacts are not published.

## 10. Risks and open questions

**Risks.**

- **False disagreements** from the harness's own nondeterminism cost triage and could be filed wrongly. S1's
  calibration on the refusal corpus, and the brackets, come before any search.
- **A leak** through a log, an artifact, a seed, a count or a run's timing would publish a vulnerability, or that one
  exists, before its advisory. Private execution, an announcement that reads only configuration, results held until
  clearance and the disclosure gate address it; a reviewer checks every change to the runner or to publication against
  §8.
- **A clearance that overruns the embargo.** Results wait for clearance (§8), so a finding still private on its
  period's release date would hold that period's results, and every later period's, past the date. The embargo is
  set longer than an advisory is expected to take, so that triage clears a period before its date; what to do when it
  is not is Q11.
- **Shared bugs.** The injector builds blocks with Scarab's own builder and signing. The reference judges every block
  it builds, so a builder bug shows as a disagreement about the base chain, which S4's gate requires to be absent.
- **Coverage.** Random search with no coverage feedback finds what it happens to reach; a night with no disagreement is
  no evidence about other inputs ([CNF-2](../design-rules.md#cnf-2)).
- **Cost.** Start-up and an era walk take much of a night; the injector cluster, starting in its genesis's era, is
  cheaper.
- **Reconnection.** A refused block ends the injector's connection to the reference node unless it is a forgiven tip
  (R12), and the reconnection delay (N4) bounds the block probes per night.

**Open questions for the owner**, each with a recommendation.

- **Q1. Which reference build does the campaign judge against?** Recommendation: the build `--reference-build` selects
  by default (on 2026-11-16, `11.1`, cardano-node 11.1.3); a second reference node of the `11.2` build once its stages
  land and Scarab starts with it, with §3 read again at its pins (N8).
- **Q2. Is every disagreement reported privately first, including one that SECURITY.md would let go to a public
  issue?** Recommendation: yes; any exception is the owner's, case by case.
- **Q3. Where does the private runner live?** Discovery never runs in the public repository's Actions (§8).
  Recommendation: a scheduled job on a machine the maintainers control, which checks out a commit of `main`, writes
  only to its run directory and the private store, and publishes nothing itself; a maintainer publishes each period's
  announcement and, after clearance, its results. Not a self-hosted runner attached to the public repository: GitHub
  advises against one there, where a fork's pull request can run code on it (not checked here). The alternative is the
  CI of a private repository that mirrors `main`.
- **Q4.** Withdrawn: discovery no longer runs in public CI (§8), so whether a public run turns red no longer arises;
  the period is part of Q6.
- **Q5. Where does the injector live?** Recommendation: a `tools` program ([TOOL-1](../design-rules.md#tool-1)) that
  reuses Scarab's node-to-node servers, `BlockBuilder` and the forger's signing, with a raw-bytes path for blocks Scarab
  cannot decode; not a Python reimplementation of the mini-protocols.
- **Q6. What are the nightly budget, the period and its quotas, the coverage matrix's minimums and the inconclusive
  ceiling?** Recommendation: §9.2's budget, one 90-minute job with a weekly longer run; a calendar month as the
  period, its announcement published on a fixed day of the next, and an embargo of 90 days before its results may be
  released; and §9.1's minimums, 20 agreeing verdicts per class and cell (10 forks for `Selected`, and the smaller
  minimums the matrix gives in brackets), with at most 5% of attempts inconclusive. These numbers are this document's
  proposal.
- **Q7. How are timing-dependent refusals handled?** The reference refuses a transaction that takes too long to
  validate, with a text that holds the duration (R5); Scarab has no such timeout (#340), and the text cannot be
  compared exactly. Recommendation: the campaign keeps generated work far below the limit, counts such a refusal as
  `NoVerdict(timing)`, and leaves the difference itself to #340; whether the variable text is a category the owner
  closes is the owner's, as [ADR 0001's
  amendment](../adr/0001-haskell-node-as-reference.md#amendment-2026-11-04-exact-diagnostic-and-refusal-parity) says.
- **Q8. How are eras before Conway compared live?** Recommendation: through path R, scoped until each era's offline
  admission adapter passes (§4), and path L reported incomplete before Conway; the campaign asks for no change to
  Scarab's mempool.
- **Q9. What does a kept input hold when its context is long?** Recommendation: the reference-format state, UTxO and
  genesis, as the refusal corpus keeps them (§7.4); never a native snapshot.
- **Q10. Must a running Scarab node judge accepted transactions before the injector exists?** Path R judges them
  offline until S4, since a cluster forwards Scarab's accepted transactions to the reference (§2.1); the issue's
  "submitted to both nodes" holds for them only from S4. Recommendation: S1 finds out whether submitting to the
  reference first and to Scarab second gives two independent verdicts (it does if the reference never offers its
  mempool to the follower, not checked); if so, use it from S2.
- **Q11. What happens when a clearance overruns the embargo?** A period's results wait until its findings are
  cleared (§8). If its release date passes first, the missing release shows that some finding is still pending: its
  existence, though not its family or input. No outcome-independent schedule removes this while clearance can take
  arbitrarily long. Recommendation: accept that residual, with the embargo (Q6) set well beyond the usual time to an
  advisory, and hold every later period's results with it, so the gap shows only that something is pending. The
  stricter alternative publishes no per-period results at all, only cumulative ones at the owner's discretion with no
  schedule, at the cost of the issue's dated result rows.

## 11. What does not change

No code of the node, no persisted format, no workflow and no fixture changes with this document. The campaign's code
lives in `scripts/devnet/`, `scripts/oracle/` and `tools`, and its kept inputs in a directory of their own.
