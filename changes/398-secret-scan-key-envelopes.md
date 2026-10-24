### Changed (secret scan, #398)

- **Cardano key envelopes are scanned.** `.gitleaks.toml` adds a rule for cardano-cli TextEnvelope signing keys (a `"type"` naming a `SigningKey` with a `"cborHex"` value) and a rule for `*.skey` paths. `scripts/check_secret_scan.py` generates a synthetic signing key per run and asserts that it is flagged, and that a verification key is not.
- **The `fixtures/` allowlist is per file.** The path-wide exception for `fixtures/` is replaced by one entry for each of the nine fixture files that the full-history scan reports for the default `generic-api-key` rule. A new fixture with a flagged value needs its own entry.
- **Two unreachable commit ids are dropped** from the secret-scan allowlist; the two commits remaining in the allowlist are still ancestors of `main`.
