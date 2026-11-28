// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.alonzo

import scala.collection.immutable.SortedMap
import cats.data.NonEmptyVector
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{
  ByteStrings,
  CborRead,
  DecodeField,
  DecoderVersion,
  DetachedRecords,
  LedgerDecodeError,
  Metadatum,
  Unsigned
}
import xyz.kd5ujc.scarab.plutus.PlutusLanguage
import xyz.kd5ujc.scarab.ledger.era.{AlonzoEraTxAuxData, AlonzoTxAuxDataRep, EraTxError}
import xyz.kd5ujc.scarab.ledger.state.Alonzo
import xyz.kd5ujc.scarab.protocol.Era

/** Alonzo's auxiliary data: `AlonzoTxAuxData AlonzoEra` (`type TxAuxData AlonzoEra =
  * AlonzoTxAuxData AlonzoEra`, cardano-ledger f649f975
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxAuxData.hs:348`), the representation Alonzo's
  * auxiliary data has ([[AlonzoTxAuxDataRep]]) with Alonzo's native scripts, a type of its own. Its
  * Plutus scripts arrive in the order their keys are written, each list in its order. Made only by
  * [[AlonzoTxAuxData.decode]].
  */
opaque type AlonzoTxAuxData = AlonzoTxAuxDataRep[AlonzoTimelock]

