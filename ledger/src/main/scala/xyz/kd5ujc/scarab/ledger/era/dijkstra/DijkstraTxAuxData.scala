// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import scala.annotation.unused
import scala.collection.immutable.SortedMap
import cats.data.NonEmptyVector
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Node, Value}
import xyz.kd5ujc.scarab.ledger.{DecodeField, LedgerDecodeError, Metadatum, Unsigned}
import xyz.kd5ujc.scarab.ledger.dijkstra.{DijkstraLanguage as FlatLanguage, Sparse, V12}
import xyz.kd5ujc.scarab.ledger.era.{AlonzoEraTxAuxData, AlonzoTxAuxDataRep, EraTxError}
import xyz.kd5ujc.scarab.ledger.state.Dijkstra
import xyz.kd5ujc.scarab.plutus.PlutusLanguage

/** Dijkstra's auxiliary data: `AlonzoTxAuxData DijkstraEra` (`type TxAuxData DijkstraEra =
  * AlonzoTxAuxData DijkstraEra`, `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxAuxData.hs:24`,
  * cardano-ledger 9df9f98c), the representation Alonzo's auxiliary data has
  * ([[AlonzoTxAuxDataRep]]) with Dijkstra's native scripts, which may require a guard, a type of
  * its own. Its Plutus scripts, V1 to V4, arrive as the block decoder lists them: by language, each
  * list in its order. Made only by [[DijkstraTxAuxData.decode]].
  */
opaque type DijkstraTxAuxData = AlonzoTxAuxDataRep[DijkstraNativeScript]

object DijkstraTxAuxData extends AlonzoTxAuxDataRep.Fields[DijkstraTxAuxData, DijkstraNativeScript]:
  given CanEqual[DijkstraTxAuxData, DijkstraTxAuxData] = CanEqual.derived

  // the languages in `Language`'s order, as `Map Language` keeps them
  private given Ordering[PlutusLanguage] = Ordering.by(_.id)

  private given Ordering[Unsigned[Unsigned.Width.W64]] = Ordering.by(_.value)

  /** Dijkstra's auxiliary data read from its item into Dijkstra's types, as the Leios build's block
    * decoder reads it ([[read]]).
    */
  def decode(n: Node): Either[EraTxError, DijkstraTxAuxData] = DijkstraTx.auxDataOf(n)

  // The keys of the tagged form (`decodeAlonzo`, `Alonzo/TxAuxData.hs:258-300`, cardano-ledger
  // 9df9f98c), made once.
  private val keys: Set[Int] = (0 to 5).toSet

  /** `DecCBOR (Annotator (AlonzoTxAuxDataRaw era))` at version 12
    * (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxAuxData.hs:214-313`, cardano-ledger 9df9f98c),
    * read from the item straight into Dijkstra's types: chosen by the first token as the Leios
    * build's block decoder chooses, with its refusals, a map of metadata alone, a list of metadata
    * and native scripts, or the tag 259 over a sparse map of metadata (0), native scripts (1) and
    * Plutus V1 to V4 scripts (2 to 5). Each metadatum is read once, from the item, by
    * [[Metadatum.read]], and each native script by Dijkstra's reader.
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
      AlonzoTxAuxDataRep(n.original, SortedMap.from(metadata), natives, byLanguage, arrival)
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

  /** `EraTxAuxData`, `AllegraEraTxAuxData` and `AlonzoEraTxAuxData DijkstraEra`
    * (`TxAuxData.hs:20-42`, cardano-ledger 9df9f98c): Alonzo's lenses and validation.
    */
  val instance: AlonzoEraTxAuxData[Dijkstra] =
    AlonzoTxAuxDataRep.Instance[Dijkstra](DijkstraScript.instance)
