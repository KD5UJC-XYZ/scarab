### Fixed

- `scarab_peer_reconnects_total` now counts every lost peer connection. The node reported
  reconnects to the log only, so the counter stayed at zero while the log carried the
  `reconnecting` events; the report now goes to the log and the metrics together.
