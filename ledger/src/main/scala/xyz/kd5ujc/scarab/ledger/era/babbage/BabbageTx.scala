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
    * `Alonzo/Tx.hs:536-554`, cardano-ledger f649f975): a record of the body, the witness set, the
    * phase-two flag when its token is a boolean (`peekTokenType`), and the auxiliary data or null,
    * of four items with the flag and three without. Bytes that hold no empty indefinite list hold
    * no detached record ([[DetachedRecords.mayHold]]), and each part is then read from its item;
    * otherwise the parts are split as the flat decoder splits them
    * ([[xyz.kd5ujc.scarab.ledger.Tx.envelopeParts]]) and each is parsed again from its bytes.
    */
  def fromEnvelope(bytes: Bytes, layout: StateLayout): Either[EraTxError, BabbageTx] =
    if DetachedRecords.mayHold(bytes) then
      DetachedRecords
        .read(bytes, Cbor.Limits.Unbounded, DetachedRecords.Shapes.transaction(Era.Babbage))(
          LedgerDecodeError.Syntax(_)
        )(xyz.kd5ujc.scarab.ledger.Tx.envelopeParts(_, Era.Babbage))
        .leftMap(EraTxError.Decode(_))
        .flatMap(decode(_, layout))
    else
      Cbor
        .decode(bytes, Cbor.Limits.Unbounded)
        .leftMap(e => EraTxError.Decode(LedgerDecodeError.Syntax(e)))
        .flatMap(root => items(root).leftMap(EraTxError.Decode(_)))
        .flatMap((body, wits, aux, valid) =>
          (
            BabbageTxBody.fromItem(body, layout),
            BabbageTxWits.fromItem(wits),
            aux.traverse(BabbageTxAuxData.fromItem)
          ).mapN((b, w, a) => BabbageTx(b, w, IsPhase2Valid.of(valid), a))
        )

  /** The parts of a whole transaction's item, as the flat decoder splits them from Alonzo on
    * ([[xyz.kd5ujc.scarab.ledger.Tx.envelopeParts]]): a list of four with a boolean third, or of
    * three whose third, the auxiliary data or null, is not a boolean, the transaction then valid.
    */
  private def items(
      node: Node
  ): Either[LedgerDecodeError, (Node, Node, Option[Node], Boolean)] =
    def aux(n: Node) = n.value match
      case Value.Null => None
      case _          => Some(n)
    def length(expected: Int, actual: Int) =
      Left(LedgerDecodeError.ArrayLength(DecodeField.Transaction, expected, actual))
    CborRead.array(node, DecodeField.Transaction).flatMap {
      case Vector(body, wits, flag, auxiliary) =>
        CborRead.bool(flag, DecodeField.IsValid).map((body, wits, aux(auxiliary), _))
      case Vector(body, wits, auxiliary) =>
        auxiliary.value match
          // read as the flag, the auxiliary data after it missing
          case Value.Bool(_) => length(4, 3)
          case _             => Right((body, wits, aux(auxiliary), true))
      case xs => length(if xs.sizeIs < 3 then 3 else 4, xs.size)
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
