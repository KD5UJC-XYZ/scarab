<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ouroboros Genesis (G3, #33)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ouroboros-genesis` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented; local stage acceptance passed on `43d940871`: shared GSM, LoE/GDD, LoP, CSJ, historicity and devoted fetching; full-era canonical headers and received-fork restart retention

## Evidence

All 84 original runtime scenarios satisfy all 21 original native property branches; exact complete selected fragments, all 16 CSJ download bounds, 1,304 complete native GDD decisions and 45 ordered callback batches; concrete TCP ordering and lifecycle/source-attribution regressions. [Contract and fixtures](../design/genesis.md)

## Explicitly unchecked or unconfirmed

Finite pinned generation, not an exhaustive campaign or a security proof. Separate scheduler-policy follow-ups: rolling ChainSync [#310](https://github.com/KD5UJC-XYZ/scarab/issues/310) and Genesis churn [#311](https://github.com/KD5UJC-XYZ/scarab/issues/311). Complete termination observations are retained; original properties permit specified alternative refusals and concurrent finalizer ordering. Standard Docker check: 8,379 passed, with 16 existing optional-input skips reported separately; extended fuzz and final Genesis/Praos relay gates passed. See the stage evidence below.

## GDD evaluations

The GDD governor (`GenesisGovernor`) follows `gddWatcher` (ouroboros-consensus 82ecba329
`Genesis/Governor.hs:121-188`, unchanged at 808e40054 and 2416493bf) as `runWatcher` runs it (`Util/STM.hs:127-152`);
the [design](../design/genesis.md#mechanisms-and-resource-policy) says how it is built. `GenesisGovernorSuite` checks
these cases in simulated time:

- a candidate superseded before the immutable anchor advanced is never evaluated against the new anchor;
- a write after the snapshot, an unchanged republication included, neither discards nor repeats an evaluation, and
  evaluations land once per interval while a candidate grows during each;
- a fingerprint that changes and comes back to the evaluated one starts no evaluation;
- a loser whose session ended after the snapshot, or while an earlier loser was being disconnected, is not
  disconnected.

`UpstreamGenesisReplaySuite` replays the upstream Genesis cases through the governor; their selections and peer
terminations are the same as before this behaviour was built. Not shown: no run against a reference node exercises
GDD's timing, and the equivalence is shown only on these cases. The governor stores the LoE fragment before it
disconnects the losers, where the reference kills them first; in the replayed cases this order ends the peers in the
reference's order and the other did not.

## A forged block and the Limit on Eagerness

A block the node forges goes to chain selection as a peer's candidate does: `Follower.adoptForged` projects it
through the Limit on Eagerness with `CandidateSelection.project`, the projection the selection worker applies to
every candidate. The reference's forging loop hands its block to `ChainDB.addBlockAsync` (ouroboros-consensus
82ecba329 `NodeKernel.hs:756`; `NodeKernel/Forge.hs:284` at 808e40054), whose chain selection trims every candidate
to the LoE fragment, the one that extends the current chain included, before it keeps those preferable to the
current chain (`constructPreferableCandidates`, `Storage/ChainDB/Impl/ChainSel.hs:735-777`). The LoE fragment is
the empty fragment at the immutable tip in PreSyncing, the GDD's fragment in Syncing, and none once caught up
(`Node/Genesis.hs:252-278`, the same at 808e40054 and 2416493bf). A block the LoE leaves out is not adopted and is
traced `TraceDidntAdoptBlock` (`NodeKernel.hs:761-769`); Scarab logs `didntAdoptBlock`, then `lost`. Neither
forging loop has a Genesis gate of its own.

`ForgedLimitOnEagernessSuite` (the follower) and `ForgeLimitOnEagernessSuite` (the forging loop, with the real
block rules) check these cases in simulated time:

- in PreSyncing, and in Syncing with the LoE fragment at the immutable tip, the first `k` blocks are adopted and a
  forged block past the LoE plus `k` is not, the immutable tip staying where it was; a peer's candidate carrying
  the same block is held back alike;
- caught up, in Praos mode, and with LoE and GDD disabled, every forged block is adopted and the immutable tip
  follows `k` blocks behind;
- a forged block of the tip's height within the LoE is still decided by chain selection's tie-break.

### A forged block is kept as the reference keeps it

Whatever chain selection makes of a forged block, the node keeps it, and chain selection takes it later if it is then
still valid, joins the chain and is preferred. The reference's ChainDB puts a block it is given in its VolatileDB
before chain selection runs for it, unless the block is there already, known invalid or no younger than the
immutable tip (`VolatileDB.putBlock`, `ChainSel.hs:428-450`; 433-455 at 808e40054, 467-489 at 2416493bf;
`olderThanImmTip`, `ChainSel.hs:574-581`, which admits an epoch boundary block of the immutable tip's own number;
579-586 at 808e40054, 870-877 at 2416493bf). Whatever the outcome, adopted, held back by the LoE, losing to the tip
or no longer attached to the chain, the block stays there: a later chain selection for a block that extends it, or
that it extends, considers it (`constructPreferableCandidates`, `ChainSel.hs:735-777`), and so does the
reconsideration the GDD governor triggers when it moves the LoE tip in Syncing or the node is caught up, of the
successors of the current chain's blocks that the VolatileDB holds, are not on that chain and are not known invalid
(`ChainSelReprocessLoEBlocks`, `ChainSel.hs:358-413`, 363-418 at 808e40054, 693-761 at 2416493bf; triggered at
`Genesis/Governor.hs:172-184`, the same at all three). A forged block the reference's selection finds invalid is in
the VolatileDB and recorded invalid (`addInvalidBlock`, `ChainSel.hs:1313-1316`), which the forging loop reads
(`NodeKernel.hs:761-769`).

`Follower.submitForged` does the same with the selection worker's processed forest, the set its selections read, and
the received log, which a restart reads back: under the selection lock, before the chain that holds an adopted block
is published, the block is admitted as the reference admits it, written to the received log and added to the forest
as a block of no session (a refusal of it blames nobody, as the forging loop's `noPunishment`, `NodeKernel.hs:747`);
a block the rules refused is recorded invalid there too. A kept block the direct selection did not take goes to the
worker's selection for it at once, its candidates going through the forest's blocks, as the reference runs
`chainSelectionForBlock` right after `putBlock` (`ChainSel.hs:452`, 457 at 808e40054): a block forged on a kept block
our chain no longer holds is adopted there when its branch is preferred, and the submission's outcome, which the
forger traces, is what that selection decided, read as the reference's forging loop reads it (the block is the tip:
adopted; recorded invalid: invalid; else not adopted, `NodeKernel.hs:758-769`). What the node holds of the block is
checked before anything else, in the reference's order: a block stored already, withheld blocks included, is neither
judged, stored nor selected again (`IgnoreBlockAlreadyInVolatileDB`, `ChainSel.hs:431-433`), and a block known
invalid is not judged again (`IgnoreInvalidBlock`, 434-440); the outcome is read as the forging loop reads it. A
rollback leaves the block in
the forest, and a restart seeds the forest from the received log before startup selection. A forged block the
node's own implementation has no verdict on is never judged again in this run, as the forger handles our own block
without a verdict as a refusal (R3 of the decision of issue 567): when the direct selection finds so, the block is
not kept; when any selection of the worker finds so of a kept one (the one its submission runs, a reselection when
the LoE moves or the node is caught up, an admission whose candidate goes through it), the block stays in the forest
withheld for good (`NoVerdictRetry.Never`), not released either when a related peer block's retry comes due, and
every other block keeps the retry schedule of a peer's block. A restart reads the kept block back as a recovered one,
and judges it again as it does every withheld block.
The forger's trace is the one of the submission (`adoptedBlock`,
`didntAdoptBlock` or `forgedInvalidBlock`); a later selection of the block is logged as the chain's (`extended`), as
the reference traces it in the ChainDB (`switchTo`, `AddedToCurrentChain`, `ChainSel.hs:964`), not in the forging
loop. Keeping the block is the node's work, not the forger's: if it fails other than in storage, the block is not
kept, the forger's outcome is what selection made of it all the same, and a `notKept` line says why; a storage
failure stops the node, as a failed write of a peer's block to the received log does. The reference has no such
best-effort keeper, and an exception in its add-block work stops the node: going on is an approved deviation
([decisions register](../decisions.md#what-stops-the-node), row of 2026-11-20).

`HeldBackForgedBlockSuite` (the follower with its selection worker and GDD governor), `ForgedBlockRetentionSuite`
(the follower in Praos mode with its worker, with and without a received log) and `ForgeLimitOnEagernessSuite` (the
forging loop with the worker and the real block rules) check these cases in simulated time:

- held back in PreSyncing, the block is in the processed forest and the received log, and once the node is caught up
  it is adopted without being offered again, logged as the chain's extension; with the real rules, it is the
  held-back block selection prefers, and the forger's traces stay `didntAdoptBlock`;
- held back in Syncing with the LoE fragment at the immutable tip, it is adopted once the GDD moves the LoE tip;
- a longer chain a peer delivered meanwhile, held back too, is selected over it once the node is caught up;
- a forged block that lost the tie to our tip is selected once a peer delivers a block on it, and one whose
  predecessor left our chain once that predecessor is delivered after it, neither delivered itself;
- a block forged on a kept block our chain does not hold (one that lost the tie, or our adopted block a peer's chain
  rolled back) is adopted at its submission when its branch is the longer, with no delivery or reselection after it,
  and reported `Adopted`; when that selection has no verdict on the block, for a cause that may pass, the submission
  is `NoVerdict` and the block is not judged again, nor adopted, in the 50 simulated seconds after it;
- an adopted forged block that a peer's longer chain rolled back wins again when its branch grows, without being
  delivered, as a fetched block does; with a received log whose cache holds one body, from the log; and after the
  log is closed and opened again, through `Main.seedReceived` and startup selection;
- a forged block no younger than the immutable tip is not kept, and the rule's epoch boundary exception holds; the
  same block offered again is neither stored nor selected again, and, delivered by a peer, not processed;
- refused by the rules, at its submission or when selection reaches it, it is recorded invalid and never selected,
  no session is blamed, and offered again it is not stored again;
- held back, and with no verdict when the reselection once caught up reaches it, for a cause that may pass, it is
  withheld for good (`NoVerdictRetry.Never`) and neither judged again nor adopted in the 50 simulated seconds after;
  offered again while its parent is our tip, it is neither judged nor adopted;
- a forged block a rival took the chain from, withheld for good when a sibling's admission reached it, is neither
  released nor adopted when a peer's child of it withheld earlier comes due, though its fault has passed;
- when keeping fails with a typed failure other than storage, or throws, no block is kept, the forger's outcomes are
  the same and each block gets a `notKept` line; a storage failure is raised to the forger, typed, before the chain
  that would hold the block is published.

With only a block the LoE holds back kept, as before this behaviour was built, every case that waits for a kept block
to be selected does not end, and the cases of duplicates, refusals and the forest's contents fail; the longer chain's
case, the fetched block's and the earlier cases pass. Without the selection at submission, a block forged on a kept
block off our chain is `Lost(DetachedPredecessor)` and our chain stays where it was; with a peer's retry schedule
for the block that selection has no verdict on, it is judged again a second later and adopted, and so is a
held-back block the reselection had no verdict on.

Not shown: no run against a reference node, and no devnet scenario forges in Genesis mode. The reference also serves
the blocks of its VolatileDB over BlockFetch, recent forks included; Scarab serves its selected chain and the
immutable store only, a separate item.
