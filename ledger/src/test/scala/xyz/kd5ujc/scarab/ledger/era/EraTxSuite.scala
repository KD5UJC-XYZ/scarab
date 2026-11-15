// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Block, TxBody as FlatTxBody}
import xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTxOut
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayTxBody, ConwayTxOut}
import xyz.kd5ujc.scarab.ledger.state.Conway
import xyz.kd5ujc.scarab.protocol.Era

/** The era-specific transaction types (ADR 0007, amendment of 2026-11-14): per-era bodies and
  * opaque per-era outputs over a shared representation, the reference's classes with instances at
  * exactly their eras, a function written once over the classes, and the compiler refusing what an
  * era does not have.
  */
class EraTxSuite extends munit.FunSuite:
  // the devnet's era walk and recorded Preview blocks with Babbage and Conway transactions
  private val sources = Vector(
    "fixtures/eras/blocks.hex",
    "fixtures/preview/babbage-mint.hex",
    "fixtures/preview/conway.hex",
    "fixtures/preview/conway-governance.hex"
  )

  private lazy val blocks: Vector[Block.Shelley] =
    EraCorpus.shelleyBlocks(sources)

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

  // The inline datums of a body's outputs, at any level the era has: written once for every era
  // whose bodies have Babbage's fields, through the class and the outputs' class it names.
  private def inlineDatums[E <: xyz.kd5ujc.scarab.ledger.state.EraTag, L <: TxLevel](
      body: TxBody[L, E]
  )(using b: BabbageEraTxBody[E], l: b.Level[L]): Vector[Bytes] =
    b.outputs(body).flatMap(b.outputsOf.data)

  test("a function written once over the body class reads Conway's bodies") {
    // its sharing across eras' bodies is shown again when Dijkstra's body is typed (stage 3)
    val c = bodiesOf(Era.Conway).map(ConwayTxBody.decode(_).toOption.get)
    def run[E <: xyz.kd5ujc.scarab.ledger.state.EraTag](bodies: Vector[TxBody[TxLevel.Top, E]])(
        using b: BabbageEraTxBody[E]
    ): Int = bodies.map(inlineDatums[E, TxLevel.Top](_)(using b, b.top).size).sum
    val flat = bodiesOf(Era.Conway)
      .flatMap(FlatTxBody.decode(_, Era.Conway).toOption.get.outputs)
      .count(_.datum match
        case xyz.kd5ujc.scarab.ledger.Datum.Inline(_) => true
        case _                                        => false)
    assertEquals(run[Conway](c)(using EraTxBody.conway), flat)
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
