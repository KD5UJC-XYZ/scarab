### Changed (era types, #85)
- **Conway's flat BBODY is gone, and the flat block rules take only the eras before Conway.** The flat Conway
  BBODY (`rules.Bbody`, with its environment and state) is deleted; the node has applied Conway blocks with
  Conway's era run since the Conway switch. The flat block rules (`EraBlock`) take a body only on a state of an era
  before Conway, with its era's evidence, so a Conway or Dijkstra state cannot reach them by type; the tick stays
  every era's. The block's own era is not in the type: that a block's era is its state's is checked before, as it
  was.
  Before the deletion, the last comparison of the two on the recorded preprod window agreed on all 5,683 Conway
  blocks. `ConwayBodiesReplaySuite` becomes `EraBodiesReplaySuite`, the era run alone.
