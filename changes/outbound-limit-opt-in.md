### Changed (outbound connections)
- **The node has no outbound connection limit by default**, as the reference's connection manager has none: the peer
  governor's targets bound what it dials. An opt-in limit stays available: `MaxOutboundConnections` in the
  configuration, or `--max-outbound N`, which wins. A deviation approved by the owner, recorded in the decisions
  register.
- **When set, the limit counts only the connections the node dialled and still uses**, from the reservation until the
  governor lets the connection go or the dial ends, whatever the connection's state; a reused connection of the peer's
  never counts. Connections to local roots (`--peer` included) are left out, as the governor classifies the peer when the
  dial reserves its connection: their dials are never counted and never refused, and a peer that becomes a local root
  once connected gives its place back.
  A counted dial at the limit is refused with `ConnectionError.OutboundLimit`.
- **A limit below the governor's largest established target plus its largest established big ledger target is refused
  at start-up** (`OutboundLimitBelowTargets`, naming the limit and that threshold). The threshold is not a promise that
  no dial is refused: after a reload or a new answer for big ledger peers, the counted dials can exceed it until the
  surplus connections close, so a limit leaves room above it. A topology reload is never refused because of the limit.
