### Fixed (nightly fuzz and cuddle cross-check after the Leios N3 work)
- **An envelope in an indefinite-length array is refused** (`HfcHeader`, `NotAnEnvelope`; an indefinite embedded header
  is `NotEmbeddedCbor`), as the reference's `decodeListLen` and `decodeBytes` refuse them. It used to decode and be
  written back as a definite array (fuzz seed 20743303, `docs/findings.md`). The `leios-header-envelope` fuzz property
  is now that the canonical encoding of what decodes decodes to the same header and is stable.
- **The cuddle cross-check has the `leios-notify:leiosNotifyMessage` root** (the generated `LeiosNotifyMessage`), with
  the same float divergence as the ChainSync header it carries listed in `models/cuddle-allowlist.tsv`.
- **`fuzz <cases> <seed> <seeds> <target>` filters by target** (the filter was passed to munit as a glob and failed with
  a regex error).
