// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import scala.collection.immutable.SortedMap
import cats.data.NonEmptyVector
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Unique, Value}
import xyz.kd5ujc.scarab.ledger.{
  AuxiliaryScripts,
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
import xyz.kd5ujc.scarab.ledger.state.Conway
import xyz.kd5ujc.scarab.protocol.Era

/** Conway's auxiliary data: `AlonzoTxAuxData ConwayEra` (`type TxAuxData ConwayEra =
  * AlonzoTxAuxData ConwayEra`, `eras/conway/impl/src/Cardano/Ledger/Conway/TxAuxData.hs:24`,
  * cardano-ledger f649f975), the representation Alonzo's auxiliary data has
  * ([[AlonzoTxAuxDataRep]]) with Conway's native scripts, a type of its own. Its Plutus scripts
  * arrive in the encoded map's order, each list in its order. Made only by
  * [[ConwayTxAuxData.decode]].
  */
opaque type ConwayTxAuxData = AlonzoTxAuxDataRep[ConwayTimelock]

object ConwayTxAuxData extends AlonzoTxAuxDataRep.Fields[ConwayTxAuxData, ConwayTimelock]:
  given CanEqual[ConwayTxAuxData, ConwayTxAuxData] = CanEqual.derived

  // the languages in `Language`'s order, as `Map Language` keeps them
  private given Ordering[PlutusLanguage] = Ordering.by(_.id)

  private given Ordering[Unsigned[Unsigned.Width.W64]] = Ordering.by(_.value)

  private val version = DecoderVersion.of(Era.Conway)

  /** Conway's auxiliary data from its bytes, read in one pass (#85): `DecCBOR (Annotator
    * (AlonzoTxAuxDataRaw era))` at decoder version 9, in any of the three forms Conway reads,
    * chosen by the token (`decodeTxAuxDataByTokenType`,
    * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxAuxData.hs:215-312`, cardano-ledger f649f975): a
    * metadata map, the `[metadata, native scripts]` record (`RecD` below version 12), or the map
    * tagged 259 (`SparseKeyed "AlonzoTxAuxData"` of `auxDataField`). The bytes are parsed once and
    * each metadatum and native script read once into Conway's types; an input that holds an empty
    * indefinite list and that the plain parse refuses is parsed again with the detached records a
    * walk finds, as the flat decoder parses it ([[DetachedRecords.read]]).
    *
    * It refuses what the two passes it replaced refuse ([[decodeByFlat]]), with the same first
    * refusal, the flat reader's ([[xyz.kd5ujc.scarab.ledger.AuxiliaryScripts.read]] at version 9):
    * the tagged map's keys are each read as a `Word` and refused when one is repeated before any
    * value is read, then the values in the order written; Conway's native-script reader is the flat
    * one's. The labels become `Word64` values last, as the projection made them. That order is not
    * always the reference's; it is kept here, and left to the decoder's order item of #85
    * (https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7162357644).
    */
  def decode(raw: Bytes): Either[EraTxError, ConwayTxAuxData] =
    DetachedRecords
      .read(
        raw,
        Cbor.Limits.Unbounded,
        DetachedRecords.Shapes.auxiliaryDataAt(Era.Conway, version.value)
      )(LedgerDecodeError.Syntax(_))(read)
      .flatMap(project(raw, _))
      .leftMap(EraTxError.Decode(_))

  /** [[decode]] of auxiliary data's item, parsed already with no detached record in it
    * ([[ConwayTx.fromEnvelope]]).
    */
  private[conway] def fromItem(n: Node): Either[EraTxError, ConwayTxAuxData] =
    read(n).flatMap(project(n.original, _)).leftMap(EraTxError.Decode(_))

  /** What the first phase read: each label's item and its metadatum, the native scripts, and the
    * Plutus scripts of each key, in the order the keys are written.
    */
  private final case class Read(
      metadata: Vector[(Node, Metadatum)],
      native: Vector[ConwayTimelock],
      plutus: Vector[(PlutusLanguage, Vector[Bytes])]
  )

  private val languages: Map[BigInt, PlutusLanguage] =
    Map(
      BigInt(2) -> PlutusLanguage.V1,
      BigInt(3) -> PlutusLanguage.V2,
      BigInt(4) -> PlutusLanguage.V3
    )

  // The first phase: the flat reader of an Alonzo-family item at version 9
  // (`AuxiliaryScripts.alonzoOn`).
  private def read(node: Node): Either[LedgerDecodeError, Read] =
    given ByteStrings = ByteStrings.of(version)
    node.value match
      case _: Value.Map => readMetadata(node).map(Read(_, Vector.empty, Vector.empty))
      case _: Value.Arr =>
        CborRead
          .record(node, 2, DecodeField.AuxiliaryData)
          .flatMap(xs => (readMetadata(xs(0)), readNative(xs(1))).mapN(Read(_, _, Vector.empty)))
      case Value.Tag(tag, contents) if tag == 259 =>
        CborRead.map(contents, DecodeField.AuxiliaryData).flatMap { fields =>
          fields
            .traverse((key, value) =>
              CborRead.uint(key, DecodeField.AuxiliaryField).map(_ -> value)
            )
            .flatMap { keyed =>
              if Unique.repeats(keyed.map(_._1)) then
                Left(LedgerDecodeError.DuplicateKey(DecodeField.AuxiliaryData))
              else
                keyed
                  .foldLeft[Either[LedgerDecodeError, Read]](
                    Right(Read(Vector.empty, Vector.empty, Vector.empty))
                  ) { (acc, entry) =>
                    acc.flatMap { r =>
                      val (key, value) = entry
                      if key == 0 then readMetadata(value).map(m => r.copy(metadata = m))
                      else if key == 1 then readNative(value).map(n => r.copy(native = n))
                      else
                        // Plutus V1 to V3 are all allowed at version 9 (`guardPlutus`,
                        // `Plutus/Language.hs:639-647`, cardano-ledger f649f975). Key 5 is refused
                        // as an unknown key (`UnknownTag(AuxiliaryField, 5)`), as the flat reader
                        // refuses it below version 12, where the reference's `auxDataField 5`
                        // (`Alonzo/TxAuxData.hs:299`) knows the key and refuses PlutusV4 by
                        // `guardPlutus` at version 9: an inherited difference of the diagnostic, not
                        // of the verdict, left to the decoder's order item of #85
                        // (https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7162357644)
                        languages
                          .get(key)
                          .toRight(LedgerDecodeError.UnknownTag(DecodeField.AuxiliaryField, key))
                          .flatMap(language =>
                            CborRead
                              .array(value, DecodeField.AuxiliaryPlutusScripts)
                              .flatMap(
                                _.traverse(CborRead.bytes(_, DecodeField.AuxiliaryPlutusScript))
                              )
                              .map(scripts => r.copy(plutus = r.plutus :+ (language -> scripts)))
                          )
                    }
                  }
            }
        }
      case _ =>
        Left(
          LedgerDecodeError
            .Expected(DecodeField.AuxiliaryData, LedgerDecodeError.Shape.ArrayOrMap, node.value)
        )

  // A metadata map: each label a `Word` and its metadatum read, then no label twice (from version
  // 9, `Metadata.hs:153-222`, cardano-ledger f649f975).
  private def readMetadata(node: Node): Either[LedgerDecodeError, Vector[(Node, Metadatum)]] =
    CborRead.map(node, DecodeField.Metadata).flatMap { pairs =>
      pairs
        .traverse((label, value) =>
          CborRead
            .uint(label, DecodeField.MetadataLabel)
            .flatMap(l => Metadatum.read(value, true).map(m => (l, (label, m))))
        )
        .flatMap(read =>
          Either.cond(
            !Unique.repeats(read.map(_._1)),
            read.map(_._2),
            LedgerDecodeError.DuplicateKey(DecodeField.MetadataLabels)
          )
        )
    }

  private def readNative(
      node: Node
  )(using ByteStrings): Either[LedgerDecodeError, Vector[ConwayTimelock]] =
    CborRead
      .array(node, DecodeField.AuxiliaryNativeScripts)
      .flatMap(_.traverse(ConwayTimelock.read(_)))

  // The second phase: the labels made `Word64`s, and the Plutus scripts by language and in the order
  // their keys are written, as the projection made them.
  private def project(raw: Bytes, r: Read): Either[LedgerDecodeError, ConwayTxAuxData] =
    r.metadata
      .traverse((label, m) =>
        Unsigned.read[Unsigned.Width.W64](label, DecodeField.MetadataLabel).map(_ -> m)
      )
      .map { metadata =>
        // an empty list adds no language (`addPlutusScripts`, `Alonzo/TxAuxData.hs:315-321`,
        // cardano-ledger f649f975)
        val plutus = SortedMap.from(
          r.plutus.flatMap((l, scripts) => NonEmptyVector.fromVector(scripts).map(l -> _))
        )
        val arrival = r.plutus.flatMap((l, scripts) => scripts.map(l -> _))
        AlonzoTxAuxDataRep(raw, SortedMap.from(metadata), r.native, plutus, arrival)
      }

  /** The two passes [[decode]] replaced (#85): Conway's decoder (the flat decoder at Conway's
    * decoder version) reads it and refuses what Conway refuses; its fields are then read from the
    * same bytes into Conway's types, in any of the three forms Conway reads: a metadata map, the
    * `[metadata, native scripts]` list, or the map tagged 259 (`decodeTxAuxDataByTokenType`,
    * `Alonzo/TxAuxData.hs:302-312`, cardano-ledger f649f975). Kept for the differential and the
    * bench until the flat decoder goes (stage 6a).
    */
  def decodeByFlat(raw: Bytes): Either[EraTxError, ConwayTxAuxData] =
    val version = DecoderVersion.of(Era.Conway)
    given ByteStrings = ByteStrings.of(version)
    (for
      _ <- AuxiliaryScripts.read(raw, Era.Conway, version.value)
      parts <- DetachedRecords.read(
        raw,
        Cbor.Limits.Unbounded,
        DetachedRecords.Shapes.auxiliaryDataAt(Era.Conway, version.value)
      )(LedgerDecodeError.Syntax(_))(fields)
    yield AlonzoTxAuxDataRep(raw, parts._1, parts._2, parts._3, parts._4))
      .leftMap(EraTxError.Decode(_))

  private val noMetadata = SortedMap.empty[Unsigned[Unsigned.Width.W64], Metadatum]
  private val noPlutus = SortedMap.empty[PlutusLanguage, NonEmptyVector[Bytes]]

  private type Parts = (
      SortedMap[Unsigned[Unsigned.Width.W64], Metadatum],
      Vector[ConwayTimelock],
      SortedMap[PlutusLanguage, NonEmptyVector[Bytes]],
      Vector[(PlutusLanguage, Bytes)]
  )

  // The fields of auxiliary data the flat decoder has read: each form and field is one it took.
  private def fields(node: Node)(using ByteStrings): Either[LedgerDecodeError, Parts] =
    node.value match
      case _: Value.Map => metadata(node).map((_, Vector.empty, noPlutus, Vector.empty))
      case _: Value.Arr =>
        // the `RecD` record the flat decoder read (`CborRead.record`, which takes it detached as
        // well); it read two items, so `xs(0)` and `xs(1)` are present
        CborRead
          .record(node, 2, DecodeField.AuxiliaryData)
          .flatMap(xs =>
            (metadata(xs(0)), native(xs(1))).mapN((m, n) => (m, n, noPlutus, Vector.empty))
          )
      case Value.Tag(_, contents) =>
        CborRead.map(contents, DecodeField.AuxiliaryData).flatMap { kvs =>
          kvs
            .traverse((k, v) => CborRead.uint(k, DecodeField.AuxiliaryField).map(_ -> v))
            .flatMap { keyed =>
              val byKey = keyed.toMap
              def plutus(key: Int, language: PlutusLanguage) =
                byKey
                  .get(BigInt(key))
                  .traverse(
                    CborRead
                      .array(_, DecodeField.AuxiliaryPlutusScripts)
                      .flatMap(_.traverse(CborRead.bytes(_, DecodeField.AuxiliaryPlutusScript)))
                  )
                  // an empty list adds no language (`addPlutusScripts`, `Alonzo/TxAuxData.hs:315-321`,
                  // cardano-ledger f649f975)
                  .map(_.flatMap(NonEmptyVector.fromVector).map(language -> _))
              (
                byKey.get(BigInt(0)).traverse(metadata).map(_.getOrElse(noMetadata)),
                byKey.get(BigInt(1)).traverse(native).map(_.orEmpty),
                plutus(2, PlutusLanguage.V1),
                plutus(3, PlutusLanguage.V2),
                plutus(4, PlutusLanguage.V3)
              ).mapN { (m, n, v1, v2, v3) =>
                val plutus = SortedMap.from(Vector(v1, v2, v3).flatten)
                val languages =
                  Map(2 -> PlutusLanguage.V1, 3 -> PlutusLanguage.V2, 4 -> PlutusLanguage.V3)
                // the keys in the encoded map's order, each once (the flat decoder refuses a key
                // twice)
                val arrival = keyed
                  .flatMap((k, _) => Option.when(k.isValidInt)(k.toInt).flatMap(languages.get))
                  .distinct
                  .flatMap(l => plutus.get(l).toVector.flatMap(_.toVector.map(l -> _)))
                (m, n, plutus, arrival)
              }
            }
        }
      // every other shape, as the flat decoder refuses it
      case Value.UInt(_) | Value.NInt(_) | Value.ByteString(_) | Value.Text(_) | Value.Bool(_) |
          Value.Null | Value.Simple(_) | Value.Float(_, _) =>
        Left(
          LedgerDecodeError
            .Expected(DecodeField.AuxiliaryData, LedgerDecodeError.Shape.ArrayOrMap, node.value)
        )

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
  )(using ByteStrings): Either[LedgerDecodeError, Vector[ConwayTimelock]] =
    CborRead
      .array(node, DecodeField.AuxiliaryNativeScripts)
      .flatMap(_.traverse(ConwayTimelock.read(_)))

  /** `EraTxAuxData`, `AllegraEraTxAuxData` and `AlonzoEraTxAuxData ConwayEra`
    * (`Conway/TxAuxData.hs:20-42`, cardano-ledger f649f975): Alonzo's lenses and validation.
    */
  val instance: AlonzoEraTxAuxData[Conway] =
    AlonzoTxAuxDataRep.Instance[Conway](ConwayScript.instance)
