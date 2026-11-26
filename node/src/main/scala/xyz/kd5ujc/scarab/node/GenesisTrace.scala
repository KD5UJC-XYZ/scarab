// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Applicative
import cats.data.NonEmptyVector
import xyz.kd5ujc.scarab.network.selection.ChainSyncJumping
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** The reference's traces of the Genesis decisions and of the chain selection starvation they read
  * (issue 1397). Each case is one reference trace, and a peer is named by the key the reference's
  * maps hold it under, its connection ([[PeerOrder]]).
  */
enum GenesisTrace:
  /** `TraceGDDDebug`: an evaluation of the GDD whose Genesis window is known, before it disconnects
    * the losing peers (ouroboros-consensus 82ecba329, `Genesis/Governor.hs:261-279`). `loeHead` is
    * the tip of the candidates' shared prefix, `genesisWindow` the window's length in slots.
    */
  case GddDebug(losingPeers: Vector[PeerOrder], loeHead: Point, genesisWindow: BigInt)

  /** `TraceGDDDisconnected`: the peers that evaluation disconnected, once they are told
    * (`Genesis/Governor.hs:281-283`, ouroboros-consensus 82ecba329).
    */
  case GddDisconnected(peers: NonEmptyVector[PeerOrder])

  /** A ChainSync jumping event of `peer` 's handle, as the reference labels it with the peer
    * (`TraceLabelPeer`; ouroboros-consensus 82ecba329, ouroboros-consensus-diffusion
    * `Ouroboros/Consensus/Node/Tracers.hs:100-101`).
    */
  case Csj(peer: PeerOrder, event: ChainSyncJumping.Event[PeerOrder])

  /** Devoted BlockFetch's rotation of the dynamo (`TraceEventDbf`). */
  case Dbf(event: ChainSyncJumping.RotatedDynamo[PeerOrder])

  /** `PeerStarvedUs`: chain selection starved while `peer` was the peer Genesis BlockFetch fetches
    * from, so it is demoted (ouroboros-network c45735a5,
    * `ouroboros-network/lib/Ouroboros/Network/BlockFetch/Decision/Genesis.hs:281-299`).
    */
  case PeerStarvedUs(peer: PeerOrder)

  /** `ChainSelStarvation`: chain selection found no block to add, or a block ended that
    * (ouroboros-consensus 82ecba329, `Storage/ChainDB/Impl/Types.hs:691-718`).
    */
  case ChainSelStarvation(edge: GenesisTrace.Edge)

object GenesisTrace:
  /** `Enclosing' (RealPoint blk)`, the edge `ChainSelStarvation` carries (ouroboros-consensus
    * 82ecba329, `Util/Enclose.hs:54-58`, `Storage/ChainDB/Impl/Types.hs:1101`): the start of a
    * starvation, or its end by the block at `point`.
    */
  enum Edge:
    case Rising
    case FallingWith(point: Point.Block)

/** Where the [[GenesisTrace]]s go: the node's log. */
trait GenesisEvents[F[_]]:
  def emit(event: GenesisTrace): F[Unit]

object GenesisEvents:
  /** Traces nothing. */
  def none[F[_]: Applicative]: GenesisEvents[F] = _ => Applicative[F].unit
