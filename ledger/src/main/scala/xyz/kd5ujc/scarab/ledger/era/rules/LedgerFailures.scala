// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.data.{NonEmptyList, NonEmptyMap}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{
  GovAction as FlatGovAction,
  MempoolProblem,
  PredicateFailure,
  Purpose
}
import xyz.kd5ujc.scarab.ledger.era.GovAction
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayPlutusPurpose
import xyz.kd5ujc.scarab.ledger.era.AsItem
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{
  AccountAddress,
  Coin,
  Conway,
  Dijkstra,
  EraTag,
  KeyHash,
  Staking
}
import xyz.kd5ujc.scarab.ledger.era.Mismatch
import xyz.kd5ujc.scarab.ledger.era.Relation.*
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

// The failures and events of LEDGER and LEDGERS as the reference declares them (cardano-ledger
// f649f975 `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Ledger.hs`,
// `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Ledgers.hs` and Shelley's `Rules/Ledger.hs` and
// `Rules/Ledgers.hs`), with the injections of every rule below LEDGER into it and into LEDGERS and
// the embeddings of UTXOW, CERTS and GOV in LEDGER and of LEDGER in LEDGERS. At 29a7d656 and
// 9df9f98c the types derive `Ord` too, and Conway's LEDGER injects Shelley's
// `AccountAlreadyRegistered` through DELEG (`StakeKeyRegisteredDELEG` here, the same credential and
// bytes), a function of the build (`InjectLedgerFailure.conwayAccountAlreadyRegistered`).

/** The failure of DELEGS, the rule Conway's LEDGER no longer runs: none at Conway, where the
  * reference's injection calls it impossible (`shelleyToConwayLedgerPredFailure`,
  * `Conway/Rules/Ledger.hs:189-196`, cardano-ledger f649f975), which this type makes so; none at
  * Dijkstra either (`shelleyToDijkstraLedgerPredFailure`, `Dijkstra/Rules/Ledger.hs:551`,
  * cardano-ledger 9df9f98c, cardano-ledger 9df9f98c). The eras before Conway add their case.
  */
type DelegsPredFailure[E <: EraTag] = E match
  case Conway   => Nothing
  case Dijkstra => Nothing

/** `data ShelleyLedgerPredFailure era` (`Shelley/Rules/Ledger.hs:123-128`, cardano-ledger
  * f649f975), its constructors in the order of their tags (0 to 3). The withdrawals are the
  * reference's `Withdrawals` (`Map AccountAddress Coin`, `Address.hs:980`).
  */
enum ShelleyLedgerPredFailure[E <: EraTag]:
  case UtxowFailure(failure: UtxowPredFailure[E])
  case DelegsFailure(failure: DelegsPredFailure[E])
  case ShelleyWithdrawalsMissingAccounts(withdrawals: Map[AccountAddress, Coin])

  /** Each withdrawal that does not drain its account: the amount (supplied) and the balance
    * (expected).
    */
  case ShelleyIncompleteWithdrawals(mismatches: NonEmptyMap[AccountAddress, Mismatch[RelEQ, Coin]])

/** `data ConwayLedgerPredFailure era` (`Conway/Rules/Ledger.hs:117-127`, cardano-ledger f649f975),
  * its constructors in the order of their tags (1 to 9, `:231-240`). The mempool's failure is the
  * node's typed cause ([[MempoolProblem]]), which the node renders to the reference's text, as the
  * flat failure carries it: LEDGER never gives it, MEMPOOL does.
  */
enum ConwayLedgerPredFailure[E <: EraTag]:
  case ConwayUtxowFailure(failure: UtxowPredFailure[E])
  case ConwayCertsFailure(failure: CertsPredFailure[E])
  case ConwayGovFailure(failure: GovPredFailure[E])
  case ConwayWdrlNotDelegatedToDRep(keys: NonEmptyList[KeyHash[Staking]])

  /** The treasury the body declares (supplied) and the chain's (expected). */
  case ConwayTreasuryValueMismatch(mismatch: Mismatch[RelEQ, Coin])

  /** The reference scripts' size (supplied) and the parameters' limit (expected), as the
    * reference's 64-bit `Int`.
    */
  case ConwayTxRefScriptsSizeTooBig(mismatch: Mismatch[RelLTEQ, Long])
  case ConwayMempoolFailure(problem: MempoolProblem)
  case ConwayWithdrawalsMissingAccounts(withdrawals: Map[AccountAddress, Coin])

  /** Each withdrawal that does not drain its account: the amount (supplied) and the balance
    * (expected).
    */
  case ConwayIncompleteWithdrawals(mismatches: NonEmptyMap[AccountAddress, Mismatch[RelEQ, Coin]])

/** `EraRuleFailure "LEDGER" era`: Conway's is its own (`Conway/Rules/Ledger.hs:129`, cardano-ledger
  * f649f975), and Dijkstra's its own (`Dijkstra/Rules/Ledger.hs:107`, cardano-ledger 9df9f98c).
  */
