// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.node.Block
import munit.FunSuite
import scala.jdk.CollectionConverters.*

/** The script data hash of a transaction without redeemers, on the real Preview chain.
  *
  * `fixtures/preview/empty-redeemers.hex` holds six blocks found by scanning the whole Preview
  * chain for transactions that carry datums, no redeemers and no Plutus language (supplemental
  * datums for outputs with a datum hash): two Babbage blocks (one with the redeemers key absent,
  * one with an explicit empty array `0x80`) and four Conway blocks (protocol 9, 10, 11 and 12). The
  * ledger hashes `originalBytes` of the redeemers, and an absent field is the encoding of an empty
  * `Redeemers` in the era's lowest protocol version: an empty list (`0x80`) before Conway, an empty
  * map (`0xa0`) from Conway (`Cardano.Ledger.Alonzo.TxWits`, `RedeemersRaw`;
  * `Cardano.Ledger.Alonzo.Tx`, `originalBytes (ScriptIntegrity ...)`). The hash is
  * `blake2b-256(redeemers ++ datums ++ 0xa0)`.
  *
  * No Alonzo transaction of this shape exists on Preview; the Alonzo case follows the same code as
  * Babbage and is covered by [[ScriptIntegritySuite]] only.
  */
class PreviewEmptyRedeemersSuite extends FunSuite:
  private val blocks: Vector[Block.Shelley] =
    Files
      .readAllLines(Path.of("fixtures/preview/empty-redeemers.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e), identity))
      .map(Block.decodeShelley(_).fold(e => fail(e.message), identity))
      .collect { case b: Block.Shelley => b }

  /** The ledger's protocol major: the header's, except that a Babbage block never runs above 8. */
  private def ledgerMajor(b: Block.Shelley): Int =
    val v = b.header.protocolVersion._1.toInt
    if b.era.toString == "Babbage" then math.min(v, 8) else v

  private val cases: Vector[(Block.Shelley, Tx)] =
    for
      b <- blocks
      t <- b.transactions
      tx = Tx.fromBlock(t, b.era).fold(e => fail(e), identity)
      if tx.witnesses.redeemers.isEmpty && tx.body.scriptDataHash.isDefined
    yield b -> tx

  test("every fixture block holds transactions with datums, no redeemers and a hash") {
    assertEquals(blocks.size, 6)
    blocks.foreach { b =>
      assert(cases.exists(_._1 eq b), s"slot ${b.header.slot}")
    }
    cases.foreach { case (_, tx) =>
      assert(tx.witnesses.datums.nonEmpty)
      assert(!tx.witnesses.hasPlutus)
    }
  }

  test("the recomputed hash equals the chain's: 0x80 before Conway, 0xa0 from Conway") {
    cases.foreach { case (b, tx) =>
      val major = ledgerMajor(b)
      assertEquals(
        ScriptIntegrity.hash(tx, TestLedger.params(major), Set.empty),
        tx.body.scriptDataHash,
        s"slot ${b.header.slot} protocol $major tx ${tx.body.id.hex}"
      )
    }
  }

  test("for an absent redeemers field the other encoding does not match the chain") {
    cases.filter(_._2.witnesses.redeemersRaw.isEmpty).foreach { case (b, tx) =>
      val major = ledgerMajor(b)
      val other = if major >= 9 then 8 else 9
      assertNotEquals(
        ScriptIntegrity.hash(tx, TestLedger.params(other), Set.empty),
        tx.body.scriptDataHash,
        s"slot ${b.header.slot}"
      )
    }
  }

  test("an explicit empty array in a Babbage witness set is hashed as written") {
    val explicit = cases.filter { case (_, tx) =>
      tx.witnesses.redeemersRaw.exists(_.hex == "80")
    }
    assert(explicit.nonEmpty)
    explicit.foreach { case (b, tx) =>
      assertEquals(b.era.toString, "Babbage")
      assertEquals(
        ScriptIntegrity.hash(tx, TestLedger.params(ledgerMajor(b)), Set.empty),
        tx.body.scriptDataHash
      )
    }
  }

  test("the fixtures cover Babbage (protocol 7) and Conway at protocol 9, 10, 11 and 12") {
    assertEquals(
      blocks.map(b => b.era.toString -> b.header.protocolVersion._1.toInt).toSet,
      Set("Babbage" -> 7, "Conway" -> 9, "Conway" -> 10, "Conway" -> 11, "Conway" -> 12)
    )
  }
