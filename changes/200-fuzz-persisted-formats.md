### Added (fuzzing, #200)

- Seeded fuzz targets for the node's volatile log, the configuration file and the topology file,
  registered in the target list so the default suite, the nightly campaign and the fixed-seed gate
  run them.
- The fuzzing coverage inventory names the persisted formats and operator JSON that are covered, and
  the persisted readers that are not, including the checkpoints file the run path reads.
