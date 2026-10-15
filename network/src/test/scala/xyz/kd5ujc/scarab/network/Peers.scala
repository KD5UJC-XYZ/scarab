// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Ref, Resource}
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{Mux as _, *}
import xyz.kd5ujc.scarab.protocol.ChainSync.{DecodeResult, Message, Point, Tip, UInt64}
import scala.concurrent.duration.*

/** Scripted in-memory responders built from the pure codecs. */
object Peers:
  type Net = Raise[IO, NetworkError]

  /** Run a program whose network failures are raised through `Raise`, and observe the first one. */
  def typed[A](body: Handle[IO, NetworkError] ?=> IO[A]): IO[Either[NetworkError, A]] =
    Handle
      .allow[NetworkError](body.map(_.asRight[NetworkError]))
      .rescue(e => IO.pure(e.asLeft[A]))

  def point(n: Int): Point =
    Point.Block(UInt64.from(n).toOption.get, Bytes(Vector.fill(32)(n.toByte)))
  def tip(n: Int): Tip = Tip(point(n), UInt64.from(n).toOption.get)

  /** A CBOR unsigned integer below 24: one valid, opaque item. */
  def header(n: Int): Bytes = Bytes(Vector(n.toByte))
  def block(n: Int): Bytes = Bytes(Vector.fill(100 + n)(n.toByte))

  private def fail[A](reason: String): IO[A] = IO.raiseError(new IllegalStateException(reason))
  private def lift[A](either: Either[String, A]): IO[A] = either.fold(fail, IO.pure)

  /** Two multiplexers joined by an in-memory bearer pair. */
  def linked(
      protocols: Set[Int] = Set(0, 2, 3, 8),
      client: Mux.Config = Mux.Config(),
      server: Mux.Config = Mux.Config(role = Mux.Role.Responder),
      capacity: Int = 64
  )(using Net): Resource[IO, (Mux[IO], Mux[IO])] =
    Resource.eval(Bearer.inMemory[IO](capacity)).flatMap { (a, b) =>
      (Mux.resource(a, protocols, client), Mux.resource(b, protocols, server)).tupled
    }

  def serveHandshake(channel: MuxChannel[IO], offers: Vector[(Int, Handshake.Data)])(using
      Net
  ): IO[Handshake.Result] =
    def decode(bytes: Bytes): DecodeResult[Handshake.Message] =
      Handshake.decodePrefix(Handshake.Suite.NodeToNode, Handshake.State.Propose, bytes) match
        case Right((m, rest))        => DecodeResult.Decoded(m, bytes.size - rest.size)
        case Left(e) if e.incomplete => DecodeResult.NeedMore
        case Left(e)                 => DecodeResult.Failed(e.message)
    for
      proposal <- channel.recv(Handshake.MaxMessageBytes)(decode)
      answer <- lift(Handshake.responder(Handshake.Suite.NodeToNode, offers, proposal))
      bytes <- lift(Handshake.encode(Handshake.Suite.NodeToNode, answer._1))
      _ <- channel.send(bytes)
    yield answer._2

  def sendChainSync(
      channel: MuxChannel[IO],
      from: ChainSync.State,
      messages: List[Message[Bytes]]
  )(using Net): IO[ChainSync.State] =
    messages.foldLeftM(from) { (state, message) =>
      for
        bytes <- lift(
          ChainSync.encode(state, ChainSync.Role.Server, message, ChainSyncFixtures.rawItem)
        )
        _ <- channel.send(bytes)
        next <- lift(ChainSync.transition(state, ChainSync.Role.Server, message))
      yield next
    }

  /** Answers FindIntersect with `intersect` and each RequestNext with the next scripted batch of
    * messages. Once the script is exhausted a RequestNext is never answered.
    */
  def serveChainSync(
      channel: MuxChannel[IO],
      intersect: Vector[Point] => Message[Bytes],
      script: List[List[Message[Bytes]]]
  )(using Net): IO[Unit] =
    def loop(remaining: List[List[Message[Bytes]]]): IO[Unit] =
      channel
        .recv(65535)(
          ChainSync.decodePrefix(
            ChainSync.State.Idle,
            ChainSync.Role.Client,
            _,
            ChainSyncFixtures.rawItem
          )
        )
        .flatMap {
          case Message.FindIntersect(points) =>
            sendChainSync(channel, ChainSync.State.Intersect, List(intersect(points))) *>
              loop(remaining)
          case Message.RequestNext =>
            remaining match
              case Nil => IO.never
              case replies :: rest =>
                sendChainSync(channel, ChainSync.State.NextCanAwait, replies) *> loop(rest)
          case _ => IO.unit
        }
    loop(script)

  /** Serves `ranges` keyed by (from, to); an unknown range gets NoBlocks. */
  def serveBlockFetch(
      channel: MuxChannel[IO],
      ranges: Map[(Point, Point), Vector[Bytes]]
  )(using Net): IO[Unit] =
    val codec = CardanoBlockFetch.payloadCodec()
    def reply(state: BlockFetch.State, message: BlockFetch.Message[CardanoBlockFetch.RawNtNBlock]) =
      lift(BlockFetch.encode(state, BlockFetch.Role.Server, message, codec)).flatMap(channel.send)
    def loop: IO[Unit] =
      channel
        .recv(65535)(
          BlockFetch
            .decodePrefix(BlockFetch.State.Idle, BlockFetch.Role.Client, _, codec)
        )
        .flatMap {
          case BlockFetch.Message.RequestRange(from, to) =>
            ranges.get((from, to)) match
              case None => reply(BlockFetch.State.Busy, BlockFetch.Message.NoBlocks) *> loop
              case Some(blocks) =>
                for
                  _ <- reply(BlockFetch.State.Busy, BlockFetch.Message.StartBatch)
                  _ <- blocks.traverse_ { b =>
                    lift(CardanoBlockFetch.RawNtNBlock.from(b)).flatMap(raw =>
                      reply(BlockFetch.State.Streaming, BlockFetch.Message.Block(raw))
                    )
                  }
                  _ <- reply(BlockFetch.State.Streaming, BlockFetch.Message.BatchDone)
                  _ <- loop
                yield ()
          case _ => IO.unit
        }
    loop

  /** Answers pings, counting them. `answer` maps the requested cookie to the cookie to return, or
    * `None` to stay silent.
    */
  def serveKeepAlive(
      channel: MuxChannel[IO],
      pings: Ref[IO, Int],
      answer: Int => Option[Int] = Some(_)
  )(using Net): IO[Unit] =
    def loop: IO[Unit] =
      channel
        .recv(65535)(
          KeepAlive.decodePrefix(KeepAlive.State.Client, KeepAlive.Role.Client, _)
        )
        .flatMap {
          case KeepAlive.Message.Request(cookie) =>
            pings.update(_ + 1) *> (answer(cookie.value) match
              case None => IO.unit
              case Some(c) =>
                for
                  cookie <- lift(KeepAlive.Cookie.from(c))
                  bytes <- lift(
                    KeepAlive.encode(
                      KeepAlive.State.Server,
                      KeepAlive.Role.Server,
                      KeepAlive.Message.Response(cookie)
                    )
                  )
                  _ <- channel.send(bytes)
                yield ()) *> loop
          case _ => IO.unit
        }
    loop

  /** A complete node-to-node responder on one multiplexer. */
  def serveAll(
      mux: Mux[IO],
      magic: Long,
      intersect: Vector[Point] => Message[Bytes],
      script: List[List[Message[Bytes]]],
      ranges: Map[(Point, Point), Vector[Bytes]],
      pings: Ref[IO, Int],
      pingAnswer: Int => Option[Int] = Some(_)
  )(using Net): Resource[IO, Unit] =
    def run(protocol: Int)(f: MuxChannel[IO] => IO[Unit]): Resource[IO, Unit] =
      Resource.eval(mux.channel(protocol)).flatMap(c => f(c).attempt.void.background.void)
    for
      _ <- run(0)(serveHandshake(_, Handshake.defaultNodeToNode(magic)).void)
      _ <- run(2)(serveChainSync(_, intersect, script))
      _ <- run(3)(serveBlockFetch(_, ranges))
      _ <- run(8)(serveKeepAlive(_, pings, pingAnswer))
    yield ()

  extension [A](io: IO[A]) def within(limit: FiniteDuration): IO[A] = io.timeout(limit)
