// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.Instant
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Rational, Value}
import xyz.kd5ujc.scarab.ledger.{EnactState, EpochGeometry, GovAction, GovActionId, LedgerGlobals}
import xyz.kd5ujc.scarab.ledger.rules.{Enact, EnactRuleEnv, EnactSignal, Globals, Sts}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.Codec
import xyz.kd5ujc.scarab.storage.haskell.state.HaskellStateCodecs

import HaskellStateCodecs.given

/** One actual generated Conway ENACT trace through the typed state transition. */
class UpstreamEnactConformanceSuite extends FunSuite:
  private val base = Path.of("node/src/test/resources/upstream/conway-enact")
  private val seed = 496101L
  private val protVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)

  private def node(path: Path) =
    Cbor.decode(Bytes.fromArray(Files.readAllBytes(path))).fold(e => fail(s"$path: $e"), identity)

  private def sha256(path: Path): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(path))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private val row = parse(Files.readString(base.resolve("manifest.json")))
    .fold(error => fail(s"ENACT manifest: $error"), identity)
    .hcursor
    .downField("cases")
    .as[Vector[Json]]
    .fold(error => fail(s"ENACT rows: $error"), identity)
    .headOption
    .getOrElse(fail("ENACT manifest has no row"))

  private def verify(name: String, path: Path): Unit =
    val file = row.hcursor.downField("files").downField(name)
    assertEquals(file.get[String]("sha256").toOption, Some(sha256(path)), s"$name SHA-256")
    assertEquals(file.get[Int]("bytes").toOption, Some(Files.size(path).toInt), s"$name bytes")

  private val globals = Globals(
    LedgerGlobals(BigInt(0), BigInt(1), Rational.zero, network = 0),
    EpochGeometry(Instant.EPOCH, 1000L, 1L, 1, BigDecimal("0.1"))
  )

  test("the exact generated ENACT case retains its upstream implementation and Agda outcomes") {
    val dir = base.resolve(s"cases/$seed")
    assertEquals(
      Files.readString(dir.resolve("result.txt")),
      "implementation-accepted\nagda-accepted\n"
    )
    Vector(
      "context.cbor",
      "environment.cbor",
      "state.cbor",
      "signal.cbor",
      "implementation-state.cbor",
      "result.txt"
    ).foreach(name => verify(name, dir.resolve(name)))
  }

  test("the generated ENACT action maps to the typed transition and complete EnactState") {
    given ProtVer = protVer
    val dir = base.resolve(s"cases/$seed")
    assertEquals(node(dir.resolve("environment.cbor")).value, Value.Null)
    val epoch = summon[Codec[EpochNo]]
      .decode(node(dir.resolve("context.cbor")))
      .fold(e => fail(s"exact ENACT context epoch: ${e.render}"), identity)
    val before = summon[Codec[EnactState]]
      .decode(node(dir.resolve("state.cbor")))
      .fold(e => fail(s"full ENACT prestate: ${e.render}"), identity)
    val expected = summon[Codec[EnactState]]
      .decode(node(dir.resolve("implementation-state.cbor")))
      .fold(e => fail(s"full ENACT implementation state: ${e.render}"), identity)
    val signal = node(dir.resolve("signal.cbor")).value match
      case Value.Arr(Vector(idNode, actionNode)) =>
        val id = summon[Codec[GovActionId]].decode(idNode).fold(e => fail(e.render), identity)
        val action = summon[Codec[GovAction]].decode(actionNode).fold(e => fail(e.render), identity)
        EnactSignal(id, action)
      case other => fail(s"expected exact two-field ENACT signal, got $other")

    val actual = Sts
      .apply(Enact)(globals, EnactRuleEnv(epoch.toBigInt, before.treasury), before, signal)
      .fold(e => fail(s"Scarab ENACT refused upstream-accepted action: $e"), identity)
    assertEquals(actual, expected, "complete Conway EnactState")
  }
