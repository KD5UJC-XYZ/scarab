// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Credential as FlatCredential,
  GovActionId,
  RewardAccount,
  Vote,
  Voter
}
import xyz.kd5ujc.scarab.ledger.state.{Coin, EpochNo, EraTag}

// The governance actions proposed and not yet enacted, as the reference keeps them (cardano-ledger
// f649f975 `eras/conway/impl/src/Cardano/Ledger/Conway/Governance/Procedures.hs` and
// `Governance/Proposals.hs`, the same at 29a7d656): each action's state, by era as the reference's
// `GovActionState era` and `Proposals era` are, in an ordered map, and the four trees of the
// purposes that have a parent. These are the governance rule's state; the node keeps its own record
// of them (`xyz.kd5ujc.scarab.ledger.Proposals`), which each era reads in and writes back
// (`conway/ConwayProposals.scala`), so nothing persisted changes.

/** One field of a [[GovRelation]], the one of purpose `P`, for every `F`: the reference's rank-2
  * `forall f. Lens' (GovRelation f) (f (GovPurposeId p))` (`grPParamUpdateL` to `grConstitutionL`,
  * `Procedures.hs:769-779`, cardano-ledger f649f975).
  */
sealed trait GovRelationL[P <: GovActionPurpose]:
  def get[F[_]](r: GovRelation[F]): F[GovPurposeId[P]]
  def set[F[_]](r: GovRelation[F], v: F[GovPurposeId[P]]): GovRelation[F]
  final def modify[F[_]](r: GovRelation[F])(f: F[GovPurposeId[P]] => F[GovPurposeId[P]]) =
    set(r, f(get(r)))

/** `data GovRelation f` (`Procedures.hs:674-679`, cardano-ledger f649f975): one `F` of each of the
  * four purposes that have a parent.
  */
final case class GovRelation[F[_]](
    grPParamUpdate: F[GovPurposeId[GovActionPurpose.PParamUpdate]],
    grHardFork: F[GovPurposeId[GovActionPurpose.HardFork]],
    grCommittee: F[GovPurposeId[GovActionPurpose.Committee]],
    grConstitution: F[GovPurposeId[GovActionPurpose.Constitution]]
)

object GovRelation:
  val grPParamUpdateL: GovRelationL[GovActionPurpose.PParamUpdate] = new:
    def get[F[_]](r: GovRelation[F]) = r.grPParamUpdate
    def set[F[_]](r: GovRelation[F], v: F[GovPurposeId[GovActionPurpose.PParamUpdate]]) =
      r.copy(grPParamUpdate = v)

  val grHardForkL: GovRelationL[GovActionPurpose.HardFork] = new:
    def get[F[_]](r: GovRelation[F]) = r.grHardFork
    def set[F[_]](r: GovRelation[F], v: F[GovPurposeId[GovActionPurpose.HardFork]]) =
      r.copy(grHardFork = v)

  val grCommitteeL: GovRelationL[GovActionPurpose.Committee] = new:
    def get[F[_]](r: GovRelation[F]) = r.grCommittee
    def set[F[_]](r: GovRelation[F], v: F[GovPurposeId[GovActionPurpose.Committee]]) =
      r.copy(grCommittee = v)

  val grConstitutionL: GovRelationL[GovActionPurpose.Constitution] = new:
    def get[F[_]](r: GovRelation[F]) = r.grConstitution
    def set[F[_]](r: GovRelation[F], v: F[GovPurposeId[GovActionPurpose.Constitution]]) =
      r.copy(grConstitution = v)

  /** `hoistGovRelation` (`Procedures.hs:781-788`, cardano-ledger f649f975). */
  def hoistGovRelation[F[_], G[_]](
      f: [P <: GovActionPurpose] => F[GovPurposeId[P]] => G[
        GovPurposeId[P]
      ]
  )(gr: GovRelation[F]): GovRelation[G] =
    GovRelation[G](
      f(gr.grPParamUpdate),
      f(gr.grHardFork),
      f(gr.grCommittee),
      f(gr.grConstitution)
    )

/** `data PRoot a` (`Governance/Proposals.hs:173-179`, cardano-ledger f649f975): the root of one
  * purpose's tree, the last action of it enacted, and the actions that name it as their parent.
  */
