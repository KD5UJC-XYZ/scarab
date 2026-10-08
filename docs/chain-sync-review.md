> Public export note: historical run logs and raw diagnostic output are retained only in the private research archive. Their mentions below document prior evidence; they are not bundled public artifacts or newly executed public checks.

# Independent pure ChainSync review

Review completed 2026-10-08 UTC against the pinned network/consensus sources and
version 0.10.0 Scala implementation. The reviewer did not edit the implementation
or run sbt; the implementation worker separately ran the full terminal checks.

No remaining correctness blocker was identified. Review checked:

- Exact message arities, state/agency matrix, double Await and terminal Done
- Source-exact nonempty candidate encoding, ordered intersection semantics and Origin tip normalization
- Checked UInt64, exact payload bytes, retained suffix handling and bounded declared lengths
- Explicit opaque NtN/NtC separation and placeholder/typed-example evidence distinctions
- Failed-intersection cursor retention, unoffered intersections and unknown rollback errors
- Portable 9 envelope + 4 binary + 2 license checks, including SHA-256, sizes and Git blob IDs where recorded
- Pure-only CLI/documentation boundaries, with no transport or Cardano validation claim

Two review findings were corrected before acceptance: standalone point/tip APIs
now enforce complete structural item/depth limits; narrow payload adapters now
reject indefinite outer/header and tag24 byte-string forms. Dedicated negative
tests cover both fixes. Final tests also cover the exact 65535-byte message boundary
and an independently specified literal script driving the semantic model to Done.

The full formatted build passed 252 tests, preserving all 215 previous tests.
See `chain-sync-build-verification.log` and `chain-sync-regression-verification.log`.
This review does not establish runtime interoperability, valid headers/blocks,
ledger rollback, adversarial-network readiness or whole ChainSync transcript provenance.
