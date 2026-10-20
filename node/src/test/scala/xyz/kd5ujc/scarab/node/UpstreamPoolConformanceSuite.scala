// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.Instant
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Rational, Value}
import xyz.kd5ujc.scarab.ledger.{Block, Certificate, EpochGeometry, LedgerGlobals, PoolParams, Tx}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Pool, PoolCert, Sts}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.Codec
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellStateCodecs, Params}

import HaskellStateCodecs.given

/** Actual generated Conway POOL judgments from cardano-ledger's pinned conformance property. */
class UpstreamPoolConformanceSuite extends FunSuite:
  private val base = Path.of("node/src/test/resources/upstream/conway-pool")
  private val seeds = Vector(492101L, 492102L, 492103L, 492104L, 492105L)
  private val protVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)

  private def bytes(path: Path): Bytes = Bytes.fromArray(Files.readAllBytes(path))

  private def node(path: Path) =
    Cbor.decode(bytes(path)).fold(e => fail(s"$path: $e"), identity)

  private def items(n: xyz.kd5ujc.scarab.models.runtime.Node, size: Int) =
    n.value match
      case Value.Arr(xs) if xs.size == size => xs
      case other                            => fail(s"expected $size-item array, got $other")

  private def state(path: Path): PState =
    given ProtVer = protVer
    summon[Codec[PState]].decode(node(path)).fold(e => fail(e.render), identity)

  private def sha256(path: Path): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(path))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private def poolCertificate(n: xyz.kd5ujc.scarab.models.runtime.Node)(using
      ProtVer
  ): PoolCert =
    val codec = summon[Codec[PoolParams]]
    n.value match
      case Value.Arr(Vector(tag, params)) if tag.value == Value.UInt(0) =>
        codec
          .decode(params)
          .fold(e => fail(s"POOL registration parameters: ${e.render}"), PoolCert.RegPool(_))
      case Value.Arr(Vector(tag, pool, epoch)) if tag.value == Value.UInt(1) =>
        val key = summon[Codec[KeyHash[StakePool]]]
          .decode(pool)
          .fold(e => fail(s"POOL retirement key: ${e.render}"), identity)
        val when = summon[Codec[EpochNo]]
          .decode(epoch)
          .fold(e => fail(s"POOL retirement epoch: ${e.render}"), identity)
        PoolCert.RetirePool(key, when.toBigInt)
      case other => fail(s"unknown upstream PoolCert wire constructor: $other")

  private val globals = Globals(
    LedgerGlobals(BigInt(0), BigInt(1), Rational.zero, network = 0),
    EpochGeometry(Instant.EPOCH, 1000L, 1L, 1, BigDecimal("0.1"))
  )

  private val manifestRows = parse(Files.readString(base.resolve("manifest.json")))
    .fold(error => fail(s"POOL manifest: $error"), identity)
    .hcursor
    .downField("cases")
    .as[Vector[Json]]
    .fold(error => fail(s"POOL manifest rows: $error"), identity)

  private def verifyManifestFile(seed: Long, name: String, path: Path): Unit =
    val row = manifestRows
      .find(_.hcursor.get[Long]("seed").toOption.contains(seed))
      .getOrElse(fail(s"seed $seed absent from POOL manifest"))
    val file = row.hcursor.downField("files").downField(name)
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

  test(
    "an empty-relay registration normalizes typed state and preserves original transaction bytes"
  ) {
    given ProtVer = protVer
    def hex(text: String): Bytes =
      Bytes.fromHex(text).fold(error => fail(error.toString), identity)

    // Pool registration tag 3, nine parameters: zero pledge/cost, margin 0/1, a network-0
    // reward account, no owners, canonical empty relay array and no metadata.
    val registrationHex =
      "8a03581c" + ("00" * 28) + "5820" + ("00" * 32) +
        "0000d81e820001581de0" + ("00" * 28) + "8080f6"
    val registration = hex(registrationHex)
    val original = Cbor.decode(registration).fold(error => fail(error.toString), identity)
    val params = Certificate.decode(original) match
      case Right(Certificate.RegisterPool(value)) => value
      case other                                  => fail(s"expected pool registration, got $other")
    assertEquals(params.relays, Bytes.empty)
    assertEquals(original.original, registration)

    val codec = summon[Codec[PoolParams]]
    val encoded = codec.encodeUnchecked(params)
    assertEquals(encoded, codec.encodeUnchecked(params.copy(relays = hex("80"))))
    assertEquals(codec.decodeBytes(encoded).fold(error => fail(error.render), identity), params)

    // Indefinite maps make accidental canonical re-encoding observable at this boundary.
    val body = hex("bf0080018002000481" + registrationHex + "ff")
    val witnesses = hex("bfff")
    val transaction = Tx
      .fromBlock(Block.Transaction(body, witnesses, None, true), Era.Conway)
      .fold(error => fail(error.toString), identity)
    assertEquals(transaction.body.certificates, Vector(Certificate.RegisterPool(params)))
    assertEquals(transaction.body.raw, body)
    assertEquals(transaction.witnesses.raw, Some(witnesses))
  }

  test("pinned POOL properties retain all generated verdicts and full trace hashes") {
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

  test("Scarab POOL adapter consumes the upstream env and tagged signal, matching full PState") {
    given ProtVer = protVer
    seeds.foreach { seed =>
      val dir = base.resolve(s"cases/$seed")
      val context = node(dir.resolve("context.cbor"))
      assertEquals(context.value, Value.Null, s"seed $seed upstream POOL context")
      val env = items(node(dir.resolve("environment.cbor")), 2)
      val epoch = summon[Codec[EpochNo]].decode(env(0)).fold(e => fail(e.render), identity)
      val pp = Params.conwayCodec.decode(env(1)).fold(e => fail(e.render), identity)
      val before = state(dir.resolve("state.cbor"))
      val expected = state(dir.resolve("implementation-state.cbor"))
      val actual = Sts
        .apply(Pool.Adapter)(
          globals,
          Pool.RuleEnv(
            Bytes.fromArray(
              s"cardano-ledger-POOL-$seed".getBytes(java.nio.charset.StandardCharsets.UTF_8)
            ),
            pp,
            epoch.toBigInt
          ),
          before,
          poolCertificate(node(dir.resolve("signal.cbor")))
        )
        .fold(e => fail(s"seed $seed Scarab refused upstream-accepted POOL: $e"), identity)
      assertEquals(actual, expected, s"seed $seed full PState")
    }
  }
