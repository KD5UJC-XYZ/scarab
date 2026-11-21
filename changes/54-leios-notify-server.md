### Added (node, #54)

- The Leios build's node serves LeiosNotify and relays endorser-block announcements, mirroring
  ouroboros-consensus 2416493b. A peer fetches only an endorser block it was offered, so its peers
  can now fetch the endorser blocks this node holds.
  - **Server.** Each request is a credit, held and answered in order from the peer's queue.
    A quit cancels the requests still held, then ends the conversation. A request that finds 1,000
    credits held ends the connection.
  - **Relay.** A new announcement, from a peer's ChainSync or of an endorser block the node forged,
    goes to every peer whose server runs and holds a credit. A second announcement for the same election goes only to the peers the first
    went to. With no credit, the announcement is dropped for that peer.
  - **Offers.** The endorser-block store now reports each body and each closure it acquires. Each
    server offers them to its peer once the peer was announced that endorser block, each part
    once, and only at `LeiosMinOfferLead` or more slots past the immutable tip (3,600 by default).
    The node now uses `LeiosMinOfferLead` from its configuration under the Leios build.
  - Votes are not relayed, and the node runs no LeiosNotify client.
