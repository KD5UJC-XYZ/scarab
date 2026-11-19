// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import scala.annotation.unused
import scala.collection.immutable.SortedMap
import cats.Id
import cats.data.NonEmptyVector
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.{CborRead, DecodeField, LedgerDecodeError, Metadatum, Unsigned}
import xyz.kd5ujc.scarab.ledger.dijkstra.{DijkstraAuxData, V12}
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
