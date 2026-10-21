// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.preview

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.protocol.Era

/** Comparison-mechanism controls, not additional observations of a running Haskell node. */
class ReplayReferenceSuite extends CatsEffectSuite:
  private val hash = "ab" * 32
  private val slot = BigInt(20)
  private val source = Path.of("native-reference/000000000020-" + hash + ".json")
  private def json(text: String): Json = parse(text).fold(e => fail(e.message), identity)
  private val body = Json.obj(
    "point" -> Json.obj("slot" -> Json.fromBigInt(slot), "hash" -> Json.fromString(hash)),
    "era" -> Json.fromString("conway"),
    "epoch" -> Json.fromInt(1),
    "fees" -> Json.fromInt(7),
    "deposited" -> Json.fromInt(0),
    "treasury" -> Json.fromInt(0),
    "reserves" -> Json.fromInt(100),
    "blocksCurrent" -> Json.obj(),
    "blocksBefore" -> Json.obj(),
    "snapshots" -> Json.obj(),
    "rewardUpdate" -> Json.Null,
    "stakeDistrib" -> Json.obj(),
    "protocolState" -> Json.obj(),
    "accounts" -> Json.obj(),
    "dreps" -> Json.obj(),
    "numDormantEpochs" -> Json.fromInt(0),
    "params" -> Json.obj(),
    "genDelegs" -> Json.obj(),
    "futureGenDelegs" -> Json.arr(),
    "pools" -> Json.obj(),
    "poolParams" -> Json.obj(),
    "futurePools" -> Json.obj(),
    "retiring" -> Json.obj(),
    "utxo" -> Json.obj(),
    "gov" -> Json.obj()
  )
  private def bound(value: Json): ReplayReference.Bound =
    ReplayReference
      .decode(source, slot, hash, value.noSpaces.getBytes("UTF-8"))
      .fold(e => fail(e.toString), identity)
  private def actual(value: Json): Json =
    value.mapObject(_.remove("era").add("format", Json.fromString("scarab-state-digest-1")))
  private def changed(value: Json, key: String, replacement: Json): Json =
    value.mapObject(_.add(key, replacement))
  private def record(slot: Int): ReplayInputs.Record =
    ReplayInputs.Record("00001.chunk", 0, 1, slot, None, None, None)

  test("a configured reference must be nonempty, well formed and fully inside the replay") {
    IO {
      assertEquals(
        ReplayInputs.referenceTargets(Vector.empty),
        Left(ReplayInputs.Error.EmptyReference)
      )
      val targets = Map(BigInt(20) -> hash)
      val records = Vector(record(15), record(20), record(30))
      assertEquals(ReplayInputs.referenceWindow(targets, 10, records), Right(targets))
      assertEquals(
        ReplayInputs.referenceWindow(Map.empty, 10, records),
        Left(ReplayInputs.Error.EmptyReference)
      )
      Vector(5, 10, 31).foreach { n =>
        assertEquals(
          ReplayInputs.referenceWindow(Map(BigInt(n) -> hash), 10, records),
          Left(ReplayInputs.Error.TargetOutsideWindow(n, 10, 30))
        )
      }
      assertEquals(
        ReplayInputs.referenceWindow(Map(BigInt(21) -> hash), 10, records),
        Left(ReplayInputs.Error.TargetAbsentFromWindow(21))
      )
      val line = s"""{"slot":20,"hash":"$hash"}"""
      assertEquals(ReplayInputs.referenceTargets(Vector(line)), Right(targets))
      assertEquals(
        ReplayInputs.referenceTargets(Vector(line, line)),
        Left(ReplayInputs.Error.DuplicateTarget(20))
      )
      assertEquals(
        ReplayInputs.referenceTargets(Vector("""{"slot":20,"hash":"00"}""")),
        Left(ReplayInputs.Error.ReferenceHashLength("00"))
      )
    }
  }

  test(
    "native fields compare exactly while Scala-only fields are explicitly outside the relation"
  ) {
    IO {
      val expected = bound(body)
      assertEquals(ReplayReference.compare(expected, actual(body), Era.Conway), Right(()))
      assertEquals(
        ReplayReference
          .compare(expected, changed(actual(body), "scalaOnly", Json.fromInt(99)), Era.Conway),
        Right(())
      )
      assertEquals(
        ReplayReference.compare(expected, actual(body), Era.Babbage),
        Left(ReplayReference.Error.EraMismatch(Era.Conway, Era.Babbage))
      )
      val altered = changed(actual(body), "fees", Json.fromInt(8))
      assertEquals(
        ReplayReference.compare(expected, altered, Era.Conway),
        Left(
          ReplayReference.Error.DifferentValue(
            Vector(ReplayReference.Segment.Field("fees")),
            Json.fromInt(7),
            Json.fromInt(8)
          )
        )
      )
      val absent = actual(body).mapObject(_.remove("fees"))
      assertEquals(
        ReplayReference.compare(expected, absent, Era.Conway),
        Left(
          ReplayReference.Error
            .MissingValue(Vector(ReplayReference.Segment.Field("fees")), Json.fromInt(7))
        )
      )
    }
  }

  test("numeric differences beyond ten decimals and nested changes never round away") {
    IO {
      val params = json("""{"nested":[{"value":0.123456789011}]}""")
      val other = json("""{"nested":[{"value":0.123456789012}]}""")
      val native = changed(body, "params", params)
      val result =
        ReplayReference.compare(bound(native), actual(changed(native, "params", other)), Era.Conway)
      result match
        case Left(ReplayReference.Error.DifferentValue(path, _, _)) =>
          assertEquals(
            path,
            Vector(
              ReplayReference.Segment.Field("params"),
              ReplayReference.Segment.Field("nested"),
              ReplayReference.Segment.Index(0),
              ReplayReference.Segment.Field("value")
            )
          )
        case other => fail(s"expected an exact nested numeric difference, got $other")
    }
  }

  test("point-only stand-ins, missing required fields and foreign point bodies are refused") {
    IO {
      val pointOnly = Json.obj(
        "point" -> body.hcursor.downField("point").focus.getOrElse(fail("point")),
        "era" -> Json.fromString("conway")
      )
      ReplayReference.decode(source, slot, hash, pointOnly.noSpaces.getBytes("UTF-8")) match
        case Left(ReplayReference.Error.MissingFields(_, missing)) =>
          assert(missing.contains("utxo")); assert(missing.contains("rewardUpdate"))
        case other => fail(s"expected missing reference fields, got $other")
      val wrong = changed(
        body,
        "point",
        Json.obj("slot" -> Json.fromInt(21), "hash" -> Json.fromString(hash))
      )
      assertEquals(
        ReplayReference.decode(source, slot, hash, wrong.noSpaces.getBytes("UTF-8")),
        Left(ReplayReference.Error.PointSlot(source, 20, 21))
      )
      val foreignHash = "cd" * 32
      val otherHash = changed(
        body,
        "point",
        Json.obj("slot" -> Json.fromBigInt(slot), "hash" -> Json.fromString(foreignHash))
      )
      assertEquals(
        ReplayReference.decode(source, slot, hash, otherHash.noSpaces.getBytes("UTF-8")),
        Left(ReplayReference.Error.PointHash(source, hash, foreignHash))
      )
    }
  }

  // Full original vector from scripts/oracle/digest.py, also used by StateDumpSuite.
  private val entries = json(
    """{"keyHash-aa":{"b":1,"a":[1,"z",null,true],"c":{"y":"é\"q","x":0}},"txid#0":"abc","zz":12345678901234567890}"""
  )
  private val zero = "0:" + "0" * 64
  private val buckets = Vector
    .fill(16)(zero)
    .updated(0, "1:3d307e8b04b3a97dc95e798cf0204230ddaf8425115050259ce1801807418247")
    .updated(9, "1:9f31969cc06e0f882ab70f15c64b8d64291888a253b3329f27d0eb52a53fe194")
    .updated(10, "1:c94fded1923582aa8bbb4f96c1cdb587b58a3900a7a8da8205527a236158b8d2")
  private val digest = Json.obj(
    "count" -> Json.fromInt(3),
    "digest" -> Json.fromString("a5b1f3f957573bb07fd0d8397839851cbc5245c80cac5d46ca04e58e0dda1cad"),
    "buckets" -> Json.arr(buckets.map(Json.fromString)*)
  )

  test("reduced/native and unreduced/Scala maps compare all independently computed digest fields") {
    IO {
      val native = changed(body, "accounts", digest)
      val found = actual(changed(body, "accounts", entries))
      assertEquals(ReplayReference.compare(bound(native), found, Era.Conway), Right(()))
      val corrupt = changed(
        digest,
        "buckets",
        Json.arr(buckets.updated(15, "1:" + "0" * 64).map(Json.fromString)*)
      )
      ReplayReference.compare(bound(changed(body, "accounts", corrupt)), found, Era.Conway) match
        case Left(ReplayReference.Error.DifferentValue(path, _, _)) =>
          assertEquals(path, Vector(ReplayReference.Segment.Field("accounts")))
        case other => fail(s"expected changed bucket rejection, got $other")
    }
  }

  private val directory: Resource[IO, Path] =
    Resource.make(IO.blocking(Files.createTempDirectory("scarab-native-reference-"))) { dir =>
      Resource.fromAutoCloseable(IO.blocking(Files.list(dir))).use { stream =>
        stream.iterator.asScala.toVector.traverse_(file => IO.blocking(Files.delete(file)))
      } *> IO.blocking(Files.delete(dir))
    }

  test(
    "missing native bodies fail, and same-point changed contents cannot pass on export existence"
  ) {
    directory.use { dir =>
      val file = dir.resolve(f"$slot%012d-$hash.json")
      for
        missing <- ReplayReference.load[IO](dir, slot, hash)
        _ <- IO(missing match
          case Left(ReplayReference.Error.Read(path, _: java.nio.file.NoSuchFileException)) =>
            assertEquals(path, file)
          case other => fail(s"expected a missing body error, got $other"))
        _ <- IO.blocking(Files.writeString(file, body.noSpaces))
        native <- ReplayReference
          .load[IO](dir, slot, hash)
          .map(_.fold(e => fail(e.toString), identity))
        _ <- IO(assertEquals(ReplayReference.compare(native, actual(body), Era.Conway), Right(())))
        _ <- IO.blocking(Files.writeString(file, changed(body, "fees", Json.fromInt(8)).noSpaces))
        tampered <- ReplayReference
          .load[IO](dir, slot, hash)
          .map(_.fold(e => fail(e.toString), identity))
        _ <- IO {
          assertEquals(tampered.slot, native.slot)
          assertEquals(tampered.hash, native.hash)
          assertNotEquals(tampered.sha256, native.sha256)
          assertEquals(
            ReplayReference.compare(tampered, actual(body), Era.Conway),
            Left(
              ReplayReference.Error.DifferentValue(
                Vector(ReplayReference.Segment.Field("fees")),
                Json.fromInt(8),
                Json.fromInt(7)
              )
            )
          )
        }
      yield ()
    }
  }
