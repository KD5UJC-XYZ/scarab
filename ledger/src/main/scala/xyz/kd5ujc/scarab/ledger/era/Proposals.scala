// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.ledger.{GovActionId, RewardAccount, Vote, Voter as FlatVoter}
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  CompactCoin,
  Credential,
  DRepRole,
  EpochNo,
  EraTag,
  HotCommittee,
  KeyHash,
  Sorted,
  StakePool,
  Staking,
  VocabError
}

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

/** `data Voter` (`Procedures.hs:338-342`, cardano-ledger f649f975): who casts a vote, a committee
  * member's hot credential, a DRep's credential or a pool's key hash, each of its role: the key a
  * vote is held under in an action's state ([[GovActionState]]). The votes of a transaction carry
  * the node's flat voter ([[VotingProcedures]]), which GOV reads into this one where it casts them
  * ([[Voter.fromFlat]]), until the votes carry the reference's typed voter (#85).
  */
enum Voter:
  case CommitteeVoter(credential: Credential[HotCommittee])
  case DRepVoter(credential: Credential[DRepRole])
  case StakePoolVoter(keyHash: KeyHash[StakePool])

object Voter:
  /** The node's flat voter at the reference's types; a hash of another length than the role's is
    * refused, which the flat decoder already refused (`Voter.decode`).
    */
  def fromFlat(v: FlatVoter): Either[VocabError, Voter] = v match
    case FlatVoter.CommitteeHot(c) => Convert.credential[HotCommittee](c).map(CommitteeVoter(_))
    case FlatVoter.DRepVoter(c)    => Convert.credential[DRepRole](c).map(DRepVoter(_))
    case FlatVoter.Pool(kh)        => KeyHash.fromBytes[StakePool](kh).map(StakePoolVoter(_))

  /** The node's flat voter of a typed one, the inverse of [[fromFlat]]. */
  def toFlat(v: Voter): FlatVoter = v match
    case CommitteeVoter(c) => FlatVoter.CommitteeHot(Convert.back(c))
    case DRepVoter(c)      => FlatVoter.DRepVoter(Convert.back(c))
    case StakePoolVoter(k) => FlatVoter.Pool(k.bytes)

/** `data GovActionState era` (`Procedures.hs:219-228`, cardano-ledger f649f975): a proposed action,
  * the votes cast on it, each map keyed by its voters' role (`:221-223`) and ordered as the
  * reference's `Map` is, by the role's `Ord` ([[Credential]]'s, [[KeyHash]]'s), the epoch it was
  * proposed in and the last epoch it can be ratified in.
  */
