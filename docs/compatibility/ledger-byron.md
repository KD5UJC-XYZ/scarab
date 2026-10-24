<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: Byron

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-byron` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: UTxO with witnesses (Byron signing tags, address roots, network magic), value conservation, linear fee, AVVM and non-AVVM genesis UTxO, delegation certificates, update proposals, votes and endorsements, adoption at the epoch boundary, the transition epoch to Shelley derived from the update state (or `TestShelleyHardForkAtEpoch`), the Byron UTxO carried into the Shelley ledger

## Evidence

real mainnet blocks (AVVM redemption, verification-key transactions with one and two inputs: Merkle proofs, signatures, roots, fees), the preprod Byron era (proposals, votes, derived transition at epoch 4 equal to the network's), synthetic signed cases; devnet e2e Byron to Shelley UTxO comparison

## Explicitly unchecked or unconfirmed

software-update proposal format checks (system tags, application names) not enforced; no preprod Byron transaction exists, so the UTxO rules are pinned by mainnet blocks (not contiguous, state not replayed); mainnet-scale UTxO memory and snapshot size not measured; the mempool reads current epoch geometry; the forger still takes the epoch history at start-up (restart after the transition on a derived chain); ledger-lite bootstrap mode needs a configured Shelley epoch
