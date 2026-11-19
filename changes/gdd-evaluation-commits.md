### Fixed (Ouroboros Genesis: density evaluation)
- **Each Genesis density evaluation lands, as the reference's GDD governor's does.** In Genesis mode the node
  evaluates the candidates of its ChainSync peers from one snapshot, disconnects the peers whose chains lose the
  density comparison and moves the Limit on Eagerness fragment, whatever ChainSync writes while the evaluation
  runs. The next evaluation waits out the configured GDD rate limit after every evaluation, and starts only once a
  peer's latest slot or idling, a connection or the GSM state differs from what was last evaluated. A disconnection
  reaches only a session that is still open, and an evaluation never pairs a peer's superseded chain with a newer
  selection. Before, an evaluation was dropped and started again at once whenever any peer's state was written
  meanwhile, an unchanged write included, so while ChainSync messages kept arriving density disconnections could be
  postponed and the LoE fragment could stand still.
