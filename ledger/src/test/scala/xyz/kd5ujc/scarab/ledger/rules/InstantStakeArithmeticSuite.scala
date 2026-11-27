// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import munit.FunSuite
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Address, TestLedger, TxIn, TxOut, Utxo as NodeUtxo}
import xyz.kd5ujc.scarab.ledger.state.*

/** Source-derived arithmetic controls, not new native captures. At cardano-ledger f649f975,
  * Conway/State/Stake.hs:87-120 uses Word64 arithmetic and Map.alter insertion on absence;
  * Shelley/Rules/Utxo.hs:613-624 adds outputs before deleting the ordered input map, which Conway's
  * change of the state on the era types follows.
  */
class InstantStakeArithmeticSuite extends FunSuite:
  private val stakeBytes = Bytes(Vector.fill(28)(2.toByte))
  private val credential: Credential[Staking] = Credential.KeyHashObj(
    KeyHash.fromBytes[Staking](stakeBytes).fold(e => fail(e.toString), identity)
  )
  private val address = Address
    .decode(Bytes(Vector(0.toByte) ++ Vector.fill(28)(1.toByte) ++ stakeBytes.value))
    .fold(e => fail(e.toString), identity)
  private val modulus = BigInt(1) << 64
  private def coin(n: BigInt): CompactCoin =
    CompactCoin.fromBigInt(n).fold(e => fail(e.toString), identity)
  private def cache(n: Option[BigInt]): ConwayInstantStake =
    ConwayInstantStake(
      SortedMap.from(n.toVector.map(credential -> coin(_)))(using
        summon[cats.Order[Credential[Staking]]].toOrdering
      )
    )
  private def output(n: BigInt): TxOut = TestLedger.out(address, n)
  private val empty = cache(None)

  test("deletion underflows modulo Word64 without widening CompactCoin") {
    assertEquals(
      InstantStakeOps.update(cache(Some(1)), Vector.empty, Vector(output(3))),
      Right(cache(Some(modulus - 2)))
    )
  }

  test("addition overflow wraps to zero and removes the entry") {
    assertEquals(
      InstantStakeOps.update(cache(Some(modulus - 1)), Vector(output(1)), Vector.empty),
      Right(empty)
    )
  }

  test("a missing credential inserts the amount for addition and deletion") {
    assertEquals(
      InstantStakeOps.update(empty, Vector(output(3)), Vector.empty),
      Right(cache(Some(3)))
    )
    assertEquals(
      InstantStakeOps.update(empty, Vector.empty, Vector(output(3))),
      Right(cache(Some(3)))
    )
    assertEquals(InstantStakeOps.update(empty, Vector.empty, Vector(output(0))), Right(empty))
  }

  test("zero removal is observed by the next ordered deletion") {
    assertEquals(
      InstantStakeOps.update(cache(Some(3)), Vector.empty, Vector(output(3), output(2))),
      Right(cache(Some(2)))
    )
  }

  test("outputs are added before consumed outputs are deleted") {
    assertEquals(InstantStakeOps.update(empty, Vector(output(1)), Vector(output(1))), Right(empty))
  }

  test("negative and oversized output coins still fail checked conversion") {
    Vector(BigInt(-1), modulus).foreach { n =>
      val expected =
        if n < 0 then VocabError.Negative(VocabError.Kind.CompactCoin, n)
        else VocabError.OutOfRange(VocabError.Kind.CompactCoin, n)
      assertEquals(InstantStakeOps.update(empty, Vector(output(n)), Vector.empty), Left(expected))
      assertEquals(InstantStakeOps.update(empty, Vector.empty, Vector(output(n))), Left(expected))
    }
  }

  test("production cache deletion follows TxIn map order rather than signed input order") {
    // the inputs are written and signed in the order (second, first), which Conway's decoder reads
    // as a set; the outputs are deleted in the order of `TxIn` (`Ord TxIn`): the output at the
    // lesser input, 3, first, which leaves nothing, then the absent credential takes 2
    val first = TxIn(Bytes(Vector.fill(32)(1.toByte)), 0)
    val second = TxIn(Bytes(Vector.fill(32)(2.toByte)), 0)
    val kit = xyz.kd5ujc.scarab.ledger.era.conway.ConwayTestKit
    val held = NodeUtxo.fromMap(Map(first -> kit.out(address, 3), second -> kit.out(address, 2)))
    val ls = Empty.ledgerState[Conway](TestLedger.params(10))
    val initial = ls.utxo.copy[Conway](utxo = UtxoTables(held), instantStake = cache(Some(3)))
    val tx =
      kit.tx(kit.Body(Vector(second, first), Vector.empty, 0, inputsInOrder = true), Vector.empty)
    val (next, _) =
      xyz.kd5ujc.scarab.ledger.era.EraUtxoRun
        .update(tx.typed, TestLedger.params(10), ls.cert, initial)
    assertEquals(tx.flat.body.inputs, Vector(second, first))
    assertEquals(next.instantStake, cache(Some(2)))
    assertEquals(next.utxo.utxo.count, 0L)
  }
