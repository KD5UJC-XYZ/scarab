// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.parser
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value as C}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.Block
import munit.FunSuite

import TestLedger.*

/** Preprod tx 0c22edee (block 3,158,876, slot 83,038,792, epoch 196, Conway) spends two Byron
  * outputs whose address root equals the hash of the vkey of its one vkey witness. The ledger puts
  * a Byron input's root into the set of needed key hashes (`bootstrapKeyHash`) and compares it with
  * the hashes of the vkey witnesses and the roots of the bootstrap witnesses together, so the vkey
  * witness satisfies it. The first preprod sync of the Scala node stopped here, demanding a
  * bootstrap witness (`MissingBootstrapWitnessesUTXOW`).
  */
class PreprodByronKeyWitnessSuite extends FunSuite:
  private val ada = BigInt(1000000)
  private val dir = Path.of("fixtures/preprod")

  private def hex(s: String): Bytes = Bytes.fromHex(s).toOption.get

  private val tx: Tx =
    val root = Cbor.decode(hex(Files.readString(dir.resolve("tx-0c22edee.hex")).trim)).toOption.get
    root.value match
      case C.Arr(xs) =>
        val block = Block.Transaction(xs(0).original, xs(1).original, None, true)
        Tx.fromBlock(block, Era.Babbage).fold(e => fail(e), identity)
      case other => fail(s"not a transaction: $other")

  private val inputs: Map[TxIn, TxOut] =
    val json = parser.parse(Files.readString(dir.resolve("tx-0c22edee-inputs.json"))).toOption.get
    json.hcursor
      .downField("inputs")
      .focus
      .get
      .asArray
      .get
      .map { e =>
        val c = e.hcursor
        val addr = hex(c.get[String]("address").toOption.get)
        val lovelace = c.get[String]("lovelace").toOption.get.toLong
        // legacy output `[address, coin]`
        val raw = Bytes(
          Vector(0x82, 0x58, addr.size).map(_.toByte) ++ addr.value ++
            Vector(0x1a, lovelace >> 24, lovelace >> 16, lovelace >> 8, lovelace).map(_.toByte)
        )
        TxIn(hex(c.get[String]("txHash").toOption.get), BigInt(c.get[Int]("index").toOption.get)) ->
          TxOut.fromBytes(raw).fold(e => fail(e), identity)
      }
      .toMap

  private val world: LedgerState =
    val params = TestLedger.params(10).copy(minFeeA = 44, minFeeB = 155381, keyDeposit = 2 * ada)
    val base = state(10, inputs.toVector).copy(params = params, epoch = 196)
    base.copy(reserves = Supply - (base.total - base.reserves))

  private val conwayEnv = env.copy(slot = 83038792, epoch = 196)

  private def rule(t: Tx): String = TxRules(world, t, conwayEnv) match
    case Left(LedgerFailure.Rejected(_, f)) => f.ruleName
    case Left(other)                        => other.message
    case Right(_)                           => "accepted"

  test("the fixture is the recorded transaction: two Byron inputs, one vkey witness") {
    assertEquals(
      tx.body.id.hex,
      "0c22edee0ffd7c8f32d2fe4da1f144e9ef78dfb51e1678d5198493a83d6cf8ec"
    )
    assertEquals(tx.body.inputs.size, 2)
    assertEquals(tx.witnesses.vkeys.size, 1)
    assert(tx.witnesses.bootstrap.isEmpty)
    val roots = inputs.values.map(_.address.kind).toSet
    assertEquals(roots.size, 1)
    roots.head match
      case Address.Kind.Byron(root) =>
        assertEquals(root.hex, "533bcb8dad5fc8049f5d43974bdc21d637f5b73d6120237303ca6843")
        assertEquals(
          xyz.kd5ujc.scarab.crypto.Blake2b.hash224.hash(tx.witnesses.vkeys.head._1),
          root
        )
      case other => fail(s"not Byron: $other")
  }

  test("a vkey witness whose key hashes to the Byron address root spends the Byron output") {
    assertEquals(rule(tx), "accepted")
  }

  test("no witness for the Byron root is refused, as a missing key hash witness") {
    assertEquals(rule(tx.copy(witnesses = Witnesses.empty)), "MissingVKeyWitnessesUTXOW")
  }

  test("a valid witness of another key does not spend the Byron output") {
    val other = key()
    val t = tx.copy(witnesses = Witnesses(Vector(other.witness(tx.body.id))))
    assertEquals(rule(t), "MissingVKeyWitnessesUTXOW")
  }
