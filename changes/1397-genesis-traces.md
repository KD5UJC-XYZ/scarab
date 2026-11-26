### Added (Genesis traces, #1397)

- The log carries the reference's traces of the Genesis decisions, each under the reference's name and
  severity with its fields: the GDD's evaluations and disconnections (`traceGDDEvent`), ChainSync jumping's
  role changes and jumps (`becomingObjector`, `blockedOnJump`, `initializedAsDynamo`, `noLongerDynamo`,
  `noLongerObjector`, `sentJumpInstruction`), Devoted BlockFetch's rotation of the dynamo (`rotatedDynamo`),
  Genesis BlockFetch's demotion of a peer that starved chain selection (`peerStarvedUs`) and the edges of
  chain selection's starvation (`chainSelStarvationEvent`).
- As in the reference's mainnet configuration, only `peerStarvedUs` is written by default; `--log-severity`
  turns the others on (for example `--log-severity file:Chain=Debug`), as `TraceOptions` does there.
