### Fixed (node-to-client, #924)
- **`cardano-cli query kes-period-info` is answered at once.** It asks for the tip over a second connection, with a
  local ChainSync `RequestNext` sent before any `FindIntersect`. The reference's ChainSync follower answers that first
  request with a roll-back to Origin and the tip, and now Scarab's does too. Before, a node without `--db` whose chain was
  longer than `k` answered `AwaitReply` and then never replied. The node-to-node server uses the same follower, so it
  changes the same way.
- **A ChainSync client whose next block the node no longer holds is disconnected, not left waiting.** This applies to a
  node without `--db` when the client's next block has left the volatile chain: a client starting from Origin
  (`cardano-cli debug log-epoch-state`) or a client more than `k` blocks behind. The connection now ends, and the log
  records the reason (`the block after … is not kept`). The reference always keeps its immutable chain, so it has no
  such case.

### Fixed (logging, #924)
- **`extended.peerTip` is the tip a peer announced.** Since the selection worker took over block adoption, every
  `Chain.Extended` line had `peerTip` at Origin, and the `scarab_peer_tip_slot` and `scarab_peer_tip_block` gauges stayed
  at 0. Both now take the tip from the last ChainSync reply that any session received. Before any peer has announced a
  tip, `peerTip` is `null` and the gauges are left unchanged. A block this node forged now carries the same value; before,
  it carried its own point. On the devnet `peerTip` was usually one block behind the block just adopted.
