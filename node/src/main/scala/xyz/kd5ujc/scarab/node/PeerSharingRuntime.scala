// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Clock, Ref, Resource}
import cats.effect.std.{Mutex, Queue}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, IpAddress, SocketAddress}
import scala.concurrent.duration.FiniteDuration
import xyz.kd5ujc.scarab.network.{DuplexPeers, PeerSharingAddress, PeerSharingServer}
import xyz.kd5ujc.scarab.network.selection.{GovernorState, PeerShare, Rng}
import xyz.kd5ujc.scarab.protocol.ConnectionState.DataFlow
import xyz.kd5ujc.scarab.protocol.PeerSharing.Address

/** The governor's public view and the inbound governor's duplex peers. This stores values only; the
  * server never captures the governor's MTL scope. DNS addresses retain the IP that actually
  * connected.
  */
final class PeerSharingRuntime[F[_]: Async] private (
    published: Ref[F, Vector[Address]],
    salting: Ref[F, PeerShare.Salting],
    resolved: Ref[F, Map[PeerNetwork.Addr, SocketAddress[IpAddress]]],
    duplex: Ref[F, DuplexPeers[PeerNetwork.Addr, Boolean]],
    pending: Ref[F, Vector[PeerSharingRuntime.Registration]],
    arrived: Queue[F, Unit],
    stepping: Mutex[F],
    changes: Queue[F, Unit]
):
  import PeerSharingRuntime.Registration

  /** The inbound peers the outbound governor reads (`readInboundPeers`): the mature duplex peers,
    * each with whether it negotiated peer sharing, as the steps of [[maturing]] left them
    * (`mkPublicState`, `InboundGovernor/State.hs` 74-83, ouroboros-network `c45735a5`).
    */
  def inbound: F[Map[PeerNetwork.Addr, Boolean]] = duplex.get.map(_.mature)

  def changed: F[Unit] = changes.take

  /** A connection the inbound governor tracks, for as long as the resource is held: once, from the
    * connection's own handshake. A duplex connection registers its peer as fresh; the end of any
    * tracked connection unregisters its address from the fresh and the mature peers alike
    * (`NewConnection`, `InboundGovernor.hs` 385-391, and `unregisterConnection`,
    * `InboundGovernor/State.hs` 208-222, at ouroboros-network `c45735a5`; the same at `4646039f`
    * and `2f1c154f`). The tracked connections are those the server accepted, of either data flow,
    * and those the node dialled whose handshake agreed on duplex, as the reference's connection
    * manager announces them (`ConnectionManager/Core.hs` 982-989 and 1594-1630).
    */
  def tracked(
      remote: SocketAddress[IpAddress],
      flow: DataFlow,
      sharing: Boolean
  ): Resource[F, Unit] =
    val peer = SocketAddress[Host](remote.host, remote.port)
    val registered = flow match
      case DataFlow.Duplex         => queued(Registration.Registered(peer, sharing))
      case DataFlow.Unidirectional => Async[F].unit
    Resource.make(registered)(_ => queued(Registration.Unregistered(peer)))

  /** `event` waits in `pending` for a step, which only the steps take from; `arrived` wakes a step
    * that waits, and carries nothing.
    */
  private def queued(event: Registration): F[Unit] =
    pending.update(_ :+ event) *> arrived.offer(()) *> changes.offer(())

  /** The inbound governor's steps over its duplex peers, for as long as it runs
    * (`inboundGovernorStep`, `InboundGovernor.hs` 242-275 and 547-556 at ouroboros-network
    * `c45735a5`, 244-277 and 590-602 at `4646039f`, 243-276 and 574-586 at `2f1c154f`). A step
    * reads the time once, before it waits, and matures the peers due then. If none is due and no
    * registration or unregistration is pending, it waits for one, or for
    * [[DuplexPeers.InactionTimeout]]; then it handles all that are pending. The wait takes no
    * event: the events stay pending until the step takes them all, so a timeout that ends the wait
    * as an event arrives loses none.
    *
    * A registered peer is fresh from the time the step handles it, read after the wait; the
    * reference stamps it with its step's time, read before the wait (`OrdPSQ.insert … time`,
    * 385-391 at `c45735a5`). The reference's inbound governor also takes a step on its connections'
    * other events, which this loop does not. So an address becomes mature only once some
    * registration of it still in force has been handled at least [[DuplexPeers.MatureDelay]]
    * earlier, and a single registration that no other replaces matures within one more
    * [[DuplexPeers.InactionTimeout]] when the steps run promptly; the reference can mature the same
    * address earlier or later, an approved deviation (`docs/decisions.md`, networking). One loop
    * runs at a time: another waits for it.
    */
  def maturing: F[Nothing] =
    val F = Async[F]
    val awaited: F[Unit] =
      pending.get.flatMap(waiting =>
        F.race(arrived.take, F.sleep(DuplexPeers.InactionTimeout)).void.whenA(waiting.isEmpty)
      )
    val step: F[Unit] =
      for
        time <- Clock[F].monotonic
        peers <- duplex.get
        matured = peers.maturedPeers(time) match
          case (due, rest) if due.nonEmpty => Some(peers.matured(due, rest))
          case _                           => None
        _ <- awaited.whenA(matured.isEmpty)
        _ <- F.uncancelable(_ =>
          (pending.getAndSet(Vector.empty), Clock[F].monotonic).flatMapN((events, at) =>
            duplex.set(events.foldLeft(matured.getOrElse(peers))(handled(at)))
          )
        )
      yield ()
    stepping.lock.surround(step.foreverM)

  private def handled(time: FiniteDuration)(
      peers: DuplexPeers[PeerNetwork.Addr, Boolean],
      event: Registration
  ): DuplexPeers[PeerNetwork.Addr, Boolean] =
    event match
      case Registration.Registered(peer, sharing) => peers.registered(peer, time, sharing)
      case Registration.Unregistered(peer)        => peers.unregistered(peer)

  def connected(peer: PeerNetwork.Addr, remote: SocketAddress[IpAddress]): F[Unit] =
    resolved.update(_ + (peer -> remote))

  def publish(state: GovernorState[PeerNetwork.Addr]): F[Unit] =
    resolved
      .modify { known =>
        val retained = known.filter((peer, _) => state.peers.contains(peer))
        (retained, retained)
      }
      .flatMap { ips =>
        val candidates = state.peers.toVector.flatMap { (peer, record) =>
          val ip = peer.host match
            case address: IpAddress => Some(SocketAddress(address, peer.port))
            case _                  => ips.get(peer)
          ip.flatMap(PeerSharingAddress.wire)
            .map(address =>
              PeerShare.Candidate(
                address,
                record.advertise.contains(true),
                record.everConnected,
                record.failCount
              )
            )
        }
        published.set(PeerShare.shareable(candidates))
      }

  val source: PeerSharingServer.Source[F] = amount =>
    for
      now <- Clock[F].monotonic
      peers <- published.get
      salt <- salting.modify { before =>
        val (salt, after) = before.at(now)
        (after, salt)
      }
    yield PeerShare.answer(salt, amount, peers)

object PeerSharingRuntime:
  /** What the inbound governor's steps learn of the connections it tracks, in the order it happens:
    * a duplex connection's `NewConnection`, and the unregistering of a tracked connection when it
    * ends (`unregisterConnection`, on `MuxFinished` or on a `CommitRemote` that closes it).
    */
  enum Registration:
    case Registered(peer: PeerNetwork.Addr, sharing: Boolean)
    case Unregistered(peer: PeerNetwork.Addr)

  def create[F[_]: Async](seed: Long): F[PeerSharingRuntime[F]] =
    (
      Ref.of[F, Vector[Address]](Vector.empty),
      Ref.of[F, PeerShare.Salting](PeerShare.Salting.start(Rng(seed))),
      Ref.of[F, Map[PeerNetwork.Addr, SocketAddress[IpAddress]]](Map.empty),
      Ref.of[F, DuplexPeers[PeerNetwork.Addr, Boolean]](DuplexPeers.empty),
      Ref.of[F, Vector[Registration]](Vector.empty),
      Queue.circularBuffer[F, Unit](1),
      Mutex[F],
      Queue.circularBuffer[F, Unit](1)
    ).mapN(new PeerSharingRuntime(_, _, _, _, _, _, _, _))
