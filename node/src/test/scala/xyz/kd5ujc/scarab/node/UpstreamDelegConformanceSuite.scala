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
import xyz.kd5ujc.scarab.ledger.{Certificate, DecoderVersion, EpochGeometry, LedgerGlobals}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.rules.{ConwayCert, Deleg, Globals, JudgedSts}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.Codec
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellStateCodecs, Params, Wire}

import HaskellStateCodecs.given

/** Actual generated Conway DELEG judgments from cardano-ledger's pinned conformance property. */
class UpstreamDelegConformanceSuite extends FunSuite:
  private val base = Path.of("node/src/test/resources/upstream/conway-deleg")
  private val seeds = Vector(491101L, 491102L, 491103L, 491104L, 491105L)
  private val protVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)

  private def bytes(path: Path): Bytes = Bytes.fromArray(Files.readAllBytes(path))

  private def node(path: Path) =
    Cbor.decode(bytes(path)).fold(e => fail(s"$path: $e"), identity)

  private def arrayItems(n: xyz.kd5ujc.scarab.models.runtime.Node, size: Int) =
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

  private val globals = Globals(
    LedgerGlobals(BigInt(0), BigInt(1), Rational.zero, network = 0),
    EpochGeometry(Instant.EPOCH, 1000L, 1L, TestGeometry.k(1), TestGeometry.f("0.1"))
  )

  private val manifestRows = parse(Files.readString(base.resolve("manifest.json")))
    .fold(error => fail(s"DELEG manifest: $error"), identity)
    .hcursor
    .downField("cases")
    .as[Vector[Json]]
    .fold(error => fail(s"DELEG manifest rows: $error"), identity)

  private def verifyManifestFile(seed: Long, name: String, path: Path): Unit =
    val row = manifestRows
      .find(_.hcursor.get[Long]("seed").toOption.contains(seed))
      .getOrElse(fail(s"seed $seed absent from DELEG manifest"))
    val file = row.hcursor
      .downField("files")
      .downField(name)
    assertEquals(
      file.get[String]("sha256").toOption,
      Some(sha256(path)),
      s"seed $seed $name sha256"
    )
    assertEquals(
      file.get[Int]("bytes").toOption,
      Some(Files.size(path).toInt),
      s"seed $seed $name size"
    )

  test("pinned DELEG properties retain all generated statuses and full trace hashes") {
    seeds.foreach { seed =>
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
      )
        .foreach { name =>
          verifyManifestFile(seed, name, dir.resolve(name))
          assertEquals(sha256(dir.resolve(name)).length, 64, s"seed $seed $name")
        }
    }
  }

  test("Scarab DELEG adapter consumes the actual environment and matches complete Haskell states") {
    given ProtVer = protVer
    seeds.foreach { seed =>
      val dir = base.resolve(s"cases/$seed")
      val traceContext = bytes(dir.resolve("context.cbor"))
      val contextNode =
        Cbor.decode(traceContext).fold(e => fail(s"seed $seed context: $e"), identity)
      // ExecSpecRule/Conway/Deleg.hs defines this as the complete set of transaction DRep delegatees.
      // Keep it as original TRC evidence; the implementation's typed rule consumes env/state/signal.
      contextNode.value match
        case Value.Tag(258, inner) if inner.value.isInstanceOf[Value.Arr] => ()
        case other => fail(s"seed $seed context is not the upstream tagged Set: $other")

      val envBytes = bytes(dir.resolve("environment.cbor"))
      val envItems = arrayItems(
        Cbor.decode(envBytes).fold(e => fail(s"seed $seed environment: $e"), identity),
        2
      )
      val pp = Params.conwayCodec.decode(envItems(0)).fold(e => fail(e.render), identity)
      val poolCodec = Wire.mapOf(
        summon[Codec[KeyHash[StakePool]]],
        summon[Codec[StakePoolState]]
      )
      val envPools = poolCodec.decode(envItems(1)).fold(e => fail(e.render), identity)
      val before = state(dir.resolve("state.cbor"))
      val expected = state(dir.resolve("implementation-state.cbor"))
      assertEquals(envPools, before.pState.stakePools, s"seed $seed complete env pool map")
      val certificate = Certificate
        .decode(
          Cbor
            .decode(bytes(dir.resolve("signal.cbor")))
            .fold(e => fail(s"seed $seed signal: $e"), identity),
          DecoderVersion.of(Era.Conway)
        )
        .fold(e => fail(s"seed $seed certificate: $e"), identity)
      val typed = ConwayCert.of(Bytes.empty, certificate).fold(e => fail(e.toString), identity)
      typed match
        case ConwayCert.OfDeleg(cert) =>
          val actual = JudgedSts
            .apply(Deleg.Adapter)(xyz.kd5ujc.scarab.ledger.LedgerFailure.Rejected(Bytes.empty, _))(
              globals,
              Deleg.RuleEnv(
                Bytes.fromArray(
                  s"cardano-ledger-DELEG-$seed".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                ),
                pp,
                envPools
              ),
              before,
              cert
            )
            .fold(e => fail(s"seed $seed Scarab refused upstream-accepted DELEG: $e"), identity)
          assertEquals(actual, expected, s"seed $seed full ConwayCertState")
        case other => fail(s"seed $seed is not an upstream DELEG signal: $other")
      assertEquals(Cbor.decode(envBytes).toOption.get.original, envBytes)
    }
  }
