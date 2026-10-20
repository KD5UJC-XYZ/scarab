# Pinned upstream mux SDU codec cases

`cases.tsv` is a bounded seeded export from the real `network-mux:test` property
`Test.Mux.tests / "sdu codec" / prop_sdu_codec`. The rows come from that pinned test module's
unchanged `Arbitrary SDUCodecCase` instance and each row is run through the unchanged upstream
property. The observer-only overlay is generated in a disposable copy by
[`export.py`](../../../scripts/oracle/network-mux/export.py); the upstream checkout remains
read-only. The adjacent manifest records the input source hash, exact commit, seed, exporter patch,
resolved Cabal plan, and output hash.

The source is ouroboros-network commit
`2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`, file
`network-mux/test/Test/Mux.hs` (SHA-256
`4db2e8967fa712334341d70f6f465655db337d54c471ccba1bcc8a9d743191a2`). The upstream repository
and this test source are Apache-2.0; see its `LICENSE` and `NOTICE`.

Forty cases were generated from seed `490049`; each row seed is the base plus its ordinal and the
QuickCheck size cycles from 30 through 50. All 40 upstream property checks passed. The Scala
consumer is `MuxUpstreamConformanceSuite`. It compares Haskell's encoded bytes with the independent
`Data.Binary` bytes retained in each row, then exercises Scarab's typed encode/decode boundary.
All 40 encoded rows match the original native bytes, including six empty SDUs. As in the native
property, those six encode successfully but are rejected by the decoder. Production message
admission independently refuses empty messages before writing any bytes; allowing the pure wire
encoder to represent them does not permit empty frames onto a live channel.

This corpus is a bounded codec probe, not the full `network-mux:test` suite, protocol-codec suite,
or an Ouroboros-network conformance claim. The ledger Agda trace adapter and broader protocol suite
coverage remain separately tracked gaps under #49.