final case class PRoot[A](prRoot: Option[A], prChildren: SortedSet[A])

/** `data PEdges a` (`Proposals.hs:191-197`, cardano-ledger f649f975): an action's parent and the
  * actions that name it as theirs.
  */
final case class PEdges[A](peParent: Option[A], peChildren: SortedSet[A])

/** `newtype PGraph a` (`Proposals.hs:200-204`, cardano-ledger f649f975): the edges of every action
  * of one purpose's tree.
  */
final case class PGraph[A](unPGraph: SortedMap[A, PEdges[A]])

/** `data GovActionState era` (`Procedures.hs:219-228`, cardano-ledger f649f975): a proposed action,
  * the votes cast on it, the epoch it was proposed in and the last epoch it can be ratified in. The
  * votes are keyed by what the era's voter ([[Voter]]) holds, its credential or pool key hash as
  * the node reads them, until the votes carry the reference's typed voter (#85).
  */
final case class GovActionState[E <: EraTag](
    gasId: GovActionId,
    gasCommitteeVotes: Map[FlatCredential, Vote],
    gasDRepVotes: Map[FlatCredential, Vote],
    gasStakePoolVotes: Map[Bytes, Vote],
    gasProposalProcedure: ProposalProcedure[E],
    gasProposedIn: EpochNo,
    gasExpiresAfter: EpochNo
):
  /** `gasAction` (`Procedures.hs:261-262`, cardano-ledger f649f975). */
  def gasAction: GovAction[E] = gasProposalProcedure.pProcGovAction

  /** `gasDeposit` (`Procedures.hs:249-250`, cardano-ledger f649f975). */
  def gasDeposit: Coin = gasProposalProcedure.pProcDeposit

  /** `gasReturnAddr` (`Procedures.hs:255-256`, cardano-ledger f649f975). */
  def gasReturnAddr: RewardAccount = gasProposalProcedure.pProcReturnAddr

object GovActionState:
  /** `withGovActionParent` (`Procedures.hs:791-811`, cardano-ledger f649f975): `f` of the field of
    * the action's purpose, its parent and its own id as one of that purpose, or `noParent` for an
    * action of no purpose with a parent.
    */
  def withGovActionParent[E <: EraTag, A](gas: GovActionState[E], noParent: => A)(
      f: GovActionParent[A]
  ): A =
    gas.gasAction match
      case GovAction.ParameterChange(parent, _, _) =>
        f(GovRelation.grPParamUpdateL, parent, GovPurposeId(gas.gasId))
      case GovAction.HardForkInitiation(parent, _) =>
        f(GovRelation.grHardForkL, parent, GovPurposeId(gas.gasId))
      case GovAction.TreasuryWithdrawals(_, _) => noParent
      case GovAction.NoConfidence(parent) =>
        f(GovRelation.grCommitteeL, parent, GovPurposeId(gas.gasId))
      case GovAction.UpdateCommittee(parent, _, _, _) =>
        f(GovRelation.grCommitteeL, parent, GovPurposeId(gas.gasId))
      case GovAction.NewConstitution(parent, _) =>
        f(GovRelation.grConstitutionL, parent, GovPurposeId(gas.gasId))
      case GovAction.InfoAction() => noParent

/** The function `withGovActionParent` applies, for every purpose `P`. */
trait GovActionParent[A]:
  def apply[P <: GovActionPurpose](
      field: GovRelationL[P],
      parent: Option[GovPurposeId[P]],
      self: GovPurposeId[P]
  ): A

/** `data Proposals era` (`Proposals.hs:238-243`, cardano-ledger f649f975): the actions' states in
  * the order they were proposed, and the four trees of the purposes with a parent: their roots and
  * the edges of their actions. Made only by [[Proposals.proposalsAddAction]] from an empty store
  * over its roots, so each action of a tree is in the ordered map and the tree's edges are its
  * actions' parents.
  */
final case class Proposals[E <: EraTag] private (
    pProps: OMap[GovActionId, GovActionState[E]],
    pRoots: GovRelation[PRoot],
    pGraph: GovRelation[PGraph]
)

