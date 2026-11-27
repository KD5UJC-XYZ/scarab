// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.syntax.all.*
import monocle.Lens
import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Committee,
  ConwayGenesis,
  Constitution,
  Credential as FlatCredential,
  DRep as FlatDRep,
  DRepState as FlatDRepState,
  GovActionId
}
import xyz.kd5ujc.scarab.ledger.state.{
  Babbage,
  Coin,
  Conway,
  Credential,
  Dijkstra,
  EraTag,
  Sorted,
  Staking
}

// The governance state of the eras from Conway, by era as the reference's is (cardano-ledger
// f649f975 `eras/conway/impl/src/Cardano/Ledger/Conway/Governance.hs`, `Governance/Internal.hs`,
// `Governance/DRepPulser.hs`; Dijkstra's the same types at 9df9f98c,
// `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Governance.hs:28-49`): the proposals, the
// parameters and the ratification of era `E`, and the classes `EraGov` and `ConwayEraGov` over
// them. The committee, the constitution, the DReps' states and the distributions are the node's
// types, the ones its record holds. The ledger state holds Dijkstra's record itself
// (`xyz.kd5ujc.scarab.ledger.state.GovState`, #826), and Conway's flat record
// (`xyz.kd5ujc.scarab.ledger.state.ConwayGovState`), which Conway reads in and writes back
// ([[GovStateRecord]]).

/** `data EnactState era` (`Governance/Internal.hs:129-141`, cardano-ledger f649f975): what enacting
  * the ratified actions changes, the committee, the constitution, the current and previous
  * parameters, the treasury, the withdrawals and the last enacted action of each purpose.
  */
final case class EnactState[E <: EraTag](
    ensCommittee: Option[Committee],
    ensConstitution: Constitution,
    ensCurPParams: PParams[E],
    ensPrevPParams: PParams[E],
    ensTreasury: Coin,
    ensWithdrawals: SortedMap[Credential[Staking], Coin],
    ensPrevGovActionIds: GovRelation[Option]
)

object EnactState:
  given [E <: EraTag]: CanEqual[EnactState[E], EnactState[E]] = CanEqual.derived

  /** `instance Default (EnactState era)` (`Internal.hs:202-211`, cardano-ledger f649f975). */
  def empty[E <: EraTag](using p: EraPParams[E]): EnactState[E] =
    EnactState[E](
      None,
      ConwayGenesis.noConstitution,
      p.emptyPParams,
      p.emptyPParams,
      Coin.zero,
      Sorted.empty,
      GovRelation[Option](None, None, None, None)
    )

/** `data RatifyState era` (`Internal.hs:249-272`, cardano-ledger f649f975): the enact state the
  * last ratification reached, the actions to enact at the next boundary in order, those to remove
  * and whether a delaying action was ratified. The actions are a sequence, not a store
  * ([[Proposals]]), as the reference's `Seq` is.
  */
final case class RatifyState[E <: EraTag](
    rsEnactState: EnactState[E],
    rsEnacted: Vector[GovActionState[E]],
    rsExpired: SortedSet[GovActionId],
    rsDelayed: Boolean
)

object RatifyState:
  given [E <: EraTag]: CanEqual[RatifyState[E], RatifyState[E]] = CanEqual.derived

  /** `instance Default (RatifyState era)` (`Internal.hs:291`, cardano-ledger f649f975). */
  def empty[E <: EraTag](using EraPParams[E]): RatifyState[E] =
    RatifyState[E](EnactState.empty[E], Vector.empty, SortedSet.empty, false)

/** `data PulsingSnapshot era` (`Governance/DRepPulser.hs:98-104`, cardano-ledger f649f975): the
  * actions, the DReps' distribution and states and the pools' distribution at the start of the
  * epoch, the node's types each but the actions.
  *
  * The DReps' states are the node's flat `DRepState`, whose fields are declared deposit first; the
  * reference's `DRepState` declares and writes them expiry, anchor, deposit, delegators
  * (`libs/cardano-ledger-core/src/Cardano/Ledger/DRep.hs:166-171` and its `EncCBOR`, `:192-199`, at
  * f649f975 and 9df9f98c alike). A codec of this record writes them in the reference's order, as
  * the flat state codec's `nodeDRepState` does, not in the flat type's.
  */
