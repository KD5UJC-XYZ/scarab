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
  * 0.11.0.0; the same functions are in 1.3.0.0). Every queue holds `x + x / 10` (`addSafetyMargin`)
  * of the worst case.
  */
object IngressLimits:
  /** The parameters the limits are derived from (`defaultMiniProtocolParameters`). */
  final case class Parameters(
      /** `chainSyncPipeliningHighMark`: the most ChainSync requests the client keeps pipelined. */
      chainSyncPipeliningHighMark: Int = 300,
      /** `blockFetchPipeliningMax`: the most blocks in one BlockFetch request. */
      blockFetchPipeliningMax: Int = 100,
      /** `maxUnacknowledgedTxIds` of `defaultTxDecisionPolicy`. */
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

  /** The node-to-node limits for each mini-protocol, a function of its number. Protocols the
    * reference has no queue for get none (a peer cannot send on them: the multiplexer ends the
    * connection for data on an unexpected protocol).
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
