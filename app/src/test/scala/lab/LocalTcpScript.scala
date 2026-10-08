// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import java.net.{InetAddress, InetSocketAddress}
import java.nio.ByteBuffer
import java.nio.channels.{
  AsynchronousChannelGroup,
  AsynchronousServerSocketChannel,
  AsynchronousSocketChannel,
  CompletionHandler
}
import java.util.concurrent.{Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import lab.cbor.Bytes

/** Test-only synthetic mux/CBOR peer. It neither uses a production wire encoder nor resolves DNS.
  * The bound listener remains owned until every client and peer program has completed.
  */
private[lab] object LocalTcpScript:
  def hex(s: String): Bytes =
    Bytes.fromHex(s).fold(e => throw new IllegalArgumentException(e.toString), identity)
  def join(xs: Bytes*): Bytes = Bytes(xs.toVector.flatMap(_.value))
  def uint(n: BigInt, major: Int = 0): Bytes =
    val (tag, width) =
      if n < 24 then (n.toInt, 0)
      else if n <= 255 then (24, 1)
      else if n <= 65535 then (25, 2)
      else if n <= BigInt("ffffffff", 16) then (26, 4)
      else (27, 8)
    Bytes(
      Vector(((major << 5) | tag).toByte) ++ (0 until width).reverse.map(i => (n >> (8 * i)).toByte)
    )
  def frame(protocol: Int, payload: Bytes, responder: Boolean = true): Bytes =
    require(payload.size <= 65535)
    val p = protocol | (if responder then 32768 else 0)
    Bytes(
      Vector(0, 0, 0, 0, p >> 8, p, payload.size >> 8, payload.size).map(_.toByte) ++ payload.value
    )
  def frames(payload: Bytes, protocol: Int = 3): Bytes =
    Bytes(payload.value.grouped(65535).flatMap(b => frame(protocol, Bytes(b)).value).toVector)

  /** Preserve the client diagnostic even when the peer observes a consequential socket reset. */
  def observeBoth[A](client: IO[A], peer: IO[Unit], describe: A => String): IO[A] =
    (client.attempt, peer.attempt).parTupled.flatMap {
      case (Right(value), Right(_)) => IO.pure(value)
      case (Right(value), Left(error)) =>
        IO.raiseError(new AssertionError(s"peer failed; client: ${describe(value)}", error))
      case (Left(clientError), peerResult) =>
        IO.raiseError(
          new AssertionError(
            s"client failed: ${clientError.getMessage}; peer: $peerResult",
            clientError
          )
        )
    }
  val proposal: Bytes = hex("8200a10e84182af500f4")
  val accepted: Bytes = frame(0, hex("83010e84182af500f4"))
  def wrapped(raw: Bytes): Bytes = join(hex("8204d818"), uint(raw.size, 2), raw)
  def response(raws: Vector[Bytes], terminal: Boolean = true): Bytes =
    frames(
      join(
        hex("8102"),
        Bytes(raws.flatMap(wrapped(_).value)),
        if terminal then hex("8105") else Bytes.empty
      )
    )
  def pointWire(encoded: String): Bytes =
    val parts = encoded.split(":", -1)
    join(hex("82"), uint(BigInt(parts(0))), hex("5820"), hex(parts(1)))
  def request(first: String, last: String): Bytes =
    join(hex("8300"), pointWire(first), pointWire(last))

  private def operation[A](cancel: IO[Unit])(register: CompletionHandler[A, Unit] => Unit): IO[A] =
    IO.async[A] { callback =>
      IO.delay {
        register(new CompletionHandler[A, Unit]:
          def completed(value: A, attachment: Unit): Unit = callback(Right(value))
          def failed(error: Throwable, attachment: Unit): Unit = callback(Left(error)))
        Some(cancel)
      }
    }

  final class Peer private[LocalTcpScript] (socket: AsynchronousSocketChannel):
    def close: IO[Unit] = IO.delay(socket.close())
    def send(bytes: Bytes, fragment: Int = 65543): IO[Unit] =
      bytes.value.grouped(fragment).toList.traverse_ { chunk =>
        val buffer = ByteBuffer.wrap(chunk.toArray)
        def loop: IO[Unit] =
          if !buffer.hasRemaining then IO.unit
          else
            operation[Integer](close)(h => socket.write(buffer, (), h)).flatMap { n =>
              if n.intValue < 0 then IO.raiseError(new AssertionError("peer write EOF"))
              else IO.cede *> IO.defer(loop)
            }
        loop
      }
    private def exact(n: Int): IO[Bytes] =
      val buffer = ByteBuffer.allocate(n)
      def loop: IO[Bytes] =
        if !buffer.hasRemaining then IO.pure(Bytes.fromArray(buffer.array()))
        else
          operation[Integer](close)(h => socket.read(buffer, (), h)).flatMap { count =>
            if count.intValue < 0 then
              IO.raiseError(new AssertionError(s"client EOF while expecting $n bytes"))
            else IO.cede *> IO.defer(loop)
          }
      loop
    def receive(protocol: Int): IO[Bytes] =
      for
        head <- exact(8)
        fields = head.value.map(_ & 255)
        _ <- IO.raiseUnless(((fields(4) << 8) | fields(5)) == protocol)(
          new AssertionError("wrong client protocol/direction")
        )
        size = (fields(6) << 8) | fields(7)
        _ <- IO.raiseUnless(size > 0)(new AssertionError("empty SDU"))
        payload <- exact(size)
      yield payload
    def expect(protocol: Int, payload: Bytes): IO[Unit] =
      for
        head <- exact(8)
        fields = head.value.map(_ & 255)
        _ <- IO.raiseUnless(((fields(4) << 8) | fields(5)) == protocol)(
          new AssertionError("wrong client protocol/direction")
        )
        size = (fields(6) << 8) | fields(7)
        _ <- IO.raiseUnless(size == payload.size)(
          new AssertionError(s"client payload size $size, expected ${payload.size}")
        )
        actual <- exact(size)
        _ <- IO.raiseUnless(actual == payload)(
          new AssertionError(s"client wire mismatch: ${actual.hex}")
        )
      yield ()
    def expectEof: IO[Unit] =
      val buffer = ByteBuffer.allocate(1)
      operation[Integer](close)(h => socket.read(buffer, (), h)).flatMap { n =>
        if n.intValue == 0 then IO.cede *> IO.defer(expectEof)
        else
          IO.raiseUnless(n.intValue == -1)(new AssertionError("unexpected client bytes before EOF"))
      }
    def expectEofOrKeepAliveDone: IO[Unit] =
      val buffer = ByteBuffer.allocate(1)
      operation[Integer](close)(h => socket.read(buffer, (), h)).flatMap { n =>
        if n.intValue == 0 then IO.cede *> IO.defer(expectEofOrKeepAliveDone)
        else if n.intValue == -1 then IO.unit
        else
          for
            tail <- exact(9)
            _ <- IO.raiseUnless(
              Bytes(Vector(buffer.array()(0)) ++ tail.value) == hex("00000000000800028102")
            )(new AssertionError("unexpected bytes on failed graceful completion"))
            _ <- expectEof
          yield ()
      }
    def endOutput: IO[Unit] = IO.delay(socket.shutdownOutput()).void

  final class Listener private[LocalTcpScript] (
      server: AsynchronousServerSocketChannel,
      val port: Int
  ):
    def accept: Resource[IO, Peer] =
      Resource
        .makeFull[IO, AsynchronousSocketChannel] { poll =>
          poll(IO.async { callback =>
            IO.delay {
              val canceled = new AtomicBoolean(false)
              val acceptedSocket = new AtomicReference[AsynchronousSocketChannel]()
              server.accept(
                (),
                new CompletionHandler[AsynchronousSocketChannel, Unit]:
                  def completed(socket: AsynchronousSocketChannel, attachment: Unit): Unit =
                    acceptedSocket.set(socket)
                    if canceled.get() then socket.close() else callback(Right(socket))
                  def failed(error: Throwable, attachment: Unit): Unit = callback(Left(error))
              )
              Some(IO.delay {
                canceled.set(true)
                server.close()
                Option(acceptedSocket.get()).foreach(_.close())
              })
            }
          })
        }(s => IO.delay(s.close()))
        .map(new Peer(_))

  val listener: Resource[IO, Listener] =
    for
      executor <- Resource.make(IO.delay(Executors.newFixedThreadPool(2)))(e =>
        IO.blocking {
          e.shutdownNow()
          if !e.awaitTermination(5, TimeUnit.SECONDS) then
            throw new AssertionError("test peer executor did not terminate")
        }
      )
      group <- Resource.make(IO.delay(AsynchronousChannelGroup.withThreadPool(executor)))(g =>
        IO.blocking {
          g.shutdownNow()
          if !g.awaitTermination(5, TimeUnit.SECONDS) then
            throw new AssertionError("test peer group did not terminate")
        }
      )
      server <- Resource.make(IO.delay(AsynchronousServerSocketChannel.open(group)))(s =>
        IO.delay(s.close())
      )
      address <- Resource.eval(IO.delay {
        val ip = InetAddress.getByAddress(Array[Byte](127, 0, 0, 1))
        server.bind(new InetSocketAddress(ip, 0), 1)
        server.getLocalAddress.asInstanceOf[InetSocketAddress]
      })
    yield new Listener(server, address.getPort)