final case class PulsingSnapshot[E <: EraTag](
    psProposals: Vector[GovActionState[E]],
    psDRepDistr: Map[FlatDRep, BigInt],
    psDRepState: Map[FlatCredential, FlatDRepState],
    psPoolDistr: Map[Bytes, BigInt]
)

object PulsingSnapshot:
  given [E <: EraTag]: CanEqual[PulsingSnapshot[E], PulsingSnapshot[E]] = CanEqual.derived

  /** `instance Default (PulsingSnapshot era)` (`DRepPulser.hs:144-145`, cardano-ledger f649f975).
    */
  def empty[E <: EraTag]: PulsingSnapshot[E] =
    PulsingSnapshot[E](Vector.empty, Map.empty, Map.empty, Map.empty)

/** `data DRepPulsingState era` (`DRepPulser.hs:423-428`, cardano-ledger f649f975), in the one form
  * the node holds and the reference encodes (`DRComplete`, `:455-459`): the snapshot the pulser
  * worked on and the ratification it reached. The running pulser (`DRPulsing`) is not held.
  */
enum DRepPulsingState[E <: EraTag]:
  case DRComplete(snapshot: PulsingSnapshot[E], ratifyState: RatifyState[E])

object DRepPulsingState:
  given [E <: EraTag]: CanEqual[DRepPulsingState[E], DRepPulsingState[E]] = CanEqual.derived

  /** `extractDRepPulsingState` (`DRepPulser.hs:512-517`, cardano-ledger 9df9f98c): the ratification
    * the pulser reached, which a complete pulser holds.
    */
  def extractDRepPulsingState[E <: EraTag](p: DRepPulsingState[E]): RatifyState[E] = p match
    case DRComplete(_, ratifyState) => ratifyState

/** `data FuturePParams era`
  * (`libs/cardano-ledger-core/src/Cardano/Ledger/State/Governance.hs:90-102`, cardano-ledger
  * f649f975): the prediction of the parameters at the next boundary. The reference's lazy `Maybe`
  * is `Option`.
  */
enum FuturePParams[E <: EraTag]:
  case NoPParamsUpdate()
  case DefinitePParamsUpdate(pp: PParams[E])
  case PotentialPParamsUpdate(pp: Option[PParams[E]])

object FuturePParams:
  given [E <: EraTag]: CanEqual[FuturePParams[E], FuturePParams[E]] = CanEqual.derived

  /** `knownFuturePParams` (`State/Governance.hs:111-114`, cardano-ledger f649f975). */
  def knownFuturePParams[E <: EraTag](f: FuturePParams[E]): Option[PParams[E]] = f match
    case DefinitePParamsUpdate(pp)                     => Some(pp)
    case NoPParamsUpdate() | PotentialPParamsUpdate(_) => None

  /** `solidifyFuturePParams` (`State/Governance.hs:135-140`, cardano-ledger 9df9f98c), which TICK
    * applies from the slot of no return (`Shelley/Rules/Tick.hs:158`): a potential update becomes
    * definite, or none.
    */
  def solidifyFuturePParams[E <: EraTag](f: FuturePParams[E]): FuturePParams[E] = f match
    case PotentialPParamsUpdate(None)                         => NoPParamsUpdate()
    case PotentialPParamsUpdate(Some(pp))                     => DefinitePParamsUpdate(pp)
    case fpp @ (NoPParamsUpdate() | DefinitePParamsUpdate(_)) => fpp

/** `data ConwayGovState era` (`Conway/Governance.hs:243-259`, cardano-ledger f649f975): the
  * proposals, the committee, the constitution, the current, previous and future parameters, and the
  * DRep pulser. Its fields in the reference's order, the order its `EncCBOR` writes them in
  * (`Governance.hs:359-369`).
  */
final case class ConwayGovState[E <: EraTag](
    cgsProposals: Proposals[E],
    cgsCommittee: Option[Committee],
    cgsConstitution: Constitution,
    cgsCurPParams: PParams[E],
    cgsPrevPParams: PParams[E],
    cgsFuturePParams: FuturePParams[E],
    cgsDRepPulsingState: DRepPulsingState[E]
)

