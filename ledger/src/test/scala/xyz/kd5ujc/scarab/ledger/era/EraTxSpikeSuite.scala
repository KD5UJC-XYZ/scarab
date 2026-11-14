// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Block, TxBody as FlatTxBody}
import xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTxOut
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayTxBody, ConwayTxOut}
import xyz.kd5ujc.scarab.ledger.state.Conway
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

  private def babbageOutputs: Vector[BabbageTxOut] =
    bodiesOf(Era.Babbage)
      .flatMap(FlatTxBody.decode(_, Era.Babbage).toOption.get.outputs)
      .map(BabbageTxOut.fromStored(_).toOption.get)

  test("Conway bodies decode to their own type with the flat decoder's fields") {
    val conway = bodiesOf(Era.Conway)
    assert(conway.nonEmpty)
    conway.foreach { raw =>
      val flat = FlatTxBody.decode(raw, Era.Conway).toOption.get
      val c = ConwayTxBody.decode(raw).toOption.get
      assertEquals(c.id, flat.id)
      assertEquals(c.outputs.map(o => EraTxOut.conway.raw(o.value)), flat.outputs.map(_.raw))
      assertEquals(
        EraTxBody.conway
          .currentTreasuryValue[TxLevel.Top](c)(using EraTxBody.conway.top)
          .map(_.value),
        flat.currentTreasury
      )
    }
  }

  test("a rule written once runs through the era's rule set") {
    // the rule's sharing across eras' bodies is shown again when Dijkstra's body is typed (stage 3)
    val c = bodiesOf(Era.Conway).map(ConwayTxBody.decode(_).toOption.get)
    def run[E <: xyz.kd5ujc.scarab.ledger.state.EraTag](bodies: Vector[TxBody[TxLevel.Top, E]])(
        using r: EraRules[E]
    ): Int = bodies.map(r.inlineDatums(_).size).sum
    val flat = bodiesOf(Era.Conway)
      .flatMap(FlatTxBody.decode(_, Era.Conway).toOption.get.outputs)
      .count(_.datum match
        case xyz.kd5ujc.scarab.ledger.Datum.Inline(_) => true
        case _                                        => false)
    assertEquals(run[Conway](c), flat)
  }

  test("a Babbage output carried into Conway keeps its bytes") {
    val outs = babbageOutputs
    assert(outs.nonEmpty)
    outs.foreach(o =>
      assertEquals(EraTxOut.conway.raw(ConwayTxOut.translate(o)), EraTxOut.babbage.raw(o))
    )
  }

  test("a body of a level an era does not have is refused: Conway has top-level bodies only") {
    val c = bodiesOf(Era.Conway).map(ConwayTxBody.decode(_).toOption.get).head
    // a method of any level runs on a top-level body with the era's evidence of the top level
    assertEquals(EraTxBody.conway.inputs[TxLevel.Top](c)(using EraTxBody.conway.top), c.spendInputs)
    // Conway's only evidence is of the top level, so a sub-transaction body cannot be read
    val noSub = compileErrors(
      "def f(b: TxBody[TxLevel.Sub, Conway]) = EraTxBody.conway.inputs[TxLevel.Sub](b)(using EraTxBody.conway.top)"
    )
    assert(noSub.contains("TxLevel.Sub"), noSub)
  }

  test("the compiler refuses another era's output; the era's own compiles") {
    // a Conway output is not a Babbage output, though they share their representation
    val wrongEra = compileErrors("def f(c: ConwayTxOut): BabbageTxOut = c")
    assert(wrongEra.contains("BabbageTxOut"), wrongEra)
    assertEquals(compileErrors("def f(c: ConwayTxOut): ConwayTxOut = c"), "")
  }
