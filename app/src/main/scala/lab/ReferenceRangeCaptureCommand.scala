// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO, Ref}
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.network.{ChainSync, NumericPeer}
import scala.concurrent.duration.*

/** Bounded original-byte acquisition only. START is excluded; END is included exactly. */
object ReferenceRangeCaptureCommand:
  private[lab] final case class Options(
      peer: NumericPeer,
      magic: Long,
      start: ChainSync.Point.Block,
      end: ChainSync.Point.Block,
      max: Int,
      maxEvents: Int = 128,
      maxBytes: Int = 32 * 1024 * 1024
  )
  private def checked[A](value: Either[String, A]): IO[A] =
    IO.fromEither(value.left.map(new IllegalArgumentException(_)))
  private def point(slot: String, hash: String): Either[String, ChainSync.Point.Block] =
    for
      _ <- Either.cond(slot.matches("0|[1-9][0-9]{0,19}"), (), "canonical uint64 slot required")
      n <- ChainSync.UInt64.from(BigInt(slot))
      _ <- Either.cond(hash.matches("[0-9a-f]{64}"), (), "canonical 32-byte hash required")
      bytes <- Bytes.fromHex(hash)
    yield ChainSync.Point.Block(n, bytes)
  private[lab] def options(args: List[String]): Either[String, Options] = args match
    case base if base.size == 9 =>
      for
        o <- options(base.take(7))
        events <- base(7).toIntOption
          .filter(n => n >= 1 && n <= 128 && n.toString == base(7))
          .toRight("event bound 1 through 128 required")
        bytes <- base(8).toIntOption
          .filter(n => n >= 1 && n <= 32 * 1024 * 1024 && n.toString == base(8))
          .toRight("byte bound 1 through 32 MiB required")
      yield o.copy(maxEvents = events, maxBytes = bytes)
    case List(port, magic, startSlot, startHash, endSlot, endHash, max) =>
      for
        _ <- Either.cond(
          port.matches("[1-9][0-9]{0,4}") && magic.matches("[1-9][0-9]{0,9}") && max.matches(
            "[1-8]"
          ),
          (),
          "canonical port, magic and maximum 1 through 8 required"
        )
        peer <- ReferenceHandshakeCommand.options(List(port, magic))
        start <- point(startSlot, startHash)
        end <- point(endSlot, endHash)
        _ <- Either.cond(
          end.slot.value > start.slot.value && end.hash != start.hash,
          (),
          "distinct increasing endpoints required"
        )
      yield Options(peer._1, peer._2, start, end, max.toInt)
    case _ =>
      Left("reference-range-capture PORT MAGIC START_SLOT START_HASH END_SLOT END_HASH MAX(1..8)")
  private[lab] def validateHeaders(
      o: Options,
      headers: Vector[ReferenceCaptureCommand.Header]
  ): Either[String, Unit] =
    for
      _ <- Either.cond(
        headers.nonEmpty && headers.size <= o.max && o.max >= 1 && o.max <= 8,
        (),
        "bounded nonempty header range required"
      )
      _ <- Either.cond(
        headers.map(_.hash).distinct.size == headers.size,
        (),
        "distinct header hashes required"
      )
      _ <- Either.cond(
        headers.last.hash == o.end.hash && headers.last.slot == o.end.slot.value,
        (),
        "exact end point required"
      )
      _ <- headers.zipWithIndex.traverse_ { (h, i) =>
        val parent = if i == 0 then o.start.hash else headers(i - 1).hash
        val slot = if i == 0 then o.start.slot.value else headers(i - 1).slot
        Either.cond(
          h.parent == parent && h.slot > slot && (i == 0 || h.blockNo == headers(
            i - 1
          ).blockNo + 1),
          (),
          "continuous header parents, slots and block numbers required"
        )
      }
    yield ()
  private[lab] def validate(
      o: Options,
      headers: Vector[ReferenceCaptureCommand.Header],
      blocks: Vector[Bytes]
  ): Either[String, Unit] =
    for
      _ <- validateHeaders(o, headers)
      _ <- Either.cond(blocks.size == headers.size, (), "one exact block per header required")
      _ <- headers.zip(blocks).zipWithIndex.traverse_ { case ((h, block), i) =>
        val anchor =
          if i == 0 then o.start
          else
            ChainSync.Point.Block(
              ChainSync.UInt64.from(headers(i - 1).slot).toOption.get,
              headers(i - 1).hash
            )
        for
          _ <- Either.cond(block.size > 0 && block.size <= 1048576, (), "one MiB block bound")
          parsed <- ReferenceCaptureCommand.header(h.envelope)
          _ <- Either.cond(parsed == h, (), "original envelope/header identity required")
          _ <- ReferenceCaptureCommand.compare(h, block, anchor)
        yield ()
      }
    yield ()
  private[lab] def record(h: ReferenceCaptureCommand.Header, block: Bytes): String =
    s"""{"record":"transfer-range-block","headerEnvelopeHex":"${h.envelope.hex}","rawBlockHex":"${block.hex}","headerHash":"${h.hash.hex}","parentHash":"${h.parent.hex}","slot":${h.slot},"blockNo":${h.blockNo}}"""
  private[lab] def account(
      counter: Ref[IO, (Int, Int)],
      o: Options
  )(events: Int, bytes: Int): IO[Unit] =
    counter
      .modify { (priorEvents, priorBytes) =>
        val next = (priorEvents + events, priorBytes + bytes)
        (next, next._1 <= o.maxEvents && next._2 <= o.maxBytes)
      }
      .flatMap(ok =>
        IO.raiseUnless(ok)(
          new IllegalArgumentException("range aggregate event/original-byte allowance exhausted")
        )
      )
  def run(args: List[String]): IO[ExitCode] =
    (for
      o <- checked(options(args))
      counter <- Ref.of[IO, (Int, Int)]((0, 0))
      observe = account(counter, o)
      headers <- ReferenceCaptureCommand.headersThroughBounded(
        o.peer,
        o.magic,
        o.start,
        Some(o.end),
        o.max,
        observe
      )
      _ <- checked(validateHeaders(o, headers))
      blocks <- headers.traverse(h =>
        ReferenceCaptureCommand.exactBlock(o.peer, o.magic, h, observe)
      )
      _ <- checked(validate(o, headers, blocks))
      totals <- counter.get
      _ <- headers.zip(blocks).traverse_((h, b) => IO.println(record(h, b)))
      _ <- IO.println(
        s"""{"scope":"reference-range-capture","passed":true,"capturedBlocks":${headers.size},"startSlot":${o.start.slot.value},"startHash":"${o.start.hash.hex}","endSlot":${o.end.slot.value},"endHash":"${o.end.hash.hex}","receivedEvents":${totals._1},"originalBytes":${totals._2},"eventDefinition":"ChainSync receive plus BlockFetch receive; handshake excluded","byteDefinition":"header envelopes plus raw blocks","acquisitionOnly":true,"originalHeaderBytesMatched":true,"bodyCommitmentsMatched":true,"signaturesChecked":false,"fullLedgerValidated":false,"consensusValidated":false}"""
      )
    yield ExitCode.Success).timeout(60.seconds).handleErrorWith { error =>
      IO.println(
        ValidatedRestartCapture.canonical(
          ReferenceJson.Json.Obj(
            Map(
              "scope" -> ReferenceJson.Json.Str("reference-range-capture"),
              "passed" -> ReferenceJson.Json.Lit("false"),
              "detail" -> ReferenceJson.Json
                .Str(Option(error.getMessage).getOrElse(error.getClass.getName))
            )
          )
        )
      ).as(ExitCode(2))
    }
