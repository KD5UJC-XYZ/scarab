// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.Order
import cats.kernel.laws.discipline.OrderTests
import cats.syntax.all.*
import munit.DisciplineSuite
import org.scalacheck.Cogen
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.state.Credential.{KeyHashObj, ScriptHashObj}
import xyz.kd5ujc.scarab.ledger.state.DRep.*
import xyz.kd5ujc.scarab.ledger.state.VocabularyGen.{*, given}

/** The vocabulary orders match the Haskell `Ord` instances. [[Credential]], [[DRep]] and
  * [[WithOrigin]] document the upstream constructors and source locations that determine them.
  */
class VocabularySuite extends DisciplineSuite:
  given Cogen[KeyHash[StakePool]] = cogenShow
  given Cogen[ScriptHash] = cogenShow
  given Cogen[VRFVerKeyHash] = cogenShow
  given Cogen[Hash32] = cogenShow
  given Cogen[Credential[Staking]] = cogenShow
  given Cogen[DRep] = cogenShow
  given Cogen[Coin] = cogenShow
  given Cogen[DeltaCoin] = cogenShow
  given Cogen[CompactCoin] = cogenShow
  given Cogen[EpochNo] = cogenShow
  given Cogen[SlotNo] = cogenShow
  given Cogen[BlockNo] = cogenShow
  given nonZeroIntCogen: Cogen[NonZero[Int]] = cogenShow
  given Cogen[Word64] = cogenShow
  given nonZeroWordCogen: Cogen[NonZero[Word64]] = cogenShow
  given Cogen[WithOrigin[SlotNo]] = cogenShow

  checkAll("KeyHash", OrderTests[KeyHash[StakePool]].order)
  checkAll("ScriptHash", OrderTests[ScriptHash].order)
  checkAll("VRFVerKeyHash", OrderTests[VRFVerKeyHash].order)
  checkAll("Hash32", OrderTests[Hash32].order)
  checkAll("Credential", OrderTests[Credential[Staking]].order)
  checkAll("DRep", OrderTests[DRep].order)
  checkAll("Coin", OrderTests[Coin].order)
  checkAll("DeltaCoin", OrderTests[DeltaCoin].order)
  checkAll("CompactCoin", OrderTests[CompactCoin].order)
  checkAll("EpochNo", OrderTests[EpochNo].order)
  checkAll("SlotNo", OrderTests[SlotNo].order)
  checkAll("BlockNo", OrderTests[BlockNo].order)
  checkAll("Word64", OrderTests[Word64].order)
  checkAll("NonZero", OrderTests[NonZero[Int]].order)
  checkAll("NonZero Word64", OrderTests[NonZero[Word64]].order)
  checkAll("WithOrigin", OrderTests[WithOrigin[SlotNo]].order)

  private def lt[A: Order](a: A, b: A): Boolean = Order[A].lt(a, b)

  private def hash28(b: Int): Bytes = Bytes.fromArray(Array.fill(28)(b.toByte))
  private def key[R <: KeyRole](b: Int): KeyHash[R] = KeyHash.fromBytes[R](hash28(b)).toOption.get
  private def script(b: Int): ScriptHash = ScriptHash.fromBytes(hash28(b)).toOption.get

  test("Credential: script credentials sort before key credentials (Credential.hs:98-101)") {
    val k: Credential[Staking] = KeyHashObj(key(0))
    val s: Credential[Staking] = ScriptHashObj(script(0xff))
    assert(lt(s, k))
    assertEquals(List(k, s).sorted(using Order[Credential[Staking]].toOrdering), List(s, k))
  }

  test("Credential: within a constructor, by hash, unsigned (Hashes.hs:166-168, 203-205)") {
    assert(lt[Credential[Staking]](KeyHashObj(key(0x7f)), KeyHashObj(key(0x80))))
    assert(lt[Credential[Staking]](ScriptHashObj(script(0x01)), ScriptHashObj(script(0xff))))
  }

  test("DRep: key hash < script hash < always abstain < always no confidence (DRep.hs:67-72)") {
    val ordered = List[DRep](
      DRepKeyHash(key(0xff)),
      DRepScriptHash(script(0)),
      DRepAlwaysAbstain,
      DRepAlwaysNoConfidence
    )
    assertEquals(ordered.reverse.sorted(using Order[DRep].toOrdering), ordered)
    assert(ordered.zip(ordered.tail).forall((a, b) => lt(a, b)))
  }

  test("DRep puts the key first where Credential puts the script first") {
    assert(lt[DRep](DRepKeyHash(key(5)), DRepScriptHash(script(5))))
    assert(lt[Credential[DRepRole]](ScriptHashObj(script(5)), KeyHashObj(key(5))))
  }

  test("WithOrigin: Origin is the least; At orders by the value (cardano-slotting Slot.hs:71-82)") {
    assert(lt[WithOrigin[Int]](WithOrigin.Origin, WithOrigin.At(0)))
    assert(lt[WithOrigin[Int]](WithOrigin.Origin, WithOrigin.At(Int.MinValue)))
    assert(lt[WithOrigin[Int]](WithOrigin.At(1), WithOrigin.At(2)))
    assert(!lt[WithOrigin[Int]](WithOrigin.At(0), WithOrigin.Origin))
    // an unsigned value from the high half is still above Origin and above the low half
    assert(lt(WithOrigin.Origin: WithOrigin[SlotNo], WithOrigin.At(SlotNo.fromUnsigned(-1L))))
    assert(lt(WithOrigin.At(SlotNo.fromUnsigned(1L)), WithOrigin.At(SlotNo.fromUnsigned(-1L))))
  }

  test("NonZero orders as the value does (NonZero.hs:76-78, deriving newtype Ord)") {
    assertEquals(
      Order[NonZero[Int]].compare(NonZero.from(-2).toOption.get, NonZero.from(3).toOption.get),
      Order[Int].compare(-2, 3)
    )
    val hi = NonZero.from(Word64.fromUnsigned(-1L)).toOption.get
    val lo = NonZero.from(Word64.fromUnsigned(1L)).toOption.get
    assert(lt(lo, hi))
  }

  property("Eq agrees with the order") {
    forAll((a: DRep, b: DRep) => (a === b) == (Order[DRep].compare(a, b) == 0))
  }

  private val two64 = BigInt(2).pow(64)
  private val two63 = BigInt(2).pow(63)

  test("Coin admits signed arbitrary integers; compact conversion retains the Word64 bounds") {
    val amounts = List(-(BigInt(1) << 128), BigInt(-1), BigInt(0), two64, BigInt(1) << 128)
    amounts.foreach(n => assertEquals(Coin(n).value, n))
    assertEquals(
      CompactCoin.fromCoin(Coin(-1)),
      Left(VocabError.Negative(VocabError.Kind.CompactCoin, BigInt(-1)))
    )
  }

  test("smart constructors reject wrong values with a typed error") {
    import VocabError.*
    assertEquals(SlotNo.fromBigInt(BigInt(-1)), Left(Negative(Kind.SlotNo, BigInt(-1))))
    assertEquals(EpochNo.fromBigInt(BigInt(-5)), Left(Negative(Kind.EpochNo, BigInt(-5))))
    assertEquals(BlockNo.fromBigInt(BigInt(-1)), Left(Negative(Kind.BlockNo, BigInt(-1))))
    assertEquals(CompactCoin.fromBigInt(BigInt(-1)), Left(Negative(Kind.CompactCoin, BigInt(-1))))
    assertEquals(SlotNo.fromBigInt(two64), Left(OutOfRange(Kind.SlotNo, two64)))
    assertEquals(EpochNo.fromBigInt(two64), Left(OutOfRange(Kind.EpochNo, two64)))
    assertEquals(BlockNo.fromBigInt(two64 + 1), Left(OutOfRange(Kind.BlockNo, two64 + 1)))
    assertEquals(CompactCoin.fromBigInt(two64), Left(OutOfRange(Kind.CompactCoin, two64)))
    val big =
      Coin(two64) // a Coin is an Integer: it fits, a compact one not
    assertEquals(CompactCoin.fromCoin(big), Left(OutOfRange(Kind.CompactCoin, two64)))
    assertEquals(
      KeyHash.fromBytes[Staking](Bytes.fromArray(Array.fill(27)(0.toByte))),
      Left(WrongHashLength(Kind.KeyHash, 28, 27))
    )
    assertEquals(Hash32.fromBytes(hash28(0)), Left(WrongHashLength(Kind.Hash32, 32, 28)))
    assertEquals(NonZero.from(0), Left(Zero(Kind.NonZero)))
    assertEquals(NonZero.from(Word64.zero), Left(Zero(Kind.NonZero)))
  }

  test("the whole range [0, 2^64) is accepted, and 2^63 and above are not negative") {
    val edges = List(BigInt(0), BigInt(1), two63 - 1, two63, two63 + 1, two64 - 1)
    edges.foreach { n =>
      assertEquals(SlotNo.fromBigInt(n).map(_.toBigInt), Right(n))
      assertEquals(EpochNo.fromBigInt(n).map(_.toBigInt), Right(n))
      assertEquals(BlockNo.fromBigInt(n).map(_.toBigInt), Right(n))
      assertEquals(CompactCoin.fromBigInt(n).map(_.toBigInt), Right(n))
    }
    assertEquals(SlotNo.fromBigInt(two64 - 1), Right(SlotNo.fromUnsigned(-1L)))
    assertEquals(Word64.max.toBigInt, two64 - 1)
  }

  test("values from 2^63 up sort above the small ones, and render unsigned") {
    val small = SlotNo.fromBigInt(BigInt(5)).toOption.get
    val high = SlotNo.fromBigInt(two63).toOption.get
    val top = SlotNo.fromBigInt(two64 - 1).toOption.get
    assert(lt(small, high))
    assert(lt(high, top))
    assertEquals(
      List(top, small, high).sorted(using Order[SlotNo].toOrdering),
      List(small, high, top)
    )
    assertEquals(top.show, "SlotNo(18446744073709551615)")
    assertEquals(EpochNo.fromUnsigned(Long.MinValue).show, "EpochNo(9223372036854775808)")
  }

  property("fromBigInt and toBigInt are inverse over the whole word") {
    forAll { (w: SlotNo) =>
      SlotNo.fromBigInt(w.toBigInt) == Right(w) && w.toBigInt.signum >= 0 && w.toBigInt < two64
    }
  }

  property("the order is the order of the integers") {
    forAll((a: EpochNo, b: EpochNo) =>
      Order[EpochNo].compare(a, b) == a.toBigInt.compare(b.toBigInt)
    )
  }

  test("accepted values round-trip") {
    assertEquals(Coin(BigInt(7)).value, BigInt(7))
    assertEquals(CompactCoin.fromCoin(Coin.zero).map(_.toBigInt), Right(BigInt(0)))
    assertEquals(NonZero.from(3).map(_.value), Right(3))
    assertEquals(EpochNo.fromBigInt(BigInt(4)).map(_.next.map(_.toBigInt)), Right(Some(BigInt(5))))
  }

  test("next is refused at the top of the range instead of wrapping") {
    assertEquals(EpochNo.fromUnsigned(-1L).next, None)
    assertEquals(EpochNo.fromUnsigned(-2L).next, Some(EpochNo.fromUnsigned(-1L)))
    assertEquals(
      EpochNo.fromUnsigned(Long.MaxValue).next,
      Some(EpochNo.fromUnsigned(Long.MinValue))
    )
  }
