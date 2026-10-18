### Added (Leios reference research, #73)

- Pinned, checksum-verified w38a and newer Leios reference images and a private probe scenario,
  with wire captures and regression tests. The runs distinguish build-specific message numbering,
  notification credit limits and transaction reply formats; hot peers require mux protocols 18/19.
- Reliable probe cleanup, trace flushing and a single-deadline duplex silence observer. Production
  Dijkstra following and committee/protocol integration remain the later stages of #73.
