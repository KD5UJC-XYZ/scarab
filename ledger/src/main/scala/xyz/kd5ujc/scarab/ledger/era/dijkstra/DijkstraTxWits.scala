// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import scala.annotation.unused
import scala.collection.immutable.{SortedMap, SortedSet}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value}
import xyz.kd5ujc.scarab.ledger.{
  BootstrapWitness,
  CborRead,
  DecodeField,
  LedgerDecodeError,
  Unsigned
}
import xyz.kd5ujc.scarab.ledger.dijkstra.{
  DijkstraLanguage as FlatLanguage,
  DijkstraWitnesses,
  Sparse,
  V12
}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraTxWits,
  AlonzoTxWitsRep,
  AsIx,
  Data,
  EraTxError,
  Redeemers,
  TxDats,
  WitVKey
}
import xyz.kd5ujc.scarab.ledger.state.Dijkstra
import xyz.kd5ujc.scarab.plutus.ExUnits

/** Dijkstra's witness set: `AlonzoTxWits DijkstraEra` (`type TxWits DijkstraEra = AlonzoTxWits
  * DijkstraEra`, `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxWits.hs:19`, cardano-ledger
  * 9df9f98c), the representation Alonzo's witness set has ([[AlonzoTxWitsRep]]) with Dijkstra's
  * scripts, Plutus V4 among them, by hash, and its redeemers keyed by Dijkstra's purposes, a type
  * of its own. Made only by [[DijkstraTxWits.decode]].
  */
opaque type DijkstraTxWits = AlonzoTxWitsRep[DijkstraScript, DijkstraPlutusPurpose[AsIx]]