type LedgerPredFailure[E <: EraTag] = E match
  case Conway   => ConwayLedgerPredFailure[Conway]
  case Dijkstra => DijkstraLedgerPredFailure[Dijkstra]

/** `data ConwayLedgerEvent era` (`Conway/Rules/Ledger.hs:262-266`, cardano-ledger f649f975). */
enum ConwayLedgerEvent[E <: EraTag]:
  case UtxowEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.UtxowEvent[E])
  case CertsEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.CertsEvent[E])
  case GovEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.GovEvent[E])

/** `EraRuleEvent "LEDGER" era`: Conway's is its own (`Conway/Rules/Ledger.hs:131`, cardano-ledger
  * f649f975), and Dijkstra's its own (`Dijkstra/Rules/Ledger.hs:109`, cardano-ledger 9df9f98c).
  */
type LedgerEvent[E <: EraTag] = E match
  case Conway   => ConwayLedgerEvent[Conway]
  case Dijkstra => DijkstraLedgerEvent[Dijkstra]

/** `newtype ShelleyLedgersPredFailure era` (`Shelley/Rules/Ledgers.hs:89-91`, cardano-ledger
  * f649f975), Conway's LEDGERS failure (`Conway/Rules/Ledgers.hs`).
  */
enum ShelleyLedgersPredFailure[E <: EraTag]:
  case LedgerFailure(failure: LedgerPredFailure[E])

/** `EraRuleFailure "LEDGERS" era`: Conway's is Shelley's (`Conway/Rules/Ledgers.hs:24`,
  * cardano-ledger f649f975), and Dijkstra's (`Dijkstra/Rules/Ledgers.hs:21`, cardano-ledger
  * 9df9f98c).
  */
type LedgersPredFailure[E <: EraTag] = E match
  case Conway   => ShelleyLedgersPredFailure[Conway]
  case Dijkstra => ShelleyLedgersPredFailure[Dijkstra]

/** `newtype ShelleyLedgersEvent era` (`Shelley/Rules/Ledgers.hs:127-129`, cardano-ledger f649f975).
  */
enum ShelleyLedgersEvent[E <: EraTag]:
  case LedgerEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.LedgerEvent[E])

/** `EraRuleEvent "LEDGERS" era`: Conway's is Shelley's (`Conway/Rules/Ledgers.hs:26`,
  * cardano-ledger f649f975), and Dijkstra's (`Dijkstra/Rules/Ledgers.hs:23`, cardano-ledger
  * 9df9f98c).
  */
type LedgersEvent[E <: EraTag] = E match
  case Conway   => ShelleyLedgersEvent[Conway]
  case Dijkstra => ShelleyLedgersEvent[Dijkstra]

/** `InjectRuleFailure "LEDGER" f era` (`Conway/Rules/Ledger.hs:133-187`, cardano-ledger f649f975):
  * how era `E` 's LEDGER reports a failure `F` of its own or of a rule below it. One instance per
  * era and failure type, in the companion. It is contravariant in `F`, as [[InjectUtxowFailure]]
  * is, so that Dijkstra's instance over the constructors of Conway's LEDGER failure its injection
  * maps ([[DijkstraLedger.ConwayFailure]]) serves a predicate declaring one of them.
  */
trait InjectLedgerFailure[-F, E <: EraTag]:
  def injectFailure(failure: F): LedgerPredFailure[E]

