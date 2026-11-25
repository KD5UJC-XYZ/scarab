// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import com.comcast.ip4s.{IpAddress, Ipv4Address, Ipv6Address, SocketAddress}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import xyz.kd5ujc.scarab.network.{ConnectionId, PeerSharingAddress}
import xyz.kd5ujc.scarab.network.selection.ChainSyncJumping.{Event, Reason}
import xyz.kd5ujc.scarab.node.{GenesisTrace, PeerOrder}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.protocol.PeerSharing.Address

/** The lines of the Genesis traces ([[GenesisTrace]]), with the fields cardano-node 11.1.3 gives
  * them at its default detail level (`DNormal`): `Cardano/Node/Tracing/Tracers/Consensus.hs`
  * 676-684 (`PeerStarvedUs`), 898-944 (the GDD), 2010-2047 (CSJ) and 2088-2097 (Devoted
  * BlockFetch), and `Tracers/ChainDB.hs` 128-132 (`ChainSelStarvation`). As in the other lines of
  * the reference's traces, its `kind` is left out where it is the event's name, and kept for the
  * two kinds of `TraceGDDEvent` and for `ChainSelStarvation`.
  */
private[log] object GenesisLines:
  /** `show` of a `SockAddr` (network's `showSockAddr`): `a.b.c.d:port`, `[v6]:port`. */
  private def shown(address: SocketAddress[IpAddress]): String = address.host match
    case v4: Ipv4Address => s"$v4:${address.port}"
    case v6: Ipv6Address => s"[$v6]:${address.port}"

  private def socket(endpoint: ConnectionId.Endpoint): Option[SocketAddress[IpAddress]] =
    PeerSharingAddress.socket(endpoint match
      case ConnectionId.Endpoint.Inet4(port, address)          => Address.V4(address, port)
      case ConnectionId.Endpoint.Inet6(port, _, a, b, c, d, _) => Address.V6(a, b, c, d, port))

  /** A peer as `forMachine` writes its `ConnectionId` (ouroboros-network c45735a5,
    * `ouroboros-network/tracing/Ouroboros/Network/Tracing/ConnectionId.hs:15-19`): its local and
    * remote addresses, shown and joined by a space. A peer of a test, which has no connection, is
    * its number.
    */
  private def peer(order: PeerOrder): Json = order match
    case PeerOrder.Tcp(connection) =>
      Json.obj(
        "connectionId" -> (socket(connection.local), socket(connection.remote))
          .mapN((local, remote) => s"${shown(local)} ${shown(remote)}")
          .asJson
      )
    case PeerOrder.Model(value) => Json.obj("connectionId" -> value.asJson)

  /** A peer as `toJSON` writes its `ConnectionId`, as `PeerStarvedUs` does (ouroboros-network
    * c45735a5, `ouroboros-network/orphan-instances/Ouroboros/Network/OrphanInstances.hs:487-497,
    * 518-522`): each address's IP and its port, both as text.
    */
  private def peerJson(order: PeerOrder): Json = order match
    case PeerOrder.Tcp(connection) =>
      def address(endpoint: ConnectionId.Endpoint): Json =
        socket(endpoint).fold(Json.Null)(a =>
          Json.obj("address" -> a.host.toString.asJson, "port" -> a.port.toString.asJson)
        )
      Json.obj(
        "localAddress" -> address(connection.local),
        "remoteAddress" -> address(connection.remote)
      )
    case PeerOrder.Model(value) => Json.obj("connectionId" -> value.asJson)

  private def reason(r: Reason): Json = (r match
    case Reason.Disengage  => "BecauseCsjDisengage"
    case Reason.Disconnect => "BecauseCsjDisconnect"
  ).asJson

  /** `forMachine` of an anchor (cardano-node 11.1.3, `Cardano/Node/Tracing/Formatting.hs` 67-76),
    * without its `blockNo`, which the GDD's fragments do not hold (a gap, #1397).
    */
  private def anchor(p: Point): Json = p match
    case Point.Origin => Json.obj("kind" -> "AnchorGenesis".asJson)
    case Point.Block(slot, hash) =>
      Json.obj(
        "kind" -> "Anchor".asJson,
        "slot" -> slot.value.asJson,
        "headerHash" -> hash.hex.asJson
      )

  /** `forMachine` of a point (cardano-node 11.1.3, `Formatting.hs` 47-57). */
  private def point(p: Point): Json = p match
    case Point.Origin => Json.obj("kind" -> "GenesisPoint".asJson)
    case Point.Block(slot, hash) =>
      Json.obj(
        "kind" -> "BlockPoint".asJson,
        "slot" -> slot.value.asJson,
        "headerHash" -> hash.hex.asJson
      )

  /** `forMachine` of a real point (cardano-node 11.1.3, `Formatting.hs` 59-65). */
  private def realPoint(p: Point.Block): Json =
    Json.obj("kind" -> "Point".asJson, "slot" -> p.slot.value.asJson, "hash" -> p.hash.hex.asJson)

  private def csj(label: PeerOrder, event: Event[PeerOrder]): Line =
    val by = "peer" -> peer(label)
    event match
      case Event.BecomingObjector(previous) =>
        EventKind.BecomingObjector.line(by, "previousObjector" -> previous.map(peer).asJson)
      case Event.BlockedOnJump       => EventKind.BlockedOnJump.line(by)
      case Event.InitializedAsDynamo => EventKind.InitializedAsDynamo.line(by)
      case Event.NoLongerDynamo(next, why) =>
        EventKind.NoLongerDynamo.line(
          by,
          "newDynamo" -> next.map(peer).asJson,
          "reason" -> reason(why)
        )
      case Event.NoLongerObjector(next, why) =>
        EventKind.NoLongerObjector
          .line(by, "newObjector" -> next.map(peer).asJson, "reason" -> reason(why))
      case Event.SentJumpInstruction(target) =>
        EventKind.SentJumpInstruction.line(by, "jumpTarget" -> point(target))

  /** The `kind` of `ChainSelStarvationEvent`'s lines, which is not the event's name. */
  private val starvation = "kind" -> "ChainSelStarvation".asJson

  def line(event: GenesisTrace): Line = event match
    case GenesisTrace.GddDebug(losing, head, window) =>
      EventKind.TraceGDDEvent.line(
        "kind" -> "TraceGDDDebugInfo".asJson,
        "losingPeers" -> losing.map(peer).asJson,
        "loeHead" -> anchor(head),
        "sgen" -> window.asJson
      )
    case GenesisTrace.GddDisconnected(peers) =>
      EventKind.TraceGDDEvent.line(
        "kind" -> "TraceGDDDisconnected".asJson,
        "peers" -> peers.toVector.map(peer).asJson
      )
    case GenesisTrace.Csj(label, e) => csj(label, e)
    case GenesisTrace.Dbf(rotated) =>
      EventKind.RotatedDynamo.line(
        "oldPeer" -> peer(rotated.oldPeer),
        "newPeer" -> peer(rotated.newPeer)
      )
    case GenesisTrace.PeerStarvedUs(starved) =>
      EventKind.PeerStarvedUs.line("peer" -> peerJson(starved))
    case GenesisTrace.ChainSelStarvation(GenesisTrace.Edge.Rising) =>
      EventKind.ChainSelStarvationEvent.line(starvation, "risingEdge" -> true.asJson)
    case GenesisTrace.ChainSelStarvation(GenesisTrace.Edge.FallingWith(block)) =>
      EventKind.ChainSelStarvationEvent.line(starvation, "fallingEdge" -> realPoint(block))
