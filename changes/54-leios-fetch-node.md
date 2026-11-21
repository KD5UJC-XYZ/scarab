### Added (node, #54)

- The Leios build's node fetches endorser blocks over LeiosFetch, mirroring ouroboros-consensus
  2416493b. The endorser blocks it is offered come over ChainSync; LeiosNotify offers none yet.
  - **Registration.** Under `--reference-build leios-b004fe0c`, with an endorser-block store, every
    node-to-node connection carries protocols 18 and 19, hot and started on demand. Each hot
    activation runs the LeiosFetch client, and the server answers from the store; a clean end of
    the activation stops the client and waits for it. The LeiosNotify server holds a peer's
    requests with nothing to notify and answers its quit, so the peer can demote the connection and
    promote it again. A release build's connections are unchanged.
  - **Decision loop.** The fetch decision runs at most every half second once woken. Its state
    starts from the store's complete closures and is pruned at the immutable tip. A connection
    activated again while the decision runs is assigned nothing until the next one.
  - **Answers.** A body is checked against the endorser block asked for, written and held with
    its closure's jobs; transactions are checked against their jobs and written. An answer that is
    not what was asked for ends the connection, in the reference's order of checks. A write waits
    for a slot of the writer's queue, 27 deep, so a slow store holds back the answers. A failure of
    the store stops the node and is not charged to the peer.
