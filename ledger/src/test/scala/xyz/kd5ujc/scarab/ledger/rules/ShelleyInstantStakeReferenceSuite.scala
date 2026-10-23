// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import io.circe.{ACursor, Json}
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Address, Ptr, TestLedger, TxChecks, TxIn, TxOut, Utxo as NodeUtxo}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.state.given

/** The native `addShelleyInstantStake` and `deleteShelleyInstantStake` results of
  * `fixtures/reference/instant-stake`, replayed against the production pre-Conway instant stake.
  * The released functions ran in the sequence of `updateUTxOAndInstantStake` (the produced outputs
  * added, then the consumed outputs deleted) at cardano-ledger f649f975,
  * `Shelley/State/Stake.hs:108-145` and `Shelley/Rules/Utxo.hs:613-624`. Each case is replayed
  * twice: through `ShelleyInstantStakeOps.update` with both sides in `TxIn` map order, and through
  * `ShelleyUtxo.update` with the body listing its inputs in the signed order of the fixture, so
  * that the production deletion order is the measured one.
  */
class ShelleyInstantStakeReferenceSuite extends FunSuite:
  private val document: Json =
    parse(Files.readString(Path.of("fixtures/reference/instant-stake/results.json")))
      .fold(e => fail(e.message), identity)

  private def field(json: Json, name: String): ACursor = json.hcursor.downField(name)
  private def text(c: ACursor): String = c.as[String].fold(e => fail(e.message), identity)
  private def number(c: ACursor): BigInt = BigInt(text(c))
  private def integer(c: ACursor): BigInt = c.as[BigInt].fold(e => fail(e.message), identity)
  private def array(c: ACursor): Vector[Json] =
    c.as[Vector[Json]].fold(e => fail(e.message), identity)
  private def bytes(c: ACursor): Bytes =
    Bytes.fromHex(text(c)).fold(e => fail(e.toString), identity)

  private def coin(n: BigInt): CompactCoin =
    CompactCoin.fromBigInt(n).fold(e => fail(e.toString), identity)

  private def stake(json: Json): ShelleyInstantStake =
    ShelleyInstantStake(
      SortedMap.from(array(field(json, "credentials")).map { entry =>
        val hash = KeyHash.fromBytes[Staking](bytes(field(entry, "keyHash")))
        Credential.KeyHashObj(hash.fold(e => fail(e.toString), identity)) ->
          coin(number(field(entry, "coin")))
      })(using summon[cats.Order[Credential[Staking]]].toOrdering),
      SortedMap.from(array(field(json, "pointers")).map { entry =>
        Ptr(
          integer(field(entry, "slot")),
          integer(field(entry, "txIx")),
          integer(field(entry, "certIx"))
        ) -> coin(number(field(entry, "coin")))
      })(using summon[cats.Order[Ptr]].toOrdering)
    )

  /** An output with its `TxIn`, as listed in the fixture. */
  private def outputs(input: Json, side: String): Vector[(TxIn, TxOut)] =
    array(field(input, side)).map { entry =>
      val address = Address
        .decode(bytes(field(entry, "address")))
        .fold(e => fail(e.toString), identity)
      TxIn(bytes(field(entry, "txId")), integer(field(entry, "index"))) ->
        TestLedger.out(address, number(field(entry, "coin")))
    }

  private def sorted(rows: Vector[(TxIn, TxOut)]): Vector[TxOut] = rows.sortBy(_._1).map(_._2)

  private val rows: Vector[Json] = array(field(document, "cases"))

  test("the fixture holds the sixteen cases for each map under both eras, all in agreement") {
    assertEquals(rows.size, 32)
    rows.foreach(row => assertEquals(field(row, "eraAgree").as[Boolean], Right(true)))
  }

  rows.foreach { row =>
    val input = row.hcursor.downField("input").focus.getOrElse(fail("input"))
    val name = text(field(input, "name"))
    val held = stake(input)
    val expected = stake(row.hcursor.downField("shelley").focus.getOrElse(fail("shelley")))
    val produced = outputs(input, "produce")
    val consumed = outputs(input, "consume")

    test(s"native $name: updater in TxIn map order") {
      assertEquals(
        ShelleyInstantStakeOps.update(held, sorted(produced), sorted(consumed)),
        Right(expected)
      )
    }

    test(s"native $name: production UTXO with inputs in signed order") {
      val utxo = NodeUtxo.fromMap(consumed.toMap)
      val state = Empty
        .ledgerState[Babbage](TestLedger.params(10))
        .utxo
        .copy[Babbage](utxo = UtxoTables(utxo), instantStake = held)
      val body = TestLedger.body(s"reference-$name", consumed.map(_._1), sorted(produced), 0)
      val next = ShelleyUtxo
        .update[Babbage](
          state,
          StAnnTx(TestLedger.tx(body, Vector.empty), utxo),
          TxChecks.Collateral.none,
          0,
          0
        )
        .fold(e => fail(e.toString), identity)
      assertEquals(next.instantStake, expected)
    }
  }

  test("native toCompact bounds agree with the checked CompactCoin conversion") {
    val checks = array(field(document, "coinChecks"))
    assertEquals(checks.size, 6)
    checks.foreach { check =>
      val n = number(field(check, "coin"))
      val native = field(check, "compact").focus.getOrElse(fail("compact")).asString.map(BigInt(_))
      assertEquals(CompactCoin.fromBigInt(n).toOption.map(_.toBigInt), native)
    }
  }
