// SPDX-License-Identifier: Apache-2.0
package lab.network

import cats.effect.{Async, Resource}
import cats.effect.std.Semaphore
import cats.effect.syntax.all.*
import cats.syntax.all.*
import java.net.{InetSocketAddress, StandardSocketOptions}
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousChannelGroup, AsynchronousSocketChannel, CompletionHandler}
import java.util.concurrent.{Executors, ExecutorService, ThreadFactory, TimeUnit, TimeoutException}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}
import lab.cbor.Bytes
import scala.concurrent.duration.FiniteDuration

/** One owned connection, one reader and one writer, no resolver, listener, retry or global pool.
  * Cleanup observes termination for a bounded interval; arbitrary JVM/native close calls are not
  * promised a hard real-time cancellation bound. Cleanup failure is raised, never silently ignored.
  */
object AsyncTcpTransport:
  /** Package-private lifecycle observations for deterministic tests, never session control. */
  private[network] trait Probe[F[_]] extends ByteTransport[F]:
    def readPermits: F[Long]
    def writePermits: F[Long]
    def registeredReads: F[Long]

  /** JDK-shaped seam, package-private solely for deterministic callback/lifecycle tests. A
    * successful read/write completion must advance the supplied buffer exactly as the JDK does.
    */
  private[network] trait Socket:
    def connect(address: InetSocketAddress, handler: CompletionHandler[Void, Unit]): Unit
    def read(buffer: ByteBuffer, handler: CompletionHandler[Integer, Unit]): Unit
    def write(buffer: ByteBuffer, handler: CompletionHandler[Integer, Unit]): Unit
    def close(): Unit
    def isOpen: Boolean

  private[network] final class JdkSocket(channel: AsynchronousSocketChannel) extends Socket:
    def connect(address: InetSocketAddress, h: CompletionHandler[Void, Unit]): Unit =
      channel.connect(address, (), h)
    def read(buffer: ByteBuffer, h: CompletionHandler[Integer, Unit]): Unit =
      channel.read(buffer, (), h)
    def write(buffer: ByteBuffer, h: CompletionHandler[Integer, Unit]): Unit =
      channel.write(buffer, (), h)
    def close(): Unit = channel.close()
    def isOpen: Boolean = channel.isOpen
    def configure(): Unit =
      channel.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
      ()

  private[network] final case class OwnedGroup(
      group: AsynchronousChannelGroup,
      executor: ExecutorService
  )
  private[network] def group[F[_]: Async](limits: TcpLimits): Resource[F, OwnedGroup] =
    val F = Async[F]
    Resource.make(F.delay {
      val serial = new AtomicInteger(0)
      val pool = Executors.newFixedThreadPool(
        limits.threads,
        new ThreadFactory:
          def newThread(r: Runnable): Thread =
            val thread = new Thread(r, s"cardano-tcp-${serial.incrementAndGet()}")
            thread.setDaemon(true)
            thread
      )
      try OwnedGroup(AsynchronousChannelGroup.withThreadPool(pool), pool)
      catch
        case scala.util.control.NonFatal(error) =>
          pool.shutdownNow()
          try
            if !pool.awaitTermination(limits.cleanup.toNanos, TimeUnit.NANOSECONDS) then
              error.addSuppressed(
                new TimeoutException("TCP executor cleanup after group acquisition failure")
              )
          catch case scala.util.control.NonFatal(cleanupError) => error.addSuppressed(cleanupError)
          throw error
    })(owned =>
      F.blocking {
        val start = System.nanoTime()
        var failure: Throwable = null
        def observe(action: => Unit): Unit =
          try action
          catch
            case scala.util.control.NonFatal(error) =>
              if failure == null then failure = error else failure.addSuppressed(error)
        observe(owned.group.shutdownNow())
        observe { owned.executor.shutdownNow(); () }
        def remaining: Long = math.max(0L, limits.cleanup.toNanos - (System.nanoTime() - start))
        observe {
          if !owned.group.awaitTermination(remaining, TimeUnit.NANOSECONDS) then
            throw new TimeoutException("owned TCP channel group did not terminate during cleanup")
        }
        observe {
          if !owned.executor.awaitTermination(remaining, TimeUnit.NANOSECONDS) then
            throw new TimeoutException("owned TCP executor did not terminate during cleanup")
        }
        if failure != null then throw failure
      }
    )

  def resource[F[_]: Async](peer: NumericPeer, limits: TcpLimits): Resource[F, ByteTransport[F]] =
    resourceWith[F](peer, limits)(_ => (), _ => ())

  /** Test-only configuration/observation on the same production ownership path. Both callbacks
    * execute under the relevant Resource ownership, so callback failure releases prior resources.
    */
  private[network] def resourceWith[F[_]: Async](peer: NumericPeer, limits: TcpLimits)(
      configure: AsynchronousSocketChannel => Unit,
      observe: OwnedGroup => Unit
  ): Resource[F, ByteTransport[F]] =
    val F = Async[F]
    group[F](limits).evalTap(owned => F.delay(observe(owned))).flatMap { owned =>
      val channel = Resource
        .make(F.delay(AsynchronousSocketChannel.open(owned.group)))(s => F.blocking(s.close()))
      channel.flatMap { channel =>
        val socket = new JdkSocket(channel)
        Resource.eval(F.delay {
          socket.configure()
          configure(channel)
        }) *> connected[F](socket, peer, limits)
      }
    }

  private def operation[F[_]: Async, A](close: F[Unit])(
      start: CompletionHandler[A, Unit] => Unit
  ): F[A] = Async[F].async[A] { callback =>
    Async[F].delay {
      start(new CompletionHandler[A, Unit]:
        def completed(value: A, ignored: Unit): Unit = callback(Right(value))
        def failed(error: Throwable, ignored: Unit): Unit = callback(Left(error)))
      Some(close)
    }
  }

  /** The caller owns Socket even if connecting fails or is canceled. */
  private[network] def connected[F[_]: Async](
      socket: Socket,
      peer: NumericPeer,
      limits: TcpLimits
  ): Resource[F, ByteTransport[F]] =
    val F = Async[F]
    Resource.eval(endpoint[F](socket, limits)).flatMap { transport =>
      Resource.make(F.pure(transport))(_.close).evalTap { _ =>
        operation[F, Void](transport.close)(socket.connect(peer.socketAddress, _)).void
          .timeout(limits.connect)
          .guaranteeCase {
            case cats.effect.kernel.Outcome.Succeeded(_) => F.unit
            case _                                       => transport.close
          }
      }
    }

  private[network] def endpoint[F[_]: Async](
      socket: Socket,
      limits: TcpLimits
  ): F[ByteTransport[F]] =
    val F = Async[F]
    (
      Semaphore[F](1),
      Semaphore[F](1),
      F.delay(new AtomicBoolean(false)),
      F.delay(new AtomicLong(0L))
    ).mapN { (reads, writes, closed, registrations) =>
      new Probe[F]:
        def readPermits = reads.count
        def writePermits = writes.count
        def registeredReads = F.delay(registrations.get())
        def close: F[Unit] = F.uncancelable { _ =>
          F.delay(closed.compareAndSet(false, true)).flatMap {
            case true  => F.blocking(socket.close())
            case false => F.unit
          }
        }
        def isClosed: F[Boolean] = F.delay(closed.get() || !socket.isOpen)
        def admitted[A](gate: Semaphore[F], deadline: FiniteDuration)(action: F[A]): F[A] =
          // Finalizer is inside permit.use: canceling a waiter never closes its current owner.
          gate.permit.use { _ =>
            action.timeout(deadline).guaranteeCase {
              case cats.effect.kernel.Outcome.Succeeded(_) => F.unit
              case _                                       => close
            }
          }
        def read: F[Option[Bytes]] = admitted(reads, limits.read) {
          isClosed.flatMap {
            case true => F.pure(None)
            case false =>
              F.defer {
                val buffer = ByteBuffer.allocate(limits.maxChunkBytes)
                def receive: F[Option[Bytes]] =
                  operation[F, Integer](close) { handler =>
                    socket.read(buffer, handler)
                    registrations.incrementAndGet()
                    ()
                  }.flatMap { n =>
                    if n.intValue < 0 then close.as(None)
                    else if n.intValue == 0 then F.cede *> F.defer(receive)
                    else
                      F.delay {
                        buffer.flip()
                        val result = new Array[Byte](n.intValue)
                        buffer.get(result)
                        Some(Bytes.fromArray(result))
                      }
                  }
                receive
              }
          }
        }
        def write(bytes: Bytes): F[Unit] =
          F.raiseWhen(bytes.size == 0 || bytes.size > limits.maxChunkBytes)(
            new IllegalArgumentException("empty or oversized transport chunk")
          ) *> admitted(writes, limits.write) {
            isClosed.flatMap {
              case true => F.raiseError(new IllegalStateException("TCP transport closed"))
              case false =>
                F.defer {
                  val buffer = ByteBuffer.wrap(bytes.toArray)
                  def send: F[Unit] =
                    if !buffer.hasRemaining then F.unit
                    else
                      operation[F, Integer](close)(socket.write(buffer, _)).flatMap { n =>
                        if n.intValue < 0 then
                          F.raiseError(new IllegalStateException("negative TCP write"))
                        else F.cede *> F.defer(send)
                      }
                  send
                }
            }
          }
    }
