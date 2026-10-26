<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: Conway

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-conway` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented incl. scripts, all certificates, treasury donation, the protocol-11 pool rule (VRF key-hash uniqueness)

## Evidence

unit/property; devnet soak (24/24, 41/41 and, with the extended governance load, 44/44) and e2e (Conway 30 comparisons, matched); Preview sync from epoch 646 (Conway, protocols 9 to 11); protocol-11 rules checked against the reference's refusals: 5 taken-VRF registrations refused with `VRFKeyHashAlreadyRegistered` and 5 around them accepted, a reference input that is also an input refused with `BabbageNonDisjointRefInputs` at 10, accepted without scripts at 11 and refused for a Plutus V3 script with `CollectErrors (BadTranslation ReferenceInputsNotDisjointFromInputs)`; the refused bytes replay through our rules (`DevnetRefusedTxSuite`)

## Explicitly unchecked or unconfirmed

native pool rules maintain the reference-counted VRF registry, and the protocol-11 transition populates it. Fresh native genesis retains injected pools with an empty registry, as the reference does; previously written or imported state still needs its own provenance checks ([#129](https://github.com/KD5UJC-XYZ/scarab/issues/129)); Plutus V3 reference-input refusal only replayed in a unit test, not from the refused bytes; see governance row
