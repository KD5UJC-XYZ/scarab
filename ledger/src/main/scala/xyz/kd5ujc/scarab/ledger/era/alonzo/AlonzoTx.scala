// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.alonzo

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{Block, CborRead, DecodeField, DetachedRecords, LedgerDecodeError}
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}
import xyz.kd5ujc.scarab.ledger.era.{
  AllegraTx,
  AlonzoEraTx,
  AlonzoEraTxAuxData,
  AlonzoEraTxWits,
  AlonzoTx as AlonzoTxFunctions,
  EraTxAuxData,
  EraTxBody,
  EraTxError,
  EraTxWits,
  IsPhase2Valid,
  PParams,
  TopOnly,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.state.{Alonzo, Coin}

/** An Alonzo transaction: `AlonzoTx TopTx AlonzoEra` (`newtype Tx l AlonzoEra = MkAlonzoTx
  * (AlonzoTx l AlonzoEra)`, cardano-ledger f649f975
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Tx.hs:218`, over `data AlonzoTx l era`, `:205-212`):
  * its body, witness set, phase-two flag and auxiliary data, each Alonzo's. Made only by
  * [[AlonzoTx.decode]] and [[AlonzoTx.fromEnvelope]].
  */
final class AlonzoTx private (
    val body: AlonzoTxBody,
    val wits: AlonzoTxWits,
    val isValid: IsPhase2Valid,
    val auxData: Option[AlonzoTxAuxData]
):
  // Not a case class: its derived `Mirror` would make one from any fields, whatever its bytes.
  private def fields: Any = (body, wits, isValid, auxData)
  override def equals(that: Any): Boolean = that match
    case o: AlonzoTx => fields == o.fields
    case _           => false
  override def hashCode(): Int = fields.hashCode
  override def toString: String = s"AlonzoTx($fields)"

  /** `sizeTxForFeeCalculation`: a list header of one byte and the parts' bytes, one for no
    * auxiliary data.
    */
  def sizeTxForFeeCalculation: Long =
    1L + body.raw.size + wits.raw.size + auxData.fold(1L)(_.raw.size.toLong)

object AlonzoTx:
  given CanEqual[AlonzoTx, AlonzoTx] = CanEqual.derived

  /** A transaction of an Alonzo block, its parts read by Alonzo's decoders, a pool's parameters in
    * `layout` ([[AlonzoTxBody.decode]], [[AlonzoTxWits.decode]], [[AlonzoTxAuxData.decode]]): the
    * body, the witness set and the auxiliary data in that order, the first refusal the
    * transaction's (`DecCBOR (Annotator (Tx l AlonzoEra))`, `Alonzo/Tx.hs:282-283`, through
    * `DecCBOR (Annotator (AlonzoTx l era))`, `:526-554`, cardano-ledger f649f975).
    */
  def decode(t: Block.Transaction, layout: StateLayout): Either[EraTxError, AlonzoTx] =
    (
      AlonzoTxBody.decode(t.body, layout),
      AlonzoTxWits.decode(t.witnesses),
      t.auxiliaryData.traverse(AlonzoTxAuxData.decode)
    ).mapN((body, wits, aux) => AlonzoTx(body, wits, IsPhase2Valid.of(t.isValid), aux))

  /** A whole Alonzo transaction as a client or a peer submits it (`decodeRecordNamed "AlonzoTx"`,
    * `Alonzo/Tx.hs:526-554`, cardano-ledger f649f975), read as the reference reads it ([[record]]).
    * Bytes that hold no empty indefinite list hold no detached record
    * ([[DetachedRecords.mayHold]]), and each part is then read from its item in the transaction's
    * tree; otherwise each part is parsed again from its bytes, with the records its own walk finds,
    * as the flat decoder reads it ([[xyz.kd5ujc.scarab.ledger.Tx.envelopeParts]]).
    */
  def fromEnvelope(bytes: Bytes, layout: StateLayout): Either[EraTxError, AlonzoTx] =
    def syntax(e: Cbor.Error) = EraTxError.Decode(LedgerDecodeError.Syntax(e))
    if DetachedRecords.mayHold(bytes) then
      DetachedRecords
        .read(bytes, Cbor.Limits.Unbounded, DetachedRecords.Shapes.transaction(Era.Alonzo))(
          syntax
        )(
          record(_)(
            n => AlonzoTxBody.decode(n.original, layout),
            n => AlonzoTxWits.decode(n.original),
            n => AlonzoTxAuxData.decode(n.original)
          )
        )
    else
      Cbor
        .decode(bytes, Cbor.Limits.Unbounded)
        .leftMap(syntax)
        .flatMap(
          record(_)(
            AlonzoTxBody.fromItem(_, layout),
            AlonzoTxWits.fromItem,
            AlonzoTxAuxData.fromItem
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
      body: Node => Either[EraTxError, AlonzoTxBody],
      wits: Node => Either[EraTxError, AlonzoTxWits],
      aux: Node => Either[EraTxError, AlonzoTxAuxData]
  ): Either[EraTxError, AlonzoTx] =
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
        yield AlonzoTx(b, w, IsPhase2Valid.of(flag.getOrElse(true)), a)
      }

  /** `EraTx AlonzoEra` and `AlonzoEraTx AlonzoEra` (`Alonzo/Tx.hs:217-246, 285-287`, cardano-ledger
    * f649f975): Alonzo's transaction, of the top level only.
    */
  val instance: AlonzoEraTx[Alonzo] { val bodyOf: EraTxBody.alonzo.type } =
    new AlonzoEraTx[Alonzo]:
      override val bodyOf: EraTxBody.alonzo.type = EraTxBody.alonzo
      override def witsOf: AlonzoEraTxWits[Alonzo] = EraTxWits.alonzo
      override def auxDataOf: AlonzoEraTxAuxData[Alonzo] = EraTxAuxData.alonzo
      override def body[L <: TxLevel](tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Alonzo])(using
          l: TopOnly[L]
      ): xyz.kd5ujc.scarab.ledger.era.TxBody[L, Alonzo] =
        l match
          case TopOnly.Top => tx.body
      override def wits[L <: TxLevel](tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Alonzo])(using
          l: TopOnly[L]
      ): AlonzoTxWits =
        l match
          case TopOnly.Top => tx.wits
      override def auxData[L <: TxLevel](tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Alonzo])(using
          l: TopOnly[L]
      ): Option[AlonzoTxAuxData] =
        l match
          case TopOnly.Top => tx.auxData
      // `txStAnnTxG` and `cacheStAnnTxG`, `Alonzo/Tx.hs:226-228`, cardano-ledger f649f975
      override def txStAnnTx[L <: TxLevel](
          stAnnTx: xyz.kd5ujc.scarab.ledger.era.StAnnTx[L, Alonzo]
      )(using l: TopOnly[L]): xyz.kd5ujc.scarab.ledger.era.Tx[L, Alonzo] =
        l match
          case TopOnly.Top => stAnnTx.asatTx
      override def cacheStAnnTx[L <: TxLevel](
          stAnnTx: xyz.kd5ujc.scarab.ledger.era.StAnnTx[L, Alonzo]
      )(using l: TopOnly[L]): xyz.kd5ujc.scarab.ledger.era.StAnnTxCache[Alonzo] =
        l match
          case TopOnly.Top => stAnnTx.asatPlutusRunnableCache
      override def sizeTxForFeeCalculation[L <: TxLevel](
          tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Alonzo]
      )(using l: TopOnly[L]): Long =
        l match
          case TopOnly.Top => tx.sizeTxForFeeCalculation
      // `sizeTxF = alonzoTxL . sizeAlonzoTxF`, `Alonzo/Tx.hs:241`, cardano-ledger f649f975: a list
      // header of one byte and the parts' bytes
      override def sizeTxF[L <: TxLevel](tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Alonzo])(using
          l: TopOnly[L]
      ): Long =
        l match
          case TopOnly.Top => tx.sizeTxForFeeCalculation
      // `validateNativeScript = validateTimelock`, `Alonzo/Tx.hs:244`, cardano-ledger f649f975
      override def validateNativeScript[L <: TxLevel](
          tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Alonzo],
          native: AlonzoTimelock
      )(using l: TopOnly[L]): Boolean =
        AllegraTx.validateTimelock[Alonzo, L](tx, native.rep)(using this, l)
      // `getMinFeeTx pp tx _ = alonzoMinFeeTx pp tx`, `Alonzo/Tx.hs:247`, cardano-ledger f649f975:
      // Alonzo has no reference scripts to price
      override def getMinFeeTx[L <: TxLevel](
          pp: PParams[Alonzo],
          tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Alonzo],
          refScriptsSize: Long
      )(using l: TopOnly[L]): Coin =
        AlonzoTxFunctions.alonzoMinFeeTx[Alonzo, L](pp, tx)(using this, l)
      // `isPhase2ValidTxL`, `Alonzo/Tx.hs:286`, cardano-ledger f649f975
      override def isValid(tx: AlonzoTx): IsPhase2Valid = tx.isValid
