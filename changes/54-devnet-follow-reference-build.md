### Added (devnet follow under a reference build, #54)

- `scripts/devnet.py follow --reference-build <build>` passes the build on to the node's own
  `follow --reference-build`, so a follow of an `up-dijkstra` cluster can name `leios-b004fe0c`;
  without the flag the node's default build is unchanged. The node checks the value and still
  refuses `leios-b004fe0c`'s `follow` until it runs that build's rules.
- `devnet.py follow --consensus-mode` reads the node configuration the node itself finds in the
  run's genesis directory, so it also works on an `up-dijkstra` run, whose configuration is
  `config.json`.