object InjectLedgerFailure:
  /** `Conway/Rules/Ledger.hs:133` (cardano-ledger f649f975): the identity. */
  given conway: InjectLedgerFailure[ConwayLedgerPredFailure[Conway], Conway] with
    def injectFailure(failure: ConwayLedgerPredFailure[Conway]): LedgerPredFailure[Conway] =
      failure

  /** `Conway/Rules/Ledger.hs:135-136` (cardano-ledger f649f975):
    * `shelleyToConwayLedgerPredFailure`.
    */
  given conwayShelley: InjectLedgerFailure[ShelleyLedgerPredFailure[Conway], Conway] with
    def injectFailure(failure: ShelleyLedgerPredFailure[Conway]): LedgerPredFailure[Conway] =
      ConwayLedger.shelleyToConwayLedgerPredFailure[Conway](failure)

  /** `Conway/Rules/Ledger.hs:138-166` and `:186-187` (cardano-ledger f649f975): UTXOW's failure and
    * each failure below it, `ConwayUtxowFailure . injectFailure`, as one instance over UTXOW's
    * injection ([[InjectUtxowFailure]]).
    */
  given conwayUtxow[F](using inject: InjectUtxowFailure[F, Conway]): InjectLedgerFailure[F, Conway]
  with
    def injectFailure(failure: F): LedgerPredFailure[Conway] =
      ConwayLedgerPredFailure.ConwayUtxowFailure(inject.injectFailure(failure))

  /** `Conway/Rules/Ledger.hs:162-163` (cardano-ledger f649f975): Shelley's UTXO failures by the
    * build's injection into UTXO's ([[InjectUtxowFailure.conwayShelleyUtxo]]), then wrapped.
    */
  def conwayShelleyUtxo(
      inject: InjectShelleyUtxoFailure[Conway]
  ): InjectLedgerFailure[ShelleyUtxoPredFailure[Conway], Conway] =
    new InjectLedgerFailure[ShelleyUtxoPredFailure[Conway], Conway]:
      def injectFailure(failure: ShelleyUtxoPredFailure[Conway]): LedgerPredFailure[Conway] =
        ConwayLedgerPredFailure.ConwayUtxowFailure(
          InjectUtxowFailure.conwayShelleyUtxo(inject).injectFailure(failure)
        )

  /** `Conway/Rules/Ledger.hs:168-181` (cardano-ledger f649f975): CERTS's failure and each failure
    * below it, `ConwayCertsFailure . injectFailure`, as one instance over CERTS's injection
    * ([[InjectCertsFailure]]).
    */
  given conwayCerts[F](using inject: InjectCertsFailure[F, Conway]): InjectLedgerFailure[F, Conway]
  with
    def injectFailure(failure: F): LedgerPredFailure[Conway] =
      ConwayLedgerPredFailure.ConwayCertsFailure(inject.injectFailure(failure))

  /** `Conway/Rules/Ledger.hs:183-184` (cardano-ledger f649f975): `ConwayGovFailure`. */
  given conwayGov: InjectLedgerFailure[ConwayGovPredFailure[Conway], Conway] with
    def injectFailure(failure: ConwayGovPredFailure[Conway]): LedgerPredFailure[Conway] =
      ConwayLedgerPredFailure.ConwayGovFailure(failure)

  /** `Conway/Rules/Ledger.hs:137-138` (cardano-ledger 29a7d656 and 9df9f98c): Shelley's
    * `AccountAlreadyRegistered`, `ConwayCertsFailure . CertFailure . DelegFailure . injectFailure`,
    * DELEG's injection its `DelegAccountAlreadyRegistered` (`StakeKeyRegisteredDELEG` here), under
    * the builds whose ledger has the instance; the 11.1 line's (f649f975) has neither the type nor
    * the instance. LEDGERS has none at any of them (`Conway/Rules/Ledgers.hs`).
    */
  def conwayAccountAlreadyRegistered(
      build: ReferenceBuild
  ): Option[InjectLedgerFailure[AccountAlreadyRegistered[Conway], Conway]] = build match
    case ReferenceBuild.Release11_1 => None
    case ReferenceBuild.Release11_2 | ReferenceBuild.LeiosB004fe0c =>
      Some(
        new InjectLedgerFailure[AccountAlreadyRegistered[Conway], Conway]:
          def injectFailure(failure: AccountAlreadyRegistered[Conway]): LedgerPredFailure[Conway] =
            ConwayLedgerPredFailure.ConwayCertsFailure(
              ConwayCertsPredFailure.CertFailure(
                ConwayCertPredFailure.DelegFailure(
                  ConwayDelegPredFailure.StakeKeyRegisteredDELEG(failure.credential)
                )
              )
            )
      )

  /** `Dijkstra/Rules/Ledger.hs:113-119`, cardano-ledger 9df9f98c: Shelley's
    * `AccountAlreadyRegistered` through DELEG's injection ([[InjectDelegFailure.dijkstra]]), then
    * CERT's, CERTS's and ENTITIES' constructors, which have no instance of their own for it.
    */
  given dijkstraAccountAlreadyRegistered
      : InjectLedgerFailure[AccountAlreadyRegistered[Dijkstra], Dijkstra] with
    def injectFailure(failure: AccountAlreadyRegistered[Dijkstra]): LedgerPredFailure[Dijkstra] =
      DijkstraLedgerPredFailure.DijkstraEntitiesFailure(
        EntitiesPredFailure.CertsFailure(
          ConwayCertsPredFailure.CertFailure(
            ConwayCertPredFailure.DelegFailure(
              InjectDelegFailure.dijkstra.injectFailure(failure)
            )
          )
        )
      )

  /** `Dijkstra/Rules/Ledger.hs:121`, cardano-ledger 9df9f98c: the identity. */
  given dijkstra: InjectLedgerFailure[DijkstraLedgerPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: DijkstraLedgerPredFailure[Dijkstra]): LedgerPredFailure[Dijkstra] =
      failure

  /** `Dijkstra/Rules/Ledger.hs:123-124`, cardano-ledger 9df9f98c:
    * `conwayToDijkstraLedgerPredFailure`, on the constructors it maps
    * ([[DijkstraLedger.ConwayFailure]]).
    */
  given dijkstraConway: InjectLedgerFailure[DijkstraLedger.ConwayFailure[Dijkstra], Dijkstra] with
    def injectFailure(
        failure: DijkstraLedger.ConwayFailure[Dijkstra]
    ): LedgerPredFailure[Dijkstra] =
      DijkstraLedger.conwayToDijkstraLedgerPredFailure(failure)

  /** `Dijkstra/Rules/Ledger.hs:126-127`, cardano-ledger 9df9f98c:
    * `shelleyToDijkstraLedgerPredFailure`.
    */
  given dijkstraShelley: InjectLedgerFailure[ShelleyLedgerPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: ShelleyLedgerPredFailure[Dijkstra]): LedgerPredFailure[Dijkstra] =
      DijkstraLedger.shelleyToDijkstraLedgerPredFailure(failure)

  /** `Dijkstra/Rules/Ledger.hs:129-166`, cardano-ledger 9df9f98c: UTXOW's failure and each failure
    * below it, `DijkstraUtxowFailure . injectFailure`, as one instance over UTXOW's injection
    * ([[InjectUtxowFailure]]), whose instances at Dijkstra are those failures, Shelley's, Alonzo's,
    * Babbage's and Conway's UTXO failures over the constructors Dijkstra's UTXO reaches.
    */
  given dijkstraUtxow[F](using
      inject: InjectUtxowFailure[F, Dijkstra]
  ): InjectLedgerFailure[F, Dijkstra] with
    def injectFailure(failure: F): LedgerPredFailure[Dijkstra] =
      DijkstraLedgerPredFailure.DijkstraUtxowFailure(inject.injectFailure(failure))

  /** `Dijkstra/Rules/Ledger.hs:168-187`, cardano-ledger 9df9f98c and `:192-193`: ENTITIES' failure
    * and each failure below it, `DijkstraEntitiesFailure . injectFailure`, as one instance over
    * ENTITIES' injection ([[InjectEntitiesFailure]]): CERTS's, CERT's, DELEG's, Shelley's and
    * Dijkstra's POOL's, and Dijkstra's and Conway's GOVCERT's.
    */
  given dijkstraEntities[F](using
      inject: InjectEntitiesFailure[F, Dijkstra]
  ): InjectLedgerFailure[F, Dijkstra] with
    def injectFailure(failure: F): LedgerPredFailure[Dijkstra] =
      DijkstraLedgerPredFailure.DijkstraEntitiesFailure(inject.injectFailure(failure))

  /** `Dijkstra/Rules/Ledger.hs:189-190`, cardano-ledger 9df9f98c: `DijkstraGovFailure`, after GOV's
    * identity.
    */
  given dijkstraGov: InjectLedgerFailure[DijkstraGovPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: DijkstraGovPredFailure[Dijkstra]): LedgerPredFailure[Dijkstra] =
      DijkstraLedgerPredFailure.DijkstraGovFailure(failure)

  /** `Dijkstra/Rules/Ledger.hs:195-196`, cardano-ledger 9df9f98c: `DijkstraGovFailure`, after GOV's
    * injection ([[InjectGov.dijkstra]]).
    */
  given dijkstraConwayGov: InjectLedgerFailure[ConwayGovPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: ConwayGovPredFailure[Dijkstra]): LedgerPredFailure[Dijkstra] =
      DijkstraLedgerPredFailure.DijkstraGovFailure(InjectGov.dijkstra.injectFailure(failure))

  /** `Dijkstra/Rules/Ledger.hs:198-199`, cardano-ledger 9df9f98c: `DijkstraSubLedgersFailure`,
    * after SUBLEDGERS' identity.
    */
  given dijkstraSubLedgers: InjectLedgerFailure[DijkstraSubLedgersPredFailure[Dijkstra], Dijkstra]
  with
    def injectFailure(
        failure: DijkstraSubLedgersPredFailure[Dijkstra]
    ): LedgerPredFailure[Dijkstra] = DijkstraLedgerPredFailure.DijkstraSubLedgersFailure(
      InjectSubLedgersFailure.dijkstra.injectFailure(failure)
    )

  /** `Dijkstra/Rules/Ledger.hs:201-205`, cardano-ledger 9df9f98c: SUBENTITIES' failure,
    * `SubEntitiesFailure`, then `SubLedgerFailure`, then the injection of SUBLEDGERS' failure.
    */
  given dijkstraSubEntities: InjectLedgerFailure[SubEntitiesPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: SubEntitiesPredFailure[Dijkstra]): LedgerPredFailure[Dijkstra] =
      dijkstraSubLedgers.injectFailure(
        DijkstraSubLedgersPredFailure.SubLedgerFailure(
          DijkstraSubLedgerPredFailure.SubEntitiesFailure(failure)
        )
      )

