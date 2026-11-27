// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.syntax.all.*
import monocle.Lens
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  CborRead,
  DecodeField,
  DecoderVersion,
  LedgerDecodeError,
  ParamUpdate,
  ProtocolParams
}
import xyz.kd5ujc.scarab.ledger.era.babbage.BabbagePParams
import xyz.kd5ujc.scarab.ledger.state.{
  Babbage,
  EraTag,
  FuturePParams as StoredFuturePParams,
  Genesis,
  KeyHash,
  ShelleyGovState as StoredShelleyGovState,
  Sorted
}

// The governance state of the eras before Conway (cardano-ledger f649f975
// `eras/shelley/impl/src/Cardano/Ledger/Shelley/Governance.hs:63-192`): the genesis delegates'
// proposed updates of the parameters, for this epoch and the next, and the current, previous and
// future parameters, by era. The node's ledger state holds its flat record
// (`xyz.kd5ujc.scarab.ledger.state.ShelleyGovState`), which an era reads in and writes back
// ([[ShelleyGovStateRecord]]).

/** One genesis delegate's proposed update of era `E` 's parameters (`PParamsUpdate era`, the value
  * of `ProposedPPUpdates era`, `Shelley/PParams.hs:270-272`, cardano-ledger f649f975), with the
  * form the node keeps it in: each field's value as the bytes it was read from (ADR 0006,
  * [[KeptUpdate]]). The reference keeps the values only; the node's stored form keeps the bytes, so
  * that a field submitted in a form other than the canonical one is stored as it came, and the
  * update read from them is kept beside them. Two are equal when their updates are, as the
  * reference's `Eq` compares the values. Made only from the stored form ([[fromStored]]), so the
  * bytes are always those the update was read from.
  */
final class KeptPParamsUpdate[E <: EraTag] private (
    val update: PParamsUpdate[E],
    val stored: ParamUpdate
):
  override def equals(that: Any): Boolean = that match
    case o: KeptPParamsUpdate[?] => update == o.update
    case _                       => false
  override def hashCode(): Int = update.hashCode
  override def toString: String = s"KeptPParamsUpdate($update)"

object KeptPParamsUpdate:
  given [E <: EraTag]: CanEqual[KeptPParamsUpdate[E], KeptPParamsUpdate[E]] = CanEqual.derived

  /** The update the node keeps, read into the era at its decoder version: each stored field's bytes
    * read by its field, as [[KeptUpdate.fromStored]] reads them. A stored form no decoder of the
    * era gave is refused: a key that is not one of the era's, or a value its field does not read.
    */
  def fromStored[E <: EraTag](stored: ParamUpdate)(using
      p: EraPParams[E],
      era: Era[E]
  ): Either[LedgerDecodeError, KeptPParamsUpdate[E]] =
    given DecodeAt =
      DecodeAt(DecoderVersion.ofVersion(era.eraProtVerLow), DecodeField.ParameterUpdate)
    PParamsUpdate
      .readFields[E, (Int, Bytes), Unit](stored.fields.toVector.sortBy(_._1), ())(
        (k, bytes) => CborRead.decode(bytes).map(BigInt(k) -> _),
        (_, _, _) => ()
      )
      .map((update, _) => KeptPParamsUpdate(update, stored))

/** The proposed updates of the genesis delegates (`newtype ProposedPPUpdates era`,
  * `Shelley/PParams.hs:270-272`, cardano-ledger f649f975), each kept with its stored form
  * ([[KeptPParamsUpdate]]). Equal as the reference's derived `Eq` (`:274`), by the updates.
  */
final case class KeptProposedPPUpdates[E <: EraTag](
    updates: SortedMap[KeyHash[Genesis], KeptPParamsUpdate[E]]
):
  /** The reference's `ProposedPPUpdates era`: the updates without their stored form. */
  def proposed: ProposedPPUpdates[E] = ProposedPPUpdates(updates.view.mapValues(_.update).toMap)

object KeptProposedPPUpdates:
  given [E <: EraTag]: CanEqual[KeptProposedPPUpdates[E], KeptProposedPPUpdates[E]] =
    CanEqual.derived

  /** `emptyPPPUpdates` (`Shelley/PParams.hs:294-295`, cardano-ledger f649f975). */
  def empty[E <: EraTag]: KeptProposedPPUpdates[E] = KeptProposedPPUpdates(Sorted.empty)

