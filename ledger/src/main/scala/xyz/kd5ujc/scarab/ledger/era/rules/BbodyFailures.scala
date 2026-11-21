// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import xyz.kd5ujc.scarab.ledger.{Mismatch, Nonce}
import xyz.kd5ujc.scarab.ledger.era.{PerasCert, Version}
import xyz.kd5ujc.scarab.ledger.state.{Conway, Dijkstra, EraTag, Hash32}
import xyz.kd5ujc.scarab.plutus.ExUnits

// The failures and events of BBODY as the reference declares them (cardano-ledger 9df9f98c, the
// `Rules/Bbody.hs` of Shelley, Alonzo, Conway and Dijkstra), with the injections of every rule
// below BBODY into Dijkstra's and the embedding of LEDGERS in BBODY. A size is the reference's
// `Int` (a `Long` here), a header's version word a `Word32` (a `Long` the header's reader bounds),
// and a block's units (`OrdExUnits`) the node's, as UTXO's failures hold them.

/** `data ShelleyBbodyPredFailure era` (`Shelley/Rules/Bbody.hs:99-107`, cardano-ledger 9df9f98c).
  */
enum ShelleyBbodyPredFailure[E <: EraTag]:
  /** The body's size (supplied) and the size its header declares (expected). */
  case WrongBlockBodySizeBBODY(mismatch: Mismatch[Long])

  /** The body's hash (supplied) and the hash its header declares (expected). */
  case InvalidBodyHashBBODY(mismatch: Mismatch[Hash32])
  case LedgersFailure(failure: LedgersPredFailure[E])

/** `data AlonzoBbodyPredFailure era` (`Alonzo/Rules/Bbody.hs:65-68`, cardano-ledger 9df9f98c). */
enum AlonzoBbodyPredFailure[E <: EraTag]:
  case ShelleyInAlonzoBbodyPredFailure(failure: ShelleyBbodyPredFailure[E])

  /** The units of the block's transactions (supplied) and the parameters' limit (expected). */
  case TooManyExUnits(mismatch: Mismatch[ExUnits])

/** `data ConwayBbodyPredFailure era` (`Conway/Rules/Bbody.hs:106-114`, cardano-ledger 9df9f98c),
  * its constructors in the order of their tags.
  */
enum ConwayBbodyPredFailure[E <: EraTag]:
  case WrongBlockBodySizeBBODY(mismatch: Mismatch[Long])
  case InvalidBodyHashBBODY(mismatch: Mismatch[Hash32])
  case LedgersFailure(failure: LedgersPredFailure[E])
  case TooManyExUnits(mismatch: Mismatch[ExUnits])

  /** The reference scripts of the block (supplied) and the parameters' limit (expected). */
  case BodyRefScriptsSizeTooBig(mismatch: Mismatch[Long])

  /** The header's protocol version (supplied) and the highest the node supports (expected). */
  case HeaderProtVerTooHigh(mismatch: Mismatch[Version])

/** `data DijkstraBbodyPredFailure era` (`Dijkstra/Rules/Bbody.hs:88-97`, cardano-ledger 9df9f98c),
  * its constructors in the order of their tags (0 to 6, `:119-127`).
  */
enum DijkstraBbodyPredFailure[E <: EraTag]:
  case WrongBlockBodySizeBBODY(mismatch: Mismatch[Long])
  case InvalidBodyHashBBODY(mismatch: Mismatch[Hash32])
  case LedgersFailure(failure: LedgersPredFailure[E])
  case TooManyExUnits(mismatch: Mismatch[ExUnits])
  case BodyRefScriptsSizeTooBig(mismatch: Mismatch[Long])

  /** The body's Peras certificate and the header's previous nonce. */
  case PerasCertValidationFailed(cert: PerasCert, nonce: Nonce)

  /** The header's highest supported major version (supplied) and the parameters' major (expected).
    */
  case HeaderProtVerTooLow(mismatch: Mismatch[Long])

/** `EraRuleFailure "BBODY" era`: Conway's (`Conway/Rules/Bbody.hs:162`) and Dijkstra's
  * (`Dijkstra/Rules/Bbody.hs:146`, cardano-ledger 9df9f98c), each its own.
  */
type BbodyPredFailure[E <: EraTag] = E match
  case Conway   => ConwayBbodyPredFailure[Conway]
  case Dijkstra => DijkstraBbodyPredFailure[Dijkstra]

/** `newtype ShelleyBbodyEvent era` (`Shelley/Rules/Bbody.hs:166-167`, cardano-ledger 9df9f98c). */
enum ShelleyBbodyEvent[E <: EraTag]:
  case LedgersEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.LedgersEvent[E])

/** `data AlonzoBbodyEvent era` (`Alonzo/Rules/Bbody.hs:72-73`, cardano-ledger 9df9f98c). */
enum AlonzoBbodyEvent[E <: EraTag]:
  case ShelleyInAlonzoEvent(event: ShelleyBbodyEvent[E])

/** `EraRuleEvent "BBODY" era`: Alonzo's at Conway (`Conway/Rules/Bbody.hs:164`) and at Dijkstra
  * (`Dijkstra/Rules/Bbody.hs:148`, cardano-ledger 9df9f98c).
  */
