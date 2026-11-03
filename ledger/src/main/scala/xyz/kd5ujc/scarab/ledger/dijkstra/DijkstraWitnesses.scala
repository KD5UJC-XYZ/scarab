// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, TreeFold, Value}
import xyz.kd5ujc.scarab.ledger.{CborRead, DecodeField, LedgerDecodeError}
import xyz.kd5ujc.scarab.ledger.LedgerDecodeError.Shape

import V12.Result

/** A bootstrap witness, its fields as read. */
final case class DijkstraBootstrap(
    key: Bytes,
    signature: Bytes,
    chainCode: Bytes,
    attributes: Bytes
)

/** A redeemer: its purpose's tag and index, the bytes of its data and its execution units. */
final case class DijkstraRedeemer(tag: Int, index: Long, data: Bytes, memory: BigInt, steps: BigInt)

/** The witness set of a Dijkstra transaction, its original bytes kept. */
final case class DijkstraWitnesses(
    raw: Bytes,
    vkeys: Vector[(Bytes, Bytes)],
    bootstraps: Vector[DijkstraBootstrap],
    scripts: Vector[DijkstraScript],
    datums: Vector[Bytes],
    redeemers: Vector[DijkstraRedeemer]
):
  /** The keys of the reference's script map: one hash per script, each once. */
  def scriptHashes: Vector[Bytes] = scripts.map(_.hash).distinct

  /** The keys of the reference's datum map (`hashData` of each datum's original bytes). */
  def datumHashes: Vector[Bytes] = datums.map(DijkstraDatum.hash)

