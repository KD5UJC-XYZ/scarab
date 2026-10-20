// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.Instant
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Rational}
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, LedgerGlobals}
import xyz.kd5ujc.scarab.ledger.rules.{ConwayEpoch, Globals, Sts}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.Codec
import xyz.kd5ujc.scarab.storage.haskell.state.HaskellStateCodecs

import HaskellStateCodecs.given

/** EPOCH parameter-consistency controls, separate from the original failing generator case. */
class UpstreamEpochParameterConformanceSuite extends FunSuite:
  private val base = Path.of("node/src/test/resources/upstream/conway-epoch-parameters")
  private given ProtVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)
  private val ids = Vector("minimal-inconsistent", "minimal-consistent") ++
    (497201L to 497208L).map(_.toString)
  private val globals = Globals(
    LedgerGlobals(
      BigInt("45000000000000000"),
      BigInt(100),
      Rational(9, 10),
      network = 0,
      updateQuorum = 5
    ),
    EpochGeometry(Instant.EPOCH, 1000L, 100L, 10, BigDecimal("0.9"))
  )

  private def decode[A](id: String, name: String, codec: Codec[A]): A =
    val path = base.resolve(s"cases/$id/$name.cbor")
    val node = Cbor
      .decode(Bytes.fromArray(Files.readAllBytes(path)))
      .fold(e => fail(s"$path: $e"), identity)
    codec.decode(node).fold(e => fail(s"$path: ${e.render}"), identity)

  test("all controls retain original exported bytes and independent comparison outcomes") {
    val manifest = parse(Files.readString(base.resolve("manifest.json")))
      .fold(e => fail(e.message), identity)
      .hcursor
    val patches = manifest.get[Vector[Json]]("buildPatches").fold(e => fail(e.message), identity)
    patches.foreach { patch =>
      val descriptor = patch.hcursor
      val path = descriptor.get[String]("path").fold(e => fail(e.message), identity)
      val sha = MessageDigest
        .getInstance("SHA-256")
        .digest(Files.readAllBytes(Path.of(path)))
        .map(b => f"${b & 0xff}%02x")
        .mkString
      assertEquals(descriptor.get[String]("sha256").toOption, Some(sha))
    }
    val rows = manifest.get[Vector[Json]]("cases").fold(e => fail(e.message), identity)
    assertEquals(rows.map(_.hcursor.get[String]("id").fold(e => fail(e.message), identity)), ids)
    rows.foreach { row =>
      val cursor = row.hcursor
      val id = cursor.get[String]("id").fold(e => fail(e.message), identity)
      assertEquals(
        cursor.get[Int]("upstreamPropertyExitCode").toOption,
        Some(if id == "minimal-inconsistent" then 1 else 0)
      )
      val files = cursor.get[Map[String, Json]]("files").fold(e => fail(e.message), identity)
      assertEquals(
        files.keySet,
        Set(
          "context.cbor",
          "environment.cbor",
          "state.cbor",
          "signal.cbor",
          "implementation-state.cbor",
          "result.txt"
        )
      )
      files.foreach { (name, descriptor) =>
        val bytes = Files.readAllBytes(base.resolve(s"cases/$id/$name"))
        val sha =
          MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
        assertEquals(descriptor.hcursor.get[String]("sha256").toOption, Some(sha))
        assertEquals(descriptor.hcursor.get[Int]("bytes").toOption, Some(bytes.length))
      }
      assertEquals(
        Files.readString(base.resolve(s"cases/$id/result.txt")),
        "implementation-accepted\nagda-accepted\n"
      )
    }
  }

  ids.foreach { id =>
    test(s"EPOCH $id matches the complete native state with parameter source preserved") {
      val codec = HaskellStateCodecs.epochState[Conway]
      val before = decode(id, "state", codec)
      val expected = decode(id, "implementation-state", codec)
      val epoch = decode(id, "signal", summon[Codec[EpochNo]])
      val gov = before.ls.utxo.gov
      val pending = gov.drepPulsing.ratify
      assertEquals(gov.curPParams.protocolMajor, BigInt(9))
      assert(pending.enacted.isEmpty)
      assert(pending.expired.isEmpty)
      assertEquals(gov.futurePParams, FuturePParams.NoPParamsUpdate)
      if id == "minimal-inconsistent" then
        assertEquals(gov.curPParams.rho, Rational.zero)
        assertEquals(pending.enact.params.rho, Rational(1, 2))
        assertEquals(pending.enact.params.copy(rho = gov.curPParams.rho), gov.curPParams)
      else assertEquals(pending.enact.params, gov.curPParams)
      assertEquals(expected.ls.utxo.gov.curPParams, gov.curPParams)
      val actual = Sts
        .apply(ConwayEpoch.sts)(globals, (), before, epoch)
        .fold(e => fail(s"native-accepted EPOCH refused: $e"), identity)
      assertEquals(Diff[EpochState[Conway]].diff(actual, expected), None)
    }
  }
