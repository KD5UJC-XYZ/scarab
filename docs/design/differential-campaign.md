<!-- SPDX-License-Identifier: Apache-2.0 -->
# A differential campaign against the reference

**Status**: Proposal, 2026-11-16. Not implemented. Its author read every claim about the reference at the pins below;
the independent check of those claims that [CONTRIBUTING](../../CONTRIBUTING.md#levels) asks for before approval is
pending. **No persisted format changes**: a kept input lives in a directory of its own, off the persisted-format
surface (§7.4, [FMT-1](../design-rules.md#fmt-1)). Tracking: [#922](https://github.com/KD5UJC-XYZ/scarab/issues/922).

Pins. The claims cite the `11.1` reference build, the default (ADR 0001, [amendment of
2026-11-14](../adr/0001-haskell-node-as-reference.md#amendment-2026-11-14-the-newest-release-re-pinned-in-stages)):
cardano-node 11.1.3; ouroboros-consensus 82ecba32; ouroboros-network c45735a5 (ouroboros-network 1.2.0.0);
cardano-ledger f649f975, and for the files of cardano-ledger-shelley 1.19.0.1 cardano-ledger 4c81e909 (the files cited
here are identical at f649f975). They were read in checkouts of those revisions, 4c81e909 from its git objects.
Nothing was read at the `11.2` build's pins (ouroboros-consensus 808e4005, ouroboros-network 4646039f, cardano-ledger
29a7d656): there every claim is not checked (§10, Q1). [`docs/data/reference-pins.json`](../data/reference-pins.json)
has every pin. Paths are abbreviated: consensus files from `Ouroboros/Consensus/` in their package
(`ouroboros-consensus`, its `-cardano` `shelley` sublibrary, `-diffusion`), network files from `Ouroboros/Network/`
(the `protocols` sublibrary and the main library), ledger files from `Cardano/Ledger/` in their era's package, and
cardano-node files from `cardano-node/src/Cardano/Node/`. In-tree code is cited by path, symbol and line at base
`03c46cd89`.

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
  `cardano-reference-11.1.3:local` (`scripts/devnet/util.py` 17; `start_reference`, `scripts/devnet/cluster.py`
  25-36). `up` makes three block-producing pools with `cardano-testnet create-env` (`cluster.py` 142-143); the walk
  from Byron to Conway is `ERA_PLAN` (`cluster.py` 176-177). `create-env` makes fresh keys and a genesis on every
  run (the refusal corpus's recorded Shelley genesis files share no genesis delegate and differ in their system
  start), so no seed reproduces a transaction's bytes: a run's record must hold the bytes and the genesis, as the
  refusal corpus does (§2.2).
- **At most one Scarab node** runs per cluster, `<name>-scala`, in the reference container's network namespace
  (`scala_container`, `cluster.py` 625-647; `SINGLE_FOLLOWER`, `util.py` 28-62). In `follow`, `soak`, `e2e` and `ntc`
  it follows reference node 1 (`scripts/devnet/follower.py` 30-33); in `pool` it forges in place of one Haskell pool
  (`scripts/devnet/scenarios/pool.py` 586-608).
- **Transactions** reach a node through `cardano-cli` in the reference container (`scripts/devnet/load.py` 132-139;
  `EraLoad.submit`, `scripts/devnet/eraload.py` 296-327) or raw over node-to-client LocalTxSubmission
  (`LocalStateQuery.submit`, `scripts/devnet/ntcquery.py` 465-476), which returns the raw `ApplyTxErr` of a refusal.
  `scripts/devnet/eratx.py` builds and signs a transaction of any Shelley-based era with its own Ed25519 (`build_tx`,
  361-366; `envelope`, 448).
- **One transaction to both nodes** happens in two places only. `ntc`'s `submissions` sends each of a fixed list of
  refused Conway transactions (`scripts/devnet/ntccases.py` 127-227) raw and through `cardano-cli` to reference node 1
  and to Scarab, retrying until one tip brackets all four submissions on both nodes, and requires equal `ApplyTxErr`
  bytes and equal printed text (`scripts/devnet/scenarios/ntc.py` 348-380, `judge_refusal` 322-345). The `pool`
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
for transactions in every era, path R in §4.

### 2.3 Tips and ledger state

A tip is read with `cardano-cli query tip` or LocalStateQuery `GetChainPoint` (`point_of`, `ntc.py` 154-166) and from
Scarab's `follower.jsonl` events or query port. Each scenario compares tips its own way (`ntc.settle`, `ntc.py`
183-191; `pool.settled_tips`, `pool.py` 426-435); no tip-equality assertion was found in `e2e`. The exact comparison
of ledger state is `e2e`'s same-point `DebugNewEpochState` and `GetUTxOWhole` (`scripts/devnet/compare.py` 377-380,
463-602; `scripts/devnet/ledgercompare.py` 49-96) and the same-point node-to-client battery
(`scripts/devnet/ntceras.py`). Missing: one primitive that brackets a probe with both nodes' tips.

### 2.4 Seeds

The only seeded devnet run is the fault schedule: `faultplan.generate` is a pure function of the seed and the
cluster's parameters, written to `fault-schedule.json` before the run, and replayed by seed or by file
(`scripts/devnet/faultplan.py` 121-150, 157-162; `scripts/devnet/scenarios/faults.py` 242-250, 323-327). The replay
repeats the schedule, not the execution ([adversarial schedules](adversarial-schedules.md)). The nightly seed is the
workflow's run number (`.github/workflows/faults.yml` 51), which is public. `e2e.yml` passes no seed.

### 2.5 The fuzz project (K3)

`Targets.all` (`fuzz/src/test/scala/xyz/kd5ujc/scarab/fuzz/Targets.scala` 2453-2465) registers the decoder targets:
per era `tx-body-<era>`, `tx-witnesses-<era>`, `tx-whole-<era>` and `block-header-<era>` (1284-1319), `tx-submitted`
(`MempoolState.parse`, 1125-1129), `local-tx-submission` (842-862), `block` (1011-1022) and the mini-protocol codecs.
Seeds are the committed blocks and their parts, the early-era transaction seeds and valid CBOR sampled from the pinned
CDDL (`Corpus.scala` 19-32, 216-273). `Mutate.scala` has byte-level CBOR operators (70-165), one to four per input
(167-180); nothing is re-signed or re-hashed. A run is deterministic per target, seed and case, and a failure prints
its reproduction (`Harness.scala` 123-141). The properties are Scarab's own: no exception, time and allocation bounds,
round trips; nothing is compared with the reference ([fuzzing](../running/fuzzing.md)). It gives the campaign
decoder seeds, a mutator, deterministic replay and a shrinker; it gives no verdict of the reference.

### 2.6 The Plutus generators (#43 WP8)

`TermGen` generates UPLC programs per language and protocol profile, with `Data` arguments and a budget, as a function
of a SplitMix64 seed (`uplc/src/test/scala/xyz/kd5ujc/scarab/uplc/oracle/TermGen.scala` 19-41; `SplitRng.scala` 6-9).
`GeneratedOracleCorpusMain` in `tools` writes requests; `scripts/oracle/plutus/generated/run.py` has the pinned
evaluator judge them offline in Docker (46-60) and writes verdicts with their provenance (94-134);
`GeneratedOracleSuite` replays them through `LedgerEval.evaluateSerialised` and requires the same admission, error
class, budget and logs
([`fixtures/reference/uplc-generated/README.md`](../../fixtures/reference/uplc-generated/README.md)). It gives the
campaign scripts, and the generate, judge offline, replay exactly shape. It has no script context and no transaction
around the script.

### 2.7 The reference oracles

`scripts/oracle/` holds offline harnesses built from released packages or pinned checkouts, run with no network. The
decoding oracles (`conway-decoding`, `header-decoding`) take a JSON file of encodings and give each one's verdict and
complete failure; they run no ledger rule ([the playbook](../playbooks/confirm-with-the-decoding-oracle.md)). The
others judge one predicate, one API or one rule (`dijkstra-rules`, Dijkstra only); none was found that applies LEDGER
or BBODY of Shelley to Conway to a given state and transaction. Only a running reference node judges a whole
transaction, both phases, against a state: the refusal corpus (§2.2), `ntc` and `pool --plutus-load`.

### 2.8 The conformance runners

`scripts/conformance/` runs the node-diversity conformance report's scenarios, whose ledger fixtures carry their
dataset's own expectations, Conway only, and no Haskell verdict
(`tools/src/main/scala/xyz/kd5ujc/scarab/ledger/LedgerRulesFixture.scala`). The upstream conformance adapters replay
committed outputs of the reference's own generators ([upstream conformance](upstream-conformance.md)). Both are
sources of seed transactions with a state; neither judges a new input.

### 2.9 Builders of blocks, and serving them

- `ForgedChain` runs the production `Forger` per slot over an in-memory Conway genesis with test keys
  (`node/src/test/scala/xyz/kd5ujc/scarab/node/forge/ForgedChain.scala` 142-150, 392-405); its blocks do not belong to
  any devnet's genesis. `BlockBuilder` assembles a header body and a Conway block as pure functions
  (`node/src/main/scala/xyz/kd5ujc/scarab/node/forge/BlockBuilder.scala`, `headerBody` 265, `block` 293-297); no
  builder writes a block of an earlier era, and the KES signature is made only in `Forger`.
- The devnet's pool keys are known to the harness: `faults.write_keys` copies a pool's VRF and KES keys and its
  certificate from the `create-env` directory (`faults.py` 231-239).
- `ChainServer` serves only what the node itself validated
  (`node/src/main/scala/xyz/kd5ujc/scarab/node/relay/ChainServer.scala` 20-23). In tests, `ServedChain` feeds raw
  blocks to it and `InMemoryPeers` serves them over in-memory bearers, Scarab to Scarab
  (`node/src/test/scala/xyz/kd5ujc/scarab/node/ServedChain.scala`; `InMemoryPeers.scala`).
- `scripts/devnet/lagproxy.py` forwards bytes unchanged, with delays (21-35); nothing in `scripts/` serves ChainSync
  or BlockFetch. The only blocks that reach a Haskell node from a source the harness runs are Scarab's own, honestly
  forged (`pool`, `relay`).

### 2.10 What is missing

| Missing | Needed for |
|---|---|
| Generators that keep a transaction valid over a state both nodes share, and a structure-aware mutator that re-signs and re-hashes | every transaction probe past the witness checks |
| A bracketed submission primitive that records both verdicts, for accepted transactions too | path L (§4) |
| A node-to-node peer the campaign controls, which serves a forged or mutated chain to a Haskell node and to Scarab, and builds blocks of the eras before Conway | every block probe; the era-transition probes |
| A reader of each node's verdict on a block: the reference's `InvalidBlock` trace is silent by default (`scripts/devnet/reference_tracing.py` 3-16); no Scarab event was found that names a refused block's full hash, and only `scarab_validation_errors_total` counts refused bodies (`MeteredRules`) | block verdicts |
| A seeded run record, a minimiser against both nodes, a private runner and store, and a published summary that cannot carry an input | §7, §8 |

## 3. What the reference does

All of §3 is derived from source, not from a capture. §3.5 lists the claims with their pins.

### 3.1 A transaction from a local client

The LocalTxSubmission server adds the transaction to the mempool and answers `SubmitSuccess`, or `SubmitFail` with
the mempool's `ApplyTxErr` (R1). For the Cardano block that error is the hard-fork wrapper: an error from one era, or
"wrong era"; in a Shelley-based era it is the ledger's `ApplyTxError`, in Conway a non-empty list of
`ConwayLedgerPredFailure` (R2). The mempool judges against the ledger state at its tip ticked to the slot after the
tip, not to the wall clock (R3), with every transaction at index `minBound` (R21). It first measures the transaction
against the per-transaction limits (its size, and from Alonzo on its units) and refuses at once one that fails; only
then does it wait until the transaction fits, and then validate it (R4, R21). The mempool follows the chain through a
background thread, so just after a block it can still judge against the previous tip (R19). In Conway the MEMPOOL
rule refuses, before LEDGER, a transaction whose inputs are all spent and, below a protocol version its comment
names as 11, votes by unelected committee members (R6): a mempool verdict can differ from the verdict on the same
transaction in a block.

Validation is timed. With the node's default limits, a Conway transaction whose validation finishes after more than
1 s is refused, whoever submitted it, with an error whose text holds the measured duration and the transaction id; a
validation cut off at 1.5 s is refused the same way for a local client and raises an exception for a remote peer.
Shelley to Babbage, and Dijkstra, have no such error: there a validation that finishes late is judged as any other,
and one cut off raises an exception for a local client too (R5). That the node hands these limits to the mempool is
not checked (N12). Such a verdict depends on the machine's speed. Scarab's mempool has no such timeout
([metrics](../running/metrics.md); [#340](https://github.com/KD5UJC-XYZ/scarab/issues/340)).

The transaction is decoded inside the message decoder (R7): a transaction the reference cannot decode gets no
`SubmitFail`. That the connection then ends is not checked (N2).

### 3.2 A transaction from a peer

The TxSubmission2 inbound side (logic V1) adds what it fetched to the mempool, traces and counts the refused ones,
and sends nothing back (R8). A peer learns no verdict; the campaign reads transaction verdicts from LocalTxSubmission
only.

### 3.3 A header from a peer

The ChainSync client validates each header and, on failure, ends the connection with `HeaderError` (R9). Validation
includes the envelope: a protocol version against the node's maximum, the header's size against `maxBHSize` and the
declared body size against `maxBBSize` (R10). The version is the header's own in the TPraos eras (Shelley to Alonzo)
and the ledger view's in the Praos eras (Babbage on) (R10, R20), so in a Praos era the envelope does not read the
version a header declares; what else reads it is N14. A header whose slot starts more than the clock skew (2 s by default)
after its arrival ends the connection; one within the skew is held until its slot starts (R11).

### 3.4 A block from a peer

A fetched block goes to chain selection with a punishment for its sender: an invalid predecessor always ends the
connection; an invalid block itself does too, unless diffusion pipelining is on and its header meets the
tentative-header criterion, when it is forgiven (R12). Chain selection validates a block only as part of a candidate
that is preferable to the current chain; otherwise the block is stored and nothing is judged (R13). A block that
fails is traced `InvalidBlock`, recorded with its `ExtValidationError` (ledger or header), and its sender punished;
a block already recorded invalid is ignored and its sender punished (R14). A ChainSync client whose candidate holds a
known-invalid block ends the connection, skipping the candidate's tip under pipelining, though it can miss one when the
candidate changes meanwhile (R15). The node traces
`ChainDB.AddBlockEvent.AddBlockValidation.InvalidBlock` at Error, with the point and the error's `Show` text (R16).
The body checks of BBODY compare the body's size and hash with the header's, and add the units of the block (Alonzo)
and the size of its reference scripts (Conway) (R17).

So a block reaches a verdict only on a chain preferable to the node's own, its header must pass header validation and
the in-future check, and an invalid tip can be forgiven without a disconnect. A block probe therefore needs a peer
that serves the only chain the node knows, signs headers with a pool's keys in slots that pool leads, forges only in
slots that have started, and reads the verdict from the tip and the `InvalidBlock` trace, never from a disconnect.

### 3.5 The claims

| ID | Claim | Pin, file and lines | Checked |
|---|---|---|---|
| R1 | LocalTxSubmission's server answers `SubmitSuccess`, or `SubmitFail` with the mempool's `ApplyTxErr`. | consensus 82ecba32 `MiniProtocol/LocalTxSubmission/Server.hs` 26-38 | yes |
| R2 | `HardForkApplyTxErr` is `HardForkApplyTxErrFromEra` or `HardForkApplyTxErrWrongEra`; a Shelley-based era's `ApplyTxErr` is the ledger's `ApplyTxError`; Conway's is `NonEmpty (ConwayLedgerPredFailure ConwayEra)`. | consensus 82ecba32 `HardFork/Combinator/Mempool.hs` 61-66, `shelley` `Shelley/Ledger/Mempool.hs` 154; ledger f649f975 `eras/conway/impl/src/Cardano/Ledger/Conway.hs` 51, 4c81e909 `eras/shelley/impl/src/Cardano/Ledger/Shelley/API/Mempool.hs` 181 | yes |
| R3 | The mempool ticks the tip's ledger state to the slot after the tip (`ForgeInUnknownSlot`); at Origin, to the first possible slot. | consensus 82ecba32 `Mempool/Impl/Common.hs` 295, 319-338 | yes |
| R4 | `addTx` first measures the transaction (`txMeasurePhase1`) and refuses at once one whose measure fails; then, if it does not fit, it waits for the mempool's size to change and tries again; only then does it validate. | consensus 82ecba32 `Mempool/API.hs` 142-145; `Mempool/Update.hs` 198-235 | yes |
| R5 | Defaults soft 1 s, hard 1.5 s, capacity 5 s. Where the era has the error, a validation that finishes over the soft limit is refused, whoever submitted it, as `ConwayMempoolFailure` with the text `"MempoolTxTooSlow (<duration>) <txid>"`, and one cut off at the hard limit is refused so for a local client; otherwise a cut-off raises `MkExnMempoolTimeout`. Only Conway has the error: Shelley to Babbage and Dijkstra have none. | node 11.1.3 `Configuration/POM.hs` 889-897; consensus 82ecba32 `Mempool/Update.hs` 127-130, 237-296, `shelley` `Shelley/Eras.hs` 169-220 | yes, except that the node passes the limits to the mempool (N12) |
| R6 | Conway's MEMPOOL rule refuses all-inputs-spent transactions and, unless `hardforkConwayDisallowUnelectedCommitteeFromVoting` holds, votes by unelected committee members, then runs LEDGER. | ledger f649f975 `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Mempool.hs` 103-139 | yes, except the predicate's definition: protocol version 11 is from the comment at 124-125 |
| R7 | The LocalTxSubmission codec decodes the transaction inside `MsgSubmitTx`'s decoder. | network c45735a5 `Protocol/LocalTxSubmission/Codec.hs` 148-169 | yes; what follows a failed decode is N2 |
| R8 | TxSubmission2 inbound V1 traces and counts refused transactions and replies nothing. | network c45735a5 `TxSubmission/Inbound/V1.hs` 313-329 | yes; that V1 is the default is N10 |
| R9 | The ChainSync client ends the connection with `HeaderError` when `validateHeader` fails; that exception brings down every mini-protocol with the peer. | consensus 82ecba32 `MiniProtocol/ChainSync/Client.hs` 1893-1925, 2262-2266 | yes |
| R10 | `validateHeader` runs `validateEnvelope`, which runs the Shelley-based envelope check: a protocol version against the node's maximum, the header size, the declared body size. Under Praos the version is the ledger view's (`plvProtocolVersion`) and `maxBHSize` is the ledger view's; under TPraos the version is the header's (`bprotver`). | consensus 82ecba32 `HeaderValidation.hs` 376, 508-514; `shelley` `Shelley/Ledger/Ledger.hs` 788-789, `Shelley/Protocol/Praos.hs` 59-72, `Shelley/Protocol/TPraos.hs` 66-77, `Shelley/Protocol/EnvelopeChecks.hs` 60-91 | yes |
| R11 | A header more than the clock skew early ends the connection (`InFutureHeaderExceedsClockSkew`); within the skew the client waits for the slot's onset; the default skew is 2 s. | consensus 82ecba32 `MiniProtocol/ChainSync/Client/InFutureCheck.hs` 154-175, 206-207; `Client.hs` 1755-1776; diffusion `Node.hs` 1108 | yes |
| R12 | A fetched block goes to `addBlockAsync`; an invalid prefix ends the connection; an invalid block itself does unless pipelining is on and the tentative-header state forgives it. | consensus 82ecba32 `MiniProtocol/BlockFetch/ClientInterface.hs` 176-227; `Storage/ChainDB/API/Types/InvalidBlockPunishment.hs` 49-92 | yes |
| R13 | Only candidates through the block that are preferable to the current chain are validated; otherwise `StoreButDontChange`. | consensus 82ecba32 `Storage/ChainDB/Impl/ChainSel.hs` 660-684, 699-778 | yes |
| R14 | A failed candidate block is traced `InvalidBlock`, added to the invalid set and its sender punished; a known-invalid block is ignored and its sender punished. `ExtValidationError` is a ledger or a header error. | consensus 82ecba32 `ChainSel.hs` 414-444, 1311-1347, 1359-1365; `Ledger/Extended.hs` 58-60; `HeaderValidation.hs` 435-439 | yes |
| R15 | The ChainSync client ends a connection whose candidate holds a known-invalid block, skipping the tip under pipelining. Its comments say the check can miss a block when the candidate changes meanwhile; it is no guarantee of detection. | consensus 82ecba32 `Client.hs` 1642-1663, 2085-2156 | yes |
| R16 | cardano-node traces `InvalidBlock` with `kind`, `block` and `error` (`showT`), at Error. | node 11.1.3 `Tracing/Tracers/ChainDB.hs` 1307-1310, 1329-1334 | yes; that the default options hide it is N11 |
| R17 | BBODY: `WrongBlockBodySizeBBODY`, `InvalidBodyHashBBODY` (Shelley); `TooManyExUnits` (Alonzo); `BodyRefScriptsSizeTooBig` (Conway). | ledger 4c81e909 `eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Bbody.hs` 96-101; f649f975 `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Rules/Bbody.hs` 63, `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Bbody.hs` 103 | yes |
| R18 | The mempool's refusal trace, `TraceMempoolRejectedTx`, carries the transaction and its error. | consensus 82ecba32 `Mempool/Update.hs` 215-220, 266-275 | yes; what the node's log prints of it is N13 |
| R19 | The mempool re-syncs with the ledger in a background watcher, apart from chain selection: its state can lag the selected chain. | consensus 82ecba32 `Mempool/Init.hs` 66-77; `Mempool/Update.hs` 558-575 | yes |
| R20 | Shelley, Allegra, Mary and Alonzo blocks are TPraos blocks; Babbage, Conway and Dijkstra blocks are Praos blocks. | consensus 82ecba32 `-cardano` `Cardano/Block.hs` 250-256 | yes |
| R21 | A Shelley-based transaction is applied with the ledger's mempool environment: the slot the mempool ticked to and the index `minBound`, on the state left by the transactions before it. Its phase-1 measure is its size from Shelley to Mary, and size and units (`txMeasureAlonzo`) from Alonzo on. | consensus 82ecba32 `shelley` `Shelley/Ledger/Mempool.hs` 301-323, 522-542, 668-676, 752-770; ledger 4c81e909 `eras/shelley/impl/src/Cardano/Ledger/Shelley/API/Mempool.hs` 368-377 | yes |

### 3.6 Not checked

Each is confirmed, by reading the pinned source or by a run, in the stage named (§9), before the campaign relies on
it.

- **N1** (S1). What LocalTxSubmission answers for a transaction whose validity flag says its scripts fail.
- **N2** (S1). That a LocalTxSubmission message whose transaction fails to decode ends the connection on both nodes,
  and how each node reports it.
- **N3** (S2). What `cardano-cli` prints for a refusal, beyond what `ntc` already compares.
- **N4** (S4). That both nodes adopt and validate a chain whose slots lie far behind the wall clock, and how soon each
  reconnects to a local root it disconnected for an invalid block.
- **N5** (S4). Chain selection between forks of equal length.
- **N6** (S4). BlockFetch's check of a fetched body against its header, and a block that fails to decode.
- **N7** (S2). How the reference decodes a hash of the wrong length outside delegation certificates (§5.3).
- **N8** (before the campaign judges against the `11.2` build, Q1). Everything in §3 at that build's pins.
- **N9** (S4). When the hard-fork mempool answers `HardForkApplyTxErrWrongEra`; R2 shows only that it exists.
- **N10** (S1). That TxSubmission's V1 logic is the 11.1.3 default (R8), as [metrics](../running/metrics.md) says.
- **N11** (S4). That 11.1.3's default trace options hide `InvalidBlock` (R16), as `scripts/devnet/reference_tracing.py`
  3-16 says, and what its log prints of a refused block.
- **N12** (S1). That the node passes its mempool time limits to the mempool (R5).
- **N13** (S1). What the node's log prints of a refused transaction (R18).
- **N14** (S4). What, if anything, reads the protocol version a Praos header declares (R10).

## 4. The shape of the campaign

**Two judging paths.**

- **Path R** (the reference live, Scarab offline): the refusal corpus's method (§2.2), extended to generated inputs
  and to accepted ones. The reference judges each probe over LocalTxSubmission at a bracketed tip; the record holds the
  state, the UTxO, the genesis, the probe and every earlier probe accepted since the state was captured, in order.
  Scarab replays offline: the earlier accepted probes into its mempool, then the probe. It covers every era from
  Shelley to Conway, and it is exact and deterministic. It does not exercise Scarab's running node.
- **Path L** (both live): each probe goes to a Scarab node and a reference node, and both verdicts are read live. It
  is the only path for blocks, for decode failures at the connection and for Scarab's node-to-client stack. Before
  Conway it is incomplete while Scarab's mempool serves Conway only
  ([CNF-2](../design-rules.md#cnf-2); §10, Q8).

**Two clusters.**

- **The shared cluster** is today's: Haskell pools forge, Scarab follows. It serves path R, and path L for refusals,
  bracketed as `ntc` brackets them. Accepted transactions have no independent second verdict there (§2.1).
- **The injector cluster** has one reference node and one Scarab node, neither forging, connected to no one but the
  campaign's peer, the injector: a `tools` program (§10, Q5) that holds every pool's keys of a `create-env` genesis,
  forges the chain both nodes follow, and serves it over node-to-node ChainSync and BlockFetch. The injector is the
  only source of blocks, so no tip moves between two probes unless the campaign moves it, a probe block is always on
  a chain preferable to both nodes' own (R13), and a transaction accepted by one node is never forwarded to the other.

**The verdict** of one node on one probe is a closed type:

- `Accepted`: `MsgAcceptTx`, or the tip is the probe block;
- `Refused(reason)`: `MsgRejectTx` with its raw `ApplyTxErr`, or `InvalidBlock` with the block's error;
- `Ended(cause)`: the connection ended: a decode failure, or a header refused (R9, R11);
- `NoVerdict(why)`: the tip moved during the probe, the block was not judged (R13), the refusal is
  timing-dependent (R5), or no settled reading came within the wait.

Two verdicts agree when both are `Accepted`, both `Refused` with equal reasons as §6 compares them, or both `Ended`
with the same cause. A `NoVerdict` on either side is inconclusive, counted, and never agreement
([CNF-2](../design-rules.md#cnf-2)).

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

A decode-level probe is judged first by the decoding oracle offline, at no node's cost; only one that decodes on both
sides, or whose connection-level outcome is the question, goes to path L.

### 5.2 Blocks

Through path L in the injector cluster only. The injector builds the base chain with `BlockBuilder` and the forger's
signing, in slots its pools lead, each slot started at least the clock skew ago (R11), each block within its
predecessor's forecast range ([leader schedule, C16](leader-schedule.md#29-the-claims)). A probe is the next block,
built and then changed:

- **Headers**: in a TPraos era, the header's protocol version above the node's maximum and at it (R10, R20); in a
  Praos era, the header's declared version changed, a probe of whatever reads it (N14), since the envelope compares
  the ledger view's version, which the campaign does not raise above the node's maximum; the header's size at
  `maxBHSize` and one above; the declared body size at `maxBBSize` and one above (R10); the VRF output, the leader proof, the KES period and the
  operational certificate's counter, each wrong with the rest re-signed; the previous hash, the block number and the
  slot, each wrong.
- **Bodies**: the body hash and the body size disagreeing with the header (R17); the block's units and reference
  script size at their limits and one above; a transaction refused only by MEMPOOL (R6), which a block may carry; a
  failing script with the validity flag false and true; a transaction from the wrong era.
- **Sizes**: the largest body the parameters allow and one byte more.
- **Era transitions**: the first block of the new era, a block of the old era after the boundary, and a transaction of
  the old era submitted after it (R2; when the reference answers "wrong era" is N9).
- **Forks**: two chains of different length served one after the other, then of equal length (N5), within `k`.
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
| Tips | slot and hash, after each block probe and at the end of each sequence | exact |
| Ledger state | same-point `DebugNewEpochState` and `GetUTxOWhole`, and the node-to-client battery (§2.3), at the end of each block sequence and at each epoch boundary | exact |

A projected comparison reports its scope beside its result and never counts as an exact pass
([CNF-2](../design-rules.md#cnf-2)). Reading a refusal's constructor path out of `Show` text is a parser, with its
table ([STR-2](../design-rules.md#str-2)).

**Keeping nondeterminism out.**

- **The tip.** The mempool judges at the slot after the tip (R3), so a transaction's verdict depends on the tip, not on
  the wall clock. Every probe is bracketed: both nodes' tips are read before and after, and a probe whose bracket
  moved is `NoVerdict` and retried within a bound, as `ntc` and `judged` do. In the injector cluster the bracket holds
  by construction.
- **The mempool.** Each probe spends fresh inputs; path R's record holds the accepted probes in order; in the injector
  cluster no block is forged between probes and nothing is forwarded between the nodes.
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
and seed, each verdict and each bracket. The keys are throwaway devnet keys: they go with the record to the
private store (§8), so that a disagreement can be shrunk again later, and never into the repository
([FMT-3](../design-rules.md#fmt-3)).

### 7.2 Replay

A probe is replayed from the record, not from the seed alone, since a cluster's genesis and keys are fresh each run
(§2.1): the injector serves the recorded chain to two fresh nodes and then the probe; path R replays offline from the
recorded state. The seed reproduces the choices only within the recorded context.

### 7.3 Minimisation

A disagreeing probe is shrunk against both nodes. A candidate is simpler when it has fewer or smaller components
(inputs, outputs, certificates, witnesses, assets, script terms, CBOR items); a structure-level candidate is signed
again as in §5.1. A candidate is kept when it reproduces the same disagreement: the same pair of verdict kinds and the
same refusal constructor path on each side. Shrinking stops at a fixed point or at its budget, and reports the
smallest candidate with the number of steps. A transaction candidate is judged as any probe: in path R the reference
judges it live at its own bracketed state and Scarab replays it offline. A block candidate needs the injector cluster at
the probe's predecessor. Shrinking runs inside the run that found the disagreement, with its cluster and keys, from a
share of the budget reserved for it; a disagreement left unshrunk is shrunk later from the record and the keys, in an
injector cluster restored from the recorded chain or a fresh cluster in which it still reproduces.

### 7.4 Kept inputs

A disagreement, once fixed and published (§8), is kept as a regression input in a new directory of the campaign's
own under [`fixtures/reference/`](../../fixtures/reference/), one subdirectory per input:

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

- **Scarab is wrong**: a Gap; it is fixed through the private advisory's process, and its kept input lands with the
  fix.
- **The reference is wrong** (a [REF-2](../design-rules.md#ref-2) defect): Scarab still gives the reference's verdict
  (I1); the maintainers report the defect upstream privately, and nothing of it enters the repository before upstream
  discloses it.
- **The campaign is wrong** (a generator or bracket defect): fixed in the campaign once shown to be the campaign's.

**Discovery runs only in private.** A run that searches for disagreements, a discovery run, executes only on
infrastructure the maintainers control, outside the public repository's Actions (§10, Q3). Everything about its
execution stays there: when it runs and which families and eras it covers, its start, end and durations, its exit
status, its failures, timeouts and cleanup, its logs, its artifacts, its record and the minimiser's trail. Its entry
point refuses to start under the public repository's Actions, and it writes only to its run directory and the private
store. Public CI runs no discovery: it replays kept inputs, each one already fixed or disclosed (§7.4).

**What is published** is one summary per period, on a fixed calendar date whatever the period's runs found, written
from one closed summary type by a module that cannot reach the inputs, with a test that its serialised form holds
exactly these fields:

- the period's first and last dates, and the configuration in force: the budget of a run (its wall-time limit, CPUs
  and memory), the reference build and image digest, and the set of Scarab commits the period's runs used;
- the number of runs, and per input family (§5) the probes tried, the verdicts reached and the inconclusive ones,
  summed over the period;
- the number of runs with each result (`agreed`, `reportedPrivately`, `incomplete`);
- the SHA-256 of each run's seed, in sorted order. A seed is 256 bits from the operating system's random source, so
  its hash commits to it and cannot be searched; a seed is shown only with its advisory.

Every family runs in every period (§9.2), so a period's totals point at no family, and nothing in a summary is per
run: no run's result, date, duration, commit or counts. A summary says that a period had a finding through its
`reportedPrivately` count and nothing more.

**The disclosure gate**, part of S1's jump gate and of every later change to the runner: a test run with a forced
disagreement, a forced failure, a forced timeout and a forced cleanup error leaves nothing outside its run directory
and the private store, and the summary of a period that holds them carries only its fields.

Node logs are the subtle leak: the reference's refusal trace carries the transaction and its error (R18; what its log
prints of it is N13), and Scarab's logs may carry its own. They stay on the private runner with the rest; the pattern
of `e2e.yml`, which prints a scenario's output and uploads every node log and run summary as an artifact even when a
run fails (`.github/workflows/e2e.yml` 85-112), is not copied anywhere the public can read, and neither is
`faults.yml`'s seed from the public run number.

The record and the minimal inputs go to the private store, encrypted at rest; the advisory draft holds what its fix
needs.

**A dated evidence row** for a period (§9.2) is written from its summary only, on the summary's date. A period whose
finding is still private says "reported privately" and nothing more.

## 9. Stages, budget and evidence

### 9.1 Stages

Each stage is one or more pull requests at the level of what it touches; none changes Scarab's behaviour, and a fix
the campaign leads to is its own change at its own level.

| Stage | Delivers | Jump gate |
|---|---|---|
| S0 | This document; its claims checked independently. | The review's check of §3 at the pins, and the owner's answers to §10. |
| S1 | Path R for generated transactions in the shared cluster: the bracketed judge with accepted probes, the record, the offline replay of every era, the private runner and store, the period summary type and the disclosure gate. Confirms N1, N2, N10, N12 and N13, and answers Q10. | The committed refusal corpus judged again gives no disagreement and no inconclusive case; a negative control is found and reported as `reportedPrivately`: a build with one check deliberately loosened, on a branch never merged, as #927's negative controls were; the summary's field test and the disclosure gate (§8) pass. |
| S2 | Phase-1 generation and mutation per era with re-signing; the decoding oracle as the offline first judge; the first target (§5.3); the minimiser; path L for Conway refusals in the shared cluster; kept inputs and their suite. Confirms N3 and N7. | A bounded run per era with every verdict conclusive or explained, every disagreement triaged privately (§8), and the first target's cases judged on both paths. |
| S3 | Phase 2: WP8 scripts in every purpose, datums and redeemers, units at the limits, every cost model. | As S2, for each Plutus language and protocol version the cluster reaches. |
| S4 | The injector and the injector cluster, with a block builder for the eras before Conway: the base chain, header, body, size, protocol-version, era-transition and fork probes, transactions in blocks, the block verdict readers on both sides, the tip and ledger-state comparisons. Confirms N4 to N6, N9 and N11. | A base chain over two epochs and one era transition adopted alike, tips and same-point state equal; each block family reaches conclusive verdicts on both nodes. |
| S5 | Nightly discovery on the private runner under §8, with its budget; the period summaries and their dated evidence rows; the compatibility register component for the campaign. | Seven nightly runs within budget with no inconclusive rate above the one S2 measured and no disagreement the triage traced to the campaign. Then #922's acceptance. |

### 9.2 Budget and the evidence row

Once S5 is stable, discovery runs nightly on the private runner, one job of at most 90 minutes on 4 CPUs (the limit
`e2e.yml` and `fuzz.yml` set), at least 60 of them probing. A private schedule, published nowhere, rotates the families
and eras so that each family, and each era the clusters reach, runs in every period; a weekly run is long enough to
cross an epoch boundary and an era transition. The budget and the period are configuration, published in each summary
(§8); a change to them is a change to this section.

The evidence row follows [How evidence is recorded](../../CONTRIBUTING.md#how-evidence-is-recorded) and the
[evidence log](../compatibility.md#evidence-log), one row per period, from its summary: the command without a seed,
the period, the commits, the totals per family, the count of each result, and the environment (the budget, CPUs,
memory and image digests, the seeds' hashes). Raw run artifacts are not published.

## 10. Risks and open questions

**Risks.**

- **False disagreements** from the harness's own nondeterminism cost triage and could be filed wrongly. S1's
  calibration on the refusal corpus, and the brackets, come before any search.
- **A leak** through a log, an artifact, a seed or a run's timing would publish a vulnerability before its fix. Private
  execution, the closed summary type and the disclosure gate address it; a reviewer checks every change to the
  campaign's runner against §8.
- **Shared bugs.** The injector builds blocks with Scarab's own builder and signing. The reference judges every block
  it builds, so a builder bug shows as a disagreement about the base chain, which S4's gate requires to be absent.
- **Coverage.** Random search with no coverage feedback finds what it happens to reach; a night with no disagreement is
  no evidence about other inputs ([CNF-2](../design-rules.md#cnf-2)).
- **Cost.** A cluster's start-up and an era walk take a large part of a night; the injector cluster, which starts in
  the era its genesis names, is the cheaper home for most probes.
- **Reconnection.** A refused block ends the injector's connection to the reference node unless it is a forgiven tip
  (R12), and the reconnection delay (N4) bounds the block probes per night.

**Open questions for the owner**, each with a recommendation.

- **Q1. Which reference build does the campaign judge against?** Recommendation: the default `--reference-build`,
  `11.1` (cardano-node 11.1.3), today; a second reference node of the `11.2` build once its stages land and Scarab
  starts with it, with §3 read again at its pins (N8).
- **Q2. Is every disagreement reported privately first, including one that SECURITY.md would let go to a public
  issue?** Recommendation: yes; any exception is the owner's, case by case.
- **Q3. Where does the private runner live?** Discovery never runs in the public repository's Actions (§8).
  Recommendation: a scheduled job on a machine the maintainers control, which checks out a commit of `main`, writes
  only to its run directory and the private store, and publishes nothing itself; a maintainer publishes each period's
  summary. Not a self-hosted runner attached to the public repository: GitHub advises against one there, where a
  fork's pull request can run code on it (not checked here). The alternative is the CI of a private repository that
  mirrors `main`.
- **Q4. What is the period of the published summary?** Recommendation: a calendar month, published on a fixed day of
  the next one.
- **Q5. Where does the injector live?** Recommendation: a `tools` program ([TOOL-1](../design-rules.md#tool-1)) that
  reuses Scarab's node-to-node servers, `BlockBuilder` and the forger's signing, with a raw-bytes path for blocks Scarab
  cannot decode; not a Python reimplementation of the mini-protocols.
- **Q6. What is the nightly budget?** Recommendation: §9.2's, one 90-minute job, with a weekly longer run.
- **Q7. How are timing-dependent refusals handled?** The reference refuses a transaction that takes too long to
  validate, with a text that holds the duration (R5); Scarab has no such timeout (#340), and the text cannot be
  compared exactly. Recommendation: the campaign keeps generated work far below the limit, counts such a refusal as
  `NoVerdict(timing)`, and leaves the difference itself to #340; whether the variable text is a category the owner
  closes is the owner's, as [ADR 0001's
  amendment](../adr/0001-haskell-node-as-reference.md#amendment-2026-11-04-exact-diagnostic-and-refusal-parity) says.
- **Q8. How are eras before Conway compared live?** Recommendation: through path R, and path L reported incomplete
  before Conway; the campaign asks for no change to Scarab's mempool.
- **Q9. What does a kept input hold when its context is long?** Recommendation: the reference-format state, UTxO and
  genesis, as the refusal corpus keeps them (§7.4); never a native snapshot.
- **Q10. Must a running Scarab node judge accepted transactions before the injector exists?** Under this plan path R
  judges them offline until S4, because a cluster forwards Scarab's accepted transactions to the reference (§2.1), so
  the issue's "submitted to both nodes" holds for accepted transactions only from S4. Recommendation: let S1 find out
  whether submitting to the reference first and to Scarab second gives two independent verdicts in the shared cluster
  (it does if the reference never offers its mempool to the follower, which is not checked); if so, use it from S2;
  otherwise path R until S4.

## 11. What does not change

No code of the node, no persisted format, no workflow and no fixture changes with this document. The campaign's code
lives in `scripts/devnet/`, `scripts/oracle/` and `tools`, and its kept inputs in a directory of their own.
