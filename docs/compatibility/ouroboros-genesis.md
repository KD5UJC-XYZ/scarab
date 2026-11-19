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
