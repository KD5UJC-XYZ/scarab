### Changed (network, #462)

- **A released connection is held in time-wait for 60 s**, as the reference's connection manager holds it in `TerminatingState` (`defaultTimeWaitTimeout`, ouroboros-network `2fb69829`): meanwhile an outbound connection to that address is refused (`ConnectionError.TimeWait`, a transient failure the peer backs off from), an inbound one from it is taken, and it counts neither as inbound nor as outbound. A peer is therefore dialled again no sooner than 60 s after its last connection ended.
