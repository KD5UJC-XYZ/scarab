### Changed (networking, #310)

- Use the pinned native rolling low/high ChainSync policy on production connections,
  with configurable 200/300 request watermarks independent of body publication chunks.
- Preserve ordered replies and per-request Genesis historical context while handling
  receiver AwaitReply notifications during blocked validation or jumping decisions.
  Drain outstanding replies before intersection changes and abort canceled partial sends.
- Retain advertised tips on both intersection outcomes, including rejected Genesis
  jumps and empty checkpoint grafts.
- Capture offered intersection points and block numbers atomically. Bound unpublished
  header history under slow body consumption without charging local capacity waits to peers.
- Count collected rolling replies in the existing ChainSync header metric; round
  aggregates remain specific to the legacy batch facade.