object DijkstraWitnesses:
  /** `AlonzoTxWitsRaw` from version 12 (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxWits.hs:
    * 620-700, 759-769`, cardano-ledger 9df9f98c), Dijkstra's witness set: `decodeSparseKeyed` with
    * no required key over the keys 0 to 8.
    *
    *   - 0 and 2, the vkey and bootstrap witnesses: non-empty sets, the tag 258 allowed, no witness
    *     twice under its `Ord` (a vkey witness by its key's and signature's hashes,
    *     `Keys/WitVKey.hs:70-81`; a bootstrap witness by the hash of its key, chain code and
    *     attributes, its signature not compared, `Keys/Bootstrap.hs:115-116, 159-191`);
    *   - 1, native scripts, and 4, datums: non-empty sets keyed by the hash of each one's original
    *     bytes, none twice;
    *   - 3, 6, 7 and 8, Plutus V1 to V4 scripts: `scriptDecoderV9`, a non-empty list with the tag
    *     258 allowed and no script hash twice;
    *   - 5, the redeemers: a map only (`TypeMapLen` or `TypeMapLenIndef`; the list form and a map
    *     whose length is written in eight bytes are refused), not empty, of a purpose
    *     `[tag, index]` (`DijkstraPlutusPurpose` as a `CBORGroup`, a `Word8` of 0 to 6 and a
    *     `Word32`, `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Scripts.hs:146-182`) to
    *     `[data, exunits]`; a purpose given twice is kept once (`Map.fromList`).
    *
    * The script maps of the keys are joined.
    */
  def decode(n: Node): Result[DijkstraWitnesses] =
    for
      fields <- Sparse.read(n, DecodeField.WitnessSet, required = Set.empty)((0 to 8).toSet)
      vkeys <- fields.get(0).traverse(vkeyWitnesses)
      native <- fields.get(1).traverse(nativeScripts)
      bootstraps <- fields.get(2).traverse(bootstrapWitnesses)
      v1 <- fields.get(3).traverse(plutusScripts(_, DijkstraLanguage.PlutusV1))
      datums <- fields.get(4).traverse(datumSet)
      redeemers <- fields.get(5).traverse(redeemerMap)
      v2 <- fields.get(6).traverse(plutusScripts(_, DijkstraLanguage.PlutusV2))
      v3 <- fields.get(7).traverse(plutusScripts(_, DijkstraLanguage.PlutusV3))
      v4 <- fields.get(8).traverse(plutusScripts(_, DijkstraLanguage.PlutusV4))
    yield DijkstraWitnesses(
      n.original,
      vkeys.orEmpty,
      bootstraps.orEmpty,
      Vector(native, v1, v2, v3, v4).flatMap(_.orEmpty),
      datums.orEmpty,
      redeemers.orEmpty
    )

  /** `WitVKey` (`Keys/WitVKey.hs:106-109`): `decodeRecordNamed` of a 32-byte Ed25519 key and a
    * 64-byte signature, each `decodeFixedSized`.
    */
  private def vkeyWitnesses(n: Node): Result[Vector[(Bytes, Bytes)]] =
    V12.nonEmptySet(n, DecodeField.VkeyWitnesses)(w =>
      V12
        .record(w, 2, DecodeField.VkeyWitness)
        .flatMap(xs =>
          (
            V12.bytesN(xs(0), 32, DecodeField.Vkey),
            V12.bytesN(xs(1), 64, DecodeField.Signature)
          ).tupled
        )
    )(identity)

  /** `BootstrapWitness` (`Keys/Bootstrap.hs:79-112`): `decodeRecordNamed` of the key, the
    * signature, a chain code of exactly 32 bytes from version 12, and the attributes.
    */
  private def bootstrapWitnesses(n: Node): Result[Vector[DijkstraBootstrap]] =
    V12.nonEmptySet(n, DecodeField.BootstrapWitnesses)(w =>
      V12
        .record(w, 4, DecodeField.BootstrapWitness)
        .flatMap(xs =>
          (
            V12.bytesN(xs(0), 32, DecodeField.BootstrapVkey),
            V12.bytesN(xs(1), 64, DecodeField.BootstrapSignature),
            V12.bytesN(xs(2), 32, DecodeField.ChainCode),
            V12.bytes(xs(3), DecodeField.Attributes)
          ).mapN(DijkstraBootstrap(_, _, _, _))
        )
    )(b => (b.key, b.chainCode, b.attributes))

  private def nativeScripts(n: Node): Result[Vector[DijkstraScript]] =
    V12.nonEmptySet(n, DecodeField.NativeScripts)(DijkstraScript.native)(_.hash)

  private def plutusScripts(n: Node, language: DijkstraLanguage): Result[Vector[DijkstraScript]] =
    V12.nonEmptySet(n, DecodeField.PlutusScripts)(DijkstraScript.plutus(_, language))(_.hash)

  /** `TxDats` from version 12 (`TxWits.hs:344-357`): a non-empty set of Plutus `Data`, no datum
    * hash twice.
    */
  private def datumSet(n: Node): Result[Vector[Bytes]] =
    V12.nonEmptySet(n, DecodeField.Datums)(d =>
      DijkstraDatum.data(d, DecodeField.Datums).as(d.original)
    )(
      DijkstraDatum.hash
    )

  /** `RedeemersRaw` from version 12 (`TxWits.hs:566-614`). */
  private def redeemerMap(n: Node): Result[Vector[DijkstraRedeemer]] = n.value match
    case Value.Map(_) if !CborRead.mapLength64(n) =>
      V12
        .entries(n, DecodeField.Redeemers)
        .flatMap(_.traverse(redeemer))
        .flatMap(V12.nonEmpty(_, DecodeField.Redeemers))
        .map(_.map(r => (r.tag, r.index) -> r).toMap.toVector.sortBy(_._1).map(_._2))
    case other => Left(LedgerDecodeError.Expected(DecodeField.Redeemers, Shape.Map, other))

  private def redeemer(key: Node, value: Node): Result[DijkstraRedeemer] =
    for
      k <- V12.record(key, 2, DecodeField.RedeemerKey)
      tag <- V12
        .word8(k(0), DecodeField.RedeemerTag)
        .flatMap(t =>
          Either.cond(t <= 6, t, LedgerDecodeError.UnknownTag(DecodeField.RedeemerTag, t))
        )
      index <- V12.word32(k(1), DecodeField.RedeemerIndex)
      v <- V12.record(value, 2, DecodeField.RedeemerValue)
      _ <- DijkstraDatum.data(v(0), DecodeField.Redeemer)
      units <- V12.record(v(1), 2, DecodeField.ExUnits)
      memory <- V12.word(units(0), DecodeField.ExUnits, V12.Int64Max)
      steps <- V12.word(units(1), DecodeField.Steps, V12.Int64Max)
    yield DijkstraRedeemer(tag, index, v(0).original, memory, steps)
end DijkstraWitnesses

/** The auxiliary data of a Dijkstra transaction, its original bytes kept. */
final case class DijkstraAuxData(
    raw: Bytes,
    metadata: Vector[(BigInt, Bytes)],
    scripts: Vector[DijkstraScript]
)

