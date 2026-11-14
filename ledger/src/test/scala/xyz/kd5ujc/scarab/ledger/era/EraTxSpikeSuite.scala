// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Block, TxBody as FlatTxBody}
import xyz.kd5ujc.scarab.ledger.era.babbage.{BabbageTxBody, BabbageTxOut}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayTxBody, ConwayTxOut}
import xyz.kd5ujc.scarab.ledger.state.{Babbage, Conway}
import xyz.kd5ujc.scarab.protocol.Era

/** Spike of the era-specific transaction types (ADR 0007, amendment of 2026-11-14): per-era bodies
  * and opaque per-era outputs over a shared representation, the reference's classes with instances
  * at exactly their eras, a rule written once, and the compiler refusing what an era does not have.
  */
class EraTxSpikeSuite extends munit.FunSuite:
  // the devnet's era walk and recorded Preview blocks with Babbage and Conway transactions
  private val sources = Vector(
    "fixtures/eras/blocks.hex",
    "fixtures/preview/babbage-mint.hex",
    "fixtures/preview/conway.hex",
    "fixtures/preview/conway-governance.hex"
  )

  private lazy val blocks: Vector[Block.Shelley] =
    sources
      .flatMap(f => Files.readString(Path.of(f)).linesIterator)
      .map(_.trim.split("\\s+").last)
      .filter(_.nonEmpty)
      .flatMap(h => Bytes.fromHex(h).toOption)
      .flatMap(raw => Block.decodeShelley(raw).toOption)
      .collect { case b: Block.Shelley => b }
      .toVector

  private def bodiesOf(era: Era): Vector[Bytes] =
    blocks.filter(_.era == era).flatMap(_.transactions.map(_.body))

  test("Babbage and Conway bodies decode to their own types with the flat decoder's fields") {
    val babbage = bodiesOf(Era.Babbage)
    val conway = bodiesOf(Era.Conway)
    assert(babbage.nonEmpty && conway.nonEmpty, (babbage.size, conway.size))
    babbage.foreach { raw =>
      val flat = FlatTxBody.decode(raw, Era.Babbage).toOption.get
      val b = BabbageTxBody.decode(raw).toOption.get
      assertEquals(b.id, flat.id)
      assertEquals(b.fee, flat.fee)
      assertEquals(b.outputs.map(EraTxOut.babbage.raw), flat.outputs.map(_.raw))
      assertEquals(SharedRules.proposedUpdate[Babbage](b), flat.update)
    }
    conway.foreach { raw =>
      val flat = FlatTxBody.decode(raw, Era.Conway).toOption.get
      val c = ConwayTxBody.decode(raw).toOption.get
      assertEquals(c.id, flat.id)
      assertEquals(c.outputs.map(EraTxOut.conway.raw), flat.outputs.map(_.raw))
      assertEquals(EraTxBody.conway.currentTreasury(c), flat.currentTreasury)
    }
  }

  test("a rule written once runs in both eras through each era's rule set") {
    val b = bodiesOf(Era.Babbage).map(BabbageTxBody.decode(_).toOption.get)
    val c = bodiesOf(Era.Conway).map(ConwayTxBody.decode(_).toOption.get)
    def run[E <: xyz.kd5ujc.scarab.ledger.state.EraTag](bodies: Vector[TxBody[E]])(using
        r: EraRules[E]
    ): Int = bodies.map(r.inlineDatums(_).size).sum
    assert(run[Babbage](b) >= 0)
    assert(run[Conway](c) >= 0)
  }

  test("a Babbage output carried into Conway keeps its bytes") {
    val outs = bodiesOf(Era.Babbage).flatMap(BabbageTxBody.decode(_).toOption.get.outputs)
    assert(outs.nonEmpty)
    outs.foreach(o =>
      assertEquals(EraTxOut.conway.raw(ConwayTxOut.translate(o)), EraTxOut.babbage.raw(o))
    )
  }

  test("the compiler refuses a capability an era does not have, and another era's output") {
    // Conway bodies have no protocol-parameter update (no ShelleyEraTxBody[Conway])
    val noUpdate = compileErrors(
      "def f(c: ConwayTxBody) = SharedRules.proposedUpdate[Conway](c)"
    )
    assert(noUpdate.contains("ShelleyEraTxBody"), noUpdate)
    // a Conway output is not a Babbage output, though they share their representation
    val wrongEra = compileErrors("def f(c: ConwayTxOut): BabbageTxOut = c")
    assert(wrongEra.nonEmpty, wrongEra)
    // and only the era's package or decoder makes one
    val noConstructor = compileErrors(
      "def f(r: BabbageTxOutRep): ConwayTxOut = r"
    )
    assert(noConstructor.nonEmpty, noConstructor)
  }
