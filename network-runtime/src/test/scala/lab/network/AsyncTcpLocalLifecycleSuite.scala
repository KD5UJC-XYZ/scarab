// SPDX-License-Identifier: Apache-2.0
package lab.network

import cats.effect.{IO, Resource}
import cats.effect.unsafe.IORuntime
import cats.syntax.all.*
import java.net.{
  BindException,
  InetAddress,
  InetSocketAddress,
  SocketException,
  StandardSocketOptions
}
import java.nio.channels.{ServerSocketChannel, SocketChannel, UnsupportedAddressTypeException}
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import lab.cbor.Bytes
import scala.concurrent.duration.*

/** Actual finite localhost tests. Never resolves names or connects to an external interface. Uses
  * production resourceWith, differing only in small SO_SNDBUF and a lifecycle observer.
  */
class AsyncTcpLocalLifecycleSuite extends munit.FunSuite:
  private val runtime = IORuntime.builder().build()
  override def afterAll(): Unit = runtime.shutdown()
  private def run[A](io: IO[A]) = io.timeout(12.seconds).unsafeToFuture()(runtime)
  private def untilRegistered(transport: ByteTransport[IO]): IO[Unit] =
    transport
      .asInstanceOf[AsyncTcpTransport.Probe[IO]]
      .registeredReads
      .flatMap(n => if n > 0 then IO.unit else IO.cede *> untilRegistered(transport))
  private val ipv4 = Array[Byte](127, 0, 0, 1)
  private val ipv6 = Array.fill[Byte](15)(0) ++ Array[Byte](1)
  private val limits = TcpLimits.checked(read = 2.seconds, write = 250.millis).toOption.get

  private def listener(address: Array[Byte]): Resource[IO, ServerSocketChannel] =
    Resource.make(IO(ServerSocketChannel.open()))(s => IO.blocking(s.close())).evalTap { s =>
      IO {
        s.configureBlocking(false)
        // Set the advertised receive window before the connection is established.
        s.setOption(StandardSocketOptions.SO_RCVBUF, Integer.valueOf(1024))
        s.bind(new InetSocketAddress(InetAddress.getByAddress(address), 0), 1)
        ()
      }
    }
  private def accept(listener: ServerSocketChannel): Resource[IO, SocketChannel] =
    Resource.makeFull[IO, SocketChannel] { poll =>
      // Mask only each nonblocking accept/ownership transfer; waiting stays cancelable.
      def acquire: IO[SocketChannel] = IO.defer {
        IO(listener.accept()).flatMap {
          case null   => poll(IO.sleep(5.millis)) *> acquire
          case socket => IO.pure(socket)
        }
      }
      acquire
    }(s => IO.blocking(s.close()))

  private def peer(listener: ServerSocketChannel, literal: String): IO[NumericPeer] = IO {
    val port = listener.getLocalAddress.asInstanceOf[InetSocketAddress].getPort
    NumericPeer.checked(literal, port).toOption.get
  }
  private def observed(
      peer: NumericPeer,
      owned: AtomicReference[AsyncTcpTransport.OwnedGroup]
  ): Resource[IO, ByteTransport[IO]] =
    AsyncTcpTransport.resourceWith[IO](peer, limits)(
      channel => { channel.setOption(StandardSocketOptions.SO_SNDBUF, Integer.valueOf(1024)); () },
      group => owned.set(group)
    )
  private def terminated(owned: AtomicReference[AsyncTcpTransport.OwnedGroup]): IO[Unit] = IO {
    val group = owned.get()
    assert(group != null, "owned group was observed")
    assert(group.group.isTerminated, "channel group terminated on release")
    assert(group.executor.isTerminated, "fixed executor terminated on release")
  }

  test("nonreading peer applies backpressure; bounded sustained writes time out and close") {
    val owned = new AtomicReference[AsyncTcpTransport.OwnedGroup]()
    val chunk = Bytes(Vector.fill[Byte](65536)(0x5a.toByte))
    val task = listener(ipv4).use { server =>
      peer(server, "127.0.0.1").flatMap { p =>
        observed(p, owned).use { transport =>
          accept(server)
            .use { accepted =>
              IO(accepted.configureBlocking(false)) *> {
                // Finite 16 MiB attempted ceiling, one retained chunk and one awaited write.
                // There is deliberately no read operation on the accepted peer.
                def fill(remaining: Int): IO[Unit] =
                  if remaining == 0 then
                    IO.raiseError(new AssertionError("16 MiB escaped socket backpressure"))
                  else transport.write(chunk) *> IO.defer(fill(remaining - 1))
                fill(256).attempt
                  .flatMap { result =>
                    for
                      closed <- transport.isClosed
                      _ <- IO {
                        assert(
                          result.left.toOption.exists(_.isInstanceOf[TimeoutException]),
                          result.toString
                        )
                        assert(closed, "write deadline poisoned the admitted socket")
                      }
                    yield ()
                  }
                  .timeout(5.seconds)
              }
            }
            .timeout(7.seconds)
        }
      }
    }
    run(task *> terminated(owned))
  }

  test("stalled reader can be closed without waiting for its permit; release terminates group") {
    val owned = new AtomicReference[AsyncTcpTransport.OwnedGroup]()
    val task = listener(ipv4).use { server =>
      peer(server, "127.0.0.1").flatMap { p =>
        observed(p, owned).use { transport =>
          accept(server)
            .use { _ =>
              for
                fiber <- transport.read.attempt.start
                _ <- untilRegistered(transport)
                _ <- transport.close.timeout(1.second)
                _ <- fiber.join.timeout(1.second)
                closed <- transport.isClosed
                _ <- IO { assert(closed) }
              yield ()
            }
            .timeout(4.seconds)
        }
      }
    }
    run(task *> terminated(owned))
  }

  test("IPv6 loopback is exercised when the local kernel supports binding ::1") {
    val owned = new AtomicReference[AsyncTcpTransport.OwnedGroup]()
    // Skip only an unsupported local IPv6 bind. Connection, resource and cleanup failures after a
    // successful bind remain real failures, never reclassified as platform skips.
    run(listener(ipv6).allocated.attempt.flatMap {
      case Left(_: BindException) | Left(_: SocketException) |
          Left(_: UnsupportedAddressTypeException) =>
        IO(assume(false, "IPv6 loopback unavailable: kernel could not bind numeric ::1"))
      case Left(error) => IO.raiseError(error)
      case Right((server, release)) =>
        peer(server, "::1")
          .flatMap { p =>
            observed(p, owned).use { transport =>
              accept(server)
                .use { socket =>
                  for
                    closed <- transport.isClosed
                    _ <- IO {
                      assert(!closed)
                      assertEquals(
                        socket.getRemoteAddress
                          .asInstanceOf[InetSocketAddress]
                          .getAddress
                          .getAddress
                          .length,
                        16
                      )
                    }
                  yield ()
                }
                .timeout(3.seconds)
            }
          }
          .guarantee(release) *> terminated(owned)
    })
  }
