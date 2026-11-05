### Changed (verification, #796)

- Inventory diagnostic producers across the main Scala modules and bind native error families to
  their pinned source identities. Preflight and CI reject unregistered producers, constructor drift,
  stale native pins and unsupported full-coverage claims. Incomplete exposure and oracle mappings
  remain explicit requirements. Existing CI and decoder-fuzz gates also run on stacked feature PRs.
