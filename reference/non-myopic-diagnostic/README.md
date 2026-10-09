# Finite supplied non-myopic native comparison

This original Apache-2.0 harness records raw stored binary32 likelihood observations.
See [model and evidence limits](../../docs/conway-non-myopic.md) and the
[portable manifest](../../ledger/src/test/resources/non-myopic/native-provenance.json).

The exact native Haskell source and pinned direct dependencies are retained here.
Building requires separately provisioned matching native dependencies; no binary,
build cache or dependency sources are bundled. Default Scala tests replay the golden.

`prepare_comparison.py NATIVE.json NEW.tsv` validates the exact pinned cases and
native schema, then emits ordered raw-bit TSV without reimplementing arithmetic.
The committed TSV is byte-identical to the independent native comparison transport.
Ten admitted raw-bit cases match, two Scala overflow cases reject, and two empty-go
completion/application probes match. Three generation probes remain diagnostics only.
The original execution logs and closure receipts remain private; binary identity is
receipt-backed. Producer labels and local hashes alone are not native-state authority.
