// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import io.circe.Json
import io.circe.parser.parse
import munit.Assertions.fail
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era

/** The phase-2-invalid Babbage transactions of the two mainnet blocks the node withheld for good
  * (`fixtures/mainnet/babbage-phase2-invalid-txs.jsonl`, #1521), each with the outputs its
  * collateral inputs spent and the fee the chain charged it.
  */
object MainnetCollateral:
  final case class Case(name: String, tx: Tx, utxo: Utxo, fee: BigInt)

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private def field(j: Json, key: String): Json =
    j.hcursor.downField(key).focus.getOrElse(fail(s"no $key in $j"))

  private def text(j: Json, key: String): String =
    field(j, key).asString.getOrElse(fail(s"$key is no string"))

  private def output(j: Json): (TxIn, TxOut) =
    val address = GenesisState.address(text(j, "address")).fold(e => fail(e.toString), _.decoded)
    val index = field(j, "index").asNumber.flatMap(_.toBigInt).getOrElse(fail("no index"))
    TxIn(hex(text(j, "txIn")), index) -> TxOut(address, Value.coin(BigInt(text(j, "coin"))))

  lazy val cases: Vector[Case] =
    Files
      .readAllLines(Path.of("fixtures", "mainnet", "babbage-phase2-invalid-txs.jsonl"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map { line =>
        val j = parse(line).fold(e => fail(e.toString), identity)
        val raw = Block.Transaction(
          hex(text(j, "body")),
          hex(text(j, "witnesses")),
          field(j, "auxiliaryData").asString.map(hex),
          field(j, "isValid").asBoolean.getOrElse(fail("no isValid"))
        )
        val tx = Tx.fromBlock(raw, Era.Babbage).fold(e => fail(e.toString), identity)
        val spent = field(j, "collateral").asArray.getOrElse(fail("no collateral")).map(output)
        val name = s"${text(j, "txId").take(16)} (slot ${field(j, "slot")})"
        assert(raw.id == hex(text(j, "txId")), s"$name: the body is not the transaction's")
        Case(name, tx, Utxo.empty.copy(resident = spent.toMap), BigInt(text(j, "fee")))
      }
