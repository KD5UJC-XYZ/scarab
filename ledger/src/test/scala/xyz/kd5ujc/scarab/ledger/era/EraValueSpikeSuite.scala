// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Block, TxBody as FlatTxBody}
import xyz.kd5ujc.scarab.ledger.era.allegra.AllegraTxOut
import xyz.kd5ujc.scarab.ledger.era.mary.MaryTxOut
import xyz.kd5ujc.scarab.ledger.state.{Allegra, Mary}
import xyz.kd5ujc.scarab.protocol.Era

/** Spike of the representation parameterised by value (ADR 0007, amendment of 2026-11-14):
  * Allegra's and Mary's outputs over one `ShelleyTxOutRep[V]`, a rule adding values up through
  * `Val` in every era, the store's partial read into an era, and the refusals the compiler makes.
  */
class EraValueSpikeSuite extends munit.FunSuite:
  private lazy val blocks: Vector[Block.Shelley] =
    Files
      .readString(Path.of("fixtures/eras/blocks.hex"))
      .linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(h => Bytes.fromHex(h).toOption)
      .flatMap(raw => Block.decodeShelley(raw).toOption)
      .collect { case b: Block.Shelley => b }
      .toVector

  private def outputsOf(era: Era): Vector[xyz.kd5ujc.scarab.ledger.TxOut] =
    blocks
      .filter(_.era == era)
      .flatMap(_.transactions.map(_.body))
      .flatMap(FlatTxBody.decode(_, era).toOption.get.outputs)

  test("Allegra and Mary outputs read from the store, with the flat outputs' lovelace and bytes") {
    val allegra = outputsOf(Era.Allegra)
    val mary = outputsOf(Era.Mary)
    assert(allegra.nonEmpty && mary.nonEmpty, (allegra.size, mary.size))
    val a = allegra.map(AllegraTxOut.fromStored(_).toOption.get)
    val m = mary.map(MaryTxOut.fromStored(_).toOption.get)
    assertEquals(SharedRules.coinOf[Allegra](a), allegra.map(_.value.coin).sum)
    assertEquals(SharedRules.coinOf[Mary](m), mary.map(_.value.coin).sum)
    assertEquals(a.map(EraTxOut.allegra.raw), allegra.map(_.raw))
  }

  test("an Allegra output carried into Mary keeps its bytes and lovelace") {
    val a = outputsOf(Era.Allegra).map(AllegraTxOut.fromStored(_).toOption.get)
    val m = a.map(MaryTxOut.translate)
    assertEquals(m.map(EraTxOut.mary.raw), a.map(EraTxOut.allegra.raw))
    assertEquals(SharedRules.coinOf[Mary](m), SharedRules.coinOf[Allegra](a))
  }

  test("the store's read into Allegra refuses what Allegra's outputs cannot hold") {
    val out = outputsOf(Era.Allegra).head
    val withAssets = out.copy(value =
      out.value.copy(assets =
        Map(Bytes(Vector.fill(28)(1.toByte)) -> Map(Bytes(Vector(2)) -> BigInt(1)))
      )
    )
    assertEquals(
      AllegraTxOut.fromStored(withAssets),
      Left(EraTxError.NotInEra(EraField.MultiAsset))
    )
    // Mary's outputs hold assets
    assert(MaryTxOut.fromStored(withAssets).isRight)
  }

  test("the compiler refuses an Allegra output as a Mary one, and an incomplete rule set") {
    val wrongEra = compileErrors("def f(a: AllegraTxOut): MaryTxOut = a")
    assert(wrongEra.contains("MaryTxOut"), wrongEra)
    val incomplete = compileErrors(
      "val r = new EraRules[xyz.kd5ujc.scarab.ledger.state.Babbage] {}"
    )
    assert(incomplete.contains("inlineDatums"), incomplete)
  }
