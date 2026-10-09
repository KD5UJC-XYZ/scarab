// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, ExitCode}
import java.nio.file.{Files, Path}
import cats.syntax.all.*
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.chain.{CardanoBlockIndex, CardanoBodyCommitment}
import lab.network.*
import scala.concurrent.duration.*

/** One live Conway successor and one exact block. No signature or ledger validation. */
object ReferenceCaptureCommand:
  final case class Header(
      envelope: Bytes,
      raw: Bytes,
      hash: Bytes,
      parent: Bytes,
      slot: BigInt,
      blockNo: BigInt,
      major: BigInt,
      minor: BigInt
  )

  private def checked[A](value: Either[String, A]): IO[A] =
    IO.fromEither(value.leftMap(new IllegalArgumentException(_)))
  private def array(n: Node, size: Int): Vector[Node] = n.value match
    case Value.Arr(xs) if xs.size == size => xs
    case _ => throw new IllegalArgumentException(s"expected $size-element array")
  private def uint(n: Node): BigInt = n.value match
    case Value.UInt(v) if v <= ChainSync.UInt64.Max => v
    case _ => throw new IllegalArgumentException("expected uint64")
  private def bytes(n: Node, size: Int): Bytes = n.value match
    case Value.ByteString(v) if v.size == size => v
    case _ => throw new IllegalArgumentException(s"expected $size bytes")

  def header(envelope: Bytes): Either[String, Header] =
    for
      root <- Cbor.decode(envelope, Cbor.Limits(65535, 24, 8192, 65535))
      raw <- Either
        .catchOnly[IllegalArgumentException] {
          val outer = array(root, 2)
          require(uint(outer(0)) == 6, "expected Conway NtN era index 6")
          outer(1).value match
            case Value.Tag(n, value) if n == 24 =>
              value.value match
                case Value.ByteString(b) if b.size <= 4096 => b
                case _ => throw new IllegalArgumentException("bounded header byte string required")
            case _ => throw new IllegalArgumentException("header tag24 required")
        }
        .leftMap(_.getMessage)
      node <- Cbor.decode(raw, Cbor.Limits(4096, 16, 256, 4096))
      result <- Either
        .catchOnly[IllegalArgumentException] {
          val h = array(node, 2)
          val body = array(h(0), 10)
          bytes(h(1), 448)
          val pv = array(body(9), 2)
          Header(
            envelope,
            raw,
            Blake2b.hash256.hash(raw),
            bytes(body(2), 32),
            uint(body(1)),
            uint(body(0)),
            uint(pv(0)),
            uint(pv(1))
          )
        }
        .leftMap(_.getMessage)
    yield result

  def compare(h: Header, raw: Bytes, anchor: ChainSync.Point): Either[String, String] =
    for
      indexed <- CardanoBlockIndex.inspect(raw)
      body <- CardanoBodyCommitment.inspect(raw).leftMap(_.toString)
      _ <- Either.cond(
        indexed.era == "conway" && indexed.headerBytes == h.raw &&
          indexed.headerHash == h.hash && indexed.slot == h.slot && indexed.blockNo == h.blockNo &&
          indexed.parentHash == h.parent,
        (),
        "header/block identity mismatch"
      )
      _ <- Either.cond(
        (anchor match
          case ChainSync.Point.Block(slot, hash) => h.parent == hash && h.slot > slot.value
          case _                                 => false
        ),
        (),
        "successor does not extend supplied anchor"
      )
      _ <- Either.cond(body.bodyCommitmentMatched, (), "body commitment mismatch")
    yield s"""{"scope":"header-block-byte-comparison","passed":true,"era":"conway","slot":${h.slot},"blockNo":${h.blockNo},"headerHash":"${h.hash.hex}","parentHash":"${h.parent.hex}","rawBlockSha256":"${indexed.rawSha256.hex}","headerProtocolVersion":{"major":${h.major},"minor":${h.minor}},"originalHeaderBytesMatched":true,"parentMatched":true,"bodySizeMatched":true,"bodyHashMatched":true,"declaredBodySize":${body.declaredSize},"actualBodySize":${body.actualSize},"signaturesChecked":false,"ledgerConformance":false}"""

  def headersThrough(
      peer: NumericPeer,
      magic: Long,
      anchor: ChainSync.Point,
      stopAt: Option[ChainSync.Point],
      maxHeaders: Int = 8
  ): IO[Vector[Header]] =
    if maxHeaders < 1 || maxHeaders > 8 then
      IO.raiseError(new IllegalArgumentException("header range exceeds eight-block bound"))
    else headersThroughBounded(peer, magic, anchor, stopAt, maxHeaders)

  /** Separate observation bound; persisted acquisition checkpoints remain limited to eight. */
  private[lab] def headersThroughBounded(
      peer: NumericPeer,
      magic: Long,
      anchor: ChainSync.Point,
      stopAt: Option[ChainSync.Point],
      maxHeaders: Int
  ): IO[Vector[Header]] =
    if maxHeaders < 1 || maxHeaders > 16 then
      IO.raiseError(new IllegalArgumentException("observation range exceeds sixteen-block bound"))
    else
      checked(TcpLimits.checked()).flatMap { limits =>
        AsyncTcpTransport
          .resource[IO](peer, limits)
          .use { transport =>
            val deadlines =
              SessionDeadlines[IO](5.seconds, 5.seconds, 5.seconds, IO.pure(Some(15.seconds)))
            ConnectionSession
              .resource[IO, ChainSyncFixtures.OpaqueNtNHeaderFixture](
                transport,
                ConnectionSession.NtN14,
                ChainSync.Role.Client,
                deadlines,
                ConnectionSession.Config(maxSduPayload = 65535, maxChunkBytes = 65543)
              )
              .use { session =>
                def forward(allowAlignment: Boolean): IO[Header] = session.receive.flatMap {
                  case ChainSync.Message.AwaitReply              => forward(allowAlignment)
                  case ChainSync.Message.RollForward(payload, _) => checked(header(payload.bytes))
                  case ChainSync.Message.RollBackward(point, _)
                      if allowAlignment && point == anchor =>
                    IO.println("{\"record\":\"intersection-alignment\",\"matchedAnchor\":true}") *>
                      session.send(ChainSync.Message.RequestNext) *> forward(false)
                  case other =>
                    IO.raiseError(new IllegalStateException(s"expected successor header: $other"))
                }
                def collect(found: Vector[Header]): IO[Vector[Header]] =
                  if found.size >= maxHeaders then
                    IO.raiseError(
                      new IllegalStateException("header range exceeds explicit observation bound")
                    )
                  else
                    session.send(ChainSync.Message.RequestNext) *> forward(found.isEmpty).flatMap {
                      h =>
                        checked(ChainSync.UInt64.from(h.slot)).flatMap { slot =>
                          val point = ChainSync.Point.Block(slot, h.hash)
                          if stopAt.isEmpty || stopAt.contains(point) then IO.pure(found :+ h)
                          else collect(found :+ h)
                        }
                    }
                for
                  negotiated <- session.negotiate(Handshake.defaultNodeToNode(magic))
                  _ <- IO.raiseUnless(
                    negotiated == Handshake.Result.Negotiated(14, Handshake.Data(magic))
                  )(new IllegalStateException("unexpected negotiation"))
                  _ <- session.send(ChainSync.Message.FindIntersect(Vector(anchor)))
                  intersection <- session.receive
                  _ <- intersection match
                    case ChainSync.Message.IntersectFound(point, _) if point == anchor => IO.unit
                    case _ => IO.raiseError(new IllegalStateException("anchor not found"))
                  h <- collect(Vector.empty)
                  _ <- session.done
                yield h
              }
          }
          .timeout(25.seconds)
      }

  def exactBlock(peer: NumericPeer, magic: Long, h: Header): IO[Bytes] =
    for
      limits <- checked(TcpLimits.checked())
      slot <- checked(ChainSync.UInt64.from(h.slot))
      point <- checked(CardanoBlockFetch.SpecificPoint.from(ChainSync.Point.Block(slot, h.hash)))
      raw <- AsyncTcpTransport.resource[IO](peer, limits).use { transport =>
        BlockFetchSession
          .resource[IO](
            transport,
            BlockFetchSession.Config(
              handshake = 5.seconds,
              times = BlockFetch
                .TimeLimits(busy = 5.seconds, streaming = 5.seconds, wholeRequest = 15.seconds)
            )
          )
          .use { session =>
            session.bounded {
              for
                _ <- session.negotiate(Handshake.Data(magic))
                _ <- session.request(CardanoBlockFetch.InclusiveRange.single(point))
                start <- session.receive
                _ <- IO.raiseUnless(start == BlockFetch.Message.StartBatch)(
                  new IllegalStateException("expected StartBatch")
                )
                message <- session.receive
                raw <- message match
                  case BlockFetch.Message.Block(block) => IO.pure(block.bytes)
                  case _ => IO.raiseError(new IllegalStateException("expected one block"))
                end <- session.receive
                _ <- IO.raiseUnless(end == BlockFetch.Message.BatchDone)(
                  new IllegalStateException("expected exact one-block batch")
                )
                _ <- session.finish
              yield raw
            }
          }
      }
    yield raw

  def options(args: List[String]): Either[String, (NumericPeer, Long, ChainSync.Point)] = args match
    case List(port, magic, slot, hash) =>
      for
        peerMagic <- ReferenceHandshakeCommand.options(List(port, magic))
        number <- Either
          .catchOnly[NumberFormatException](BigInt(slot))
          .leftMap(_ => "invalid anchor slot")
        n <- ChainSync.UInt64.from(number)
        bytes <- Bytes.fromHex(hash)
        _ <- Either.cond(bytes.size == 32, (), "anchor hash requires 32 bytes")
      yield (peerMagic._1, peerMagic._2, ChainSync.Point.Block(n, bytes))
    case _ => Left("usage: reference-capture PORT MAGIC ANCHOR_SLOT ANCHOR_HASH (loopback only)")

  /** Offline regression of captured public bytes; this command opens no socket. */
  def verify(args: List[String]): IO[ExitCode] = args match
    case List(headerFile, blockFile, slot, hash) =>
      def readHex(file: String, max: Int): IO[Bytes] = IO
        .blocking {
          val path = Path.of(file)
          require(Files.size(path) <= max.toLong * 2 + 2, "hex input exceeds byte cap")
          Files.readString(path).trim
        }
        .flatMap(text => checked(Bytes.fromHex(text)))
      (for
        parsed <- checked(options(List("1", "1082026", slot, hash)))
        envelope <- readHex(headerFile, 65535)
        raw <- readHex(blockFile, 1048576)
        h <- checked(header(envelope))
        report <- checked(compare(h, raw, parsed._3))
        _ <- IO.println(report)
      yield ExitCode.Success).handleErrorWith(e =>
        IO.println(s"ERROR: ${e.getMessage}").as(ExitCode(2))
      )
    case _ =>
      IO.println(
        "usage: reference-capture-check HEADER_HEX_FILE BLOCK_HEX_FILE ANCHOR_SLOT ANCHOR_HASH"
      ).as(ExitCode(2))

  def run(args: List[String]): IO[ExitCode] =
    checked(options(args))
      .flatMap { case (peer, magic, anchor) =>
        (for
          headers <- headersThrough(peer, magic, anchor, None, 1)
          h = headers.head
          _ <- IO.println(
            s"""{"record":"header","envelopeHex":"${h.envelope.hex}","headerHex":"${h.raw.hex}","headerHash":"${h.hash.hex}","headerProtocolVersion":{"major":${h.major},"minor":${h.minor}}}"""
          )
          raw <- exactBlock(peer, magic, h)
          _ <- IO.println(s"""{"record":"block","rawHex":"${raw.hex}"}""")
          report <- checked(compare(h, raw, anchor))
          _ <- IO.println(report)
        yield ExitCode.Success).timeout(45.seconds)
      }
      .handleErrorWith(e => IO.println(s"ERROR: ${e.getMessage}").as(ExitCode(2)))
