<!-- SPDX-License-Identifier: Apache-2.0 -->
# Typed per-kind decode sharing (#204)

Part of the [compatibility matrix](../compatibility.md); the register entry is `typed-decode-sharing` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: homogeneous kind/scope tables and typed cached-hash entries; weak association metadata, document-owned values

## Evidence

`InternsSuite` identity/kind/growth/concurrency/lifetime/compiler probes, unchanged full-state sharing and 256 MiB million-voter control; full Docker check 5,144 passed/16 optional skips; extended fuzz 77 targets/30.8 million cases pass; snapshot restart 30 applications/two V5 writes/one restore; complete old/new state/refusal fingerprints and retained counts match; [performance](../performance.md#34-typed-per-kind-decode-interning-204), [as-built ownership](../adr/0013-cbor-documents-are-an-index-over-the-input.md#addendum-typed-kind-ownership-204-2026-10-20)

## Explicitly unchecked or unconfirmed

Snapshot-payload decode, not backing RocksDB entries or full-node throughput. Runtime source `9ac7ea5e`; both representative primary inputs pass 10%; shared-host variation and weak metadata limits documented. [Extended-fuzz/exact-head receipts](https://github.com/KD5UJC-XYZ/scarab/pull/263). Ready status on dev does not establish main landing.
