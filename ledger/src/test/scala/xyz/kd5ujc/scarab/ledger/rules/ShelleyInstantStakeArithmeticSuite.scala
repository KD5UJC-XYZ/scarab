// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import munit.FunSuite
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Address, Ptr, TestLedger, TxChecks, TxIn, TxOut, Utxo as NodeUtxo}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.state.given

/** The pre-Conway instant stake at its Word64 boundaries, for the credential map and the pointer
  * map. At cardano-ledger f649f975, `Shelley/State/Stake.hs:108-145` adds and subtracts
  * `CompactForm Coin` as Word64, inserts the output amount when the entry is missing for either
  * operation, and removes an entry that is zero; `Shelley/Rules/Utxo.hs:613-624` adds the outputs
  * before deleting the extracted inputs, which `extractKeys` yields in `TxIn` map order, and
  * `Babbage/Rules/Utxo.hs:477-486` does the same for collateral. These cases are derived from that
  * source and keep the checked output-coin bounds; the captured results of the released native
  * functions are replayed by `ShelleyInstantStakeReferenceSuite`.
  */
class ShelleyInstantStakeArithmeticSuite extends FunSuite:
  private val stakeBytes = Bytes(Vector.fill(28)(2.toByte))
  private val credential: Credential[Staking] = Credential.KeyHashObj(
    KeyHash.fromBytes[Staking](stakeBytes).fold(e => fail(e.toString), identity)
  )
  private val pointer = Ptr(1, 2, 3)
  private val payment = Vector.fill(28)(1.toByte)
  private def decode(bytes: Vector[Byte]): Address =
    Address.decode(Bytes(bytes)).fold(e => fail(e.toString), identity)
  private val baseAddress = decode(Vector(0.toByte) ++ payment ++ stakeBytes.value)
  private val pointerAddress =
    decode(Vector(0x40.toByte) ++ payment ++ Vector(1, 2, 3).map(_.toByte))
  private val enterpriseAddress = decode(Vector(0x60.toByte) ++ payment)
  private val modulus = BigInt(1) << 64
  private def coin(n: BigInt): CompactCoin =
    CompactCoin.fromBigInt(n).fold(e => fail(e.toString), identity)
  private def output(address: Address, n: BigInt): TxOut = TestLedger.out(address, n)

  private def credentials(n: Option[BigInt]): ShelleyInstantStake =
    ShelleyInstantStake(
      SortedMap.from(n.toVector.map(credential -> coin(_)))(using
        summon[cats.Order[Credential[Staking]]].toOrdering
      ),
      SortedMap.empty(using summon[cats.Order[Ptr]].toOrdering)
    )
  private def pointers(n: Option[BigInt]): ShelleyInstantStake =
    ShelleyInstantStake(
      SortedMap.empty(using summon[cats.Order[Credential[Staking]]].toOrdering),
      SortedMap.from(n.toVector.map(pointer -> coin(_)))(using summon[cats.Order[Ptr]].toOrdering)
    )

  private val maps: Vector[(String, Option[BigInt] => ShelleyInstantStake, Address)] =
    Vector(("credential", credentials, baseAddress), ("pointer", pointers, pointerAddress))

  private def update(from: ShelleyInstantStake, added: Vector[TxOut], deleted: Vector[TxOut]) =
    ShelleyInstantStakeOps.update(from, added, deleted)

  maps.foreach { (name, cache, address) =>
    test(s"$name: deletion underflows modulo Word64") {
      assertEquals(
        update(cache(Some(1)), Vector.empty, Vector(output(address, 3))),
        Right(cache(Some(modulus - 2)))
      )
    }

    test(s"$name: addition overflow wraps to zero and removes the entry") {
      assertEquals(
        update(cache(Some(modulus - 1)), Vector(output(address, 1)), Vector.empty),
        Right(cache(None))
      )
    }

    test(s"$name: deletion to exactly zero removes the entry") {
      assertEquals(
        update(cache(Some(3)), Vector.empty, Vector(output(address, 3))),
        Right(cache(None))
      )
    }

    test(s"$name: a missing entry inserts the amount for addition and deletion") {
      assertEquals(
        update(cache(None), Vector(output(address, 3)), Vector.empty),
        Right(cache(Some(3)))
      )
      assertEquals(
        update(cache(None), Vector.empty, Vector(output(address, 3))),
        Right(cache(Some(3)))
      )
    }

    test(s"$name: a zero amount never creates an entry") {
      assertEquals(
        update(cache(None), Vector(output(address, 0)), Vector.empty),
        Right(cache(None))
      )
      assertEquals(
        update(cache(None), Vector.empty, Vector(output(address, 0))),
        Right(cache(None))
      )
    }

    test(s"$name: zero removal is observed by the next ordered deletion") {
      assertEquals(
        update(cache(Some(3)), Vector.empty, Vector(output(address, 3), output(address, 2))),
        Right(cache(Some(2)))
      )
    }

    test(s"$name: zero removal then reinsertion within one update") {
      assertEquals(
        update(
          cache(Some(3)),
          Vector(output(address, 1)),
          Vector(output(address, 4), output(address, 5))
        ),
        Right(cache(Some(5)))
      )
    }

    test(s"$name: outputs are added before consumed outputs are deleted") {
      assertEquals(
        update(cache(None), Vector(output(address, 1)), Vector(output(address, 1))),
        Right(cache(None))
      )
      assertEquals(
        update(cache(None), Vector(output(address, 1)), Vector(output(address, 2))),
        Right(cache(Some(modulus - 1)))
      )
    }

    test(s"$name: negative and oversized output coins still fail checked conversion") {
      Vector(BigInt(-1), modulus).foreach { n =>
        val expected =
          if n < 0 then VocabError.Negative(VocabError.Kind.CompactCoin, n)
          else VocabError.OutOfRange(VocabError.Kind.CompactCoin, n)
        assertEquals(
          update(cache(None), Vector(output(address, n)), Vector.empty),
          Left(expected)
        )
        assertEquals(
          update(cache(None), Vector.empty, Vector(output(address, n))),
          Left(expected)
        )
      }
    }
  }

  test("an address without a credential or pointer leaves the cache unchanged") {
    val held = credentials(Some(7))
    assertEquals(update(held, Vector(output(enterpriseAddress, 3)), Vector.empty), Right(held))
    assertEquals(update(held, Vector.empty, Vector(output(enterpriseAddress, 3))), Right(held))
  }

  private val first = TxIn(Bytes(Vector.fill(32)(1.toByte)), 0)
  private val second = TxIn(Bytes(Vector.fill(32)(2.toByte)), 0)
  private val held =
    NodeUtxo.fromMap(Map(first -> output(baseAddress, 3), second -> output(baseAddress, 2)))
  private val initial =
    Empty
      .ledgerState[Babbage](TestLedger.params(10))
      .utxo
      .copy[Babbage](utxo = UtxoTables(held), instantStake = credentials(Some(3)))

  test("production cache deletion follows TxIn map order rather than signed input order") {
    val body = TestLedger.body("cache-input-order", Vector(second, first), Vector.empty, 0)
    val transaction = TestLedger.tx(body, Vector.empty)
    val next = ShelleyUtxo
      .update[Babbage](initial, StAnnTx(transaction, held), TxChecks.Collateral.none, 0, 0)
      .fold(e => fail(e.toString), identity)
    assertEquals(transaction.body.inputs, Vector(second, first))
    assertEquals(next.instantStake, credentials(Some(2)))
    assertEquals(next.utxo.utxo.count, 0L)
  }

  test("a phase 2 failure deletes its collateral in TxIn map order") {
    val body = TestLedger
      .body("cache-collateral-order", Vector.empty, Vector.empty, 0)
      .copy(collateral = Vector(second, first))
    val transaction = TestLedger.tx(body, Vector.empty).copy(isValid = false)
    val collateral = TxChecks.Collateral(Vector(output(baseAddress, 2), output(baseAddress, 3)), 0)
    val next = ShelleyUtxo
      .update[Babbage](initial, StAnnTx(transaction, held), collateral, 0, 0)
      .fold(e => fail(e.toString), identity)
    assertEquals(next.instantStake, credentials(Some(2)))
  }
