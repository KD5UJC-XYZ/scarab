// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import scala.annotation.unused
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{TranslationError, TxIn, Unsigned}
import xyz.kd5ujc.scarab.ledger.state.{Babbage, Conway, Dijkstra, EraTag, TxIx}
import xyz.kd5ujc.scarab.plutus.PlutusData

// The translation of a transaction to the script context of a Plutus language
// (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Plutus/Context.hs`, cardano-ledger 9df9f98c, whose
// shape 29a7d656 shares and f649f975 does not: decided 2026-11-20, #85).

/** `class Inject t s` (`libs/cardano-ledger-core/src/Cardano/Ledger/BaseTypes.hs:1032-1033`,
  * cardano-ledger f649f975): a value of `A` as one of `B`, as an era's context error carries an
  * earlier era's.
  */
trait Inject[A, B]:
  def inject(a: A): B

object Inject:
  /** `instance Inject a a` (`BaseTypes.hs:1035-1036`, cardano-ledger f649f975). */
  given self[A]: Inject[A, A] = a => a

  /** `instance Inject (AlonzoContextError era) (BabbageContextError era)`
    * (`Babbage/TxInfo.hs:272-273`, cardano-ledger f649f975; `:285-286` at 9df9f98c), Babbage's
    * context error being its own.
    */
  given babbageAlonzo: Inject[AlonzoContextError[Babbage], ContextError[Babbage]] =
    BabbageContextError.AlonzoContextError(_)

  /** `instance Inject (BabbageContextError era) (ConwayContextError era)`
    * (`Conway/TxInfo.hs:209-210`, cardano-ledger f649f975).
    */
  given conwayBabbage: Inject[BabbageContextError[Conway], conway.ConwayContextError[Conway]] =
    conway.ConwayContextError.BabbageContextError(_)

  /** `instance Inject (AlonzoContextError era) (ConwayContextError era)`
    * (`Conway/TxInfo.hs:230-234`, cardano-ledger 9df9f98c): an unsupported certificate or purpose
    * as Conway's own, which f649f975's Conway raises itself (`Conway/TxInfo.hs:397, 645, 739`), the
    * rest through Babbage's (`:212-213` at f649f975), so Conway's errors are the same at both.
    */
  given conwayAlonzo: Inject[AlonzoContextError[Conway], conway.ConwayContextError[Conway]] =
    _ match
      case AlonzoContextError.CertificateNotSupported(txCert) =>
        conway.ConwayContextError.CertificateNotSupported(txCert)
      case AlonzoContextError.PlutusPurposeNotSupported(purpose) =>
        conway.ConwayContextError.PlutusPurposeNotSupported(purpose)
      case a @ (AlonzoContextError.TranslationLogicMissingInput(_) |
          AlonzoContextError.TimeTranslationPastHorizon(_) | AlonzoContextError.Undecodable(_, _) |
          AlonzoContextError.ImpossibleContextError(_)) =>
        conway.ConwayContextError.BabbageContextError(BabbageContextError.AlonzoContextError(a))

  /** `instance Inject (ConwayContextError era) (DijkstraContextError era)`
    * (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxInfo.hs:290-291`, cardano-ledger 9df9f98c).
    */
  given dijkstraConway: Inject[conway.ConwayContextError[Dijkstra], ContextError[Dijkstra]] =
    dijkstra.DijkstraContextError.ConwayContextError(_)

  /** `instance Inject (BabbageContextError era) (DijkstraContextError era)` (`TxInfo.hs:293-294`,
    * cardano-ledger 9df9f98c): through Conway's.
    */
  given dijkstraBabbage: Inject[BabbageContextError[Dijkstra], ContextError[Dijkstra]] = b =>
    dijkstra.DijkstraContextError.ConwayContextError(
      conway.ConwayContextError.BabbageContextError(b)
    )

  /** `instance Inject (AlonzoContextError era) (DijkstraContextError era)` (`TxInfo.hs:296-297`,
    * cardano-ledger 9df9f98c): through Babbage's and Conway's.
    */
  given dijkstraAlonzo: Inject[AlonzoContextError[Dijkstra], ContextError[Dijkstra]] = a =>
    dijkstra.DijkstraContextError.ConwayContextError(
      conway.ConwayContextError.BabbageContextError(BabbageContextError.AlonzoContextError(a))
    )

