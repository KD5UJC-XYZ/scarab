// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import xyz.kd5ujc.scarab.protocol.Handshake

/** How many bytes a peer may have sent on one mini-protocol that the protocol has not read yet (the
  * multiplexer's ingress queue limit). The multiplexer never waits for a slow reader: a peer that
  * goes past the limit has broken the protocol, and the connection ends with
  * [[NetworkError.IngressLimit]] (`Network.Mux.Ingress.demuxer`, `IngressQueueOverRun`).
  *
  * The limits are those of the reference, derived from what the protocol itself allows a well
  * behaved peer to have in flight (`Cardano.Network.NodeToNode`, `chainSyncProtocolLimits` and its
  * neighbours, ouroboros-network 1.2.0.0, the version cardano-node 11.1.3 pins, with network-mux
  * 0.11.0.0; the same functions are in 1.3.0.0). Derived queue budgets hold `x + x / 10`
  * (`addSafetyMargin`) of the worst case; handshake, discovery and local-interface budgets use
  * their explicit bounds below.
  */
object IngressLimits:
  /** The parameters the limits are derived from (`defaultMiniProtocolParameters`).
    * @param chainSyncPipeliningHighMark
    *   `chainSyncPipeliningHighMark`: the most ChainSync requests the client keeps pipelined.
    * @param blockFetchPipeliningMax
    *   `blockFetchPipeliningMax`: the most blocks in one BlockFetch request.
    * @param maxUnacknowledgedTxIds
    *   `maxUnacknowledgedTxIds` of `defaultTxDecisionPolicy`.
    */
  final case class Parameters(
      chainSyncPipeliningHighMark: Int = 300,
      blockFetchPipeliningMax: Int = 100,
      maxUnacknowledgedTxIds: Int = 10
  )

  /** `addSafetyMargin`: ten percent more. */
  def withMargin(x: Long): Long = x + x / 10

  /** The largest message ChainSync carries is a roll-forward with a block header; 1400 bytes is the
    * reference's bound (`maxBlockHeaderSize` of the genesis).
    */
  val ChainSyncHeaderBytes = 1400L

  /** The largest block of BlockFetch's bound per block (88 KiB plus the envelope) and the byron
    * era's bound of ten blocks of two MiB, whichever is larger (`blockFetchProtocolLimits`).
    */
  val BlockFetchBlockBytes = 90112L
  val BlockFetchByronBytes = 10L * 2097154L

  /** `max_TX_SIZE` (`TxSubmission/Inbound/V2/Policy.hs`) and the 44 bytes a reply to one id adds.
    */
  val TxBytes = 65540L
  val TxIdReplyBytes = 44L

  /** KeepAlive: one small outstanding message. */
  val KeepAliveBytes = 1280L

  /** Leios notify and fetch (protocols 18 and 19) are not in ouroboros-network 1.2.0.0: they come
    * from the leios prototype build, whose limits are not published. Only tools run them; this is
    * the largest message the wire codec accepts, with the margin.
    */
  val LeiosBytes = 100000000L

  /** Byte budgets by mini-protocol number, with a small fallback for protocols without a dedicated
    * formula. This function does not register protocols: [[Mux]] separately rejects SDUs for
    * channels that the connection has not opened.
    */
  def nodeToNode(parameters: Parameters = Parameters()): Int => Long = {
    case Protocols.Handshake => Handshake.MaxMessageBytes.toLong
    case Protocols.ChainSync =>
      withMargin(parameters.chainSyncPipeliningHighMark.toLong * ChainSyncHeaderBytes)
    case Protocols.BlockFetch =>
      withMargin(
        math.max(
          BlockFetchByronBytes,
          parameters.blockFetchPipeliningMax.toLong * BlockFetchBlockBytes
        )
      )
    case Protocols.TxSubmission2 =>
      withMargin(parameters.maxUnacknowledgedTxIds.toLong * (TxIdReplyBytes + TxBytes))
    case Protocols.KeepAlive                          => withMargin(KeepAliveBytes)
    case Protocols.LeiosNotify | Protocols.LeiosFetch => LeiosBytes
    // the protocol numbers of node-to-client never share a connection with these (see below)
    case Protocols.LocalChainSync | Protocols.LocalTxSubmission | Protocols.LocalStateQuery |
        Protocols.LocalTxMonitor =>
      NodeToClientBytes
    // PeerSharing (10) is `4 * 1440`, one round trip of TCP segments.
    case _ => 4L * 1440L
  }

  /** Node-to-client: every queue is as large as the field can say (`maximumMiniProtocolLimits`,
    * `0xffffffff`). The peer is a local process, not a network peer.
    */
  val nodeToClient: Int => Long = _ => NodeToClientBytes

  val NodeToClientBytes = 0xffffffffL
