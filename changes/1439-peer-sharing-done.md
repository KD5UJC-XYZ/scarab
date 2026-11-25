### Fixed (peer sharing, #1439)

- A peer-share request to a peer whose connection is closing no longer fails or ends the connection. The close says
  PeerSharing's `Done` first, but the link stays in the outbound table while the connection manager holds the
  connection (`OutboundIdle` for the outbound idle timeout), and the governor may still ask a closing peer, as the
  reference's may. The request reached the client in `StDone`, failed with `IllegalTransition(10,
  PeerSharing(Terminal))`, aborted the connection and counted as a failed request against the peer; the mainnet sync
  that found it saw 82% of its requests fail so. The warm connection's PeerSharing client is now registered until its `Done`
  (`PeerSharingController`), as the reference's `bracketPeerSharingClient` registers its controller for the life of
  the client, and a request after the `Done` answers `NotRegisteredYet` without touching the connection, as
  `requestPeerSharingResult` does.
