// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.Instant
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Rational, Value}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.rules.{CertEnv, Certs, CertsEnv, Globals, Sts}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.Codec
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellStateCodecs, Params, Wire}
import HaskellStateCodecs.given
import xyz.kd5ujc.scarab.storage.haskell.state.NodeOrders.given

/** One actual generated Conway CERTS trace decoded at Scarab's full typed CERTS boundary. */
class UpstreamCertsCoverageSuite extends FunSuite:
  private val base = Path.of("node/src/test/resources/upstream/conway-certs")
  private val seed = 494101L
  private val protVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)

  private def bytes(path: Path): Bytes = Bytes.fromArray(Files.readAllBytes(path))

  private def node(path: Path) =
    Cbor.decode(bytes(path)).fold(e => fail(s"$path: $e"), identity)

  private def state(path: Path): ConwayCertState =
    given ProtVer = protVer
    summon[Codec[ConwayCertState]].decode(node(path)).fold(e => fail(e.render), identity)

  private def sha256(path: Path): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(path))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private val manifest = parse(Files.readString(base.resolve("manifest.json")))
    .fold(error => fail(s"CERTS manifest: $error"), identity)
  private val row = manifest.hcursor
    .downField("cases")
    .as[Vector[Json]]
    .fold(error => fail(s"CERTS rows: $error"), identity)
    .find(_.hcursor.get[Long]("seed").toOption.contains(seed))
    .getOrElse(fail(s"missing exact CERTS seed $seed"))

  private val globals = Globals(
    LedgerGlobals(BigInt(0), BigInt(1), Rational.zero, network = 0),
    EpochGeometry(Instant.EPOCH, 1000L, 1L, 1, BigDecimal("0.1"))
  )

  test("the accepted generated CERTS row and exact source files are retained") {
    assertEquals(
      row.hcursor.get[String]("upstreamStatus").toOption,
      Some("implementation-accepted")
    )
    assertEquals(row.hcursor.get[String]("agdaStatus").toOption, Some("agda-accepted"))
    assertEquals(row.hcursor.get[String]("adapterStatus").toOption, Some("matched"))
    val dir = base.resolve(s"cases/$seed")
    Vector(
      "context.cbor",
      "environment.cbor",
      "state.cbor",
      "signal.cbor",
      "implementation-state.cbor",
      "result.txt"
    ).foreach { name =>
      val path = dir.resolve(name)
      val file = row.hcursor.downField("files").downField(name)
      assertEquals(file.get[String]("sha256").toOption, Some(sha256(path)), s"$name SHA-256")
      assertEquals(file.get[Int]("bytes").toOption, Some(Files.size(path).toInt), s"$name size")
    }
    assertEquals(
      Files.readString(dir.resolve("result.txt")),
      "implementation-accepted\nagda-accepted\n"
    )
  }

  test("the complete generated CERTS judgment matches the full Haskell state") {
    given ProtVer = protVer
    val dir = base.resolve(s"cases/$seed")
    val env = node(dir.resolve("environment.cbor")).value match
      case Value.Arr(xs) if xs.size == 5 => xs
      case other => fail(s"expected all five actual CERTS environment fields, got $other")

    // Actual upstream CertsEnv: [Tx, PParams, current epoch, current committee,
    // committee proposals]. Decode the full Tx, including proposal procedures, from its original
    // serialized parts; do not reconstruct omitted fields from the smaller Agda context.
    val txItems = env(0).value match
      case Value.Arr(xs) if xs.size == 4 => xs
      case other => fail(s"expected the full serialized Conway Tx, got $other")
    val isValid = txItems(2).value match
      case Value.Bool(value) => value
      case other             => fail(s"expected actual Tx validity boolean, got $other")
    val auxiliary = txItems(3).value match
      case Value.Null => None
      case _          => Some(txItems(3).original)
    val tx = Tx
      .fromBlock(
        Block.Transaction(txItems(0).original, txItems(1).original, auxiliary, isValid),
        Era.Conway
      )
      .fold(e => fail(s"decode complete generated Conway Tx: $e"), identity)
    val pp = Params.conwayCodec.decode(env(1)).fold(e => fail(e.render), identity)
    val epoch = summon[Codec[EpochNo]].decode(env(2)).fold(e => fail(e.render), identity)
    val committee = Wire
      .strictMaybe(summon[Codec[Committee]])
      .decode(env(3))
      .fold(e => fail(e.render), identity)
    val committeeProposals = Wire
      .mapOf(summon[Codec[GovActionId]], summon[Codec[GovActionState]])
      .decode(env(4))
      .fold(e => fail(s"full committee proposal map: ${e.render}"), identity)

    // Verify the related Agda context is exactly the projection of withdrawals and votes from
    // the actual transaction whose untouched original encoding is retained above.
    val context = node(dir.resolve("context.cbor")).value match
      case Value.Arr(Vector(withdrawals, votes)) => (withdrawals, votes)
      case other => fail(s"expected translated CERTS context [withdrawals,votes], got $other")
    assertEquals(context._1.value, Value.Map(Vector.empty))
    assertEquals(context._2.value, Value.Map(Vector.empty))
    assertEquals(tx.body.withdrawals, Vector.empty)
    assertEquals(tx.body.votes, Vector.empty)
    assert(tx.body.raw == txItems(0).original)

    val before = state(dir.resolve("state.cbor"))
    val expected = state(dir.resolve("implementation-state.cbor"))
    val signalNode = node(dir.resolve("signal.cbor"))
    val signal = signalNode.value match
      case Value.Arr(xs) =>
        xs.traverse(Certificate.decode).fold(e => fail(s"full CERTS signal: $e"), identity)
      case other => fail(s"expected CERTS certificate sequence, got $other")
    assertEquals(signal, Vector.empty)

    val actual = Sts
      .apply(Certs)(
        globals,
        CertsEnv(
          tx,
          CertEnv(
            tx.body.id,
            pp,
            epoch.toBigInt,
            committee,
            committeeProposals
          )
        ),
        before,
        signal
      )
      .fold(e => fail(s"Scarab CERTS rejected upstream-accepted trace: $e"), identity)
    assertEquals(actual, expected, "full ConwayCertState")
  }
