### Changed (networking)

- Coordinate BlockFetch across inbound and outbound peers using the pinned reference's
  Praos ranking, global bulk/deadline peer targets and per-peer request/byte windows.
- Pipeline outgoing range requests with ordered collection, incremental credit release
  and cancellation cleanup; retain typed failures through session shutdown.
- Compare scheduling decisions with independently exported upstream-generated cases.