/** `Embed (UTXOW era) (LEDGER era)`, `Embed (CERTS era) (LEDGER era)` and `Embed (GOV era) (LEDGER
  * era)` (`Conway/Rules/Ledger.hs:521-601`, cardano-ledger f649f975): how the failures and events
  * of the rules LEDGER runs sit in LEDGER's.
  */
trait EmbedInLedger[E <: EraTag]:
  def wrapUtxowFailed(failure: UtxowPredFailure[E]): LedgerPredFailure[E]
  def wrapUtxowEvent(event: UtxowEvent[E]): LedgerEvent[E]
  def wrapCertsFailed(failure: CertsPredFailure[E]): LedgerPredFailure[E]
  def wrapCertsEvent(event: CertsEvent[E]): LedgerEvent[E]
  def wrapGovFailed(failure: GovPredFailure[E]): LedgerPredFailure[E]
  def wrapGovEvent(event: GovEvent[E]): LedgerEvent[E]

object EmbedInLedger:
  /** Conway's: `ConwayUtxowFailure` with `UtxowEvent`, `ConwayCertsFailure` with `CertsEvent`, and
    * `ConwayGovFailure` with `GovEvent`.
    */
  given conway: EmbedInLedger[Conway] with
    def wrapUtxowFailed(failure: UtxowPredFailure[Conway]): LedgerPredFailure[Conway] =
      ConwayLedgerPredFailure.ConwayUtxowFailure(failure)
    def wrapUtxowEvent(event: UtxowEvent[Conway]): LedgerEvent[Conway] =
      ConwayLedgerEvent.UtxowEvent(event)
    def wrapCertsFailed(failure: CertsPredFailure[Conway]): LedgerPredFailure[Conway] =
      ConwayLedgerPredFailure.ConwayCertsFailure(failure)
    def wrapCertsEvent(event: CertsEvent[Conway]): LedgerEvent[Conway] =
      ConwayLedgerEvent.CertsEvent(event)
    def wrapGovFailed(failure: GovPredFailure[Conway]): LedgerPredFailure[Conway] =
      ConwayLedgerPredFailure.ConwayGovFailure(failure)
    def wrapGovEvent(event: GovEvent[Conway]): LedgerEvent[Conway] =
      ConwayLedgerEvent.GovEvent(event)

