// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import scala.annotation.unused
import scala.collection.immutable.SortedMap
import cats.Id
import cats.data.NonEmptyVector
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value}
import xyz.kd5ujc.scarab.ledger.{CborRead, DecodeField, LedgerDecodeError, Metadatum, Unsigned}
import xyz.kd5ujc.scarab.ledger.dijkstra.{
  DijkstraAuxData,
  DijkstraLanguage as FlatLanguage,
  Sparse,
  V12
}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraTxAuxData,
  AlonzoTxAuxData,
  EraScript,
  EraTxError,
  ProtVer,
  SupportedPlutusRunnable,
  UnavailableScript
}
import xyz.kd5ujc.scarab.ledger.state.Dijkstra
import xyz.kd5ujc.scarab.plutus.{PlutusLanguage, ScriptDecoder}

/** Dijkstra's auxiliary data: `AlonzoTxAuxData DijkstraEra` (`instance EraTxAuxData DijkstraEra`,
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxAuxData.hs:20-42`, cardano-ledger 9df9f98c;
  * `data AlonzoTxAuxDataRaw`, `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxAuxData.hs:124-128`),
  * with the bytes it arrived as, which the body's auxiliary data hash is over. Its native scripts
  * are Dijkstra's, which may require a guard. Made only by [[DijkstraTxAuxData.decode]].
  *
  * @param metadata
  *   by label (a `Word64`)
  * @param plutusScripts
  *   per language, Plutus V1 to V4, the Plutus scripts' bytes
  * @param plutusArrival
  *   the Plutus scripts with their languages in the order they arrived as the block decoder lists
  *   them: by language, each list in its order
  */
final class DijkstraTxAuxData private (
    val raw: Bytes,
    val metadata: SortedMap[Unsigned[Unsigned.Width.W64], Metadatum],
    val nativeScripts: Vector[DijkstraNativeScript],
    val plutusScripts: SortedMap[PlutusLanguage, NonEmptyVector[Bytes]],
    val plutusArrival: Vector[(PlutusLanguage, Bytes)]
):
  // Not a case class: its derived `Mirror` would make one from any fields, whatever its bytes.
  private def fields: Any = (raw, metadata, nativeScripts, plutusScripts, plutusArrival)
  override def equals(that: Any): Boolean = that match
    case o: DijkstraTxAuxData => fields == o.fields
    case _                    => false
  override def hashCode(): Int = fields.hashCode
  override def toString: String = s"DijkstraTxAuxData($fields)"

