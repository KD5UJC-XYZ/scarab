<!-- SPDX-License-Identifier: Apache-2.0 -->
# Per-state provenance (#129)

Part of the [compatibility matrix](../compatibility.md); the register entry is `per-state-provenance` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented in live state and the existing V5 gap/observed fields: genesis/import, block transitions, rollback, save/restore/rewrite; native Shelley voting-deadline counter

## Evidence

Frozen V5 era files rewrite byte-for-byte and retain fingerprint `7c483457b06802d6`; complete native imports, incomplete metadata, repeated reopen, rollback and actual block-boundary regressions

## Explicitly unchecked or unconfirmed

Missing historical likelihoods remain marked. Arbitrary imported delegator counts need three witnessed rotations; restore restarts that conservative proof. Operational preprod/mainnet rehearsal and approved cutover remain [#129](https://github.com/KD5UJC-XYZ/scarab/issues/129), under [storage migrations](../storage-migrations.md#per-state-provenance-upgrades-129)
