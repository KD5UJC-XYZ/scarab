### Fixed (scheduling, #596)

- The block rules of every block received from a peer run on the node's CPU pool (`--cpu-threads`)
  again. Since the selection worker adopts every peer block, they had run on the runtime's default
  pool beside the timers and the forger; the worker now places them as a forged block's are.
