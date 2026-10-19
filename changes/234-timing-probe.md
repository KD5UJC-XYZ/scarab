### Added (Crypto, #234)

- A diagnostic timing probe for the crypto math (`TimingProbeMain`): fix-vs-random secret
  classes, percentile bucketing, Welch's t per bucket, with a measured noise control and a
  noise-relative leak verdict. Targets: the curve25519 `point.multiply` constant-time claim,
  the `Ed25519Point.scalarMultiply` known variable-time path (its public-inputs-only contract),
  and the `uniform` public-input control. Protocol in `docs/performance.md` section 32; it is a
  manual tool, not part of `check`.
- A contamination guard: a repetition whose measured noise floor exceeds the contamination
  bound (1,000; clean floors observed at or below ~54, contaminated at ~9,000 and up) is
  retried once and then discarded without verdicts, instead of emitting noise-inverted
  results.
