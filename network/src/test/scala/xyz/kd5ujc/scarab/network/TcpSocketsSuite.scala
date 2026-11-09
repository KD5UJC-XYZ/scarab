// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.io.net.Network
import java.net.SocketException
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.selection.DiffusionMode

/** The reference's socket configuration on loopback (issue 854): an outbound socket bound to the
  * port the node listens on, linger 0, `TCP_NODELAY`, and the four-tuple a bound socket occupies.
  */
class TcpSocketsSuite extends FailsOnUncaught:
  private val loopback: SocketAddress[IpAddress] = SocketAddress(ip"127.0.0.1", Port.Wildcard)
  private val timeout = 3.seconds
  private val configured =
    ObservedSettings(reuseAddress = true, reusePort = true, noDelay = true, linger = 0)

  private def right[A](resource: Resource[IO, Either[TcpSocketError, A]]): Resource[IO, A] =
    resource.evalMap(r => IO(r.fold(e => fail(s"socket call failed: $e"), identity)))

  private def listening: Resource[IO, TcpListener[IO]] = right(TcpSockets.listen[IO](loopback))

  /** A connection from the node listening at `own` to `remote`, bound to `own`. */
  private def dial(
      own: SocketAddress[IpAddress],
      remote: SocketAddress[IpAddress]
  ): Resource[IO, Either[TcpSocketError, TcpStream[IO]]] =
    TcpSockets.connect[IO](
      OutboundPlan.of(remote, Some(own), None, DiffusionMode.InitiatorAndResponder),
      timeout
    )

  test("the outbound plan binds only in InitiatorAndResponder mode, to the address of its family") {
    val remote4 = SocketAddress[IpAddress](ip"192.0.2.1", port"3001")
    val remote6 = SocketAddress[IpAddress](ip"2001:db8::1", port"3001")
    val own4 = SocketAddress[IpAddress](ip"198.51.100.7", port"3001")
    val own6 = SocketAddress[IpAddress](ip"2001:db8::7", port"3001")
    val base = List(
      SocketSetting.ReuseAddress,
      SocketSetting.ReusePort,
      SocketSetting.NoDelay,
      SocketSetting.LingerZero
    )
    val duplex = DiffusionMode.InitiatorAndResponder
    assertEquals(
      OutboundPlan.of(remote4, Some(own4), Some(own6), duplex),
      OutboundPlan(remote4, SocketFamily.Inet, base, Some(own4))
    )
    assertEquals(
      OutboundPlan.of(remote4, Some(own4), Some(own6), DiffusionMode.InitiatorOnly),
      OutboundPlan(remote4, SocketFamily.Inet, base, None)
    )
    assertEquals(
      OutboundPlan.of(remote6, Some(own4), Some(own6), duplex),
      OutboundPlan(remote6, SocketFamily.Inet6, base :+ SocketSetting.Ipv6Only, Some(own6))
    )
    // `configureSocket socket addr` takes the family of the node's own address: with none of the
    // remote's family configured, an IPv6 socket is not made IPv6-only
    assertEquals(
      OutboundPlan.of(remote6, Some(own4), None, duplex),
      OutboundPlan(remote6, SocketFamily.Inet6, base, None)
    )
  }

  test("an outbound socket binds to the address the node listens on while it listens") {
    (listening, listening).tupled.use { (node, peer) =>
      right(dial(node.address, peer.address)).use { outbound =>
        for
          _ <- IO(assertEquals(outbound.local, node.address))
          _ <- right(peer.accept).use(inbound => IO(assertEquals(inbound.remote, node.address)))
          // the node still accepts on the port its outbound socket is bound to
          _ <- Network
            .forAsync[IO]
            .connect(node.address)
            .use(_ => right(node.accept).use(_ => IO.unit))
            .timeout(timeout)
        yield ()
      }
    }
  }

  test("the fs2 listener sets no SO_REUSEPORT, so a socket cannot bind to its port") {
    BearerServer
      .listen[IO, NetworkError](Some(ip"127.0.0.1"), None)((_, _) => IO.unit)((_, _) => IO.unit)
      .use { bound =>
        (listening).use { peer =>
          dial(bound, peer.address).use(result =>
            IO(
              assertEquals(
                result.void,
                Left(
                  TcpSocketError
                    .Failed(SocketStep.Bind(bound), ErrnoCause.Known(Errno.EADDRINUSE))
                )
              )
            )
          )
        }
      }
  }

  test("closing with linger 0 resets the connection: the peer reads a reset, not end of stream") {
    (listening, listening).tupled.use { (node, peer) =>
      for
        (outbound, release) <- right(dial(node.address, peer.address)).allocated
        reset <- right(peer.accept).use { inbound =>
          for
            // both ends read while the closer closes, as a live connection's do
            peerReads <- inbound.bearer.reads.compile.drain.attempt.start
            ownReads <- outbound.bearer.reads.compile.drain.attempt.start
            _ <- IO.sleep(100.millis)
            _ <- release
            _ <- ownReads.join
            seen <- peerReads.joinWithNever.timeout(timeout)
          yield seen
        }
        _ <- IO(reset match
          case Left(e: SocketException) => assertEquals(e.getMessage, "Connection reset")
          case other                    => fail(s"expected a reset, got $other"))
        // without linger, fs2's socket ends with a FIN: the peer reads end of stream
        eof <- Network
          .forAsync[IO]
          .connect(peer.address)
          .allocated
          .flatMap((_, close) =>
            right(peer.accept)
              .use(inbound => close *> inbound.bearer.reads.compile.drain.attempt.timeout(timeout))
          )
        _ <- IO(assertEquals(eof, Right(())))
      yield ()
    }
  }

  test("the outbound and the accepted socket have TCP_NODELAY and the rest of configureSocket") {
    (listening, listening).tupled.use { (node, peer) =>
      right(dial(node.address, peer.address)).use { outbound =>
        for
          own <- outbound.observed
          accepted <- right(peer.accept).use(_.observed)
        yield
          assertEquals(own, Right(configured))
          assertEquals(accepted, Right(configured))
      }
    }
  }

  test("a second socket bound to the same address cannot connect to the same remote") {
    (listening, listening).tupled.use { (node, peer) =>
      right(dial(node.address, peer.address)).use { _ =>
        dial(node.address, peer.address).use(second =>
          IO(
            assertEquals(
              second.void,
              Left(
                TcpSocketError.Failed(
                  SocketStep.Connect(peer.address),
                  ErrnoCause.Known(Errno.EADDRNOTAVAIL)
                )
              )
            )
          )
        )
      }
    }
  }

  /** The kernel's states of the TCP sockets from port `local` to port `remote` (`/proc/net/tcp`,
    * and `/proc/net/tcp6`, where fs2's dual-stack sockets are).
    */
  private def states(local: Port, remote: Port): IO[List[Int]] =
    List("/proc/net/tcp", "/proc/net/tcp6")
      .flatTraverse(table =>
        IO.blocking(java.nio.file.Files.readAllLines(java.nio.file.Path.of(table)))
          .map(_.toArray(Array.empty[String]).toList.drop(1))
      )
      .map(_.flatMap { line =>
        line.trim.split("\\s+").toList match
          case _ :: from :: to :: state :: _ =>
            def port(endpoint: String) = Integer.parseInt(endpoint.split(':')(1), 16)
            Option.when(port(from) == local.value && port(to) == remote.value)(
              Integer.parseInt(state, 16)
            )
          case _ => None
      })

  private val TimeWait = 6

  test("the end that closes with linger 0 keeps no TIME_WAIT, so its four-tuple is free at once") {
    (listening, listening).tupled.use { (node, peer) =>
      for
        // the outbound socket closes first, as the end that would otherwise enter TIME_WAIT
        (_, release) <- right(dial(node.address, peer.address)).allocated
        lingering <- right(peer.accept).use { inbound =>
          release *> IO.sleep(100.millis) *> inbound.bearer.close.attempt *>
            states(node.address.port, peer.address.port)
        }
        _ <- IO(assertEquals(lingering.filter(_ == TimeWait), Nil))
        _ <- right(dial(node.address, peer.address)).use(again =>
          right(peer.accept).use(inbound => IO(assertEquals(inbound.remote, again.local)))
        )
        // without linger, fs2's socket closes first with a FIN, and once the peer ends its side
        // too it is left in TIME_WAIT
        plain <- Network
          .forAsync[IO]
          .connect(peer.address)
          .allocated
          .flatMap((socket, close) =>
            right(peer.accept).use { inbound =>
              close *> IO.sleep(100.millis) *> inbound.bearer.close *> IO.sleep(100.millis) *>
                states(socket.address.asIpUnsafe.port, peer.address.port)
            }
          )
        _ <- IO(assertEquals(plain, List(TimeWait)))
      yield ()
    }
  }

  test("IPV6_V6ONLY cannot be set, so an IPv6 address is refused before any bind") {
    val own6 = SocketAddress[IpAddress](ip"::1", Port.Wildcard)
    val remote6 = SocketAddress[IpAddress](ip"::1", port"3001")
    val unsupported = Left(TcpSocketError.Unsupported(SocketSetting.Ipv6Only))
    for
      listened <- TcpSockets.listen[IO](own6).use(r => IO(r.void))
      dialled <- TcpSockets
        .connect[IO](
          OutboundPlan.of(remote6, None, Some(own6), DiffusionMode.InitiatorAndResponder),
          timeout
        )
        .use(r => IO(r.void))
    yield
      assertEquals(listened, unsupported)
      assertEquals(dialled, unsupported)
  }
