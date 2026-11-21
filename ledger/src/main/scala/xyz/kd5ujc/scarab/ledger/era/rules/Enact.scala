// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.Order
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.GovActionId
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{
  AccountAddress,
  Coin,
  ColdCommittee,
  Credential,
  EpochNo,
  EraTag,
  Sorted,
  Staking,
  VocabError
}

/** `data EnactSignal era` (`Conway/Rules/Enact.hs:57-61`, cardano-ledger f649f975): the id of a
  * ratified action and the action.
  */
final case class EnactSignal[E <: EraTag](esGovActionId: GovActionId, esGovAction: GovAction[E])

/** Conway's ENACT (cardano-ledger f649f975
  * `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Enact.hs`, the same at 9df9f98c), the ENACT of
  * both eras from Conway (`Conway/Era.hs`, `Dijkstra/Era.hs:208`, 9df9f98c), generic in its era
  * over the reference's classes. The rule has no predicate failure and no event (`Void`, `:77`, and
  * `VoidEraRule "ENACT"`, `:55`): it is a function of the enact state. What remains is a value of
  * the node's records outside the reference's types, the committee the enact state holds or a
  * withdrawal's account, a [[VocabError]]. The committee's is not reached from a state the node's
  * codec read, which bounds its credentials, epochs and threshold as the reference's types do. The
  * account's is: the node's stored codec of an account takes any network of the header's low
  * nibble, 0 to 15, where the reference's decoder takes 0 and 1 only (`Address.hs:930-946`,
  * 9df9f98c), so an imported state can hold a withdrawal to network 2, which ENACT refuses as
  * `VocabError.OutOfRange` of the network.
  */
object ConwayEnact:
  /** `enactmentTransition` (`Enact.hs:83-110`, cardano-ledger f649f975): the effect of one ratified
    * action on the enact state, and the action's id as the last enacted of its purpose.
    */
  def enactmentTransition[E <: EraTag](st: EnactState[E], signal: EnactSignal[E])(using
      p: EraPParams[E]
  ): Either[VocabError, EnactState[E]] =
    val EnactSignal(govActionId, act) = signal
    def prev[P <: GovActionPurpose](field: GovRelationL[P]): GovRelation[Option] =
      field.set[Option](st.ensPrevGovActionIds, Some(GovPurposeId[P](govActionId)))
    act match
      case GovAction.ParameterChange(_, ppu, _) =>
        Right(
          st.copy(
            ensCurPParams = p.applyPPUpdates(st.ensCurPParams, ppu),
            ensPrevGovActionIds = prev(GovRelation.grPParamUpdateL)
          )
        )
      case GovAction.HardForkInitiation(_, pv) =>
        Right(
          st.copy(
            ensCurPParams = p.ppProtocolVersionL.replace(pv)(st.ensCurPParams),
            ensPrevGovActionIds = prev(GovRelation.grHardForkL)
          )
        )
      case GovAction.TreasuryWithdrawals(wdrls, _) =>
        val wdrlsAmount = Coin(wdrls.valuesIterator.map(_.value).sum)
        withdrawalsNoNetworkId(wdrls).map(noNetworkId =>
          st.copy(
            ensWithdrawals = noNetworkId.foldLeft(st.ensWithdrawals) { case (m, (c, amount)) =>
              m.updated(c, m.get(c).fold(amount)(held => Coin(amount.value + held.value)))
            },
            ensTreasury = Coin(st.ensTreasury.value - wdrlsAmount.value)
          )
        )
      case GovAction.NoConfidence(_) =>
        Right(st.copy(ensCommittee = None, ensPrevGovActionIds = prev(GovRelation.grCommitteeL)))
      case GovAction.UpdateCommittee(_, membersToRemove, membersToAdd, newThreshold) =>
        st.ensCommittee
          .traverse(Committee.fromStored[E])
          .map(committee =>
            st.copy(
              ensCommittee = Some(
                Committee.toStored(
                  updatedCommittee(membersToRemove, membersToAdd, newThreshold, committee)
                )
              ),
              ensPrevGovActionIds = prev(GovRelation.grCommitteeL)
            )
          )
      case GovAction.NewConstitution(_, c) =>
        Right(
          st.copy(ensConstitution = c, ensPrevGovActionIds = prev(GovRelation.grConstitutionL))
        )
      case GovAction.InfoAction() => Right(st)

  /** `Map.mapKeys (^. accountAddressCredentialL) wdrls` (`Enact.hs:96`, cardano-ledger f649f975):
    * the withdrawals by their accounts' credentials, each read at the reference's `AccountAddress`;
    * of accounts of one credential, the value of the greatest account in the reference's order (its
    * network, then its credential) is kept, as `mapKeys` keeps it.
    */
  private def withdrawalsNoNetworkId(
      wdrls: Map[xyz.kd5ujc.scarab.ledger.RewardAccount, Coin]
  ): Either[VocabError, SortedMap[Credential[Staking], Coin]] =
    given Ordering[AccountAddress] =
      Order
        .by[AccountAddress, (Int, Credential[Staking])](a => (a.networkId.wire, a.id.credential))
        .toOrdering
    wdrls.toVector
      .traverse((account, amount) => Convert.accountAddress(account).map(_ -> amount))
      .map(read =>
        Sorted.map(read.sortBy(_._1).map((account, amount) => account.id.credential -> amount))
      )

  /** `updatedCommittee` (`Enact.hs:118-133`, cardano-ledger f649f975): the members added over those
    * kept, a member both removed and added being added, with the new threshold.
    */
  def updatedCommittee[E <: EraTag](
      membersToRemove: Set[Credential[ColdCommittee]],
      membersToAdd: Map[Credential[ColdCommittee], EpochNo],
      newThreshold: UnitInterval,
      committee: Option[Committee[E]]
  ): Committee[E] =
    committee match
      case None => Committee[E](Sorted.map(membersToAdd), newThreshold)
      case Some(Committee(currentMembers, _)) =>
        Committee[E](
          (currentMembers -- membersToRemove) ++ membersToAdd,
          newThreshold
        )
