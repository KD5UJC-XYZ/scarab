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

/** The orders of the vocabulary are the Haskell `Ord` instances (pinned sources:
  * `.workspace/data/2026-10-15-upstream-pinned/cardano-ledger/libs/cardano-ledger-core/src/Cardano/Ledger/`).
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
  given Cogen[NonZero[Int]] = cogenShow
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
  checkAll("NonZero", OrderTests[NonZero[Int]].order)
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

  test("WithOrigin: Origin is the least") {
    assert(lt[WithOrigin[Int]](WithOrigin.Origin, WithOrigin.At(0)))
    assert(lt[WithOrigin[Int]](WithOrigin.At(1), WithOrigin.At(2)))
  }

  property("Eq agrees with the order") {
    forAll((a: DRep, b: DRep) => (a === b) == (Order[DRep].compare(a, b) == 0))
  }

  test("smart constructors reject wrong values with a typed error") {
    import VocabError.*
    assertEquals(Coin.fromBigInt(BigInt(-1)), Left(Negative(Kind.Coin, BigInt(-1))))
    assertEquals(SlotNo.fromLong(-1), Left(Negative(Kind.SlotNo, BigInt(-1))))
    assertEquals(EpochNo.fromLong(-5), Left(Negative(Kind.EpochNo, BigInt(-5))))
    assertEquals(BlockNo.fromLong(-1), Left(Negative(Kind.BlockNo, BigInt(-1))))
    assertEquals(
      KeyHash.fromBytes[Staking](Bytes.fromArray(Array.fill(27)(0.toByte))),
      Left(WrongHashLength(Kind.KeyHash, 28, 27))
    )
    assertEquals(Hash32.fromBytes(hash28(0)), Left(WrongHashLength(Kind.Hash32, 32, 28)))
    assertEquals(NonZero.from(0), Left(Zero(Kind.NonZero)))
    val big = Coin.fromBigInt(BigInt(2).pow(63)).toOption.get
    assertEquals(CompactCoin.fromCoin(big), Left(OutOfRange(Kind.CompactCoin, BigInt(2).pow(63))))
  }

  test("accepted values round-trip") {
    assertEquals(Coin.fromBigInt(BigInt(7)).map(Coin.value), Right(BigInt(7)))
    assertEquals(CompactCoin.fromCoin(Coin.zero).map(_.toCoin), Right(Coin.zero))
    assertEquals(NonZero.from(3).map(_.value), Right(3))
    assertEquals(EpochNo.fromLong(4).map(_.next.value), Right(5L))
  }
