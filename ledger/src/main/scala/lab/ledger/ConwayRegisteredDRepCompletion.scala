// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.Blake2b
import lab.cbor.Bytes
import ConwayEmptyGovernance as G

/** Pure registered-DRep completion of one captured empty-proposal boundary. No live authority. */
object ConwayRegisteredDRepCompletion:
  val Profile = "conway-pv9-supplied-registered-drep-completion-v1"
  enum Failure:
    case SourceMismatch, EpochMismatch, UnsupportedGovernance, UnsupportedDelegation,
      PoolDomainMismatch, ArithmeticOverflow

  final class Completed private[ConwayRegisteredDRepCompletion] (
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

  /** Completes the captured empty-proposal pulser with registered and special voting targets.
    * Native computeDRepDistr includes instantaneous stake plus reward balance, not account/DRep
    * deposits, and ignores unregistered credential targets. It does not filter by DRep expiry;
    * activity is a RATIFY decision and an empty action signal has no voting threshold decision.
    * Supplied pool domain is preserved exactly, with the same stronger full-domain restriction as
    * the empty completion component. No fresh account/registry data is supplied separately.
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
      if fresh.index != 0 || fresh.drepDistribution.nonEmpty ||
        fresh.proposals.nonEmpty || fresh.proposalDeposits.nonEmpty || fresh.enact.withdrawals.nonEmpty
      then Left(Failure.UnsupportedGovernance)
      else if fresh.stakePoolDistribution.pools.keySet != fresh.stakePools.keySet then
        Left(Failure.PoolDomainMismatch)
      else
        val distribution = fresh.accounts.toVector.foldLeft(Map.empty[G.Vote, BigInt]) {
          case (acc, (credential, account)) =>
            account.vote match
              case Some(vote) if (vote match
                    case G.Vote.Credential(drep) => fresh.dreps.contains(drep)
                    case _                       => true
                  ) =>
                val amount = fresh.instantaneous.getOrElse(credential, BigInt(0)) + account.rewards
                acc.updated(vote, acc.getOrElse(vote, BigInt(0)) + amount)
              case _ => acc
        }
        val maxCoin = (BigInt(1) << 64) - 1
        if distribution.values.exists(n => n < 0 || n > maxCoin) then
          return Left(Failure.ArithmeticOverflow)
        val snapshot = G.CompletedSnapshot(
          Vector.empty,
          distribution,
          fresh.dreps,
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
