// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.Instant
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Rational, Value}
import xyz.kd5ujc.scarab.ledger.{
  Certificate,
  Committee,
  DecoderVersion,
  EpochGeometry,
  GovActionId,
  GovActionState,
  LedgerGlobals
}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.rules.{ConwayCert, Globals, JudgedSts, Pool, PoolCert}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.Codec
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellStateCodecs, NodeOrders, Params, Wire}

import HaskellStateCodecs.given
import NodeOrders.given

/** A full generated Conway CERT trace, routed through the exact subrule selected by CERT. */
class UpstreamCertConformanceSuite extends FunSuite:
  private val base = Path.of("node/src/test/resources/upstream/conway-cert")
  private val seed = 493101L
  private val protVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)

  private def bytes(path: Path): Bytes = Bytes.fromArray(Files.readAllBytes(path))

  private def node(path: Path) =
    Cbor.decode(bytes(path)).fold(e => fail(s"$path: $e"), identity)

  private def items(n: xyz.kd5ujc.scarab.models.runtime.Node, size: Int) =
    n.value match
      case Value.Arr(xs) if xs.size == size => xs
      case other                            => fail(s"expected $size-item array, got $other")

  private def state(path: Path): ConwayCertState =
    given ProtVer = protVer
    summon[Codec[ConwayCertState]].decode(node(path)).fold(e => fail(e.render), identity)

  private def sha256(path: Path): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(path))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private val manifestRows = parse(Files.readString(base.resolve("manifest.json")))
    .fold(error => fail(s"CERT manifest: $error"), identity)
    .hcursor
    .downField("cases")
    .as[Vector[Json]]
    .fold(error => fail(s"CERT manifest rows: $error"), identity)

  private def verifyManifestFile(name: String, path: Path): Unit =
    val row = manifestRows.headOption.getOrElse(fail("CERT manifest has no generated row"))
    assertEquals(row.hcursor.get[Long]("seed").toOption, Some(seed))
    val file = row.hcursor.downField("files").downField(name)
    assertEquals(file.get[String]("sha256").toOption, Some(sha256(path)), s"$name sha256")
    assertEquals(file.get[Int]("bytes").toOption, Some(Files.size(path).toInt), s"$name size")

  private val globals = Globals(
    LedgerGlobals(BigInt(0), BigInt(1), Rational.zero, network = 0),
    EpochGeometry(Instant.EPOCH, 1000L, 1L, 1, BigDecimal("0.1"))
  )

  test("the actual CERT property row retains its accepted implementation and Agda statuses") {
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
    ).foreach(name => verifyManifestFile(name, dir.resolve(name)))
  }

  test("the generated CERT registration reaches POOL with full decoded state and environment") {
    given ProtVer = protVer
    val dir = base.resolve(s"cases/$seed")

    // The upstream CERT context is [withdrawals, votes]; it is retained and checked structurally.
    // Neither field is read by the selected POOL subrule, which is how the pinned CERT transition
    // dispatches this generated PoolCert.
    val context = items(node(dir.resolve("context.cbor")), 2)
    assertEquals(context.map(_.value), Vector(Value.Map(Vector.empty), Value.Map(Vector.empty)))

    val env = items(node(dir.resolve("environment.cbor")), 4)
    val pp = Params.conwayCodec.decode(env(0)).fold(e => fail(e.render), identity)
    val epoch = summon[Codec[EpochNo]].decode(env(1)).fold(e => fail(e.render), identity)
    val committee = Wire
      .strictMaybe(summon[Codec[Committee]])
      .decode(env(2))
      .fold(e => fail(e.render), identity)
    val proposalCodec = Wire.mapOf(
      summon[Codec[GovActionId]],
      summon[Codec[GovActionState]]
    )
    val proposals = proposalCodec.decode(env(3)).fold(e => fail(e.render), identity)
    assert(
      proposals.isEmpty,
      s"expected the full generated proposal environment to be empty: $proposals"
    )

    val before = state(dir.resolve("state.cbor"))
    val expected = state(dir.resolve("implementation-state.cbor"))
    val signal = Certificate
      .decode(node(dir.resolve("signal.cbor")), DecoderVersion.of(Era.Conway))
      .fold(error => fail(s"generated CERT signal: $error"), identity)
    ConwayCert.of(Bytes.empty, signal).fold(e => fail(e.toString), identity) match
      case ConwayCert.OfPool(cert @ PoolCert.RegPool(_)) =>
        val actual = JudgedSts
          .apply(Pool.Adapter)(xyz.kd5ujc.scarab.ledger.LedgerFailure.Rejected(Bytes.empty, _))(
            globals,
            Pool.RuleEnv(
              Bytes.fromArray(
                "cardano-ledger-CERT-493101".getBytes(java.nio.charset.StandardCharsets.UTF_8)
              ),
              pp,
              epoch.toBigInt
            ),
            before.pState,
            cert
          )
          .fold(e => fail(s"Scarab POOL refused upstream-accepted CERT: $e"), identity)
        assertEquals(actual, expected.pState, "the full POOL substate")
        assertEquals(before.vState, expected.vState, "unmodified VState")
        assertEquals(before.dState, expected.dState, "unmodified DState")
        assertEquals(committee, None, "decoded full committee input")
      case other =>
        fail(s"expected upstream CERT to dispatch the generated pool registration: $other")
  }
