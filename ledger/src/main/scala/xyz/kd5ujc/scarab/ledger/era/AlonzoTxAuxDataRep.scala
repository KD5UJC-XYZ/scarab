// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import scala.collection.immutable.SortedMap
import cats.Id
import cats.data.NonEmptyVector
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Metadatum, Unsigned}
import xyz.kd5ujc.scarab.ledger.state.EraTag
import xyz.kd5ujc.scarab.plutus.{PlutusLanguage, ScriptDecoder}

/** The auxiliary data the reference shares from Alonzo (`newtype AlonzoTxAuxData era =
  * MkAlonzoTxAuxData (MemoBytes (AlonzoTxAuxDataRaw era))` over `data AlonzoTxAuxDataRaw`,
  * cardano-ledger f649f975 `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxAuxData.hs:124-128, 329`),
  * with the bytes it arrived as, which the body's auxiliary data hash is over. Parameterised by
  * what differs between the eras, the native script `S`: [[conway.ConwayTimelock]] behind Conway's
  * auxiliary data, [[dijkstra.DijkstraNativeScript]], which may require a guard, behind Dijkstra's.
  * Each era's auxiliary data is an opaque type over it, so the representation is shared and the
  * types are not; it has no `CanEqual`, so under strict equality two of its values are compared
  * only as the same era's.
  *
  * @param metadata
  *   by label (a `Word64`)
  * @param plutusScripts
  *   per language, the Plutus scripts' bytes
  * @param plutusArrival
  *   the Plutus scripts with their languages in the order they arrived, as the era's decoder lists
  *   them
  */
final class AlonzoTxAuxDataRep[S] private[era] (
    val raw: Bytes,
    val metadata: SortedMap[Unsigned[Unsigned.Width.W64], Metadatum],
    val nativeScripts: Vector[S],
    val plutusScripts: SortedMap[PlutusLanguage, NonEmptyVector[Bytes]],
    val plutusArrival: Vector[(PlutusLanguage, Bytes)]
):
  // Not a case class: its derived `Mirror` would make one from any fields, whatever its bytes.
  private def fields: Any = (raw, metadata, nativeScripts, plutusScripts, plutusArrival)
  override def equals(that: Any): Boolean = that match
    case o: AlonzoTxAuxDataRep[?] => fields == o.fields
    case _                        => false
  override def hashCode(): Int = fields.hashCode
  override def toString: String = s"AlonzoTxAuxDataRep($fields)"

object AlonzoTxAuxDataRep:
  /** The companion of an era's auxiliary data `A`, an opaque type over this representation: its
    * fields, read only, written once for every era. Only the file that defines `A` can give the
    * evidence that `A` is the representation.
    */
  abstract class Fields[A, S](using is: A =:= AlonzoTxAuxDataRep[S]):
    extension (a: A)
      /** The shared representation, read only. */
      def rep: AlonzoTxAuxDataRep[S] = is(a)
      def raw: Bytes = is(a).raw
      def metadata: SortedMap[Unsigned[Unsigned.Width.W64], Metadatum] = is(a).metadata
      def nativeScripts: Vector[S] = is(a).nativeScripts
      def plutusScripts: SortedMap[PlutusLanguage, NonEmptyVector[Bytes]] = is(a).plutusScripts
      def plutusArrival: Vector[(PlutusLanguage, Bytes)] = is(a).plutusArrival

  /** `EraTxAuxData`, `AllegraEraTxAuxData` and `AlonzoEraTxAuxData` of every era whose auxiliary
    * data is this representation, written once as the reference writes Alonzo's lenses and
    * validation once over `AlonzoTxAuxData era` (`metadataAlonzoTxAuxDataL`,
    * `validateAlonzoTxAuxData`, `nativeScriptsAlonzoTxAuxDataL` and
    * `plutusScriptsAllegraTxAuxDataL`, `Alonzo/TxAuxData.hs:356-400`, cardano-ledger f649f975). An
    * era's instance names its scripts' class; only its own file can give the evidence that its
    * auxiliary data is the representation, and its cache is the runnable scripts by hash, as each
    * era's instance asks (`StAnnTxCache era ~ Map ScriptHash (SupportedPlutusRunnable era)`,
    * `Conway/TxAuxData.hs:20-22`).
    */
  final class Instance[E <: EraTag](scripts: AlonzoEraScript[E])(using
      rep: TxAuxData[E] =:= AlonzoTxAuxDataRep[NativeScript[E]],
      cache: StAnnTxCache[E] =:= Map[Bytes, SupportedPlutusRunnable[E]]
  ) extends AlonzoEraTxAuxData[E]:
    override def originalBytes(aux: TxAuxData[E]): Bytes = rep(aux).raw
    override def validateTxAuxData(
        decoder: ScriptDecoder[Id],
        stAnnTxCache: StAnnTxCache[E],
        pv: ProtVer,
        aux: TxAuxData[E]
    ): Either[UnavailableScript, Boolean] =
      AlonzoTxAuxData.validateAlonzoTxAuxData[E](decoder, cache(stAnnTxCache), pv, aux)(using
        this,
        scripts
      )
    override def metadata(aux: TxAuxData[E]): SortedMap[Unsigned[Unsigned.Width.W64], Metadatum] =
      rep(aux).metadata
    override def nativeScripts(aux: TxAuxData[E]): Vector[NativeScript[E]] =
      rep(aux).nativeScripts
    override def plutusScripts(
        aux: TxAuxData[E]
    ): SortedMap[PlutusLanguage, NonEmptyVector[Bytes]] =
      rep(aux).plutusScripts
    override def plutusScriptsArrival(aux: TxAuxData[E]): Vector[(PlutusLanguage, Bytes)] =
      rep(aux).plutusArrival
