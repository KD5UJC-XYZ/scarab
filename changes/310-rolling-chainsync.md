### Changed (networking, #310)

- Use the pinned native rolling low/high ChainSync policy on production connections,
  with configurable 200/300 request watermarks independent of body publication chunks.
- Preserve ordered replies and per-request Genesis historical context while handling
  receiver AwaitReply notifications during blocked validation or jumping decisions.
  Drain outstanding replies before intersection changes and abort canceled partial sends.
- Capture offered intersection points and block numbers atomically. Bound unpublished
  header history under slow body consumption without charging local capacity waits to peers.
