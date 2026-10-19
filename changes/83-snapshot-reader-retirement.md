### Changed (snapshot versions and migration procedure, #83)

- Retain native snapshot V5 and its fingerprint, with fixed current-format fixture bytes and verified
  historical conversion provenance. Recognize retired V1–V4 identifiers but remove their unused
  runtime payload readers; pinned historical source and one-shot recipes remain recoverable.
- Check identity and snapshot compatibility before opening mutable stores. Retired and unknown
  versions refuse startup and direct restoration instead of silently causing replay. Current corrupt
  snapshots retain recovery behavior; checkpoint-key migration remains supported.
- Document future storage migrations, atomic restart behavior, semantic/gap invariants, consistent
  snapshot/checkpoint backups and the verified local-data conditions for reader retirement. Existing
  pinned producers retain their compatible binary until a separately approved database conversion and upgrade.