/** `data ShelleyGovState era` (`Shelley/Governance.hs:63-70`, cardano-ledger f649f975): the
  * proposed updates of this epoch and of the next, and the current, previous and future parameters,
  * in the reference's order, the order its `EncCBOR` writes them in (`:108-122`). The proposals are
  * kept with their stored form ([[KeptProposedPPUpdates]]).
  */
final case class ShelleyGovState[E <: EraTag](
    sgsCurProposals: KeptProposedPPUpdates[E],
    sgsFutureProposals: KeptProposedPPUpdates[E],
    sgsCurPParams: PParams[E],
    sgsPrevPParams: PParams[E],
    sgsFuturePParams: FuturePParams[E]
)

object ShelleyGovState:
  given [E <: EraTag]: CanEqual[ShelleyGovState[E], ShelleyGovState[E]] = CanEqual.derived

  /** `emptyShelleyGovState` (`Shelley/Governance.hs:185-192`, cardano-ledger f649f975): no
    * proposal, the empty parameters and no future update.
    */
  def emptyShelleyGovState[E <: EraTag](using p: EraPParams[E]): ShelleyGovState[E] =
    ShelleyGovState[E](
      KeptProposedPPUpdates.empty[E],
      KeptProposedPPUpdates.empty[E],
      p.emptyPParams,
      p.emptyPParams,
      FuturePParams.NoPParamsUpdate()
    )

  /** `curPParamsShelleyGovStateL` (`Shelley/Governance.hs:74-75`, cardano-ledger f649f975). */
  def curPParamsShelleyGovStateL[E <: EraTag]: Lens[ShelleyGovState[E], PParams[E]] =
    Lens[ShelleyGovState[E], PParams[E]](_.sgsCurPParams)(v => _.copy(sgsCurPParams = v))

  /** `prevPParamsShelleyGovStateL` (`Shelley/Governance.hs:77-78`, cardano-ledger f649f975). */
  def prevPParamsShelleyGovStateL[E <: EraTag]: Lens[ShelleyGovState[E], PParams[E]] =
    Lens[ShelleyGovState[E], PParams[E]](_.sgsPrevPParams)(v => _.copy(sgsPrevPParams = v))

  /** `futurePParamsShelleyGovStateL` (`Shelley/Governance.hs:80-82`, cardano-ledger f649f975). */
  def futurePParamsShelleyGovStateL[E <: EraTag]: Lens[ShelleyGovState[E], FuturePParams[E]] =
    Lens[ShelleyGovState[E], FuturePParams[E]](_.sgsFuturePParams)(v =>
      _.copy(sgsFuturePParams = v)
    )

/** Which of the governance state's proposed updates a refusal to read it names. */
enum ShelleyProposals:
  /** `sgsCurProposals`. */
  case Current

  /** `sgsFutureProposals`. */
  case Future

/** Why the node's flat record of a governance state before Conway cannot be read into an era's
  * [[ShelleyGovState]]: what only a damaged record gives, as [[GovStateReadError]] is. None is a
  * verdict of the rules.
  */
enum ShelleyGovStateReadError:
  /** A parameter record outside the era's types ([[PParamsError]]). */
  case Parameters(params: GovStateParams, error: PParamsError)

  /** A delegate's proposed update that the era's decoder does not read. */
  case Proposal(proposals: ShelleyProposals, genesis: KeyHash[Genesis], error: LedgerDecodeError)

/** How era `E` reads the node's flat record of its governance state (the flat
  * [[xyz.kd5ujc.scarab.ledger.state.ShelleyGovState]]) into its [[ShelleyGovState]] and writes it
  * back: glue while the node's ledger state holds the flat record (#85). The flat parameters hold
  * every era's fields and the era's record only its own, so the era's record is written back over
  * the record it was read from, whose other fields it keeps; the proposals are written back as the
  * bytes they keep. One instance per era, in the companion.
  */
sealed trait ShelleyGovStateRecord[E <: EraTag]:
  /** The era's fields of the node's parameters. */
  def pparams(p: ProtocolParams): Either[PParamsError, PParams[E]]

  /** The node's parameters `base` with the era's fields those of `pp`. */
  def storedPParams(pp: PParams[E], base: ProtocolParams): ProtocolParams

  /** The era's parameter and proposal classes. */
  def pparamsOf: EraPParams[E]
  def eraOf: Era[E]