/** The context error of era `E` (`type ContextError era`, `Context.hs:198`, cardano-ledger
  * f649f975): Babbage's is `BabbageContextError BabbageEra` (`Babbage/TxInfo.hs:229`,
  * cardano-ledger f649f975; `:232` at 9df9f98c), Conway's is `ConwayContextError ConwayEra`
  * (`Conway/TxInfo.hs:150`), Dijkstra's `DijkstraContextError DijkstraEra`
  * (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxInfo.hs:300`, cardano-ledger 9df9f98c).
  */
type ContextError[E <: EraTag] = E match
  case Babbage  => BabbageContextError[Babbage]
  case Conway   => conway.ConwayContextError[Conway]
  case Dijkstra => dijkstra.DijkstraContextError[Dijkstra]

/** The certificate of a language's context (`type family PlutusTxCert`, `Context.hs:264-268`,
  * cardano-ledger 9df9f98c).
  */
type PlutusTxCert[L <: Language] = L match
  case Language.PlutusV1 => PV1.DCert
  case Language.PlutusV2 => PV1.DCert
  case Language.PlutusV3 => PV3.TxCert
  case Language.PlutusV4 => PV4.TxCert

/** The script purpose of a language's context (`type family PlutusScriptPurpose`,
  * `Context.hs:270-274`, cardano-ledger 9df9f98c).
  */
type PlutusScriptPurpose[L <: Language] = L match
  case Language.PlutusV1 => PV1.ScriptPurpose
  case Language.PlutusV2 => PV1.ScriptPurpose
  case Language.PlutusV3 => PV3.ScriptPurpose
  case Language.PlutusV4 => PV4.ScriptPurpose

/** The transaction information of a language's context (`type family PlutusTxInfo`,
  * `Context.hs:276-280`, cardano-ledger 9df9f98c).
  */
type PlutusTxInfo[L <: Language] = L match
  case Language.PlutusV1 => PV1.TxInfo
  case Language.PlutusV2 => PV2.TxInfo
  case Language.PlutusV3 => PV3.TxInfo
  case Language.PlutusV4 => PV4.TxInfo

/** An input of a language's context (`type family PlutusTxInInfo era l`, `Context.hs:282-289`,
  * cardano-ledger 9df9f98c), without Alonzo's own case for Plutus V1 (`Maybe PV1.TxInInfo`): Alonzo
  * is not among the era types yet, and no later era has the case.
  */
type PlutusTxInInfo[E <: EraTag, L <: Language] = L match
  case Language.PlutusV1 => PV1.TxInInfo
  case Language.PlutusV2 => PV2.TxInInfo
  case Language.PlutusV3 => PV3.TxInInfo
  case Language.PlutusV4 => PV4.TxInInfo

/** The script context of a language (`type family PlutusScriptContext`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Language.hs:364-367`, cardano-ledger
  * f649f975).
  */
type PlutusScriptContext[L <: Language] = L match
  case Language.PlutusV1 => PV1.ScriptContext
  case Language.PlutusV2 => PV2.ScriptContext
  case Language.PlutusV3 => PV3.ScriptContext

/** The arguments of a Plutus V1 or V2 script (`data LegacyPlutusArgs l`,
  * `Plutus/Language.hs:369-383`, cardano-ledger f649f975): the redeemer and the context, the datum
  * first for a spending script.
  */
enum LegacyPlutusArgs[L <: Language]:
  case LegacyPlutusArgs2(redeemer: PlutusData, scriptContext: PlutusScriptContext[L])
  case LegacyPlutusArgs3(
      datum: PlutusData,
      redeemer: PlutusData,
      scriptContext: PlutusScriptContext[L]
  )

