// SPDX-License-Identifier: Apache-2.0
package lab.network

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import java.net.{InetAddress, InetSocketAddress, StandardSocketOptions}
import java.nio.channels.{
  AsynchronousServerSocketChannel,
  AsynchronousSocketChannel,
  CompletionHandler
}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

/** Real TCP simulation restricted to one JVM and the IPv4 loopback interface. Cancellation of an
  * in-flight operation closes its channel; it cannot leave an asynchronous read, write, connect or
  * accept running after cancellation.
  */
object TcpLoopback:
  final case class Limits(maxChunkBytes: Int = 1024)
  final case class Connection[F[_]](
      left: ByteTransport[F],
      right: ByteTransport[F],
      address: InetSocketAddress
  )

  def pair[F[_]: Async](
      limits: Limits = Limits()
  ): Resource[F, (ByteTransport[F], ByteTransport[F])] =
    withAddress[F](limits).map(connection => (connection.left, connection.right))

  def withAddress[F[_]: Async](limits: Limits = Limits()): Resource[F, Connection[F]] =
    val F = Async[F]
    for
      _ <- Resource.eval(
        F.raiseUnless(limits.maxChunkBytes > 0 && limits.maxChunkBytes <= 65543)(
          new IllegalArgumentException("invalid TCP loopback resource limits")
        )
      )
      listener <- Resource.make(F.delay(AsynchronousServerSocketChannel.open()))(s =>
        F.delay(s.close())
      )
      address <- Resource.eval(F.delay {
        val loopback = InetAddress.getByAddress(Array[Byte](127, 0, 0, 1))
        listener.bind(new InetSocketAddress(loopback, 0), 1)
        listener.getLocalAddress.asInstanceOf[InetSocketAddress]
      })
      client <- Resource.make(F.delay(AsynchronousSocketChannel.open()))(s => F.delay(s.close()))
      _ <- Resource.eval(operation[F, Void](F.delay(client.close())) { handler =>
        client.connect(address, (), handler)
      })
      server <- Resource.makeFull[F, AsynchronousSocketChannel](poll => poll(accept[F](listener)))(
        s => F.delay(s.close())
      )
      _ <- Resource.eval(F.delay {
        client.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
        server.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
      })
      left <- Resource.eval(endpoint[F](client, limits))
      right <- Resource.eval(endpoint[F](server, limits))
    yield Connection(left, right, address)

  private def operation[F[_]: Async, A](cancel: F[Unit])(
      start: CompletionHandler[A, Unit] => Unit
  ): F[A] =
    val F = Async[F]
    F.async[A] { callback =>
      F.delay {
        start(new CompletionHandler[A, Unit]:
          def completed(result: A, attachment: Unit): Unit = callback(Right(result))
          def failed(error: Throwable, attachment: Unit): Unit = callback(Left(error)))
        Some(cancel)
      }
    }

  private def accept[F[_]: Async](
      listener: AsynchronousServerSocketChannel
  ): F[AsynchronousSocketChannel] =
    val F = Async[F]
    // Also close a socket accepted concurrently with cancellation, before its
    // ownership has crossed into the Resource finalizer.
    F.async[AsynchronousSocketChannel] { callback =>
      F.delay {
        val canceled = new AtomicBoolean(false)
        val accepted = new AtomicReference[AsynchronousSocketChannel]()
        listener.accept(
          (),
          new CompletionHandler[AsynchronousSocketChannel, Unit]:
            def completed(socket: AsynchronousSocketChannel, attachment: Unit): Unit =
              accepted.set(socket)
              if canceled.get() then socket.close()
              else callback(Right(socket))
            def failed(error: Throwable, attachment: Unit): Unit = callback(Left(error))
        )
        Some(F.delay {
          canceled.set(true)
          listener.close()
          Option(accepted.get()).foreach(_.close())
        })
      }
    }

  private def endpoint[F[_]: Async](
      socket: AsynchronousSocketChannel,
      limits: Limits
  ): F[ByteTransport[F]] =
    Async[F]
      .fromEither(
        TcpLimits
          .checked(maxChunkBytes = limits.maxChunkBytes)
          .leftMap(new IllegalArgumentException(_))
      )
      .flatMap { checked =>
        AsyncTcpTransport.endpoint[F](new AsyncTcpTransport.JdkSocket(socket), checked)
      }
