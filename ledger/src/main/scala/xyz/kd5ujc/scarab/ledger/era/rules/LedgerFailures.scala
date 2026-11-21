// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.data.{NonEmptyList, NonEmptyMap}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{
  GovAction as FlatGovAction,
  MempoolProblem,
  Mismatch,
  PredicateFailure,
  Purpose
}
import xyz.kd5ujc.scarab.ledger.era.GovAction
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayPlutusPurpose
import xyz.kd5ujc.scarab.ledger.era.AsItem
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{AccountAddress, Coin, Conway, EraTag, KeyHash, Staking}
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
  * `Conway/Rules/Ledger.hs:189-196`, cardano-ledger f649f975), which this type makes so. The eras
  * before Conway add their case.
  */
type DelegsPredFailure[E <: EraTag] = E match
  case Conway => Nothing

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
  case ShelleyIncompleteWithdrawals(mismatches: NonEmptyMap[AccountAddress, Mismatch[Coin]])

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
  case ConwayTreasuryValueMismatch(mismatch: Mismatch[Coin])

  /** The reference scripts' size (supplied) and the parameters' limit (expected), as the
    * reference's 64-bit `Int`.
    */
  case ConwayTxRefScriptsSizeTooBig(mismatch: Mismatch[Long])
  case ConwayMempoolFailure(problem: MempoolProblem)
  case ConwayWithdrawalsMissingAccounts(withdrawals: Map[AccountAddress, Coin])

  /** Each withdrawal that does not drain its account: the amount (supplied) and the balance
    * (expected).
    */
  case ConwayIncompleteWithdrawals(mismatches: NonEmptyMap[AccountAddress, Mismatch[Coin]])

/** `EraRuleFailure "LEDGER" era`: Conway's is its own (`Conway/Rules/Ledger.hs:129`, cardano-ledger
  * f649f975).
  */
type LedgerPredFailure[E <: EraTag] = E match
  case Conway => ConwayLedgerPredFailure[Conway]

/** `data ConwayLedgerEvent era` (`Conway/Rules/Ledger.hs:262-266`, cardano-ledger f649f975). */
enum ConwayLedgerEvent[E <: EraTag]:
  case UtxowEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.UtxowEvent[E])
  case CertsEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.CertsEvent[E])
  case GovEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.GovEvent[E])

/** `EraRuleEvent "LEDGER" era`: Conway's is its own (`Conway/Rules/Ledger.hs:131`, cardano-ledger
  * f649f975).
  */
type LedgerEvent[E <: EraTag] = E match
  case Conway => ConwayLedgerEvent[Conway]

/** `newtype ShelleyLedgersPredFailure era` (`Shelley/Rules/Ledgers.hs:89-91`, cardano-ledger
  * f649f975), Conway's LEDGERS failure (`Conway/Rules/Ledgers.hs`).
  */
enum ShelleyLedgersPredFailure[E <: EraTag]:
  case LedgerFailure(failure: LedgerPredFailure[E])

/** `EraRuleFailure "LEDGERS" era`: Conway's is Shelley's (`Conway/Rules/Ledgers.hs:24`,
  * cardano-ledger f649f975).
  */
type LedgersPredFailure[E <: EraTag] = E match
  case Conway => ShelleyLedgersPredFailure[Conway]

/** `newtype ShelleyLedgersEvent era` (`Shelley/Rules/Ledgers.hs:127-129`, cardano-ledger f649f975).
  */
enum ShelleyLedgersEvent[E <: EraTag]:
  case LedgerEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.LedgerEvent[E])

/** `EraRuleEvent "LEDGERS" era`: Conway's is Shelley's (`Conway/Rules/Ledgers.hs:26`,
  * cardano-ledger f649f975).
  */
type LedgersEvent[E <: EraTag] = E match
  case Conway => ShelleyLedgersEvent[Conway]

/** `InjectRuleFailure "LEDGER" f era` (`Conway/Rules/Ledger.hs:133-187`, cardano-ledger f649f975):
  * how era `E` 's LEDGER reports a failure `F` of its own or of a rule below it. One instance per
  * era and failure type, in the companion.
  */
trait InjectLedgerFailure[F, E <: EraTag]:
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

/** `InjectRuleFailure "LEDGERS" f era` (`Conway/Rules/Ledgers.hs:28-82`, cardano-ledger f649f975):
  * how era `E` 's LEDGERS reports a failure `F` of its own or of a rule below it. One instance per
  * era and failure type, in the companion. Shelley's LEDGER failure has none, as in the reference.
  */
trait InjectLedgersFailure[F, E <: EraTag]:
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
    def coins(m: Mismatch[Coin]): Mismatch[BigInt] = Mismatch(m.supplied.value, m.expected.value)
    failure match
      case ConwayUtxowFailure(f) =>
        ConwayUtxowPredFailure.toStored(f, purpose).leftMap(LedgerUnprojected.Utxow(_))
      case ConwayCertsFailure(f) => Right(ConwayCertsPredFailure.toStored(f))
      case ConwayGovFailure(f) =>
        ConwayGovPredFailure.toStored(f, read).leftMap(LedgerUnprojected.Gov(_))
      case ConwayWdrlNotDelegatedToDRep(keys) =>
        Right(PredicateFailure.WdrlNotDelegatedToDRep(keys.toList.toVector.map(_.bytes)))
      case ConwayTreasuryValueMismatch(m) => Right(PredicateFailure.TreasuryValueMismatch(coins(m)))
      case ConwayTxRefScriptsSizeTooBig(m) => Right(PredicateFailure.TxRefScriptsSizeTooBig(m))
      case ConwayMempoolFailure(p)         => Right(PredicateFailure.Mempool(p))
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
