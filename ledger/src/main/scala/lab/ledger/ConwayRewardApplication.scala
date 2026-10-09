// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.Blake2b
import lab.cbor.Bytes
import scala.util.control.NonFatal
import ConwayStake as Stake
import ConwayEpochBoundary.{Reward, Pots, Deltas}

/** PV9 reward account/pot application subrule. No entitlement, pulser or non-myopic update. */
object ConwayRewardApplication:
  val Profile = "conway-pv9-completed-reward-application-v1"
  private val Max = (BigInt(1) << 64) - 1
  final class Applied private[ConwayRewardApplication] (
      val registered: Map[Stake.Credential, Set[Reward]],
      val unregistered: Map[Stake.Credential, Set[Reward]],
      val credited: Map[Stake.Credential, BigInt],
      val totalUnregistered: BigInt,
      val accounts: Map[Stake.Credential, Stake.Account],
      val pots: Pots,
      val id: Bytes
  ):
    val unregisteredCredentials = unregistered.keySet
    val shelleyIgnored: Map[Stake.Credential, Set[Reward]] = Map.empty
    val balances = accounts.map((c, a) => c -> a.balance)
    val nativeEntitlementValidated = false
  private def checked[A](a: => A): Either[String, A] =
    try Right(a)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def width(b: Bytes, n: Int) = b != null && b.value != null && b.size == n
  private def credential(c: Stake.Credential) = c != null && width(c.hash, 28)
  private def coin(n: BigInt) = n != null && n >= 0 && n <= Max
  private def signed(n: BigInt) = n != null && n >= -Max && n <= Max
  private def potBounds(p: Pots): Unit =
    require(
      p != null && Vector(p.treasury, p.reserves, p.fees, p.maxSupply).forall(coin) &&
        p.maxSupply > 0 && p.treasury + p.reserves + p.fees <= p.maxSupply,
      "pot bounds"
    )
  private def rewardText(rs: Map[Stake.Credential, Set[Reward]]) = rs.toVector
    .sortBy(_._1.key)
    .map { (c, set) =>
      c.key + ":" + set.toVector
        .sortBy(r => (r.kind.ordinal, r.pool.hex))
        .map(r => s"${r.kind}:${r.pool.hex}:${r.amount}")
        .mkString("|")
    }
    .mkString("\n")

  /** Shared PV9 shape/set-identity validation, bounded before aggregation. */
  private[ledger] def sumChecked(
      rewards: Map[Stake.Credential, Set[Reward]]
  ): Either[String, BigInt] = checked {
    require(
      rewards != null && rewards.size <= 4096 && rewards.values.forall(_ != null) &&
        rewards.values.map(_.size.toLong).sum <= 4096,
      "reward set bound"
    )
    rewards.foreach { (c, rs) =>
      require(
        credential(c) && rs.forall(r =>
          r != null && r.kind != null && width(r.pool, 28) && coin(r.amount)
        ),
        "reward shape"
      )
      require(rs.map(r => (r.kind, r.pool)).size == rs.size, "duplicate reward type/pool")
    }
    val total = rewards.values.toVector.flatMap(_.toVector).map(_.amount).sum
    require(coin(total), "reward aggregate overflow")
    total
  }

  /** Registration is exactly the application-time account-map domain, never the frozen domain.
    * Unregistered recipients need not refer to a currently registered pool. PV9 ignores no reward.
    */
  def applyPv9(
      accounts: Map[Stake.Credential, Stake.Account],
      before: Pots,
      deltas: Deltas,
      rewards: Map[Stake.Credential, Set[Reward]]
  ): Either[String, Applied] = checked {
    require(
      accounts != null && accounts.size <= 4096 && accounts.forall { (c, a) =>
        credential(c) && a != null && coin(a.balance) && coin(a.deposit) &&
        a.delegation != null && a.delegation.forall(width(_, 28))
      },
      "application account bounds"
    )
    potBounds(before)
    val oldBalances = accounts.values.map(_.balance).sum
    require(
      coin(
        oldBalances
      ) && oldBalances + before.treasury + before.reserves + before.fees <= before.maxSupply,
      "application tracked supply bounds"
    )
    require(
      deltas != null && Vector(deltas.treasury, deltas.reserves, deltas.fees).forall(signed),
      "signed delta bounds"
    )
    val total = sumChecked(rewards).fold(s => throw new IllegalArgumentException(s), identity)
    val (registered, unregistered) = rewards.partition((c, _) => accounts.contains(c))
    def aggregate(rs: Map[Stake.Credential, Set[Reward]]) =
      rs.map((c, set) => c -> set.toVector.map(_.amount).sum)
    val credited = aggregate(registered)
    val unregisteredCoin = aggregate(unregistered).values.sum
    require(
      coin(total) && deltas.treasury + deltas.reserves + deltas.fees + total == 0,
      "reward conservation"
    )
    // Check addDeltaCoin before adding unregistered rewards: later credit cannot repair a negative pot.
    val deltaTreasury = before.treasury + deltas.treasury
    val deltaReserves = before.reserves + deltas.reserves
    val deltaFees = before.fees + deltas.fees
    require(
      Vector(deltaTreasury, deltaReserves, deltaFees).forall(coin),
      "negative or overflowing delta pot"
    )
    val pots = before.copy(
      treasury = deltaTreasury + unregisteredCoin,
      reserves = deltaReserves,
      fees = deltaFees
    )
    potBounds(pots)
    val after =
      accounts.map((c, a) => c -> a.copy(balance = a.balance + credited.getOrElse(c, BigInt(0))))
    require(
      after.values.forall(a => coin(a.balance)) && after.values.map(_.balance).sum <= Max,
      "post reward balance bounds"
    )
    require(
      after.values.map(_.balance).sum + pots.treasury + pots.reserves + pots.fees ==
        oldBalances + before.treasury + before.reserves + before.fees,
      "application conservation"
    )
    val accountText = accounts.toVector
      .sortBy(_._1.key)
      .map((c, a) => s"${c.key}:${a.balance}:${a.deposit}:${a.delegation.map(_.hex)}")
      .mkString("|")
    val text = s"$Profile\n$before\n$deltas\n$accountText\n${rewardText(rewards)}"
    val id = Blake2b.hash256.hash(Bytes.fromArray(text.getBytes("UTF-8")))
    new Applied(registered, unregistered, credited, unregisteredCoin, after, pots, id)
  }