type BbodyEvent[E <: EraTag] = E match
  case Conway   => AlonzoBbodyEvent[Conway]
  case Dijkstra => AlonzoBbodyEvent[Dijkstra]

object ShelleyBbodyPredFailure:
  given [E <: EraTag]: CanEqual[ShelleyBbodyPredFailure[E], ShelleyBbodyPredFailure[E]] =
    CanEqual.derived

object AlonzoBbodyPredFailure:
  given [E <: EraTag]: CanEqual[AlonzoBbodyPredFailure[E], AlonzoBbodyPredFailure[E]] =
    CanEqual.derived

object ConwayBbodyPredFailure:
  given [E <: EraTag]: CanEqual[ConwayBbodyPredFailure[E], ConwayBbodyPredFailure[E]] =
    CanEqual.derived

  /** `shelleyToConwayBbodyPredFailure` (`Conway/Rules/Bbody.hs:231-241`, cardano-ledger 9df9f98c).
    */
  def shelleyToConwayBbodyPredFailure[E <: EraTag](
      failure: ShelleyBbodyPredFailure[E]
  ): ConwayBbodyPredFailure[E] = failure match
    case ShelleyBbodyPredFailure.WrongBlockBodySizeBBODY(m) => WrongBlockBodySizeBBODY(m)
    case ShelleyBbodyPredFailure.InvalidBodyHashBBODY(m)    => InvalidBodyHashBBODY(m)
    case ShelleyBbodyPredFailure.LedgersFailure(x)          => LedgersFailure(x)

  /** `alonzoToConwayBbodyPredFailure` (`Conway/Rules/Bbody.hs:243-248`, cardano-ledger 9df9f98c).
    */
  def alonzoToConwayBbodyPredFailure[E <: EraTag](
      failure: AlonzoBbodyPredFailure[E]
  ): ConwayBbodyPredFailure[E] = failure match
    case AlonzoBbodyPredFailure.ShelleyInAlonzoBbodyPredFailure(x) =>
      shelleyToConwayBbodyPredFailure(x)
    case AlonzoBbodyPredFailure.TooManyExUnits(m) => TooManyExUnits(m)

object DijkstraBbodyPredFailure:
  given [E <: EraTag]: CanEqual[DijkstraBbodyPredFailure[E], DijkstraBbodyPredFailure[E]] =
    CanEqual.derived

object ShelleyBbodyEvent:
  given [E <: EraTag]: CanEqual[ShelleyBbodyEvent[E], ShelleyBbodyEvent[E]] = CanEqual.derived

object AlonzoBbodyEvent:
  given [E <: EraTag]: CanEqual[AlonzoBbodyEvent[E], AlonzoBbodyEvent[E]] = CanEqual.derived

/** The constructors of Conway's BBODY failure that `conwayToDijkstraBbodyPredFailure` maps
  * (`Dijkstra/Rules/Bbody.hs:405-413`, cardano-ledger 9df9f98c): `HeaderProtVerTooHigh`, which it
  * calls impossible, left out, by D7a's decision on the reference's partial injections (2026-11-20,
  * #85). Each payload is taken whole.
  */
type ConwayBbodyFailureInDijkstra[E <: EraTag] = ConwayBbodyPredFailure.WrongBlockBodySizeBBODY[E] |
  ConwayBbodyPredFailure.InvalidBodyHashBBODY[E] | ConwayBbodyPredFailure.LedgersFailure[E] |
  ConwayBbodyPredFailure.TooManyExUnits[E] | ConwayBbodyPredFailure.BodyRefScriptsSizeTooBig[E]

/** `conwayToDijkstraBbodyPredFailure` (`Dijkstra/Rules/Bbody.hs:405-413`, cardano-ledger 9df9f98c),
  * on [[ConwayBbodyFailureInDijkstra]].
  */
def conwayToDijkstraBbodyPredFailure[E <: EraTag](
    failure: ConwayBbodyFailureInDijkstra[E]
): DijkstraBbodyPredFailure[E] = failure match
  case ConwayBbodyPredFailure.WrongBlockBodySizeBBODY(m) =>
    DijkstraBbodyPredFailure.WrongBlockBodySizeBBODY(m)
  case ConwayBbodyPredFailure.InvalidBodyHashBBODY(m) =>
    DijkstraBbodyPredFailure.InvalidBodyHashBBODY(m)
  case ConwayBbodyPredFailure.LedgersFailure(f) => DijkstraBbodyPredFailure.LedgersFailure(f)
  case ConwayBbodyPredFailure.TooManyExUnits(m) => DijkstraBbodyPredFailure.TooManyExUnits(m)
  case ConwayBbodyPredFailure.BodyRefScriptsSizeTooBig(m) =>
    DijkstraBbodyPredFailure.BodyRefScriptsSizeTooBig(m)

