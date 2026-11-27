// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.babbage

import scala.collection.immutable.{SortedSet, TreeMap}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node}
import xyz.kd5ujc.scarab.ledger.{
  Address,
  ByteStrings,
  CborRead,
  Certificate,
  DecodeField,
  DecodeSite,
  DecoderVersion,
  DetachedRecords,
  LedgerDecodeError,
  RewardAccount,
  TxIn,
  Value as FlatValue
}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraTxBodyToConway,
  AlonzoPlutusPurpose,
  AsIx,
  AsIxItem,
  BabbageEraTxBody,
  BabbageEraTxOut,
  EraTxError,
  KeptUpdate,
  LedgerLevelTxInfo,
  Network,
  ShelleyEraTxBody,
  ShelleyEraTxCertToBabbage,
  Sized,
  TopOnly,
  TxBody,
  TxLevel,
  Update,
  ValidityInterval
}
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{Babbage, Coin, Withdrawals}
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}

/** A Babbage transaction body: `TxBody TopTx BabbageEra` (`data BabbageTxBodyRaw`, cardano-ledger
  * f649f975 `eras/babbage/impl/src/Cardano/Ledger/Babbage/TxBody.hs:125-148`), with the bytes it
  * arrived as (`MemoBytes`, `:262`), whose hash is the transaction's id. Its outputs and collateral
  * return are Babbage outputs, its certificates Babbage certificates in the order read (a
  * `StrictSeq`, repeats kept) and its update proposal the typed one with the bytes the node stores
  * it by ([[KeptUpdate]]). Babbage's bodies are of the top level only. Made only by
  * [[BabbageTxBody.decode]].
  *
  * @param spendInputs
  *   the inputs (`btbrInputs`), in the ledger's `TxIn` order
  * @param outputs
  *   the outputs, each with the size of the encoding it was read from (`Sized`)
  * @param validity
  *   the slots the transaction is valid in (`btbrValidityInterval`)
  */
final class BabbageTxBody private (
    val raw: Bytes,
    val spendInputs: SortedSet[TxIn],
    val collateralInputs: SortedSet[TxIn],
    val referenceInputs: SortedSet[TxIn],
    val outputs: Vector[Sized[BabbageTxOut]],
    val collateralReturn: Option[Sized[BabbageTxOut]],
    val totalCollateral: Option[Coin],
    val certs: Vector[BabbageTxCert],
    val withdrawals: Withdrawals,
    val fee: Coin,
    val validity: ValidityInterval,
    val update: Option[KeptUpdate[Babbage]],
    val reqSignerHashes: SortedSet[Bytes],
    val mint: Map[Bytes, Map[Bytes, BigInt]],
    val scriptIntegrityHash: Option[Bytes],
    val auxDataHash: Option[Bytes],
    val networkId: Option[Network]
):
  // Not a case class: its derived `Mirror` would make one from any fields, whatever its bytes.
  private def fields: Any = (
    raw,
    spendInputs,
    collateralInputs,
    referenceInputs,
    outputs,
    collateralReturn,
    totalCollateral,
    certs,
    withdrawals,
    fee,
    validity,
    update,
    reqSignerHashes,
    mint,
    scriptIntegrityHash,
    auxDataHash,
    networkId
  )
  override def equals(that: Any): Boolean = that match
    case o: BabbageTxBody => fields == o.fields
    case _                => false
  override def hashCode(): Int = fields.hashCode
  override def toString: String = s"BabbageTxBody($fields)"
  lazy val id: Bytes = xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(raw)

