### Fixed (networking)

- Recover a ChainSync candidate whose intersection leaves the retained chain window by draining
  pending work and finding a new intersection on the same connection, keeping an inbound duplex
  follower active while other peers advance the local chain.
