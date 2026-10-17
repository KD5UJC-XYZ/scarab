// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.legacy.*


import xyz.kd5ujc.scarab.codec.Bytes
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

/** The stake distribution kept incrementally with the UTxO set equals the one recomputed from the
  * whole set, for base and pointer addresses, before and from Conway, under random spends and
  * creations.
  */
class StakeSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(100)

  private def hash(n: Int): Bytes = Bytes.wrap(Array.tabulate(28)(i => (n + i).toByte))

  private def address(kind: Int, pay: Int, stake: Int): Address =
    val raw: Bytes = kind match
      case 0 => Bytes.wrap(0x61.toByte +: hash(pay).toArray)
      case 1 => Bytes.wrap((0x01.toByte +: hash(pay).toArray) ++ hash(stake).toArray)
      case _ =>
        Bytes.wrap((0x41.toByte +: hash(pay).toArray) ++ Array[Byte](1, 2, (stake % 3).toByte))
    Address.decode(raw).fold(e => throw new AssertionError(e), identity)

  private val genOut: Gen[TxOut] =
    for
      kind <- Gen.choose(0, 2)
      pay <- Gen.choose(0, 4)
      stake <- Gen.choose(0, 4)
      coin <- Gen.choose(0L, 5000000L)
    yield TxOut(address(kind, pay, stake), Value.coin(coin))

  /** The original, whole-set computation. */
  private def recomputed(state: LedgerState): Map[Credential, BigInt] =
    val pointers = state.params.protocolMajor < 9
    val fromUtxo = state.utxo.valuesIterator
      .flatMap(out =>
        out.address.kind match
          case Address.Kind.Shelley(_, _, Some(stake), _) => Iterator(stake -> out.value.coin)
          case Address.Kind.Shelley(_, _, None, Some(ptr)) if pointers =>
            state.ptrs.get(ptr).iterator.map(_ -> out.value.coin)
          case _ => Iterator.empty
      )
      .toVector
      .groupMapReduce(_._1)(_._2)(_ + _)
    state.accounts.iterator.map { case (c, a) =>
      c -> (fromUtxo.getOrElse(c, BigInt(0)) + a.balance)
    }.toMap

  private val genState: Gen[LedgerState] =
    for
      major <- Gen.oneOf(6, 8, 9, 10)
      funds <- Gen.listOfN(30, genOut)
      drops <- Gen.listOf(Gen.choose(0, 29))
      more <- Gen.listOfN(10, genOut)
      registered <- Gen.listOf(Gen.choose(0, 4))
      pointed <- Gen.listOf(Gen.choose(0, 2))
      balances <- Gen.listOfN(5, Gen.choose(0L, 100000L))
    yield
      val ins = funds.indices.map(i => TestLedger.txIn(i))
      val base = FlatTestLedger.state(major, Vector.empty)
      val start = base.utxo ++ ins.zip(funds)
      val spent = start -- drops.distinct.map(TestLedger.txIn)
      val created = spent ++ more.zipWithIndex.map((o, i) => TestLedger.txIn(100 + i) -> o)
      base.copy(
        utxo = created,
        accounts = registered.distinct
          .map(n => Credential.Key(hash(n)) -> Account(balances(n), 0, None, None))
          .toMap,
        ptrs = PtrMap(pointed.distinct.map(n => Ptr(1, 2, n) -> Credential.Key(hash(n))).toMap)
      )

  property("incremental stake equals the stake recomputed from the whole UTxO set") {
    forAll(genState) { state =>
      Stake.instant(state) == recomputed(state)
    }
  }

  property("coin total and count are exact") {
    forAll(genState) { state =>
      state.utxo.coin == state.utxo.valuesIterator.map(_.value.coin).sum &&
      state.utxo.count == state.utxo.toMap.size
    }
  }
