### Changed (the Leios reference build's name and its re-pins, #1365)

- The Leios testnet's reference build is `--reference-build leios-rc-2026w47`, named by the release it is pinned at
  (cardano-node b9c1dbea, ouroboros-consensus d91b3557, the same cardano-ledger as before). It replaces
  `leios-b004fe0c`, which is no longer taken as the flag.
- A build's former names are read from files as the build they became, while the layout is the same. Snapshots,
  refusal corpora and captures recorded under `leios-b004fe0c` are read as `leios-rc-2026w47` instead of being
  deleted as unknown. This is a storage format change approved by the owner (FMT-1): no new version, no migration.
- The playbook has the steps for following each weekly Leios release.
- The devnet's Leios clusters run the rc-2026w47 image.
