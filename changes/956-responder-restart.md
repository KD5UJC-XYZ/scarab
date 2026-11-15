### Fixed (network, #956)

- **A conversation that starts in the SDU that ended the last one is served.** A peer may cut its byte stream into
  SDUs as the mux protocol allows, so a mini-protocol's `MsgDone` and the first message of its next conversation can
  share one SDU. The responder that should then start again waited for an SDU that would never come, since the bytes
  were already in the channel's buffer: on a connection the node accepted or dialled, the peer's ChainSync or KeepAlive
  was left without an answer. A responder now starts again on bytes left in its channel, as the reference's mux returns
  a finished job's trailing bytes to the ingress queue that starts the next responder (network-mux `Mux.hs` 345-351 at
  ouroboros-network c45735a5). On a connection that has closed, the responder ends with the reason instead of starting
  again.
- **A message to a responder that is running no longer starts KeepAlive again.** Once a peer had ended its KeepAlive
  conversation, any message it then sent to another running responder (a PeerSharing or ChainSync `MsgDone`, which a
  peer that demotes us to cold sends in any order) started our KeepAlive responder again. The connection did not go
  idle, and 97 s later the KeepAlive server's timeout ended it with an error, where the reference releases it at the
  5 s protocol idle timeout, or keeps it while our outbound side uses it. KeepAlive (`StartOnDemandAny`) now starts on
  a byte on its own channel or with an on-demand responder that starts, as in the reference (network-mux `Mux.hs`
  444-450, 571-577 and 588-591 at ouroboros-network c45735a5).
