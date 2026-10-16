// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.Eq
import cats.Show
import cats.derived.*
import monocle.{Focus, Lens}
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.{
  Committee,
  Constitution,
  ParamUpdate,
  ProtocolParams,
  Proposals,
  PulsingSnapshot,
  RatifyState
}

// The governance state of the ledger state, by era: `ShelleyGovState` (proposed protocol-parameter
// updates, Shelley to Babbage) and `ConwayGovState` (proposals, committee, constitution, DRep
// pulser). The parameters are the node's one flat `ProtocolParams` for every era.

/** `FuturePParams era` (`State/Governance.hs:90-102`): the prediction of a protocol-parameter
  * change at the next epoch boundary.
  */
enum FuturePParams derives Eq, Show, Diff:
  /** Definitely no update at the next boundary. */
  case NoPParamsUpdate

  /** The parameters that will be adopted at the next boundary. */
  case DefinitePParamsUpdate(params: ProtocolParams)

  /** No guarantee: solidified two stability windows before the end of the epoch. Lazy in Haskell;
    * `None` is the `Nothing` it may hold.
    */
  case PotentialPParamsUpdate(params: Option[ProtocolParams])

object FuturePParams:
  /** The prediction with its parameters changed by `f` (Haskell's `translateEra` of a future
    * `PParams`).
    */
  def map(f: FuturePParams)(g: ProtocolParams => ProtocolParams): FuturePParams = f match
    case NoPParamsUpdate              => NoPParamsUpdate
    case DefinitePParamsUpdate(p)     => DefinitePParamsUpdate(g(p))
    case PotentialPParamsUpdate(next) => PotentialPParamsUpdate(next.map(g))

  /** The parameters the prediction holds, if any. */
  extension (f: FuturePParams)
    /** `knownFuturePParams` (`State/Governance.hs:105-108`): the parameters, only if it is certain
      * that they will be adopted.
      */
    def definite: Option[ProtocolParams] = f match
      case DefinitePParamsUpdate(p) => Some(p)
      case _                        => None

    def nextParams: Option[ProtocolParams] = f match
      case NoPParamsUpdate              => None
      case DefinitePParamsUpdate(p)     => Some(p)
      case PotentialPParamsUpdate(next) => next

/** `DRComplete` (`Conway/Governance/DRepPulser.hs:423-431`): the completed DRep pulser, the
  * snapshot it worked on and the ratification it reached. The mirror holds the pulser only in this
  * form, as Haskell encodes it (`DRepPulser.hs:455-459`).
  */
final case class DRComplete(snapshot: PulsingSnapshot, ratify: RatifyState) derives Eq, Show, Diff

object DRComplete:
  val snapshot: Lens[DRComplete, PulsingSnapshot] = Focus[DRComplete](_.snapshot)
  val ratify: Lens[DRComplete, RatifyState] = Focus[DRComplete](_.ratify)

/** `ConwayGovState` (`Conway/Governance.hs:243-259`). */
final case class ConwayGovState(
    proposals: Proposals,
    committee: Option[Committee],
    constitution: Constitution,
    curPParams: ProtocolParams,
    prevPParams: ProtocolParams,
    futurePParams: FuturePParams,
    drepPulsing: DRComplete
) derives Eq,
      Show,
      Diff

object ConwayGovState:
  val proposals: Lens[ConwayGovState, Proposals] = Focus[ConwayGovState](_.proposals)
  val committee: Lens[ConwayGovState, Option[Committee]] = Focus[ConwayGovState](_.committee)
  val constitution: Lens[ConwayGovState, Constitution] = Focus[ConwayGovState](_.constitution)
  val curPParams: Lens[ConwayGovState, ProtocolParams] = Focus[ConwayGovState](_.curPParams)
  val prevPParams: Lens[ConwayGovState, ProtocolParams] = Focus[ConwayGovState](_.prevPParams)
  val futurePParams: Lens[ConwayGovState, FuturePParams] = Focus[ConwayGovState](_.futurePParams)
  val drepPulsing: Lens[ConwayGovState, DRComplete] = Focus[ConwayGovState](_.drepPulsing)

/** `ShelleyGovState` (`Shelley/Governance.hs:63-70`). The proposals are the genesis delegates'
  * parameter-update proposals (`ProposedPPUpdates`, a map from genesis key hash).
  */
final case class ShelleyGovState(
    curProposals: SortedMap[KeyHash[Genesis], ParamUpdate],
    futureProposals: SortedMap[KeyHash[Genesis], ParamUpdate],
    curPParams: ProtocolParams,
    prevPParams: ProtocolParams,
    futurePParams: FuturePParams
) derives Eq,
      Show,
      Diff

object ShelleyGovState:
  val curProposals: Lens[ShelleyGovState, SortedMap[KeyHash[Genesis], ParamUpdate]] =
    Focus[ShelleyGovState](_.curProposals)
  val futureProposals: Lens[ShelleyGovState, SortedMap[KeyHash[Genesis], ParamUpdate]] =
    Focus[ShelleyGovState](_.futureProposals)
  val curPParams: Lens[ShelleyGovState, ProtocolParams] = Focus[ShelleyGovState](_.curPParams)
  val prevPParams: Lens[ShelleyGovState, ProtocolParams] = Focus[ShelleyGovState](_.prevPParams)
  val futurePParams: Lens[ShelleyGovState, FuturePParams] = Focus[ShelleyGovState](_.futurePParams)