object BabbageTxBody:
  given CanEqual[BabbageTxBody, BabbageTxBody] = CanEqual.derived

  private val version: DecoderVersion = DecoderVersion.of(Era.Babbage)

  // `bodyFields`, `Babbage/TxBody.hs:540-564`, cardano-ledger f649f975
  private val known: Set[Int] = Set(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 13, 14, 15, 16, 17, 18)

  /** What the keys read so far set: the reference's `basicBabbageTxBodyRaw` (`:577-595`), each
    * field set by its key.
    */
  private final case class Fields(
      inputs: Option[SortedSet[TxIn]] = None,
      collateral: SortedSet[TxIn] = SortedSet.empty,
      references: SortedSet[TxIn] = SortedSet.empty,
      outputs: Option[Vector[Sized[BabbageTxOut]]] = None,
      collateralReturn: Option[Sized[BabbageTxOut]] = None,
      totalCollateral: Option[Coin] = None,
      certs: Vector[BabbageTxCert] = Vector.empty,
      withdrawals: Withdrawals = Withdrawals(Map.empty),
      fee: Option[Coin] = None,
      invalidHereafter: Option[BigInt] = None,
      invalidBefore: Option[BigInt] = None,
      update: Option[KeptUpdate[Babbage]] = None,
      signers: SortedSet[Bytes] = SortedSet.empty,
      mint: Map[Bytes, Map[Bytes, BigInt]] = Map.empty,
      scriptIntegrityHash: Option[Bytes] = None,
      auxDataHash: Option[Bytes] = None,
      networkId: Option[Network] = None
  )

  /** A Babbage body from its bytes: `DecCBOR (BabbageTxBodyRaw l BabbageEra)` (`SparseKeyed
    * "BabbageTxBodyRaw"` of `bodyFields` and `requiredFields`, `Babbage/TxBody.hs:531-572`,
    * cardano-ledger f649f975) at Babbage's decoder version, 7. The keys are read as they are
    * written, as `decodeSparse` reads them (`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/
    * Decoding/Coders.hs:545-597`): a key read before is refused (`duplicateKey`), then a key
    * Babbage does not know (`invalidField`), then its value is read; after the last key a missing
    * input, output or fee is refused, in that order (`unusedRequiredKeys`). Each value is read by
    * the flat decoder's reader of its field at version 7, so each refuses what the flat decoder
    * refuses, with its error. The bytes are parsed once; an input that holds an empty indefinite
    * list and that the plain parse refuses is parsed again with the detached records a walk finds,
    * as the flat decoder parses it ([[DetachedRecords.read]]). A pool's parameters are read in
    * `layout`, the layout of the build that reads it ([[BabbageTxCert.fromFlat]]).
    */
  def decode(raw: Bytes, layout: StateLayout): Either[EraTxError, BabbageTxBody] =
    DetachedRecords
      .read(raw, Cbor.Limits.Unbounded, DetachedRecords.Shapes.body(Era.Babbage, version))(e =>
        EraTxError.Decode(LedgerDecodeError.Syntax(e))
      )(read(raw, _, layout))

  /** [[decode]] of a body's item, parsed already with no detached record in it
    * ([[BabbageTx.fromEnvelope]]).
    */
  private[babbage] def fromItem(n: Node, layout: StateLayout): Either[EraTxError, BabbageTxBody] =
    read(n.original, n, layout)

  private def read(
      raw: Bytes,
      root: Node,
      layout: StateLayout
  ): Either[EraTxError, BabbageTxBody] =
    val what = DecodeField.TransactionBody
    def decode[A](r: Either[LedgerDecodeError, A]): Either[EraTxError, A] =
      r.leftMap(EraTxError.Decode(_))
    def required[A](a: Option[A], site: DecodeSite): Either[EraTxError, A] =
      a.toRight(EraTxError.Decode(LedgerDecodeError.MissingField(what, site)))
    for
      entries <- decode(CborRead.map(root, what))
      read <- entries.foldM((Fields(), Set.empty[Int])) { case ((fields, seen), (k, v)) =>
        for
          key <- decode(
            CborRead
              .uint(k, what / DecodeSite.Qualifier.Key)
              .flatMap(key =>
                Either.cond(key.isValidInt, key.toInt, LedgerDecodeError.UnknownKey(what, key))
              )
          )
          _ <- Either.cond(
            !seen.contains(key),
            (),
            EraTxError.Decode(LedgerDecodeError.DuplicateKey(what))
          )
          _ <- Either.cond(
            known.contains(key),
            (),
            EraTxError.Decode(LedgerDecodeError.UnknownFields(what, Set(key)))
          )
          set <- field(key, v, fields, layout)
        yield (set, seen + key)
      }
      fields = read._1
      inputs <- required(fields.inputs, DecodeField.Inputs)
      outputs <- required(fields.outputs, DecodeField.Outputs)
      fee <- required(fields.fee, DecodeField.Fee)
    yield BabbageTxBody(
      raw,
      inputs,
      fields.collateral,
      fields.references,
      outputs,
      fields.collateralReturn,
      fields.totalCollateral,
      fields.certs,
      fields.withdrawals,
      fee,
      ValidityInterval(fields.invalidBefore, fields.invalidHereafter),
      fields.update,
      fields.signers,
      fields.mint,
      fields.scriptIntegrityHash,
      fields.auxDataHash,
      fields.networkId
    )

  /** The value of `key`, one Babbage knows, read into `fields` (`bodyFields`,
    * `Babbage/TxBody.hs:540-564`, cardano-ledger f649f975), by the flat decoder's reader of the
    * field at version 7 ([[xyz.kd5ujc.scarab.ledger.TxBody.decode]]): below version 9 the sets keep
    * the first of the elements alike, the certificates keep their repeats, and a repeated
    * withdrawal's account keeps its last amount.
    */
  private def field(
      key: Int,
      n: Node,
      fields: Fields,
      layout: StateLayout
  ): Either[EraTxError, Fields] =
    given ByteStrings = ByteStrings.of(version)
    val mode = Address.Mode.at(version)
    val values = FlatValue.Decoding.of(Era.Babbage, version)
    def decode[A](r: Either[LedgerDecodeError, A]): Either[EraTxError, A] =
      r.leftMap(EraTxError.Decode(_))
    def txIns(what: DecodeField) =
      decode(CborRead.set(n, what, version)(TxIn.decode)).map(SortedSet.from(_))
    def output(x: Node) = BabbageTxOut.read(x, mode, values)
    def coin(site: DecodeField) = decode(CborRead.uint(n, site)).map(Coin(_))
    def hash(site: DecodeField) = decode(CborRead.bytesN(n, 32, site))
    key match
      case 0  => txIns(DecodeField.Inputs).map(i => fields.copy(inputs = Some(i)))
      case 13 => txIns(DecodeField.Collateral).map(i => fields.copy(collateral = i))
      case 18 => txIns(DecodeField.ReferenceInputs).map(i => fields.copy(references = i))
      case 1 =>
        decode(CborRead.array(n, DecodeField.Outputs))
          .flatMap(_.traverse(output))
          .map(o => fields.copy(outputs = Some(o)))
      case 16 => output(n).map(o => fields.copy(collateralReturn = Some(o)))
      case 17 => coin(DecodeField.TotalCollateral).map(c => fields.copy(totalCollateral = Some(c)))
      case 2  => coin(DecodeField.Fee).map(c => fields.copy(fee = Some(c)))
      case 3 =>
        decode(CborRead.uint(n, DecodeField.Ttl)).map(s => fields.copy(invalidHereafter = Some(s)))
      case 8 =>
        decode(CborRead.uint(n, DecodeField.ValidityStart))
          .map(s => fields.copy(invalidBefore = Some(s)))
      case 4 =>
        decode(
          CborRead.oset(n, DecodeField.Certificates, version)(
            Certificate.decode(_, Era.Babbage, version)
          )(Certificate.identity)
        ).flatMap(_.traverse(BabbageTxCert.fromFlat(_, layout)))
          .map(c => fields.copy(certs = c))
      case 5 =>
        decode(
          CborRead
            .map(n, DecodeField.Withdrawals)
            .flatMap(_.traverse { case (k, v) =>
              (
                CborRead.bytes(k, DecodeField.WithdrawalAccount).flatMap(RewardAccount.decode),
                CborRead.uint(v, DecodeField.Withdrawal).map(Coin(_))
              ).tupled
            })
        ).flatMap(ws =>
          // below version 9 an account written twice keeps its last amount (`Map.fromList`)
          Convert.withdrawals(TreeMap.from(ws)).leftMap(EraTxError.Vocab(_))
        ).map(w => fields.copy(withdrawals = w))
      case 6 => decode(KeptUpdate.decode[Babbage](n)).map(u => fields.copy(update = Some(u)))
      case 7 => hash(DecodeField.AuxiliaryDataHash).map(h => fields.copy(auxDataHash = Some(h)))
      case 9 =>
        decode(FlatValue.multiasset(n, DecodeField.Mint, signed = true, values))
          .map(m => fields.copy(mint = m))
      case 11 =>
        hash(DecodeField.ScriptDataHash).map(h => fields.copy(scriptIntegrityHash = Some(h)))
      case 14 =>
        decode(
          CborRead.set(n, DecodeField.RequiredSigners, version)(
            CborRead.bytesN(_, 28, DecodeField.Signer)
          )
        ).map(s => fields.copy(signers = SortedSet.from(s)))
      case 15 =>
        decode(xyz.kd5ujc.scarab.ledger.TxBody.networkId(n)).flatMap(v =>
          // a `Word8` of 0 or 1, as the reader read it
          Network
            .of(v)
            .toRight(
              EraTxError.Decode(LedgerDecodeError.NumberOutOfRange(DecodeField.NetworkId, v, 0, 1))
            )
            .map(net => fields.copy(networkId = Some(net)))
        )
      // a key Babbage does not know was refused before its value is read
      case _ => Right(fields)

  /** `ShelleyEraTxBody BabbageEra` to `BabbageEraTxBody BabbageEra` (`Babbage/TxBody.hs:261-373`,
    * cardano-ledger f649f975), with the required signers' field of the eras up to Conway.
    */
  val instance: (ShelleyEraTxBody[Babbage] & BabbageEraTxBody[Babbage] &
    AlonzoEraTxBodyToConway[Babbage]) { type Level[L <: TxLevel] = TopOnly[L] } =
    new ShelleyEraTxBody[Babbage]
      with BabbageEraTxBody[Babbage]
      with AlonzoEraTxBodyToConway[Babbage]:
      type Level[L <: TxLevel] = TopOnly[L]
      override def top: TopOnly[TxLevel.Top] = TopOnly.Top
      override def levelOf[L <: TxLevel](level: LedgerLevelTxInfo[L, Babbage]): TopOnly[L] =
        // the information's sub-transactions are none at Babbage, whose information comes with its
        // Plutus side (#85): only the level is read
        level match
          case _: LedgerLevelTxInfo.LedgerTopTxInfo[?] => TopOnly.Top
      override def outputsOf: BabbageEraTxOut[Babbage] = BabbageTxOut.instance
      override def certsOf: ShelleyEraTxCertToBabbage[Babbage] = BabbageTxCert.instance
      override def id[L <: TxLevel](body: TxBody[L, Babbage])(using l: TopOnly[L]): Bytes =
        l match
          case TopOnly.Top => body.id
      override def inputs[L <: TxLevel](body: TxBody[L, Babbage])(using
          l: TopOnly[L]
      ): SortedSet[TxIn] =
        l match
          case TopOnly.Top => body.spendInputs
      override def outputs[L <: TxLevel](body: TxBody[L, Babbage])(using
          l: TopOnly[L]
      ): Vector[BabbageTxOut] =
        l match
          case TopOnly.Top => body.outputs.map(_.value)
      override def sizedOutputs[L <: TxLevel](body: TxBody[L, Babbage])(using
          l: TopOnly[L]
      ): Vector[Sized[BabbageTxOut]] =
        l match
          case TopOnly.Top => body.outputs
      override def fee(body: BabbageTxBody): Coin = body.fee
      override def withdrawals[L <: TxLevel](body: TxBody[L, Babbage])(using
          l: TopOnly[L]
      ): Withdrawals =
        l match
          case TopOnly.Top => body.withdrawals
      override def auxDataHash[L <: TxLevel](body: TxBody[L, Babbage])(using
          l: TopOnly[L]
      ): Option[Bytes] =
        l match
          case TopOnly.Top => body.auxDataHash
      override def certs[L <: TxLevel](body: TxBody[L, Babbage])(using
          l: TopOnly[L]
      ): Vector[BabbageTxCert] =
        l match
          case TopOnly.Top => body.certs
      // `updateTxBodyL`, `Babbage/TxBody.hs:306-307`, cardano-ledger f649f975
      override def update(body: BabbageTxBody): Option[Update[Babbage]] =
        body.update.map(_.update)
      override def vldt[L <: TxLevel](body: TxBody[L, Babbage])(using
          l: TopOnly[L]
      ): ValidityInterval =
        l match
          case TopOnly.Top => body.validity
      override def mint[L <: TxLevel](body: TxBody[L, Babbage])(using
          l: TopOnly[L]
      ): Map[Bytes, Map[Bytes, BigInt]] =
        l match
          case TopOnly.Top => body.mint
      override def collateralInputs(body: BabbageTxBody): SortedSet[TxIn] = body.collateralInputs
      override def reqSignerHashesField[L <: TxLevel](body: TxBody[L, Babbage])(using
          l: TopOnly[L]
      ): SortedSet[Bytes] =
        l match
          case TopOnly.Top => body.reqSignerHashes
      override def scriptIntegrityHash[L <: TxLevel](body: TxBody[L, Babbage])(using
          l: TopOnly[L]
      ): Option[Bytes] =
        l match
          case TopOnly.Top => body.scriptIntegrityHash
      override def networkId[L <: TxLevel](body: TxBody[L, Babbage])(using
          l: TopOnly[L]
      ): Option[Network] =
        l match
          case TopOnly.Top => body.networkId
      // `redeemerPointerInverse = alonzoRedeemerPointerInverse`, `Babbage/TxBody.hs:343`,
      // cardano-ledger f649f975
      override def redeemerPointerInverse[L <: TxLevel](
          body: TxBody[L, Babbage],
          ptr: AlonzoPlutusPurpose[AsIx, Babbage]
      )(using l: TopOnly[L]): Option[AlonzoPlutusPurpose[AsIxItem, Babbage]] =
        AlonzoPlutusPurpose.alonzoRedeemerPointerInverse[Babbage, L](body, ptr)(using this, l)
      override def referenceInputs[L <: TxLevel](body: TxBody[L, Babbage])(using
          l: TopOnly[L]
      ): SortedSet[TxIn] =
        l match
          case TopOnly.Top => body.referenceInputs
      override def totalCollateral(body: BabbageTxBody): Option[Coin] = body.totalCollateral
      override def sizedCollateralReturn(body: BabbageTxBody): Option[Sized[BabbageTxOut]] =
        body.collateralReturn
      // `allInputsTxBodyF = babbageAllInputsTxBodyF`, `Babbage/TxBody.hs:288`, cardano-ledger
      // f649f975
      override def allInputs(body: BabbageTxBody): SortedSet[TxIn] = allInputsBabbage(body)
      // `spendableInputsTxBodyF`, `Babbage/TxBody.hs:285`, cardano-ledger f649f975
      override def spendableInputs[L <: TxLevel](body: TxBody[L, Babbage])(using
          l: TopOnly[L]
      ): SortedSet[TxIn] =
        l match
          case TopOnly.Top => spendableInputsBabbage(body)
      // `allSizedOutputsTxBodyF`, `Babbage/TxBody.hs:372`, cardano-ledger f649f975
      override def allSizedOutputs[L <: TxLevel](body: TxBody[L, Babbage])(using
          l: TopOnly[L]
      ): Vector[Sized[BabbageTxOut]] =
        l match
          case TopOnly.Top => allSizedOutputsBabbage(body)