/** `Embed (LEDGER era) (LEDGERS era)` (`Conway/Rules/Ledger.hs:562-587`, cardano-ledger f649f975):
  * Shelley's `LedgerFailure` and `LedgerEvent`.
  */
trait EmbedLedgerInLedgers[E <: EraTag]:
  def wrapFailed(failure: LedgerPredFailure[E]): LedgersPredFailure[E]
  def wrapEvent(event: LedgerEvent[E]): LedgersEvent[E]

object EmbedLedgerInLedgers:
  given conway: EmbedLedgerInLedgers[Conway] with
    def wrapFailed(failure: LedgerPredFailure[Conway]): LedgersPredFailure[Conway] =
      ShelleyLedgersPredFailure.LedgerFailure(failure)
    def wrapEvent(event: LedgerEvent[Conway]): LedgersEvent[Conway] =
      ShelleyLedgersEvent.LedgerEvent(event)

  /** `Embed (LEDGER era) (Babbage.LEDGERS era)` (`Dijkstra/Rules/Ledger.hs:510-518`, cardano-ledger
    * 9df9f98c): Shelley's `LedgerFailure` and `LedgerEvent`.
    */
  given dijkstra: EmbedLedgerInLedgers[Dijkstra] with
    def wrapFailed(failure: LedgerPredFailure[Dijkstra]): LedgersPredFailure[Dijkstra] =
      ShelleyLedgersPredFailure.LedgerFailure(failure)
    def wrapEvent(event: LedgerEvent[Dijkstra]): LedgersEvent[Dijkstra] =
      ShelleyLedgersEvent.LedgerEvent(event)

/** `InjectRuleFailure "LEDGERS" f era` (`Conway/Rules/Ledgers.hs:28-82`, cardano-ledger f649f975):
  * how era `E` 's LEDGERS reports a failure `F` of its own or of a rule below it. One instance per
  * era and failure type, in the companion. Shelley's LEDGER failure has none, as in the reference.
  * Contravariant like LEDGER's ([[InjectLedgerFailure]]): an instance for a failure type, or for
  * the union of the constructors an era reaches, injects each of its constructors.
  */
trait InjectLedgersFailure[-F, E <: EraTag]:
  def injectFailure(failure: F): LedgersPredFailure[E]

