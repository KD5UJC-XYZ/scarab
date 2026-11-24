// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.syntax.all.*
import scala.annotation.unused
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.{DecodeField, LedgerDecodeError}
import xyz.kd5ujc.scarab.ledger.dijkstra.{
  DijkstraSubTx as FlatDijkstraSubTx,
  DijkstraTx as FlatDijkstraTx
}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraTx,
  AlonzoEraTxAuxData,
  AlonzoEraTxWits,
  BothLevels,
  EraTxAuxData,
  EraTxBody,
  EraTxError,
  EraTxWits,
  IsPhase2Valid,
  OMap,
  PParams,
  SupportedPlutusRunnable,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTx
import xyz.kd5ujc.scarab.ledger.state.{Coin, Dijkstra}
import xyz.kd5ujc.scarab.plutus.ExUnits

/** A Dijkstra transaction of level `L`: `Tx l DijkstraEra` (`data DijkstraTx l era`,
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Tx.hs:97-110`, cardano-ledger 9df9f98c): its
  * body of that level, witness set and auxiliary data, each Dijkstra's. A top-level transaction
  * ([[DijkstraTx.TopTx]], `DijkstraTx`) also has its phase-two flag; a sub-transaction
  * ([[DijkstraTx.SubTx]], `DijkstraSubTx`) has none. Made only by [[DijkstraTx]]'s decoders.
  */
sealed abstract class DijkstraTx[L <: TxLevel] private[dijkstra] (
    val body: DijkstraTxBody[L],
    val wits: DijkstraTxWits,
    val auxData: Option[DijkstraTxAuxData]
):
  /** The transaction's id, its body's hash. */
  def id: Bytes = body.id

  /** `sizeDijkstraTxF` (`Tx.hs:310-319`, cardano-ledger 9df9f98c): the bytes of the body, the
    * witnesses and the auxiliary data (one byte for none) as they arrived, and one for the list of
    * three that holds them (`toCBORForSizeComputation`, `Tx.hs:297-308`).
    */
  def sizeTxF: Long = 1L + body.raw.size + wits.raw.size + auxData.fold(1L)(_.raw.size.toLong)

