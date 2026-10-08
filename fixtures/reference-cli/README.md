# Offline reference CLI evidence

Captured on 2026-10-08 UTC using the optional pinned Python harness. All six operations passed: three exact transaction IDs and three Conway JSON views. The version output and binary/archive hashes identify the official Linux x86-64 release artifact. This is decoding/hash evidence only, not a ledger, script, node, or network verdict.

`results.json` records commands, input/envelope hashes, expected and actual outputs, and statuses. `provenance.json` records official release/source links and the harness hash. `SHA256SUMS` checks every file in this evidence directory except itself. The official manifest is retained verbatim as `upstream-sha256sums.txt`; it authenticates consistency with the release publisher, not a signature or reproducible build.

The three JSON envelopes preserve existing `../raw/*-tx.cbor` bytes exactly. Outputs may reproduce data from those synthetic Cardano Ledger fixtures, whose Apache-2.0 license and NOTICE remain in `../licenses/`. No CLI/API implementation source, native executable, or archive is redistributed. See [reproduction and limitations](../../docs/offline-reference-checks.md).
