<!-- SPDX-License-Identifier: Apache-2.0 -->
# Plutus: Scarab's own evaluator

Part of the [compatibility matrix](../compatibility.md); the register entry is `plutus-own-evaluator` in [compatibility.json](../data/compatibility.json).

## Status as recorded

partial (I2, [#43](https://github.com/KD5UJC-XYZ/scarab/issues/43), [ADR 0027](../adr/0027-own-uplc-evaluator-as-a-correctness-backend.md)):
the `uplc` module, with no Scalus on its class path, evaluates phase 2 and admits scripts in the block
rules, the mempool and the forger under `--plutus-vm scarab` ([flags](../running/flags.md)). Scalus stays
the default. `--forge` with `scarab` is refused at start, and protocol 12 and later get no verdict, both
by decision. What each suite shows is in [the conformance profile](../plutus-conformance.md#scarabs-own-evaluator).

## Evidence

- **Upstream conformance:** the 1,009 cases of the pinned evaluation corpus, without exclusions, and the
  906 of them in Flat form through the own Flat decoder, with exact results and budgets.
- **Costing oracle:** 55,740 observations of 1,858 scripts under 30 parameter lists and 111,552 budget
  boundaries, plus 5,661 genesis cost-model observations, all against the unchanged Haskell costing of
  plutus 1.70.0.0.
- **Boundary corpora:** the 477 and 54 Haskell verdicts of `plutus-boundaries` and
  `plutus-upstream-boundaries`, through the ledger's evaluation.
- **Generated scripts:** the 3,000 of `fixtures/reference/uplc-generated`, judged by the pinned Haskell
  evaluator, agree in stage, error, budget and logs; two larger corpora of 15,000 each, not retained,
  also agreed ([corpus](../../fixtures/reference/uplc-generated/README.md)).
- **Differential:** both backends on 752 recorded script requests, with no difference in verdict or
  failure class; the successes also agree in execution units and logs.
- **Devnet `e2e`**, at `b736b7fa7` and again at `766324062` after a rebase, with `--plutus-vm scarab`:
  Byron to Conway, with every projected and whole-state comparison matched and the Plutus cases accepted
  or refused as the reference does.
- **Preview from genesis**, at `b736b7fa7` on 2026-11-13, with `--plutus-vm scarab`: all 4,760,847 blocks
  of a Haskell node's Preview ChainDB, to block 4,760,846 at slot 125,430,904, replayed through the full
  block rules (`import-haskell-db`, then `follow --plutus-vm scarab --dump-state --dump-state-at
  epoch-boundaries` with its peer unreachable). The ledger state at every epoch boundary was compared with the
  `db-analyser` projection of cardano-node 11.1.3 for the same block
  (`oracle_compare.py diff --require-complete --expected-epochs 1451`): 1,451 of 1,451 compared and
  matched, 0 mismatched, none excluded. The node logged the own evaluator as its backend, and thread
  samples in the first epochs showed the own machine evaluating and no Scalus frame. Raw run data is
  not published.
- **Preprod from genesis**, at `2cf627c2` on 2026-11-14, with `--plutus-vm scarab`, beside the same build with
  `--plutus-vm scalus` as the control: all 5,406,450 blocks of a verified Mithril preprod snapshot, to block
  5,406,449 at slot 138,866,397, replayed through the full block rules each way (`import-haskell-db`, then
  `follow --dump-state --dump-state-at epoch-boundaries` with its peer unreachable). The ledger state at every
  epoch boundary from 5 to 325 was compared with the `db-analyser` projection of cardano-node 11.1.3 for the
  same block (`oracle_compare.py reference --byron-epochs 4 --byron-epoch-slots 21600 --epoch-slots 432000`,
  then `diff --require-complete --expected-epochs 321`): 321 of 321 matched with each evaluator, 0
  mismatched. Epoch 4, the first Shelley epoch, has no digest because the replay starts in Byron
  ([the oracle](../running/oracle.md)). Each run logged the evaluator it was given. Raw run data is not
  published.

## Explicitly unchecked or unconfirmed

- **Forging** with the own evaluator is refused until its evidence is reviewed (I2-WP11, owner decision).
- **Protocol 12 and later** (Leios, PlutusV4) get no verdict, by decision.
- **Mainnet:** no replay with the own evaluator is recorded.
- **Projection scope:** the Preview comparison covers the fields of the oracle's projection
  ([the oracle](../running/oracle.md)), not every byte of the state, and only the state at epoch
  boundaries. It compares no single script's verdict or budget with the Scalus backend's; a different
  verdict that left the projected state unchanged would not show.
- **Failure-path comparison:** the differential compares execution units and logs on successes only.
- **ExUnits conversion:** the conversion of a redeemer's `ExUnits` to a budget is not compared with cardano-ledger's own.
- **Finite samples:** no corpus shows equality for every program, size or cost-model list.
