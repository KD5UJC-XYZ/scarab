<!-- SPDX-License-Identifier: Apache-2.0 -->
# Local ChainSync bytes beyond the signed-32-bit boundary

Status: Current. Executed reference evidence for [#749](https://github.com/KD5UJC-XYZ/scarab/issues/749).

On 2026-11-05 the native ChainSync codec and plain `runPeer` driver accepted an actual
2,147,483,649-byte opaque serialized payload, fed in 1-MiB pieces. Its complete re-encoding
matched the original 2,147,483,661-byte message. `expected.json` contains the exact input recipe
and complete payload/wire SHA-256 identities; `native.jsonl` records the encoding and decoding
observations for this one case. No multi-gigabyte fixture is stored.

The [observer and reproduction instructions](../../../scripts/oracle/chainsync-wide-serialised/README.md)
and `provenance.json` bind the source, executable, packages and output. The native `Serialised`
instance treats the payload as opaque bytes: tag 24 followed by a definite byte string.
Internal storage chunks must not change that wire representation.

The consuming protocol integration's `ChunkedChainSyncSuite` compares the complete logical
payload and encoded message with these identities using shared bounded chunks. Until that
consumer is integrated, this native capture alone is not a Scarab conformance pass.

This case demonstrates the recorded local codec/driver capacity. It does not demonstrate a
ledger-valid block, forging, persistent storage/restart, unrestricted sizes, or ordinary
node-to-node propagation. The pinned BlockFetch receive policy is 2,500,000 bytes; it is a
different boundary. Wider production validation and storage remain separate work under #749.
Bulk input expansions, native build products and raw run artifacts are not published.
