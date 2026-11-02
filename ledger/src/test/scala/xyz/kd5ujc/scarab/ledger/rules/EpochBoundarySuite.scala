// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import monocle.Lens
import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import scala.collection.immutable.{SortedMap, TreeMap}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Ptr
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.state.given
import xyz.kd5ujc.scarab.ledger.state.VocabularyGen.given

/** The pieces of the epoch boundary that were made fast (#131) give what the straightforward
  * version gave: the comparison of hashes, the sorted maps built once from sorted input, the merge
  * of the accounts with the instant stake and with the rewards.
  */
class EpochBoundarySuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(300)

  private def hash28(n: Int): Bytes =
    Bytes.fromArray(Array.tabulate(28)(i => (n >> (i % 4)).toByte))

  /** A small universe of credentials, so that the maps overlap. */
  private val credential: Gen[Credential[Staking]] =
    Gen.choose(0, 40).map { n =>
      if n % 3 == 0 then
        Credential.ScriptHashObj[Staking](ScriptHash.fromBytes(hash28(n)).toOption.get)
      else Credential.KeyHashObj[Staking](KeyHash.fromBytes[Staking](hash28(n)).toOption.get)
    }

  private val pool: Gen[KeyHash[StakePool]] =
    Gen.choose(0, 5).map(n => KeyHash.fromBytes[StakePool](hash28(100 + n)).toOption.get)

  private def sorted[A](
      entries: Gen[(Credential[Staking], A)]
  ): Gen[SortedMap[Credential[Staking], A]] =
    Gen.listOf(entries).map(Sorted.map(_))

  private def reference(a: Bytes, b: Bytes): Int =
    val n = math.min(a.size, b.size)
    val (x, y) = (a.toArray, b.toArray)
    (0 until n).iterator
      .map(i => java.lang.Byte.toUnsignedInt(x(i)) - java.lang.Byte.toUnsignedInt(y(i)))
      .find(_ != 0)
      .getOrElse(a.size - b.size)

  private val bytes: Gen[Bytes] =
    Gen
      .choose(0, 40)
      .flatMap(n =>
        Gen
          .listOfN(n, Gen.oneOf(0, 1, 0x7f, 0x80, 0xff))
          .map(bs => Bytes.fromArray(bs.map(_.toByte).toArray))
      )

  property("Bytes.compareTo orders as the unsigned lexicographic comparison, of windows too") {
    forAll(bytes, bytes, Gen.choose(0, 5)) { (a, b, cut) =>
      Integer.signum(a.compareTo(b)) == Integer.signum(reference(a, b)) &&
      Integer.signum(a.drop(cut).compareTo(b.take(40 - cut))) ==
        Integer.signum(reference(a.drop(cut), b.take(40 - cut)))
    }
  }

  property(
    "fromAscending is the map of the same entries, with the ordering of every map of the key"
  ) {
    forAll(sorted(Gen.zip(credential, Gen.choose(0, 9)))) { m =>
      val ascending = Sorted.fromAscending(m.toVector)
      val shared = Sorted.empty[Credential[Staking], Int]
      assertEquals(ascending.toList, m.toList)
      assertEquals(ascending.size, m.size)
      assertEquals(ascending.keySet.toList, m.keySet.toList)
      assert(ascending.ordering eq shared.ordering)
      assert(m.ordering eq shared.ordering)
      // a map made from a map of the same ordering is that map, not a copy
      assert(TreeMap.from(m)(using m.ordering) eq m)
      m.keys.foreach(k => assertEquals(ascending.get(k), m.get(k)))
      assertEquals(ascending.updated(k0, 99).toList, m.updated(k0, 99).toList)
      assertEquals(ascending.removed(k0).toList, m.removed(k0).toList)
      true
    }
  }

  private val k0: Credential[Staking] =
    Credential.KeyHashObj[Staking](KeyHash.fromBytes[Staking](hash28(0)).toOption.get)

  private val balance: Lens[CompactCoin, CompactCoin] =
    Lens[CompactCoin, CompactCoin](identity)(b => _ => b)

  property("crediting ascending compact amounts is crediting them one by one") {
    forAll(
      sorted(Gen.zip(credential, Arbitrary.arbitrary[CompactCoin])),
      sorted(Gen.zip(credential, Arbitrary.arbitrary[CompactCoin]))
    ) { (states, amounts) =>
      val ascending = amounts.toVector
      val merged = EraLedger.mergeStates(states, balance, ascending)
      val oneByOne = EraLedger.creditStates(states, balance, ascending)
      assertEquals(merged.toList, oneByOne.toList)
      assert(merged.ordering eq states.ordering)
      true
    }
  }

  /** The same native compact resolution by a lookup for each account instead of a cursor. */
  private def resolved(
      instant: SortedMap[Credential[Staking], CompactCoin],
      accounts: Vector[(Credential[Staking], EraLedger.AccountView)]
  ): ActiveStake =
    val entries = accounts.flatMap { (c, account) =>
      account.pool.flatMap { p =>
        val total = instant.get(c) match
          case Some(stake) => Some(stake.add(account.balance))
          case None        => Option.unless(account.balance.word.isZero)(account.balance)
        total.map(stake => c -> StakeWithDelegation(NonZero.unsafe(stake), p))
      }
    }
    ActiveStake(Sorted.map(entries))

  property(
    "the active stake by a merge with the instant stake is the one by a lookup for each account"
  ) {
    val account = for
      b <- Arbitrary.arbitrary[CompactCoin]
      d <- Arbitrary.arbitrary[CompactCoin]
      p <- Gen.option(pool)
    yield EraLedger.AccountView(b, d, p)
    forAll(
      sorted(Gen.zip(credential, account)),
      sorted(Gen.zip(credential, Arbitrary.arbitrary[CompactCoin]))
    ) { (accounts, instant) =>
      val got = StakeDistribution.resolve(
        instant,
        accounts.iterator,
        Sorted.empty[Ptr, Credential[Staking]],
        Sorted.empty[Ptr, CompactCoin]
      )
      val want = resolved(instant, accounts.toVector)
      assertEquals(got.entries.toList, want.entries.toList)
      true
    }
  }

  property("a cursor answers ascending queries as a lookup does, dense or sparse") {
    val wide: Gen[Credential[Staking]] =
      Gen
        .choose(0, 400)
        .map(n =>
          Credential.KeyHashObj[Staking](KeyHash.fromBytes[Staking](hash28(n * 7)).toOption.get)
        )
    forAll(
      Gen.listOf(Gen.zip(wide, Gen.choose(0, 9))).map(Sorted.map(_)),
      Gen.listOf(wide).map(Sorted.set(_))
    ) { (map, keys) =>
      val cursor = Sorted.Cursor(map)
      keys.foreach(k => assertEquals(cursor.at(k), map.get(k)))
      true
    }
  }
