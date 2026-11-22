// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.Order
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Credential as FlatCredential,
  GovActionId,
  GovActionState as StoredGovActionState,
  PrevIds,
  Proposal,
  Proposals as StoredProposals,
  Vote
}
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.era.{
  EraTxError,
  GovActionState,
  GovPurposeId,
  GovRelation,
  ProposalProcedure,
  Proposals,
  ProposalsReadError
}
import xyz.kd5ujc.scarab.ledger.state.{
  Conway,
  Credential,
  DRepRole,
  EpochNo,
  EraTag,
  HotCommittee,
  KeyHash,
  Sorted,
  StakePool,
  VocabError
}

/** Conway's proposals read from the node's record of them and written back (design §3.6: what is
  * stored does not change). The read is the reference's `mkProposals` over the record's roots and
  * actions in order; each stored proposal is read as Conway's decoder reads the proposal it was
  * stored from ([[ConwayProcedures.proposal]]), the only other way a Conway proposal is made.
  */
object ConwayProposals:
  /** The roots the node records ([[PrevIds]]) as the reference's `GovRelation StrictMaybe`. */
  def roots(ids: PrevIds): GovRelation[Option] =
    GovRelation[Option](
      ids.paramUpdate.map(GovPurposeId(_)),
      ids.hardFork.map(GovPurposeId(_)),
      ids.committee.map(GovPurposeId(_)),
      ids.constitution.map(GovPurposeId(_))
    )

  /** The reference's roots as the node records them, the inverse of [[roots]]. */
  def prevIds(r: GovRelation[Option]): PrevIds =
    PrevIds(
      r.grPParamUpdate.map(_.unGovPurposeId),
      r.grHardFork.map(_.unGovPurposeId),
      r.grCommittee.map(_.unGovPurposeId),
      r.grConstitution.map(_.unGovPurposeId)
    )

  private def votes[K, T: Order](
      m: Map[K, Vote],
      key: K => Either[VocabError, T]
  ): Either[VocabError, SortedMap[T, Vote]] =
    m.toVector.traverse((k, v) => key(k).map(_ -> v)).map(Sorted.map(_))

  /** A stored action's state read into era `E` 's, its proposal read by `proposal`: each vote keyed
    * by its voter's role, and its epochs. Every era whose proposals the node records as Conway's
    * flat proposal reads them so.
    */
  private[era] def actionWith[E <: EraTag, P](
      stored: StoredGovActionState,
      proposal: Proposal => Either[P, ProposalProcedure[E]]
  )(wrap: (GovActionId, P) => ProposalsReadError): Either[ProposalsReadError, GovActionState[E]] =
    proposal(stored.proposal)
      .leftMap(wrap(stored.id, _))
      .flatMap(p =>
        actionOf[E](
          stored.id,
          stored.committeeVotes,
          stored.drepVotes,
          stored.poolVotes,
          p,
          stored.proposedIn,
          stored.expiresAfter
        )
      )

  /** An action's state of era `E` from the items the node's record of one holds, with its proposal
    * `p` already the era's: each vote keyed by its voter's role, and its epochs. What the Haskell
    * state codec reads a Dijkstra state's actions with.
    */
  def actionOf[E <: EraTag](
      id: GovActionId,
      committeeVotes: Map[FlatCredential, Vote],
      drepVotes: Map[FlatCredential, Vote],
      poolVotes: Map[Bytes, Vote],
      p: ProposalProcedure[E],
      proposedIn: BigInt,
      expiresAfter: BigInt
  ): Either[ProposalsReadError, GovActionState[E]] =
    for
      committee <- votes[FlatCredential, Credential[HotCommittee]](
        committeeVotes,
        Convert.credential[HotCommittee](_)
      ).leftMap(ProposalsReadError.StoredVoter(id, _))
      dreps <- votes[FlatCredential, Credential[DRepRole]](
        drepVotes,
        Convert.credential[DRepRole](_)
      ).leftMap(ProposalsReadError.StoredVoter(id, _))
      pools <- votes[Bytes, KeyHash[StakePool]](poolVotes, KeyHash.fromBytes[StakePool](_))
        .leftMap(ProposalsReadError.StoredVoter(id, _))
      epochs <- (EpochNo.fromBigInt(proposedIn), EpochNo.fromBigInt(expiresAfter)).tupled
        .leftMap(ProposalsReadError.StoredEpoch(id, _))
    yield GovActionState[E](id, committee, dreps, pools, p, epochs._1, epochs._2)

  /** Era `E` 's action state as the node records it, with `proposal` the flat proposal of its own:
    * the inverse of [[actionWith]].
    */
  private[era] def storedWith[E <: EraTag](
      gas: GovActionState[E],
      proposal: Proposal
  ): StoredGovActionState =
    StoredGovActionState(
      gas.gasId,
      gas.gasCommitteeVotes.iterator.map((c, v) => Convert.back(c) -> v).toMap,
      gas.gasDRepVotes.iterator.map((c, v) => Convert.back(c) -> v).toMap,
      gas.gasStakePoolVotes.iterator.map((k, v) => k.bytes -> v).toMap,
      proposal,
      gas.gasProposedIn.toBigInt,
      gas.gasExpiresAfter.toBigInt
    )

  /** A stored action's state as Conway's, its proposal read as Conway's decoder reads the one it
    * was stored from ([[ConwayProcedures.proposal]]).
    */
  def action(stored: StoredGovActionState): Either[ProposalsReadError, GovActionState[Conway]] =
    actionWith[Conway, EraTxError](stored, ConwayProcedures.proposal)(
      ProposalsReadError.StoredProposal(_, _)
    )

  /** Conway's action state as the node records it, with the proposal it was read as. */
  def storedAction(gas: GovActionState[Conway]): StoredGovActionState =
    storedWith(gas, gas.gasProposalProcedure.read)

  /** The node's record of the proposals as Conway's [[Proposals]]. */
  def fromStored(stored: StoredProposals): Either[ProposalsReadError, Proposals[Conway]] =
    stored.actions
      .traverse(action)
      .flatMap(Proposals.mkProposals[Conway](roots(stored.roots), _))

  /** Conway's [[Proposals]] as the node records them: the roots and the actions in order, each with
    * the proposal it was read as.
    */
  def toStored(ps: Proposals[Conway]): StoredProposals =
    StoredProposals(
      ps.pProps.toVector.map(storedAction),
      prevIds(Proposals.toPrevGovActionIds(ps.pRoots))
    )
