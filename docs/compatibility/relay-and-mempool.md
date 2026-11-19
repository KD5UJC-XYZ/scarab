<!-- SPDX-License-Identifier: Apache-2.0 -->
# Relay and mempool

Part of the [compatibility matrix](../compatibility.md); the register entry is `relay-and-mempool` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: pure `MempoolState`, TxSubmission2 both roles, typed rejections; in every era from Shelley to Conway the pool holds the transactions of the era of its ledger, the tip's state ticked to the slot after it, and TxSubmission2 carries the hard-fork combinator's `GenTx` and `GenTxId` of every Shelley-based era under the era's index, each transaction read by its era's decoders; a peer's transaction of another era is taken as a local client's is, as the selected reference build does (#934; `EraMempoolSuite`). A Byron `GenTx` or `GenTxId` from a peer ends the connection. The reference decodes both: it takes a Byron `GenTxId` as any other id, compared by its hash whatever the era (`AcrossEras.hs:167-181`, ouroboros-consensus `82ecba32`), for its lookups and requests, and refuses a Byron `GenTx`, the transaction, with the era mismatch

## Evidence

unit/property (held transactions always apply in order, byte accounting, tickets; the measure refusals `MaxTxSizeUTxO`, `ExUnitsTooBigUTxO` and `ConwayTxRefScriptsSizeTooBig` before the ledger rules, and each component of the capacity vector as the binding one); devnet `relay` (203/203 transactions via the Scala mempool into pool blocks) and `pool`

## Explicitly unchecked or unconfirmed

full successor-slot ticking includes epoch transitions, pending rewards, treasury and adopted parameters (H4/#40); ordered rollback revalidation matches the native current-entry policy, with no automatic block-transaction resurrection and no persistent seen-ID suppression of later re-offers; admission waits for room as the reference does (two FIFO lines, local clients ahead of peers, cancelable; tests `MempoolBackpressureSuite`, [IN-82](../implementer-notes.md)), per-client mempool admission fairness beyond that remains roadmap G5-F1; network resource bounds are recorded in [G5](../design/network-limits.md)

## Validation time limits

The mempool times the validation of a new transaction and applies the reference's three limits, `MempoolTimeoutSoft`,
`MempoolTimeoutHard` and `MempoolTimeoutCapacity` ([configuration](../running/configuration.md); defaults 1 s, 1.5 s
and 5 s, on by default as cardano-node 11.1.3 passes them, `Node/Run.hs` 388-392), as `doAddTx` and `pureTryAddTx` do
(ouroboros-consensus `82ecba32` `Mempool/Update.hs` 205-451; the same at `808e4005`, 207-454):

- **What is timed:** `Clock[F].monotonic` around the second measure, the capacity in all its components, the capacity
  of time and the ledger rules, scheduling and collection included; not the wait in line, the state lock, the first
  measure or the read of the UTxO entries.
- **Soft:** a validation that finished after strictly longer than the soft limit is refused, whatever its result and
  whoever submitted it, where the era of the pool's ledger has an error for it: only Conway's (`mkMempoolApplyTxError`,
  `shelley` `Shelley/Eras.hs` 169-220; none from Shelley to Babbage, and none in Dijkstra). The refusal is
  `ConwayMempoolFailure` with the reference's text, `MempoolTxTooSlow (<duration>) <txid>`: the duration as Haskell
  shows a `DiffTime` (`1.234567891s`) and the id as the hard-fork combinator shows the submitted transaction's
  (`HardForkGenTxId {getHardForkGenTxId = S (S (... (Z (WrapGenTxId {unwrapGenTxId = txid: TxId {unTxId = SafeHash
  "<hex>"}}))...))}`). A peer's refused transaction ends nothing.
- **Hard:** the validation runs under `Sync.interruptible`; at the limit it is cancelled, the evaluator the mempool is
  given stops once its thread is interrupted ([`ScarabEvaluator.interruptible`](../../plutus/src/main/scala/xyz/kd5ujc/scarab/plutus/ScarabEvaluator.scala)),
  and the cut-off returns once it has stopped. A local client's Conway transaction is then refused as above; otherwise
  (a peer's, or before Conway) the submitter's connection ends, as `ExnMempoolTimeout`, which no rethrow policy names,
  ends the reference's, and the node goes on. The limit is taken as the reference's runtime takes it: floored to
  microseconds and narrowed to 64 bits (io-classes 1.8.0.1 `MonadTimer/SI.hs` 43-49, 262), negative being none and
  zero a cut-off before the validation starts; a limit past 292 years is not distinguished from none.
- **Capacity:** each held transaction records how long its validation took, which revalidation keeps; while the held
  transactions took longer than the capacity together, a new transaction waits for the pool to change (one
  transaction can take them past it), after the second measure and the room in every component and before the rules.
- **No verdict:** a validation the rules or the evaluator give no verdict on (`RulesDefect`, `LedgerFailure.Unavailable`
  or `Unverifiable`) keeps its cause and its handling (the submitter's connection ends) however long it took: in the
  reference the exception escapes `timeout` and never meets the soft limit.
- **Order of the early refusals:** in Conway a duplicate of a held transaction is refused first among the rules, where
  the reference's MEMPOOL rule refuses it, so it waits for room and for time as any other transaction. A transaction
  that does not reach the ledger's era is refused where the selected build refuses it: under `11.2` and the Leios
  build in its first measure, at once (`HardFork/Combinator/Mempool.hs` 357-362 at `808e4005`); under `11.1` it
  measures nothing, waits as any other and is refused by the rules in the timed step (371-376, 416-421 and 501-506 at
  `82ecba32`), so it can be refused as too slow, naming the era it came in.
- **Reporting:** a refusal is a `mempoolRejected` line with reason `TooSlow` or `TimedOut`, a wait for time a
  `mempoolWaiting` line; `cardano_node_metrics_txsMempoolTimeoutSoft_counter` and `..._txsMempoolTimeoutHard_counter`
  count them as the reference's do ([metrics](../running/metrics.md)).
- **Evidence:** `MempoolTimeoutsSuite` (the soft boundary from either source, a late refusal of the rules, the reply's
  bytes and text, a cut-off per source and era, the capacity's wait and release, durations kept through revalidation,
  and the audit's G2 `hashToGroup` loop cut off at the hard limit through the node's own timing with the pool left
  empty, a no-verdict failure past the soft limit, a duplicate's and an era mismatch's place under each build);
  `MempoolMeasureSuite` and `MempoolBackpressureSuite` (a duplicate waits for room); `ConfigSuite` (all, none, `null`,
  some, values aeson refuses, the microsecond floor); `CekStopSuite` and `InterruptibleEvaluatorSuite` (an
  evaluation's verdict and budget unchanged until it is stopped: on the 945 programs of the 1,009 conformance cases of
  plutus 1.71.0.0, the other 64 being the reference's parse refusals, which the suite checks are refused, and on the
  boundary corpus).
- **Not checked:** a capture of the reference's refusal text (the text was read from the derived `Show` instances,
  not executed); the duration in it is each node's own measurement, so it never compares exactly (the
  [campaign's Q7](../design/differential-campaign.md#10-risks-and-open-questions)). A builtin call that runs long
  between two steps of the machine is stopped at the next step after it returns, as GHC stops a computation at its
  next allocation. Under `11.1`, a transaction of an era whose transactions the pool does not read (Byron, Dijkstra)
  has no id the pool can show, so where the reference would refuse it as too slow (a soft limit below the time its
  era check takes, or a hard limit below a microsecond), Scarab refuses it with the era mismatch, or ends a local
  client's connection when the hard limit cuts it off. A cancellation during the validation leaves the pool as it
  was; once a transaction is held, its state, the pool's version and the `mempoolAdded` event are separate steps, as
  before.

## Differences decided on 2026-11-14 (#956)

- A transaction id named twice in one `MsgRequestTxs` ends the connection (`TxSubmissionPolicy.bodies`). The TxSubmission2 outbound side of ouroboros-network `c45735a5`, which cardano-node 11.1.3 links, sends the transaction, while it is still in the mempool, once for each time the request names it. An approved deviation that follows ouroboros-network pull request [#5435](https://github.com/IntersectMBO/ouroboros-network/pull/5435) ([decisions](../decisions.md#networking-and-local-clients), [ADR 0008](../adr/0008-networking-mirrors-ouroboros-network.md#amendment-2026-11-14-the-re-pins-deviations-decided)).

## Synchronisation with the chain

The pool is validated again at a new tip from a watcher of its own (`MempoolSync`), as the reference's mempool is from
the watcher `openMempool` forks on the ledger tip (`forkSyncStateOnTipPointChange`, ouroboros-consensus `82ecba32` and
`808e4005`, `Mempool/Init.hs` 47-82; no module of `Storage/ChainDB/Impl` names the mempool). The follower's commit only
publishes the chain and never waits for the pool. The watcher looks once at start and then whenever the tip is not the
one it saw last, taking the latest (`runWatcher` and `blockUntilChanged`, `Util/STM.hs` 47-56 and 132-152), and the
pool reads the chain again once its state lock is held, as `implSyncWithLedger` reads the ledger in the step that
takes the pool's state (`82ecba32` `Mempool/Update.hs` 558-625): a revalidation that waited behind an admission applies
the chain as it is then, and none runs when the pool is at the tip. Its failure stops the node, as the reference's
linked thread does. Admissions stay serial under the state lock, as in the reference. Scarab's own logging of the
transactions that left the pool in a block (`mempoolAdopted`) reads the blocks the pool was not revalidated past from
the chain's volatile window; one in a block that is no longer volatile is reported with its reason (`mempoolDropped`),
as the reference reports every removed transaction (`TraceMempoolRemoveTxs`). Tests: `MempoolSyncSuite` (a
transaction holding the pool's lock holds back no commit, forged block's adoption, GSM comparison or state-query
acquire, and the pool catches up at the chain's tip once it lets go; a revalidation that waited never applies a tip
the chain has left, and the admitted transaction stays; the look at start and the coalescing; failures; a ScalaCheck
model of the blocks not seen). Unchecked: the reference's revalidation at `808e4005` runs off its state lock with a
bounded residual under it (`Mempool/Update.hs` 514-692); Scarab's revalidation holds the state lock throughout, as
`82ecba32` does (`Mempool/Update.hs` 544-625).
