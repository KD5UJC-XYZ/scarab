### Fixed (header clock, #153)
- **Incoming headers are held to the node's clock**, as the reference does (`HeaderInFutureCheck`, skew 2 s): a header whose slot
  begins within the tolerable skew is held back until its slot begins and is then validated; one whose slot begins later is
  refused with a typed error (`HeaderError.FarFuture`) and the peer is dropped and forgotten. The check applies to live
  ChainSync sessions with and without `--header-first`; replays and tools are unaffected.