object ShelleyGovStateRecord:
  given babbage: ShelleyGovStateRecord[Babbage] with
    def pparams(
        p: ProtocolParams
    ): Either[PParamsError, PParams[Babbage]] =
      BabbagePParams.fromStored(p).map(PParams[Babbage](_))
    def storedPParams(
        pp: PParams[Babbage],
        base: ProtocolParams
    ): ProtocolParams = BabbagePParams.toStored(pp.hkd, base)
    def pparamsOf: EraPParams[Babbage] = EraPParams.babbage
    def eraOf: Era[Babbage] = Era.babbage

  /** The node's flat record read into era `E` 's: each parameter record by the era's fields, each
    * proposed update by the era's decoder, with the bytes it keeps.
    */
  def fromStored[E <: EraTag](g: StoredShelleyGovState)(using
      r: ShelleyGovStateRecord[E]
  ): Either[ShelleyGovStateReadError, ShelleyGovState[E]] =
    def params(which: GovStateParams, p: ProtocolParams) =
      r.pparams(p).leftMap(ShelleyGovStateReadError.Parameters(which, _))
    def proposals(which: ShelleyProposals, ps: SortedMap[KeyHash[Genesis], ParamUpdate]) =
      ps.toVector
        .traverse((genesis, stored) =>
          KeptPParamsUpdate
            .fromStored[E](stored)(using r.pparamsOf, r.eraOf)
            .bimap(ShelleyGovStateReadError.Proposal(which, genesis, _), genesis -> _)
        )
        .map(kvs => KeptProposedPPUpdates[E](Sorted.map(kvs)))
    val future: Either[ShelleyGovStateReadError, FuturePParams[E]] = g.futurePParams match
      case StoredFuturePParams.NoPParamsUpdate => Right(FuturePParams.NoPParamsUpdate())
      case StoredFuturePParams.DefinitePParamsUpdate(p) =>
        params(GovStateParams.Future, p).map(FuturePParams.DefinitePParamsUpdate(_))
      case StoredFuturePParams.PotentialPParamsUpdate(p) =>
        p.traverse(params(GovStateParams.Future, _)).map(FuturePParams.PotentialPParamsUpdate(_))
    (
      proposals(ShelleyProposals.Current, g.curProposals),
      proposals(ShelleyProposals.Future, g.futureProposals),
      params(GovStateParams.Current, g.curPParams),
      params(GovStateParams.Previous, g.prevPParams),
      future
    ).mapN(ShelleyGovState[E](_, _, _, _, _))

  /** Era `E` 's record written back over the node's flat record `base`: each parameter record over
    * `base` 's of the same place, whose other eras' fields it keeps (the future parameters over
    * `base` 's future ones where it has them, else over its current ones, from which the flat rules
    * make them), and each proposed update as the bytes it keeps. The inverse of [[fromStored]]: a
    * record read from `base` is written back as `base`.
    */
  def toStored[E <: EraTag](g: ShelleyGovState[E], base: StoredShelleyGovState)(using
      r: ShelleyGovStateRecord[E]
  ): StoredShelleyGovState =
    def proposals(ps: KeptProposedPPUpdates[E]): SortedMap[KeyHash[Genesis], ParamUpdate] =
      ps.updates.transform((_, kept) => kept.stored)
    val futureBase = base.futurePParams.nextParams.getOrElse(base.curPParams)
    val future = g.sgsFuturePParams match
      case FuturePParams.NoPParamsUpdate() => StoredFuturePParams.NoPParamsUpdate
      case FuturePParams.DefinitePParamsUpdate(pp) =>
        StoredFuturePParams.DefinitePParamsUpdate(r.storedPParams(pp, futureBase))
      case FuturePParams.PotentialPParamsUpdate(pp) =>
        StoredFuturePParams.PotentialPParamsUpdate(pp.map(r.storedPParams(_, futureBase)))
    StoredShelleyGovState(
      proposals(g.sgsCurProposals),
      proposals(g.sgsFutureProposals),
      r.storedPParams(g.sgsCurPParams, base.curPParams),
      r.storedPParams(g.sgsPrevPParams, base.prevPParams),
      future
    )
