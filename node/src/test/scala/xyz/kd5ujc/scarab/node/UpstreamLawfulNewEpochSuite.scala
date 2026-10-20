// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.Instant
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Rational, Value}
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, LedgerGlobals}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, NewEpoch, Sts}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.Codec

/** Supplemental native constructors retain NonZero sentinels and coherent pending enactment. */
class UpstreamLawfulNewEpochSuite extends FunSuite:
  import UpstreamNativeStates.given
  private val base = Path.of("node/src/test/resources/upstream/conway-lawful-newepoch")
  private given ProtVer = ProtVer(Word64.fromUnsigned(10), Word64.zero)
  private val globals = Globals(
    LedgerGlobals(BigInt("45000000000000000"), 100, Rational(9, 10), 0, updateQuorum = 5),
    EpochGeometry(Instant.EPOCH, 1000L, 100L, 10, BigDecimal("0.9"))
  )

  private def node(id: String, name: String) =
    val path = base.resolve(s"cases/$id/$name.cbor")
    Cbor.decode(Bytes.fromArray(Files.readAllBytes(path))).fold(e => fail(s"$path: $e"), identity)

  private def decode[A](id: String, name: String, codec: Codec[A]): A =
    codec.decode(node(id, name)).fold(e => fail(s"$id/$name: ${e.render}"), identity)

  test(
    "all bounded generated inputs, independent verdicts and native results retain their hashes"
  ) {
    val manifest = parse(Files.readString(base.resolve("manifest.json")))
      .fold(e => fail(e.message), identity)
      .hcursor
    val rows = manifest.get[Vector[Json]]("cases").fold(e => fail(e.message), identity)
    assertEquals(rows.size, 12)
    rows.foreach { row =>
      val cursor = row.hcursor
      val id = cursor.get[String]("id").fold(e => fail(e.message), identity)
      assertEquals(cursor.get[Int]("upstreamPropertyExitCode").toOption, Some(0))
      assertEquals(cursor.get[String]("nativeVerdict").toOption, Some("accepted"))
      assertEquals(cursor.get[String]("agdaVerdict").toOption, Some("accepted"))
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
        assertEquals(descriptor.hcursor.get[String]("sha256").toOption, Some(sha), s"$id/$name")
        assertEquals(
          descriptor.hcursor.get[Int]("bytes").toOption,
          Some(bytes.length),
          s"$id/$name"
        )
      }
      assertEquals(
        Files.readString(base.resolve(s"cases/$id/result.txt")),
        "implementation-accepted\nagda-accepted\n"
      )
    }
  }

  Vector("same" -> 0L, "next" -> 1L, "skip" -> 3L).foreach { (kind, advance) =>
    (269101L to 269104L).foreach { seed =>
      val id = s"$kind-$seed"
      test(s"$id matches the complete applicable native NEWEPOCH result") {
        assertEquals(node(id, "environment").value, Value.Null)
        assertEquals(node(id, "context").value, Value.Null)
        val codec = UpstreamNativeStates.nativeNewEpochState
        val before = decode(id, "state", codec)
        val expected = decode(id, "implementation-state", codec)
        val epoch = decode(id, "signal", summon[Codec[EpochNo]])
        assertEquals(epoch.toBigInt, before.epoch.toBigInt + advance)
        val actual = Sts
          .apply(NewEpoch.sts[Conway])(globals, (), before, epoch)
          .fold(e => fail(s"native-accepted $id refused: $e"), identity)
        assertEquals(Diff[NewEpochState[Conway]].diff(actual, expected), None)
        assertEquals(actual.epoch, if advance == 1 then epoch else before.epoch)
      }
    }
  }
