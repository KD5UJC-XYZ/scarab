// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import scala.collection.immutable.SortedMap
import cats.data.NonEmptyVector
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
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
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraTxAuxData,
  AlonzoTxAuxData,
  EraScript,
  EraTxError,
  ProtVer,
  SupportedPlutusRunnable,
  UnavailableScript
}
import cats.Id
import xyz.kd5ujc.scarab.plutus.ScriptDecoder
import xyz.kd5ujc.scarab.ledger.state.Conway
import xyz.kd5ujc.scarab.protocol.Era

/** Conway's auxiliary data: `AlonzoTxAuxData ConwayEra` (`data AlonzoTxAuxDataRaw`, cardano-ledger
  * f649f975 `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxAuxData.hs:124-128`), with the bytes it
  * arrived as, which the body's auxiliary data hash is over. Made only by
  * [[ConwayTxAuxData.decode]].
  *
  * @param metadata
  *   by label (a `Word64`)
  * @param plutusScripts
  *   per language, the Plutus scripts' bytes
  * @param plutusArrival
  *   the Plutus scripts with their languages in the order they arrived: the fields in the encoded
  *   map's order, each list in its order
  */
final class ConwayTxAuxData private (
    val raw: Bytes,
    val metadata: SortedMap[Unsigned[Unsigned.Width.W64], Metadatum],
    val nativeScripts: Vector[ConwayTimelock],
    val plutusScripts: SortedMap[PlutusLanguage, NonEmptyVector[Bytes]],
    val plutusArrival: Vector[(PlutusLanguage, Bytes)]
):
  // Not a case class: its derived `Mirror` would make one from any fields, whatever its bytes.
  private def fields: Any = (raw, metadata, nativeScripts, plutusScripts, plutusArrival)
  override def equals(that: Any): Boolean = that match
    case o: ConwayTxAuxData => fields == o.fields
    case _                  => false
  override def hashCode(): Int = fields.hashCode
  override def toString: String = s"ConwayTxAuxData($fields)"

object ConwayTxAuxData:
  given CanEqual[ConwayTxAuxData, ConwayTxAuxData] = CanEqual.derived

  // the languages in `Language`'s order, as `Map Language` keeps them
  private given Ordering[PlutusLanguage] = Ordering.by(_.id)

  private given Ordering[Unsigned[Unsigned.Width.W64]] = Ordering.by(_.value)

  /** Conway's auxiliary data from its bytes. Conway's decoder (the flat decoder at Conway's decoder
    * version) reads it and refuses what Conway refuses; its fields are then read from the same
    * bytes into Conway's types, in any of the three forms Conway reads: a metadata map, the
    * `[metadata, native scripts]` list, or the map tagged 259 (`decodeTxAuxDataByTokenType`,
    * `Alonzo/TxAuxData.hs:302-312`, cardano-ledger f649f975).
    */
  def decode(raw: Bytes): Either[EraTxError, ConwayTxAuxData] =
    val version = DecoderVersion.of(Era.Conway)
    given ByteStrings = ByteStrings.of(version)
    (for
      _ <- AuxiliaryScripts.read(raw, Era.Conway, version.value)
      parts <- DetachedRecords.read(
        raw,
        Cbor.Limits.Unbounded,
        DetachedRecords.Shapes.auxiliaryDataAt(Era.Conway, version.value)
      )(LedgerDecodeError.Syntax(_))(fields)
    yield ConwayTxAuxData(raw, parts._1, parts._2, parts._3, parts._4))
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

  val instance: AlonzoEraTxAuxData[Conway] = new AlonzoEraTxAuxData[Conway]:
    override def originalBytes(aux: ConwayTxAuxData): Bytes = aux.raw
    // `validateTxAuxData = validateAlonzoTxAuxData`, `Conway/TxAuxData.hs:30`, cardano-ledger
    // f649f975
    override def validateTxAuxData(
        decoder: ScriptDecoder[Id],
        stAnnTxCache: Map[Bytes, SupportedPlutusRunnable[Conway]],
        pv: ProtVer,
        aux: ConwayTxAuxData
    ): Either[UnavailableScript, Boolean] =
      AlonzoTxAuxData.validateAlonzoTxAuxData[Conway](decoder, stAnnTxCache, pv, aux)(using
        this,
        EraScript.conway
      )
    override def metadata(
        aux: ConwayTxAuxData
    ): SortedMap[Unsigned[Unsigned.Width.W64], Metadatum] =
      aux.metadata
    override def nativeScripts(aux: ConwayTxAuxData): Vector[ConwayTimelock] = aux.nativeScripts
    override def plutusScripts(
        aux: ConwayTxAuxData
    ): SortedMap[PlutusLanguage, NonEmptyVector[Bytes]] =
      aux.plutusScripts
    override def plutusScriptsArrival(aux: ConwayTxAuxData): Vector[(PlutusLanguage, Bytes)] =
      aux.plutusArrival
