// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import scala.collection.immutable.{SortedMap, SortedSet}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Unique, Value}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{
  BootstrapWitness,
  ByteStrings,
  CborRead,
  DecodeField,
  DecoderVersion,
  DetachedRecords,
  LedgerDecodeError,
  ScriptKind,
  Unsigned,
  Witnesses
}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraTxWits,
  AlonzoTxWitsRep,
  AsIx,
  Data,
  EraTxError,
  Plutus,
  Redeemers,
  TxDats,
  WitVKey
}
import xyz.kd5ujc.scarab.ledger.state.Conway
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.protocol.Era

/** Conway's witness set: `AlonzoTxWits ConwayEra` (`type TxWits ConwayEra = AlonzoTxWits
  * ConwayEra`, `eras/conway/impl/src/Cardano/Ledger/Conway/TxWits.hs:25`, cardano-ledger f649f975),
  * the representation Alonzo's witness set has ([[AlonzoTxWitsRep]]) with Conway's scripts, by
  * hash, and its redeemers keyed by Conway's purposes, a type of its own. Made only by
  * [[ConwayTxWits.decode]].
  */
opaque type ConwayTxWits = AlonzoTxWitsRep[ConwayScript, ConwayPlutusPurpose[AsIx]]

object ConwayTxWits
    extends AlonzoTxWitsRep.Fields[ConwayTxWits, ConwayScript, ConwayPlutusPurpose[AsIx]]:
  private val Word32Max = (BigInt(1) << 32) - 1

  given CanEqual[ConwayTxWits, ConwayTxWits] = CanEqual.derived

  private val version = DecoderVersion.of(Era.Conway)

  /** A Conway witness set from its bytes, read in one pass (#85): `DecCBOR (Annotator
    * (AlonzoTxWitsRaw era))` below decoder version 12 (`SparseKeyed "AlonzoTxWits"` of
    * `txWitnessField`, `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxWits.hs:603-703`,
    * cardano-ledger f649f975) at version 9, the bytes parsed once and each field read once into
    * Conway's types (an input that holds an empty indefinite list and that the plain parse refuses
    * is parsed again with the detached records a walk finds, as the flat decoder parses it
    * ([[DetachedRecords.read]])): a native script by Conway's reader, each of its levels with its
    * own bytes, and each datum and redeemer's data decoded once, from its item ([[Data.read]]).
    *
    * It refuses what the two passes it replaced refuse ([[decodeByFlat]]), with the same first
    * refusal: the keys are read in the flat decoder's order (the keys a witness set has, then 0, 1,
    * 2, 3, 6, 7, 4 and 5), each with its leaf reader and refusal; Conway's native-script reader is
    * the flat one's (`NativeScript.decode` of the time locks, its refusal the same), and the data
    * reader the flat one's (`PlutusData.fromNode`). That order is not always the reference's, which
    * reads the keys as written; it is kept here, and left to the decoder's order item of #85
    * (https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7162357644).
    */
  def decode(raw: Bytes): Either[EraTxError, ConwayTxWits] =
    DetachedRecords
      .read(raw, Cbor.Limits.Unbounded, DetachedRecords.Shapes.witnesses(Era.Conway, version))(
        LedgerDecodeError.Syntax(_)
      )(read(raw, _))
      .bimap(EraTxError.Decode(_), project)

  /** [[decode]] of a witness set's item, parsed already with no detached record in it
    * ([[ConwayTx.fromEnvelope]]).
    */
  private[conway] def fromItem(n: Node): Either[EraTxError, ConwayTxWits] =
    read(n.original, n).bimap(EraTxError.Decode(_), project)

  // The keys of a witness set (`txWitnessField`), made once.
  private val keys: Set[Int] = (0 to 7).toSet

  /** A redeemer as the first phase read it: its purpose, its data and its units. */
  private final case class ReadRedeemer(
      purpose: ConwayPlutusPurpose[AsIx],
      data: Data,
      units: ExUnits
  )

  /** What the first phase read. */
  private final case class Read(
      raw: Bytes,
      vkeys: Vector[(Bytes, Bytes)],
      bootstrap: Vector[BootstrapWitness],
      scripts: Vector[(Bytes, ConwayScript)],
      datums: Option[Node],
      datumSet: Vector[Data],
      redeemers: Option[Node],
      redeemerList: Vector[ReadRedeemer]
  )

  /** The first phase: the flat decoder's reading of a witness set at Conway
    * ([[xyz.kd5ujc.scarab.ledger.Witnesses.decode]] at version 9). From version 9 each of the key,
    * bootstrap, native script, Plutus script and datum lists is optionally tagged and must not be
    * empty, as the redeemers must not; of the key witnesses, native scripts and datums alike the
    * first is kept, and of the bootstrap witnesses of one root the last ([[BootstrapWitness.set]]).
    */
  private def read(raw: Bytes, root: Node): Either[LedgerDecodeError, Read] =
    given ByteStrings = ByteStrings.of(version)
    def list(n: Node, what: DecodeField): Either[LedgerDecodeError, Vector[Node]] =
      CborRead.list(n, what, version).flatMap(CborRead.nonEmpty(_, what, true))
    def witnessSet[A, K: Ordering](n: Node, what: DecodeField)(
        elem: Node => Either[LedgerDecodeError, A]
    )(identity: A => K): Either[LedgerDecodeError, Vector[A]] =
      list(n, what).flatMap(_.traverse(elem)).map(Unique.firstsBy(_)(identity))
    val newest = ScriptKind.newest(Era.Conway)
    def plutus(key: Int, kind: ScriptKind, make: Bytes => ConwayPlutusScript)(
        f: Map[Int, Node]
    ) =
      f.get(key)
        .traverse(n =>
          CborRead
            .set(n, DecodeField.PlutusScripts, version)(
              CborRead.bytes(_, DecodeField.PlutusScript)
            )
            .flatMap(CborRead.nonEmpty(_, DecodeField.PlutusScripts, true))
            .flatMap(scripts =>
              Either.cond(
                scripts.isEmpty || kind.tag <= newest.tag,
                scripts.map(b => hashed(ConwayScript.plutus(make(b)))),
                LedgerDecodeError.UnsupportedLanguage(kind, newest)
              )
            )
        )
        .map(_.orEmpty)
    for
      f <- CborRead.fields(root, DecodeField.WitnessSet)
      _ <- Either.cond(
        f.keySet.subsetOf(keys),
        (),
        LedgerDecodeError.UnknownFields(DecodeField.WitnessSet, f.keySet -- keys)
      )
      vkeys <- f
        .get(0)
        .traverse(
          witnessSet(_, DecodeField.VkeyWitnesses) { w =>
            CborRead
              .arrayN(w, 2, DecodeField.VkeyWitness)
              .flatMap(xs =>
                (
                  CborRead.bytesN(xs(0), 32, DecodeField.Vkey),
                  CborRead.bytesN(xs(1), 64, DecodeField.Signature)
                ).tupled
              )
          }(identity)
        )
      native <- f
        .get(1)
        .traverse(
          witnessSet(_, DecodeField.NativeScripts)(n =>
            ConwayTimelock.read(n).map(t => hashed(ConwayScript.native(t)))
          )(_._1)
        )
      // every witness is kept for `BootstrapWitness.set`, which keeps the last of a root
      bootstrap <- f
        .get(2)
        .traverse(
          list(_, DecodeField.BootstrapWitnesses)
            .flatMap(_.traverse { w =>
              CborRead.arrayN(w, 4, DecodeField.BootstrapWitness).flatMap { xs =>
                (
                  CborRead.bytesN(xs(0), 32, DecodeField.BootstrapVkey),
                  CborRead.bytesN(xs(1), 64, DecodeField.BootstrapSignature),
                  BootstrapWitness.chainCode(xs(2), version),
                  CborRead.bytes(xs(3), DecodeField.Attributes)
                ).mapN(BootstrapWitness(_, _, _, _))
              }
            })
            .flatMap(BootstrapWitness.set(_, version))
        )
      v1 <- plutus(3, ScriptKind.PlutusV1, b => ConwayPlutusScript.V1(Plutus(b)))(f)
      v2 <- plutus(6, ScriptKind.PlutusV2, b => ConwayPlutusScript.V2(Plutus(b)))(f)
      v3 <- plutus(7, ScriptKind.PlutusV3, b => ConwayPlutusScript.V3(Plutus(b)))(f)
      datums <- f
        .get(4)
        .traverse(
          witnessSet(_, DecodeField.Datums)(n =>
            Data.read(n).leftMap(LedgerDecodeError.PlutusData(DecodeField.Datums, _))
          )(d => Blake2b.hash256.hash(d.originalBytes))
        )
      redeemers <- f.get(5).traverse(redeemerList)
      // of the scripts with one hash, which are one script, the first read, native ones first
      scripts = (native.orEmpty ++ v1 ++ v2 ++ v3).distinctBy(_._1)
    yield Read(
      raw,
      vkeys.orEmpty,
      bootstrap.orEmpty,
      scripts,
      f.get(4),
      datums.orEmpty,
      f.get(5),
      redeemers.orEmpty
    )

  // A script with its hash (`hashScript`), which the set's uniqueness and the map are by.
  private def hashed(s: ConwayScript): (Bytes, ConwayScript) =
    ConwayScript.instance.hashScript(s) -> s

  /** The redeemers (`DecCBOR (Annotator (RedeemersRaw era))`, `Alonzo/TxWits.hs:549-597`,
    * cardano-ledger f649f975) as the flat decoder reads them at version 9: the map form, chosen by
    * `peekTokenType` for a map whose length is not written in eight bytes, or the list form, not
    * empty either way; of the entries of one purpose the last read counts, in the order of the last
    * ones.
    */
  private def redeemerList(n: Node): Either[LedgerDecodeError, Vector[ReadRedeemer]] =
    def data(x: Node) = Data.read(x).leftMap(LedgerDecodeError.PlutusData(DecodeField.Redeemer, _))
    def units(x: Node) = Witnesses.exUnits(x, version)
    val all: Either[LedgerDecodeError, Vector[ReadRedeemer]] = n.value match
      case Value.Map(kvs) if !CborRead.mapLength64(n) =>
        kvs.traverse { case (k, v) =>
          for
            key <- CborRead.arrayN(k, 2, DecodeField.RedeemerKey)
            p <- purpose(key(0), key(1))
            value <- CborRead.definite(v, DecodeField.RedeemerValue) *>
              CborRead.arrayN(v, 2, DecodeField.RedeemerValue)
            d <- data(value(0))
            u <- units(value(1))
          yield ReadRedeemer(p, d, u)
        }
      case _ =>
        CborRead
          .array(n, DecodeField.Redeemers)
          .flatMap(_.traverse { r =>
            for
              xs <- CborRead.arrayN(r, 4, DecodeField.Redeemer)
              p <- purpose(xs(0), xs(1))
              d <- data(xs(2))
              u <- units(xs(3))
            yield ReadRedeemer(p, d, u)
          })
    all
      .flatMap(CborRead.nonEmpty(_, DecodeField.Redeemers, true))
      .map(_.reverse.distinctBy(_.purpose).reverse)

  /** A redeemer's purpose, as the flat decoder's `Redeemer.purpose` reads it at Conway: the tag a
    * `Word8` that names one of Conway's purposes, any other refused before the index is read, and
    * the index a `Word32` (`decCBORGroup` of `ConwayPlutusPurpose AsIx`,
    * `Conway/Scripts.hs:280-296`, cardano-ledger f649f975).
    */
  private def purpose(
      tag: Node,
      index: Node
  ): Either[LedgerDecodeError, ConwayPlutusPurpose[AsIx]] =
    for
      t <- Unsigned.read[Unsigned.Width.W8](tag, DecodeField.RedeemerTag).map(_.value)
      of <- Option
        .when(t.isValidInt)(t.toInt)
        .flatMap(ConwayPlutusPurpose.ofTag)
        .toRight(LedgerDecodeError.UnknownTag(DecodeField.RedeemerTag, t))
      i <- Unsigned.read[Unsigned.Width.W32](index, DecodeField.RedeemerIndex)
    yield of(i)

  /** The witness set the first phase read, in its representation. */
  private def project(r: Read): ConwayTxWits =
    AlonzoTxWitsRep(
      r.raw,
      SortedSet.from(r.vkeys.map(WitVKey(_, _))),
      r.bootstrap,
      SortedMap.from(r.scripts),
      r.scripts.map(_._1),
      TxDats(r.datums.map(_.original), SortedMap.from(r.datumSet.map(d => d.hashData -> d))),
      Redeemers(
        r.redeemers.map(_.original),
        SortedMap.from(r.redeemerList.map(x => x.purpose -> (x.data, x.units)))
      )
    )

  /** The two passes [[decode]] replaced (#85): Conway's decoder (the flat decoder at Conway) reads
    * it and refuses what Conway refuses; its scripts are then read from the same bytes into
    * Conway's script types, each level of a native script with its own bytes (the flat witness set
    * keeps a native script's bytes whole, not by level), and must be the scripts the flat decoder
    * read, by hash. Kept for the differential and the bench until the flat decoder goes (stage 6a).
    */
  def decodeByFlat(raw: Bytes): Either[EraTxError, ConwayTxWits] =
    // a datum and a redeemer's data are read as the flat decoder read them, which refuses data
    // that does not decode with this error; kept decoded here
    def data(bytes: Bytes, site: DecodeField) =
      Data.decode(bytes).leftMap(e => EraTxError.Decode(LedgerDecodeError.PlutusData(site, e)))
    for
      flat <- Witnesses.decode(raw, Era.Conway).leftMap(EraTxError.Decode(_))
      scripts <- DetachedRecords
        .read(raw, Cbor.Limits.Unbounded, DetachedRecords.Shapes.witnesses(Era.Conway))(
          LedgerDecodeError.Syntax(_)
        )(scriptsOf)
        .leftMap(EraTxError.Decode(_))
      _ <- Either.cond(
        scripts._1.keySet == flat.scriptsByHash.keySet,
        (),
        EraTxError.ScriptsReadAgain
      )
      // the flat decoder read the tag as one of Conway's and the index as a `Word32`
      // (`Redeemer.purpose`), and refuses either otherwise with these errors
      redeemers <- flat.redeemers.traverse(r =>
        for
          index <- Unsigned
            .from[Unsigned.Width.W32](r.index)
            .toRight(
              EraTxError.Decode(
                LedgerDecodeError.NumberOutOfRange(DecodeField.RedeemerIndex, r.index, 0, Word32Max)
              )
            )
          purpose <- ConwayPlutusPurpose
            .asIx(r.tag, index)
            .toRight(
              EraTxError.Decode(LedgerDecodeError.UnknownTag(DecodeField.RedeemerTag, r.tag))
            )
          redeemer <- data(r.data, DecodeField.Redeemer)
        yield purpose -> (redeemer, r.exUnits)
      )
      datums <- flat.datums.traverse(data(_, DecodeField.Datums))
    yield AlonzoTxWitsRep(
      raw,
      SortedSet.from(flat.vkeys.map(WitVKey(_, _))),
      flat.bootstrap,
      scripts._1,
      scripts._2,
      TxDats(flat.datumsRaw, SortedMap.from(datums.map(d => d.hashData -> d))),
      Redeemers(flat.redeemersRaw, SortedMap.from(redeemers))
    )

  // The scripts of the witness set (keys 1, 3, 6 and 7), each read with its own bytes. Each list
  // was read by the flat decoder before, so its form is one it read.
  private def scriptsOf(
      root: Node
  ): Either[LedgerDecodeError, (SortedMap[Bytes, ConwayScript], Vector[Bytes])] =
    val version = DecoderVersion.of(Era.Conway)
    given ByteStrings = ByteStrings.of(version)
    def items(key: Int, site: DecodeField)(fields: Map[Int, Node]) =
      fields.get(key).traverse(CborRead.list(_, site, version)).map(_.orEmpty)
    def plutus(key: Int, fields: Map[Int, Node])(make: Bytes => ConwayPlutusScript) =
      items(key, DecodeField.PlutusScripts)(fields)
        .flatMap(_.traverse(CborRead.bytes(_, DecodeField.PlutusScript)))
        .map(_.map(b => ConwayScript.plutus(make(b))))
    for
      fields <- CborRead.fields(root, DecodeField.WitnessSet)
      native <- items(1, DecodeField.NativeScripts)(fields)
        .flatMap(_.traverse(ConwayTimelock.read(_)).map(_.map(ConwayScript.native)))
      v1 <- plutus(3, fields)(b => ConwayPlutusScript.V1(Plutus(b)))
      v2 <- plutus(6, fields)(b => ConwayPlutusScript.V2(Plutus(b)))
      v3 <- plutus(7, fields)(b => ConwayPlutusScript.V3(Plutus(b)))
    // of the scripts with one hash, which are one script, the first read, as the flat decoder keeps
    yield
      val arrived = (native ++ v1 ++ v2 ++ v3)
        .map(s => ConwayScript.instance.hashScript(s) -> s)
        .distinctBy(_._1)
      (SortedMap.from(arrived), arrived.map(_._1))

  /** `EraTxWits ConwayEra` and `AlonzoEraTxWits ConwayEra` (`Conway/TxWits.hs:24-43`,
    * cardano-ledger f649f975): Alonzo's lenses.
    */
  val instance: AlonzoEraTxWits[Conway] = AlonzoTxWitsRep.Instance[Conway](ConwayScript.instance)
