### Changed (build, #381)

- The node's run wiring in `Main` is split into named phases (instruments, rules, storage, the
  selected chain, header checks, block supply, connections, services and the run), with the same
  capabilities and the same order of effects. The run's `for`-comprehension no longer sits at the
  compiler's stack limit, so a cold compile of the node module does not depend on a large compiler
  thread stack. The node's behaviour is unchanged.
