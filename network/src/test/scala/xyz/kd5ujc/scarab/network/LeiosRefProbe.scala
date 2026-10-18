// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{ExitCode, IO, IOApp, Ref}
import cats.mtl.Handle
import cats.syntax.all.*
import com.comcast.ip4s.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.protocol.ChainSync.DecodeResult
import xyz.kd5ujc.scarab.protocol.{ChainSync, Handshake, LeiosFetch, LeiosNotify}
import scala.annotation.tailrec
import scala.concurrent.duration.*

/** Probe of a private Leios reference node (docs/leios.md section 12): the first message the
  * LeiosNotify (18) and LeiosFetch (19) responders accept, a pipelined credit run, a duplex
  * connection, and the chain's first Leios blocks.
  *
  * The messages are built and framed here as raw CBOR, in either of two wire dialects, because the
  * reference builds disagree on the numbering of the messages: `blueprint` (the Blueprint
  * `fe4cceb9` and the codecs of the `protocol` module) and `w38a` (the build the public testnet
  * runs). Every message received is classified by the dialect's tags and run through the `protocol`
  * codecs as it came and, for `w38a`, with the tag renumbered to the Blueprint's, so a difference
  * in numbering alone shows as such. One line per message is appended to `<out>`:
  * `ms<TAB>protocol<TAB>kind<TAB>codec<TAB>hex`.
  *
  * `network/Test/runMain xyz.kd5ujc.scarab.network.LeiosRefProbe <host> <port> <magic> <dialect>
  * <mode> <out> [args]`, with the modes `initiator <seconds>`, `credit <initial> <top-up>
  * <seconds>`, `duplex <seconds>` and `chain <headers>`. Throwaway tool: not part of the node, not
  * run by `check`.
  */
