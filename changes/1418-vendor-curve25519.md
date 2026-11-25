### Changed (build, #1418)

- The Edwards-curve arithmetic behind Ed25519 verification and VRF proving is built from the source of
  curve25519-elisabeth 0.1.3 (MIT), vendored in `crypto/src/main/java/com/weavechain`; the jar is no longer a
  dependency. Provenance is in `docs/licenses.md`.
