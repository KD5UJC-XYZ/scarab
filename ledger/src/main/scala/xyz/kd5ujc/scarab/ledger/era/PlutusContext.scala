// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{TranslationError, TxIn, Unsigned}
import xyz.kd5ujc.scarab.ledger.state.{Conway, EraTag}

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
  case Language.PlutusV3 => PV3.TxCert

/** The script purpose of a language's context (`type family PlutusScriptPurpose`,
  * `Context.hs:238-242`, cardano-ledger f649f975).
  */
type PlutusScriptPurpose[L <: Language] = L match
  case Language.PlutusV3 => PV3.ScriptPurpose

/** The transaction information of a language's context (`type family PlutusTxInfo`,
  * `Context.hs:244-248`, cardano-ledger f649f975).
  */
type PlutusTxInfo[L <: Language] = L match
  case Language.PlutusV3 => PV3.TxInfo

/** An input of a language's context (`type family PlutusTxInInfo era l`, `Context.hs:250-257`,
  * cardano-ledger f649f975): no era from Babbage has Alonzo's special case.
  */
type PlutusTxInInfo[E <: EraTag, L <: Language] = L match
  case Language.PlutusV3 => PV3.TxInInfo

/** `PlutusArgs 'PlutusV3` (`newtype PlutusArgs 'PlutusV3 = PlutusV3Args`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Language.hs:558`, cardano-ledger f649f975):
  * the script context, the one argument of a Plutus V3 script.
  */
final case class PlutusV3Args(unPlutusV3Args: PV3.ScriptContext)

/** The arguments of a language's script (`data PlutusArgs l`, `Plutus/Language.hs:463`,
  * cardano-ledger f649f975).
  */
type PlutusArgs[L <: Language] = L match
  case Language.PlutusV3 => PlutusV3Args

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
  * EraPlutusTxInfo (l :: Language) era`, `Context.hs:105-144`, cardano-ledger f649f975). Data the
  * reference holds decoded (`Data era`) is given as the bytes it arrived as.
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
      maybeSpendingData: Option[Bytes],
      redeemerData: Bytes
  ): Either[ContextError[E], PlutusArgs[L]]

  def toPlutusTxInInfo(utxo: UTxO[E], txIn: TxIn): Either[ContextError[E], PlutusTxInInfo[E, L]]

/** The instances of each era and language, the one place that lists them: Conway's for Plutus V3
  * (`Conway/TxInfo.hs:482-534`, cardano-ledger f649f975).
  */
object EraPlutusTxInfo:
  given conwayPlutusV3: conway.ConwayEraPlutusTxInfo[Language.PlutusV3, Conway] =
    conway.ConwayTxInfo.plutusV3

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