object LeiosRefProbe extends IOApp:
  enum Dialect:
    case Blueprint, W38a

  /** The message numbers of a dialect. */
  private final case class Tags(
      request: Int,
      announcement: Int,
      offer: Int,
      txsOffer: Int,
      votes: Int,
      blockRequest: Int,
      block: Int,
      txsRequest: Int,
      txs: Int
  )
  private val blueprint = Tags(3, 4, 5, 6, 7, 1, 2, 3, 4)
  private val w38a = Tags(0, 1, 2, 3, 4, 0, 1, 2, 3)
  private def tags(d: Dialect): Tags = if d == Dialect.W38a then w38a else blueprint

  // ---------------------------------------------------------------- raw CBOR

  /** The length of the first complete CBOR item of `bytes`, or `None` while more bytes are needed.
    * Explicit stack of the items still to read in each open container (`Long.MaxValue` stands for
    * an indefinite one, ended by the break byte); no recursion.
    */
  private[network] def extent(bytes: Bytes): Option[Int] =
    val size = bytes.size
    def argument(info: Int, at: Int): Option[(Long, Int)] =
      if info < 24 then Some((info.toLong, 0))
      else
        val width = info match
          case 24 => 1
          case 25 => 2
          case 26 => 4
          case 27 => 8
          case _  => 0
        if width == 0 || at + 1 + width > size then None
        else
          Some(
            ((0 until width).foldLeft(0L)((n, i) => (n << 8) | (bytes(at + 1 + i) & 0xffL)), width)
          )
    @tailrec def loop(at: Int, open: List[Long]): Option[Int] =
      open match
        case Nil          => Some(at)
        case 0L :: parent =>
          // a finished container is one item of its parent
          loop(
            at,
            parent match
              case Nil          => Nil
              case head :: rest => (if head == Long.MaxValue then head else head - 1) :: rest
          )
        case head :: rest =>
          if at >= size then None
          else
            val b = bytes(at) & 0xff
            val major = b >> 5
            val info = b & 0x1f
            // the stack after one item of the container on top is complete
            val one = (if head == Long.MaxValue then head else head - 1) :: rest
            if b == 0xff then // break: ends the indefinite container on top
              if head == Long.MaxValue then loop(at + 1, 0L :: rest) else None
            else if info == 31 then
              if major >= 2 && major <= 5 then loop(at + 1, Long.MaxValue :: open) else None
            else
              argument(info, at) match
                case None => None
                case Some((n, w)) =>
                  val next = at + 1 + w
                  major match
                    case 0 | 1 | 7 => loop(next, one)
                    case 2 | 3 =>
                      if n < 0 || n > Int.MaxValue || next.toLong + n > size then None
                      else loop(next + n.toInt, one)
                    case 4 =>
                      if n < 0 || n > size - next then None
                      else if n == 0 then loop(next, one)
                      else loop(next, n :: open)
                    case 5 =>
                      if n < 0 || n > (size - next) / 2 then None
                      else if n == 0 then loop(next, one)
                      else loop(next, (2 * n) :: open)
                    case _ => loop(next, 1L :: open)
    loop(0, List(1L))

  private def head(major: Int, n: Long): Vector[Byte] =
    if n < 24 then Vector(((major << 5) | n.toInt).toByte)
    else if n < 256 then Vector(((major << 5) | 24).toByte, n.toByte)
    else if n < 65536 then Vector(((major << 5) | 25).toByte, (n >> 8).toByte, n.toByte)
    else if n < (1L << 32) then
      ((major << 5) | 26).toByte +: (3 to 0 by -1).map(i => (n >> (8 * i)).toByte).toVector
    else ((major << 5) | 27).toByte +: (7 to 0 by -1).map(i => (n >> (8 * i)).toByte).toVector

  private def array(items: Vector[Byte]*): Vector[Byte] = head(4, items.size) ++ items.flatten
  private def uint(n: Long): Vector[Byte] = head(0, n)
  private def point(slot: Long, hash: Bytes): Vector[Byte] =
    array(uint(slot), head(2, hash.size.toLong) ++ octets(hash))

  private def octets(b: Bytes): Vector[Byte] = (0 until b.size).map(b(_)).toVector

  /** `{window => bitmap}` as the reference decodes it (`decodeMapLenIndef`: an indefinite map). */
  private def bitmaps(windows: Vector[(Int, Long)], indefinite: Boolean): Vector[Byte] =
    val entries = windows.flatMap { case (w, bits) => head(0, w.toLong) ++ head(0, bits) }
    if indefinite then Vector(0xbf.toByte) ++ entries :+ 0xff.toByte
    else head(5, windows.size.toLong) ++ entries

  // ---------------------------------------------------------------- classification

  /** The kind of a message by its first array element and the dialect, and whether our codec reads
    * it.
    */
  private final case class Seen(kind: String, codec: String)

  private def tagOf(message: Bytes): Option[Int] =
    Cbor.decode(message, Cbor.Limits(2500000, 16, 70000, 70000)).toOption.flatMap { root =>
      root.value match
        case Value.Arr(xs) =>
          xs.headOption.map(_.value).collect { case Value.UInt(t) => t.toInt }
        case _ => None
    }

  /** `message` with its tag replaced (the tags of both dialects are below 24). */
  private def renumber(message: Bytes, tag: Int): Bytes =
    val v = octets(message)
    Bytes(v.updated(1, tag.toByte))

  private def verdict[A](r: Either[?, A]): String =
    r.fold(e => s"refused ${e.toString.take(60)}", _ => "ok")

  private def notifySeen(dialect: Dialect, message: Bytes): Seen =
    val t = tags(dialect)
    val kind = tagOf(message) match
      case Some(x) if x == t.announcement => "announcement"
      case Some(x) if x == t.offer        => "block-offer"
      case Some(x) if x == t.txsOffer     => "txs-offer"
      case Some(x) if x == t.votes        => "votes"
      case Some(x)                        => s"tag-$x"
      case None                           => "undecodable"
    val asReceived = verdict(LeiosNotify.decodePrefix(LeiosNotify.State.Busy, message))
    val renumbered = dialect match
      case Dialect.Blueprint => ""
      case Dialect.W38a =>
        val to = Map(
          t.announcement -> blueprint.announcement,
          t.offer -> blueprint.offer,
          t.txsOffer -> blueprint.txsOffer,
          t.votes -> blueprint.votes
        )
        tagOf(message).flatMap(to.get) match
          case Some(n) =>
            s" renumbered:${verdict(LeiosNotify.decodePrefix(LeiosNotify.State.Busy, renumber(message, n)))}"
          case None => ""
    Seen(kind, s"as-received:$asReceived$renumbered")

  private def fetchSeen(dialect: Dialect, state: LeiosFetch.State, message: Bytes): Seen =
    val t = tags(dialect)
    val kind = tagOf(message) match
      case Some(x) if x == t.block => "block"
      case Some(x) if x == t.txs   => "block-txs"
      case Some(x)                 => s"tag-$x"
      case None                    => "undecodable"
    val asReceived = verdict(LeiosFetch.decodePrefix(state, message))
    val renumbered = dialect match
      case Dialect.Blueprint => ""
      case Dialect.W38a =>
        val to = Map(t.block -> blueprint.block, t.txs -> blueprint.txs)
        tagOf(message).flatMap(to.get) match
          case Some(n) =>
            s" renumbered:${verdict(LeiosFetch.decodePrefix(state, renumber(message, n)))}"
          case None => ""
    Seen(kind, s"as-received:$asReceived$renumbered")

  // ---------------------------------------------------------------- connection

  private val limits = Cbor.Limits(70000, 16, 256, 70000)
  private val leiosProtocols = Set(Protocols.LeiosNotify, Protocols.LeiosFetch)
  private val raw: ChainSync.PayloadCodec[Bytes] = new ChainSync.PayloadCodec[Bytes]:
    def encode(value: Bytes): Either[Nothing, Bytes] = Right(value)
    def decode(bytes: Bytes): Either[Nothing, Bytes] = Right(bytes)

  private final class Log(out: Path, start: FiniteDuration):
    def line(protocol: Int, kind: String, codec: String, hex: String): IO[Unit] =
      IO.monotonic.flatMap { now =>
        val text = s"${(now - start).toMillis}\t$protocol\t$kind\t$codec\t$hex\n"
        IO.blocking(
          Files.write(
            out,
            text.getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
          )
        ).void
      }

  /** The next whole CBOR item on a channel, as the exact bytes. */
  private def nextItem(channel: MuxChannel[IO], within: FiniteDuration)(using
      Handle[IO, NetworkError]
  ): IO[Bytes] =
    Timed.within(within, channel.protocol, "reply")(item(channel))

  private def item(channel: MuxChannel[IO])(using Handle[IO, NetworkError]): IO[Bytes] =
    channel.recv(2500000)(bytes =>
      extent(bytes) match
        case None    => DecodeResult.NeedMore
        case Some(n) => DecodeResult.Decoded(bytes.take(n).compact, n)
    )

  private[network] def silentWindow(
      duration: FiniteDuration,
      first: IO[Unit],
      second: IO[Unit]
  ): IO[Boolean] = IO.race(IO.sleep(duration), IO.race(first, second)).map(_.isLeft)

  def run(args: List[String]): IO[ExitCode] = args match
    case host :: port :: magic :: dialect :: mode :: out :: rest =>
      val d = if dialect == "w38a" then Dialect.W38a else Dialect.Blueprint
      val target = (Host.fromString(host), Port.fromInt(port.toInt)).mapN(SocketAddress(_, _))
      target match
        case None => IO.println("bad address").as(ExitCode.Error)
        case Some(addr) =>
          IO.monotonic.flatMap { start =>
            val log = Log(Path.of(out), start)
            val program = (mode, rest) match
              case ("initiator", s :: Nil) => initiator(addr, magic.toLong, d, log, s.toInt.seconds)
              case ("credit", a :: b :: s :: Nil) =>
                credit(addr, magic.toLong, d, log, a.toInt, b.toInt, s.toInt.seconds)
              case ("duplex", s :: Nil) => duplex(addr, magic.toLong, log, s.toInt.seconds)
              case ("chain", n :: Nil)  => chain(addr, magic.toLong, n.toInt)
              case ("listen", p :: s :: w :: Nil) =>
                listen(p.toInt, magic.toLong, log, s.toInt.seconds, w == "leios")
              case _ => IO.println("bad mode or arguments")
            program.as(ExitCode.Success)
          }
    case _ =>
      IO.println("usage: LeiosRefProbe host port magic blueprint|w38a mode out [args]")
        .as(ExitCode.Error)

  private def handshake(mux: Mux[IO], magic: Long, initiatorOnly: Boolean)(using
      Handle[IO, NetworkError]
  ): IO[HandshakeClient.Agreed] =
    mux
      .channel(Protocols.Handshake, Mux.Role.Initiator)
      .flatMap(
        HandshakeClient.run(
          _,
          Vector(14, 15, 16).map(_ -> Handshake.Data(magic, initiatorOnly = initiatorOnly))
        )
      )

  private def open[A](addr: SocketAddress[Host], protocols: Set[Int], duplex: Boolean)(
      use: Mux[IO] => Handle[IO, NetworkError] ?=> IO[A]
  ): IO[Unit] =
    Handle
      .allow[NetworkError] {
        Bearer
          .tcp[IO](addr, 10.seconds)
          .flatMap(Mux.resource[IO](_, protocols, Mux.Config(duplex = duplex)))
          .use(mux => use(mux).void)
      }
      .rescue(e => IO.println(s"ENDED ${e.message}"))

  private val initiatorProtocols = Set(
    Protocols.Handshake,
    Protocols.KeepAlive,
    Protocols.LeiosNotify,
    Protocols.LeiosFetch
  )

  // ---------------------------------------------------------------- modes

  /** One request in flight: the first message after the handshake is a plain RequestNext; every
    * offered endorser block is fetched, and the first transaction of the first offered one.
    */
  private def initiator(
      addr: SocketAddress[Host],
      magic: Long,
      d: Dialect,
      log: Log,
      duration: FiniteDuration
  ): IO[Unit] =
    val t = tags(d)
    open(addr, initiatorProtocols, duplex = false) { mux =>
      for
        agreed <- handshake(mux, magic, initiatorOnly = true)
        _ <- IO.println(s"handshake: version ${agreed.version}")
        ka <- mux.channel(Protocols.KeepAlive)
        notify <- mux.channel(Protocols.LeiosNotify)
        fetch <- mux.channel(Protocols.LeiosFetch)
        fetched <- Ref.of[IO, Int](0)
        step = for
          _ <- notify.send(Bytes(array(uint(t.request.toLong))))
          _ <- log.line(18, "request-next", "", Bytes(array(uint(t.request.toLong))).hex)
          msg <- nextItem(notify, 60.seconds)
          seen = notifySeen(d, msg)
          _ <- log.line(18, seen.kind, seen.codec, msg.hex)
          _ <- IO.println(s"notify: ${seen.kind} (${msg.size} bytes) ${seen.codec}")
          n <- fetched.get
          _ <- Option
            .when(seen.kind == "block-offer" && n < 2)(offered(msg))
            .flatten
            .traverse_ { p =>
              fetched.update(_ + 1) *> fetchBlock(fetch, d, log, p) *> fetchTxs(fetch, d, log, p)
            }
        yield ()
        _ <- KeepAliveClient
          .background[IO](ka, mux)
          .use(_ => IO.race(IO.sleep(duration), step.foreverM).void)
      yield ()
    }

  /** The `[slot, hash]` of an offer, from its second element. */
  private def offered(message: Bytes): Option[(Long, Bytes)] =
    Cbor.decode(message, limits).toOption.flatMap { root =>
      root.value match
        case Value.Arr(xs) if xs.size >= 2 =>
          xs(1).value match
            case Value.Arr(Vector(s, h)) =>
              (s.value, h.value) match
                case (Value.UInt(slot), Value.ByteString(hash)) => Some((slot.toLong, hash))
                case _                                          => None
            case _ => None
        case _ => None
    }

  private def fetchBlock(fetch: MuxChannel[IO], d: Dialect, log: Log, p: (Long, Bytes))(using
      Handle[IO, NetworkError]
  ): IO[Unit] =
    val request = Bytes(array(uint(tags(d).blockRequest.toLong), point(p._1, p._2)))
    for
      _ <- fetch.send(request)
      _ <- log.line(19, "block-request", "", request.hex)
      msg <- nextItem(fetch, 60.seconds)
      seen = fetchSeen(d, LeiosFetch.State.Block, msg)
      _ <- log.line(19, seen.kind, seen.codec, msg.hex)
      _ <- IO.println(s"fetch: ${seen.kind} (${msg.size} bytes) ${seen.codec}")
    yield ()

  /** The transactions of window 0 with bit 0 and with bits 0 to 2 set, as an indefinite map (what
    * the reference decodes) and, for the Blueprint dialect, as a definite one.
    */
  private def fetchTxs(fetch: MuxChannel[IO], d: Dialect, log: Log, p: (Long, Bytes))(using
      Handle[IO, NetworkError]
  ): IO[Unit] =
    val request = Bytes(
      array(
        uint(tags(d).txsRequest.toLong),
        point(p._1, p._2),
        bitmaps(Vector(0 -> 7L), indefinite = d == Dialect.W38a)
      )
    )
    for
      _ <- fetch.send(request)
      _ <- log.line(19, "txs-request", "", request.hex)
      msg <- nextItem(fetch, 60.seconds)
      seen = fetchSeen(d, LeiosFetch.State.BlockTxs, msg)
      _ <- log.line(19, seen.kind, seen.codec, msg.hex)
      _ <- IO.println(s"fetch: ${seen.kind} (${msg.size} bytes) ${seen.codec}")
    yield ()

  /** The usage the CIP describes: `initial` requests at once, then `topUp` more for every `topUp`
    * received. Ends when the connection does, or after `duration`; says when and how.
    */
  private def credit(
      addr: SocketAddress[Host],
      magic: Long,
      d: Dialect,
      log: Log,
      initial: Int,
      topUp: Int,
      duration: FiniteDuration
  ): IO[Unit] =
    val t = tags(d)
    val request = Bytes(array(uint(t.request.toLong)))
    open(addr, initiatorProtocols, duplex = false) { mux =>
      for
        agreed <- handshake(mux, magic, initiatorOnly = true)
        _ <- IO.println(s"handshake: version ${agreed.version}")
        ka <- mux.channel(Protocols.KeepAlive)
        notify <- mux.channel(Protocols.LeiosNotify)
        sent <- Ref.of[IO, Int](0)
        received <- Ref.of[IO, Int](0)
        send = (k: Int) =>
          (1 to k).toList.traverse_(_ => notify.send(request) *> sent.update(_ + 1)) *>
            log.line(18, "requests-sent", "", s"$k")
        collect = for
          msg <- nextItem(notify, 60.seconds)
          n <- received.updateAndGet(_ + 1)
          seen = notifySeen(d, msg)
          _ <- log.line(18, seen.kind, seen.codec, msg.hex)
          _ <- if n % topUp == 0 then send(topUp) else IO.unit
        yield ()
        run = send(initial) *> collect.foreverM
        _ <- KeepAliveClient.background[IO](ka, mux).use { _ =>
          IO.race(
            IO.sleep(duration),
            run
          ).void
            .guarantee(
              (sent.get, received.get)
                .flatMapN((s, r) => IO.println(s"requests sent $s, messages received $r"))
            )
        }
      yield ()
    }

  /** A duplex connection whose responder side serves the Praos protocols from an empty chain, with
    * the Leios protocols registered on both roles. Logs the first message the reference sends on 18
    * or 19 and the Praos protocols it starts.
    */
  private def duplex(
      addr: SocketAddress[Host],
      magic: Long,
      log: Log,
      duration: FiniteDuration
  ): IO[Unit] =
    val protocols = NodeToNodeServer.Protocol ++ leiosProtocols
    open(addr, protocols, duplex = true) { mux =>
      for
        agreed <- handshake(mux, magic, initiatorOnly = false)
        _ <- IO.println(s"duplex handshake: version ${agreed.version} data ${agreed.data}")
        listen = (protocol: Int) =>
          mux.channel(protocol, Mux.Role.Responder).flatMap { channel =>
            item(channel).flatMap { msg =>
              log.line(protocol, "first-message-from-reference", "", msg.hex) *>
                IO.println(s"reference sent on $protocol: ${msg.hex.take(80)}")
            }
          }
        _ <- NodeToNodeServer
          .start(
            mux,
            EmptyChain.handlers[IO](),
            NodeToNodeServer.Settings(),
            agreed.data.peerSharing
          )
          .use { _ =>
            NodeToNode.clients[IO, Bytes](mux, Mux.Role.Initiator, agreed.version, raw).use {
              conn =>
                val syncing = conn.chainSync
                  .findIntersect(Vector(ChainSync.Point.Origin))
                  .flatMap(_ => conn.chainSync.requestNextBatch(4))
                  .flatMap(updates => IO.println(s"chain-sync: ${updates.size} updates"))
                val waiting = silentWindow(
                  duration,
                  listen(Protocols.LeiosNotify),
                  listen(Protocols.LeiosFetch)
                ).flatMap {
                  case true  => IO.println("duplex: nothing received on 18 or 19")
                  case false => IO.unit
                }
                syncing *> waiting
            }
          }
      yield ()
    }

  /** A node that is listening and serves the Praos protocols from an empty chain, as this node's
    * inbound side does, for a reference relay that has it as a local root and so connects to it:
    * does the reference start LeiosNotify and LeiosFetch toward a peer it made hot, what is the
    * first message, and what does this node's multiplexer do with it? `withLeios` registers 18 and
    * 19; without, the set is `NodeToNodeServer.Protocol` as it is today.
    */
  private def listen(
      port: Int,
      magic: Long,
      log: Log,
      duration: FiniteDuration,
      withLeios: Boolean
  ): IO[Unit] =
    val protocols = NodeToNodeServer.Protocol ++ (if withLeios then leiosProtocols else Set.empty)
    val config = Mux.Config(role = Mux.Role.Responder, duplex = true)
    val serve = (bearer: Bearer[IO], _: SocketAddress[IpAddress]) =>
      Handle
        .allow[NetworkError] {
          Mux.resource[IO](bearer, protocols, config).use { mux =>
            for
              agreed <- mux
                .channel(Protocols.Handshake)
                .flatMap(HandshakeServer.run(_, HandshakeServer.offers(magic, duplex = true)))
              _ <- IO.println(s"inbound handshake: version ${agreed.version} data ${agreed.data}")
              watch = (protocol: Int) =>
                mux.channel(protocol, Mux.Role.Responder).flatMap { channel =>
                  nextItem(channel, duration).flatMap { msg =>
                    log.line(protocol, "first-message-from-reference", "", msg.hex) *>
                      IO.println(s"reference sent on $protocol: ${msg.hex.take(80)}")
                  }
                }
              _ <- NodeToNodeServer
                .start(
                  mux,
                  EmptyChain.handlers[IO](),
                  NodeToNodeServer.Settings(),
                  peerSharing = false
                )
                .use { _ =>
                  val leios =
                    if withLeios then
                      (watch(Protocols.LeiosNotify), watch(Protocols.LeiosFetch)).parTupled.void
                    else IO.never[Unit]
                  IO.race(mux.closed, IO.race(IO.sleep(duration), leios)).flatMap {
                    case Left(reason) => IO.println(s"connection ended: ${reason.message}")
                    case Right(_)     => IO.println("listener: window over, connection still open")
                  }
                }
            yield ()
          }
        }
        .rescue(e => IO.println(s"ENDED ${e.message}"))
    BearerServer
      .listen[IO](None, Port.fromInt(port), 4)(serve)
      .use(address => IO.println(s"listening on $address") *> IO.sleep(duration + 5.seconds))

  /** ChainSync from Origin: the first header with an announcement, the first with a certificate and
    * their numbers, slots and hashes. Header body positions as in `dijkstra.cddl`.
    */
  private def chain(addr: SocketAddress[Host], magic: Long, headers: Int): IO[Unit] =
    val protocols =
      Set(Protocols.Handshake, Protocols.ChainSync, Protocols.BlockFetch, Protocols.KeepAlive)
    open(addr, protocols, duplex = false) { mux =>
      for
        agreed <- handshake(mux, magic, initiatorOnly = true)
        _ <- NodeToNode.clients[IO, Bytes](mux, Mux.Role.Initiator, agreed.version, raw).use {
          conn =>
            for
              found <- conn.chainSync.findIntersect(Vector(ChainSync.Point.Origin))
              _ <- IO.println(s"intersection: ${found.map(_._1)}")
              seen <- Ref.of[IO, (Option[String], Option[String], Int)]((None, None, 0))
              batch = conn.chainSync.requestNextBatch(4).flatMap { updates =>
                seen.update { case (ann, cert, n) =>
                  updates.foldLeft((ann, cert, n)) {
                    case ((a, c, k), ChainSyncClient.Update.RollForward(h, _)) =>
                      val info = describe(h)
                      (
                        a.orElse(info.filter(_.announces).map(_.text)),
                        c.orElse(info.filter(_.certifies).map(_.text)),
                        k + 1
                      )
                    case (acc, _) => acc
                  }
                }
              }
              _ <- Ref.of[IO, Boolean](true).flatMap { _ =>
                (batch *> seen.get).iterateUntil { case (a, c, n) =>
                  n >= headers || (a.isDefined && c.isDefined)
                }
              }
              result <- seen.get
              _ <- IO.println(s"headers read: ${result._3}")
              _ <- IO.println(s"first announcement: ${result._1.getOrElse("none")}")
              _ <- IO.println(s"first certificate: ${result._2.getOrElse("none")}")
            yield ()
        }
      yield ()
    }

  private final case class HeaderInfo(text: String, announces: Boolean, certifies: Boolean)

  /** Block number, slot, hash and the two Leios fields of a header envelope `[era, tag24(header)]`.
    */
  private def describe(envelope: Bytes): Option[HeaderInfo] =
    for
      root <- Cbor.decode(envelope, limits).toOption
      era <- root.value match
        case Value.Arr(Vector(e, p)) =>
          e.value match
            case Value.UInt(n) => Some((n.toInt, p))
            case _             => None
        case _ => None
      inner <- era._2.value match
        case Value.Tag(_, n) => Some(n)
        case _               => None
      encoded <- inner.value match
        case Value.ByteString(b) => Some(b)
        case _                   => None
      header <- Cbor.decode(encoded, limits).toOption
      fields <- header.value match
        case Value.Arr(Vector(b, _)) =>
          b.value match
            case Value.Arr(xs) if xs.size == 12 => Some(xs)
            case _                              => None
        case _ => None
      number <- fields(0).value match
        case Value.UInt(n) => Some(n)
        case _             => None
      slot <- fields(1).value match
        case Value.UInt(n) => Some(n)
        case _             => None
      certifies = fields(10).value == Value.Bool(true)
      announced = fields(11).value match
        case Value.Arr(Vector(h, _)) =>
          h.value match
            case Value.ByteString(b) => Some(b.hex)
            case _                   => None
        case _ => None
      hash = Blake2b.hash256.hash(encoded).hex
    yield HeaderInfo(
      s"era index ${era._1}, block $number, slot $slot, hash $hash" +
        announced.fold("")(h => s", announces EB $h") + (if certifies then ", certifies" else ""),
      announced.isDefined,
      certifies
    )
