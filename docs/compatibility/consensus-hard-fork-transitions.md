<!-- SPDX-License-Identifier: Apache-2.0 -->
# Consensus: Shelley-based era transitions

Part of the [compatibility matrix](../compatibility.md); the register entry is `consensus-hard-fork-transitions` in [compatibility.json](../data/compatibility.json).

## Status as recorded

implemented: every path that decides which Shelley-based era a block is in asks `HardForkConfig.transition`, the reference's `singleEraTransition` (ouroboros-consensus 82ecba32 `ShelleyHFC.hs:220-227`): the epoch of a `Test<Era>HardForkAtEpoch` key, or the next epoch once the definite parameter update names exactly the next era's first major version and at least `k` blocks of the epoch followed its voting deadline. The paths are the ledger tick (`Hfc.tick`), the header envelope's expected era (`HeaderValidation`), the Genesis window's era summary (`GenesisHistory`) and the era a chain started from genesis is in (`HardForkConfig.initialEra`, as `injectInitialExtLedgerState` extends the genesis state to slot 0, `HardFork/Combinator/Embed/Nary.hs:259-306`: only configured epochs enter an era there, never the Shelley genesis's protocol version, which also no longer decides whether the Conway genesis feeds the start's parameters). A restored snapshot of the retired flat formats that does not know its after-voting counter (`Gap.ShelleyTransition`) has it recounted from the immutable store before replay (`TransitionRecount`): the blocks from the voting deadline to the anchor, which the reference's serialised state holds (`Ledger.hs:855-882`).

## Evidence

unit/property: `HardForkSuite` (exact version, a version above the target, a skipped era, `k - 1` against `k`, no definite update, epoch triggers, a genesis start), `TickRulesSuite` (the tick reads the counter it is given). Real blocks: `EraTransitionSuite` applies all 731 blocks of the `fixtures/eras` capture under a mainnet-style history, every Shelley-based era entered by version, so the five boundaries Shelley to Conway are crossed by the state's definite update and counter alone, reaching the same state as the configured-epoch history; a version the update does not name keeps the chain in Shelley; and `GenesisStartSuite` starts the devnet genesis at protocol 10 with only Shelley (then up to Mary) configured at epoch 0 in Shelley (Mary); and a restored state with the gap recovers the exact counter from an immutable store of the same chain.

## Explicitly unchecked or unconfirmed

a restored snapshot with `Gap.ShelleyTransition` whose immutable store holds no block before the tip epoch's voting deadline cannot be recounted; it keeps the gap and the counter it was written with until the next epoch boundary. No public-network capture of a version-triggered boundary is replayed here (the `fixtures/eras` cluster places the boundaries with `Test<Era>HardForkAtEpoch` and raises the version by update proposals in step). Byron to Shelley is decided by the Byron rules and is covered under `ledger-byron`.
