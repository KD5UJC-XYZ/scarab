// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{DataSite, Datum, TxIn, TxOutSource}
import xyz.kd5ujc.scarab.ledger.state.EraTag

/** Why Babbage's translation of a transaction to a script context fails (`data BabbageContextError
  * era`, `eras/babbage/impl/src/Cardano/Ledger/Babbage/TxInfo.hs:253-260`, cardano-ledger
  * f649f975), which Conway's error carries.
  */
enum BabbageContextError[E <: EraTag]:
  case AlonzoContextError(error: xyz.kd5ujc.scarab.ledger.era.AlonzoContextError[E])
  case ByronTxOutInContext(txOutSource: TxOutSource)
  case RedeemerPointerPointsToNothing(ptr: PlutusPurpose[AsIx, E])
  case InlineDatumsNotSupported(txOutSource: TxOutSource)
  case ReferenceScriptsNotSupported(txOutSource: TxOutSource)
  case ReferenceInputsNotSupported(txIns: Set[TxIn])

/** Babbage's translations (`eras/babbage/impl/src/Cardano/Ledger/Babbage/TxInfo.hs`, cardano-ledger
  * f649f975) that Conway's contexts use, generic in the era over its classes, with the injection of
  * Babbage's error into the era's (`Inject (BabbageContextError era) (ContextError era)`).
  */
object BabbageTxInfo:
  import PlutusTxInfos.*

  private def alonzo[E <: EraTag](
      e: AlonzoContextError[E]
  )(using inj: Inject[BabbageContextError[E], ContextError[E]]): ContextError[E] =
    inj.inject(BabbageContextError.AlonzoContextError(e))

  /** The node's undecodable data at `site`, as the era's error. */
  def undecodable[E <: EraTag](site: DataSite)(using
      Inject[BabbageContextError[E], ContextError[E]]
  ): ContextError[E] = alonzo[E](AlonzoContextError.Undecodable(site))

  /** `transReferenceScript` (`Babbage/TxInfo.hs:100-106`, cardano-ledger f649f975): the hash of the
    * reference script.
    */
  def transReferenceScript[E <: EraTag](script: Option[Script[E]])(using
      s: EraScript[E]
  ): Option[PV1.ScriptHash] =
    script.map(x => PV1.ScriptHash(s.hashScript(x)))

  /** `transTxOutV2` (`Babbage/TxInfo.hs:130-156`, cardano-ledger f649f975): the output in the V2
    * form, a Byron address refused; an inline datum is read from its bytes, only for an address
    * that is not Byron, as the reference's lazy `datum` is forced only there (lines 142-156).
    */
  def transTxOutV2[E <: EraTag](txOutSource: TxOutSource, txOut: TxOut[E])(using
      o: BabbageEraTxOut[E],
      inj: Inject[BabbageContextError[E], ContextError[E]],
      v: Value[E] =:= MultiAssetValue
  ): Either[ContextError[E], PV2.TxOut] =
    val value = o.value(txOut)
    val referenceScript = transReferenceScript(o.referenceScript(txOut))(using o.scripts)
    def datum: Either[ContextError[E], PV2.OutputDatum] = o.datum(txOut) match
      case Datum.NoDatum  => Right(PV2.OutputDatum.NoOutputDatum)
      case Datum.Hash(dh) => Right(PV2.OutputDatum.OutputDatumHash(transDataHash(dh)))
      case Datum.Inline(binaryData) =>
        decodeData(binaryData, DataSite.InlineDatum)
          .bimap(undecodable[E], d => PV2.OutputDatum.OutputDatum(PV1.Datum(d)))
    transAddr(o.address(txOut)) match
      case None => Left(inj.inject(BabbageContextError.ByronTxOutInContext(txOutSource)))
      case Some(addr) =>
        datum.map(d => PV2.TxOut(addr, AlonzoTxInfo.transValue(v(value)), d, referenceScript))

  /** `transRedeemer` (`Babbage/TxInfo.hs:188-189`, cardano-ledger f649f975): the redeemer's data,
    * read from the bytes it arrived as.
    */
  def transRedeemer(raw: Bytes): Either[DataSite, PV1.Redeemer] =
    decodeData(raw, DataSite.Redeemer).map(PV1.Redeemer(_))

  /** `transRedeemerPtr` (`Babbage/TxInfo.hs:191-207`, cardano-ledger f649f975): the purpose a
    * redeemer's pointer names in the language's form, with the redeemer; a pointer that names
    * nothing refused.
    */
  def transRedeemerPtr[L <: Language, E <: EraTag, T <: TxLevel](
      pv: ProtVer,
      txBody: TxBody[T, E],
      ptr: PlutusPurpose[AsIx, E],
      data: Bytes
  )(using
      t: EraPlutusTxInfo[L, E],
      b: AlonzoEraTxBody[E],
      l: b.Level[T],
      inj: Inject[BabbageContextError[E], ContextError[E]]
  ): Either[ContextError[E], (PlutusScriptPurpose[L], PV1.Redeemer)] =
    for
      plutusScriptPurpose <- redeemerPurpose[L, E, T](pv, txBody, ptr)
      redeemer <- transRedeemer(data).leftMap(undecodable[E])
    yield (plutusScriptPurpose, redeemer)

  /** The part of `transRedeemerPtr` the reference's decoded data does not touch: the purpose the
    * pointer names, in the language's form, or the pointer refused.
    */
  private def redeemerPurpose[L <: Language, E <: EraTag, T <: TxLevel](
      pv: ProtVer,
      txBody: TxBody[T, E],
      ptr: PlutusPurpose[AsIx, E]
  )(using
      t: EraPlutusTxInfo[L, E],
      b: AlonzoEraTxBody[E],
      l: b.Level[T],
      inj: Inject[BabbageContextError[E], ContextError[E]]
  ): Either[ContextError[E], PlutusScriptPurpose[L]] =
    b.redeemerPointerInverse[T](txBody, ptr) match
      case None     => Left(inj.inject(BabbageContextError.RedeemerPointerPointsToNothing(ptr)))
      case Some(sp) => t.toPlutusScriptPurpose(pv, sp)

  /** `transTxRedeemers` (`Babbage/TxInfo.hs:211-226`, cardano-ledger f649f975): every redeemer of
    * the witnesses, in the order of their pointers, by its purpose. Every pointer and purpose is
    * translated, in the reference's order, before any redeemer is read from its bytes, so that the
    * node's own `Undecodable` comes after any failure the reference has, as the flat path's
    * (`ScriptContexts.redeemerMap`).
    */
  def transTxRedeemers[L <: Language, E <: EraTag, T <: TxLevel](pv: ProtVer, tx: Tx[T, E])(using
      t: EraPlutusTxInfo[L, E],
      x: AlonzoEraTx[E],
      l: x.bodyOf.Level[T],
      inj: Inject[BabbageContextError[E], ContextError[E]]
  ): Either[ContextError[E], AssocMap[PlutusScriptPurpose[L], PV1.Redeemer]] =
    val entries = x.witsOf.rdmrsTxWits(x.wits[T](tx)).entries.toVector
    for
      purposes <- entries.traverse((ptr, _) =>
        redeemerPurpose[L, E, T](pv, x.body[T](tx), ptr)(using t, x.bodyOf, l, inj)
      )
      redeemers <- entries.traverse((_, entry) => transRedeemer(entry._1).leftMap(undecodable[E]))
    yield AssocMap(purposes.zip(redeemers))
