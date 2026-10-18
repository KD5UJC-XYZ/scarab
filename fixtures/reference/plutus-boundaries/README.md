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