object DijkstraAuxData:
  /** `AlonzoTxAuxDataRaw` from version 12
    * (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxAuxData.hs: 214-313`, 9df9f98c), chosen by the
    * first token: a map is metadata alone; a list is `decodeRecordNamed` of metadata and native
    * scripts (`decodeStrictSeq`); a tag must be 259 (`assertTag`) over `decodeSparseKeyed` with no
    * required key of metadata (0), native scripts (1) and lists of Plutus V1 to V4 scripts (2 to 5,
    * each `decodeList`, all allowed at 12 by `guardPlutus`); anything else is refused.
    */
  def decode(n: Node): Result[DijkstraAuxData] = n.value match
    case Value.Map(_) => metadata(n).map(DijkstraAuxData(n.original, _, Vector.empty))
    case Value.Arr(_) =>
      V12
        .record(n, 2, DecodeField.AuxiliaryData)
        .flatMap(xs => (metadata(xs(0)), natives(xs(1))).mapN(DijkstraAuxData(n.original, _, _)))
    case Value.Tag(t, inner) if t == 259 =>
      for
        fields <- Sparse.read(inner, DecodeField.AuxiliaryData, required = Set.empty)(
          (0 to 5).toSet
        )
        meta <- fields.get(0).traverse(metadata)
        native <- fields.get(1).traverse(natives)
        plutus <- Vector(
          2 -> DijkstraLanguage.PlutusV1,
          3 -> DijkstraLanguage.PlutusV2,
          4 -> DijkstraLanguage.PlutusV3,
          5 -> DijkstraLanguage.PlutusV4
        ).flatTraverse((k, lang) =>
          fields
            .get(k)
            .traverse(
              V12
                .list(_, DecodeField.AuxiliaryPlutusScripts)
                .flatMap(_.traverse(DijkstraScript.plutus(_, lang)))
            )
            .map(_.orEmpty)
        )
      yield DijkstraAuxData(n.original, meta.orEmpty, native.orEmpty ++ plutus)
    case Value.Tag(t, _) => Left(LedgerDecodeError.UnknownTag(DecodeField.AuxiliaryData, t))
    case other =>
      Left(LedgerDecodeError.Expected(DecodeField.AuxiliaryData, Shape.ArrayOrMap, other))

  private def natives(n: Node): Result[Vector[DijkstraScript]] =
    V12.list(n, DecodeField.AuxiliaryNativeScripts).flatMap(_.traverse(DijkstraScript.native))

  /** `Map Word64 Metadatum` (`decodeMap`, no label twice), each value kept as its bytes. */
  private def metadata(n: Node): Result[Vector[(BigInt, Bytes)]] =
    V12.map(n, DecodeField.Metadata)(V12.word64(_, DecodeField.MetadataLabel))((_, v) =>
      metadatum(v).as(v.original)
    )

  /** `decodeMetadatum` (`libs/cardano-ledger-core/src/Cardano/Ledger/Metadata.hs:153-221`): an
    * integer item (not a bignum), a byte string or a text of at most 64 bytes (chunked or not, the
    * chunks joined), or a list or map of metadata in any length form (a map's keys may repeat).
    * Nesting is unbounded, so the value is read on the heap ([[TreeFold]], ADR 0011).
    */
  private def metadatum(root: Node): Result[Unit] =
    TreeFold.foldE[Node, Unit, LedgerDecodeError](root) { node =>
      def bounded(size: Int): Result[TreeFold.Step[Node, Unit, LedgerDecodeError]] =
        Either.cond(
          size <= 64,
          TreeFold.Step.Leaf(()),
          LedgerDecodeError.NumberOutOfRange(DecodeField.MetadataByteLength, size, 0, 64)
        )
      node.value match
        case Value.UInt(_) | Value.NInt(_) => Right(TreeFold.Step.Leaf(()))
        case Value.ByteString(b)           => bounded(b.size)
        case Value.Text(s)  => bounded(s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
        case Value.Arr(xs)  => Right(TreeFold.Step.Branch(xs, _ => Right(())))
        case Value.Map(kvs) => Right(TreeFold.Step.Branch(TreeFold.flatten(kvs), _ => Right(())))
        case other =>
          Left(LedgerDecodeError.Expected(DecodeField.MetadataValue, Shape.Metadatum, other))
    }

end DijkstraAuxData
