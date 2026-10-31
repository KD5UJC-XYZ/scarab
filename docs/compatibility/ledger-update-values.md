<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: pre-Conway parameter-update values decoded with the body, decoder versions 2 to 9 (#470)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-update-values` in [compatibility.json](../data/compatibility.json). The sections below record the implementation, evidence and remaining limits.

## Status as recorded

implemented: below decoder version 12 the reference reads each value of a `PParamsUpdate` at the type of its field when it decodes the body (`SparseKeyed`, `mkField`, `Core/PParams.hs:257-293`, cardano-ledger `f649f975`). `ParamUpdate.read` now does the same for Shelley to Babbage. The keys every Shelley-based era shares (0 to 11, 16, 17, 19 to 24) use Conway's readers (`ConwayParam.everyEra`). `d` (12) is a `UnitInterval`, the extra entropy (13) a `Nonce`, the minimum UTxO value (15) a `Word64`, and the protocol version (14) is read as `decodeProtVer` reads it: a `Word32` major at most one above the era's `ProtVerHigh` (Shelley 3, Allegra 4, Mary 5, Alonzo 7, Babbage 9) and a `Word32` minor. `Prices` and `ExUnits` are `RecD` records, definite lists only below version 12 (`decodeCount`, `Decoding/Coders.hs:482`), in Conway as well. A refusal is a typed `LedgerDecodeError`. The update keeps the original bytes of each value, which are what is compared, stored and hashed.

## Evidence

`UpdateValuesReferenceSuite`: 450 original bodies (`fixtures/reference/update-values/cases.jsonl`, oracle `scripts/oracle/conway-decoding/update_value_cases.py`, harness unchanged). In each era from Shelley to Babbage, every update key except the cost models at the bound of its type and one over, and once per type a wide head, a negative integer, the wrong major type, the wrong length and a zero denominator; the protocol version at the era's bound, one over, 13 and 14; each arity of the extra entropy. Conway bodies set `Prices` and `ExUnits` definite and indefinite. 161 accepted, 289 refused, each refusal recorded with the fragment of the reference failure. The suite also applies a stored update the body would refuse.

## Explicitly unchecked or unconfirmed

An update held in a stored ledger state is read as the builds before #470 stored it (ADR 0006): a pre-Conway update by `ProtocolParams.update` when it is applied, and a Conway proposal by `ConwayParamUpdate.decodeStored`, which takes `Prices` and `ExUnits` as indefinite lists too. Proposals are still counted for the quorum by their bytes, not their decoded values ([#669](https://github.com/KD5UJC-XYZ/scarab/issues/669)). Other `RecD` records reachable from a block are [#668](https://github.com/KD5UJC-XYZ/scarab/issues/668).
