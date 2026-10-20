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
import xyz.kd5ujc.scarab.models.runtime.{Codec, DecodeError}
import xyz.kd5ujc.scarab.storage.haskell.state.HaskellStateCodecs

import HaskellStateCodecs.given

/** Actual typed EPOCH and NEWEPOCH inputs from the pinned constrained generators. */
class UpstreamEpochConformanceSuite extends FunSuite:
  private val base = Path.of("node/src/test/resources/upstream/conway-epochs/cases")
  private given ProtVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)

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

  private def decode[A](seed: Long, name: String, codec: Codec[A]): A =
    val path = base.resolve(s"$seed/$name.cbor")
    val node = Cbor
      .decode(Bytes.fromArray(Files.readAllBytes(path)))
      .fold(e => fail(s"$path: $e"), identity)
    codec.decode(node).fold(e => fail(s"$path: ${e.render}"), identity)

  test("original bytes and upstream comparison failures remain bound to the manifest") {
    val manifest = parse(Files.readString(base.getParent.resolve("manifest.json")))
      .fold(e => fail(e.message), identity)
      .hcursor
    val cases = manifest.get[Vector[Json]]("cases").fold(e => fail(e.message), identity)
    assertEquals(cases.size, 2)
    cases.foreach { row =>
      val cursor = row.hcursor
      val seed = cursor.get[Long]("seed").fold(e => fail(e.message), identity)
      val expectedExit = if seed == 497101L then 1 else 0
      assertEquals(cursor.get[Int]("upstreamPropertyExitCode").toOption, Some(expectedExit))
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
        val bytes = Files.readAllBytes(base.resolve(s"$seed/$name"))
        val sha = MessageDigest
          .getInstance("SHA-256")
          .digest(bytes)
          .map(b => f"${b & 0xff}%02x")
          .mkString
        assertEquals(descriptor.hcursor.get[String]("sha256").toOption, Some(sha))
        assertEquals(descriptor.hcursor.get[Int]("bytes").toOption, Some(bytes.length))
      }
      assertEquals(
        Files.readString(base.resolve(s"$seed/result.txt")),
        "implementation-accepted\nagda-accepted\n"
      )
    }
  }

  test("EPOCH retains an upstream comparison failure and matches the native full state") {
    val seed = 497101L
    val codec = HaskellStateCodecs.epochState[Conway]
    val before = decode(seed, "state", codec)
    val expected = decode(seed, "implementation-state", codec)
    val epoch = decode(seed, "signal", summon[Codec[EpochNo]])
    val gov = before.ls.utxo.gov
    val pending = gov.drepPulsing.ratify
    assert(pending.enacted.isEmpty)
    assert(pending.expired.isEmpty)
    assertEquals(gov.futurePParams, FuturePParams.PotentialPParamsUpdate(None))
    assertEquals(gov.curPParams.rho, Rational(8606909, 10000000))
    assertEquals(
      pending.enact.params.rho,
      Rational(BigInt("633925279839594401"), BigInt("1250000000000000000"))
    )
    assertNotEquals(pending.enact.params, gov.curPParams)
    assertEquals(expected.ls.utxo.gov.curPParams, gov.curPParams)
    val actual = Sts
      .apply(ConwayEpoch.sts)(globals, (), before, epoch)
      .fold(e => fail(s"native-accepted EPOCH refused: $e"), identity)
    assertEquals(Diff[EpochState[Conway]].diff(actual, expected), None)
  }

  test("the size-zero NEWEPOCH generator case retains its nonzero-stake domain gap") {
    val seed = 498101L
    val codec = summon[Codec[NewEpochState[Conway]]]
    val path = base.resolve(s"$seed/state.cbor")
    val node = Cbor
      .decode(Bytes.fromArray(Files.readAllBytes(path)))
      .fold(e => fail(e.message), identity)
    codec.decode(node) match
      case Left(
            DecodeError(
              DecodeError.Kind.Domain(VocabError.Zero(VocabError.Kind.NonZero)),
              path,
              _,
              _
            )
          ) =>
        assertEquals(path, List("pd", "totalActiveStake"))
      case other => fail(s"expected the original zero total-active-stake domain gap, got $other")
  }
