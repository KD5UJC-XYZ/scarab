### Fixed

- Inbound duplex ChainSync and BlockFetch clients now contribute to the same peer usefulness
  metrics as outbound clients, attributed to the remote socket address. Header usefulness is
  credited after acceptance while preserving arrival timestamps; serving-only connections do
  not receive client credit.
