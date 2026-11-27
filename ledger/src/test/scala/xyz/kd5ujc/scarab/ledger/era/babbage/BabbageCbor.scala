// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.babbage

import org.scalacheck.Gen
import xyz.kd5ujc.scarab.ledger.ParamUpdate

/** Babbage's parameter updates as CBOR written by hand, for the suites that read them: each
  * unsigned integer in its shortest form or in eight bytes, so a value may be written in a form
  * other than the canonical one.
  */
object BabbageCbor:
  def head(major: Int, n: BigInt, wide: Boolean): String =
    val m = major << 5
    def hexOf(width: Int) = n.toString(16).reverse.padTo(width * 2, '0').reverse
    if wide then f"${m | 27}%02x" + hexOf(8)
    else if n < 24 then f"${m | n.toInt}%02x"
    else if n < 0x100 then f"${m | 24}%02x" + hexOf(1)
    else if n < 0x10000 then f"${m | 25}%02x" + hexOf(2)
    else if n < (BigInt(1) << 32) then f"${m | 26}%02x" + hexOf(4)
    else f"${m | 27}%02x" + hexOf(8)

  /** An unsigned integer, in its shortest form or, if `wide`, in eight bytes. */
  def uint(n: BigInt, wide: Boolean = false): String = head(0, n, wide)
  def array(items: Seq[String]): String = head(4, items.size, false) + items.mkString
  def map(kvs: Seq[(String, String)]): String =
    head(5, kvs.size, false) + kvs.map(_ + _).mkString
  def ratio(n: BigInt, d: BigInt, wide: Boolean): String =
    "d81e" + array(Seq(uint(n, wide), uint(d, wide)))

  val genWide: Gen[Boolean] = Gen.oneOf(false, true)
  def genWord(max: BigInt): Gen[BigInt] =
    Gen
      .oneOf(Gen.const(BigInt(0)), Gen.choose(1L, 70000L).map(BigInt(_)), Gen.const(max))
      .map(_.min(max))
  val word64 = (BigInt(1) << 64) - 1
  val word32 = (BigInt(1) << 32) - 1
  val word16 = BigInt(0xffff)

  /** A value of each of Babbage's update keys, with the field's width, in a canonical or a wide
    * form.
    */
  def genValue(key: Int): Gen[String] =
    def word(max: BigInt) = for n <- genWord(max); w <- genWide yield uint(n, w)
    def interval(unit: Boolean) =
      for
        d <- Gen.choose(1L, 1000L)
        n <- Gen.choose(0L, if unit then d else 5000L)
        w <- genWide
      yield ratio(n, d, w)
    key match
      case 0 | 1 | 5 | 6 | 16 | 17 => word(word64)
      case 2 | 3 | 7 | 22          => word(word32)
      case 4 | 8 | 23 | 24         => word(word16)
      case 9                       => interval(unit = false)
      case 10 | 11                 => interval(unit = true)
      case 14 =>
        for major <- Gen.choose(0, 9); minor <- Gen.choose(0, 3); w <- genWide
        yield array(Seq(uint(major, w), uint(minor, w)))
      case 18 =>
        for
          lang <- Gen.oneOf(0, 1)
          w <- genWide
        yield map(
          Seq(uint(lang) -> array(Seq.tabulate(if lang == 0 then 166 else 175)(i => uint(i, w))))
        )
      case 19 => for a <- interval(false); b <- interval(false) yield array(Seq(a, b))
      case _ =>
        for m <- Gen.choose(0L, 1000000L); s <- Gen.choose(0L, 1000000L); w <- genWide
        yield array(Seq(uint(m, w), uint(s, w)))

  val babbageKeys = ParamUpdate.Keys.Babbage.keys.toVector.sorted

  /** A parameter update's map, its keys in a random order. */
  def genFields(keys: Vector[Int]): Gen[Vector[(Int, String)]] =
    for
      chosen <- Gen.someOf(keys)
      ordered <- Gen.pick(chosen.size, chosen).map(_.toVector)
      values <- Gen.sequence[Vector[String], String](ordered.map(genValue))
    yield ordered.zip(values)

  def genesisKey(i: Int): String = "581c" + f"$i%02x" * 28

  /** A body's key 6: one to three delegates' updates and an epoch. */
  val genUpdate: Gen[(String, Vector[Vector[(Int, String)]])] =
    for
      n <- Gen.choose(1, 3)
      updates <- Gen.listOfN(n, genFields(babbageKeys)).map(_.toVector)
      epoch <- Gen.choose(0L, 1000L)
    yield (
      array(
        Seq(
          map(
            updates.zipWithIndex.map((u, i) => genesisKey(i) -> map(u.map((k, v) => uint(k) -> v)))
          ),
          uint(epoch)
        )
      ),
      updates
    )
