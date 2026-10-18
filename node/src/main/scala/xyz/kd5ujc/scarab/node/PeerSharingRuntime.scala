// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Clock, Ref, Resource}
import cats.effect.std.Queue
import cats.syntax.all.*
import com.comcast.ip4s.{Host, IpAddress, SocketAddress}
import xyz.kd5ujc.scarab.network.{PeerSharingAddress, PeerSharingServer}
import xyz.kd5ujc.scarab.network.selection.{GovernorState, PeerShare, Rng}
import xyz.kd5ujc.scarab.protocol.PeerSharing.Address

/** The governor's public view and negotiated inbound peers. This stores values only; the server
  * never captures the governor's MTL scope. DNS addresses retain the IP that actually connected.
  */
final class PeerSharingRuntime[F[_]: Async] private (
    published: Ref[F, Vector[Address]],
    salting: Ref[F, PeerShare.Salting],
    resolved: Ref[F, Map[PeerNetwork.Addr, SocketAddress[IpAddress]]],
    incoming: Ref[F, Map[PeerNetwork.Addr, Boolean]],
    changes: Queue[F, Unit]
):
  def inbound: F[Map[PeerNetwork.Addr, Boolean]] = incoming.get
  def changed: F[Unit] = changes.take

  def accepted(remote: SocketAddress[IpAddress], sharing: Boolean): Resource[F, Unit] =
    val peer = SocketAddress[Host](remote.host, remote.port)
    Resource.make(incoming.update(_ + (peer -> sharing)) *> changes.offer(()))(_ =>
      incoming.update(_ - peer) *> changes.offer(())
    )

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
  def create[F[_]: Async](seed: Long): F[PeerSharingRuntime[F]] =
    (
      Ref.of[F, Vector[Address]](Vector.empty),
      Ref.of[F, PeerShare.Salting](PeerShare.Salting.start(Rng(seed))),
      Ref.of[F, Map[PeerNetwork.Addr, SocketAddress[IpAddress]]](Map.empty),
      Ref.of[F, Map[PeerNetwork.Addr, Boolean]](Map.empty),
      Queue.circularBuffer[F, Unit](1)
    ).mapN(new PeerSharingRuntime(_, _, _, _, _))
