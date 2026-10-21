### Fixed

- Use the pinned reference's ChainSync MustReply deadlines: a fresh random 601–911 seconds
  for each untrustable-peer state, and no deadline for trustable outbound local roots.
  Preserve a single budget across partial reads and capture trustability when scheduling
  activation; inbound clients always use the untrustable profile.
