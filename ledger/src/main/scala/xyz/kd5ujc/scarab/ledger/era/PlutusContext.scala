// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.ledger.{TranslationError, TxIn, Unsigned}
import xyz.kd5ujc.scarab.ledger.state.{Conway, EraTag}
import xyz.kd5ujc.scarab.plutus.PlutusData

// The translation of a transaction to the script context of a Plutus language
// (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Plutus/Context.hs`, cardano-ledger f649f975).

/** `class Inject t s` (`libs/cardano-ledger-core/src/Cardano/Ledger/BaseTypes.hs:1032-1033`,
  * cardano-ledger f649f975): a value of `A` as one of `B`, as an era's context error carries an
  * earlier era's.
  */
trait Inject[A, B]:
  def inject(a: A): B

object Inject:
  /** `instance Inject a a` (`BaseTypes.hs:1035-1036`, cardano-ledger f649f975). */
  given self[A]: Inject[A, A] = a => a

  /** `instance Inject (BabbageContextError era) (ConwayContextError era)`
    * (`Conway/TxInfo.hs:209-210`, cardano-ledger f649f975).
    */
  given conwayBabbage: Inject[BabbageContextError[Conway], conway.ConwayContextError[Conway]] =
    conway.ConwayContextError.BabbageContextError(_)

  /** `instance Inject (AlonzoContextError era) (ConwayContextError era)`
    * (`Conway/TxInfo.hs:212-213`, cardano-ledger f649f975): through Babbage's.
    */
  given conwayAlonzo: Inject[AlonzoContextError[Conway], conway.ConwayContextError[Conway]] = a =>
    conway.ConwayContextError.BabbageContextError(BabbageContextError.AlonzoContextError(a))

/** The context error of era `E` (`type ContextError era`, `Context.hs:198`, cardano-ledger
  * f649f975): Conway's is `ConwayContextError ConwayEra` (`Conway/TxInfo.hs:150`).
  */
type ContextError[E <: EraTag] = E match
  case Conway => conway.ConwayContextError[Conway]

/** The certificate of a language's context (`type family PlutusTxCert`, `Context.hs:232-236`,
  * cardano-ledger f649f975).
  */
type PlutusTxCert[L <: Language] = L match
  case Language.PlutusV1 => PV1.DCert
  case Language.PlutusV2 => PV1.DCert
  case Language.PlutusV3 => PV3.TxCert
  case Language.PlutusV4 => PV3.TxCert

/** The script purpose of a language's context (`type family PlutusScriptPurpose`,
  * `Context.hs:238-242`, cardano-ledger f649f975).
  */
type PlutusScriptPurpose[L <: Language] = L match
  case Language.PlutusV1 => PV1.ScriptPurpose
  case Language.PlutusV2 => PV1.ScriptPurpose
  case Language.PlutusV3 => PV3.ScriptPurpose
  case Language.PlutusV4 => PV3.ScriptPurpose

/** The transaction information of a language's context (`type family PlutusTxInfo`,
  * `Context.hs:244-248`, cardano-ledger f649f975).
  */
type PlutusTxInfo[L <: Language] = L match
  case Language.PlutusV1 => PV1.TxInfo
  case Language.PlutusV2 => PV2.TxInfo
  case Language.PlutusV3 => PV3.TxInfo
  case Language.PlutusV4 => PV3.TxInfo

/** An input of a language's context (`type family PlutusTxInInfo era l`, `Context.hs:250-257`,
  * cardano-ledger f649f975), without Alonzo's own case for Plutus V1 (`Maybe PV1.TxInInfo`): Alonzo
  * is not among the era types yet, and no later era has the case.
  */
type PlutusTxInInfo[E <: EraTag, L <: Language] = L match
  case Language.PlutusV1 => PV1.TxInInfo
  case Language.PlutusV2 => PV2.TxInInfo
  case Language.PlutusV3 => PV3.TxInInfo
  case Language.PlutusV4 => PV3.TxInInfo

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

