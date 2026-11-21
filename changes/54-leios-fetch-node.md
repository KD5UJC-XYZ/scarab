### Added (node, #54)

- The Leios build's node fetches endorser blocks over LeiosFetch, mirroring ouroboros-consensus
  2416493b. Nothing offers it an endorser block yet, so on a network it fetches nothing until the
  offers are wired.
  - **Registration.** Under `--reference-build leios-b004fe0c`, with an endorser-block store, every
    node-to-node connection carries protocols 18 and 19, hot and started on demand. Each hot
    activation runs the LeiosFetch client, and the server answers from the store. Nothing runs on
    LeiosNotify yet. A release build's connections are unchanged.
  - **Decision loop.** The fetch decision runs at most every half second once woken. Its state
    starts from the store's complete closures and is pruned at the immutable tip.
  - **Answers.** A body is checked against the endorser block asked for, written and held with
    its closure's jobs; transactions are checked against their jobs and written. An answer that is
    not what was asked for ends the connection, in the reference's order of checks. A failure of
    the store stops the node and is not charged to the peer.