object InjectLedgersFailure:
  /** `Conway/Rules/Ledgers.hs:28` (cardano-ledger f649f975): the identity, the class's default. */
  given conway: InjectLedgersFailure[ShelleyLedgersPredFailure[Conway], Conway] with
    def injectFailure(failure: ShelleyLedgersPredFailure[Conway]): LedgersPredFailure[Conway] =
      failure

  /** `Conway/Rules/Ledgers.hs:30-31` (cardano-ledger f649f975): `LedgerFailure`. */
  given conwayLedger: InjectLedgersFailure[ConwayLedgerPredFailure[Conway], Conway] with
    def injectFailure(failure: ConwayLedgerPredFailure[Conway]): LedgersPredFailure[Conway] =
      ShelleyLedgersPredFailure.LedgerFailure(failure)

  /** `Conway/Rules/Ledgers.hs:33-58` and `:63-64` (cardano-ledger f649f975): UTXOW's failure and
    * each failure below it but Shelley's UTXO failure, `LedgerFailure . injectFailure`, as one
    * instance over UTXOW's injection ([[InjectUtxowFailure]]) through LEDGER's
    * ([[InjectLedgerFailure.conwayUtxow]]).
    */
  given conwayUtxow[F](using inject: InjectUtxowFailure[F, Conway]): InjectLedgersFailure[F, Conway]
  with
    def injectFailure(failure: F): LedgersPredFailure[Conway] =
      ShelleyLedgersPredFailure.LedgerFailure(
        InjectLedgerFailure.conwayUtxow[F].injectFailure(failure)
      )

  /** `Conway/Rules/Ledgers.hs:60-61` (cardano-ledger f649f975): Shelley's UTXO failures by the
    * build's injection into UTXO's ([[InjectLedgerFailure.conwayShelleyUtxo]]), then wrapped.
    */
  def conwayShelleyUtxo(
      inject: InjectShelleyUtxoFailure[Conway]
  ): InjectLedgersFailure[ShelleyUtxoPredFailure[Conway], Conway] =
    new InjectLedgersFailure[ShelleyUtxoPredFailure[Conway], Conway]:
      def injectFailure(failure: ShelleyUtxoPredFailure[Conway]): LedgersPredFailure[Conway] =
        ShelleyLedgersPredFailure.LedgerFailure(
          InjectLedgerFailure.conwayShelleyUtxo(inject).injectFailure(failure)
        )

  /** `Conway/Rules/Ledgers.hs:66-79` (cardano-ledger f649f975): CERTS's failure and each failure
    * below it, `LedgerFailure . injectFailure`, as one instance over CERTS's injection
    * ([[InjectCertsFailure]]) through LEDGER's ([[InjectLedgerFailure.conwayCerts]]).
    */
  given conwayCerts[F](using inject: InjectCertsFailure[F, Conway]): InjectLedgersFailure[F, Conway]
  with
    def injectFailure(failure: F): LedgersPredFailure[Conway] =
      ShelleyLedgersPredFailure.LedgerFailure(
        InjectLedgerFailure.conwayCerts[F].injectFailure(failure)
      )

  /** `Conway/Rules/Ledgers.hs:81-82` (cardano-ledger f649f975): `LedgerFailure . injectFailure`. */
  given conwayGov: InjectLedgersFailure[ConwayGovPredFailure[Conway], Conway] with
    def injectFailure(failure: ConwayGovPredFailure[Conway]): LedgersPredFailure[Conway] =
      ShelleyLedgersPredFailure.LedgerFailure(InjectLedgerFailure.conwayGov.injectFailure(failure))

  private def through[F](using
      inject: InjectLedgerFailure[F, Dijkstra]
  ): InjectLedgersFailure[F, Dijkstra] =
    new InjectLedgersFailure[F, Dijkstra]:
      def injectFailure(failure: F): LedgersPredFailure[Dijkstra] =
        ShelleyLedgersPredFailure.LedgerFailure(inject.injectFailure(failure))

  /** `Dijkstra/Rules/Ledgers.hs:25`, cardano-ledger 9df9f98c: the identity. */
  given dijkstra: InjectLedgersFailure[ShelleyLedgersPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: ShelleyLedgersPredFailure[Dijkstra]): LedgersPredFailure[Dijkstra] =
      failure

  /** `Dijkstra/Rules/Ledgers.hs:27-28`, cardano-ledger 9df9f98c: `LedgerFailure`. */
  given dijkstraLedger: InjectLedgersFailure[DijkstraLedgerPredFailure[Dijkstra], Dijkstra] =
    through(using InjectLedgerFailure.dijkstra)

  /** `Dijkstra/Rules/Ledgers.hs:30-31`, cardano-ledger 9df9f98c. */
  given dijkstraUtxow: InjectLedgersFailure[DijkstraUtxowPredFailure[Dijkstra], Dijkstra] =
    through[DijkstraUtxowPredFailure[Dijkstra]]

  /** `Dijkstra/Rules/Ledgers.hs:33-34`, cardano-ledger 9df9f98c. */
  given dijkstraBabbageUtxow: InjectLedgersFailure[BabbageUtxowPredFailure[Dijkstra], Dijkstra] =
    through[BabbageUtxowPredFailure[Dijkstra]]

  /** `Dijkstra/Rules/Ledgers.hs:36-37`, cardano-ledger 9df9f98c. */
  given dijkstraAlonzoUtxow: InjectLedgersFailure[AlonzoUtxowPredFailure[Dijkstra], Dijkstra] =
    through[AlonzoUtxowPredFailure[Dijkstra]]

  /** `Dijkstra/Rules/Ledgers.hs:39-40`, cardano-ledger 9df9f98c. */
  given dijkstraShelleyUtxow: InjectLedgersFailure[ShelleyUtxowPredFailure[Dijkstra], Dijkstra] =
    through[ShelleyUtxowPredFailure[Dijkstra]]

  /** `Dijkstra/Rules/Ledgers.hs:42-43`, cardano-ledger 9df9f98c. */
  given dijkstraUtxo: InjectLedgersFailure[DijkstraUtxoPredFailure[Dijkstra], Dijkstra] =
    through[DijkstraUtxoPredFailure[Dijkstra]]

  /** `Dijkstra/Rules/Ledgers.hs:45-46`, cardano-ledger 9df9f98c, on the constructors Dijkstra's
    * UTXO reaches.
    */
  given dijkstraBabbageUtxo: InjectLedgersFailure[DijkstraUtxo.BabbageFailure[Dijkstra], Dijkstra] =
    through[DijkstraUtxo.BabbageFailure[Dijkstra]](using
      InjectLedgerFailure.dijkstraUtxow[DijkstraUtxo.BabbageFailure[Dijkstra]](using
        InjectUtxowFailure.dijkstraUtxo[DijkstraUtxo.BabbageFailure[Dijkstra]](using
          InjectUtxoFailure.dijkstraBabbage
        )
      )
    )

  /** `Dijkstra/Rules/Ledgers.hs:48-49`, cardano-ledger 9df9f98c, on the constructors Dijkstra's
    * UTXO reaches.
    */
  given dijkstraAlonzoUtxo: InjectLedgersFailure[DijkstraUtxo.AlonzoFailure[Dijkstra], Dijkstra] =
    through[DijkstraUtxo.AlonzoFailure[Dijkstra]](using
      InjectLedgerFailure.dijkstraUtxow[DijkstraUtxo.AlonzoFailure[Dijkstra]](using
        InjectUtxowFailure.dijkstraUtxo[DijkstraUtxo.AlonzoFailure[Dijkstra]](using
          InjectUtxoFailure.dijkstraAlonzo
        )
      )
    )

  /** `Dijkstra/Rules/Ledgers.hs:51-52`, cardano-ledger 9df9f98c. */
  given dijkstraAlonzoUtxos: InjectLedgersFailure[AlonzoUtxosPredFailure[Dijkstra], Dijkstra] =
    through[AlonzoUtxosPredFailure[Dijkstra]]

  /** `Dijkstra/Rules/Ledgers.hs:54-55`, cardano-ledger 9df9f98c. */
  given dijkstraUtxos: InjectLedgersFailure[ConwayUtxosPredFailure[Dijkstra], Dijkstra] =
    through[ConwayUtxosPredFailure[Dijkstra]]

  /** `Dijkstra/Rules/Ledgers.hs:57-58`, cardano-ledger 9df9f98c, on the constructors Dijkstra's
    * UTXO reaches.
    */
  given dijkstraShelleyUtxo: InjectLedgersFailure[DijkstraUtxo.ShelleyFailure[Dijkstra], Dijkstra] =
    through[DijkstraUtxo.ShelleyFailure[Dijkstra]](using
      InjectLedgerFailure.dijkstraUtxow[DijkstraUtxo.ShelleyFailure[Dijkstra]](using
        InjectUtxowFailure.dijkstraUtxo[DijkstraUtxo.ShelleyFailure[Dijkstra]](using
          InjectUtxoFailure.dijkstraShelley
        )
      )
    )

  /** `Dijkstra/Rules/Ledgers.hs:60-61`, cardano-ledger 9df9f98c, on the constructors Dijkstra's
    * UTXO reaches.
    */
  given dijkstraAllegraUtxo: InjectLedgersFailure[DijkstraUtxo.AllegraFailure[Dijkstra], Dijkstra] =
    through[DijkstraUtxo.AllegraFailure[Dijkstra]](using
      InjectLedgerFailure.dijkstraUtxow[DijkstraUtxo.AllegraFailure[Dijkstra]](using
        InjectUtxowFailure.dijkstraUtxo[DijkstraUtxo.AllegraFailure[Dijkstra]](using
          InjectUtxoFailure.dijkstraAllegra
        )
      )
    )

  /** `Dijkstra/Rules/Ledgers.hs:63-64`, cardano-ledger 9df9f98c. */
  given dijkstraCerts: InjectLedgersFailure[ConwayCertsPredFailure[Dijkstra], Dijkstra] =
    through[ConwayCertsPredFailure[Dijkstra]]

  /** `Dijkstra/Rules/Ledgers.hs:66-67`, cardano-ledger 9df9f98c. */
  given dijkstraCert: InjectLedgersFailure[ConwayCertPredFailure[Dijkstra], Dijkstra] =
    through[ConwayCertPredFailure[Dijkstra]]

  /** `Dijkstra/Rules/Ledgers.hs:69-70`, cardano-ledger 9df9f98c. */
  given dijkstraDeleg: InjectLedgersFailure[ConwayDelegPredFailure[Dijkstra], Dijkstra] =
    through[ConwayDelegPredFailure[Dijkstra]]

  /** `Dijkstra/Rules/Ledgers.hs:72-73`, cardano-ledger 9df9f98c. */
  given dijkstraShelleyPool: InjectLedgersFailure[ShelleyPoolPredFailure[Dijkstra], Dijkstra] =
    through[ShelleyPoolPredFailure[Dijkstra]]

  /** `Dijkstra/Rules/Ledgers.hs:75-76`, cardano-ledger 9df9f98c. */
  given dijkstraGovCert: InjectLedgersFailure[DijkstraGovCertPredFailure[Dijkstra], Dijkstra] =
    through[DijkstraGovCertPredFailure[Dijkstra]]

  /** `Dijkstra/Rules/Ledgers.hs:78-79`, cardano-ledger 9df9f98c. */
  given dijkstraConwayGov: InjectLedgersFailure[ConwayGovPredFailure[Dijkstra], Dijkstra] =
    through[ConwayGovPredFailure[Dijkstra]]

  /** `Dijkstra/Rules/Ledgers.hs:81-82`, cardano-ledger 9df9f98c. */
  given dijkstraGov: InjectLedgersFailure[DijkstraGovPredFailure[Dijkstra], Dijkstra] =
    through[DijkstraGovPredFailure[Dijkstra]]