/** `PlutusArgs 'PlutusV4` (`newtype PlutusArgs 'PlutusV4 = PlutusV4Args`, `Plutus/Language.hs:579`,
  * cardano-ledger f649f975): the V3 script context.
  */
final case class PlutusV4Args(unPlutusV4Args: PV3.ScriptContext)

/** The arguments of a language's script (`data PlutusArgs l`, `Plutus/Language.hs:463`,
  * cardano-ledger f649f975).
  */
type PlutusArgs[L <: Language] = L match
  case Language.PlutusV1 => PlutusV1Args
  case Language.PlutusV2 => PlutusV2Args
  case Language.PlutusV3 => PlutusV3Args
  case Language.PlutusV4 => PlutusV4Args

/** What the ledger hands the translation of a transaction to a script context (`data LedgerTxInfo
  * era`, `Context.hs:92-103`, cardano-ledger f649f975): the protocol version, the slot to POSIX
  * time of the epoch information and the system start, the UTxO set and the transaction.
  *
  * The epoch information and the system start are one function here, the clock the flat translation
  * takes, whose failure is typed where the reference's is a `Text`; the transaction is a top-level
  * one, the only level of an era before Dijkstra (`withTopTxLevelOnly`); and the memoised contexts
  * of sub-transactions (`ltiMemoizedSubTransactions`), Dijkstra's alone, are not here.
  */
final case class LedgerTxInfo[E <: EraTag](
    ltiProtVer: ProtVer,
    ltiSlotToPOSIXTime: BigInt => Either[TranslationError, BigInt],
    ltiUTxO: UTxO[E],
    ltiTx: Tx[TxLevel.Top, E]
)

/** The transaction information of language `L` for a transaction of era `E` (`newtype
  * PlutusTxInfoResult l era`, `Context.hs:156-164`, cardano-ledger f649f975): its failure, or a
  * function of the purpose, which every era before Dijkstra ignores.
  */
final case class PlutusTxInfoResult[L <: Language, E <: EraTag](
    unPlutusTxInfoResult: Either[
      ContextError[E],
      PlutusPurpose[AsPurpose, E] => Either[ContextError[E], PlutusTxInfo[L]]
    ]
)

/** The translation of a transaction of era `E` to the context of language `L` (`class
  * EraPlutusTxInfo (l :: Language) era`, `Context.hs:105-144`, cardano-ledger f649f975).
  */
trait EraPlutusTxInfo[L <: Language, E <: EraTag]:
  /** The language (the superclass `PlutusLanguage l`). */
  def plutusLanguage: PlutusLanguage[L]

  def toPlutusTxCert(pv: ProtVer, txCert: TxCert[E]): Either[ContextError[E], PlutusTxCert[L]]

  def toPlutusScriptPurpose(
      pv: ProtVer,
      purpose: PlutusPurpose[AsIxItem, E]
  ): Either[ContextError[E], PlutusScriptPurpose[L]]

  def toPlutusTxInfo(lti: LedgerTxInfo[E]): PlutusTxInfoResult[L, E]

  def toPlutusArgs(
      pv: ProtVer,
      txInfo: PlutusTxInfo[L],
      purpose: PlutusPurpose[AsIxItem, E],
      maybeSpendingData: Option[Data],
      redeemerData: Data
  ): Either[ContextError[E], PlutusArgs[L]]

  def toPlutusTxInInfo(utxo: UTxO[E], txIn: TxIn): Either[ContextError[E], PlutusTxInInfo[E, L]]

  /** The language's own information among the era's memoised ones: what `lookupTxInfoResult` of the
    * era's `EraPlutusContext` instance selects for the language (`Context.hs:216-220`,
    * cardano-ledger f649f975). Each language's instance selects its own, so a language the era does
    * not support, the reference's `lookupTxInfoResultImpossible` (`Context.hs:224-227`), has no
    * instance to ask.
    */
  def lookupTxInfoResult(result: TxInfoResult[E]): PlutusTxInfoResult[L, E]