object DijkstraTxWits
    extends AlonzoTxWitsRep.Fields[DijkstraTxWits, DijkstraScript, DijkstraPlutusPurpose[AsIx]]:
  given CanEqual[DijkstraTxWits, DijkstraTxWits] = CanEqual.derived

  /** A Dijkstra witness set read as the Leios build's block decoder reads it
    * ([[DijkstraWitnesses.decode]], which decides what is refused), projected to Dijkstra's types.
    */
  def decode(n: Node): Either[EraTxError, DijkstraTxWits] = DijkstraTx.witsOf(n)

  /** The witness set the flat decoder read, in Dijkstra's types. Its scripts are made in Dijkstra's
    * script type from the flat decoder's, each with the bytes it arrived as, and must have the flat
    * decoder's hashes. The flat decoder keeps not the bytes of the datums (key 4) and redeemers
    * (key 5), which the script integrity hash is over, so they are read again from the set's bytes
    * and must be the flat decoder's. Each datum and redeemer's data is decoded as the flat decoder
    * decoded it, which refused data that does not decode.
    */
  private[dijkstra] def fromFlat(flat: DijkstraWitnesses)(using
      @unused proof: DijkstraTx.FromBytes
  ): Either[EraTxError, DijkstraTxWits] =
    def data(bytes: Bytes, site: DecodeField) =
      Data.decode(bytes).leftMap(e => EraTxError.Decode(LedgerDecodeError.PlutusData(site, e)))
    val s = DijkstraScript.instance
    for
      read <- flat.scripts
        .traverse(f => script(f).map(f -> _))
        .leftMap(EraTxError.Decode(_))
      _ <- Either.cond(
        read.forall((f, x) => s.hashScript(x) == f.hash),
        (),
        EraTxError.ScriptsReadAgain
      )
      // of the scripts with one hash, which are one script, the first read, as the flat decoder keeps
      arrived = read.map((f, x) => f.hash -> x).distinctBy(_._1)
      again <- CborRead
        .decode(flat.raw, V12.nestedLimits)
        .flatMap(readAgain)
        .leftMap(EraTxError.Decode(_))
      // the datums and redeemers read again are the flat decoder's: the datums each with the bytes
      // it read, in its order; the redeemers by purpose, a purpose given twice the last one read
      // (`Map.fromList`), as it keeps them
      _ <- Either.cond(
        again.datums == flat.datums &&
          keyed(again.redeemers) == keyed(
            flat.redeemers.map(r => Redeemer(r.tag, r.index, r.data, r.memory, r.steps))
          ),
        (),
        EraTxError.WitnessDataReadAgain
      )
      // the flat decoder read the tag as one of Dijkstra's seven and the index as a `Word32`, and
      // refuses either otherwise with these errors
      redeemers <- flat.redeemers.traverse(r =>
        for
          index <- Unsigned
            .from[Unsigned.Width.W32](BigInt(r.index))
            .toRight(
              EraTxError.Decode(
                LedgerDecodeError
                  .NumberOutOfRange(DecodeField.RedeemerIndex, r.index, 0, V12.Word32Max)
              )
            )
          purpose <- DijkstraPlutusPurpose
            .asIx(r.tag, index)
            .toRight(
              EraTxError.Decode(LedgerDecodeError.UnknownTag(DecodeField.RedeemerTag, r.tag))
            )
          redeemer <- data(r.data, DecodeField.Redeemer)
        yield purpose -> (redeemer, ExUnits(r.memory, r.steps))
      )
      datums <- flat.datums.traverse(data(_, DecodeField.Datums))
    yield AlonzoTxWitsRep(
      flat.raw,
      SortedSet.from(flat.vkeys.map(WitVKey(_, _))),
      BootstrapWitness.set(
        flat.bootstraps.map(b => BootstrapWitness(b.key, b.signature, b.chainCode, b.attributes))
      ),
      SortedMap.from(arrived),
      arrived.map(_._1),
      TxDats(again.datumsRaw, SortedMap.from(datums.map(d => d.hashData -> d))),
      Redeemers(again.redeemersRaw, SortedMap.from(redeemers))
    )

  // The keys of a witness set (`txWitnessField`, `Alonzo/TxWits.hs:668-700`, cardano-ledger
  // 9df9f98c), made once.
  private val keys: Set[Int] = (0 to 8).toSet

  /** `DecCBOR (Annotator (AlonzoTxWitsRaw era))` at version 12 (`decodeSparseKeyed`,
    * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxWits.hs:620-700`, cardano-ledger 9df9f98c), read
    * from the item straight into Dijkstra's types: the keys and their order, each field and each
    * refusal are the Leios build's block decoder's ([[DijkstraWitnesses.decode]]), which this reads
    * as the flat decoder followed by [[fromFlat]] did. Each script is read once by Dijkstra's
    * script readers, which refuse what the flat decoder's refuse with the same errors, and each
    * datum and redeemer's data is decoded once, from the item ([[Data.read]]), not from its bytes
    * again.
    */
  private[dijkstra] def read(n: Node)(using
      @unused proof: DijkstraTx.FromBytes
  ): Either[EraTxError, DijkstraTxWits] =
    (for
      fields <- Sparse.read(n, DecodeField.WitnessSet, required = Set.empty)(keys)
      vkeys <- fields.get(0).traverse(DijkstraWitnesses.vkeyWitnesses)
      native <- fields.get(1).traverse(nativeScripts)
      bootstraps <- fields.get(2).traverse(DijkstraWitnesses.bootstrapWitnesses)
      v1 <- fields.get(3).traverse(plutusScripts(_, FlatLanguage.PlutusV1))
      datums <- fields.get(4).traverse(datumSet)
      redeemers <- fields.get(5).traverse(redeemerMap)
      v2 <- fields.get(6).traverse(plutusScripts(_, FlatLanguage.PlutusV2))
      v3 <- fields.get(7).traverse(plutusScripts(_, FlatLanguage.PlutusV3))
      v4 <- fields.get(8).traverse(plutusScripts(_, FlatLanguage.PlutusV4))
    yield
      // of the scripts with one hash, which are one script, the first read, native ones first; of
      // the redeemers of one purpose, the last read (`Map.fromList`), which `SortedMap.from` keeps
      val arrived = Vector(native, v1, v2, v3, v4).flatMap(_.orEmpty).distinctBy(_._1)
      AlonzoTxWitsRep(
        n.original,
        SortedSet.from(vkeys.orEmpty.map(WitVKey(_, _))),
        BootstrapWitness.set(
          bootstraps.orEmpty.map(b =>
            BootstrapWitness(b.key, b.signature, b.chainCode, b.attributes)
          )
        ),
        SortedMap.from(arrived),
        arrived.map(_._1),
        TxDats(fields.get(4).map(_.original), SortedMap.from(datums.orEmpty)),
        Redeemers(fields.get(5).map(_.original), SortedMap.from(redeemers.orEmpty))
      )
    ).leftMap(EraTxError.Decode(_))

  // A script with its hash (`hashScript`), which the set's uniqueness and the map are by.
  private def hashed(s: DijkstraScript): (Bytes, DijkstraScript) =
    DijkstraScript.instance.hashScript(s) -> s

  // Key 1: a non-empty set of native scripts, none twice by hash.
  private def nativeScripts(n: Node): Either[LedgerDecodeError, Vector[(Bytes, DijkstraScript)]] =
    V12.nonEmptySet(n, DecodeField.NativeScripts)(s =>
      DijkstraNativeScript.read(s).map(x => x.hash -> DijkstraScript.instance.fromNativeScript(x))
    )(_._1)

  // Keys 3, 6, 7 and 8: a non-empty set of Plutus scripts of a language, none twice by hash.
  private def plutusScripts(
      n: Node,
      language: FlatLanguage
  ): Either[LedgerDecodeError, Vector[(Bytes, DijkstraScript)]] =
    V12.nonEmptySet(n, DecodeField.PlutusScripts)(s =>
      V12
        .bytes(s, DecodeField.PlutusScript)
        .map(code =>
          hashed(
            DijkstraScript.instance.fromPlutusScript(DijkstraPlutusScript.ofFlat(language, code))
          )
        )
    )(_._1)

  // Key 4: `TxDats` from version 12 (`TxWits.hs:344-357`, cardano-ledger 9df9f98c), a non-empty set
  // of data, none twice by hash.
  private def datumSet(n: Node): Either[LedgerDecodeError, Vector[(Bytes, Data)]] =
    V12.nonEmptySet(n, DecodeField.Datums)(d =>
      Data
        .read(d)
        .bimap(LedgerDecodeError.PlutusData(DecodeField.Datums, _), x => x.hashData -> x)
    )(_._1)

  // Key 5: `RedeemersRaw` from version 12 (`TxWits.hs:566-614`, cardano-ledger 9df9f98c), a map only,
  // not empty, as [[DijkstraWitnesses.decode]] reads it.
  private def redeemerMap(
      n: Node
  ): Either[LedgerDecodeError, Vector[(DijkstraPlutusPurpose[AsIx], (Data, ExUnits))]] =
    n.value match
      case Value.Map(_) if !CborRead.mapLength64(n) =>
        V12
          .entries(n, DecodeField.Redeemers)
          .flatMap(_.traverse(redeemer))
          .flatMap(V12.nonEmpty(_, DecodeField.Redeemers))
      case other =>
        Left(LedgerDecodeError.Expected(DecodeField.Redeemers, LedgerDecodeError.Shape.Map, other))

  // A redeemer: its purpose `[tag, index]` (a `Word8` of 0 to 6 and a `Word32`) and `[data, units]`.
  private def redeemer(
      key: Node,
      value: Node
  ): Either[LedgerDecodeError, (DijkstraPlutusPurpose[AsIx], (Data, ExUnits))] =
    for
      k <- V12.record(key, 2, DecodeField.RedeemerKey)
      tag <- V12
        .word8(k(0), DecodeField.RedeemerTag)
        .flatMap(t =>
          Either.cond(t <= 6, t, LedgerDecodeError.UnknownTag(DecodeField.RedeemerTag, t))
        )
      index <- V12.word32(k(1), DecodeField.RedeemerIndex)
      v <- V12.record(value, 2, DecodeField.RedeemerValue)
      data <- Data.read(v(0)).leftMap(LedgerDecodeError.PlutusData(DecodeField.Redeemer, _))
      units <- V12.record(v(1), 2, DecodeField.ExUnits)
      memory <- V12.word(units(0), DecodeField.ExUnits, V12.Int64Max)
      steps <- V12.word(units(1), DecodeField.Steps, V12.Int64Max)
      // a tag of 0 to 6 is one of Dijkstra's purposes and the index a `Word32`, as read above
      wide <- Unsigned
        .from[Unsigned.Width.W32](BigInt(index))
        .toRight(
          LedgerDecodeError.NumberOutOfRange(DecodeField.RedeemerIndex, index, 0, V12.Word32Max)
        )
      purpose <- DijkstraPlutusPurpose
        .asIx(tag, wide)
        .toRight(LedgerDecodeError.UnknownTag(DecodeField.RedeemerTag, tag))
    yield purpose -> (data, ExUnits(memory, steps))

  /** A script the block decoder read, in Dijkstra's script type: a native script read from the
    * bytes the decoder kept of it, each level with its own bytes, or a Plutus script of its
    * language.
    */
  private[dijkstra] def script(
      f: xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraScript
  ): Either[LedgerDecodeError, DijkstraScript] =
    val s = DijkstraScript.instance
    f match
      case xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraScript.Native(raw) =>
        CborRead
          .decode(raw, V12.nestedLimits)
          .flatMap(DijkstraNativeScript.read(_))
          .map(s.fromNativeScript)
      case xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraScript.Plutus(language, code) =>
        Right(s.fromPlutusScript(DijkstraPlutusScript.ofFlat(language, code)))

  /** A redeemer as the flat decoder reads it: its purpose's tag and index, its data's bytes and its
    * units.
    */
  private final case class Redeemer(
      tag: Int,
      index: Long,
      data: Bytes,
      memory: BigInt,
      steps: BigInt
  )

  private def keyed(rs: Vector[Redeemer]): Map[(Int, Long), Redeemer] =
    rs.map(r => (r.tag, r.index) -> r).toMap

  /** What keys 4 and 5 of a witness set hold, read again: the bytes of each key, each datum's bytes
    * and each redeemer.
    */
  private final case class ReadAgain(
      datumsRaw: Option[Bytes],
      redeemersRaw: Option[Bytes],
      datums: Vector[Bytes],
      redeemers: Vector[Redeemer]
  )

  // The two items of a record of two the flat decoder read, which it refused otherwise.
  private def pair(n: Node, site: DecodeField): Either[LedgerDecodeError, (Node, Node)] =
    V12.record(n, 2, site).flatMap {
      case Vector(a, b) => Right((a, b))
      case xs           => Left(LedgerDecodeError.ArrayLength(site, 2, xs.size))
    }

  // A redeemer read again as the flat decoder read it (`RedeemersRaw`), its purpose's tag and index,
  // the bytes of its data, and its units (`Alonzo/TxWits.hs:566-614`, cardano-ledger 9df9f98c).
  private def redeemerAgain(key: Node, value: Node): Either[LedgerDecodeError, Redeemer] =
    for
      k <- pair(key, DecodeField.RedeemerKey)
      tag <- V12.word8(k._1, DecodeField.RedeemerTag)
      index <- V12.word32(k._2, DecodeField.RedeemerIndex)
      v <- pair(value, DecodeField.RedeemerValue)
      units <- pair(v._2, DecodeField.ExUnits)
      memory <- V12.word(units._1, DecodeField.ExUnits, V12.Int64Max)
      steps <- V12.word(units._2, DecodeField.Steps, V12.Int64Max)
    yield Redeemer(tag, index, v._1.original, memory, steps)

  // The datums (key 4) and redeemers (key 5) of the witness set, with the bytes of each key. The
  // flat decoder read the set before: each key is one it took, once, in a form it read.
  private def readAgain(root: Node): Either[LedgerDecodeError, ReadAgain] =
    for
      entries <- V12.entries(root, DecodeField.WitnessSet)
      keyed <- entries.traverse((k, v) => CborRead.uint(k, DecodeField.WitnessSet).map(_ -> v))
      byKey = keyed.toMap
      datums <- byKey
        .get(BigInt(4))
        .traverse(V12.setItems(_, DecodeField.Datums))
        .map(_.orEmpty.map(_.original))
      redeemers <- byKey
        .get(BigInt(5))
        .traverse(V12.entries(_, DecodeField.Redeemers).flatMap(_.traverse(redeemerAgain)))
        .map(_.orEmpty)
    yield ReadAgain(
      byKey.get(BigInt(4)).map(_.original),
      byKey.get(BigInt(5)).map(_.original),
      datums,
      redeemers
    )

  /** `EraTxWits DijkstraEra` and `AlonzoEraTxWits DijkstraEra` (`TxWits.hs:18-37`, cardano-ledger
    * 9df9f98c): Alonzo's lenses.
    */
  val instance: AlonzoEraTxWits[Dijkstra] =
    AlonzoTxWitsRep.Instance[Dijkstra](DijkstraScript.instance)
