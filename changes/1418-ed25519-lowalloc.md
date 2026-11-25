### Changed (crypto, #1418)

- **An Ed25519 verification allocates about 6 KB instead of about 480 KB.** `StrictEd25519` (transaction witnesses,
  operational certificates, the KES leaf and the Plutus builtin `verifyEd25519Signature`) and `ByronEd25519` compute
  with `VartimeEd25519`, the vendored curve25519 arithmetic written over working storage that one verification makes
  and drops, and hash the key, signature and message without copying them, only after the key, R and S pass their
  checks, in the order they did. Strict verification takes about 0.72 of the time it did, and a signature refused by
  its key, R or S about a third. What is accepted, and every refusal and failure, is unchanged: every operation gives
  the vendored one's limbs, and a differential suite holds both verifiers' results to the vendored ones on valid,
  mutated, edge and malformed inputs. The arithmetic is variable time; only the two verifiers and their package's
  tests may name it (the compiler and `scripts/check_crypto_guard.py`), and VRF proving keeps the vendored
  constant-time operations.
