// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import xyz.kd5ujc.scarab.ledger.rules.{
  Convert,
  EraLedger,
  InstantStakeOps,
  ShelleyInstantStakeOps,
  StakeDistribution
}
import xyz.kd5ujc.scarab.ledger.state.{
  CompactCoin,
  ConwayInstantStake,
  KeyHash,
  ShelleyInstantStake,
  Sorted,
  StakePool,
  Staking,
  VocabError,
  Credential as Cred
}
import xyz.kd5ujc.scarab.ledger.state.given

/** The stake kept incrementally with the UTxO set equals the one recomputed from the whole set, for
  * base and pointer addresses, before and from Conway, under random spends and creations: the
  * node's UTxO value (its stake by credential and by pointer, its total and count) and the era's
  * instant stake that the rules keep, resolved for the accounts as the SNAP rule resolves it.
  */
class StakeSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(100)

  private def ok[A](value: Either[VocabError, A]): A = value.fold(e => fail(e.toString), identity)

  private def hash(n: Int): Bytes = Bytes.wrap(Array.tabulate(28)(i => (n + i).toByte))

  private def credential(n: Int): Cred[Staking] =
    ok(Convert.credential[Staking](Credential.Key(hash(n))))

  /** The one pool every account delegates to: the resolution keeps delegated accounts only. */
  private val pool: KeyHash[StakePool] = ok(KeyHash.fromBytes[StakePool](hash(99)))

  private def address(kind: Int, pay: Int, stake: Int): Address =
    val raw: Bytes = kind match
      case 0 => Bytes.wrap(0x61.toByte +: hash(pay).toArray)
      case 1 => Bytes.wrap((0x01.toByte +: hash(pay).toArray) ++ hash(stake).toArray)
      case _ =>
        Bytes.wrap((0x41.toByte +: hash(pay).toArray) ++ Array[Byte](1, 2, (stake % 3).toByte))
    Address.decode(raw).fold(e => fail(e.toString), identity)

  private val genOut: Gen[TxOut] =
    for
      kind <- Gen.choose(0, 2)
      pay <- Gen.choose(0, 4)
      stake <- Gen.choose(0, 4)
      coin <- Gen.choose(0L, 5000000L)
    yield TxOut(address(kind, pay, stake), Value.coin(coin))

  /** A UTxO set funded with `funds`, then spent of `dropped` and given `more`; the accounts
    * registered with their reward balances, and the pointers of the certificates that registered
    * some credentials.
    */
  private final case class Case(
      major: Int,
      funds: Vector[(TxIn, TxOut)],
      dropped: Vector[TxIn],
      more: Vector[(TxIn, TxOut)],
      accounts: SortedMap[Cred[Staking], BigInt],
      ptrs: SortedMap[Ptr, Cred[Staking]]
  ):
    val start: Utxo = Utxo.empty ++ funds
    val utxo: Utxo = start -- dropped ++ more
    def spent: Vector[TxOut] = dropped.flatMap(start.get)
    def pointers: Boolean = major < 9

  private val genCase: Gen[Case] =
    for
      major <- Gen.oneOf(6, 8, 9, 10)
      funds <- Gen.listOfN(30, genOut)
      drops <- Gen.listOf(Gen.choose(0, 29))
      more <- Gen.listOfN(10, genOut)
      registered <- Gen.listOf(Gen.choose(0, 4))
      pointed <- Gen.listOf(Gen.choose(0, 2))
      balances <- Gen.listOfN(5, Gen.choose(0L, 100000L))
    yield Case(
      major,
      funds.toVector.zipWithIndex.map((o, i) => TestLedger.txIn(i) -> o),
      drops.distinct.toVector.map(TestLedger.txIn),
      more.toVector.zipWithIndex.map((o, i) => TestLedger.txIn(100 + i) -> o),
      Sorted.map(registered.distinct.map(n => credential(n) -> BigInt(balances(n)))),
      Sorted.map(pointed.distinct.map(n => Ptr(1, 2, n) -> credential(n)))
    )

  /** The original, whole-set computation: for each account, the coin of the outputs staking to it
    * (and, before Conway, of those whose pointer names it) plus its reward balance.
    */
  private def recomputed(c: Case): Map[Cred[Staking], BigInt] =
    val fromUtxo = c.utxo.valuesIterator
      .flatMap(out =>
        out.address.kind match
          case Address.Kind.Shelley(_, _, Some(stake), _) =>
            Iterator(ok(Convert.credential[Staking](stake)) -> out.value.coin)
          case Address.Kind.Shelley(_, _, None, Some(ptr)) if c.pointers =>
            c.ptrs.get(ptr).iterator.map(_ -> out.value.coin)
          case _ => Iterator.empty
      )
      .toVector
      .groupMapReduce(_._1)(_._2)(_ + _)
    c.accounts.iterator.map((k, balance) => k -> (fromUtxo.getOrElse(k, BigInt(0)) + balance)).toMap

  /** The era's instant stake kept through the creations and spends, resolved for the accounts
    * (`resolveActiveInstantStakeCredentials`; before Conway with the pointer stake).
    */
  private def resolved(c: Case): Map[Cred[Staking], BigInt] =
    val (credentials, ptrs, ptrStake) =
      if c.pointers then
        val is = (for
          funded <- ShelleyInstantStakeOps.update(
            ShelleyInstantStake(Sorted.empty, Sorted.empty),
            c.funds.map(_._2),
            Nil
          )
          spent <- ShelleyInstantStakeOps.update(funded, Nil, c.spent)
          created <- ShelleyInstantStakeOps.update(spent, c.more.map(_._2), Nil)
        yield created).fold(e => fail(e.toString), identity)
        (is.credentialStake, c.ptrs, is.ptrStake)
      else
        val is = (for
          funded <- InstantStakeOps.update(ConwayInstantStake(Sorted.empty), c.funds.map(_._2), Nil)
          spent <- InstantStakeOps.update(funded, Nil, c.spent)
          created <- InstantStakeOps.update(spent, c.more.map(_._2), Nil)
        yield created).fold(e => fail(e.toString), identity)
        (is.credentialStake, Sorted.empty[Ptr, Cred[Staking]], Sorted.empty[Ptr, CompactCoin])
    val accounts = c.accounts.iterator.map { (k, balance) =>
      k -> EraLedger.AccountView(
        ok(CompactCoin.fromBigInt(balance)),
        ok(CompactCoin.fromBigInt(0)),
        Some(pool)
      )
    }
    StakeDistribution
      .resolve(credentials, accounts, ptrs, ptrStake)
      .entries
      .iterator
      .map { (k, s) =>
        k -> s.stake.value.toBigInt
      }
      .toMap

  property("the UTxO's stake by credential and by pointer equals that of the whole set") {
    forAll(genCase) { c =>
      val outputs = c.utxo.valuesIterator.toVector
      val byCredential = outputs
        .flatMap(out =>
          out.address.kind match
            case Address.Kind.Shelley(_, _, Some(stake), _) => Some(stake -> out.value.coin)
            case _                                          => None
        )
        .groupMapReduce(_._1)(_._2)(_ + _)
        .filter(_._2 != 0)
      val byPointer = outputs
        .flatMap(out =>
          out.address.kind match
            case Address.Kind.Shelley(_, _, None, Some(ptr)) => Some(ptr -> out.value.coin)
            case _                                           => None
        )
        .groupMapReduce(_._1)(_._2)(_ + _)
        .filter(_._2 != 0)
      c.utxo.stake == byCredential && c.utxo.ptrStake == byPointer
    }
  }

  property("incremental instant stake, resolved, equals the stake recomputed from the whole set") {
    forAll(genCase) { c =>
      // the resolution keeps the accounts whose stake is not zero
      resolved(c) == recomputed(c).filter(_._2 != 0)
    }
  }

  property("coin total and count are exact") {
    forAll(genCase) { c =>
      c.utxo.coin == c.utxo.valuesIterator.map(_.value.coin).sum &&
      c.utxo.count == c.utxo.toMap.size
    }
  }