object Proposals:
  private def emptyRoot[P <: GovActionPurpose](root: Option[GovPurposeId[P]]) =
    PRoot[GovPurposeId[P]](root, SortedSet.empty)

  private def emptyGraph[P <: GovActionPurpose] = PGraph[GovPurposeId[P]](SortedMap.empty)

  /** `def & pRootsL .~ fromPrevGovActionIds pgais` (`Proposals.hs:356`, cardano-ledger f649f975):
    * no action, over the roots `pgais`.
    */
  def empty[E <: EraTag](pgais: GovRelation[Option]): Proposals[E] =
    Proposals[E](
      OMap.empty,
      fromPrevGovActionIds(pgais),
      GovRelation[PGraph](emptyGraph, emptyGraph, emptyGraph, emptyGraph)
    )

  /** `fromPrevGovActionIds` (`Proposals.hs:605-606`, cardano-ledger f649f975). */
  def fromPrevGovActionIds(pgais: GovRelation[Option]): GovRelation[PRoot] =
    GovRelation.hoistGovRelation[Option, PRoot](
      [P <: GovActionPurpose] => (root: Option[GovPurposeId[P]]) => emptyRoot(root)
    )(pgais)

  /** `toPrevGovActionIds` (`Proposals.hs:602-603`, cardano-ledger f649f975). */
  def toPrevGovActionIds(roots: GovRelation[PRoot]): GovRelation[Option] =
    GovRelation.hoistGovRelation[PRoot, Option](
      [P <: GovActionPurpose] => (root: PRoot[GovPurposeId[P]]) => root.prRoot
    )(roots)

  /** `proposalsAddAction` (`Proposals.hs:297-303`) with `runProposalsAddAction` (`:305-336`,
    * cardano-ledger f649f975): `gas` added at the end of the ordered map, or in the place of the
    * action of its id; an action with a parent in its purpose's tree as a child of that parent, if
    * its parent is the tree's root or an action of the tree, the parent's children changed before
    * the new edge is inserted, as the reference composes them (`:330-333`). None otherwise. The
    * reference's `checkInvariantAfterAddition` is an assertion, compiled out of the node.
    */
  def proposalsAddAction[E <: EraTag](
      gas: GovActionState[E],
      ps: Proposals[E]
  ): Option[Proposals[E]] =
    val psWithGas = ps.copy(pProps = ps.pProps.||>(gas.gasId, gas))
    GovActionState.withGovActionParent(gas, Option(psWithGas))(
      new GovActionParent[Option[
        Proposals[E]
      ]]:
        def apply[P <: GovActionPurpose](
            field: GovRelationL[P],
            parent: Option[GovPurposeId[P]],
            newId: GovPurposeId[P]
        ): Option[Proposals[E]] =
          val nodes = field.get(ps.pGraph).unPGraph
          if parent == field.get(ps.pRoots).prRoot then
            Some(
              psWithGas.copy(
                pRoots =
                  field.modify(psWithGas.pRoots)(r => r.copy(prChildren = r.prChildren + newId)),
                pGraph = field.modify(psWithGas.pGraph)(g =>
                  PGraph(g.unPGraph.updated(newId, PEdges(parent, SortedSet.empty)))
                )
              )
            )
          else
            parent.filter(nodes.contains) match
              case Some(parentId) =>
                Some(
                  psWithGas.copy(pGraph =
                    field.modify(psWithGas.pGraph)(g =>
                      PGraph(
                        g.unPGraph
                          .updatedWith(parentId)(
                            _.map(e => e.copy(peChildren = e.peChildren + newId))
                          )
                          .updated(newId, PEdges(Some(parentId), SortedSet.empty))
                      )
                    )
                  )
                )
              case None => None
    )

  /** `proposalsAddVote` (`Proposals.hs:410-430`, cardano-ledger f649f975): `voter` 's vote on the
    * action `gai`, replacing the one it had; no change if no action has that id.
    */
  def proposalsAddVote[E <: EraTag](
      voter: Voter,
      vote: Vote,
      gai: GovActionId,
      ps: Proposals[E]
  ): Proposals[E] =
    def updateVote(gas: GovActionState[E]): GovActionState[E] = voter match
      case Voter.DRepVoter(c) => gas.copy(gasDRepVotes = gas.gasDRepVotes.updated(c, vote))
      case Voter.Pool(kh) => gas.copy(gasStakePoolVotes = gas.gasStakePoolVotes.updated(kh, vote))
      case Voter.CommitteeHot(c) =>
        gas.copy(gasCommitteeVotes = gas.gasCommitteeVotes.updated(c, vote))
    ps.copy(pProps = ps.pProps.adjust(updateVote, gai))

  /** `mapProposals` (`Proposals.hs:258-259`, cardano-ledger f649f975): every action's state changed
    * by `f`, which keeps its id.
    */
  def mapProposals[E <: EraTag](f: GovActionState[E] => GovActionState[E])(
      ps: Proposals[E]
  ): Proposals[E] = ps.copy(pProps = ps.pProps.mapUnsafe(f))

  /** `proposalsActionsMap` (`Proposals.hs:588-591`, cardano-ledger f649f975). */
  def proposalsActionsMap[E <: EraTag](ps: Proposals[E]): Map[GovActionId, GovActionState[E]] =
    ps.pProps.toMap

  /** `proposalsWithPurpose` (`Proposals.hs:274-291`, cardano-ledger f649f975): the actions of the
    * tree of purpose `P`, by the ids its graph holds, each looked up in the ordered map. The
    * reference falls back on a filter of the map by purpose if a lookup fails (`assert False`),
    * which the store's construction rules out ([[Proposals]]), so no lookup fails here.
    */
  def proposalsWithPurpose[E <: EraTag, P <: GovActionPurpose](
      propL: GovRelationL[P],
      ps: Proposals[E]
  ): Map[GovPurposeId[P], GovActionState[E]] =
    propL
      .get(ps.pGraph)
      .unPGraph
      .keysIterator
      .flatMap(id => ps.pProps.get(id.unGovPurposeId).map(id -> _))
      .toMap

  /** `proposalsLookupId` (`Proposals.hs:596-600`, cardano-ledger f649f975). */
  def proposalsLookupId[E <: EraTag](
      gai: GovActionId,
      ps: Proposals[E]
  ): Option[GovActionState[E]] = ps.pProps.get(gai)

  /** `mkProposals` (`Proposals.hs:338-357`, cardano-ledger f649f975): the store of the actions
    * `omap`, in their order, over the roots `pgais`, each added as [[proposalsAddAction]] adds it;
    * the first action that cannot be (`fail "mkProposals: Could not add a proposal"`,
    * [[ProposalsReadError.Unattached]]); or a store whose ordered map is not the actions given
    * (`fail "mkProposals: OMap is malformed"`, `:353`, [[ProposalsReadError.Malformed]]), as two
    * actions of one id give, the later replacing the earlier.
    */
  def mkProposals[E <: EraTag](
      pgais: GovRelation[Option],
      omap: Vector[GovActionState[E]]
  ): Either[ProposalsReadError, Proposals[E]] =
    omap
      .foldLeft[Either[ProposalsReadError, Proposals[E]]](Right(empty[E](pgais))) { (acc, gas) =>
        acc.flatMap(props =>
          proposalsAddAction(gas, props).toRight(ProposalsReadError.Unattached(gas.gasId))
        )
      }
      .flatMap(ps =>
        Either.cond(
          ps.pProps.toVector == omap,
          ps,
          ProposalsReadError.Malformed(omap.map(_.gasId), ps.pProps.keys)
        )
      )

/** Why the node's record of the proposals cannot be read into an era's [[Proposals]]: what only a
  * damaged record gives, where the reference decodes its own state with `mkProposals`. None is a
  * verdict of the rules.
  */
enum ProposalsReadError:
  /** A stored action's proposal that the era's types cannot hold. */
  case StoredProposal(id: GovActionId, error: EraTxError)

  /** A stored action's epochs outside `EpochNo`. */
  case StoredEpoch(id: GovActionId, error: xyz.kd5ujc.scarab.ledger.state.VocabError)

  /** `mkProposals: Could not add a proposal`: a stored action whose parent is neither its tree's
    * root nor an action stored before it.
    */
  case Unattached(id: GovActionId)

  /** `mkProposals: OMap is malformed`: the store rebuilt holds other actions than the record, as a
    * record holding an id twice gives: the ids recorded and those the store holds, in order.
    */
  case Malformed(recorded: Vector[GovActionId], rebuilt: Vector[GovActionId])