/** What a LEDGER failure's flat form cannot hold without what the transaction gives: UTXOW's
  * ([[UtxowUnprojected]]), and an action of GOV's failure that none of the transaction's proposals
  * was read as ([[GovActionNotRead]]).
  */
enum LedgerUnprojected:
  case Utxow(cause: UtxowUnprojected)
  case Gov(cause: GovActionNotRead[Conway])

object ConwayLedgerPredFailure:
  given [E <: EraTag]: CanEqual[ConwayLedgerPredFailure[E], ConwayLedgerPredFailure[E]] =
    CanEqual.derived

  /** The failure in the node's flat form (`PredicateFailure`), glue the comparisons with the flat
    * rules and the node's encoder read until the era's failures have their encoders (#85): UTXOW's
    * through [[ConwayUtxowPredFailure.toStored]], a missing redeemer's purpose by `purpose`,
    * CERTS's through [[ConwayCertsPredFailure.toStored]], and GOV's through
    * [[ConwayGovPredFailure.toStored]], an action by `read`. It is one-way.
    */
  def toStored(
      failure: ConwayLedgerPredFailure[Conway],
      purpose: ConwayPlutusPurpose[AsItem] => Option[Purpose],
      read: GovAction[Conway] => Option[FlatGovAction]
  ): Either[LedgerUnprojected, PredicateFailure] =
    failure match
      case ConwayUtxowFailure(f) =>
        ConwayUtxowPredFailure.toStored(f, purpose).leftMap(LedgerUnprojected.Utxow(_))
      case ConwayCertsFailure(f) => Right(ConwayCertsPredFailure.toStored(f))
      case ConwayGovFailure(f) =>
        ConwayGovPredFailure.toStored(f, read).leftMap(LedgerUnprojected.Gov(_))
      case ConwayWdrlNotDelegatedToDRep(keys) =>
        Right(PredicateFailure.WdrlNotDelegatedToDRep(keys.toList.toVector.map(_.bytes)))
      case ConwayTreasuryValueMismatch(m) => Right(PredicateFailure.TreasuryValueMismatch(coins(m)))
      case ConwayTxRefScriptsSizeTooBig(m) =>
        Right(PredicateFailure.TxRefScriptsSizeTooBig(m.stored))
      case ConwayMempoolFailure(p) => Right(PredicateFailure.Mempool(p))
      case ConwayWithdrawalsMissingAccounts(ws) =>
        Right(
          PredicateFailure.WithdrawalsMissingAccounts(
            ws.map((account, coin) => Convert.rewardAccountBack(account) -> coin.value)
          )
        )
      case ConwayIncompleteWithdrawals(ms) =>
        Right(
          PredicateFailure.IncompleteWithdrawals(
            ms.toSortedMap.toMap.map((account, m) => Convert.rewardAccountBack(account) -> coins(m))
          )
        )

object ShelleyLedgerPredFailure:
  given [E <: EraTag]: CanEqual[ShelleyLedgerPredFailure[E], ShelleyLedgerPredFailure[E]] =
    CanEqual.derived

object ShelleyLedgersPredFailure:
  given [E <: EraTag]: CanEqual[ShelleyLedgersPredFailure[E], ShelleyLedgersPredFailure[E]] =
    CanEqual.derived