end LeiosRefProbe

/** What a node with no chain serves: ChainSync from Origin only, no blocks, transactions ignored.
  */
private object EmptyChain:
  import cats.effect.{Async, Resource}
  import xyz.kd5ujc.scarab.protocol.ChainSync

  def handlers[F[_]: Async](): ServerHandlers[F, Bytes] =
    val codec = new ChainSync.PayloadCodec[Bytes]:
      def encode(value: Bytes): Either[Nothing, Bytes] = Right(value)
      def decode(bytes: Bytes): Either[Nothing, Bytes] = Right(bytes)
    val source = new ChainSyncServer.Source[F, Bytes]:
      def intersect(points: Vector[ChainSync.Point]) =
        Async[F].pure(
          if points.contains(ChainSync.Point.Origin) then
            Right((ChainSync.Point.Origin, ChainSync.Tip.Origin))
          else Left(ChainSync.Tip.Origin)
        )
      def poll = Async[F].pure(None)
      def await = Async[F].never
    val blocks = new BlockFetchServer.Blocks[F]:
      def range(from: ChainSync.Point, to: ChainSync.Point, maxBlocks: Int) = Async[F].pure(None)
    val sink = new TxSubmissionServer.Sink[F]:
      def known(id: Bytes) = Async[F].pure(false)
      def submit(originals: Vector[Bytes]) = Async[F].unit
    ServerHandlers(codec, Resource.pure[F, ChainSyncServer.Source[F, Bytes]](source), blocks, sink)
