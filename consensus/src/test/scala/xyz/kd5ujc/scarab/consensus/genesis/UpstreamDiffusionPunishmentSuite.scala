// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.genesis

import java.nio.file.{Files, Path}
import io.circe.{Decoder, DecodingFailure, HCursor, Json}
import io.circe.parser.parse
import cats.syntax.all.*
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.state.{BlockIssuer, KeyHash, ShelleyBasedEra, Word64, VocabError}
import DiffusionPunishment.*
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}
import java.nio.charset.StandardCharsets.UTF_8

class UpstreamDiffusionPunishmentSuite extends FunSuite:
  private def word(c: HCursor, key: String): Decoder.Result[Word64] =
    c.get[BigInt](key)
      .flatMap(n =>
        Word64
          .fromBigInt(VocabError.Kind.BlockNo, n)
          .leftMap(e => DecodingFailure(e.toString, c.history))
      )
  private def issuer(c: HCursor): Decoder.Result[HotIdentity] =
    for
      hex <- c.get[String]("issuer")
      bytes <- Bytes.fromHex(hex).leftMap(e => DecodingFailure(e.toString, c.history))
      key <- KeyHash
        .fromBytes[BlockIssuer](bytes)
        .leftMap(e => DecodingFailure(e.toString, c.history))
      issue <- word(c, "issueNo")
    yield HotIdentity(key, issue)
  private def shelley(era: Int, n: Word64, id: HotIdentity): Option[View.Shelley[?]] = era match
    case 1 => Some(View.Shelley(ShelleyBasedEra.SShelley, n, id))
    case 2 => Some(View.Shelley(ShelleyBasedEra.SAllegra, n, id))
    case 3 => Some(View.Shelley(ShelleyBasedEra.SMary, n, id))
    case 4 => Some(View.Shelley(ShelleyBasedEra.SAlonzo, n, id))
    case 5 => Some(View.Shelley(ShelleyBasedEra.SBabbage, n, id))
    case 6 => Some(View.Shelley(ShelleyBasedEra.SConway, n, id))
    case _ => None
  private def input(c: HCursor): Decoder.Result[View] =
    for
      era <- c.get[Int]("era")
      n <- word(c, "blockNo")
      result <-
        if era == 0 then c.get[Boolean]("isEBB").map(View.Byron(n, _))
        else
          for
            identity <- issuer(c)
            view <- shelley(era, n, identity).toRight(
              DecodingFailure("unsupported input era", c.history)
            )
            raw <- c.get[String]("issuerVkey")
            bytes <- Bytes.fromHex(raw).leftMap(e => DecodingFailure(e.toString, c.history))
            _ <- Either.cond(
              Blake2b.hash224.hash(bytes) == identity.issuer.bytes,
              (),
              DecodingFailure("native issuer projection differs", c.history)
            )
          yield view
    yield result
  private def state(c: HCursor): Decoder.Result[State] =
    c.get[Int]("era").flatMap {
      case 0 =>
        c.get[Option[Json]]("previous")
          .flatMap(
            _.traverse(p =>
              (word(p.hcursor, "blockNo"), p.hcursor.get[Boolean]("isEBB")).mapN(View.Byron.apply)
            )
          )
          .map(State.Byron(_))
      case era =>
        for
          height <- c
            .get[Option[BigInt]]("lastBlockNo")
            .flatMap(
              _.traverse(n =>
                Word64
                  .fromBigInt(VocabError.Kind.BlockNo, n)
                  .leftMap(e => DecodingFailure(e.toString, c.history))
              )
            )
          ids <- c.get[Vector[Json]]("badIdentities").flatMap(_.traverse(j => issuer(j.hcursor)))
          representative <- ids.headOption.toRight(
            DecodingFailure("native state has no trap identity", c.history)
          )
          view <- shelley(era, height.getOrElse(Word64.zero), representative)
            .toRight(DecodingFailure("unsupported state era", c.history))
        yield State.Shelley(view.witness, height, ids.toSet)
    }

  test("every native Cardano tentative-header state transition matches across supported eras") {
    val rows = Files.readAllLines(Path.of("fixtures/reference/genesis-punishment/punishment.jsonl"))
    import scala.jdk.CollectionConverters.*
    val traces =
      rows.asScala.toVector.map(line => parse(line).fold(e => fail(e.toString), identity))
    assertEquals(traces.size, 72)
    val count = traces.foldLeft(0) { (total, trace) =>
      val label = trace.hcursor.get[String]("label").fold(e => fail(e.toString), identity)
      val steps = trace.hcursor.get[Vector[Json]]("steps").fold(e => fail(e.toString), identity)
      steps.foldLeft(initial) { (old, step) =>
        val c = step.hcursor
        val view = input(c.downField("input").success.fold(fail("missing input"))(identity))
          .fold(e => fail(e.toString), identity)
        val expected = state(c.downField("state").success.fold(fail("missing state"))(identity))
          .fold(e => fail(e.toString), identity)
        val accepted = c.get[Boolean]("accepted").fold(e => fail(e.toString), identity)
        val actual = judge(old, view, Invalidity.BlockItself)
        assertEquals(
          actual,
          Decision(expected, if accepted then Outcome.Forgive else Outcome.Punish),
          label
        )
        actual.state
      }
      total + steps.size
    }
    assertEquals(count, 4178)
  }

  test("original signed Conway header projects to the native hot identity") {
    val native =
      parse(Files.readString(Path.of("fixtures/reference/genesis-punishment/projection.json")))
        .fold(e => fail(e.toString), identity)
        .hcursor
    val source = native.downField("source")
    val path = source.get[String]("path").fold(e => fail(e.toString), identity)
    val line = source.get[Int]("line").fold(e => fail(e.toString), identity)
    val raw = Bytes
      .fromHex(Files.readAllLines(Path.of(path)).get(line - 1))
      .fold(e => fail(e.toString), identity)
    val block = xyz.kd5ujc.scarab.ledger.Block
      .decodeShelley(raw)
      .fold(e => fail(e.toString), identity)
    val expected = View.Shelley(
      ShelleyBasedEra.SConway,
      word(native, "blockNo").fold(e => fail(e.toString), identity),
      issuer(native).fold(e => fail(e.toString), identity)
    )
    val nativeRaw = native
      .get[String]("header")
      .flatMap(hex =>
        Bytes
          .fromHex(hex)
          .leftMap(e => DecodingFailure(e.toString, native.history))
      )
      .fold(e => fail(e.toString), identity)
    assertEquals(block.header.raw, nativeRaw)
    assertEquals(DiffusionPunishment.view(block.header), Right(expected))
  }

  private def point(c: HCursor): Point.Block =
    val slot = c
      .get[BigInt]("slot")
      .flatMap(n => UInt64.from(n).leftMap(e => DecodingFailure(e.toString, c.history)))
      .fold(e => fail(e.toString), identity)
    val hash = c.get[String]("hash").fold(e => fail(e.toString), identity)
    Point.Block(slot, Bytes(hash.getBytes(UTF_8).toVector))

  test(
    "actual ChainDB punishment callbacks agree on invalid blocks, prefixes, suffixes and valid EBBs"
  ) {
    import scala.jdk.CollectionConverters.*
    val traces = Files
      .readAllLines(Path.of("fixtures/reference/genesis-punishment/classification.jsonl"))
      .asScala
      .toVector
      .map(line => parse(line).fold(e => fail(e.toString), identity).hcursor)
    assertEquals(traces.size, 4)
    traces.foreach { trace =>
      val steps = trace.get[Vector[Json]]("steps").fold(e => fail(e.toString), identity)
      val last =
        steps.lastOption.fold(fail("native classification has no processed block"))(identity)
      val expected =
        last.hcursor.get[Option[String]]("punishment").fold(e => fail(e.toString), identity).map {
          case "BlockItself" => Invalidity.BlockItself
          case "BlockPrefix" => Invalidity.BlockPrefix
          case other         => fail(s"unknown native invalidity $other")
        }
      val c = trace
        .downField("classification")
        .success
        .fold(fail("missing classification inputs"))(identity)
      val added = point(c.downField("added").success.fold(fail("missing added point"))(identity))
      val invalid =
        point(c.downField("invalid").success.fold(fail("missing invalid point"))(identity))
      val lastValid = c
        .get[Option[Json]]("lastValid")
        .fold(e => fail(e.toString), identity)
        .fold[Point](Point.Origin)(j => point(j.hcursor))
      assertEquals(classify(added, invalid, lastValid), expected)
    }
  }

  test(
    "actual opaque native punishment DSL preserves prefix punishment, repeated traps and client reset"
  ) {
    val c =
      parse(Files.readString(Path.of("fixtures/reference/genesis-punishment/projection.json")))
        .fold(e => fail(e.toString), identity)
        .hcursor
    val view = View.Shelley(
      ShelleyBasedEra.SConway,
      word(c, "blockNo").fold(e => fail(e.toString), identity),
      issuer(c).fold(e => fail(e.toString), identity)
    )
    val prefix = judge(initial, view, Invalidity.BlockPrefix)
    val first = judge(prefix.state, view, Invalidity.BlockItself)
    val repeated = judge(first.state, view, Invalidity.BlockItself)
    val reset = judge(initial, view, Invalidity.BlockItself)
    val actual = Vector(prefix, first, repeated, reset).map(_.outcome == Outcome.Punish)
    assertEquals(
      actual,
      c.get[Vector[Boolean]]("dslPunished").fold(e => fail(e.toString), identity)
    )
  }