final case class GovActionState[E <: EraTag](
    gasId: GovActionId,
    gasCommitteeVotes: SortedMap[Credential[HotCommittee], Vote],
    gasDRepVotes: SortedMap[Credential[DRepRole], Vote],
    gasStakePoolVotes: SortedMap[KeyHash[StakePool], Vote],
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
      case Voter.StakePoolVoter(kh) =>
        gas.copy(gasStakePoolVotes = gas.gasStakePoolVotes.updated(kh, vote))
      case Voter.CommitteeVoter(c) =>
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

  /** `proposalsActions` (`Proposals.hs:557-562`, cardano-ledger 9df9f98c): the actions' states in
    * their order.
    */
  def proposalsActions[E <: EraTag](ps: Proposals[E]): Vector[GovActionState[E]] =
    ps.pProps.toVector

  /** `proposalsDeposits` (`Proposals.hs:564-580`, cardano-ledger 9df9f98c): the deposits of the
    * actions by the credential of their return accounts, a deposit that is not compact counted as
    * zero and the sums `addCompactCoin` 's, which wraps. A return account's credential of another
    * length than a stake credential's is refused, which the decoder of an account address refused.
    */
  def proposalsDeposits[E <: EraTag](
      ps: Proposals[E]
  ): Either[VocabError, SortedMap[Credential[Staking], CompactCoin]] =
    proposalsActions(ps).foldLeft[Either[VocabError, SortedMap[Credential[Staking], CompactCoin]]](
      Right(Sorted.empty)
    ) { (acc, gas) =>
      for
        gasMap <- acc
        credential <- Convert.credential[Staking](gas.gasReturnAddr.credential)
        deposit = CompactCoin.fromCoin(gas.gasDeposit).getOrElse(CompactCoin.zero)
      yield gasMap.updatedWith(credential)(old => Some(old.fold(deposit)(_.add(deposit))))
    }

  /** `proposalsRemoveIds` (`Proposals.hs:431-461`, cardano-ledger 9df9f98c): the actions of `gais`
    * taken out of the ordered map, and out of their trees, each as a child of its parent, in the
    * order of their ids (`Map`'s `foldl'`); and the actions taken out. The reference asserts that
    * an action whose parent is not its tree's root names one (`assert False id`); the assertion is
    * compiled out of the node, so such an action is only taken out of the graph, as there.
    */
  def proposalsRemoveIds[E <: EraTag](
      gais: Set[GovActionId],
      ps: Proposals[E]
  ): (Proposals[E], SortedMap[GovActionId, GovActionState[E]]) =
    val (retainedOMap, removedFromOMap) = ps.pProps.extractKeys(gais)
    type Forest = (GovRelation[PRoot], GovRelation[PGraph])
    val (roots, graph) =
      removedFromOMap.valuesIterator.foldLeft[Forest]((ps.pRoots, ps.pGraph)) {
        case (accum @ (roots, graph), gas) =>
          GovActionState.withGovActionParent(gas, accum)(
            new GovActionParent[Forest]:
              def apply[P <: GovActionPurpose](
                  field: GovRelationL[P],
                  parent: Option[GovPurposeId[P]],
                  gpi: GovPurposeId[P]
              ): Forest =
                if parent == field.get(roots).prRoot then
                  (
                    field.modify(roots)(r => r.copy(prChildren = r.prChildren - gpi)),
                    field.modify(graph)(g => PGraph(g.unPGraph - gpi))
                  )
                else
                  (
                    roots,
                    field.modify(graph) { g =>
                      val nodes = g.unPGraph - gpi
                      PGraph(parent match
                        case None => nodes
                        case Some(parentGpi) =>
                          nodes.updatedWith(parentGpi)(
                            _.map(e => e.copy(peChildren = e.peChildren - gpi))
                          ))
                    }
                  )
          )
      }
    (ps.copy(pProps = retainedOMap, pRoots = roots, pGraph = graph), removedFromOMap)

  /** `getAllDescendants` of `proposalsRemoveWithDescendants` (`Proposals.hs:474-483`,
    * cardano-ledger 9df9f98c): every action below `gai` in its tree. The reference recurses over
    * the graph; this walks it with a work list (STK-1), the same set, as the graph is a forest
    * ([[proposalsAddAction]] adds each action as a leaf). An id the ordered map does not hold, or a
    * node the graph does not, has none (`assert False mempty`, compiled out).
    */
  private def getAllDescendants[E <: EraTag](
      gai: GovActionId,
      ps: Proposals[E]
  ): Set[GovActionId] =
    ps.pProps.get(gai).fold(Set.empty[GovActionId]) { gas =>
      GovActionState.withGovActionParent(gas, Set.empty[GovActionId])(
        new GovActionParent[Set[GovActionId]]:
          def apply[P <: GovActionPurpose](
              field: GovRelationL[P],
              parent: Option[GovPurposeId[P]],
              self: GovPurposeId[P]
          ): Set[GovActionId] =
            val nodes = field.get(ps.pGraph).unPGraph
            @scala.annotation.tailrec
            def go(acc: Set[GovActionId], pending: List[GovPurposeId[P]]): Set[GovActionId] =
              pending match
                case Nil => acc
                case gpi :: rest =>
                  nodes.get(gpi) match
                    case None => go(acc, rest)
                    case Some(PEdges(_, children)) =>
                      val fresh = children.filterNot(c => acc.contains(c.unGovPurposeId))
                      go(acc ++ fresh.iterator.map(_.unGovPurposeId), fresh.toList ++ rest)
            go(Set.empty, List(self))
      )
    }

  /** `proposalsRemoveWithDescendants` (`Proposals.hs:463-483`, cardano-ledger 9df9f98c): the
    * actions of `gais` and every action below them taken out ([[proposalsRemoveIds]]).
    */
  def proposalsRemoveWithDescendants[E <: EraTag](
      gais: Set[GovActionId],
      ps: Proposals[E]
  ): (Proposals[E], SortedMap[GovActionId, GovActionState[E]]) =
    proposalsRemoveIds(gais ++ gais.flatMap(getAllDescendants(_, ps)), ps)

  /** `proposalsApplyEnactment` (`Proposals.hs:485-556`, cardano-ledger 9df9f98c), for EPOCH: the
    * expired actions and every action below them taken out, then each enacted action in order made
    * the root of its tree, its siblings and every action below them taken out, its children the
    * root's; an action of no tree only taken out. The proposals after, the actions enacted, those
    * taken out by the enactments and those taken out by expiry, each by id. Of an id in two maps
    * the union keeps the first's (`Map.union`). The reference's assertions are compiled out of the
    * node: an enacted action the proposals do not hold is enacted all the same, as there.
    */
  def proposalsApplyEnactment[E <: EraTag](
      enactedGass: Vector[GovActionState[E]],
      expiredGais: Set[GovActionId],
      props: Proposals[E]
  ): (
      Proposals[E],
      SortedMap[GovActionId, GovActionState[E]],
      SortedMap[GovActionId, GovActionState[E]],
      SortedMap[GovActionId, GovActionState[E]]
  ) =
    type Step = (
        Proposals[E],
        SortedMap[GovActionId, GovActionState[E]],
        SortedMap[GovActionId, GovActionState[E]]
    )
    def enact(step: Step, gas: GovActionState[E]): Step =
      val (ps, enacted, removed) = step
      val gai = gas.gasId
      def enactWithoutRoot: Step =
        val (newOMap, enactedAction) = ps.pProps.extractKeys(Set(gai))
        (ps.copy(pProps = newOMap), enactedAction ++ enacted, removed)
      GovActionState.withGovActionParent(gas, enactWithoutRoot)(
        new GovActionParent[Step]:
          def apply[P <: GovActionPurpose](
              field: GovRelationL[P],
              parent: Option[GovPurposeId[P]],
              gpi: GovPurposeId[P]
          ): Step =
            val siblings = field.get(ps.pRoots).prChildren.map(_.unGovPurposeId) - gai
            val newRootChildren =
              field
                .get(ps.pGraph)
                .unPGraph
                .get(gpi)
                .fold(SortedSet.empty[GovPurposeId[P]])(
                  _.peChildren
                )
            val (withoutSiblings, removedActions) = proposalsRemoveWithDescendants(siblings, ps)
            val newGraph = field.get(withoutSiblings.pGraph).unPGraph - gpi
            val (newOMap, enactedAction) = withoutSiblings.pProps.extractKeys(Set(gai))
            val newProposals = withoutSiblings.copy(
              pProps = newOMap,
              pRoots = field.set(withoutSiblings.pRoots, PRoot(Some(gpi), newRootChildren)),
              pGraph = field.set(withoutSiblings.pGraph, PGraph(newGraph))
            )
            (newProposals, enactedAction ++ enacted, removedActions ++ removed)
      )
    val (unexpiredProposals, expiredRemoved) = proposalsRemoveWithDescendants(expiredGais, props)
    val (enactedProposalsState, enacted, removedDueToEnactment) =
      enactedGass.foldLeft[Step]((unexpiredProposals, SortedMap.empty, SortedMap.empty))(enact)
    (enactedProposalsState, enacted, removedDueToEnactment, expiredRemoved)

/** Why the node's record of the proposals cannot be read into an era's [[Proposals]]: what only a
  * damaged record gives, where the reference decodes its own state with `mkProposals`. None is a
  * verdict of the rules.
  */
enum ProposalsReadError:
  /** A stored action's proposal that the era's types cannot hold. */
  case StoredProposal(id: GovActionId, error: EraTxError)

  /** A stored action's epochs outside `EpochNo`. */
  case StoredEpoch(id: GovActionId, error: VocabError)

  /** A stored action's voter, a credential or pool key hash of another length than its role's. */
  case StoredVoter(id: GovActionId, error: VocabError)

  /** `mkProposals: Could not add a proposal`: a stored action whose parent is neither its tree's
    * root nor an action stored before it.
    */
  case Unattached(id: GovActionId)

  /** `mkProposals: OMap is malformed`: the store rebuilt holds other actions than the record, as a
    * record holding an id twice gives: the ids recorded and those the store holds, in order.
    */
  case Malformed(recorded: Vector[GovActionId], rebuilt: Vector[GovActionId])
