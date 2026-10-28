// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.TestGeometry
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.Instant
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Rational, Value}
import xyz.kd5ujc.scarab.ledger.{Committee, EnactState, EpochGeometry, LedgerGlobals, Proposals}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Gov, GovEnv, GovSignal, JudgedSts}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.Codec
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellStateCodecs, Params, Wire}

import HaskellStateCodecs.given

/** One actual generated Conway GOV trace, decoded to its typed STS boundary. */
class UpstreamGovConformanceSuite extends FunSuite:
  private val base = Path.of("node/src/test/resources/upstream/conway-gov")
  private val seed = 495101L
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
    .fold(error => fail(s"GOV manifest: $error"), identity)
    .hcursor
    .downField("cases")
    .as[Vector[Json]]
    .fold(error => fail(s"GOV rows: $error"), identity)
    .headOption
    .getOrElse(fail("GOV manifest has no case"))

  private def verify(name: String, path: Path): Unit =
    val file = row.hcursor.downField("files").downField(name)
    assertEquals(file.get[String]("sha256").toOption, Some(sha256(path)), s"$name SHA-256")
    assertEquals(file.get[Int]("bytes").toOption, Some(Files.size(path).toInt), s"$name byte size")

  private val globals = Globals(
    LedgerGlobals(BigInt(0), BigInt(1), Rational.zero, network = 0),
    EpochGeometry(Instant.EPOCH, 1000L, 1L, TestGeometry.k(1), TestGeometry.f("0.1"))
  )

  test("the actual generated GOV trace retains accepted statuses and every raw component") {
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

  test("the generated empty GOV signal traverses Scarab GOV against the complete typed trace") {
    given ProtVer = protVer
    val dir = base.resolve(s"cases/$seed")
    val context = summon[Codec[EnactState]]
      .decode(node(dir.resolve("context.cbor")))
      .fold(e => fail(s"full EnactState context: ${e.render}"), identity)

    val env = node(dir.resolve("environment.cbor")).value match
      case Value.Arr(xs) if xs.size == 6 => xs
      case other => fail(s"expected exact six-field translated GOV environment, got $other")
    val txId = Codec.bytesSized(32, 32).decode(env(0)).fold(e => fail(e.render), identity)
    val epoch = summon[Codec[EpochNo]].decode(env(1)).fold(e => fail(e.render), identity)
    val pp = Params.conwayCodec.decode(env(2)).fold(e => fail(e.render), identity)
    val guardrails = Wire
      .strictMaybe(Codec.bytesSized(28, 28))
      .decode(env(3))
      .fold(e => fail(e.render), identity)
    val cert = summon[Codec[ConwayCertState]].decode(env(4)).fold(e => fail(e.render), identity)
    val committee = Wire
      .strictMaybe(summon[Codec[Committee]])
      .decode(env(5))
      .fold(e => fail(e.render), identity)
    assertEquals(committee, None, "actual Haskell GOV environment committee")
    assertNotEquals(
      context.committee,
      committee,
      "retain the distinct implicit EnactState context rather than substituting it into GovEnv"
    )

    val signalNode = node(dir.resolve("signal.cbor"))
    signalNode.value match
      case Value.Arr(Vector(votes, proposals, certificates)) =>
        assertEquals(votes.value, Value.Map(Vector.empty))
        proposals.value match
          case Value.Tag(258, inner) => assertEquals(inner.value, Value.Arr(Vector.empty))
          case other                 => fail(s"expected the encoded proposal set, got $other")
        assertEquals(certificates.value, Value.Arr(Vector.empty))
      case other => fail(s"expected the complete three-field empty GOV signal, got $other")
    val before = summon[Codec[Proposals]]
      .decode(node(dir.resolve("state.cbor")))
      .fold(e => fail(s"full GOV prestate: ${e.render}"), identity)
    val expected = summon[Codec[Proposals]]
      .decode(node(dir.resolve("implementation-state.cbor")))
      .fold(e => fail(s"full GOV implementation state: ${e.render}"), identity)
    assertEquals(before, expected, "pinned generated trace is an accepted no-op")

    val actual = JudgedSts
      .apply(Gov)(xyz.kd5ujc.scarab.ledger.LedgerFailure.Rejected(txId, _))(
        globals,
        GovEnv(txId, epoch.toBigInt, pp, guardrails, cert, committee),
        before,
        GovSignal(Vector.empty, Vector.empty, Vector.empty)
      )
      .fold(e => fail(s"Scarab GOV refused the upstream-accepted empty signal: $e"), identity)
    assertEquals(actual, expected, "complete GOV proposal state")
  }
