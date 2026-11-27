### Added (fuzz, #1418)

- Differential fuzz targets that compare Scarab's Ed25519 verification (`StrictEd25519`), VRF verification
  (`StrictDraft03`, `PraosVrfCertificate`) and VRF proving (`Draft03Prover`) with the libsodium the reference node
  links, IntersectMBO/libsodium `dbb48cce`, live and on the same inputs: verdicts, outputs, public keys and proofs,
  on fuzzer-chosen bytes and on valid signatures and proofs made from derived keys, mixed-order keys among them, then
  mutated toward small-order points, non-canonical encodings and scalars at and above L. The fuzz gate and the
  nightly campaign build that library from its pinned source archive (`scripts/sodium_oracle.py`) and fail unless
  the targets ran against it; elsewhere they are skipped. libsodium is not a dependency of the node or of any
  ordinary test run.
