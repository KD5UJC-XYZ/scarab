// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Rational, Value}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  CborRead,
  DecodeField,
  DetachedRecords,
  LedgerDecodeError,
  Unsigned
}
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}
import xyz.kd5ujc.scarab.ledger.era.{
  AllegraTx,
  AlonzoEraTx,
  AlonzoEraTxAuxData,
  AlonzoTx,
  ConwayEraPParams,
  AlonzoEraTxWits,
  EraTxAuxData,
  EraTxBody,
  EraTxError,
  EraTxWits,
  IsPhase2Valid,
  PParams,
  PositiveInterval,
  TopOnly,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.state.{Coin, Conway, EraTag, NonZero}

/** A Conway transaction: `AlonzoTx TopTx ConwayEra` (`data AlonzoTx l era`, cardano-ledger f649f975
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Tx.hs:205-212`): its body, witness set, phase-two
  * flag and auxiliary data, each Conway's. Made only by [[ConwayTx.decode]].
  */
final class ConwayTx private (
    val body: ConwayTxBody,
    val wits: ConwayTxWits,
    val isValid: IsPhase2Valid,
    val auxData: Option[ConwayTxAuxData]
):
  // Not a case class: its derived `Mirror` would make one from any fields, whatever its bytes.
  private def fields: Any = (body, wits, isValid, auxData)
  override def equals(that: Any): Boolean = that match
    case o: ConwayTx => fields == o.fields
    case _           => false
  override def hashCode(): Int = fields.hashCode
  override def toString: String = s"ConwayTx($fields)"

  /** `sizeTxForFeeCalculation`, as the flat transaction's size. */
  def sizeTxForFeeCalculation: Long =
    1L + body.raw.size + wits.raw.size + auxData.fold(1L)(_.raw.size.toLong)

  /** The transaction with the phase-two flag `valid` (`tx & isPhase2ValidTxL .~ valid`, Conway's
    * `isPhase2ValidTxL`, `Conway/Tx.hs:145-146`, through `isPhase2ValidAlonzoTxL`,
    * `Alonzo/Tx.hs:334-339`, cardano-ledger f649f975): its body, witnesses and auxiliary data as
    * they were read, the flag being none of their bytes.
    */
  def withIsValid(valid: IsPhase2Valid): ConwayTx = new ConwayTx(body, wits, valid, auxData)

object ConwayTx:
  given CanEqual[ConwayTx, ConwayTx] = CanEqual.derived

  /** A transaction of a Conway block, its parts read by Conway's decoders in one pass each, a
    * pool's parameters in `layout` ([[ConwayTxBody.decode]], [[ConwayTxWits.decode]],
    * [[ConwayTxAuxData.decode]]): the body, the witness set and the auxiliary data in that order,
    * the first refusal the transaction's (`DecCBOR (Annotator (AlonzoTx l era))`,
    * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Tx.hs:536-554`, cardano-ledger f649f975, and the
    * block body's segments, `Alonzo/BlockBody/Internal.hs:212-256`).
    */
  def decode(t: Block.Transaction, layout: StateLayout): Either[EraTxError, ConwayTx] =
    (
      ConwayTxBody.decode(t.body, layout),
      ConwayTxWits.decode(t.witnesses),
      t.auxiliaryData.traverse(ConwayTxAuxData.decode)
    ).mapN((body, wits, aux) => ConwayTx(body, wits, IsPhase2Valid.of(t.isValid), aux))

  /** A whole Conway transaction as a client or a peer submits it (`decodeRecordNamed "AlonzoTx"`,
    * `Alonzo/Tx.hs:536-554`, cardano-ledger f649f975), its parts split as the flat decoder splits
    * them ([[xyz.kd5ujc.scarab.ledger.Tx.envelopeParts]]) and each read by Conway's decoders, as
    * [[xyz.kd5ujc.scarab.ledger.Tx.fromEnvelope]] reads them. Bytes that hold no empty indefinite
    * list (`9f ff`) hold no detached record ([[DetachedRecords.mayHold]]), and each part is then
    * read from its item in the transaction's tree, which is the tree its own bytes parse to;
    * otherwise each part is parsed again from its bytes, with the records its own walk finds, as
    * the flat decoder reads it.
    */
  def fromEnvelope(bytes: Bytes, layout: StateLayout): Either[EraTxError, ConwayTx] =
    if DetachedRecords.mayHold(bytes) then fromEnvelopeWith(bytes)(decode(_, layout))
    else
      Cbor
        .decode(bytes, Cbor.Limits.Unbounded)
        .leftMap(e => EraTxError.Decode(LedgerDecodeError.Syntax(e)))
        .flatMap(root => items(root).leftMap(EraTxError.Decode(_)))
        .flatMap((body, wits, aux, valid) =>
          (
            ConwayTxBody.fromItem(body, layout),
            ConwayTxWits.fromItem(wits),
            aux.traverse(ConwayTxAuxData.fromItem)
          ).mapN((b, w, a) => ConwayTx(b, w, IsPhase2Valid.of(valid), a))
        )

  /** The parts of a whole transaction's item as the flat decoder splits them from Alonzo on
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

  private def fromEnvelopeWith(bytes: Bytes)(
      read: Block.Transaction => Either[EraTxError, ConwayTx]
  ): Either[EraTxError, ConwayTx] =
    DetachedRecords
      .read(bytes, Cbor.Limits.Unbounded, DetachedRecords.Shapes.transaction(Era.Conway))(
        LedgerDecodeError.Syntax(_)
      )(xyz.kd5ujc.scarab.ledger.Tx.envelopeParts(_, Era.Conway))
      .leftMap(EraTxError.Decode(_))
      .flatMap(read)

  /** The two passes [[decode]] replaced (#85): each part read by the flat decoder at Conway, then
    * projected into Conway's types ([[ConwayTxBody.decodeByFlat]], [[ConwayTxWits.decodeByFlat]],
    * [[ConwayTxAuxData.decodeByFlat]]). Kept for the differential and the bench until the flat
    * decoder goes (stage 6a of `docs/design/era-typed-transactions.md`).
    */
  def decodeByFlat(t: Block.Transaction, layout: StateLayout): Either[EraTxError, ConwayTx] =
    (
      ConwayTxBody.decodeByFlat(t.body, layout),
      ConwayTxWits.decodeByFlat(t.witnesses),
      t.auxiliaryData.traverse(ConwayTxAuxData.decodeByFlat)
    ).mapN((body, wits, aux) => ConwayTx(body, wits, IsPhase2Valid.of(t.isValid), aux))

  /** [[fromEnvelope]] in the two passes it replaced ([[decodeByFlat]]), kept with it. */
  def fromEnvelopeByFlat(bytes: Bytes, layout: StateLayout): Either[EraTxError, ConwayTx] =
    fromEnvelopeWith(bytes)(decodeByFlat(_, layout))

  val instance: AlonzoEraTx[Conway] { val bodyOf: EraTxBody.conway.type } =
    new AlonzoEraTx[Conway]:
      override val bodyOf: EraTxBody.conway.type = EraTxBody.conway
      override def witsOf: AlonzoEraTxWits[Conway] = EraTxWits.conway
      override def auxDataOf: AlonzoEraTxAuxData[Conway] = EraTxAuxData.conway
      override def body[L <: TxLevel](tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Conway])(using
          l: TopOnly[L]
      ): xyz.kd5ujc.scarab.ledger.era.TxBody[L, Conway] =
        l match
          case TopOnly.Top => tx.body
      override def wits[L <: TxLevel](tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Conway])(using
          l: TopOnly[L]
      ): ConwayTxWits =
        l match
          case TopOnly.Top => tx.wits
      override def auxData[L <: TxLevel](tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Conway])(using
          l: TopOnly[L]
      ): Option[ConwayTxAuxData] =
        l match
          case TopOnly.Top => tx.auxData
      // `txStAnnTxG` and `cacheStAnnTxG`, `Conway/Tx.hs:71-73`, cardano-ledger f649f975
      override def txStAnnTx[L <: TxLevel](
          stAnnTx: xyz.kd5ujc.scarab.ledger.era.StAnnTx[L, Conway]
      )(using l: TopOnly[L]): xyz.kd5ujc.scarab.ledger.era.Tx[L, Conway] =
        l match
          case TopOnly.Top => stAnnTx.asatTx
      override def cacheStAnnTx[L <: TxLevel](
          stAnnTx: xyz.kd5ujc.scarab.ledger.era.StAnnTx[L, Conway]
      )(using l: TopOnly[L]): xyz.kd5ujc.scarab.ledger.era.StAnnTxCache[Conway] =
        l match
          case TopOnly.Top => stAnnTx.asatPlutusRunnableCache
      override def sizeTxForFeeCalculation[L <: TxLevel](
          tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Conway]
      )(using l: TopOnly[L]): Long =
        l match
          case TopOnly.Top => tx.sizeTxForFeeCalculation
      // `sizeTxF = conwayTxL . sizeAlonzoTxF`, `Conway/Tx.hs:86`, cardano-ledger f649f975: a list
      // header of one byte and the parts' bytes, the count `sizeTxForFeeCalculation` makes
      override def sizeTxF[L <: TxLevel](tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Conway])(using
          l: TopOnly[L]
      ): Long =
        l match
          case TopOnly.Top => tx.sizeTxForFeeCalculation
      // `validateNativeScript = validateTimelock`, `Conway/Tx.hs:89`, cardano-ledger f649f975
      override def validateNativeScript[L <: TxLevel](
          tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Conway],
          native: ConwayTimelock
      )(using l: TopOnly[L]): Boolean =
        AllegraTx.validateTimelock[Conway, L](tx, native.rep)(using this, l)
      // `getMinFeeTx = getConwayMinFeeTx`, `Conway/Tx.hs:92`, cardano-ledger f649f975
      override def getMinFeeTx[L <: TxLevel](
          pp: PParams[Conway],
          tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, Conway],
          refScriptsSize: Long
      )(using l: TopOnly[L]): Coin =
        getConwayMinFeeTx[Conway, L](pp, tx, refScriptsSize)(using this, l)
      override def isValid(tx: ConwayTx): IsPhase2Valid = tx.isValid

  /** `getConwayMinFeeTx` (`Conway/Tx.hs:100-118`, cardano-ledger f649f975): Alonzo's minimum fee
    * and the fee of the reference scripts.
    */
  def getConwayMinFeeTx[E <: EraTag, L <: TxLevel](
      pp: PParams[E],
      tx: xyz.kd5ujc.scarab.ledger.era.Tx[L, E],
      refScriptsSize: Long
  )(using t: AlonzoEraTx[E], l: t.bodyOf.Level[L], p: ConwayEraPParams[E]): Coin =
    Coin(
      AlonzoTx.alonzoMinFeeTx[E, L](pp, tx).value +
        tierRefScriptFee(
          p.ppRefScriptCostMultiplierG.get(pp),
          p.ppRefScriptCostStrideG.get(pp),
          ConwayEraPParams.ppMinFeeRefScriptCostPerByteL[E].get(pp).rational,
          refScriptsSize
        ).value
    )

  /** `tierRefScriptFee` (`Conway/Tx.hs:120-142`, cardano-ledger f649f975): the price per byte
    * starts at `baseFee` and is multiplied by `multiplier` for each `sizeIncrement` bytes. The
    * reference refuses a multiplier or an increment that is not positive with an error; their types
    * here have no such value. The size is a 64-bit `Int`, as the reference's.
    */
  def tierRefScriptFee(
      multiplier: PositiveInterval,
      sizeIncrement: NonZero[Unsigned[Unsigned.Width.W32]],
      baseFee: Rational,
      totalSize: Long
  ): Coin =
    val increment = sizeIncrement.value.value.toLong
    val sizeIncrementRational = Rational(BigInt(increment))
    @annotation.tailrec
    def go(acc: Rational, curTierPrice: Rational, n: Long): Coin =
      if n < increment then Coin((acc + Rational(BigInt(n)) * curTierPrice).floor)
      else
        go(
          acc + sizeIncrementRational * curTierPrice,
          multiplier.rational * curTierPrice,
          n - increment
        )
    go(Rational(BigInt(0)), baseFee, totalSize)
