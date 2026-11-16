### Fixed (devnet, #785)
- **A same-point whole-state comparison no longer fails on a follower tip that a fork has just taken off the
  reference's chain.** When the reference refuses the point as not on its chain (`PointNotOnChain`), `e2e` and `soak`
  sample the follower's tip again, a second later, at most twice; both answers are still taken at exactly one point,
  and a refusal past that bound, a point too old, a sampled point that is not a block point, a reply the protocol does
  not allow (judged on its bytes) or a byte difference still fails. Every comparison keeps its acquisitions (stage, node, point, outcome,
  parsed refusal) in its record as they happen, and an error also its kind, its stage and the point selected by then.

### Fixed (devnet)
- **`e2e` judges its follower's whole log and how it ended.** It stops the follower on purpose before it judges
  it (its peer frozen, its final tip recorded once two readings agree, SIGTERM), so an error logged or an exit
  after the wait for the target epoch fails the run; a follower that ended by itself before the stop is a premature
  exit, and one that ends on the stop with another status than 143, a kill after the limit among them, fails. A
  completed log whose last line has no newline fails. With `--oracle` the oracle's forced removal ends the follower
  instead, and only its log is judged.