object DijkstraTxAuxData:
  given CanEqual[DijkstraTxAuxData, DijkstraTxAuxData] = CanEqual.derived

  // the languages in `Language`'s order, as `Map Language` keeps them
  private given Ordering[PlutusLanguage] = Ordering.by(_.id)

  private given Ordering[Unsigned[Unsigned.Width.W64]] = Ordering.by(_.value)

  /** Dijkstra's auxiliary data read as the Leios build's block decoder reads it
    * ([[DijkstraAuxData.decode]], which decides what is refused), projected to Dijkstra's types.
    */
  def decode(n: Node): Either[EraTxError, DijkstraTxAuxData] = DijkstraTx.auxDataOf(n)

  /** The auxiliary data the flat decoder read, which decided its form and what is refused, in
    * Dijkstra's types: each metadatum read from the bytes the decoder kept of it, each native
    * script from its bytes, each level with its own, and the Plutus scripts per language. The
    * scripts arrive in the order the decoder lists them, the native ones first and then the Plutus
    * ones by language (`decodeTxAuxDataByTokenType`, `Alonzo/TxAuxData.hs:302-313`, cardano-ledger
    * 9df9f98c), which is the order of the reference's `Map Language`.
    */
  private[dijkstra] def fromFlat(flat: DijkstraAuxData)(using
      @unused proof: DijkstraTx.FromBytes
  ): Either[EraTxError, DijkstraTxAuxData] =
    (
      flat.metadata.traverse(metadatum),
      flat.scripts.traverse(DijkstraTxWits.script)
    ).mapN { (metadata, scripts) =>
      val s = DijkstraScript.instance
      val natives = scripts.flatMap(s.getNativeScript)
      val plutus = scripts.flatMap(s.toPlutusScript).map(p => p.language -> p.binary)
      val byLanguage = SortedMap.from(
        plutus
          .groupBy(_._1)
          .flatMap((language, ps) => NonEmptyVector.fromVector(ps.map(_._2)).map(language -> _))
      )
      DijkstraTxAuxData(flat.raw, SortedMap.from(metadata), natives, byLanguage, plutus)
    }.leftMap(EraTxError.Decode(_))

  // The keys of the tagged form (`decodeAlonzo`, `Alonzo/TxAuxData.hs:258-300`, cardano-ledger
  // 9df9f98c), made once.
  private val keys: Set[Int] = (0 to 5).toSet

  /** `DecCBOR (Annotator (AlonzoTxAuxDataRaw era))` at version 12
    * (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxAuxData.hs:214-313`, cardano-ledger 9df9f98c),
    * read from the item straight into Dijkstra's types: chosen by the first token as the Leios
    * build's block decoder chooses ([[DijkstraAuxData.decode]]), with its refusals, a map of
    * metadata alone, a list of metadata and native scripts, or the tag 259 over a sparse map of
    * metadata (0), native scripts (1) and Plutus V1 to V4 scripts (2 to 5). Each metadatum is read
    * once, from the item, by [[Metadatum.read]], which refuses what the flat decoder's metadatum
    * refuses with the same errors, and each native script by Dijkstra's reader, likewise.
    */
  private[dijkstra] def read(n: Node)(using
      @unused proof: DijkstraTx.FromBytes
  ): Either[EraTxError, DijkstraTxAuxData] =
    def of(
        metadata: Vector[(Unsigned[Unsigned.Width.W64], Metadatum)],
        natives: Vector[DijkstraNativeScript],
        plutus: Vector[DijkstraPlutusScript]
    ) =
      val arrival = plutus.map(p => p.language -> p.binary)
      val byLanguage = SortedMap.from(
        arrival
          .groupBy(_._1)
          .flatMap((language, ps) => NonEmptyVector.fromVector(ps.map(_._2)).map(language -> _))
      )
      DijkstraTxAuxData(n.original, SortedMap.from(metadata), natives, byLanguage, arrival)
    val read: Either[LedgerDecodeError, DijkstraTxAuxData] = n.value match
      case Value.Map(_) => metadataMap(n).map(of(_, Vector.empty, Vector.empty))
      case Value.Arr(_) =>
        V12
          .record(n, 2, DecodeField.AuxiliaryData)
          .flatMap(xs => (metadataMap(xs(0)), natives(xs(1))).mapN(of(_, _, Vector.empty)))
      case Value.Tag(t, inner) if t == 259 =>
        for
          fields <- Sparse.read(inner, DecodeField.AuxiliaryData, required = Set.empty)(keys)
          meta <- fields.get(0).traverse(metadataMap)
          native <- fields.get(1).traverse(natives)
          plutus <- Vector(
            2 -> FlatLanguage.PlutusV1,
            3 -> FlatLanguage.PlutusV2,
            4 -> FlatLanguage.PlutusV3,
            5 -> FlatLanguage.PlutusV4
          ).flatTraverse((k, language) =>
            fields
              .get(k)
              .traverse(
                V12
                  .list(_, DecodeField.AuxiliaryPlutusScripts)
                  .flatMap(
                    _.traverse(p =>
                      V12
                        .bytes(p, DecodeField.PlutusScript)
                        .map(DijkstraPlutusScript.ofFlat(language, _))
                    )
                  )
              )
              .map(_.orEmpty)
          )
        yield of(meta.orEmpty, native.orEmpty, plutus)
      case Value.Tag(t, _) => Left(LedgerDecodeError.UnknownTag(DecodeField.AuxiliaryData, t))
      case other =>
        Left(
          LedgerDecodeError
            .Expected(DecodeField.AuxiliaryData, LedgerDecodeError.Shape.ArrayOrMap, other)
        )
    read.leftMap(EraTxError.Decode(_))

  // `decodeStrictSeq` of native scripts, each read by Dijkstra's reader.
  private def natives(n: Node): Either[LedgerDecodeError, Vector[DijkstraNativeScript]] =
    V12.list(n, DecodeField.AuxiliaryNativeScripts).flatMap(_.traverse(DijkstraNativeScript.read))

  // `Map Word64 Metadatum` (`decodeMap`, no label twice), each value read from its item.
  private def metadataMap(
      n: Node
  ): Either[LedgerDecodeError, Vector[(Unsigned[Unsigned.Width.W64], Metadatum)]] =
    V12
      .map(n, DecodeField.Metadata)(V12.word64(_, DecodeField.MetadataLabel))((_, v) =>
        Metadatum.read(v, true)
      )
      .flatMap(
        _.traverse((label, m) =>
          // a `Word64`, as read above
          Unsigned
            .from[Unsigned.Width.W64](label)
            .toRight(
              LedgerDecodeError.NumberOutOfRange(DecodeField.MetadataLabel, label, 0, V12.Word64Max)
            )
            .tupleRight(m)
        )
      )

  // A metadatum the flat decoder read, its label a `Word64` and its value read from its bytes.
  private def metadatum(
      entry: (BigInt, Bytes)
  ): Either[LedgerDecodeError, (Unsigned[Unsigned.Width.W64], Metadatum)] =
    val (label, bytes) = entry
    (
      Unsigned
        .from[Unsigned.Width.W64](label)
        .toRight(
          LedgerDecodeError.NumberOutOfRange(DecodeField.MetadataLabel, label, 0, V12.Word64Max)
        ),
      CborRead.decode(bytes, V12.nestedLimits).flatMap(Metadatum.read(_, true))
    ).tupled

  /** `EraTxAuxData`, `AllegraEraTxAuxData` and `AlonzoEraTxAuxData DijkstraEra`
    * (`TxAuxData.hs:20-42`, cardano-ledger 9df9f98c): Alonzo's lenses and validation.
    */
  val instance: AlonzoEraTxAuxData[Dijkstra] = new AlonzoEraTxAuxData[Dijkstra]:
    override def originalBytes(aux: DijkstraTxAuxData): Bytes = aux.raw
    // `validateTxAuxData = validateAlonzoTxAuxData`, `TxAuxData.hs:30`, cardano-ledger 9df9f98c
    override def validateTxAuxData(
        decoder: ScriptDecoder[Id],
        stAnnTxCache: Map[Bytes, SupportedPlutusRunnable[Dijkstra]],
        pv: ProtVer,
        aux: DijkstraTxAuxData
    ): Either[UnavailableScript, Boolean] =
      AlonzoTxAuxData.validateAlonzoTxAuxData[Dijkstra](decoder, stAnnTxCache, pv, aux)(using
        this,
        EraScript.dijkstra
      )
    override def metadata(
        aux: DijkstraTxAuxData
    ): SortedMap[Unsigned[Unsigned.Width.W64], Metadatum] = aux.metadata
    override def nativeScripts(aux: DijkstraTxAuxData): Vector[DijkstraNativeScript] =
      aux.nativeScripts
    override def plutusScripts(
        aux: DijkstraTxAuxData
    ): SortedMap[PlutusLanguage, NonEmptyVector[Bytes]] = aux.plutusScripts
    override def plutusScriptsArrival(aux: DijkstraTxAuxData): Vector[(PlutusLanguage, Bytes)] =
      aux.plutusArrival