object ConwayGovState:
  given [E <: EraTag]: CanEqual[ConwayGovState[E], ConwayGovState[E]] = CanEqual.derived

  /** `cgsProposalsL` (`Governance.hs:263-264`, cardano-ledger f649f975). */
  def cgsProposalsL[E <: EraTag]: Lens[ConwayGovState[E], Proposals[E]] =
    Lens[ConwayGovState[E], Proposals[E]](_.cgsProposals)(v => _.copy(cgsProposals = v))

  /** `cgsDRepPulsingStateL` (`Governance.hs:266-267`, cardano-ledger f649f975). */
  def cgsDRepPulsingStateL[E <: EraTag]: Lens[ConwayGovState[E], DRepPulsingState[E]] =
    Lens[ConwayGovState[E], DRepPulsingState[E]](_.cgsDRepPulsingState)(v =>
      _.copy(cgsDRepPulsingState = v)
    )

  /** `cgsCommitteeL` (`Governance.hs:269-270`, cardano-ledger f649f975). */
  def cgsCommitteeL[E <: EraTag]: Lens[ConwayGovState[E], Option[Committee]] =
    Lens[ConwayGovState[E], Option[Committee]](_.cgsCommittee)(v => _.copy(cgsCommittee = v))

  /** `cgsConstitutionL` (`Governance.hs:272-273`, cardano-ledger f649f975). */
  def cgsConstitutionL[E <: EraTag]: Lens[ConwayGovState[E], Constitution] =
    Lens[ConwayGovState[E], Constitution](_.cgsConstitution)(v => _.copy(cgsConstitution = v))

  /** `cgsCurPParamsL` (`Governance.hs:275-276`, cardano-ledger f649f975). */
  def cgsCurPParamsL[E <: EraTag]: Lens[ConwayGovState[E], PParams[E]] =
    Lens[ConwayGovState[E], PParams[E]](_.cgsCurPParams)(v => _.copy(cgsCurPParams = v))

  /** `cgsPrevPParamsL` (`Governance.hs:278-279`, cardano-ledger f649f975). */
  def cgsPrevPParamsL[E <: EraTag]: Lens[ConwayGovState[E], PParams[E]] =
    Lens[ConwayGovState[E], PParams[E]](_.cgsPrevPParams)(v => _.copy(cgsPrevPParams = v))

  /** `cgsFuturePParamsL` (`Governance.hs:281-283`, cardano-ledger f649f975). */
  def cgsFuturePParamsL[E <: EraTag]: Lens[ConwayGovState[E], FuturePParams[E]] =
    Lens[ConwayGovState[E], FuturePParams[E]](_.cgsFuturePParams)(v => _.copy(cgsFuturePParams = v))

  /** `predictFuturePParams` (`Conway/Governance.hs:300-327`, cardano-ledger 9df9f98c): while the
    * future parameters are only potential, they become the parameters the pulser's ratification
    * enacts, if it enacts a parameter change or a hard fork, else none. The reference leaves them a
    * thunk TICK forces at the slot of no return; the pulser is complete here, so they are a value.
    */
  def predictFuturePParams[E <: EraTag](govState: ConwayGovState[E]): ConwayGovState[E] =
    govState.cgsFuturePParams match
      case FuturePParams.NoPParamsUpdate() | FuturePParams.DefinitePParamsUpdate(_) => govState
      case FuturePParams.PotentialPParamsUpdate(_) =>
        val ratifyState = DRepPulsingState.extractDRepPulsingState(govState.cgsDRepPulsingState)
        def hasChangesToPParams(gas: GovActionState[E]): Boolean = gas.gasAction match
          case GovAction.ParameterChange(_, _, _) | GovAction.HardForkInitiation(_, _) => true
          case GovAction.TreasuryWithdrawals(_, _) | GovAction.NoConfidence(_) |
              GovAction.UpdateCommittee(_, _, _, _) | GovAction.NewConstitution(_, _) |
              GovAction.InfoAction() =>
            false
        val newFuturePParams = Option.when(ratifyState.rsEnacted.exists(hasChangesToPParams))(
          ratifyState.rsEnactState.ensCurPParams
        )
        govState.copy(cgsFuturePParams = FuturePParams.PotentialPParamsUpdate(newFuturePParams))

  /** `mkEnactState` (`Conway/Governance.hs:329-339`, cardano-ledger 9df9f98c): the enact state a
    * ratification starts from, of the governance state's committee, constitution, parameters and
    * the roots of its proposals, with no treasury and no withdrawals.
    */
  def mkEnactState[E <: EraTag](gs: ConwayGovState[E]): EnactState[E] =
    EnactState[E](
      gs.cgsCommittee,
      gs.cgsConstitution,
      gs.cgsCurPParams,
      gs.cgsPrevPParams,
      Coin.zero,
      Sorted.empty,
      Proposals.toPrevGovActionIds(gs.cgsProposals.pRoots)
    )

  /** `instance Default (ConwayGovState era)` (`Governance.hs:377-378`, cardano-ledger f649f975): no
    * proposal over no root, no committee, the default constitution (an anchor of the empty URL and
    * the zero hash, no guardrails script, `Procedures.hs:982-983`, `BaseTypes.hs:1026-1027`), the
    * empty parameters, no future update and the pulser complete over the empty snapshot and
    * ratification.
    */
  def empty[E <: EraTag](using p: EraPParams[E]): ConwayGovState[E] =
    ConwayGovState[E](
      Proposals.empty[E](GovRelation[Option](None, None, None, None)),
      None,
      ConwayGenesis.noConstitution,
      p.emptyPParams,
      p.emptyPParams,
      FuturePParams.NoPParamsUpdate(),
      DRepPulsingState.DRComplete(PulsingSnapshot.empty[E], RatifyState.empty[E])
    )

