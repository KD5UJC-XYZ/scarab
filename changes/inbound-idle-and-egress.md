### Changed (inbound connections and egress buffering, security)
- **An accepted connection that sends nothing on a mini-protocol within 5 s of its handshake is closed**, as the reference's
  inbound governor does (`defaultProtocolIdleTimeout`, ouroboros-network `2fb69829`).
- **At most 8 connections are accepted from one IP address at once** (`maxInboundPerAddress`, a typed
  `ConnectionError.AddressLimit`). The reference has no per-address limit; the reason is in ADR 0008.
- **`--inbound-sync` is off by default.** Pass `--inbound-sync on` to run our clients on inbound duplex connections.
- **Multiplexer egress is bounded in bytes per channel** (`egressBufferBytes`, 0x3ffff, a soft limit as in network-mux's
  `egressSoftBufferLimit`) instead of in messages across the connection.
