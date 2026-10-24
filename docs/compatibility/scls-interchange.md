<!-- SPDX-License-Identifier: Apache-2.0 -->
# SCLS binary interchange (K8, #77)

Part of the [compatibility matrix](../compatibility.md); the register entry is `scls-interchange` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: pinned `ledger-f649-conway-v0`, all twelve namespaces, complete-base binary import/export, exact point/full identity admission, bounded staging and durable no-clobber publication; native V5/CBOR retained

## Evidence

[Reference fixtures](../../fixtures/scls/README.md), actual 6,060-byte Haskell artifact reproduced from native snapshot/checkpoint, typed mapper/dormancy/proposal vectors; 14 complete-base integration tests, native immutable-point import/re-export, native format goldens and scoped publication rollback controls; full check 4,932 passed/16 optional-data skips, explicit Scalafix, 76 extended fuzz targets and private restart passed

## Explicitly unchecked or unconfirmed

Requires an explicit complete Conway base and matching target immutable point; SCLS alone omits restart/consensus state. Pinned profile differs from draft CIP namespace list; no future-profile or standalone-bootstrap claim. Haskell full-state equality retains the documented MemPack-constructor discrepancy; large acceptance datasets are not committed. [Design](../design/scls-interchange.md).
