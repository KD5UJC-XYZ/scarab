// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.Blake2b
import lab.cbor.Bytes
import scala.util.control.NonFatal
import ConwayEpochBoundary as B
import ConwayStake as S

/** Pure completion monetary equations over explicit frozen inputs; no pulser or entitlement. */
object ConwayRewardCompletion:
  val Profile = "conway-pv9-supplied-completion-monetary-v1"
  private val Max = (BigInt(1) << 64) - 1
  final class Inputs private[ConwayRewardCompletion] (
      val frozen: B.Frozen,
      val snapshotFees: BigInt,
      val deltaR1: BigInt,
      val rewardPot: BigInt,
      val treasuryDelta: BigInt,
      val id: Bytes
  )
  final class Completed private[ConwayRewardCompletion] (
      val inputs: Inputs,
      val rewards: Map[S.Credential, Set[B.Reward]],
      val totalRewards: BigInt,
      val deltaR2: BigInt,
      val deltas: B.Deltas,
      val id: Bytes
  ):
    val nativeEntitlementValidated = false
    val pulserCompleted = false
    val nonMyopicUpdated = false
  private def checked[A](a: => A): Either[String, A] =
    try Right(a)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  private def coin(n: BigInt) = n != null && n >= 0 && n <= Max
  private def width(b: Bytes, n: Int) = b != null && b.value != null && b.size == n
  private def hash(text: String) =
    Blake2b.hash256.hash(Bytes.fromArray((Profile + "\n" + text).getBytes("UTF-8")))

  /** Checks a supplied frozen allocation, not the rho/tau/eta calculation that produced it. */
  def checkedInputs(
      frozen: B.Frozen,
      expectedFrozenId: Bytes,
      snapshotFees: BigInt,
      deltaR1: BigInt,
      rewardPot: BigInt,
      treasuryDelta: BigInt
  ): Either[String, Inputs] = checked {
    require(
      frozen != null && width(expectedFrozenId, 32) && frozen.id == expectedFrozenId,
      "frozen input identity mismatch"
    )
    require(
      Vector(snapshotFees, deltaR1, rewardPot, treasuryDelta).forall(coin),
      "completion input coin bounds"
    )
    require(snapshotFees == frozen.snapshotFees, "fee snapshot mismatch")
    require(deltaR1 <= frozen.reserves, "reserve contribution exceeds frozen reserves")
    require(
      snapshotFees + deltaR1 <= Max && rewardPot + treasuryDelta == snapshotFees + deltaR1,
      "frozen allocation conservation/bounds"
    )
    new Inputs(
      frozen,
      snapshotFees,
      deltaR1,
      rewardPot,
      treasuryDelta,
      hash(s"inputs:${frozen.id.hex}:$snapshotFees:$deltaR1:$rewardPot:$treasuryDelta")
    )
  }

  /** Native completion supplies at most one member reward per credential and leader reward sets.
    * Types and set identities are checked before combining; internal digest order is not CBOR.
    */
  def complete(
      inputs: Inputs,
      expectedInputsId: Bytes,
      members: Map[S.Credential, B.Reward],
      leaders: Map[S.Credential, Set[B.Reward]]
  ): Either[String, Completed] = checked {
    require(
      inputs != null && width(expectedInputsId, 32) && inputs.id == expectedInputsId,
      "completion input identity mismatch"
    )
    require(
      members != null && members.size <= 4096 && leaders != null && leaders.size <= 4096 &&
        leaders.values
          .forall(_ != null) && members.size.toLong + leaders.values.map(_.size.toLong).sum <= 4096,
      "completion reward set bound"
    )
    require(
      members.values.forall(r => r != null && r.kind == B.RewardKind.Member) &&
        leaders.values.forall(_.forall(r => r != null && r.kind == B.RewardKind.Leader)),
      "member/leader type mismatch"
    )
    val rewards = (members.keySet ++ leaders.keySet).map { c =>
      c -> (leaders.getOrElse(c, Set.empty) ++ members.get(c).toSet)
    }.toMap
    val total = get(ConwayRewardApplication.sumChecked(rewards))
    require(total <= inputs.rewardPot, "rewards exceed frozen reward pot")
    val remainder = inputs.rewardPot - total
    val deltas = B.Deltas(inputs.treasuryDelta, -inputs.deltaR1 + remainder, -inputs.snapshotFees)
    require(
      Vector(deltas.treasury, deltas.reserves, deltas.fees).forall(n => n >= -Max && n <= Max) &&
        deltas.treasury + deltas.reserves + deltas.fees + total == 0,
      "completed monetary conservation"
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
    new Completed(
      inputs,
      rewards,
      total,
      remainder,
      deltas,
      hash(s"completed:${inputs.id.hex}:$rs:$total:$remainder:$deltas")
    )
  }
