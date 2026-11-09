### Changed (connection manager, #854)
- **Connections take the reference connection manager's states and are counted as it counts them.**
  - **States:** `ReservedOutbound`, `Unnegotiated`, `OutboundUni`, `OutboundDup`, `OutboundIdle`, `InboundIdle`, `Inbound`,
    `Duplex`, `Terminating` and `Terminated` (ouroboros-network `2fb69829`, `ConnectionManager/State.hs`). Every move
    is one the reference's own `verifyAbstractTransition` accepts.
  - **Counts:** `cardano_node_metrics_connectionManager_{inbound,outbound,duplex}Conns_int` count each connection by what
    it is used for now, as `connectionStateToCounters` does. A reservation counts in no direction, and a connection in
    time-wait counts only as terminating.
  - **Where it shows:**
    - `scarab_connections{state}` uses the new state names;
    - `connection` log lines print the reference's state names (`ReservedOutboundSt`, `UnnegotiatedSt Outbound`, …);
    - `GET /connections` reports the reference's six counters (`fullDuplex`, `duplex`, `unidirectional`, `inbound`,
      `outbound`, `terminating`) in place of `established`.
  - **Dashboards:** the Grafana network and runs dashboards are updated to the new state names.
- **An outbound reservation that never connected is no longer held in time-wait.** As in the reference, it ends at once.
