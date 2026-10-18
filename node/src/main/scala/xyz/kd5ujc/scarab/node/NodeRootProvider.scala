// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Applicative
import cats.syntax.all.*
import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.ledger.LedgerRelay
import xyz.kd5ujc.scarab.network.selection.*

/** The roots the governor asks for, from the topology and from the ledger
  * (`requestPublicRootPeersImpl`, `Cardano/Network/PeerSelection/PeerSelectionActions.hs` 40-100,
  * and `Ouroboros/Network/PeerSelection/PeerSelectionActions.hs` 125-184):
  *
  *   - in the sensitive state (bootstrap peers in use and the ledger too old), the bootstrap peers
  *     and nothing else;
  *   - otherwise the ledger peers the worker draws ([[LedgerPeersWorker]]); when the ledger gives
  *     none (ledger peers off, the ledger not yet at the `useLedgerAfterSlot` slot, or no pool with
  *     a relay) the public roots of the topology;
  *   - the request for big ledger peers is answered from the big ledger map only, and with nothing
  *     when there is none (the reference then offers the public roots, which are known already).
  *
  * A ledger relay is a peer address: an IP address, or a host name that is resolved when the
  * connection is made (`Transport.resolve`), so a name that does not resolve is a failed connect,
  * counted and backed off like any other. An SRV relay is dropped (D9). The answer of the ledger is
  * good for [[LedgerPeerStake.ReRequestInterval]], the time the governor waits before it asks
  * again; the configured roots for an hour.
  */
object NodeRootProvider:
  type Addr = SocketAddress[Host]

  /** The time a configured root, or an answer without a peer, is good for. */
  val ConfiguredTtl: FiniteDuration = 1.hour

  /** The peer address of a ledger relay, none for one this node does not connect to: an SRV domain,
    * a name that is not a host name, the unspecified address.
    */
  def address(relay: LedgerRelay): Option[Addr] = relay match
    case LedgerRelay.Address(ip, port) =>
      for
        a <- IpAddress.fromBytes(ip.octets.toArray)
        if !a.toBytes.forall(_ == 0)
        p <- Port.fromInt(port.toInt)
      yield SocketAddress[Host](a, p)
    case LedgerRelay.Domain(name, port) =>
      // a snapshot writes a domain with a trailing dot; the topology's spelling has none
      for
        h <- Host.fromString(name.stripSuffix("."))
        p <- Port.fromInt(port.toInt)
      yield SocketAddress[Host](h, p)
    case LedgerRelay.Srv(_) => None

  def apply[F[_]: Applicative](
      public: Set[Addr],
      bootstrap: Vector[Addr],
      worker: LedgerPeersWorker[F],
      advertise: Map[Addr, Boolean] = Map.empty
  ): RootProvider[F, Addr] = new RootProvider[F, Addr]:
    def request(max: Int, sensitive: Boolean) =
      if sensitive then
        Applicative[F].pure(
          Some(PublicRoots(Set.empty[Addr], bootstrap.toSet) -> ConfiguredTtl)
        )
      else
        worker.request[LedgerKind.All](max).map {
          case LedgerAnswer.FallBackToPublicRoots() =>
            Some(PublicRoots(public, Set.empty[Addr], advertise = advertise) -> ConfiguredTtl)
          case LedgerAnswer.Picked(relays, _) =>
            Some(
              PublicRoots(Set.empty[Addr], Set.empty[Addr], relays.flatMap(address).toSet) ->
                LedgerPeerStake.ReRequestInterval
            )
        }

    def requestBigLedger(max: Int) =
      worker.request[LedgerKind.Big](max).map {
        case LedgerAnswer.FallBackToPublicRoots() =>
          Some(Set.empty[Addr] -> LedgerPeerStake.ReRequestInterval)
        case LedgerAnswer.Picked(relays, _) =>
          Some(relays.flatMap(address).toSet -> LedgerPeerStake.ReRequestInterval)
      }
