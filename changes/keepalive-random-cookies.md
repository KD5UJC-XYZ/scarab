### Fixed (network)

- The KeepAlive client draws its cookies from a random generator, as the reference's client draws them from a
  `StdGen`, instead of counting up from 0 on each connection. The client of each connection seeds a generator of its
  own from the platform's secure generator, where the reference gives every connection of a node run the same one;
  this deviation is approved (decisions register, networking). A response must still carry the cookie of the
  request in flight; any other ends the connection as a protocol violation. A cookie is 16 bits wide, as the
  protocol defines it, so a guessed answer still matches one ping in 65,536.

### Changed (network)

- The KeepAlive client pings every 10 seconds, as the reference's client does (`KeepAliveInterval 10`), instead of
  every 30 seconds, so the round-trip samples that block fetch ranks peers by are taken as often as the reference
  takes them.
