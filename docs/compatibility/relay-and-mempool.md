<!-- SPDX-License-Identifier: Apache-2.0 -->
# Relay and mempool

Part of the [compatibility matrix](../compatibility.md); the register entry is `relay-and-mempool` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: pure `MempoolState`, TxSubmission2 both roles, typed rejections; in every era from Shelley to Conway the pool holds the transactions of the era of its ledger, the tip's state ticked to the slot after it, and TxSubmission2 carries the hard-fork combinator's `GenTx` and `GenTxId` of every Shelley-based era under the era's index, each transaction read by its era's decoders; a peer's transaction of another era is taken as a local client's is, as the selected reference build does (#934; `EraMempoolSuite`). A Byron `GenTx` or `GenTxId` from a peer ends the connection. The reference decodes both: it takes a Byron `GenTxId` as any other id, compared by its hash whatever the era (`AcrossEras.hs:167-181`, ouroboros-consensus `82ecba32`), for its lookups and requests, and refuses a Byron `GenTx`, the transaction, with the era mismatch

## Evidence

unit/property (held transactions always apply in order, byte accounting, tickets; the measure refusals `MaxTxSizeUTxO`, `ExUnitsTooBigUTxO` and `ConwayTxRefScriptsSizeTooBig` before the ledger rules, and each component of the capacity vector as the binding one); devnet `relay` (203/203 transactions via the Scala mempool into pool blocks) and `pool`

## Explicitly unchecked or unconfirmed

full successor-slot ticking includes epoch transitions, pending rewards, treasury and adopted parameters (H4/#40); ordered rollback revalidation matches the native current-entry policy, with no automatic block-transaction resurrection and no persistent seen-ID suppression of later re-offers; admission waits for room as the reference does (two FIFO lines, local clients ahead of peers, cancelable; tests `MempoolBackpressureSuite`, [IN-82](../implementer-notes.md)), per-client mempool admission fairness beyond that remains roadmap G5-F1; network resource bounds are recorded in [G5](../design/network-limits.md)

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
