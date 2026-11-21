### Added (consensus, #54)

- The Leios build's endorser-block fetch decision, as pure code that the node does not run yet
  (`consensus.leios`). It mirrors ouroboros-consensus 2416493b. It covers:
  - the fetch state: what is tracked, which endorser block each election fetches, and what is in
    flight from each peer;
  - one pass of the decision over the peers' offers, with each peer's byte budget, the freshest
    endorser blocks first and the jobs of a closure;
  - the bookkeeping that arrivals, disconnects and the immutable tip do;
  - the checks of an answer against its jobs' commitments.
- The decision gives a peer jobs drawn by a mirror of the reference's `StdGen` (`random` 1.3.1 over
  `splitmix` 0.1.3.2). Its seeds, words, draws and splits equal the values the reference's own
  library prints (`scripts/oracle/leios-stdgen`).
