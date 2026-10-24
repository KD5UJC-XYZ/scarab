<!-- SPDX-License-Identifier: Apache-2.0 -->
# Decoders under hostile input (CBOR, blocks and headers of every era, transaction bodies, witnesses and submitted transactions, addresses, Plutus data, node-to-client queries)

Part of the [compatibility matrix](../compatibility.md); the register entry is `decoders-hostile-input` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: every decoder returns a value or a typed error, in bounded time and memory, also for items nested beyond the thread's stack

## Evidence

structured fuzzing (K3): `fuzz` project, ScalaCheck generators with byte, length-field, nesting, indefinite-length and tag mutations of the committed fixtures and of valid items sampled from the pinned CDDL, deterministic seeds, properties never-throws, time and allocation bounds (a 10-byte input declaring 2^60 elements), decode-encode round trips; 400,000 cases per target in the longest run so far (4 seeds of 100,000), bugs found in [findings.md](../findings.md); `check` runs a one-minute budget, the nightly workflow `fuzz.yml` many seeds

## Explicitly unchecked or unconfirmed

fuzzing finds crashes, not wrong verdicts (that is the oracle's job); the generators reach 1 to 25 percent valid items per target, so deep ledger rules behind a valid transaction are exercised by the ledger suites, not by the fuzzer; storage files (snapshots, stores) and the JSON configuration are not fuzzed
