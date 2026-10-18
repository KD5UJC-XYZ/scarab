# Executed Plutus API boundaries

Source, tool, image, dependency pins and reproduction:
[`scripts/oracle/plutus/README.md`](../../../scripts/oracle/plutus/README.md).
Executed2026-10-18 with the complete Haskell cryptography backend.

- `scripts.jsonl`: original CBOR-wrapped Flat bytes of53 probes. Malformed Value literals retain
  their order, duplicates, empty maps and zero quantities; no text-parser round trip supplies them.
- `verdicts.jsonl`:477 actual reference rows, one per probe ×V1/V2/V3 ×protocol9/10/11. Each binds
  script bytes and canonical JSON cost-model lists by SHA-256 and records decoder admission and
  success/decode/setup/scope/budget/runtime failure stage. The Scala suite checks every row.
- `hash-vectors.jsonl`: independently exported compressed G1/G2 points from the pinned Haskell
  crypto module, used as assertions inside the scripts. Empty, ASCII, high-bit, zero-containing and
  maximum-length tags are represented; no String conversion supplies expected points.

The coverage includes the original22 conformance exceptions; malformed Value keys/quantities and
nested constants; type headers31/33; constr fields1024/1025; checked and deferred variable scope;
64-bit narrowing and65-bit rejection; case-on-builtins; and restricting-mode dropList failures.
The direct API admits/rejects scripts independently from complete transaction rules. REF-01/02/03
transaction-context evidence and #171's malformed Byron reference gap remain separate.

`transactions/conway-pv10.json` holds four actual reference-node11.1.3 submissions on a short private
Conway/protocol10 cluster. The original binary-DST assertion was wrapped in one context lambda;
the failing control evaluates that assertion before `error`. The success script is accepted with
isValid=true and rejected with isValid=false (PassedUnexpectedly); the failure control is rejected
with isValid=true (FailedUnexpectedly) and accepted with isValid=false via collateral. Accepted
transactions were observed in blocks. `V1ContextSuite` reuses its phase2/LEDGER/BBODY harness for
these fixtures and agrees at all three levels. No public network or follower was involved.

`transaction-scripts.jsonl` preserves the two exact scripts. Their only transformation from the
closed hash-G1-90 probe in scripts.jsonl was the context lambda, and, for the failure control, a
strict application that evaluates the probe then returns `error`. The recorded scripts are well
formed; no malformed Value constant is round-tripped through a text parser.
