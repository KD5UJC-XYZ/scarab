// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import xyz.kd5ujc.scarab.codec.{Bytes, CollidingBytes, Node, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.storage.{Codec, NodeCodecs}

/** The maps a multi-asset value is decoded into are the ones the decoder built before it stopped
  * building a hash map of the entries it drops: `normal(policies.map(_ -> _.toMap).toMap)`, kept
  * here as the oracle. Each case compares the two values, the order each level of their maps
  * iterates in, and the bytes the store writes for them (`NodeCodecs.value`), which follow that
  * order.
  *
  * Those bytes depend on the layout of Scala's `HashMap`, whose order the store writes a map in;
  * the decoder rebuilds that order from the keys' hash codes (`Value.multiasset`). This suite is
  * what notices a change of that layout, as a new Scala library could bring.
  */
class PruningMapsSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(2000)

  type Entries = Vector[(Bytes, Vector[(Bytes, BigInt)])]
  type Assets = Map[Bytes, Map[Bytes, BigInt]]

  /** `normal` as `Value` has it, over the maps built from every entry. */
  private def oracle(e: Entries): Assets =
    val assets = e.map((p, qs) => p -> qs.toMap).toMap
    if assets.forall((_, names) => names.nonEmpty && names.forall(_._2.signum != 0)) then assets
    else
      assets.iterator
        .map((policy, names) => policy -> names.filter(_._2.signum != 0))
        .filter(_._2.nonEmpty)
        .toMap

  private def leaf(v: CborValue): Node = Node(v, Bytes.empty)

  private def node(e: Entries): Node =
    leaf(CborValue.Map(e.map { (p, qs) =>
      leaf(CborValue.ByteString(p)) -> leaf(
        CborValue.Map(qs.map((n, q) => leaf(CborValue.ByteString(n)) -> leaf(CborValue.UInt(q))))
      )
    }))

  private def decoded(e: Entries, decoding: Value.Decoding): Either[LedgerDecodeError, Assets] =
    Value.multiasset(node(e), DecodeField.Mint, signed = false, decoding)(using
      ByteStrings.Definite
    )

  private def levels(a: Assets): Vector[(Bytes, Vector[(Bytes, BigInt)])] =
    a.iterator.map((p, m) => p -> m.iterator.toVector).toVector

  private def stored(a: Assets): Vector[Byte] =
    Codec.encode(Value(1, a))(using NodeCodecs.value).toVector

  private def classes(a: Assets): (String, Vector[String]) =
    a.getClass.getName -> a.valuesIterator.map(_.getClass.getName).toVector

  // a value too big to compact is refused, by the oracle's count; any other is the oracle's
  private def same(e: Entries, decoding: Value.Decoding = Value.Decoding.Pruning): Unit =
    val expected = oracle(e)
    val (assets, policies) = (expected.valuesIterator.map(_.size).sum, expected.size)
    if 44L * assets + 28L * policies > 65535 then
      assertEquals(
        decoded(e, decoding),
        Left(LedgerDecodeError.MultiAssetTooBig(DecodeField.Mint, assets, policies))
      )
    else decoded(e, decoding).fold(err => fail(s"refused: $err for $e"), same(e, expected, _))

  private def same(e: Entries, expected: Assets, obtained: Assets): Unit =
    assertEquals(obtained, expected)
    assertEquals(levels(obtained), levels(expected), s"the maps iterate otherwise for $e")
    assertEquals(classes(obtained), classes(expected), s"the maps are of other classes for $e")
    assertEquals(stored(obtained), stored(expected), s"the stored bytes differ for $e")

  // names and policies from pools of strings of one hash code and of random ones
  private val prefix = Bytes(Vector.fill(21)(9.toByte))
  private val names = CollidingBytes.of(12, 7) ++ CollidingBytes.random(12, 7, 7)
  private val policies =
    (CollidingBytes.of(10, 7) ++ CollidingBytes.random(10, 7, 11)).map(prefix ++ _)
  private val quantity: Gen[BigInt] =
    Gen.frequency(2 -> Gen.const(BigInt(0)), 3 -> Gen.choose(1, 3).map(BigInt(_)))
  // up to 12 names and 12 policies, so maps of more and of fewer than four entries
  private val inner: Gen[Vector[(Bytes, BigInt)]] =
    Gen.choose(0, 12).flatMap(Gen.listOfN(_, Gen.zip(Gen.oneOf(names), quantity))).map(_.toVector)
  private val entries: Gen[Entries] =
    Gen.choose(0, 12).flatMap(Gen.listOfN(_, Gen.zip(Gen.oneOf(policies), inner))).map(_.toVector)

  property("below version 9: zeros, repeats and empty maps, of keys of one hash code or not") {
    Prop.forAll(entries)(e => same(e))
  }

  property("from version 9: policies and names given once, none of quantity zero") {
    def keyed(p: Bytes): Gen[(Bytes, Vector[(Bytes, BigInt)])] =
      Gen
        .choose(1, 12)
        .flatMap(Gen.pick(_, names))
        .flatMap(ns =>
          Gen.listOfN(ns.size, Gen.choose(1, 3)).map(qs => p -> ns.toVector.zip(qs.map(BigInt(_))))
        )
    val valid: Gen[Entries] = Gen
      .choose(0, 12)
      .flatMap(Gen.pick(_, policies))
      .flatMap(ps =>
        Gen.sequence[Vector[(Bytes, Vector[(Bytes, BigInt)])], (Bytes, Vector[(Bytes, BigInt)])](
          ps.map(keyed)
        )
      )
    Prop.forAll(valid)(e => same(e, Value.Decoding.Rejecting))
  }

  // keys of two hash codes shared by several, beside random ones, and more of each than above
  private val wider: Gen[Entries] =
    val prefixB = Bytes(Vector.fill(21)(3.toByte))
    val ps =
      (CollidingBytes.of(8, 7).map(prefix ++ _) ++ CollidingBytes.of(8, 7).map(prefixB ++ _) ++
        CollidingBytes.random(60, 28, 13))
    val ns =
      CollidingBytes.of(10, 7) ++ CollidingBytes.of(10, 6) ++ CollidingBytes.random(60, 7, 17)
    val in =
      Gen.choose(0, 40).flatMap(Gen.listOfN(_, Gen.zip(Gen.oneOf(ns), quantity))).map(_.toVector)
    Gen.choose(0, 60).flatMap(Gen.listOfN(_, Gen.zip(Gen.oneOf(ps), in))).map(_.toVector)

  property("more policies and names, two groups of one hash code each") {
    Prop.forAll(wider)(e => same(e))
  }

  test("five names, four of quantity zero: a hash map of one") {
    val policy = CollidingBytes.random(5, 28, 3).head
    val names = CollidingBytes.random(5, 7, 5)
    same(Vector(policy -> names.zipWithIndex.map((n, i) => n -> BigInt(if i == 2 then 1 else 0))))
  }

  test("policies of two hash codes shared by three each, one of each kept") {
    val a = CollidingBytes.of(3, 7).map(prefix ++ _)
    val b = CollidingBytes.of(3, 7).map(Bytes(Vector.fill(21)(3.toByte)) ++ _)
    val name = CollidingBytes.random(1, 7, 5).head
    same(
      (a ++ b).zipWithIndex.map((p, i) =>
        p -> (if i == 1 || i == 4 then Vector(name -> BigInt(1)) else Vector(name -> BigInt(0)))
      )
    )
  }

  test("five names, the first of quantity zero: a filtered hash map, in its order") {
    val policy = CollidingBytes.random(5, 28, 3).head
    val names = CollidingBytes.random(5, 7, 5)
    same(Vector(policy -> names.zipWithIndex.map((n, i) => n -> BigInt(if i == 0 then 0 else 1))))
  }

  test("five policies, three left empty: the two kept in the order of the map of five") {
    val policies = CollidingBytes.random(5, 28, 3)
    val name = CollidingBytes.random(5, 7, 5).head
    same(
      policies.zipWithIndex.map((p, i) =>
        p -> (if i == 1 || i == 3 then Vector(name -> BigInt(1)) else Vector.empty)
      )
    )
  }

  test("names of one hash code dropped beside names kept") {
    val hex = (s: String) => Bytes.fromHex(s).fold(e => fail(e.message), identity)
    same(
      Vector(
        hex("090909090909090909090909090909090909090909abfb5a3ce95a21") -> Vector(
          hex("fc787878787de1") -> BigInt(0),
          hex("fc78787878787c") -> BigInt(0),
          hex("fc78787878795d") -> BigInt(0),
          hex("fc787878787c00") -> BigInt(1),
          hex("bba3bf02597de5") -> BigInt(1)
        )
      )
    )
  }
