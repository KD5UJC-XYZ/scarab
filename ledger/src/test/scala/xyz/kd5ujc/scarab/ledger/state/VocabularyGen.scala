// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.Show
import cats.syntax.all.*
import org.scalacheck.{Arbitrary, Cogen, Gen}
import xyz.kd5ujc.scarab.codec.Bytes

/** Generators of the vocabulary. Hashes differ in few places and use bytes on both sides of 0x80,
  * so that the unsigned comparison and the ties between constructors are exercised often.
  */
object VocabularyGen:
  private val edge: Gen[Byte] = Gen.oneOf(0, 1, 0x7f, 0x80, 0xff).map(_.toByte)

  def bytes(size: Int): Gen[Bytes] =
    for
      head <- edge
      mid <- edge
      last <- edge
    yield Bytes.fromArray(Array.tabulate(size) {
      case 0                  => head
      case i if i == size / 2 => mid
      case i if i == size - 1 => last
      case _                  => 0.toByte
    })

  private def valid[A](e: Either[VocabError, A]): A = e.fold(err => sys.error(err.show), identity)

  given [R <: KeyRole]: Arbitrary[KeyHash[R]] = Arbitrary(
    bytes(28).map(b => valid(KeyHash.fromBytes(b)))
  )
  given Arbitrary[ScriptHash] = Arbitrary(bytes(28).map(b => valid(ScriptHash.fromBytes(b))))
  given Arbitrary[VRFVerKeyHash] = Arbitrary(bytes(32).map(b => valid(VRFVerKeyHash.fromBytes(b))))
  given Arbitrary[Hash32] = Arbitrary(bytes(32).map(b => valid(Hash32.fromBytes(b))))

  given [R <: KeyRole]: Arbitrary[Credential[R]] = Arbitrary(
    Gen.oneOf(
      Arbitrary.arbitrary[ScriptHash].map(Credential.ScriptHashObj[R](_)),
      Arbitrary.arbitrary[KeyHash[R]].map(Credential.KeyHashObj[R](_))
    )
  )

  given Arbitrary[DRep] = Arbitrary(
    Gen.oneOf(
      Arbitrary.arbitrary[KeyHash[DRepRole]].map(DRep.DRepKeyHash(_)),
      Arbitrary.arbitrary[ScriptHash].map(DRep.DRepScriptHash(_)),
      Gen.const(DRep.DRepAlwaysAbstain),
      Gen.const(DRep.DRepAlwaysNoConfidence)
    )
  )

  given Arbitrary[Coin] = Arbitrary(
    Gen.oneOf(0L, 1L, 2L, Long.MaxValue).map(n => Coin(BigInt(n)))
  )
  given Arbitrary[DeltaCoin] = Arbitrary(
    Gen.oneOf(-1L, 0L, 1L, Long.MinValue).map(n => DeltaCoin(BigInt(n)))
  )

  /** Words around the edges of both halves: values from 2^63 up are negative `Long` s, and must
    * sort above the small ones.
    */
  val word64Bits: Gen[Long] = Gen.frequency(
    4 -> Gen.oneOf(
      0L,
      1L,
      2L,
      Long.MaxValue - 1,
      Long.MaxValue,
      Long.MinValue,
      Long.MinValue + 1,
      -2L,
      -1L
    ),
    3 -> Gen.choose(0L, 1000L),
    3 -> Arbitrary.arbitrary[Long]
  )

  given Arbitrary[Word64] = Arbitrary(word64Bits.map(Word64.fromUnsigned))
  given Arbitrary[CompactCoin] = Arbitrary(word64Bits.map(CompactCoin.fromUnsigned))
  given Arbitrary[EpochNo] = Arbitrary(word64Bits.map(EpochNo.fromUnsigned))
  given Arbitrary[SlotNo] = Arbitrary(word64Bits.map(SlotNo.fromUnsigned))
  given Arbitrary[BlockNo] = Arbitrary(word64Bits.map(BlockNo.fromUnsigned))
  given nonZeroInt: Arbitrary[NonZero[Int]] = Arbitrary(
    Gen.oneOf(-2, -1, 1, 2).map(n => valid(NonZero.from(n)))
  )
  given nonZeroWord: Arbitrary[NonZero[Word64]] = Arbitrary(
    word64Bits.suchThat(_ != 0L).map(b => valid(NonZero.from(Word64.fromUnsigned(b))))
  )

  given [A: Arbitrary]: Arbitrary[WithOrigin[A]] = Arbitrary(
    Gen.oneOf(Gen.const(WithOrigin.Origin), Arbitrary.arbitrary[A].map(WithOrigin.At(_)))
  )

  /** A cogen through the rendering: enough to separate values for the law functions. */
  def cogenShow[A: Show]: Cogen[A] = Cogen[String].contramap(_.show)
