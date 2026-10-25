### Changed (#474 rows 31 and 34: mux SDU size and state time limits follow the reference)
- **SDUs are sent at 12,288 bytes and accepted up to 65,535.** The multiplexer cut messages at the largest
  accepted payload, 65,535 bytes; the reference socket bearer, node-to-client sockets included, sends
  12,288 (`Network/Mux/Bearer.hs:83-86`) and accepts any length that fits the header. `Mux.Config.sduSize` is the
  send size, `maxSduPayload` stays the acceptance limit. The handshake keeps its 10 s SDU timeout, which is
  the reference's (`ConnectionHandler.hs:98-102`), the other protocols 30 s.
- **State time limits are the reference's.** The server no longer gives BlockFetch (in node-to-node) an idle timeout of 3,373 s, TxSubmission2 an Init limit
  or an Idle limit of 3,373 s, or the node-to-client protocols (LocalStateQuery, LocalTxSubmission, LocalTxMonitor,
  local ChainSync) and the node-to-client handshake any state limit: the reference waits for ever in those states
  (`timeLimitsBlockFetch`, `timeLimitsTxSubmission2`; `runPeer` in `mkApps` of ouroboros-consensus 82ecba32;
  `noTimeLimitsHandshake`). The node-to-node ChainSync server keeps its Idle limit of 3,373 s, which is
  `defaultChainSyncIdleTimeout` of the reference, and the other states are unchanged.
