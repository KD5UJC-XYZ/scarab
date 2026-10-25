// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import java.nio.file.{Files, Path}
import io.circe.{Decoder, Json}
import io.circe.parser.parse
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.rules.{
  CertEnv,
  JudgedSts,
  Pool,
  PoolCert,
  PreConway,
  ShelleyUtxo,
  StAnnTx,
  Test,
  UtxoEnv
}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.Codec
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.HaskellStateCodecs

import HaskellStateCodecs.given

/** Exact released native POOL outcomes and pre-Alonzo UTXO predicate bytes for #141. */
class RemainingPredicateReferenceSuite extends FunSuite:
  private given ProtVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)
  private val dir = Path.of("fixtures/reference/remaining-predicates")
  private def rows(name: String): Vector[Json] =
    parse(Files.readString(dir.resolve(name)))
      .flatMap(_.as[Vector[Json]])
      .fold(e => fail(e.toString), identity)
  private def field[A: Decoder](row: Json, name: String): A =
    row.hcursor.get[A](name).fold(e => fail(e.toString), identity)
  private def hex(value: String): Bytes = Bytes.fromHex(value).fold(e => fail(e.toString), identity)
  private def params(value: String): PoolParams =
    summon[Codec[PoolParams]].decodeBytes(hex(value)).fold(e => fail(e.render), identity)
  private def poolState(value: String): PState =
    summon[Codec[PState]].decodeBytes(hex(value)).fold(e => fail(e.render), identity)
  private val id = TestLedger.txIn(90).id

  test("native capture provenance binds the committed inputs, outputs and oracle sources") {
    val provenance = parse(Files.readString(dir.resolve("provenance.json")))
      .fold(e => fail(e.toString), identity)
    val files = field[Map[String, String]](provenance, "files")
    files.foreach { (name, expected) =>
      val actual = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(Files.readAllBytes(Path.of(name)))
        .map(b => f"${b & 0xff}%02x")
        .mkString
      assertEquals(actual, expected, name)
    }
    assertEquals(field[Boolean](provenance, "ruleSourcePatched"), false)
    assertEquals(
      field[String](provenance, "ledgerSourceCommit"),
      "f649f9751074d2ab3de033fc3912f29c9862c1f5"
    )
  }

  test(
    "native POOL reference agrees on all version gates, boundaries, re-registrations and first failures"
  ) {
    val fixtures = rows("pool.json")
    assertEquals(fixtures.size, 43)
    fixtures.foreach { row =>
      val name = field[String](row, "name")
      val pp = TestLedger
        .params(field[Int](row, "major"))
        .copy(
          protocolMinor = field[BigInt](row, "minor"),
          minPoolCost = field[BigInt](row, "minCost"),
          poolDeposit = 0
        )
      def apply(state: PState, p: PoolParams) = JudgedSts(Pool)(LedgerFailure.Rejected(id, _))(
        TestLedger.globals,
        CertEnv(id, pp, 5, None, Map.empty),
        state,
        PoolCert.RegPool(p)
      )
      val empty = Empty.ledgerState[Conway](pp).cert.pState
      val before = field[Option[String]](row, "previous").fold(empty)(old =>
        apply(empty, params(old)).fold(e => fail(s"$name prior registration: $e"), identity)
      )
      val result = field[Json](row, "result")
      apply(before, params(field[String](row, "params"))) match
        case Right(state) =>
          assert(field[Boolean](result, "accepted"), name)
          assertEquals(state, poolState(field[String](result, "stateCbor")), name)
        case Left(
              LedgerFailure.Rejected.Single(
                _,
                PredicateFailure.Certs(CertsFailure.Cert(CertFailure.Pool(f)))
              )
            ) =>
          assert(!field[Boolean](result, "accepted"), name)
          val actual = CborOut.build(w => PredicateFailureCbor.pool(w, f))
          // applySTS prepends failures, so the last recorded failure is the first
          // predicate in source order. Scarab deliberately returns that first failure.
          val expected = field[Vector[String]](result, "failureCbor").lastOption
            .getOrElse(fail(s"$name native failures"))
          assertEquals(actual, hex(expected), name)
          // The shared POOL encoding must reach clients in every Shelley-based era.
          val failure = PredicateFailure.pool(f)
          for era <- Vector(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway)
          do
            val reason = PredicateFailureCbor
              .applyTxError(era, Vector(failure))
              .getOrElse(fail(s"$name $era encoding"))
            assert(reason.hex.endsWith(actual.hex), s"$name $era")
        case other => fail(s"$name unexpected result: $other")
    }
  }

  test("native early-era bootstrap predicates and exact NtC failure bytes match") {
    val fixtures = rows("outputs.json")
    assertEquals(fixtures.size, 36)
    fixtures.foreach { row =>
      val major = field[Int](row, "protocolMajor")
      val era = major match
        case 2     => Era.Shelley
        case 3     => Era.Allegra
        case 4     => Era.Mary
        case other => fail(s"unsupported fixture era $other")
      val body = TxBody
        .decode(hex(field[String](row, "body")), era)
        .fold(e => fail(e.toString), identity)
      def validate[E <: EraTag](network: Int)(using ShelleyBasedEra[E], PreConway[E]) =
        val pp = TestLedger.params(major)
        val cert = summon[PreConway[E]].cert.get(Empty.ledgerState[E](pp))
        val spent =
          TestLedger.out(body.outputs.head.address, body.outputs.map(_.value.coin).sum + body.fee)
        val utxo = Utxo.fromMap(body.inputs.map(_ -> spent).toMap)
        ShelleyUtxo.validate[E](
          UtxoEnv(TestLedger.env.copy(network = network), pp, cert),
          StAnnTx(Tx(body, Witnesses.empty, 100, true, None), utxo),
          0,
          0
        )
      def check(network: Int) = major match
        case 2     => validate[Shelley](network)
        case 3     => validate[Allegra](network)
        case 4     => validate[Mary](network)
        case other => fail(s"unsupported major $other")
      val outputNetwork = xyz.kd5ujc.scarab.ledger.byron.ByronAddress
        .decode(body.outputs.head.address.raw)
        .fold(e => fail(e.toString), _.network)
      // Isolate attributes by agreeing on network, then run combined production validation.
      // Shelley and Allegra/Mary execute network before attributes. Preserve that source
      // order when both independently captured native predicates fail.
      val checks = Vector(
        "attributes" -> check(outputNetwork),
        "ordered" -> check(field[Int](row, "network"))
      )
      for (name, checked) <- checks do
        val addresses = field[Json](row, "addresses")
        val expected =
          if name == "ordered" && !field[Boolean](addresses, "accepted") then addresses
          else field[Json](row, "attributes")
        // the first failure UTXO records is the first in the source order
        Test.failures(checked).map(_.head) match
          case None => assert(field[Boolean](expected, "accepted"), s"$major $name")
          case Some(failure) =>
            assert(!field[Boolean](expected, "accepted"), s"$major $name")
            val native = field[Vector[String]](expected, "failureCbor").headOption
              .getOrElse(fail("native output predicate failure"))
            val reason = PredicateFailureCbor
              .applyTxError(era, Vector(failure))
              .getOrElse(fail(s"$major output encoding"))
            // Unwrap hard-fork era, failure list, LEDGER and UTXOW. Preserve the native
            // UTXO bytes, including its original bootstrap output representation.
            def array(bytes: Bytes): Vector[xyz.kd5ujc.scarab.codec.Node] =
              Cbor
                .decode(bytes)
                .fold(
                  e => fail(e.toString),
                  _.value match
                    case CborValue.Arr(xs) => xs
                    case other             => fail(s"array expected: $other")
                )
            val hfc = array(reason).headOption.getOrElse(fail("HFC error"))
            val list = array(hfc.original)(1)
            val ledger = array(list.original).headOption.getOrElse(fail("LEDGER error"))
            val utxow = array(ledger.original)(1)
            val utxo = array(utxow.original)(1)
            assertEquals(utxo.original, hex(native), s"$major $name")
    }
  }
