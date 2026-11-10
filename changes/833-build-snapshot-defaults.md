### Fixed (reference configuration)

- Resolve the reference's LedgerDB snapshot-delay defaults using `--reference-build`: 5–10
  minutes for `11.1`, and 5 minutes–6 hours for `leios-b004fe0c`. Explicit complete delay pairs
  retain their values. These settings remain recognised but unused by Scarab's snapshot scheduler
  (#833, #826).
