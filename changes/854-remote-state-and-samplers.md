### Added (network, #854)
- **The inbound governor's remote states are tracked and counted, as the reference's are.** Each connection the peer can
  use (accepted, or dialled duplex) is `RemoteIdle`, `RemoteWarm` while the peer runs our KeepAlive or PeerSharing
  responder only, `RemoteHot` while it runs a ChainSync, BlockFetch or TxSubmission2 responder, or `RemoteCold` when
  the idle timeout passed and our outbound side keeps it (`InboundGovernor.hs`, ouroboros-network `2fb69829`). A
  responder that fails or is cancelled stops the counting, as the reference's mux tracer does. The counts are exported
  under cardano-node 11.1.3's names: `cardano_node_metrics_inboundGovernor_idle_int`, `_cold_int`, `_warm_int` and
  `_hot_int`.

### Fixed (network, #854)
- **The governor's bounded random draws are uniform, drawn with the reference's samplers.** The relay of a ledger pool
  and the bonus of a stay in `CaughtUp` use `uniformR`'s bitmask with rejection (a pool of one relay takes no draw), the
  weighted SRV pick random's `Word16` draw, and the fuzz of a reconnection or retry delay is a `Double` number of
  seconds scaled as `random-1.3.1` scales it, floored to the nanosecond, where it was a whole number of milliseconds.
  The remainder sampler, which favoured the low values of a range slightly, is gone.
