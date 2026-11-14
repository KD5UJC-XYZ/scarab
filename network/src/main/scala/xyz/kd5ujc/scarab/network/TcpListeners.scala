// SPDX-License-Identifier: Apache-2.0 AND MIT
// Adapted from fs2 3.14.0 (bd384ed29184872e12b51d3b1abdc68bab614d91, MIT,
// Copyright (c) 2013 Functional Streams for Scala): `AsyncSocket` of
// io/jvm-native/src/main/scala/fs2/io/net/SocketPlatform.scala, and `bind` and `acceptIncoming` of
// io/jvm-native/src/main/scala/fs2/io/net/AsynchronousChannelGroupIpSocketsProvider.scala.
// MIT notice: licenses/fs2-3.14.0/LICENSE.
package xyz.kd5ujc.scarab.network

import cats.effect.Async
import cats.effect.Resource
import cats.effect.std.Mutex
import cats.syntax.all.*
import com.comcast.ip4s.{IpAddress, Ipv4Address, Ipv6Address, SocketAddress}
import fs2.{Chunk, Stream}
import java.io.IOException
import java.net.{InetSocketAddress, SocketAddress as JSocketAddress}
import java.nio.ByteBuffer
import java.nio.channels.{
  AsynchronousServerSocketChannel,
  AsynchronousSocketChannel,
  CompletionHandler
}
import scala.util.control.NonFatal
import xyz.kd5ujc.scarab.protocol.ConnectionIdentityError

/** An accepted TCP connection: its bearer, the peer's address, and the close of its channel (which
  * may be run more than once).
  */
final case class TcpStream[F[_]](
    bearer: Bearer[F],
    remote: SocketAddress[IpAddress],
    close: F[Unit]
)

/** A bound, listening TCP socket. */
trait TcpListener[F[_]]:
  def address: SocketAddress[IpAddress]

  /** The next connection, or why `accept` failed. */
  def accept: F[Either[AcceptFailure, TcpStream[F]]]

/** Why an accept failed. The reference's `AcceptFailure` carries any exception of the call that is
  * not asynchronous (ouroboros-network c45735a5,
  * `ouroboros-network/framework/lib/Ouroboros/Network/Snocket.hs` 170-214): here a failed system
  * call, or an exception that is not one, a defect.
  */
enum AcceptFailure:
  case System(error: SystemCallError)
  case Defect(cause: Throwable)

  /** The failure that ends the server's accept loop with it. */
  def diffusion: DiffusionFailure = this match
    case System(error) => DiffusionFailure.System(error)
    case Defect(cause) => DiffusionFailure.Defect(cause)

  def shown: String = diffusion.shown

/** Where the node-to-node server's listening sockets come from: the JDK's in the node, scripted
  * ones in tests.
  */
trait TcpListeners[F[_]]:
  def bind(address: SocketAddress[IpAddress]): Resource[F, TcpListener[F]]

/** The JDK's asynchronous TCP channels as fs2 3.14.0's `Network.forAsync` uses them (its
  * `AsynchronousChannelGroupIpSocketsProvider` and `Socket.forAsync`), with one change: a failed
  * accept is returned, where fs2 drops it and accepts again at once
  * (`AsynchronousChannelGroupIpSocketsProvider.scala` 131-135), so that the server can do what the
  * reference's accept loop does with it. fs2's constructor of a socket over an accepted channel is
  * private to fs2, so the channel's reads and writes are fs2's, copied: one read at a time into a
  * buffer of 8192 bytes, each write to its end under a lock, a cancelled read or write ending that
  * direction of the connection.
  */
