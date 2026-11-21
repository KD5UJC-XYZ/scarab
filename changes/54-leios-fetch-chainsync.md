### Added (node, #54)

- The Leios build's node learns over ChainSync what to fetch, mirroring ouroboros-consensus
  2416493b. Each header that header-first validation accepts is handed, with the header state of its
  predecessor, to the connection's Leios handling.
  - **Offers.** A header whose body carries a certificate offers the endorser block its predecessor
    announced, its body and its closure, at the closure bound of the predecessor's ledger view. A
    peer that claims two different endorser blocks certified for one election loses the connection,
    and is tried again later. An accepted Genesis jump records the offers of the headers it skipped.
  - **Announcements.** A new announcement, counted at most twice per election, makes the fetch logic
    track its endorser block and writes the announced point to the store. It is not relayed, because
    LeiosNotify does not run yet.
  - **Forecast and clock.** The Praos2 ledger view carries `maxEndorserBlockTxsSize`. The decision
    loop reads the closure bound forecast from the immutable tip, and the current slot that the
    node's blockchain time publishes from a fiber of its own.
