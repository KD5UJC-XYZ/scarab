### Added (Verification)

- A `security.yml` CI workflow: semgrep with the repo's crypto rules (failing on any
  WARNING-level finding) and the crypto guard, which keeps the variable-time Java math
  (`Draft03Math`, `Ed25519Point`) referenced only from inside the `vrf` package — their
  public-inputs-only contract, now tool-enforced (AUD-27/#219, AUD-33/#234).