object DijkstraTx:
  /** That a flat value was read from bytes by the block decoder here, in [[inBlock]] or
    * [[fromSubmission]] (or a witness set's or auxiliary data's decoder through them): only this
    * object makes one, and it keeps it, so only what the decoder read from bytes is projected into
    * the era's types, and a body's level and bytes are the ones it was read with.
    */
  final class FromBytes private[DijkstraTx] ()

  private given FromBytes = FromBytes()

  /** A witness set read from bytes by the block decoder, in the era's types. */
  private[dijkstra] def witsOf(n: Node): Either[EraTxError, DijkstraTxWits] =
    xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraWitnesses
      .decode(n)
      .leftMap(EraTxError.Decode(_))
      .flatMap(DijkstraTxWits.fromFlat)

  /** Auxiliary data read from bytes by the block decoder, in the era's types. */
  private[dijkstra] def auxDataOf(n: Node): Either[EraTxError, DijkstraTxAuxData] =
    xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraAuxData
      .decode(n)
      .leftMap(EraTxError.Decode(_))
      .flatMap(DijkstraTxAuxData.fromFlat)

  /** A top-level transaction (`DijkstraTx`, `Tx.hs:98-104`, cardano-ledger 9df9f98c). */
  final class TopTx private[DijkstraTx] (
      topBody: DijkstraTxBody[TxLevel.Top],
      topWits: DijkstraTxWits,
      val isValid: IsPhase2Valid,
      topAuxData: Option[DijkstraTxAuxData]
  ) extends DijkstraTx[TxLevel.Top](topBody, topWits, topAuxData):
    // Not a case class: its derived `Mirror` would make one from any fields, whatever its bytes.
    private def fields: Any = (body, wits, isValid, auxData)
    override def equals(that: Any): Boolean = that match
      case o: TopTx => fields == o.fields
      case _        => false
    override def hashCode(): Int = fields.hashCode
    override def toString: String = s"TopTx($fields)"

  /** A sub-transaction (`DijkstraSubTx`, `Tx.hs:105-110`, cardano-ledger 9df9f98c). */
  final class SubTx private[DijkstraTx] (
      subBody: DijkstraTxBody[TxLevel.Sub],
      subWits: DijkstraTxWits,
      subAuxData: Option[DijkstraTxAuxData]
  ) extends DijkstraTx[TxLevel.Sub](subBody, subWits, subAuxData):
    private def fields: Any = (body, wits, auxData)
    override def equals(that: Any): Boolean = that match
      case o: SubTx => fields == o.fields
      case _        => false
    override def hashCode(): Int = fields.hashCode
    override def toString: String = s"SubTx($fields)"

  given [L <: TxLevel]: CanEqual[DijkstraTx[L], DijkstraTx[L]] = CanEqual.derived

  /** `isPhase2ValidDijkstraTxL` (`Tx.hs:279-282`, cardano-ledger 9df9f98c): the phase-two flag, of
    * a top-level transaction only.
    */
  def isValid(tx: DijkstraTx[TxLevel.Top]): IsPhase2Valid = tx match
    case t: TopTx => t.isValid

  /** `toCBORForBlockInclusion` (`Tx.hs:527-533`, cardano-ledger 9df9f98c): `[body, witnesses,
    * auxiliary_data / null, is_valid]`, the body, the witness set and the auxiliary data each
    * written as the bytes it memoizes, the phase-two flag last. The reference keeps no bytes of the
    * whole transaction (`Tx.hs:483-489`): a block and an endorser block hold this encoding
    * (`serializeTxForLeios`), whatever form the transaction arrived in. Of a top-level transaction
    * only, as the reference's is.
    */
  def forBlockInclusion(tx: DijkstraTx[TxLevel.Top]): Bytes =
    import xyz.kd5ujc.scarab.ledger.HeaderBodyEncoding.{False, Null, True, join, list}
    tx match
      case t: TopTx =>
        join(
          Vector(
            list(4),
            t.body.raw.toArray,
            t.wits.raw.toArray,
            t.auxData.fold(Null)(_.raw.toArray),
            t.isValid match
              case IsPhase2Valid.Phase2Valid   => True
              case IsPhase2Valid.Phase2Invalid => False
          )
        )

  /** A top-level transaction the Leios build's block decoder read
    * ([[xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTx]], which decides what is refused), in
    * Dijkstra's types, its sub-transactions at the sub-transaction level. Only [[inBlock]] and
    * [[fromSubmission]] (and [[ofBlock]], on a transaction the block decoder read) call it, each on
    * what the flat decoder read from bytes, so each body is of the level read at and its bytes are
    * the ones read.
    */
  private def decode(flat: FlatDijkstraTx): Either[EraTxError, DijkstraTx[TxLevel.Top]] =
    for
      subs <- flat.body.subTransactions.traverse(sub)
      // the decoder refused a sub-transaction id twice and kept the order read
      // (`decodeNonEmptySetLikeEnforceNoDuplicatesAnn (flip OMap.|>)`), which the ordered map
      // refuses again (`TxBody.hs:467-471`, cardano-ledger 9df9f98c)
      ordered <- OMap
        .decoded(subs.map(s => s.id -> s))
        .toRight(EraTxError.Decode(LedgerDecodeError.DuplicateElement(DecodeField.SubTransactions)))
      body <- DijkstraTxBody.topOf(flat.body, ordered)
      wits <- DijkstraTxWits.fromFlat(flat.witnesses)
      aux <- flat.auxiliary.traverse(DijkstraTxAuxData.fromFlat)
    yield TopTx(body, wits, IsPhase2Valid.of(flat.isValid), aux)

  /** `decodeDijkstraTopTxInBlock` (`Tx.hs:175-182`, cardano-ledger 9df9f98c): a transaction of a
    * block body, its validity flag last. The node is trusted to be the CBOR parser's, its value the
    * reading of its bytes, as every decoder of the node assumes: a node built by hand
    * (`Node.apply`) can hold a value its bytes are not (#85).
    */
  def inBlock(n: Node): Either[EraTxError, DijkstraTx[TxLevel.Top]] =
    FlatDijkstraTx.inBlock(n).leftMap(EraTxError.Decode(_)).flatMap(decode)

  /** A transaction of a block the flat block decoder read (`LeiosBlock.decode`, which reads each
    * transaction with `decodeDijkstraTopTxInBlock` as [[inBlock]] does): what [[inBlock]] reads,
    * from the transaction that decoder already read. Only [[DijkstraBlock.decode]] holds the
    * evidence ([[DijkstraBlock.Decoded]]), on the block it decoded, so no copied flat transaction
    * reaches the era's types this way.
    */
  private[dijkstra] def ofBlock(flat: FlatDijkstraTx)(using
      @unused decoded: DijkstraBlock.Decoded
  ): Either[EraTxError, DijkstraTx[TxLevel.Top]] =
    decode(flat)

  /** `decodeDijkstraTopTx` (`Tx.hs:152-169`, cardano-ledger 9df9f98c): a transaction as a client or
    * a peer submits it, valid.
    */
  def fromSubmission(raw: Bytes): Either[EraTxError, DijkstraTx[TxLevel.Top]] =
    FlatDijkstraTx.fromSubmission(raw).leftMap(EraTxError.Decode(_)).flatMap(decode)

  /** The setter of `isPhase2ValidTxL` (`isPhase2ValidDijkstraTxL`, `Tx.hs:279-283`, cardano-ledger
    * 9df9f98c): the top-level transaction with its phase-two flag set to `isValid`, its body,
    * witnesses and auxiliary data as they are, which the flag is not part of. The mempool sets it
    * on a transaction from a peer (`tx & isPhase2ValidTxL .~ Phase2Valid`, and `Phase2Invalid` when
    * only the flag is refused, `applyAlonzoBasedTx`, ouroboros-consensus 2416493b
    * `ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Eras.hs:291-342`).
    */
  def withIsValid(tx: DijkstraTx[TxLevel.Top], isValid: IsPhase2Valid): DijkstraTx[TxLevel.Top] =
    tx match
      case t: TopTx => TopTx(t.body, t.wits, isValid, t.auxData)

  /** A sub-transaction the flat decoder read (`DecCBOR (Annotator (DijkstraTx SubTx era))`,
    * `Tx.hs:184-192`, cardano-ledger 9df9f98c), its body at the sub-transaction level.
    */
  private def sub(flat: FlatDijkstraSubTx): Either[EraTxError, DijkstraTx[TxLevel.Sub]] =
    (
      DijkstraTxBody.subOf(flat.body),
      DijkstraTxWits.fromFlat(flat.witnesses),
      flat.auxiliary.traverse(DijkstraTxAuxData.fromFlat)
    ).mapN(SubTx(_, _, _))

  /** `instance EraTx DijkstraEra` (`Tx.hs:216-253`, cardano-ledger 9df9f98c) and `instance
    * AlonzoEraTx DijkstraEra` (`Tx.hs:413-422`), of both levels. The size for the fee is the
    * class's default (`Core.hs`), the bytes of the parts and the list's, which is
    * [[DijkstraTx.sizeTxF]]'s count. `mkBasicTx` is not a member of the class here, as for Conway;
    * `validateNativeScript` is `validateDijkstraNativeScript` (`Tx.hs:427-438`); `allInputsTxBodyF`
    * is [[EraTxBody.allInputs]].
    */
  val instance: AlonzoEraTx[Dijkstra] { val bodyOf: EraTxBody.dijkstra.type } =
    new AlonzoEraTx[Dijkstra]:
      override val bodyOf: EraTxBody.dijkstra.type = EraTxBody.dijkstra
      override def witsOf: AlonzoEraTxWits[Dijkstra] = EraTxWits.dijkstra
      override def auxDataOf: AlonzoEraTxAuxData[Dijkstra] = EraTxAuxData.dijkstra
      override def body[L <: TxLevel](tx: DijkstraTx[L])(using BothLevels[L]): DijkstraTxBody[L] =
        tx.body
      override def wits[L <: TxLevel](tx: DijkstraTx[L])(using BothLevels[L]): DijkstraTxWits =
        tx.wits
      override def auxData[L <: TxLevel](tx: DijkstraTx[L])(using
          BothLevels[L]
      ): Option[DijkstraTxAuxData] = tx.auxData
      // `txStAnnTxG` and `cacheStAnnTxG`, `Tx.hs:225-231`, cardano-ledger 9df9f98c
      override def txStAnnTx[L <: TxLevel](stAnnTx: DijkstraStAnnTx[L])(using
          BothLevels[L]
      ): DijkstraTx[L] = stAnnTx match
        case s: DijkstraStAnnTx.TopTx => s.dsattTx
        case s: DijkstraStAnnTx.SubTx => s.dsastTx
      override def cacheStAnnTx[L <: TxLevel](stAnnTx: DijkstraStAnnTx[L])(using
          BothLevels[L]
      ): Map[Bytes, SupportedPlutusRunnable[Dijkstra]] = stAnnTx match
        case s: DijkstraStAnnTx.TopTx => s.dsattPlutusRunnableCache.value
        case s: DijkstraStAnnTx.SubTx => s.dsastPlutusRunnableCache.value
      override def sizeTxForFeeCalculation[L <: TxLevel](tx: DijkstraTx[L])(using
          BothLevels[L]
      ): Long = tx.sizeTxF
      // `sizeTxF = dijkstraTxL . sizeDijkstraTxF`, `Tx.hs:243`, cardano-ledger 9df9f98c
      override def sizeTxF[L <: TxLevel](tx: DijkstraTx[L])(using BothLevels[L]): Long = tx.sizeTxF
      // `validateNativeScript = validateDijkstraNativeScript`, `Tx.hs:427-438`, cardano-ledger
      // 9df9f98c: the script for the key hashes of the key witnesses, the validity interval and the
      // body's guards
      override def validateNativeScript[L <: TxLevel](
          tx: DijkstraTx[L],
          native: DijkstraNativeScript
      )(using l: BothLevels[L]): Boolean =
        val txBody = body[L](tx)
        val vi = bodyOf.vldt[L](txBody)
        native.eval(
          witsOf.addrTxWits(wits[L](tx)).map(_.keyHash),
          vi.invalidBefore,
          vi.invalidHereafter,
          txBody.guards.toVector.map {
            case xyz.kd5ujc.scarab.ledger.state.Credential.KeyHashObj(h) =>
              xyz.kd5ujc.scarab.ledger.Credential.Key(h.bytes)
            case xyz.kd5ujc.scarab.ledger.state.Credential.ScriptHashObj(h) =>
              xyz.kd5ujc.scarab.ledger.Credential.Script(h.bytes)
          }.toSet
        )
      // `getMinFeeTx = getConwayMinFeeTx`, `Tx.hs:249`, cardano-ledger 9df9f98c
      override def getMinFeeTx[L <: TxLevel](
          pp: PParams[Dijkstra],
          tx: DijkstraTx[L],
          refScriptsSize: Long
      )(using l: BothLevels[L]): Coin =
        ConwayTx.getConwayMinFeeTx[Dijkstra, L](pp, tx, refScriptsSize)(using this, l)
      override def isValid(tx: DijkstraTx[TxLevel.Top]): IsPhase2Valid = DijkstraTx.isValid(tx)
      // `getTotalExUnits`, `Tx.hs:417-422`, cardano-ledger 9df9f98c: the witnesses' units, and at the top level each
      // sub-transaction's
      override def getTotalExUnits[L <: TxLevel](tx: DijkstraTx[L])(using
          l: BothLevels[L]
      ): ExUnits =
        val own = super.getTotalExUnits[L](tx)
        l match
          case BothLevels.Top =>
            EraTxBody.dijkstra
              .subTransactions(tx.body)
              .toVector
              .foldLeft(own)((total, sub) =>
                total + getTotalExUnits[TxLevel.Sub](sub)(using BothLevels.Sub)
              )
          case BothLevels.Sub => own