/** The governance state of era `E` (`class EraGov era`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/State/Governance.hs:44-89`, cardano-ledger
  * f649f975): its record is the era's family ([[GovState]]), with lenses onto its current, previous
  * and future parameters and the deposits it holds. Its superclass `EraPParams era` is a member
  * ([[pparamsOf]]). The constraints on the record's instances (`Eq`, `Show`, the codecs, `Default`)
  * are the record's own; the record has no codec of its own yet, the node storing its flat form.
  */
trait EraGov[E <: EraTag]:
  /** The parameters' class (the superclass `EraPParams era`). */
  def pparamsOf: EraPParams[E]

  /** `emptyGovState` (`State/Governance.hs:69-70`, cardano-ledger f649f975): the record's
    * `Default`.
    */
  def emptyGovState: GovState[E]

  /** `curPParamsGovStateL` (`State/Governance.hs:73`, cardano-ledger f649f975). */
  def curPParamsGovStateL: Lens[GovState[E], PParams[E]]

  /** `prevPParamsGovStateL` (`State/Governance.hs:76`, cardano-ledger f649f975). */
  def prevPParamsGovStateL: Lens[GovState[E], PParams[E]]

  /** `futurePParamsGovStateL` (`State/Governance.hs:86`, cardano-ledger f649f975). */
  def futurePParamsGovStateL: Lens[GovState[E], FuturePParams[E]]

  /** `obligationGovState` (`State/Governance.hs:88`, cardano-ledger f649f975): the deposits the
    * governance state holds.
    */
  def obligationGovState(st: GovState[E]): Obligations

/** The governance state of an era from Conway (`class ConwayEraGov era`,
  * `Conway/Governance.hs:422-426`, cardano-ledger f649f975): lenses onto its constitution,
  * proposals, DRep pulser and committee. Its superclasses `EraStake era` and `ConwayEraAccounts
  * era` are members ([[stakeOf]], [[accountsOf]]).
  */
trait ConwayEraGov[E <: EraTag] extends EraGov[E]:
  /** The stake's class (the superclass `EraStake era`). */
  def stakeOf: EraStake[E]

  /** The accounts' class (the superclass `ConwayEraAccounts era`). */
  def accountsOf: ConwayEraAccounts[E]

  /** `constitutionGovStateL` (`Governance.hs:423`, cardano-ledger f649f975). */
  def constitutionGovStateL: Lens[GovState[E], Constitution]

  /** `proposalsGovStateL` (`Governance.hs:424`, cardano-ledger f649f975). */
  def proposalsGovStateL: Lens[GovState[E], Proposals[E]]

  /** `drepPulsingStateGovStateL` (`Governance.hs:425`, cardano-ledger f649f975). */
  def drepPulsingStateGovStateL: Lens[GovState[E], DRepPulsingState[E]]

  /** `committeeGovStateL` (`Governance.hs:426`, cardano-ledger f649f975). */
  def committeeGovStateL: Lens[GovState[E], Option[Committee]]