/** `InjectRuleFailure "BBODY" f era` (`Dijkstra/Rules/Bbody.hs:150-296`, cardano-ledger 9df9f98c):
  * how era `E` 's BBODY reports a failure `F` of its own or of a rule below it. Contravariant in
  * `F`, as [[InjectLedgerFailure]] is, so that an instance over a whole type or a union of
  * constructors serves a predicate declaring one of them.
  */
trait InjectBbodyFailure[-F, E <: EraTag]:
  def injectFailure(failure: F): BbodyPredFailure[E]

object InjectBbodyFailure:
  /** `Dijkstra/Rules/Bbody.hs:150`, cardano-ledger 9df9f98c: the identity, the class's default. */
  given dijkstra: InjectBbodyFailure[DijkstraBbodyPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: DijkstraBbodyPredFailure[Dijkstra]): BbodyPredFailure[Dijkstra] =
      failure

  /** `Dijkstra/Rules/Bbody.hs:152-153`, cardano-ledger 9df9f98c:
    * `conwayToDijkstraBbodyPredFailure`, on the constructors it maps
    * ([[ConwayBbodyFailureInDijkstra]]).
    */
  given dijkstraConway: InjectBbodyFailure[ConwayBbodyFailureInDijkstra[Dijkstra], Dijkstra] with
    def injectFailure(failure: ConwayBbodyFailureInDijkstra[Dijkstra]): BbodyPredFailure[Dijkstra] =
      conwayToDijkstraBbodyPredFailure(failure)

  /** `Dijkstra/Rules/Bbody.hs:158-159`, cardano-ledger 9df9f98c: `conwayToDijkstraBbodyPredFailure
    * . shelleyToConwayBbodyPredFailure`, which reaches only the three constructors that keep their
    * names, so it is written as their map.
    */
  given dijkstraShelley: InjectBbodyFailure[ShelleyBbodyPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: ShelleyBbodyPredFailure[Dijkstra]): BbodyPredFailure[Dijkstra] =
      failure match
        case ShelleyBbodyPredFailure.WrongBlockBodySizeBBODY(m) =>
          DijkstraBbodyPredFailure.WrongBlockBodySizeBBODY(m)
        case ShelleyBbodyPredFailure.InvalidBodyHashBBODY(m) =>
          DijkstraBbodyPredFailure.InvalidBodyHashBBODY(m)
        case ShelleyBbodyPredFailure.LedgersFailure(x) =>
          DijkstraBbodyPredFailure.LedgersFailure(x)

  /** `Dijkstra/Rules/Bbody.hs:155-156`, cardano-ledger 9df9f98c: `conwayToDijkstraBbodyPredFailure
    * . alonzoToConwayBbodyPredFailure`, which reaches no `HeaderProtVerTooHigh`: Shelley's through
    * [[dijkstraShelley]], the units as Dijkstra's.
    */
  given dijkstraAlonzo: InjectBbodyFailure[AlonzoBbodyPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: AlonzoBbodyPredFailure[Dijkstra]): BbodyPredFailure[Dijkstra] =
      failure match
        case AlonzoBbodyPredFailure.ShelleyInAlonzoBbodyPredFailure(x) =>
          dijkstraShelley.injectFailure(x)
        case AlonzoBbodyPredFailure.TooManyExUnits(m) => DijkstraBbodyPredFailure.TooManyExUnits(m)

  /** `Dijkstra/Rules/Bbody.hs:161-296`, cardano-ledger 9df9f98c: LEDGERS's failure and each failure
    * below it, `conwayToDijkstraBbodyPredFailure . shelleyToConwayBbodyPredFailure . LedgersFailure
    * . injectFailure`, which is Dijkstra's `LedgersFailure` of LEDGERS's injection: one instance
    * over LEDGERS's twenty ([[InjectLedgersFailure]]), the ones the reference lists here.
    */
  given dijkstraLedgers[F](using
      inject: InjectLedgersFailure[F, Dijkstra]
  ): InjectBbodyFailure[F, Dijkstra] with
    def injectFailure(failure: F): BbodyPredFailure[Dijkstra] =
      DijkstraBbodyPredFailure.LedgersFailure(inject.injectFailure(failure))

/** `Embed ledgers (BBODY era)` (`Dijkstra/Rules/Bbody.hs:415-423`, cardano-ledger 9df9f98c): the
  * failures of LEDGERS in `LedgersFailure` and its events in `ShelleyInAlonzoEvent . LedgersEvent`.
  */
trait EmbedLedgersInBbody[E <: EraTag]:
  def wrapFailed(failure: LedgersPredFailure[E]): BbodyPredFailure[E]
  def wrapEvent(event: LedgersEvent[E]): BbodyEvent[E]

object EmbedLedgersInBbody:
  given dijkstra: EmbedLedgersInBbody[Dijkstra] with
    def wrapFailed(failure: LedgersPredFailure[Dijkstra]): BbodyPredFailure[Dijkstra] =
      DijkstraBbodyPredFailure.LedgersFailure(failure)
    def wrapEvent(event: LedgersEvent[Dijkstra]): BbodyEvent[Dijkstra] =
      AlonzoBbodyEvent.ShelleyInAlonzoEvent(ShelleyBbodyEvent.LedgersEvent(event))
