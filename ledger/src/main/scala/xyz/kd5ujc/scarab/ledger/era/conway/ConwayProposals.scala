// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{
  GovActionState as StoredGovActionState,
  PrevIds,
  Proposals as StoredProposals
}
import xyz.kd5ujc.scarab.ledger.era.{
  GovActionState,
  GovPurposeId,
  GovRelation,
  Proposals,
  ProposalsReadError
}
import xyz.kd5ujc.scarab.ledger.state.{Conway, EpochNo}

/** Conway's proposals read from the node's record of them and written back (design §3.6: what is
  * stored does not change). The read is the reference's `mkProposals` over the record's roots and
  * actions in order; each stored proposal is read as Conway's decoder reads the proposal it was
  * stored from ([[ConwayProcedures.proposal]]), the only other way a Conway proposal is made.
  */
object ConwayProposals:
  private def roots(ids: PrevIds): GovRelation[Option] =
    GovRelation[Option](
      ids.paramUpdate.map(GovPurposeId(_)),
      ids.hardFork.map(GovPurposeId(_)),
      ids.committee.map(GovPurposeId(_)),
      ids.constitution.map(GovPurposeId(_))
    )

  private def action(
      stored: StoredGovActionState
  ): Either[ProposalsReadError, GovActionState[Conway]] =
    for
      proposal <- ConwayProcedures
        .proposal(stored.proposal)
        .leftMap(ProposalsReadError.StoredProposal(stored.id, _))
      epochs <- (
        EpochNo.fromBigInt(stored.proposedIn),
        EpochNo.fromBigInt(stored.expiresAfter)
      ).tupled
        .leftMap(ProposalsReadError.StoredEpoch(stored.id, _))
    yield GovActionState[Conway](
      stored.id,
      stored.committeeVotes,
      stored.drepVotes,
      stored.poolVotes,
      proposal,
      epochs._1,
      epochs._2
    )

  /** The node's record of the proposals as Conway's [[Proposals]]. */
  private[ledger] def fromStored(
      stored: StoredProposals
  ): Either[ProposalsReadError, Proposals[Conway]] =
    stored.actions
      .traverse(action)
      .flatMap(
        Proposals
          .mkProposals[Conway](roots(stored.roots), _)
      )

  /** Conway's [[Proposals]] as the node records them: the roots and the actions in order, each with
    * the proposal it was read as.
    */
  def toStored(ps: Proposals[Conway]): StoredProposals =
    val r = Proposals.toPrevGovActionIds(ps.pRoots)
    StoredProposals(
      ps.pProps.toVector.map(gas =>
        StoredGovActionState(
          gas.gasId,
          gas.gasCommitteeVotes,
          gas.gasDRepVotes,
          gas.gasStakePoolVotes,
          gas.gasProposalProcedure.read,
          gas.gasProposedIn.toBigInt,
          gas.gasExpiresAfter.toBigInt
        )
      ),
      PrevIds(
        r.grPParamUpdate.map(_.unGovPurposeId),
        r.grHardFork.map(_.unGovPurposeId),
        r.grCommittee.map(_.unGovPurposeId),
        r.grConstitution.map(_.unGovPurposeId)
      )
    )
