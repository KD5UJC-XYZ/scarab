### Fixed (network, #956)

- **A conversation that starts in the SDU that ended the last one is served.** A peer may cut its byte stream into
  SDUs as the mux protocol allows, so a mini-protocol's `MsgDone` and the first message of its next conversation can
  share one SDU. The responder that should then start again waited for an SDU that would never come, since the bytes
  were already in the channel's buffer: the peer's ChainSync or KeepAlive was left without an answer. A responder now
  starts again on bytes left in its channel, as the reference's mux returns a finished job's trailing bytes to the
  ingress queue that starts the next responder (network-mux `Mux.hs` 345-351 at ouroboros-network c45735a5). On a
  connection that has closed, the responder ends with the reason instead of starting again.
