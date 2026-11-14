<!-- SPDX-License-Identifier: Apache-2.0 -->
# SCLS binary interchange (K8, #77)

Part of the [compatibility matrix](../compatibility.md); the register entry is `scls-interchange` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: pinned `ledger-f649-conway-v0`, all twelve namespaces, complete-base binary import/export, exact point/full identity admission, bounded staging and durable no-clobber publication; native V5/CBOR retained

## Evidence

[Reference fixtures](../../fixtures/scls/README.md), actual 6,060-byte Haskell artifact reproduced from native snapshot/checkpoint, typed mapper/dormancy/proposal vectors; 14 complete-base integration tests, native immutable-point import/re-export, native format goldens and scoped publication rollback controls; full check 4,932 passed/16 optional-data skips, explicit Scalafix, 76 extended fuzz targets and private restart passed

Dated evidence (2026-11-13): the import rebuilds the governance proposals over the imported roots
with `mkProposals`, as the reference's `recreateProposals` does
(cardano-ledger f649f975, `CanonicalState/Conway/Import.hs:396-401`), and refuses a proposal that
attaches to neither its lineage's root nor an earlier proposal of its lineage
(`SclsStateMapperSuite`; the reading rule is in [the state-file entry](haskell-ledger-state-file.md#evidence)).

Dated evidence (2026-11-14): the state mapper orders the imported proposals as the reference's
import does (#968): sorted by their order value, the arrival order kept for equal values, and the
first of a repeated id kept (cardano-ledger f649f975, `CanonicalState/Conway/Import.hs:219-227`;
`libs/cardano-data/src/Data/OMap/Strict.hs:178-184,227-231`); the order values need not run from 0
to n-1 (`SclsStateMapperSuite`). This is not visible through the import service: the reference's
export numbers the proposals from 0 (`CanonicalState/Conway/Export.hs:191-199`), as Scarab's does,
the reader refuses keys that do not strictly increase, so no file repeats an id, and complete-base
admission refuses a file whose proposal rows differ from the base's own projection.

## Explicitly unchecked or unconfirmed

Requires an explicit complete Conway base and matching target immutable point; SCLS alone omits restart/consensus state. Pinned profile differs from draft CIP namespace list; no future-profile or standalone-bootstrap claim. Haskell full-state equality retains the documented MemPack-constructor discrepancy; large acceptance datasets are not committed. [Design](../design/scls-interchange.md).