object LegacyPlutusArgs:
  /** `legacyPlutusArgsToData` (`Plutus/Language.hs:445-448`, cardano-ledger f649f975): the
    * arguments in the order the script takes them.
    */
  def legacyPlutusArgsToData[L <: Language](args: LegacyPlutusArgs[L])(using
      c: ToData[PlutusScriptContext[L]]
  ): Vector[PlutusData] = args match
    case LegacyPlutusArgs2(redeemer, scriptContext) =>
      Vector(redeemer, c.toBuiltinData(scriptContext))
    case LegacyPlutusArgs3(datum, redeemer, scriptContext) =>
      Vector(datum, redeemer, c.toBuiltinData(scriptContext))

/** `PlutusArgs 'PlutusV1` (`newtype PlutusArgs 'PlutusV1 = PlutusV1Args`, `Plutus/Language.hs:518`,
  * cardano-ledger f649f975).
  */
final case class PlutusV1Args(unPlutusV1Args: LegacyPlutusArgs[Language.PlutusV1])

/** `PlutusArgs 'PlutusV2` (`newtype PlutusArgs 'PlutusV2 = PlutusV2Args`, `Plutus/Language.hs:538`,
  * cardano-ledger f649f975).
  */
final case class PlutusV2Args(unPlutusV2Args: LegacyPlutusArgs[Language.PlutusV2])

/** `PlutusArgs 'PlutusV3` (`newtype PlutusArgs 'PlutusV3 = PlutusV3Args`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Language.hs:558`, cardano-ledger f649f975):
  * the script context, the one argument of a Plutus V3 script.
  */
final case class PlutusV3Args(unPlutusV3Args: PV3.ScriptContext)

/** `PlutusArgs 'PlutusV4` (`newtype PlutusArgs 'PlutusV4 = PlutusV4Args`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Language.hs:583`, cardano-ledger 9df9f98c):
  * the V4 script context, the one argument of a Plutus V4 script.
  */
final case class PlutusV4Args(unPlutusV4Args: PV4.ScriptContext)

/** The arguments of a language's script (`data PlutusArgs l`, `Plutus/Language.hs:463`,
  * cardano-ledger f649f975).
  */
type PlutusArgs[L <: Language] = L match
  case Language.PlutusV1 => PlutusV1Args
  case Language.PlutusV2 => PlutusV2Args
  case Language.PlutusV3 => PlutusV3Args
  case Language.PlutusV4 => PlutusV4Args

/** What a context needs of a transaction's level (`data LedgerLevelTxInfo level era`,
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Plutus/Context.hs:104-113`, cardano-ledger 9df9f98c,
  * the same at 29a7d656): at the top level, the memoised information of each sub-transaction by its
  * id, which only Dijkstra fills; a sub-transaction's index among its top level's. Only Dijkstra
  * has sub-transactions, so the sub-transaction's case is of Dijkstra alone, and the level of an
  * era with the top level only is the top level, as the reference's `withTopTxLevelOnly` shows it.
  */
enum LedgerLevelTxInfo[L <: TxLevel, E <: EraTag]:
  case LedgerTopTxInfo[E <: EraTag](subTxInfoResults: Map[Bytes, TxInfoResult[E]])
      extends LedgerLevelTxInfo[TxLevel.Top, E]
  case LedgerSubTxInfo(txIx: TxIx) extends LedgerLevelTxInfo[TxLevel.Sub, Dijkstra]

/** What the ledger hands the translation of a transaction to a script context (`data LedgerTxInfo
  * level era`, `Context.hs:116-128`, cardano-ledger 9df9f98c, the same at 29a7d656): the protocol
  * version, the slot to POSIX time of the epoch information and the system start, the UTxO set, the
  * transaction of level `L`, its Plutus scripts used with their purposes, and what its level gives.
  * f649f975's `LedgerTxInfo era` (`Context.hs:92-103`) has neither the level nor the scripts used;
  * the shape is 9df9f98c's and 29a7d656's, with no change of behaviour (decided 2026-11-20, #85).
  *
  * The epoch information and the system start are one function here, the clock the flat translation
  * takes, whose failure is typed where the reference's is a `Text`. The script hashes by purpose
  * (`ltiScriptHashesUsed`) are derived from the scripts used, as every construction of the
  * reference's makes them (`toScriptHashByPurpose ltiScriptsUsed`), so the two cannot disagree;
  * they are computed when first asked for, as the reference's field is lazy.
  */
