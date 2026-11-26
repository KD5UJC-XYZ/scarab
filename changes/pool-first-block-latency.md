### Fixed (devnet pool scenarios)
- **The `pool` scenarios' verdict no longer depends on where the leader schedule puts the node's first slot.** The bound of
  ten slots from a slot's start to adoption was applied to every block, and the first block a node forges is built and adopted
  by a JVM that has not run that code yet: about 1.5 s when the slot fell within two seconds of start, a few ms otherwise
  (#1471). The first block the node adopts of those it forged now has a bound of its own (5 s), every later block keeps the ten slots, and
  `pool-summary.json` reports both (`adoptionLatency`).
