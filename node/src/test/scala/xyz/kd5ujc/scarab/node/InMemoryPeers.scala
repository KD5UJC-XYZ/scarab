// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref, Resource}
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import com.comcast.ip4s.*
import xyz.kd5ujc.scarab.ledger.PeerError
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.protocol.NtNHeader

/** Peers on in-memory bearers: the `Transport` of the governor tests. A peer is the address of a
  * server; opening a bearer to it starts the server's side of the connection on the other end of an
  * in-memory pair, and an address without a server refuses like a closed port.
  */
final class InMemoryPeers private (
    magic: Long,
    servers: Ref[IO, Map[PeerNetwork.Addr, ServerHandlers[IO, NtNHeader]]],
    opened: Ref[IO, Map[PeerNetwork.Addr, Int]],
    ends: Ref[IO, Map[PeerNetwork.Addr, List[Bearer[IO]]]]
) extends Transport[IO, PeerNetwork.Addr]:

  def resolve(peer: PeerNetwork.Addr) = IO.pure(
    peer.host match
      case ip: IpAddress => Right(SocketAddress(ip, peer.port))
      case other         => Left(PeerError.Unavailable(s"cannot resolve $other"))
  )

  def bearer(peer: PeerNetwork.Addr, remote: SocketAddress[IpAddress])(using
      raise: Raise[IO, NetworkError]
  ): Resource[IO, Bearer[IO]] =
    Resource.eval(servers.get.map(_.get(peer))).flatMap {
      case None =>
        Resource.eval(
          raise.raise[NetworkError, Bearer[IO]](NetworkError.Connect(peer.toString, "refused"))
        )
      case Some(handlers) =>
        for
          _ <- Resource.eval(opened.update(m => m.updated(peer, m.getOrElse(peer, 0) + 1)))
          pair <- Resource.eval(Bearer.inMemory[IO](256))
          _ <- Resource.eval(ends.update(m => m.updated(peer, pair._1 :: m.getOrElse(peer, Nil))))
          _ <- Handle
            .allow[NetworkError](NodeToNodeServer.accept(pair._1, magic, handlers).void)
            .rescue(_ => IO.unit)
            .background
        yield pair._2
    }

  /** Start serving `peer` with `handlers`. */
  def serve(peer: PeerNetwork.Addr, handlers: ServerHandlers[IO, NtNHeader]): IO[Unit] =
    servers.update(_.updated(peer, handlers))

  /** Stop serving `peer`: later connections are refused. */
  def stop(peer: PeerNetwork.Addr): IO[Unit] = servers.update(_ - peer)

  /** The peer dies: its connections are closed on its side and later ones are refused. */
  def kill(peer: PeerNetwork.Addr): IO[Unit] =
    stop(peer) *> ends.modify(m => (m - peer, m.getOrElse(peer, Nil))).flatMap(_.traverse_(_.close))

  /** How many bearers were opened to each peer. */
  def connections: IO[Map[PeerNetwork.Addr, Int]] = opened.get

object InMemoryPeers:
  def create(magic: Long): IO[InMemoryPeers] =
    (
      Ref.of[IO, Map[PeerNetwork.Addr, ServerHandlers[IO, NtNHeader]]](Map.empty),
      Ref.of[IO, Map[PeerNetwork.Addr, Int]](Map.empty),
      Ref.of[IO, Map[PeerNetwork.Addr, List[Bearer[IO]]]](Map.empty)
    ).mapN(new InMemoryPeers(magic, _, _, _))

  /** The address of the n-th test peer. */
  def address(n: Int): PeerNetwork.Addr =
    SocketAddress[Host](
      IpAddress.fromString(s"10.1.${n / 250}.${n % 250 + 1}").getOrElse(sys.error("address")),
      port"3001"
    )