object AlonzoTxAuxData extends AlonzoTxAuxDataRep.Fields[AlonzoTxAuxData, AlonzoTimelock]:
  given CanEqual[AlonzoTxAuxData, AlonzoTxAuxData] = CanEqual.derived

  // the languages in `Language`'s order, as `Map Language` keeps them
  private given Ordering[PlutusLanguage] = Ordering.by(_.id)

  private given Ordering[Unsigned[Unsigned.Width.W64]] = Ordering.by(_.value)

  private val blockVersion = DecoderVersion.of(Era.Alonzo)

  /** What the keys read so far set (`emptyAlonzoTxAuxDataRaw`), the Plutus scripts of each key in
    * the order the keys are written.
    */
  private final case class Fields(
      metadata: SortedMap[Unsigned[Unsigned.Width.W64], Metadatum] = SortedMap.empty,
      native: Vector[AlonzoTimelock] = Vector.empty,
      plutus: Vector[(PlutusLanguage, Vector[Bytes])] = Vector.empty
  )

  /** Alonzo's auxiliary data from its bytes: `DecCBOR (Annotator (AlonzoTxAuxDataRaw era))` at
    * Alonzo's decoder version, 5, in any of the three forms it reads, chosen by the token
    * (`decodeTxAuxDataByTokenType`,
    * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxAuxData.hs:210-312`, cardano-ledger f649f975): a
    * metadata map (`decodeShelley`), the `[metadata, native scripts]` record (`decodeAllegra`,
    * `RecD`), or the map tagged 259 (`decodeAlonzo`, `SparseKeyed "AlonzoTxAuxData"` of
    * `auxDataField`), whose keys are read as they are written (`decodeSparse`,
    * `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/ Coders.hs:545-597`): a key
    * read before is refused, then a key it does not know, then its value. A Plutus key is refused
    * at its turn below the version its language starts at (`guardPlutus`,
    * `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Language.hs:639-647`), PlutusV2's key 3,
    * PlutusV3's key 4 and PlutusV4's key 5 at Alonzo. Each metadatum and native script is read by
    * the flat reader's reader at version 5 ([[xyz.kd5ujc.scarab.ledger.AuxiliaryScripts.read]]);
    * below version 9 a label written twice keeps its last metadatum (`Map.fromList`). The bytes are
    * parsed once; an input that holds an empty indefinite list and that the plain parse refuses is
    * parsed again with the detached records a walk finds ([[DetachedRecords.read]]).
    */
  def decode(raw: Bytes): Either[EraTxError, AlonzoTxAuxData] = decodeAt(raw, blockVersion)

  /** [[decode]] at `version`, one of Alonzo's ([[DecoderVersion.at]]), as `decodeFull'` reads a
    * type at any version: the reference's verdicts on auxiliary data at Alonzo's protocol version 6
    * are read at it. A block's and a submission's are read at 5 ([[decode]]).
    */
  private[alonzo] def decodeAt(
      raw: Bytes,
      version: DecoderVersion
  ): Either[EraTxError, AlonzoTxAuxData] =
    DetachedRecords
      .read(
        raw,
        Cbor.Limits.Unbounded,
        DetachedRecords.Shapes.auxiliaryDataAt(Era.Alonzo, version.value)
      )(LedgerDecodeError.Syntax(_))(read(_, version))
      .bimap(EraTxError.Decode(_), project(raw, _))

  /** [[decode]] of auxiliary data's item, parsed already with no detached record in it
    * ([[AlonzoTx.fromEnvelope]]).
    */
  private[alonzo] def fromItem(n: Node): Either[EraTxError, AlonzoTxAuxData] =
    read(n, blockVersion).bimap(EraTxError.Decode(_), project(n.original, _))

  private def read(node: Node, version: DecoderVersion): Either[LedgerDecodeError, Fields] =
    given ByteStrings = ByteStrings.of(version)
    node.value match
      case _: Value.Map => metadata(node).map(m => Fields(metadata = m))
      case _: Value.Arr =>
        CborRead
          .record(node, 2, DecodeField.AuxiliaryData)
          .flatMap(xs => (metadata(xs(0)), native(xs(1))).mapN((m, n) => Fields(m, n)))
      case Value.Tag(tag, contents) if tag == 259 =>
        CborRead
          .map(contents, DecodeField.AuxiliaryData)
          .flatMap(_.foldM((Fields(), Set.empty[BigInt])) { case ((fields, seen), (k, v)) =>
            for
              key <- CborRead.uint(k, DecodeField.AuxiliaryField)
              _ <- Either.cond(
                !seen.contains(key),
                (),
                LedgerDecodeError.DuplicateKey(DecodeField.AuxiliaryData)
              )
              set <- field(key, v, fields, version)
            yield (set, seen + key)
          })
          .map(_._1)
      case _ =>
        Left(
          LedgerDecodeError
            .Expected(DecodeField.AuxiliaryData, LedgerDecodeError.Shape.ArrayOrMap, node.value)
        )

  /** The value of `key` read into `fields` (`auxDataField`, `Alonzo/TxAuxData.hs:290-300`,
    * cardano-ledger f649f975).
    */
  private def field(key: BigInt, n: Node, fields: Fields, version: DecoderVersion)(using
      ByteStrings
  ): Either[LedgerDecodeError, Fields] =
    def plutus(language: PlutusLanguage) =
      CborRead
        .array(n, DecodeField.AuxiliaryPlutusScripts)
        .flatMap(_.traverse(CborRead.bytes(_, DecodeField.AuxiliaryPlutusScript)))
        .map(scripts => fields.copy(plutus = fields.plutus :+ (language -> scripts)))
    // `guardPlutus`: the language's first version, above Alonzo's, refused before the value is
    // read, as the flat reader refuses PlutusV2 and PlutusV3 at version 5
    def guard(minimum: Int, newest: Int) =
      Left(
        LedgerDecodeError.NumberOutOfRange(
          DecodeField.AuxiliaryScriptProtocol,
          version.value,
          minimum,
          newest
        )
      )
    key match
      case k if k == 0 => metadata(n).map(m => fields.copy(metadata = m))
      case k if k == 1 => native(n).map(s => fields.copy(native = fields.native ++ s))
      case k if k == 2 => plutus(PlutusLanguage.V1)
      case k if k == 3 => guard(7, 11)
      case k if k == 4 => guard(9, 11)
      // the flat reader refuses key 5 as unknown below version 12 (#85); the reference knows it and
      // refuses PlutusV4 there (`auxDataField 5`, `Alonzo/TxAuxData.hs:299`, cardano-ledger
      // f649f975), as this does
      case k if k == 5 => guard(12, 12)
      case k           => Left(LedgerDecodeError.UnknownTag(DecodeField.AuxiliaryField, k))

  // A metadata map: each label a `Word64` and its metadatum read; below version 9 a label written
  // twice keeps its last metadatum (`Metadata.hs:153-222`, cardano-ledger f649f975).
  private def metadata(
      node: Node
  ): Either[LedgerDecodeError, SortedMap[Unsigned[Unsigned.Width.W64], Metadatum]] =
    CborRead
      .map(node, DecodeField.Metadata)
      .flatMap(
        _.traverse((label, value) =>
          (
            Unsigned.read[Unsigned.Width.W64](label, DecodeField.MetadataLabel),
            Metadatum.read(value, true)
          ).tupled
        )
      )
      .map(SortedMap.from(_))

  private def native(
      node: Node
  )(using ByteStrings): Either[LedgerDecodeError, Vector[AlonzoTimelock]] =
    CborRead
      .array(node, DecodeField.AuxiliaryNativeScripts)
      .flatMap(_.traverse(AlonzoTimelock.read(_)))

  // The fields read, in the representation: an empty list adds no language (`addPlutusScripts`,
  // `Alonzo/TxAuxData.hs:315-321`, cardano-ledger f649f975).
  private def project(raw: Bytes, r: Fields): AlonzoTxAuxData =
    val plutus = SortedMap.from(
      r.plutus.flatMap((l, scripts) => NonEmptyVector.fromVector(scripts).map(l -> _))
    )
    AlonzoTxAuxDataRep(
      raw,
      r.metadata,
      r.native,
      plutus,
      r.plutus.flatMap((l, scripts) => scripts.map(l -> _))
    )

  /** `EraTxAuxData`, `AllegraEraTxAuxData` and `AlonzoEraTxAuxData AlonzoEra`
    * (`Alonzo/TxAuxData.hs:344-392`, cardano-ledger f649f975): Alonzo's lenses and validation.
    */
  val instance: AlonzoEraTxAuxData[Alonzo] =
    AlonzoTxAuxDataRep.Instance[Alonzo](AlonzoScript.instance)
