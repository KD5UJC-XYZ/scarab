// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Block, TxBody as FlatTxBody}
import xyz.kd5ujc.scarab.ledger.era.allegra.AllegraTxOut
import xyz.kd5ujc.scarab.ledger.era.mary.MaryTxOut
import xyz.kd5ujc.scarab.ledger.state.{Allegra, Coin, Mary}
import xyz.kd5ujc.scarab.protocol.Era

/** The representation parameterised by value (ADR 0007, amendment of 2026-11-14): Allegra's and
  * Mary's outputs over one `ShelleyTxOutRep[V]`, a function adding values up through `Val` in every
  * era, the store's partial read into an era, and the refusals the compiler makes.
  */
class EraValueSuite extends munit.FunSuite:
  // The lovelace of a set of outputs, whatever the era's value is (`coin . sumVal`): written once
  // through the outputs' class and its value's `Val`.
  private def coinOf[E <: xyz.kd5ujc.scarab.ledger.state.EraTag](outs: Vector[TxOut[E]])(using
      o: EraTxOut[E]
  ): xyz.kd5ujc.scarab.ledger.state.Coin =
    o.valueOps.coin(outs.map(o.value).foldLeft(o.valueOps.zero)(o.valueOps.plus))

  private lazy val blocks: Vector[Block.Shelley] =
    EraCorpus.shelleyBlocks(Vector("fixtures/eras/blocks.hex"))

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
    assertEquals(coinOf[Allegra](a).value, allegra.map(_.value.coin).sum)
    assertEquals(coinOf[Mary](m).value, mary.map(_.value.coin).sum)
    assertEquals(a.map(EraTxOut.allegra.raw), allegra.map(_.raw))
  }

  test("an Allegra output carried into Mary keeps its bytes and lovelace") {
    val a = outputsOf(Era.Allegra).map(AllegraTxOut.fromStored(_).toOption.get)
    val m = a.map(MaryTxOut.translate)
    assertEquals(m.map(EraTxOut.mary.raw), a.map(EraTxOut.allegra.raw))
    assertEquals(coinOf[Mary](m).value, coinOf[Allegra](a).value)
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

  test("the compiler refuses an Allegra output as a Mary one; Mary's own compiles") {
    val wrongEra = compileErrors("def f(a: AllegraTxOut): MaryTxOut = a")
    assert(wrongEra.contains("MaryTxOut"), wrongEra)
    assertEquals(compileErrors("def f(m: MaryTxOut): MaryTxOut = m"), "")
  }

  test("Val is the reference's signed group: subtraction, scaling and pointwise comparison") {
    val ada = summon[Val[AdaValue]]
    val (five, seven) = (AdaValue(5), AdaValue(7))
    // a negative amount is a value in a calculation (`Coin` is an `Integer`)
    assertEquals(ada.minus(five, seven).lovelace, BigInt(-2))
    assertEquals(ada.scale(-1, seven).lovelace, BigInt(-7))
    assert(ada.isZero(ada.minus(seven, seven)))
    assert(ada.pointwise(_ <= _, five, seven))
    assertEquals(ada.modifyCoin(c => Coin(c.value * 2), five).lovelace, BigInt(10))
    val ma = summon[Val[MultiAssetValue]]
    val policy = Bytes(Vector.fill(28)(1.toByte))
    val (a, b) = (Bytes(Vector(0x61)), Bytes(Vector(0x62)))
    val v =
      MultiAssetValue.of(xyz.kd5ujc.scarab.ledger.Value(10, Map(policy -> Map(a -> 3, b -> 4))))
    val w = MultiAssetValue.of(xyz.kd5ujc.scarab.ledger.Value(4, Map(policy -> Map(a -> 3))))
    // a quantity that reaches zero leaves the map, as `canonicalMap` drops it
    assertEquals(ma.minus(v, w).assets, Map(policy -> Map(b -> BigInt(4))))
    assertEquals(ma.minus(v, w).lovelace, BigInt(6))
    assert(ma.isZero(ma.scale(0, v)))
    assertEquals(ma.scale(-2, w).assets, Map(policy -> Map(a -> BigInt(-6))))
    // a quantity one side lacks is zero
    assert(ma.pointwise(_ >= _, v, w))
    assert(!ma.pointwise(_ <= _, v, w))
    assert(ma.pointwise(_ >= _, v, ma.zero))
  }

  test("the store's read into Allegra still refuses a negative coin, which no output holds") {
    val out = outputsOf(Era.Allegra).head
    assertEquals(
      AllegraTxOut.fromStored(out.copy(value = out.value.copy(coin = -1))),
      Left(EraTxError.NotInEra(EraField.NegativeValue))
    )
  }
