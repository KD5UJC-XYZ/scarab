### Added (reference build 11.2)

- `--reference-build 11.2` names the cardano-node 11.2 release line, pinned at 11.2.1, the reference
  target since 2026-11-14. Its ledger state layout is not implemented yet, so `follow`,
  `import-ledger-snapshot` and `export-ledger-snapshot` refuse it at start; the default stays `11.1`.
- `scripts/prepare_reference_image.py --release 11.2.1` builds `cardano-reference-11.2.1:local` from
  the checksum-pinned 11.2.1 release archive, and `devnet.py up --node-image [N=]IMAGE` runs the
  devnet's pools, or pool N, on the `cardano-node` of another reference image.
