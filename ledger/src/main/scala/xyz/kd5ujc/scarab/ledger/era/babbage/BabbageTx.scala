// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.babbage

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{Block, CborRead, DecodeField, DetachedRecords, LedgerDecodeError}
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}
import xyz.kd5ujc.scarab.ledger.era.{
  AllegraTx,
  AlonzoEraTx,
  AlonzoEraTxAuxData,
  AlonzoEraTxWits,
  AlonzoTx,
  EraTxAuxData,
  EraTxBody,
  EraTxError,
  EraTxWits,
  IsPhase2Valid,
  PParams,
  TopOnly,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.state.{Babbage, Coin}

/** A Babbage transaction: `AlonzoTx TopTx BabbageEra` (`newtype Tx l BabbageEra = MkBabbageTx
  * (AlonzoTx l BabbageEra)`, cardano-ledger f649f975
  * `eras/babbage/impl/src/Cardano/Ledger/Babbage/Tx.hs:40`, over `data AlonzoTx l era`,
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Tx.hs:205-212`): its body, witness set, phase-two
  * flag and auxiliary data, each Babbage's. Made only by [[BabbageTx.decode]] and
  * [[BabbageTx.fromEnvelope]].
  */
final class BabbageTx private (
    val body: BabbageTxBody,
    val wits: BabbageTxWits,
    val isValid: IsPhase2Valid,
    val auxData: Option[BabbageTxAuxData]
):
  // Not a case class: its derived `Mirror` would make one from any fields, whatever its bytes.
  private def fields: Any = (body, wits, isValid, auxData)
  override def equals(that: Any): Boolean = that match
    case o: BabbageTx => fields == o.fields
    case _            => false
  override def hashCode(): Int = fields.hashCode
  override def toString: String = s"BabbageTx($fields)"

  /** `sizeTxForFeeCalculation`: a list header of one byte and the parts' bytes, one for no
    * auxiliary data.
    */
  def sizeTxForFeeCalculation: Long =
    1L + body.raw.size + wits.raw.size + auxData.fold(1L)(_.raw.size.toLong)

object BabbageTx:
  given CanEqual[BabbageTx, BabbageTx] = CanEqual.derived

  /** A transaction of a Babbage block, its parts read by Babbage's decoders, a pool's parameters in
    * `layout` ([[BabbageTxBody.decode]], [[BabbageTxWits.decode]], [[BabbageTxAuxData.decode]]):
    * the body, the witness set and the auxiliary data in that order, the first refusal the
    * transaction's (`DecCBOR (Annotator (Tx l BabbageEra))`, `Babbage/Tx.hs:78-79`, through
    * `DecCBOR (Annotator (AlonzoTx l era))`, `Alonzo/Tx.hs:526-554`, cardano-ledger f649f975).
    */
  def decode(t: Block.Transaction, layout: StateLayout): Either[EraTxError, BabbageTx] =
    (
      BabbageTxBody.decode(t.body, layout),
      BabbageTxWits.decode(t.witnesses),
      t.auxiliaryData.traverse(BabbageTxAuxData.decode)
    ).mapN((body, wits, aux) => BabbageTx(body, wits, IsPhase2Valid.of(t.isValid), aux))

  /** A whole Babbage transaction as a client or a peer submits it (`decodeRecordNamed "AlonzoTx"`,
    * `Alonzo/Tx.hs:526-554`, cardano-ledger f649f975), read as the reference reads it ([[record]]).
    * Bytes that hold no empty indefinite list hold no detached record
    * ([[DetachedRecords.mayHold]]), and each part is then read from its item in the transaction's
    * tree; otherwise each part is parsed again from its bytes, with the records its own walk finds,
    * as the flat decoder reads it ([[xyz.kd5ujc.scarab.ledger.Tx.envelopeParts]]).
    */
  def fromEnvelope(bytes: Bytes, layout: StateLayout): Either[EraTxError, BabbageTx] =
    def syntax(e: Cbor.Error) = EraTxError.Decode(LedgerDecodeError.Syntax(e))
    if DetachedRecords.mayHold(bytes) then
      DetachedRecords
        .read(bytes, Cbor.Limits.Unbounded, DetachedRecords.Shapes.transaction(Era.Babbage))(
          syntax
        )(
          record(_)(
            n => BabbageTxBody.decode(n.original, layout),
            n => BabbageTxWits.decode(n.original),
            n => BabbageTxAuxData.decode(n.original)
          )
        )
    else
      Cbor
        .decode(bytes, Cbor.Limits.Unbounded)
        .leftMap(syntax)
        .flatMap(
          record(_)(
            BabbageTxBody.fromItem(_, layout),
            BabbageTxWits.fromItem,
            BabbageTxAuxData.fromItem
          )
        )

  /** `decodeRecordNamed "AlonzoTx"` of a whole transaction's item (`Alonzo/Tx.hs:538-553`,
    * cardano-ledger f649f975), its items read in order: the body, the witness set, the phase-two
    * flag when the third item is a boolean (`peekTokenType`), the transaction valid otherwise, and
    * then the auxiliary data or null (`decodeNullStrictMaybe`, `decodeTxAuxDataByTokenType`). Only
    * then is the record's length checked (`decodeListLikeT`, `libs/cardano-ledger-binary/src/
    * Cardano/Ledger/Binary/Decoding/Decoder.hs:691-709`): four items with the flag, three without.
    *
    * An item the list lacks is read where the reference reads it, from the token after the list's
    * last item. After a definite list that is the end of the input, as a submitted transaction is
    * the whole input ("end of input", [[Cbor.Error.UnexpectedEnd]]). In an indefinite list it is
    * the break, which the part's reader refuses as no item of its shape: the auxiliary data's
    * ("Failed to decode AlonzoTxAuxData"). The break is no value the parser gives, so its refusal
    * names it as the token it is, major type 7 with additional information 31 (`Simple(31)`). A
    * definite list longer than the record is refused by its length ("Size mismatch when decoding
    * Record"), an indefinite one by the break the reference looks for after the last field ("Excess
    * terms in array"), as the node's readers of a record refuse it
    * ([[LedgerDecodeError.IndefiniteLength]], [[CborRead.record]]).
    */
  private def record(node: Node)(
      body: Node => Either[EraTxError, BabbageTxBody],
      wits: Node => Either[EraTxError, BabbageTxWits],
      aux: Node => Either[EraTxError, BabbageTxAuxData]
  ): Either[EraTxError, BabbageTx] =
    def refused(e: LedgerDecodeError) = EraTxError.Decode(e)
    // the item at `i`, or the refusal of the token after the list's last item by the reader of
    // the part of `site` and `shape`
    def at(xs: Vector[Node], i: Int, site: DecodeField, shape: LedgerDecodeError.Shape) =
      xs.lift(i)
        .toRight(
          refused(
            if node.isIndefinite then LedgerDecodeError.Expected(site, shape, Value.Simple(31))
            else LedgerDecodeError.Syntax(Cbor.Error.UnexpectedEnd)
          )
        )
    CborRead
      .array(node, DecodeField.Transaction)
      .leftMap(refused)
      .flatMap { xs =>
        for
          b <- at(xs, 0, DecodeField.TransactionBody, LedgerDecodeError.Shape.Map).flatMap(body)
          w <- at(xs, 1, DecodeField.WitnessSet, LedgerDecodeError.Shape.Map).flatMap(wits)
          flag = xs.lift(2).map(_.value).collect { case Value.Bool(v) => v }
          size = if flag.isDefined then 4 else 3
          auxItem <- at(xs, size - 1, DecodeField.AuxiliaryData, LedgerDecodeError.Shape.ArrayOrMap)
          a <- auxItem.value match
            case Value.Null => Right(None)
            case _          => aux(auxItem).map(Some(_))
          _ <- Either.cond(
            xs.sizeIs == size,
            (),
            refused(
              if node.isIndefinite then LedgerDecodeError.IndefiniteLength(DecodeField.Transaction)
              else LedgerDecodeError.ArrayLength(DecodeField.Transaction, size, xs.size)
            )
          )
        yield BabbageTx(b, w, IsPhase2Valid.of(flag.getOrElse(true)), a)
      }

  /** `EraTx BabbageEra` and `AlonzoEraTx BabbageEra` (`Babbage/Tx.hs:36-76`, cardano-ledger
    * f649f975): Alonzo's transaction, of the top level only.
    */
  val instance: AlonzoEraTx[Babbage] { val bodyOf: EraTxBody.babbage.type } =
    new AlonzoEraTx[Babbage]:
      override val bodyOf: EraTxBody.babbage.type = EraTxBody.babbage
      override def witsOf: AlonzoEraTxWits[Babbage] = EraTxWits.babbage
      override def auxDataOf: AlonzoEraTxAuxData[Babbage] = EraTxAuxData.babbage
      override def body[L <: TxLevel](tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Babbage])(using
          l: TopOnly[L]
      ): xyz.kd5ujc.scarab.ledger.era.TxBody[L, Babbage] =
        l match
          case TopOnly.Top => tx.body
      override def wits[L <: TxLevel](tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Babbage])(using
          l: TopOnly[L]
      ): BabbageTxWits =
        l match
          case TopOnly.Top => tx.wits
      override def auxData[L <: TxLevel](tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Babbage])(using
          l: TopOnly[L]
      ): Option[BabbageTxAuxData] =
        l match
          case TopOnly.Top => tx.auxData
      // `txStAnnTxG` and `cacheStAnnTxG`, `Babbage/Tx.hs:48-50`, cardano-ledger f649f975
      override def txStAnnTx[L <: TxLevel](
          stAnnTx: xyz.kd5ujc.scarab.ledger.era.StAnnTx[L, Babbage]
      )(using l: TopOnly[L]): xyz.kd5ujc.scarab.ledger.era.Tx[L, Babbage] =
        l match
          case TopOnly.Top => stAnnTx.asatTx
      override def cacheStAnnTx[L <: TxLevel](
          stAnnTx: xyz.kd5ujc.scarab.ledger.era.StAnnTx[L, Babbage]
      )(using l: TopOnly[L]): xyz.kd5ujc.scarab.ledger.era.StAnnTxCache[Babbage] =
        l match
          case TopOnly.Top => stAnnTx.asatPlutusRunnableCache
      override def sizeTxForFeeCalculation[L <: TxLevel](
          tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Babbage]
      )(using l: TopOnly[L]): Long =
        l match
          case TopOnly.Top => tx.sizeTxForFeeCalculation
      // `sizeTxF = babbageTxL . sizeAlonzoTxF`, `Babbage/Tx.hs:63`, cardano-ledger f649f975: a list
      // header of one byte and the parts' bytes
      override def sizeTxF[L <: TxLevel](tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Babbage])(using
          l: TopOnly[L]
      ): Long =
        l match
          case TopOnly.Top => tx.sizeTxForFeeCalculation
      // `validateNativeScript = validateTimelock`, `Babbage/Tx.hs:66`, cardano-ledger f649f975
      override def validateNativeScript[L <: TxLevel](
          tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Babbage],
          native: BabbageTimelock
      )(using l: TopOnly[L]): Boolean =
        AllegraTx.validateTimelock[Babbage, L](tx, native.rep)(using this, l)
      // `getMinFeeTx pp tx _ = alonzoMinFeeTx pp tx`, `Babbage/Tx.hs:69`, cardano-ledger f649f975:
      // the reference scripts' size is not priced in Babbage
      override def getMinFeeTx[L <: TxLevel](
          pp: PParams[Babbage],
          tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Babbage],
          refScriptsSize: Long
      )(using l: TopOnly[L]): Coin =
        AlonzoTx.alonzoMinFeeTx[Babbage, L](pp, tx)(using this, l)
      // `isPhase2ValidTxL`, `Babbage/Tx.hs:75`, cardano-ledger f649f975
      override def isValid(tx: BabbageTx): IsPhase2Valid = tx.isValid
