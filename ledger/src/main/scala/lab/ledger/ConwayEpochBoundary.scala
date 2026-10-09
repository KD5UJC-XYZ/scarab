// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.{Blake2b}
import lab.cbor.Bytes
import scala.util.control.NonFatal
import ConwayStake as Stake

/** Unpublished boundary subrule algebra. Supplied reward effects are not native monetary proof. */
object ConwayEpochBoundary:
  val Profile = "conway-pv9-unpublished-boundary-preview-v1"
  private val Max = (BigInt(1) << 64) - 1
  enum RewardKind:
    case Member, Leader
  final case class Reward(kind: RewardKind, pool: Bytes, amount: BigInt)
  final case class Pots(treasury: BigInt, reserves: BigInt, fees: BigInt, maxSupply: BigInt)
  final case class Deltas(treasury: BigInt, reserves: BigInt, fees: BigInt)
  enum Timing:
    case TooEarly, StartOrPulse, ForceCompletion
  enum Unresolved:
    case NativeRewardConformance, PoolReaping, Governance, Donations, ParameterRollover,
      HeaderAndBlock
  final class Owner private[ConwayEpochBoundary] ()
  def owner(): Owner = new Owner()
  final class Context private[ConwayEpochBoundary] (
      private[ConwayEpochBoundary] val owner: Owner,
      private[ConwayEpochBoundary] val stakeOwner: Stake.Owner,
      val tupleId: Bytes,
      val stake: Stake.State,
      val pots: Pots,
      val previousBlocks: Map[Bytes, BigInt],
      val currentBlocks: Map[Bytes, BigInt],
      val id: Bytes
  )
  final class Signal private[ConwayEpochBoundary] (
      private[ConwayEpochBoundary] val owner: Owner,
      val contextId: Bytes,
      val beforeRevision: BigInt,
      val headerHash: Bytes,
      val slot: BigInt
  )
  final class Frozen private[ConwayEpochBoundary] (
      private[ConwayEpochBoundary] val owner: Owner,
      private[ConwayEpochBoundary] val start: Context,
      val observedSlot: BigInt,
      val window: BigInt,
      val previousParameters: Bytes,
      val id: Bytes
  ):
    val epoch = start.stake.epoch
    val preTickTupleId = start.tupleId
    val go = start.stake.snapshots.go
    val snapshotFees = start.stake.snapshots.fees
    val previousBlocks = start.previousBlocks
    val registeredAccounts = start.stake.context.accounts
    val reserves = start.pots.reserves
    val maxSupply = start.pots.maxSupply
  final class Absence private[ConwayEpochBoundary] (
      private[ConwayEpochBoundary] val owner: Owner,
      val contextId: Bytes,
      val evidenceId: Bytes
  )
  final class Complete private[ConwayEpochBoundary] (
      private[ConwayEpochBoundary] val owner: Owner,
      val contextId: Bytes,
      val frozen: Frozen,
      val deltas: Deltas,
      val rewards: Map[Stake.Credential, Set[Reward]],
      val balances: Map[Stake.Credential, BigInt],
      val pots: Pots,
      val id: Bytes
  ):
    val nativeMonetaryValidated = false
  enum RewardPhase:
    case Unknown
    case Absent(evidence: Absence)
    case Pulsing(inputs: Frozen)
    case Completed(effect: Complete)
  final class Preview private[ConwayEpochBoundary] (
      val before: Context,
      val signal: Signal,
      val balances: Map[Stake.Credential, BigInt],
      val pots: Pots,
      val rotation: Stake.Rotation,
      val rewardIdentity: Bytes,
      val id: Bytes
  ):
    val previousBlocks = before.currentBlocks
    val currentBlocks: Map[Bytes, BigInt] = Map.empty
    // TICK's RUPD environment is captured before NEWEPOCH, not reconstructed from this result.
    val preTickRewardEnvironment = before
    val epoch = before.stake.epoch + 1
    val unresolved = Unresolved.values.toSet
    val epochTransitionValidated = false
    val published = false

  private def checked[A](a: => A): Either[String, A] =
    try Right(a)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  private def hash(s: String) =
    Blake2b.hash256.hash(Bytes.fromArray((Profile + "\n" + s).getBytes("UTF-8")))
  private def width(b: Bytes, n: Int) = b != null && b.value != null && b.size == n
  private def coin(n: BigInt) = n != null && n >= 0 && n <= Max
  private def signed(n: BigInt) = n != null && n >= -Max && n <= Max
  private def own(o: Owner, c: Context): Unit =
    require(o != null && c != null && (o eq c.owner), "foreign boundary context")
  private def counts(m: Map[Bytes, BigInt]): Unit =
    require(
      m != null && m.size <= 4096 && m.forall((p, n) => width(p, 28) && coin(n) && n > 0) &&
        m.values.sum <= Max,
      "block count bounds/canonical entries"
    )
  private def countText(m: Map[Bytes, BigInt]) =
    m.toVector.sortBy(_._1.hex).map((p, n) => s"${p.hex}:$n").mkString("|")
  private def balanceText(m: Map[Stake.Credential, BigInt]) =
    m.toVector.sortBy(_._1.key).map((c, n) => s"${c.key}:$n").mkString("|")
  private def validPots(p: Pots): Unit =
    require(
      p != null && Vector(p.treasury, p.reserves, p.fees, p.maxSupply).forall(coin) &&
        p.maxSupply > 0 && p.treasury + p.reserves + p.fees <= p.maxSupply,
      "pot bounds"
    )

  def context(
      o: Owner,
      stakeOwner: Stake.Owner,
      tupleId: Bytes,
      state: Stake.State,
      pots: Pots,
      previousBlocks: Map[Bytes, BigInt],
      currentBlocks: Map[Bytes, BigInt]
  ): Either[String, Context] = checked {
    require(o != null && width(tupleId, 32) && state != null, "boundary context shape")
    validPots(pots); counts(previousBlocks); counts(currentBlocks)
    require(
      state.epoch < Max && (state.epoch + 1) * state.context.epochLength <= Max,
      "epoch bound"
    )
    // Reuse the opaque stake owner's existing check; this creates only an unpublished rotation.
    get(
      Stake.previewRotation(
        stakeOwner,
        state,
        tupleId,
        (state.epoch + 1) * state.context.epochLength,
        pots.fees
      )
    )
    val held = state.utxo.values.map(_.coin).sum + state.context.accounts.values
      .map(a => a.balance + a.deposit)
      .sum + state.context.pools.values.map(_.deposit).sum
    require(
      held + pots.treasury + pots.reserves + pots.fees <= pots.maxSupply,
      "tracked supply exceeds maximum"
    )
    new Context(
      o,
      stakeOwner,
      tupleId,
      state,
      pots,
      previousBlocks,
      currentBlocks,
      hash(
        s"context:${tupleId.hex}:${state.id.hex}:${state.revision}:$pots:${countText(previousBlocks)}:${countText(currentBlocks)}"
      )
    )
  }
  def signal(o: Owner, c: Context, headerHash: Bytes, slot: BigInt): Either[String, Signal] =
    checked {
      own(o, c)
      require(
        width(headerHash, 32) && coin(slot) && slot > c.stake.slot &&
          slot / c.stake.context.epochLength == c.stake.epoch + 1,
        "exact successor epoch signal required"
      )
      new Signal(o, c.id, c.stake.revision, headerHash, slot)
    }
  def rewardTiming(epochFirst: BigInt, window: BigInt, slot: BigInt): Either[String, Timing] =
    checked {
      require(
        coin(epochFirst) && coin(window) && window > 0 && coin(slot) && slot >= epochFirst &&
          epochFirst + 2 * window <= Max,
        "reward timing bounds"
      )
      if slot <= epochFirst + window then Timing.TooEarly
      else if slot <= epochFirst + 2 * window then Timing.StartOrPulse
      else Timing.ForceCompletion
    }

  /** Supplied pre-tick start environment, frozen once at an actual observed start/force signal. No
    * reward math or native pulser is implemented by this constructor.
    */
  def freeze(
      o: Owner,
      preTick: Context,
      observedSlot: BigInt,
      window: BigInt,
      previousParameters: Bytes
  ): Either[String, Frozen] = checked {
    own(o, preTick)
    require(
      previousParameters != null && previousParameters.value != null &&
        previousParameters.size > 0 && previousParameters.size <= 65536,
      "bounded original previous parameters required"
    )
    require(
      coin(observedSlot) && observedSlot >= preTick.stake.slot &&
        observedSlot / preTick.stake.context.epochLength == preTick.stake.epoch,
      "frozen start epoch/slot mismatch"
    )
    require(
      get(
        rewardTiming(preTick.stake.epoch * preTick.stake.context.epochLength, window, observedSlot)
      ) != Timing.TooEarly,
      "reward start is strictly after stability point"
    )
    new Frozen(
      o,
      preTick,
      observedSlot,
      window,
      previousParameters,
      hash(
        s"frozen:${preTick.id.hex}:$observedSlot:$window:${Blake2b.hash256.hash(previousParameters).hex}"
      )
    )
  }

  /** Explicit synthetic/source assertion only. JSON null is not an absence proof. No importer
    * exists.
    */
  def suppliedAbsent(o: Owner, c: Context, evidenceId: Bytes): Either[String, Absence] = checked {
    own(o, c); require(width(evidenceId, 32), "explicit absence evidence identity required")
    new Absence(o, c.id, evidenceId)
  }

  /** Hand-supplied PV9 monetary effect. Checks application/conservation, never reward entitlement.
    */
  def syntheticComplete(
      o: Owner,
      c: Context,
      frozen: Frozen,
      deltas: Deltas,
      rewards: Map[Stake.Credential, Set[Reward]]
  ): Either[String, Complete] = checked {
    own(o, c)
    require(
      frozen != null && (frozen.owner eq o) && (frozen.start.stakeOwner eq c.stakeOwner) &&
        frozen.epoch == c.stake.epoch && frozen.start.stake.context.id == c.stake.context.id &&
        frozen.start.stake.revision <= c.stake.revision && frozen.observedSlot <= c.stake.slot &&
        frozen.maxSupply == c.pots.maxSupply && frozen.reserves == c.pots.reserves &&
        frozen.previousBlocks == c.previousBlocks,
      "foreign/stale frozen reward environment"
    )
    require(
      deltas != null && Vector(deltas.treasury, deltas.reserves, deltas.fees).forall(signed),
      "signed delta bounds"
    )
    require(
      rewards != null && rewards.size <= 4096 && rewards.values.forall(_ != null) &&
        rewards.values.map(_.size.toLong).sum <= 4096,
      "reward set bound"
    )
    rewards.foreach { (credential, rs) =>
      require(
        c.stake.context.accounts.contains(credential),
        "unregistered reward redistribution unsupported"
      )
      require(
        rs.nonEmpty && rs.forall(r =>
          r != null && r.kind != null && width(r.pool, 28) &&
            c.stake.context.pools.contains(r.pool) && coin(r.amount)
        ),
        "reward shape/pool"
      )
      // Haskell Reward Ord identifies entries by type and pool, not amount.
      require(rs.map(r => (r.kind, r.pool)).size == rs.size, "duplicate reward type/pool")
    }
    val totals = rewards.map((c, rs) => c -> rs.toVector.map(_.amount).sum)
    val total = totals.values.sum
    require(
      coin(total) && deltas.treasury + deltas.reserves + deltas.fees + total == 0,
      "reward conservation"
    )
    val balances = c.stake.context.accounts.map((credential, a) =>
      credential -> (a.balance + totals.getOrElse(credential, BigInt(0)))
    )
    require(
      balances.values.forall(coin) && balances.values.sum <= Max,
      "post reward balance bounds"
    )
    val pots = c.pots.copy(
      treasury = c.pots.treasury + deltas.treasury,
      reserves = c.pots.reserves + deltas.reserves,
      fees = c.pots.fees + deltas.fees
    )
    validPots(pots)
    val oldTotal = c.pots.treasury + c.pots.reserves + c.pots.fees + c.stake.context.accounts.values
      .map(_.balance)
      .sum
    require(
      pots.treasury + pots.reserves + pots.fees + balances.values.sum == oldTotal,
      "application conservation"
    )
    val rs = rewards.toVector
      .sortBy(_._1.key)
      .map { (c, set) =>
        c.key + ":" + set.toVector
          .sortBy(r => (r.kind.ordinal, r.pool.hex))
          .map(r => s"${r.kind}:${r.pool.hex}:${r.amount}")
          .mkString("|")
      }
      .mkString("\n")
    new Complete(
      o,
      c.id,
      frozen,
      deltas,
      rewards,
      balances,
      pots,
      hash(s"effect:${c.id.hex}:${frozen.id.hex}:$deltas:$rs")
    )
  }
  def preview(
      o: Owner,
      current: Context,
      next: Signal,
      rewards: RewardPhase
  ): Either[String, Preview] = checked {
    own(o, current)
    require(
      next != null && (next.owner eq o) && next.contextId == current.id &&
        next.beforeRevision == current.stake.revision,
      "stale/foreign boundary signal"
    )
    val (balances, pots, rewardId) = rewards match
      case RewardPhase.Unknown =>
        throw new IllegalArgumentException("unknown reward phase unsupported")
      case RewardPhase.Pulsing(_) =>
        throw new IllegalArgumentException("pulsing requires checked completion; never zero")
      case RewardPhase.Absent(a) =>
        require(
          a != null && (a.owner eq o) && a.contextId == current.id,
          "stale/foreign absence evidence"
        )
        (
          current.stake.context.accounts.map((c, a) => c -> a.balance),
          current.pots,
          hash(s"absent:${a.evidenceId.hex}")
        )
      case RewardPhase.Completed(e) =>
        require(
          e != null && (e.owner eq o) && e.contextId == current.id,
          "stale/foreign complete reward effect"
        )
        (e.balances, e.pots, e.id)
    val rotation = get(
      Stake.previewRotationAfterRewards(
        current.stakeOwner,
        current.stake,
        next.headerHash,
        next.slot,
        pots.fees,
        balances
      )
    )
    val identity = hash(
      s"preview:${current.id.hex}:${next.headerHash.hex}:${next.slot}:${rewardId.hex}:$pots:${balanceText(balances)}:" +
        s"${Stake.snapshotIdentity(rotation.snapshots.mark).hex}:${Stake.snapshotIdentity(rotation.snapshots.set).hex}:${Stake.snapshotIdentity(rotation.snapshots.go).hex}"
    )
    new Preview(current, next, balances, pots, rotation, rewardId, identity)
  }
