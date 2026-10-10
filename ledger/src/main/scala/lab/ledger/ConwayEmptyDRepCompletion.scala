// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.Blake2b
import lab.cbor.Bytes
import ConwayEmptyGovernance as G

/** Pure completion of one captured, supplied empty-governance boundary. No live authority. */
object ConwayEmptyDRepCompletion:
  val Profile = "conway-pv9-supplied-empty-drep-completion-v1"
  enum Failure:
    case SourceMismatch, EpochMismatch, UnsupportedGovernance, UnsupportedDelegation,
      PoolDomainMismatch

  final class Completed private[ConwayEmptyDRepCompletion] (
      val source: G.Applied,
      val snapshot: G.CompletedSnapshot,
      val ratify: G.Ratify,
      val id: Bytes
  ):
    val epoch = source.epoch
    val chainTreasury = source.treasury
    val syntheticOnly = true
    val nativePayloadsValidated = false
    val epochTransitionValidated = false
    val repeatedEpochsValidated = false
    val published = false

  private def sourceMatches(source: G.Applied, expectedId: Bytes): Boolean =
    source != null && expectedId != null && expectedId.value != null &&
      expectedId.size == 32 && source.id == expectedId

  /** Accounts and instantaneous stake remain captured unchanged. No DRep votes or registrations are
    * supported. Empty proposal deposits make native computeDRepDistr preserve the supplied mark
    * pool distribution exactly. Requiring its domain to equal the registered pool domain is a
    * deliberately stronger restriction than native completion; omitted pools are never invented.
    * Parameters/committee/constitution/roots come only from the genuine Applied's fresh enactment.
    */
  def complete(
      source: G.Applied,
      expectedAppliedId: Bytes,
      expectedEpoch: BigInt
  ): Either[Failure, Completed] =
    if !sourceMatches(source, expectedAppliedId) then Left(Failure.SourceMismatch)
    else if expectedEpoch == null || expectedEpoch != source.epoch ||
      source.fresh.epoch != source.epoch
    then Left(Failure.EpochMismatch)
    else
      val fresh = source.fresh
      if fresh.index != 0 || fresh.drepDistribution.nonEmpty || fresh.dreps.nonEmpty ||
        fresh.proposals.nonEmpty || fresh.proposalDeposits.nonEmpty || fresh.enact.withdrawals.nonEmpty
      then Left(Failure.UnsupportedGovernance)
      else if fresh.accounts.values.exists(_.vote.nonEmpty) then Left(Failure.UnsupportedDelegation)
      else if fresh.stakePoolDistribution.pools.keySet != fresh.stakePools.keySet then
        Left(Failure.PoolDomainMismatch)
      else
        val snapshot = G.CompletedSnapshot(
          Vector.empty,
          Map.empty,
          Map.empty,
          fresh.stakePoolDistribution.pools.map((pool, share) => pool -> share.stake)
        )
        // Pinned Conway RATIFY Empty clears enactment treasury, not chain treasury.
        val ratify = G.Ratify(fresh.enact.copy(treasury = 0), Vector.empty, Set.empty, false)
        val id = Blake2b.hash256.hash(
          Bytes.fromArray(
            (Profile + "\n" + source.id.hex + ":" + source.epoch).getBytes("UTF-8")
          )
        )
        Right(new Completed(source, snapshot, ratify, id))

  /** Reject reuse against a different captured application, even an equal reconstructed value. This
    * checks capture identity only, not intervening chain/governance freshness or epoch ingress.
    */
  def forSource(
      completed: Completed,
      source: G.Applied,
      expectedAppliedId: Bytes,
      expectedEpoch: BigInt
  ): Either[Failure, G.OldDRep] =
    if completed == null || !sourceMatches(source, expectedAppliedId) ||
      !(completed.source eq source)
    then Left(Failure.SourceMismatch)
    else if expectedEpoch == null || expectedEpoch != completed.epoch then
      Left(Failure.EpochMismatch)
    else Right(G.OldDRep.Complete(completed.snapshot, completed.ratify))
