// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Block, TxBody as FlatTxBody}
import xyz.kd5ujc.scarab.ledger.era.allegra.AllegraTxOut
import xyz.kd5ujc.scarab.ledger.era.mary.MaryTxOut
import xyz.kd5ujc.scarab.ledger.state.{Allegra, Coin, CompactCoin, Mary}
import xyz.kd5ujc.scarab.protocol.Era

/** The representation parameterised by value (ADR 0007, amendment of 2026-11-14): Allegra's and
  * Mary's outputs over one `ShelleyTxOutRep[V]`, a function adding values up through `Val` in every
  * era, the store's partial read into an era, and the refusals the compiler makes; and `Val` 's
  * size and compact-form members over each era value's compact form.
  */
class EraValueSuite extends ScalaCheckSuite:
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

  test("Val is the reference's signed group: subtraction and scaling") {
    val ada = summon[Val[AdaValue]]
    val (five, seven) = (AdaValue(5), AdaValue(7))
    // a negative amount is a value in a calculation (`Coin` is an `Integer`)
    assertEquals(ada.minus(five, seven).lovelace, BigInt(-2))
    assertEquals(ada.scale(-1, seven).lovelace, BigInt(-7))
    assert(ada.isZero(ada.minus(seven, seven)))
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
  }

  test("eqv is MaryValue's pointwise (==): a zero quantity or an empty policy is absent") {
    import xyz.kd5ujc.scarab.ledger.Value as Flat
    val ma = summon[Val[MultiAssetValue]]
    val (p, q) = (Bytes(Vector.fill(28)(1.toByte)), Bytes(Vector.fill(28)(2.toByte)))
    val (a, b) = (Bytes(Vector(0x61)), Bytes(Vector(0x62)))
    val zeros = MultiAssetValue.of(Flat(5, Map(p -> Map(a -> 0), q -> Map.empty)))
    // `of` is canonical, as `MultiAsset` is
    assertEquals(zeros.assets, Map.empty)
    assert(ma.eqv(zeros, ma.inject(Coin(5))))
    val three = MultiAssetValue.of(Flat(4, Map(p -> Map(a -> 3))))
    val two = MultiAssetValue.of(Flat(4, Map(p -> Map(a -> 2))))
    val other = MultiAssetValue.of(Flat(4, Map(p -> Map(b -> 3))))
    // assets alone tell these apart: a quantity, a name present on one side only, in either order
    assert(!ma.eqv(three, two))
    assert(!ma.eqv(three, other) && !ma.eqv(other, three))
    assert(!ma.eqv(three, ma.inject(Coin(4))) && !ma.eqv(ma.inject(Coin(4)), three))
    assert(!ma.eqv(three, MultiAssetValue.of(Flat(5, Map(p -> Map(a -> 3))))))
    // what the arithmetic brings to zero is absent, so the sum is the coin alone
    assert(ma.eqv(ma.plus(three, ma.scale(-1, three)), ma.zero))
    assert(ma.eqv(ma.minus(ma.plus(three, two), two), three))
    val ada = summon[Val[AdaValue]]
    assert(ada.eqv(AdaValue(7), ada.plus(AdaValue(3), AdaValue(4))))
    assert(!ada.eqv(AdaValue(7), AdaValue(-7)))
  }

  test("the store's read into Allegra still refuses a negative coin, which no output holds") {
    val out = outputsOf(Era.Allegra).head
    assertEquals(
      AllegraTxOut.fromStored(out.copy(value = out.value.copy(coin = -1))),
      Left(EraTxError.NotInEra(EraField.NegativeValue))
    )
  }

  // ---- size and the compact forms (`Val.hs:71-88`, `Mary/Value.hs:214-282,402-785`,
  // cardano-ledger f649f975)

  private val ada = summon[Val[AdaValue]]
  private val ma = summon[Val[MultiAssetValue]]
  private val MaxWord64 = (BigInt(1) << 64) - 1

  private def flat(coin: BigInt, assets: Map[Bytes, Map[Bytes, BigInt]]): MultiAssetValue =
    MultiAssetValue.of(xyz.kd5ujc.scarab.ledger.Value(coin, assets))

  private def ascii(s: String): Bytes = Bytes(s.getBytes("US-ASCII").toVector)

  // The worked example of the reference's comment on the packed representation
  // (`Mary/Value.hs:480-490`, cardano-ledger f649f975): two policies, the names "Snark" and "Boojum" under one, and the
  // empty name under the other.
  private val (pidA, pidF) =
    (Bytes(Vector.fill(28)(0xa5.toByte)), Bytes(Vector.fill(28)(0xf8.toByte)))
  private val example = flat(
    7,
    Map(pidA -> Map(Bytes.empty -> 42), pidF -> Map(ascii("Snark") -> 1, ascii("Boojum") -> 1))
  )

  private val genWord64: Gen[BigInt] =
    Gen.frequency(
      8 -> Gen.choose(1L, Long.MaxValue).map(BigInt(_)),
      1 -> Gen.const(MaxWord64),
      1 -> Gen.choose(0L, Long.MaxValue).map(n => MaxWord64 - n)
    )

  private val genName: Gen[Bytes] =
    Gen.oneOf(
      Gen.const(Bytes.empty),
      Gen
        .choose(1, 32)
        .flatMap(n => Gen.listOfN(n, Gen.choose(0, 255)).map(b => Bytes(b.map(_.toByte).toVector))),
      Gen.oneOf(Vector("a", "ab", "Snark", "Boojum").map(ascii))
    )

  // policies from a small pool, so that names repeat across them
  private val genPolicy: Gen[Bytes] =
    Gen.choose(0, 5).map(i => Bytes(Vector.fill(28)(i.toByte)))

  // canonical values small enough to compact (`isMultiAssetSmallEnough`), quantities in a Word64
  private val genCompactible: Gen[MultiAssetValue] =
    for
      coin <- Gen.frequency(1 -> Gen.const(BigInt(0)), 4 -> genWord64)
      policies <- Gen.choose(0, 4)
      assets <- Gen.listOfN(
        policies,
        for
          p <- genPolicy
          names <- Gen.choose(1, 6)
          held <- Gen.listOfN(names, Gen.zip(genName, genWord64))
        yield p -> held.toMap
      )
    yield flat(coin, assets.toMap)

  test("size: one word for a Coin, two for MaryValue's lovelace alone") {
    assertEquals(ada.size(AdaValue(5)), BigInt(1))
    assertEquals(ada.size(AdaValue(-5)), BigInt(1))
    assertEquals(ma.size(ma.inject(Coin(5))), BigInt(2))
    assertEquals(ma.size(ma.zero), BigInt(2))
  }

  test("size of the reference's worked example: 103 bytes of representation, 13 words and 6 more") {
    // 12 bytes for each of 3 triples, 28 for each of 2 policies, and "Snark" and "Boojum"
    assertEquals(ma.size(example), BigInt(6 + (3 * 12 + 2 * 28 + 5 + 6 + 7) / 8))
    assertEquals(ma.size(example), BigInt(19))
  }

  test("the compact form of the worked example is the layout `to` writes") {
    def le(v: Long, width: Int): Vector[Byte] = Vector.tabulate(width)(i => (v >>> (8 * i)).toByte)
    // `to` orders the triples by name offset (the names descending), and the policies ascending
    // in region D, so the policy offsets are 64, 64, 36 and the name offsets 92, 97, 103 (the
    // comment's figure draws the policies the other way)
    val expected = Bytes(
      Vector(1L, 1L, 42L).flatMap(le(_, 8)) ++
        Vector(64L, 64L, 36L).flatMap(le(_, 2)) ++
        Vector(92L, 97L, 103L).flatMap(le(_, 2)) ++
        pidA.value ++ pidF.value ++ ascii("SnarkBoojum").value
    )
    ma.toCompact(example) match
      case Some(CompactValue.MultiAsset(coin, n, rep)) =>
        assertEquals(coin.toBigInt, BigInt(7))
        assertEquals(n, 3)
        assertEquals(rep, expected)
      case other => fail(s"not a multi-asset compact form: $other")
  }

  property("size of a multi-asset value counts the words of its compact form, and 6 more") {
    forAll(genCompactible) { v =>
      ma.toCompact(v) match
        case Some(CompactValue.MultiAsset(_, _, rep)) =>
          ma.size(v) == BigInt(6 + (rep.size + 7) / 8)
        case Some(CompactValue.AdaOnly(_)) => ma.isAdaOnly(v) && ma.size(v) == BigInt(2)
        case None                          => false
    }
  }

  property("a value small enough comes back from its compact form, with its lovelace and kind") {
    forAll(genCompactible) { v =>
      ma.toCompact(v) match
        case None => false
        case Some(c) =>
          ma.eqv(ma.fromCompact(c), v) &&
          ma.coinCompact(c).toCoin == ma.coin(v) &&
          ma.isAdaOnlyCompact(c) == ma.isAdaOnly(v)
    }
  }

  property("modifyCompactCoin is modifyCoin on the compact form, and keeps the assets") {
    forAll(genCompactible, genWord64) { (v, w) =>
      val f: CompactCoin => CompactCoin = _ => CompactCoin.fromUnsigned(w.toLong)
      ma.toCompact(v) match
        case None => false
        case Some(c) =>
          val m = ma.modifyCompactCoin(f, c)
          ma.coinCompact(m).toBigInt == w &&
          ma.isAdaOnlyCompact(m) == ma.isAdaOnlyCompact(c) &&
          ma.eqv(ma.fromCompact(m), ma.modifyCoin(_ => Coin(w), v))
    }
  }

  property("injectCompact is inject on the compact form, in both eras' values") {
    forAll(Gen.frequency(1 -> Gen.const(BigInt(0)), 4 -> genWord64)) { n =>
      val c = CompactCoin.fromUnsigned(n.toLong)
      ma.isAdaOnlyCompact(ma.injectCompact(c)) && ma.coinCompact(ma.injectCompact(c)) == c &&
      ma.eqv(ma.fromCompact(ma.injectCompact(c)), ma.inject(Coin(n))) &&
      ma.toCompact(ma.inject(Coin(n))).contains(ma.injectCompact(c)) &&
      ada.isAdaOnlyCompact(ada.injectCompact(c)) && ada.coinCompact(ada.injectCompact(c)) == c &&
      ada.toCompact(AdaValue(n)).map(ada.fromCompact).contains(AdaValue(n)) &&
      ada.modifyCompactCoin(_.add(c), c).toBigInt == (2 * n) % (MaxWord64 + 1)
    }
  }

  test("no compact form outside a Word64: a negative or too large lovelace or quantity") {
    assertEquals(ada.toCompact(AdaValue(-1)), None)
    assertEquals(ada.toCompact(AdaValue(MaxWord64 + 1)), None)
    assert(ada.toCompact(AdaValue(MaxWord64)).isDefined)
    assertEquals(ma.toCompact(ma.inject(Coin(-1))), None)
    assertEquals(ma.toCompact(flat(-1, Map(pidA -> Map(Bytes.empty -> 1)))), None)
    assertEquals(ma.toCompact(flat(1, Map(pidA -> Map(Bytes.empty -> -1)))), None)
    assertEquals(ma.toCompact(flat(1, Map(pidA -> Map(Bytes.empty -> (MaxWord64 + 1))))), None)
    assert(ma.toCompact(flat(1, Map(pidA -> Map(Bytes.empty -> MaxWord64)))).isDefined)
  }

  test("a multi-asset compact form is built only from a value, so it always reads back") {
    // the constructor is private to `CompactValue`, so its object has no public `apply`
    val made = compileErrors("CompactValue.MultiAsset(CompactCoin.zero, 1, Bytes.empty)")
    assert(made.contains("does not take parameters"), made)
    val built = compileErrors("new CompactValue.MultiAsset(CompactCoin.zero, 1, Bytes.empty)")
    assert(built.contains("cannot be accessed"), built)
    assertEquals(compileErrors("CompactValue.AdaOnly(CompactCoin.zero)"), "")
  }

  test(
    "a value whose policy id is not 28 bytes has no compact form, as the reference has no such value"
  ) {
    import xyz.kd5ujc.scarab.ledger.Value as Flat
    val name = Bytes(Vector(0x61))
    Vector(27, 29).foreach { n =>
      val v = MultiAssetValue.of(Flat(5, Map(Bytes(Vector.fill(n)(1.toByte)) -> Map(name -> 1))))
      assertEquals(CompactValue.to(v), None, s"a policy of $n bytes")
    }
    val ok = MultiAssetValue.of(Flat(5, Map(Bytes(Vector.fill(28)(1.toByte)) -> Map(name -> 1))))
    assert(CompactValue.to(ok).isDefined)
  }

  test("assets too many to compact have no compact form (`isMultiAssetSmallEnough`)") {
    // 44 * 1489 + 28 = 65544 is over 65535; 1488 assets of one policy are not
    def assets(n: Int): Map[Bytes, Map[Bytes, BigInt]] =
      Map(pidA -> (0 until n).map(i => Bytes(Vector((i >> 8).toByte, i.toByte)) -> BigInt(1)).toMap)
    assert(ma.toCompact(flat(1, assets(1488))).isDefined)
    assertEquals(ma.toCompact(flat(1, assets(1489))), None)
  }