final case class LedgerTxInfo[L <: TxLevel, E <: EraTag](
    ltiProtVer: ProtVer,
    ltiSlotToPOSIXTime: BigInt => Either[TranslationError, BigInt],
    ltiUTxO: UTxO[E],
    ltiTx: Tx[L, E],
    ltiScriptsUsed: List[(PlutusPurpose[AsIxItem, E], SupportedPlutusRunnable[E])],
    ltiLevelTxInfo: LedgerLevelTxInfo[L, E]
)(using s: AlonzoEraScript[E]):
  /** `ltiScriptHashesUsed`: the hash of the script used for each purpose, by its index. */
  lazy val ltiScriptHashesUsed: Map[PlutusPurpose[AsIx, E], Bytes] =
    LedgerTxInfo.toScriptHashByPurpose(ltiScriptsUsed)

object LedgerTxInfo:
  /** `toScriptHashByPurpose` (`Context.hs:133-141`, cardano-ledger 9df9f98c): the hash of each
    * runnable script by its purpose's index, a later purpose of the same index replacing an earlier
    * one (`Map.fromList`).
    */
  def toScriptHashByPurpose[E <: EraTag](
      scriptsUsed: List[(PlutusPurpose[AsIxItem, E], SupportedPlutusRunnable[E])]
  )(using s: AlonzoEraScript[E]): Map[PlutusPurpose[AsIx, E], Bytes] =
    scriptsUsed.map { (sp, sr) =>
      s.hoistPlutusPurpose[AsIxItem, AsIx]([ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsIx(a))(
        sp
      ) -> sr.plutusRunnable.plutusRunnableScriptHash
    }.toMap

  /** `toSTxLevel` of a transaction's information (`instance HasEraTxLevel LedgerTxInfo era`,
    * `Context.hs:130-131`, cardano-ledger 9df9f98c): the era's evidence of its level.
    */
  def toSTxLevel[L <: TxLevel, E <: EraTag](lti: LedgerTxInfo[L, E])(using
      b: EraTxBody[E]
  ): b.Level[L] = b.levelOf(lti.ltiLevelTxInfo)

  /** `withTopTxLevelOnly` of a transaction's information (`Core/TxLevel.hs:96-102`, cardano-ledger
    * 9df9f98c), for an era with the top level only: `f` of the information, its level shown to be
    * the top level.
    */
  def withTopTxLevelOnly[T <: TxLevel, E <: EraTag, A](lti: LedgerTxInfo[T, E])(
      f: LedgerTxInfo[TxLevel.Top, E] => A
  )(using b: EraTxBody[E] { type Level[L <: TxLevel] = TopOnly[L] }): A =
    b.levelOf(lti.ltiLevelTxInfo) match
      case TopOnly.Top => f(lti)

  /** `withBothTxLevels` of a transaction's information (`Core/TxLevel.hs:123-130`, cardano-ledger
    * 9df9f98c), for an era with both levels: `fTop` of the information at the top level, `fSub` of
    * it at a sub-transaction's, its level read from the level's information where the reference's
    * reads the transaction's.
    */
  def withBothTxLevels[T <: TxLevel, E <: EraTag, A](lti: LedgerTxInfo[T, E])(
      fTop: LedgerTxInfo[TxLevel.Top, E] => A
  )(fSub: LedgerTxInfo[TxLevel.Sub, E] => A)(using
      b: EraTxBody[E] { type Level[L <: TxLevel] = BothLevels[L] }
  ): A =
    b.levelOf(lti.ltiLevelTxInfo) match
      case BothLevels.Top => fTop(lti)
      case BothLevels.Sub => fSub(lti)

/** The transaction information of language `L` for a transaction of era `E` (`newtype
  * PlutusTxInfoResult l era`, `Context.hs:193-196`, cardano-ledger 9df9f98c, the same at 29a7d656):
  * the information or its failure. f649f975's (`Context.hs:156-164`) is a function of the purpose,
  * which every era it has ignores, so the information is the same.
  */
final case class PlutusTxInfoResult[L <: Language, E <: EraTag](
    unPlutusTxInfoResult: Either[ContextError[E], PlutusTxInfo[L]]
)

/** The translation of a transaction of era `E` to the context of language `L` (`class
  * EraPlutusTxInfo (l :: Language) era`, `Context.hs:143-181`, cardano-ledger 9df9f98c, the same at
  * 29a7d656): of a transaction of any level, the purpose's and the arguments' translations taking
  * the transaction's information, where f649f975's take the protocol version
  * (`Context.hs:105-144`). An instance learns the level from the information
  * ([[LedgerTxInfo.toSTxLevel]]).
  */
trait EraPlutusTxInfo[L <: Language, E <: EraTag]:
  /** The language (the superclass `PlutusLanguage l`). */
  def plutusLanguage: PlutusLanguage[L]

  def toPlutusTxCert(pv: ProtVer, txCert: TxCert[E]): Either[ContextError[E], PlutusTxCert[L]]

  def toPlutusScriptPurpose[T <: TxLevel](
      lti: LedgerTxInfo[T, E],
      purpose: PlutusPurpose[AsIxItem, E]
  ): Either[ContextError[E], PlutusScriptPurpose[L]]

  def toPlutusTxInfo[T <: TxLevel](lti: LedgerTxInfo[T, E]): PlutusTxInfoResult[L, E]

  /** `toPlutusArgs`: the language's arguments for the purpose, the spending datum read by the
    * instance from the information's UTxO set and transaction (`getSpendingDatum`).
    */
  def toPlutusArgs[T <: TxLevel](
      lti: LedgerTxInfo[T, E],
      txInfo: PlutusTxInfo[L],
      purpose: PlutusPurpose[AsIxItem, E],
      redeemerData: Data
  ): Either[ContextError[E], PlutusArgs[L]]

  def toPlutusTxInInfo(utxo: UTxO[E], txIn: TxIn): Either[ContextError[E], PlutusTxInInfo[E, L]]

  /** The language's own information among the era's memoised ones: what `lookupTxInfoResult` of the
    * era's `EraPlutusContext` instance selects for the language (`Context.hs:245-252`,
    * cardano-ledger 9df9f98c). Each language's instance selects its own, so a language the era does
    * not support, the reference's `lookupTxInfoResultImpossible` (`Context.hs:256-260`), has no
    * instance to ask.
    */
  def lookupTxInfoResult(result: TxInfoResult[E]): PlutusTxInfoResult[L, E]

/** The instances of each era and language, the one place that lists them: Babbage's for Plutus V1
  * and V2 (`Babbage/TxInfo.hs:319-401`, cardano-ledger f649f975; `:359-435` at 9df9f98c), Conway's
  * for Plutus V1 to V3 (`Conway/TxInfo.hs:420-547`, cardano-ledger 9df9f98c) and Dijkstra's for
  * Plutus V1 to V4 (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxInfo.hs:333-484, 568-629`,
  * cardano-ledger 9df9f98c).
  */
object EraPlutusTxInfo:
  given babbagePlutusV1: EraPlutusTxInfo[Language.PlutusV1, Babbage] = BabbageTxInfo.plutusV1
  given babbagePlutusV2: EraPlutusTxInfo[Language.PlutusV2, Babbage] = BabbageTxInfo.plutusV2
  given conwayPlutusV1: EraPlutusTxInfo[Language.PlutusV1, Conway] =
    conway.ConwayTxInfo.plutusV1
  given conwayPlutusV2: EraPlutusTxInfo[Language.PlutusV2, Conway] =
    conway.ConwayTxInfo.plutusV2
  given conwayPlutusV3: conway.ConwayEraPlutusTxInfo[Language.PlutusV3, Conway] =
    conway.ConwayTxInfo.plutusV3
  given dijkstraPlutusV1: EraPlutusTxInfo[Language.PlutusV1, Dijkstra] =
    dijkstra.DijkstraTxInfo.plutusV1
  given dijkstraPlutusV2: EraPlutusTxInfo[Language.PlutusV2, Dijkstra] =
    dijkstra.DijkstraTxInfo.plutusV2
  given dijkstraPlutusV3: conway.ConwayEraPlutusTxInfo[Language.PlutusV3, Dijkstra] =
    dijkstra.DijkstraTxInfo.plutusV3
  given dijkstraPlutusV4: conway.ConwayEraPlutusTxInfo[Language.PlutusV4, Dijkstra] =
    dijkstra.DijkstraTxInfo.plutusV4

/** The memoised transaction information of every language era `E` supports (`data TxInfoResult
  * era`, `Context.hs:234`, cardano-ledger 9df9f98c): Babbage's of Plutus V1 and V2
  * (`Babbage/TxInfo.hs:233-236`; `:230-233` at f649f975), Conway's of Plutus V1 to V3
  * (`Conway/TxInfo.hs:160-164`), Dijkstra's of V1 to V4
  * (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxInfo.hs:302-307`).
  */
type TxInfoResult[E <: EraTag] = E match
  case Babbage  => BabbageTxInfoResult
  case Conway   => conway.ConwayTxInfoResult
  case Dijkstra => dijkstra.DijkstraTxInfoResult

/** A language era `E` supports, with its translation (`data SupportedLanguage era`,
  * `Context.hs:345-359`, cardano-ledger 9df9f98c): equal and ordered as its language is.
  */
sealed abstract class SupportedLanguage[E <: EraTag]:
  type L <: Language
  def plutusTxInfo: EraPlutusTxInfo[L, E]

  /** The language, as a value (`plutusLanguage` of its `SLanguage`). */
  final def language: xyz.kd5ujc.scarab.plutus.PlutusLanguage =
    plutusTxInfo.plutusLanguage.language

  override def equals(that: Any): Boolean = that match
    case o: SupportedLanguage[?] => language == o.language
    case _                       => false
  override def hashCode(): Int = language.hashCode
  override def toString: String = s"SupportedLanguage($language)"

object SupportedLanguage:
  /** `SupportedLanguage`, of a language whose translation `t` is. */
  def apply[E <: EraTag, L0 <: Language](t: EraPlutusTxInfo[L0, E]): SupportedLanguage[E] =
    new SupportedLanguage[E]:
      type L = L0
      def plutusTxInfo: EraPlutusTxInfo[L0, E] = t

  /** `Ord (SupportedLanguage era)` (`Context.hs:356-358`, cardano-ledger 9df9f98c): by the
    * language.
    */
  given ordering[E <: EraTag]: Ordering[SupportedLanguage[E]] = (a, b) =>
    Integer.compare(a.language.id, b.language.id)

/** The script contexts of era `E` 's languages (`class EraPlutusContext era`, `Context.hs:219-252`,
  * cardano-ledger 9df9f98c, the same at 29a7d656): the languages it supports, their runnable
  * scripts and their memoised information. The context error is the family [[ContextError]] and the
  * memoised information the family [[TxInfoResult]].
  */
trait EraPlutusContext[E <: EraTag]:
  /** `mkSupportedLanguage`: the language as one the era supports, none if it does not. */
  def mkSupportedLanguage(
      lang: xyz.kd5ujc.scarab.plutus.PlutusLanguage
  ): Option[SupportedLanguage[E]]

  /** `mkSupportedPlutusRunnable`: the era's Plutus script as a runnable one of its language, for
    * the major protocol version `v` ([[PlutusRunnable.decodePlutusRunnable]]).
    */
  def mkSupportedPlutusRunnable(
      v: Version,
      plutusScript: PlutusScript[E]
  ): SupportedPlutusRunnable[E]

  /** `mkTxInfoResult`: the information of every language the era supports, of a transaction of any
    * level, each computed when first looked up.
    */
  def mkTxInfoResult[T <: TxLevel](lti: LedgerTxInfo[T, E]): TxInfoResult[E]

  /** `lookupTxInfoResult`: the information of language `L`, which the era supports. */
  final def lookupTxInfoResult[L <: Language](result: TxInfoResult[E])(using
      t: EraPlutusTxInfo[L, E]
  ): PlutusTxInfoResult[L, E] = t.lookupTxInfoResult(result)

/** The instances of each era, the one place that lists them: Babbage's
  * (`Babbage/TxInfo.hs:228-251`, cardano-ledger f649f975; `:231-254` at 9df9f98c), Conway's
  * (`Conway/TxInfo.hs:157-187`, cardano-ledger 9df9f98c) and Dijkstra's
  * (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxInfo.hs:299-331`, cardano-ledger 9df9f98c).
  */
object EraPlutusContext:
  given babbage: EraPlutusContext[Babbage] = BabbageTxInfo.context
  given conway: EraPlutusContext[Conway] = xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxInfo.context
  given dijkstra: EraPlutusContext[Dijkstra] =
    xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTxInfo.context

/** The functions of `Context.hs` (cardano-ledger 9df9f98c). */
object PlutusContext:
  /** `mkPlutusTxInfoFromResult` (`Context.hs:199-204`, cardano-ledger 9df9f98c, deprecated there in
    * favour of `unPlutusTxInfoResult`): the information, whatever the purpose.
    */
  def mkPlutusTxInfoFromResult[L <: Language, E <: EraTag](
      @unused sp: PlutusPurpose[AsPurpose, E],
      result: PlutusTxInfoResult[L, E]
  ): Either[ContextError[E], PlutusTxInfo[L]] =
    result.unPlutusTxInfoResult

  /** `toPlutusTxInfoForPurpose` (`Context.hs:210-217`, cardano-ledger 9df9f98c). */
  def toPlutusTxInfoForPurpose[L <: Language, E <: EraTag, T <: TxLevel](
      lti: LedgerTxInfo[T, E],
      sp: PlutusPurpose[AsPurpose, E]
  )(using t: EraPlutusTxInfo[L, E]): Either[ContextError[E], PlutusTxInfo[L]] =
    mkPlutusTxInfoFromResult(sp, t.toPlutusTxInfo(lti))

/** `class Indexable elem container`
  * (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxBody.hs:546-581`, cardano-ledger f649f975), its
  * `fromIndex` for each container a body indexes: the item at a redeemer's index, none past the
  * end. The `Word32` index is compared with the size as it is, never narrowed.
  */
object Indexable:
  private type Ix = Unsigned[Unsigned.Width.W32]

  private def at[A](ix: AsIx[Ix, A], items: IndexedSeq[A]): Option[AsIxItem[Ix, A]] =
    Option.when(ix.ix.value < BigInt(items.size))(AsIxItem(ix.ix, items(ix.ix.value.toInt)))

  /** `instance Indexable k (Set k)` (`Alonzo/TxBody.hs:550-558`, cardano-ledger f649f975): the
    * set's `elemAt`.
    */
  def fromIndexSet[A](
      ix: AsIx[Ix, A],
      s: scala.collection.immutable.SortedSet[A]
  ): Option[AsIxItem[Ix, A]] =
    at(ix, s.toVector)

  /** `instance Indexable k (StrictSeq k)` (`Alonzo/TxBody.hs:560-567`, cardano-ledger f649f975):
    * the sequence's.
    */
  def fromIndexSeq[A](ix: AsIx[Ix, A], s: Vector[A]): Option[AsIxItem[Ix, A]] = at(ix, s)

  /** `instance Indexable k (Map k v)` (`Alonzo/TxBody.hs:569-577`, cardano-ledger f649f975): the
    * key at the index, in the keys' order `ord`.
    */
  def fromIndexMap[A, V](ix: AsIx[Ix, A], m: Map[A, V])(using
      ord: Ordering[A]
  ): Option[AsIxItem[Ix, A]] =
    at(ix, m.keys.toVector.sorted)

  /** `instance Indexable k (OSet k)` (`Alonzo/TxBody.hs:579-581`, cardano-ledger f649f975): its
    * sequence's.
    */
  def fromIndexOSet[A](ix: AsIx[Ix, A], s: OSet[A]): Option[AsIxItem[Ix, A]] = at(ix, s.toVector)