/** The instances of each era, the one place that lists the eras having each governance class. */
object EraGov:
  /** `nextEpochPParams` (`State/Governance.hs:116-123`, cardano-ledger 9df9f98c): the future
    * parameters where they are known (`knownFuturePParams`), else the current ones.
    */
  def nextEpochPParams[E <: EraTag](govState: GovState[E])(using g: EraGov[E]): PParams[E] =
    FuturePParams
      .knownFuturePParams(g.futurePParamsGovStateL.get(govState))
      .getOrElse(g.curPParamsGovStateL.get(govState))

  /** The instances over [[ConwayGovState]], the record of every era from Conway: `instance EraGov
    * ConwayEra` (`Conway/Governance.hs:405-420`) and `instance ConwayEraGov ConwayEra` (`:428-432`,
    * cardano-ledger f649f975), and Dijkstra's, the same (`Dijkstra/Governance.hs:28-49`,
    * cardano-ledger 9df9f98c). The deposits are the proposals' (`foldMap' gasDeposit`), the others
    * zero.
    */
  private def overConwayGovState[E <: EraTag](
      p: EraPParams[E],
      s: EraStake[E],
      a: ConwayEraAccounts[E]
  )(using ev: ConwayGovState[E] =:= GovState[E]): ConwayEraGov[E] = new ConwayEraGov[E]:
    private def on[A](l: Lens[ConwayGovState[E], A]): Lens[GovState[E], A] =
      ev.substituteCo[[x] =>> Lens[x, A]](l)
    val pparamsOf: EraPParams[E] = p
    val stakeOf: EraStake[E] = s
    val accountsOf: ConwayEraAccounts[E] = a
    val emptyGovState: GovState[E] = ev(ConwayGovState.empty[E](using p))
    val curPParamsGovStateL: Lens[GovState[E], PParams[E]] = on(ConwayGovState.cgsCurPParamsL[E])
    val prevPParamsGovStateL: Lens[GovState[E], PParams[E]] =
      on(ConwayGovState.cgsPrevPParamsL[E])
    val futurePParamsGovStateL: Lens[GovState[E], FuturePParams[E]] =
      on(ConwayGovState.cgsFuturePParamsL[E])
    val constitutionGovStateL: Lens[GovState[E], Constitution] =
      on(ConwayGovState.cgsConstitutionL[E])
    val proposalsGovStateL: Lens[GovState[E], Proposals[E]] = on(ConwayGovState.cgsProposalsL[E])
    val drepPulsingStateGovStateL: Lens[GovState[E], DRepPulsingState[E]] =
      on(ConwayGovState.cgsDRepPulsingStateL[E])
    val committeeGovStateL: Lens[GovState[E], Option[Committee]] =
      on(ConwayGovState.cgsCommitteeL[E])
    def obligationGovState(st: GovState[E]): Obligations =
      Obligations(
        Coin.zero,
        Coin.zero,
        Coin.zero,
        Coin(proposalsGovStateL.get(st).pProps.toVector.foldMap(_.gasDeposit.value))
      )

  /** `instance EraGov BabbageEra`
    * (`eras/babbage/impl/src/Cardano/Ledger/Babbage/PParams.hs:249-259`, cardano-ledger f649f975):
    * Shelley's record and lenses (`Shelley/Governance.hs:74-82, 185-192`), and no deposits
    * (`obligationGovState = const mempty`).
    */
  given babbage: EraGov[Babbage] with
    val pparamsOf: EraPParams[Babbage] = EraPParams.babbage
    val emptyGovState: GovState[Babbage] =
      ShelleyGovState.emptyShelleyGovState[Babbage](using pparamsOf)
    val curPParamsGovStateL: Lens[GovState[Babbage], PParams[Babbage]] =
      ShelleyGovState.curPParamsShelleyGovStateL[Babbage]
    val prevPParamsGovStateL: Lens[GovState[Babbage], PParams[Babbage]] =
      ShelleyGovState.prevPParamsShelleyGovStateL[Babbage]
    val futurePParamsGovStateL: Lens[GovState[Babbage], FuturePParams[Babbage]] =
      ShelleyGovState.futurePParamsShelleyGovStateL[Babbage]
    def obligationGovState(st: GovState[Babbage]): Obligations = cats.Monoid[Obligations].empty

  given conway: ConwayEraGov[Conway] =
    overConwayGovState[Conway](EraPParams.conway, EraStake.conway, EraAccounts.conway)

  given dijkstra: ConwayEraGov[Dijkstra] =
    overConwayGovState[Dijkstra](EraPParams.dijkstra, EraStake.dijkstra, EraAccounts.dijkstra)
