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

A forged block the LoE holds back is kept, and chain selection takes it later if it is then still valid, joins the
chain and is preferred. The reference's ChainDB puts every block it is given in its VolatileDB before chain selection
runs for it (`VolatileDB.putBlock`, `ChainSel.hs:450`; 455 at 808e40054, 489 at 2416493bf), and considers the
blocks the LoE postponed again when the GDD governor moves the LoE tip in Syncing or the node is caught up
(`ChainSelReprocessLoEBlocks`, `ChainSel.hs:358-413`, 363-418 at 808e40054, 693-761 at 2416493bf; triggered at
`Genesis/Governor.hs:172-184`, the same at all three). Scarab puts the held-back block in the received log and in
the selection worker's processed forest, as a block of no session (a refusal of it blames nobody, as the forging
loop's `noPunishment`, `NodeKernel.hs:747`), and the worker's selection on the governor's signal reads that forest.
The forger's trace is the one of the submission, `didntAdoptBlock`; a later selection of the block is logged as the
chain's (`extended`), as the reference traces it in the ChainDB (`switchTo`, `AddedToCurrentChain`,
`ChainSel.hs:964`), not in the forging loop.

`HeldBackForgedBlockSuite` (the follower with its selection worker and GDD governor) and `ForgeLimitOnEagernessSuite`
(the forging loop with the worker and the real block rules) check these cases in simulated time:

- held back in PreSyncing, the block is in the processed forest, after the worker's preparation (which writes the
  received log in the node), and once the node is caught up it is adopted without being offered again, logged as
  the chain's extension; with the real rules, it is the held-back block selection prefers, and the forger's traces
  stay `didntAdoptBlock`;
- held back in Syncing with the LoE fragment at the immutable tip, it is adopted once the GDD moves the LoE tip;
- a longer chain a peer delivered meanwhile, held back too, is selected over it once the node is caught up;
- refused by the rules when selection reaches it, it is recorded invalid, no session is blamed and the node goes on.

With the keep taken out of `Follower.adoptForged`, the four cases that wait for the block to be adopted or refused
fail, and the longer chain's case and the earlier cases pass.

Not shown: no run against a reference node, and no devnet scenario forges in Genesis mode. A forged block chain
selection does not take for another reason (a slot battle lost, a tip that moved) is still not kept, where the
reference's VolatileDB keeps every block it is given; such a block was never on the node's chain, so no peer has it
to build on.
