// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.{Semaphore, Supervisor}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import lab.cbor.Bytes
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{ServerSocketChannel, SocketChannel}
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

/** Deliberately small HTTP/1.1 endpoint: one request per connection, no chunking or pipelining.
  * Every accepted socket is either immediately refused or owns one of eight permits before any
  * request bytes are read. Nonblocking I/O never owns a blocking thread, and all socket lifetimes
  * are resource scoped.
  */
private[lab] object AdaHttp:
  val MaxBodyBytes = 65536
  val MaxHeaderBytes = 8192
  val MaxRequests = 8
  val IoTimeout = 5.seconds
  final case class Response(status: Int, json: String)
  final case class Bound(port: Int)
  trait Handler[F[_]]:
    def request: Resource[F, Option[RequestHandler[F]]]
  trait RequestHandler[F[_]]:
    def submit(original: Bytes): F[Response]
    def transaction(transactionId: Bytes): F[Response]
    def state: F[Response]

  private final case class Request(method: String, target: String, length: Int)
  private final case class BadRequest(status: Int, code: String) extends RuntimeException(code)
  private def error(status: Int, code: String): Response =
    Response(status, s"""{"code":"$code","fullLedgerValidated":false}""")

  private def parse(header: String): Either[BadRequest, Request] =
    val lines = header.stripSuffix("\r\n\r\n").split("\r\n", -1).toVector
    val first = lines.headOption.getOrElse("").split(" ", -1).toVector
    val raw = lines.drop(1)
    val validHeaders = raw.forall { line =>
      val colon = line.indexOf(':')
      colon > 0 && line.take(colon).forall(c => c.isLetterOrDigit || c == '-') &&
      line.drop(colon + 1).forall(c => c >= ' ' && c <= '~')
    }
    if first.size != 3 || first(2) != "HTTP/1.1" || !validHeaders then
      Left(BadRequest(400, "MalformedHttp"))
    else
      val headers = raw.map { line =>
        val colon = line.indexOf(':')
        line.take(colon).toLowerCase(java.util.Locale.ROOT) -> line.drop(colon + 1).trim
      }
      val grouped = headers.groupMap(_._1)(_._2)
      val length = grouped.get("content-length").flatMap(_.headOption)
      if grouped.values.exists(_.size != 1) || !grouped.contains("host") then
        Left(BadRequest(400, "AmbiguousHeaders"))
      else if grouped.contains("transfer-encoding") || grouped.contains("expect") then
        Left(BadRequest(400, "UnsupportedFraming"))
      else if length.exists(s => s.isEmpty || s.length > 10 || !s.forall(c => c >= '0' && c <= '9'))
      then Left(BadRequest(400, "InvalidContentLength"))
      else
        val size = length.fold(0L)(_.toLong)
        if size > MaxBodyBytes then Left(BadRequest(413, "BodyTooLarge"))
        else if first(0) == "POST" && first(1) == "/v1/transactions" then
          if length.isEmpty then Left(BadRequest(411, "ContentLengthRequired"))
          else if !grouped.get("content-type").exists(_.head.equalsIgnoreCase("application/cbor"))
          then Left(BadRequest(415, "ContentTypeRequired"))
          else Right(Request(first(0), first(1), size.toInt))
        else if first(0) == "GET" && size == 0 then Right(Request(first(0), first(1), 0))
        else Left(BadRequest(400, "UnsupportedRequest"))

  private def readInto[F[_]: Async](socket: SocketChannel, buffer: ByteBuffer): F[Unit] =
    Async[F].defer {
      if !buffer.hasRemaining then Async[F].unit
      else
        Async[F].delay(socket.read(buffer)).flatMap {
          case -1 => Async[F].raiseError(BadRequest(400, "TruncatedRequest"))
          case 0  => Async[F].sleep(2.millis) *> readInto(socket, buffer)
          case _  => readInto(socket, buffer)
        }
    }

  private def readRequest[F[_]: Async](socket: SocketChannel): F[(Request, Bytes)] =
    Async[F]
      .defer {
        val bytes = new Array[Byte](MaxHeaderBytes)
        val one = ByteBuffer.allocate(1)
        def loop(size: Int): F[(Request, Bytes)] =
          if size == MaxHeaderBytes then Async[F].raiseError(BadRequest(431, "HeadersTooLarge"))
          else
            Async[F].delay(one.clear()) *> readInto(socket, one) *> Async[F].defer {
              bytes(size) = one.get(0)
              val next = size + 1
              if next >= 4 && bytes(next - 4) == 13 && bytes(next - 3) == 10 &&
                bytes(next - 2) == 13 && bytes(next - 1) == 10
              then
                Async[F]
                  .fromEither(parse(new String(bytes, 0, next, StandardCharsets.US_ASCII)))
                  .flatMap { request =>
                    val body = ByteBuffer.allocate(request.length)
                    readInto(socket, body).as(request -> body).map { case (r, b) =>
                      r -> Bytes.fromArray(b.array())
                    }
                  }
              else if bytes(size) < 0 then Async[F].raiseError(BadRequest(400, "MalformedHttp"))
              else loop(next)
            }
        loop(0)
      }
      .timeoutTo(IoTimeout, Async[F].raiseError(BadRequest(408, "ReadTimeout")))

  private def write[F[_]: Async](socket: SocketChannel, response: Response): F[Unit] =
    Async[F]
      .defer {
        val safe =
          if response.status < 200 || response.status > 599 || response.json.length > MaxBodyBytes
          then error(500, "InvalidHandlerResponse")
          else response
        val candidate = safe.json.getBytes(StandardCharsets.UTF_8)
        val actual =
          if candidate.length > MaxBodyBytes then error(500, "InvalidHandlerResponse") else safe
        val body = actual.json.getBytes(StandardCharsets.UTF_8)
        val header =
          s"HTTP/1.1 ${actual.status} Result\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n"
        val buffer = ByteBuffer.wrap(header.getBytes(StandardCharsets.US_ASCII) ++ body)
        def loop: F[Unit] = Async[F].defer {
          if !buffer.hasRemaining then Async[F].unit
          else
            Async[F].delay(socket.write(buffer)).flatMap { count =>
              (if count == 0 then Async[F].sleep(2.millis) else Async[F].unit) *> loop
            }
        }
        loop
      }
      .timeout(IoTimeout)

  private def serve[F[_]: Async](socket: SocketChannel, handler: RequestHandler[F]): F[Unit] =
    readRequest(socket)
      .flatMap { case (request, body) =>
        if request.method == "POST" then handler.submit(body)
        else if request.target == "/v1/state" then handler.state
        else if request.target.startsWith("/v1/transactions/") then
          val id = request.target.stripPrefix("/v1/transactions/")
          if id.length != 64 then Async[F].pure(error(400, "InvalidTransactionId"))
          else
            Bytes.fromHex(id) match
              case Left(_)      => Async[F].pure(error(400, "InvalidTransactionId"))
              case Right(value) => handler.transaction(value)
        else Async[F].pure(error(404, "UnknownRoute"))
      }
      .handleError {
        case BadRequest(status, code) => error(status, code)
        case _                        => error(500, "InternalError")
      }
      .flatMap(write(socket, _))
      .handleError(_ => ())

  /** Parent-owned registry closes sockets even if their supervised fiber never starts. Removal is
    * the release linearization point, so child, failed handoff, and shutdown may all release the
    * same socket without returning its permit more than once.
    */
  private[lab] final class SocketOwners[F[_]: Async] private[AdaHttp] (
      registry: Ref[F, Set[SocketChannel]],
      permits: Semaphore[F]
  ):
    def adopt(socket: SocketChannel): F[Unit] = registry.update(_ + socket)
    def size: F[Int] = registry.get.map(_.size)
    def release(socket: SocketChannel): F[Unit] = Async[F].uncancelable { _ =>
      registry.modify(current => (current - socket, current.contains(socket))).flatMap {
        case false => Async[F].unit
        case true  => Async[F].delay(socket.close()).handleError(_ => ()).guarantee(permits.release)
      }
    }
    private[AdaHttp] def drain: F[Unit] = registry.get.flatMap(_.toVector.traverse_(release))

  private[lab] def socketOwners[F[_]: Async](permits: Semaphore[F]): Resource[F, SocketOwners[F]] =
    Resource
      .make(Ref.of[F, Set[SocketChannel]](Set.empty).map(new SocketOwners(_, permits)))(_.drain)

  def server[F[_]: Async](handler: Handler[F], port: Int = 0): Resource[F, Bound] =
    val F = Async[F]
    for
      _ <- Resource.eval(
        F.raiseUnless(port >= 0 && port <= 65535)(new IllegalArgumentException("invalid port"))
      )
      listener <- Resource.make(F.delay(ServerSocketChannel.open()))(s => F.delay(s.close()))
      _ <- Resource.eval(F.delay {
        listener.configureBlocking(false)
        listener.bind(new InetSocketAddress("127.0.0.1", port), MaxRequests)
      })
      permits <- Resource.eval(Semaphore[F](MaxRequests.toLong))
      // Acquired before Supervisor: drain runs after accept and children have stopped.
      owners <- socketOwners(permits)
      supervisor <- Supervisor[F](await = false)
      loop = {
        def accept: F[Unit] = F.uncancelable { poll =>
          F.delay(Option(listener.accept())).flatMap {
            case None => poll(F.sleep(2.millis))
            case Some(socket) =>
              val close = F.delay(socket.close()).handleError(_ => ())
              permits.tryAcquire.flatMap {
                case false => close
                case true =>
                  val request = (
                    F.delay(socket.configureBlocking(false)) *> handler.request
                      .use {
                        case None           => write(socket, error(503, "IngressUnavailable"))
                        case Some(admitted) => serve(socket, admitted)
                      }
                      .handleError(_ => ())
                  ).guarantee(owners.release(socket))
                  owners.adopt(socket) *> supervisor
                    .supervise(request)
                    .void
                    .handleErrorWith(_ => owners.release(socket))
              }
          }
        } *> F.defer(accept)
        accept
      }
      _ <- Resource.make(loop.start)(_.cancel)
      port <- Resource.eval(
        F.delay(listener.getLocalAddress.asInstanceOf[InetSocketAddress].getPort)
      )
    yield Bound(port)