/** The instances of each era and language, the one place that lists them: Conway's for Plutus V1 to
  * V3 (`Conway/TxInfo.hs:399-534`, cardano-ledger f649f975).
  */
object EraPlutusTxInfo:
  given conwayPlutusV1: EraPlutusTxInfo[Language.PlutusV1, Conway] =
    conway.ConwayTxInfo.plutusV1
  given conwayPlutusV2: EraPlutusTxInfo[Language.PlutusV2, Conway] =
    conway.ConwayTxInfo.plutusV2
  given conwayPlutusV3: conway.ConwayEraPlutusTxInfo[Language.PlutusV3, Conway] =
    conway.ConwayTxInfo.plutusV3

/** The memoised transaction information of every language era `E` supports (`data TxInfoResult
  * era`, `Context.hs:202`, cardano-ledger f649f975): Conway's of Plutus V1 to V3
  * (`Conway/TxInfo.hs:152-156`).
  */
type TxInfoResult[E <: EraTag] = E match
  case Conway => conway.ConwayTxInfoResult

/** A language era `E` supports, with its translation (`data SupportedLanguage era`,
  * `Context.hs:313-325`, cardano-ledger f649f975): equal and ordered as its language is.
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

  /** The derived `Ord (SupportedLanguage era)` (`Context.hs:323-325`, cardano-ledger f649f975): by
    * the language.
    */
  given ordering[E <: EraTag]: Ordering[SupportedLanguage[E]] = (a, b) =>
    Integer.compare(a.language.id, b.language.id)

/** The script contexts of era `E` 's languages (`class EraPlutusContext era`, `Context.hs:187-220`,
  * cardano-ledger f649f975): the languages it supports and their memoised information. The
  * translation of a Plutus script into a runnable one (`mkSupportedPlutusRunnable`) waits for the
  * era types' runnable scripts (#85); the context error is the family [[ContextError]] and the
  * memoised information the family [[TxInfoResult]].
  */
trait EraPlutusContext[E <: EraTag]:
  /** `mkSupportedLanguage`: the language as one the era supports, none if it does not. */
  def mkSupportedLanguage(
      lang: xyz.kd5ujc.scarab.plutus.PlutusLanguage
  ): Option[SupportedLanguage[E]]

  /** `mkTxInfoResult`: the information of every language the era supports, each computed when first
    * looked up.
    */
  def mkTxInfoResult(lti: LedgerTxInfo[E]): TxInfoResult[E]

  /** `lookupTxInfoResult`: the information of language `L`, which the era supports. */
  final def lookupTxInfoResult[L <: Language](result: TxInfoResult[E])(using
      t: EraPlutusTxInfo[L, E]
  ): PlutusTxInfoResult[L, E] = t.lookupTxInfoResult(result)

/** The instances of each era, the one place that lists them: Conway's (`Conway/TxInfo.hs:149-178`,
  * cardano-ledger f649f975).
  */
object EraPlutusContext:
  given conway: EraPlutusContext[Conway] = xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxInfo.context

/** The functions of `Context.hs` (cardano-ledger f649f975). */
object PlutusContext:
  /** `mkPlutusTxInfoFromResult` (`Context.hs:167-172`, cardano-ledger f649f975): the information
    * for the purpose.
    */
  def mkPlutusTxInfoFromResult[L <: Language, E <: EraTag](
      sp: PlutusPurpose[AsPurpose, E],
      result: PlutusTxInfoResult[L, E]
  ): Either[ContextError[E], PlutusTxInfo[L]] =
    result.unPlutusTxInfoResult.flatMap(_(sp))

  /** `toPlutusTxInfoForPurpose` (`Context.hs:178-185`, cardano-ledger f649f975). */
  def toPlutusTxInfoForPurpose[L <: Language, E <: EraTag](
      lti: LedgerTxInfo[E],
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
