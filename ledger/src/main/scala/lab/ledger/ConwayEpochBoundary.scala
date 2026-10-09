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
      val application: Stake.Context,
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
      val id: Bytes,
      val rewardParameters: Option[ConwayRewardStart.Parameters] = None,
      val rewardGlobals: Option[ConwayRewardStart.Globals] = None,
      private[ConwayEpochBoundary] val successorApplication: Option[
        (BigInt, Bytes, BigInt, Map[Bytes, BigInt])
      ] = None
  ):
    val epoch = successorApplication.fold(start.stake.epoch)(_._1)
    val epochLength = start.stake.context.epochLength
    val preTickTupleId = start.tupleId
    val go = start.stake.snapshots.go
    val snapshotFees = start.stake.snapshots.fees
    val previousBlocks = start.previousBlocks
    val registeredAccounts = start.application.accounts
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
      val applied: ConwayRewardApplication.Applied,
      val id: Bytes,
      val completionIdentity: Option[Bytes] = None
  ):
    val balances = applied.balances
    val pots = applied.pots
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
      val rewardApplication: Option[ConwayRewardApplication.Applied],
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
  private def own(o: Owner, c: Context): Unit =
    require(o != null && c != null && (o eq c.owner), "foreign boundary context")
  private def counts(m: Map[Bytes, BigInt]): Unit =
    require(
      m != null && m.size <= 4096 && m.forall((p, n) => width(p, 28) && coin(n)) &&
        m.values.sum <= Max,
      "block count bounds"
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
    require(state != null, "boundary context shape")
    get(
      contextAtApplication(
        o,
        stakeOwner,
        tupleId,
        state,
        state.context,
        pots,
        previousBlocks,
        currentBlocks
      )
    )
  }

  /** Explicit supplied application-time account view, not validation of registration changes. */
  def contextAtApplication(
      o: Owner,
      stakeOwner: Stake.Owner,
      tupleId: Bytes,
      state: Stake.State,
      application: Stake.Context,
      pots: Pots,
      previousBlocks: Map[Bytes, BigInt],
      currentBlocks: Map[Bytes, BigInt]
  ): Either[String, Context] = checked {
    require(
      o != null && width(tupleId, 32) && state != null && application != null,
      "boundary context shape"
    )
    require(
      application.epochLength == state.context.epochLength &&
        application.pools.map((p, v) => p -> v.copy(delegators = Set.empty)) ==
        state.context.pools.map((p, v) => p -> v.copy(delegators = Set.empty)),
      "application pool parameters/epoch geometry changed"
    )
    validPots(pots); counts(previousBlocks); counts(currentBlocks)
    require(
      state.epoch < Max && (state.epoch + 1) * state.context.epochLength <= Max,
      "epoch bound"
    )
    // Reuse the opaque stake owner's existing check; this creates only an unpublished rotation.
    get(
      Stake.previewRotationAfterRewards(
        stakeOwner,
        state,
        tupleId,
        (state.epoch + 1) * state.context.epochLength,
        pots.fees,
        application.accounts.map((c, a) => c -> a.balance),
        application
      )
    )
    val held = state.utxo.values.map(_.coin).sum + application.accounts.values
      .map(a => a.balance + a.deposit)
      .sum + application.pools.values.map(_.deposit).sum
    require(
      held + pots.treasury + pots.reserves + pots.fees <= pots.maxSupply,
      "tracked supply exceeds maximum"
    )
    new Context(
      o,
      stakeOwner,
      tupleId,
      state,
      application,
      pots,
      previousBlocks,
      currentBlocks,
      hash(
        s"context:${tupleId.hex}:${state.id.hex}:${state.revision}:${application.id.hex}:$pots:${countText(previousBlocks)}:${countText(currentBlocks)}"
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

  /** Scoped checked parameter/global bytes are bound at freezing, never supplied at calculation. */
  def freezeForAllocation(
      o: Owner,
      preTick: Context,
      observedSlot: BigInt,
      window: BigInt,
      parameters: ConwayRewardStart.Parameters,
      globals: ConwayRewardStart.Globals
  ): Either[String, Frozen] = checked {
    own(o, preTick)
    require(
      parameters != null && globals != null &&
        globals.epochLength == preTick.stake.context.epochLength && globals.maxSupply == preTick.pots.maxSupply,
      "reward-start globals differ from frozen context"
    )
    val base = get(freeze(o, preTick, observedSlot, window, parameters.original))
    new Frozen(
      o,
      preTick,
      observedSlot,
      window,
      parameters.original,
      hash(s"allocation-frozen:${base.id.hex}:${parameters.id.hex}:${globals.id.hex}"),
      Some(parameters),
      Some(globals)
    )
  }

  /** TICK captures the reward environment before NEWEPOCH but RUPD uses the actual signal's epoch
    * timing. The owned coordinator supplies its selected post-boundary stake; no old frozen cursor
    * is reused. Only immutable bounded source components survive, never recursive history.
    */
  private[lab] def freezeAfterBoundary(
      o: Owner,
      preview: Preview,
      postStake: Stake.State,
      window: BigInt,
      parameters: ConwayRewardStart.Parameters,
      globals: ConwayRewardStart.Globals
  ): Either[String, Frozen] = checked {
    require(preview != null && postStake != null, "boundary/post stake required")
    val pre = preview.preTickRewardEnvironment
    own(o, pre)
    val slot = preview.signal.slot
    require(
      postStake.epoch == preview.epoch && postStake.slot == slot &&
        postStake.revision == pre.stake.revision + 1 &&
        postStake.context.accounts.map((c, a) => c -> a.balance) == preview.balances &&
        postStake.snapshots == preview.rotation.snapshots && parameters != null && globals != null &&
        globals.epochLength == pre.stake.context.epochLength && globals.maxSupply == pre.pots.maxSupply,
      "selected successor stake/reward globals mismatch"
    )
    require(
      get(rewardTiming(preview.epoch * globals.epochLength, window, slot)) != Timing.TooEarly,
      "post-boundary reward start too early"
    )
    new Frozen(
      o,
      pre,
      slot,
      window,
      parameters.original,
      hash(
        s"post-boundary-frozen:${preview.id.hex}:${postStake.context.id.hex}:$slot:$window:${parameters.id.hex}:${globals.id.hex}"
      ),
      Some(parameters),
      Some(globals),
      Some((preview.epoch, postStake.context.id, preview.pots.reserves, preview.previousBlocks))
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
        frozen.epoch == c.stake.epoch && frozen.successorApplication.fold(
          frozen.start.stake.context.id
        )(_._2) == c.stake.context.id &&
        frozen.start.stake.revision <= c.stake.revision && frozen.observedSlot <= c.stake.slot &&
        frozen.maxSupply == c.pots.maxSupply && frozen.successorApplication.fold(frozen.reserves)(
          _._3
        ) == c.pots.reserves &&
        frozen.successorApplication.fold(frozen.previousBlocks)(_._4) == c.previousBlocks,
      "foreign/stale frozen reward environment"
    )
    val applied =
      get(ConwayRewardApplication.applyPv9(c.application.accounts, c.pots, deltas, rewards))
    new Complete(
      o,
      c.id,
      frozen,
      deltas,
      rewards,
      applied,
      hash(s"effect:${c.id.hex}:${frozen.id.hex}:${applied.id.hex}")
    )
  }

  /** Derived monetary completion only; inputs/entitlements remain supplied, not native proof. */
  def completeFromFrozen(
      o: Owner,
      c: Context,
      completed: ConwayRewardCompletion.Completed
  ): Either[String, Complete] = checked {
    require(completed != null, "completion required")
    val applied =
      get(syntheticComplete(o, c, completed.inputs.frozen, completed.deltas, completed.rewards))
    new Complete(
      o,
      c.id,
      completed.inputs.frozen,
      completed.deltas,
      completed.rewards,
      applied.applied,
      hash(s"derived-completion:${applied.id.hex}:${completed.id.hex}"),
      Some(completed.id)
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
    val (balances, pots, rewardId, applicationResult) = rewards match
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
          current.application.accounts.map((c, a) => c -> a.balance),
          current.pots,
          hash(s"absent:${a.evidenceId.hex}"),
          None
        )
      case RewardPhase.Completed(e) =>
        require(
          e != null && (e.owner eq o) && e.contextId == current.id,
          "stale/foreign complete reward effect"
        )
        (e.balances, e.pots, e.id, Some(e.applied))
    val rotation = get(
      Stake.previewRotationAfterRewards(
        current.stakeOwner,
        current.stake,
        next.headerHash,
        next.slot,
        pots.fees,
        balances,
        current.application
      )
    )
    val identity = hash(
      s"preview:${current.id.hex}:${next.headerHash.hex}:${next.slot}:${rewardId.hex}:$pots:${balanceText(balances)}:" +
        s"${Stake.snapshotIdentity(rotation.snapshots.mark).hex}:${Stake.snapshotIdentity(rotation.snapshots.set).hex}:${Stake.snapshotIdentity(rotation.snapshots.go).hex}"
    )
    new Preview(current, next, balances, pots, rotation, rewardId, applicationResult, identity)
  }