object TcpListeners:
  /** fs2's `defaultReadSize`. */
  val ReadSize: Int = 8192

  def jdk[F[_]](using F: Async[F]): TcpListeners[F] = new TcpListeners[F]:
    def bind(address: SocketAddress[IpAddress]): Resource[F, TcpListener[F]] =
      Resource
        .make(F.delay(AsynchronousServerSocketChannel.open()))(channel =>
          F.delay(if channel.isOpen then channel.close())
        )
        .evalTap(channel => F.delay(channel.bind(inet(address))))
        .evalMap(channel =>
          F.delay(channel.getLocalAddress).flatMap(ip[F]).map(local => listener(channel, local))
        )

  // fs2 binds a wildcard host as `null`, the wildcard of the channel's family, so that the listener
  // takes IPv4 and IPv6 alike; `InetSocketAddress(port)` is that address
  private def inet(address: SocketAddress[IpAddress]): InetSocketAddress =
    if address.host == Ipv4Address.Wildcard || address.host == Ipv6Address.Wildcard then
      InetSocketAddress(address.port.value)
    else InetSocketAddress(address.host.toInetAddress, address.port.value)

  private def ip[F[_]](address: JSocketAddress)(using F: Async[F]): F[SocketAddress[IpAddress]] =
    address match
      case inet: InetSocketAddress => F.pure(SocketAddress.fromInetSocketAddress(inet))
      case other => F.raiseError(new IllegalStateException(s"not an IP socket address: $other"))

  private def listener[F[_]](
      channel: AsynchronousServerSocketChannel,
      local: SocketAddress[IpAddress]
  )(using
      F: Async[F]
  ): TcpListener[F] = new TcpListener[F]:
    val address: SocketAddress[IpAddress] = local

    // fs2's `acceptChannel`: the accept is cancelled by closing the listening channel. Once a
    // channel is accepted nothing cancels until its stream owns it, and it is closed if that fails,
    // as fs2's `Resource.makeFull` around it does
    def accept: F[Either[AcceptFailure, TcpStream[F]]] =
      F.uncancelable { poll =>
        poll(F.async[AsynchronousSocketChannel] { cb =>
          F.delay(channel.accept(channel, Accepted(cb))).as(Some(F.delay(channel.close())))
        }).attempt
          .flatMap {
            case Right(accepted) =>
              stream(accepted)
                .onError { case _ => F.delay(if accepted.isOpen then accepted.close()) }
                .map(Right(_))
            case Left(e: IOException) =>
              F.pure(Left(AcceptFailure.System(SystemCallError.of(SystemCall.Accept, None)(e))))
            case Left(NonFatal(cause)) => F.pure(Left(AcceptFailure.Defect(cause)))
            case Left(fatal)           => F.raiseError(fatal)
          }
      }

  /** fs2's `AsyncSocket` as a bearer. The buffer of a read is allocated for it, where fs2 keeps one
    * per socket and copies each read out of it.
    */
  private def stream[F[_]](channel: AsynchronousSocketChannel)(using F: Async[F]): F[TcpStream[F]] =
    for
      reading <- Mutex[F]
      writing <- Mutex[F]
      local <- F.delay(channel.getLocalAddress).flatMap(ip[F])
      remote <- F.delay(channel.getRemoteAddress).flatMap(ip[F])
    yield
      val endOfInput = F.delay(channel.shutdownInput()).void.handleError(_ => ())
      val endOfOutput = F.delay(channel.shutdownOutput()).void.handleError(_ => ())

      val read: F[Option[Chunk[Byte]]] =
        reading.lock.surround(F.delay(ByteBuffer.allocate(ReadSize)).flatMap { buffer =>
          F.async[Int] { cb =>
            F.delay(channel.read(buffer, buffer, Transferred(cb))).as(Some(endOfInput))
          }.flatMap(read =>
            F.delay(Option.when(read >= 0)(Chunk.array(buffer.array, 0, buffer.position())))
          )
        })

      val bearer = new Bearer[F]:
        override def connectionId: Option[F[Either[ConnectionIdentityError, ConnectionId]]] =
          Some(F.pure(ConnectionId.fromSocketAddresses(local, remote)))

        def reads: Stream[F, Byte] = Stream.repeatEval(read).unNoneTerminate.unchunks

        def write(bytes: Chunk[Byte]): F[Unit] =
          def rest(buffer: ByteBuffer): F[Unit] =
            F.async[Int] { cb =>
              F.delay(channel.write(buffer, buffer, Transferred(cb))).as(Some(endOfOutput))
            }.flatMap(written =>
              F.delay(written >= 0 && buffer.hasRemaining).ifM(rest(buffer), F.unit)
            )
          writing.lock.surround(F.delay(bytes.toByteBuffer).flatMap(rest))

        // fs2's `endOfOutput`, which `Bearer.fromSocket` closes with
        def close: F[Unit] = F.delay(channel.shutdownOutput()).void

      TcpStream(bearer, remote, F.delay(if channel.isOpen then channel.close()))

  private final class Accepted(cb: Either[Throwable, AsynchronousSocketChannel] => Unit)
      extends CompletionHandler[AsynchronousSocketChannel, AsynchronousServerSocketChannel]:
    def completed(
        accepted: AsynchronousSocketChannel,
        from: AsynchronousServerSocketChannel
    ): Unit =
      cb(Right(accepted))
    def failed(cause: Throwable, from: AsynchronousServerSocketChannel): Unit = cb(Left(cause))

  /** fs2's `IntCompletionHandler`. */
  private final class Transferred(cb: Either[Throwable, Int] => Unit)
      extends CompletionHandler[Integer, ByteBuffer]:
    def completed(count: Integer, buffer: ByteBuffer): Unit = cb(Right(count.intValue))
    def failed(cause: Throwable, buffer: ByteBuffer): Unit = cb(Left(cause))
