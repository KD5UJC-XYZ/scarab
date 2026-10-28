<!-- SPDX-License-Identifier: Apache-2.0 -->
# Block forging

Part of the [compatibility matrix](../compatibility.md); the register entry is `block-forging` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: Praos (Conway) only; G7-F3 retains typed terminal storage/unavailable/internal/defect outcomes, prevents signing after key-update failure and preserves the KES failure event/metric; ECVRF draft-03 proving, Sum6 KES generate/sign/evolve, opcert loading; typed operational-certificate input/verifier causes retained through OCERT, credential checks and reload refusals (#82); KES signing-key validation returns closed error cases with depth/period/length payloads, preserved through `ForgeError.KesKey` and `KesEvolution` (#82)

## Evidence

IETF draft-03 vectors 16 and 17 reproduced exactly; KES verification key reproduced from `cardano-cli` key seed; `Sum6KesSigningSuite`, `KeyFilesSuite`, `KesRotationSuite` (exact error cases and payloads, caller bytes retained, operator refusal leaves the key unchanged, terminal failure prevents later signing); `ForgerFailureSuite` (real rule/store failures, deferred defects, missing-file classification, cancellation and queued signers); `ForgeSuite`; `SystemStartSuite` and the geometry properties (slot clock), `ForgeSuite` counters; devnet `kes-rotate` (key rotated four times while forging, H2a); devnet `pool` (Scala node as pool 1 of three: 86 forged, 85 adopted, Haskell blocks-made 85 against 83.2 expected, 151 transactions, 18 KES periods, 16/16 comparisons)

## Explicitly unchecked or unconfirmed

TPraos forging (H3); no Byron (PBFT) forging, a decision (H7); no KES agent (H2); the evolved KES key is written back to its file, which the Haskell node never does (H2a), and the KES key and certificate are reloaded on SIGHUP after a check the Haskell node does not make; opcert issuing is the driver `scripts/pool/rotate_kes.py` (`cardano-cli` does the signing), exercised by the devnet `kes-rotate` only; never run on a public network until epoch 321 on preprod, 2026-10-23 (H1/H1a, evidence log); mainnet not run; a slot the loop overran is not forged late, as in the reference, so at 1 CPU on a loaded host about one leader slot in a hundred runs was lost that way (evidence log; since #543 such a slot is passed over without a count, as the reference's `knownSlotWatcher` does); side-channel properties of the secret-scalar arithmetic not reviewed
