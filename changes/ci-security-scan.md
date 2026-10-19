### Added (Verification)

- A `security.yml` CI workflow: semgrep with the repo's crypto rules (failing on any
  WARNING-level finding) and the crypto guard, which keeps the variable-time Java math
  (`Draft03Math`, `Ed25519Point`) referenced only from files under
  `*/src/{main,test}/{scala,java}/xyz/kd5ujc/scarab/crypto/vrf/` that declare that package.
  The guard is a containment rule: the secret-scalar data-flow invariant itself remains the
  review of #219 together with the timing probe (AUD2-27/#219, AUD2-33/#234).
