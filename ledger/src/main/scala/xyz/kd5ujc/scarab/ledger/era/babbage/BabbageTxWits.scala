// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.babbage

import scala.collection.immutable.{SortedMap, SortedSet}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Unique}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{
  BootstrapWitness,
  ByteStrings,
  CborRead,
  DecodeField,
  DecodeSite,
  DecoderVersion,
  DetachedRecords,
  LedgerDecodeError,
  ScriptKind,
  Unsigned,
  Witnesses
}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraTxWits,
  AlonzoPlutusPurpose,
  AlonzoTxWitsRep,
  AsIx,
  Data,
  EraTxError,
  Plutus,
  Redeemers,
  TxDats,
  WitVKey
}
import xyz.kd5ujc.scarab.ledger.state.Babbage
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.protocol.Era

/** Babbage's witness set: `AlonzoTxWits BabbageEra` (`type TxWits BabbageEra = AlonzoTxWits
  * BabbageEra`, cardano-ledger f649f975
  * `eras/babbage/impl/src/Cardano/Ledger/Babbage/TxWits.hs:27`), the representation Alonzo's
  * witness set has ([[AlonzoTxWitsRep]]) with Babbage's scripts, by hash, and its redeemers keyed
  * by Alonzo's purposes, a type of its own. Made only by [[BabbageTxWits.decode]].
  */
opaque type BabbageTxWits =
  AlonzoTxWitsRep[BabbageScript, AlonzoPlutusPurpose[AsIx, Babbage]]

object BabbageTxWits
    extends AlonzoTxWitsRep.Fields[
      BabbageTxWits,
      BabbageScript,
      AlonzoPlutusPurpose[AsIx, Babbage]
    ]:
  given CanEqual[BabbageTxWits, BabbageTxWits] = CanEqual.derived

  private val version = DecoderVersion.of(Era.Babbage)

  // `txWitnessField`, `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxWits.hs:650-675`,
  // cardano-ledger f649f975
  private val keys: Set[Int] = (0 to 7).toSet

  /** A redeemer as read: its purpose, its data and its units. */
  private final case class ReadRedeemer(
      purpose: AlonzoPlutusPurpose[AsIx, Babbage],
      data: Data,
      units: ExUnits
  )

  /** What the keys read so far set: the reference's `emptyTxWitsRaw`, each field set by its key.
    * The scripts are kept per key, so they arrive in the flat decoder's order (native, then each
    * Plutus language's) whatever the order of the keys.
    */
  private final case class Fields(
      vkeys: Vector[(Bytes, Bytes)] = Vector.empty,
      native: Vector[(Bytes, BabbageScript)] = Vector.empty,
      bootstrap: Vector[BootstrapWitness] = Vector.empty,
      v1: Vector[(Bytes, BabbageScript)] = Vector.empty,
      v2: Vector[(Bytes, BabbageScript)] = Vector.empty,
      datums: Option[Node] = None,
      datumSet: Vector[Data] = Vector.empty,
      redeemers: Option[Node] = None,
      redeemerList: Vector[ReadRedeemer] = Vector.empty
  )

  /** A Babbage witness set from its bytes: `DecCBOR (Annotator (AlonzoTxWitsRaw era))` below
    * decoder version 12 (`SparseKeyed "AlonzoTxWits"` of `txWitnessField`,
    * `Alonzo/TxWits.hs:603-703`, cardano-ledger f649f975) at Babbage's version, 7. The keys are
    * read as they are written, as `decodeSparse` reads them
    * (`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/ Coders.hs:545-597`): a key
    * read before is refused, then a key the witness set does not know, then its value. Below
    * version 9 each list is a plain list, empty or not, of which the set keeps one of the elements
    * alike, and the redeemers are the list form alone; each value is read by the flat decoder's
    * reader of its key at version 7 ([[Witnesses.decode]]), so each refuses what that reader
    * refuses, with its error. A native script is read by Babbage's reader, each of its levels with
    * its own bytes, and each datum and redeemer's data is decoded once, from its item. The bytes
    * are parsed once; an input that holds an empty indefinite list and that the plain parse refuses
    * is parsed again with the detached records a walk finds ([[DetachedRecords.read]]).
    */
  def decode(raw: Bytes): Either[EraTxError, BabbageTxWits] =
    DetachedRecords
      .read(raw, Cbor.Limits.Unbounded, DetachedRecords.Shapes.witnesses(Era.Babbage, version))(
        LedgerDecodeError.Syntax(_)
      )(read)
      .bimap(EraTxError.Decode(_), project(raw, _))

  /** [[decode]] of a witness set's item, parsed already with no detached record in it
    * ([[BabbageTx.fromEnvelope]]).
    */
  private[babbage] def fromItem(n: Node): Either[EraTxError, BabbageTxWits] =
    read(n).bimap(EraTxError.Decode(_), project(n.original, _))

  private def read(root: Node): Either[LedgerDecodeError, Fields] =
    val what = DecodeField.WitnessSet
    CborRead
      .map(root, what)
      .flatMap(_.foldM((Fields(), Set.empty[Int])) { case ((fields, seen), (k, v)) =>
        for
          key <- CborRead
            .uint(k, what / DecodeSite.Qualifier.Key)
            .flatMap(key =>
              Either.cond(key.isValidInt, key.toInt, LedgerDecodeError.UnknownKey(what, key))
            )
          _ <- Either.cond(!seen.contains(key), (), LedgerDecodeError.DuplicateKey(what))
          _ <- Either.cond(keys.contains(key), (), LedgerDecodeError.UnknownFields(what, Set(key)))
          set <- field(key, v, fields)
        yield (set, seen + key)
      })
      .map(_._1)

  // A script with its hash (`hashScript`), which the set's uniqueness and the map are by.
  private def hashed(s: BabbageScript): (Bytes, BabbageScript) =
    BabbageScript.instance.hashScript(s) -> s

  /** The value of `key` read into `fields` (`txWitnessField`, `Alonzo/TxWits.hs:650-675`,
    * cardano-ledger f649f975) by the flat decoder's reader of the key at version 7: of the key
    * witnesses, native scripts and datums alike the first is kept, of the bootstrap witnesses of
    * one root the last ([[BootstrapWitness.set]]), and of the redeemers of one purpose the last.
    */
  private def field(key: Int, n: Node, fields: Fields): Either[LedgerDecodeError, Fields] =
    given ByteStrings = ByteStrings.of(version)
    def list(what: DecodeField) = CborRead.list(n, what, version)
    def witnessSet[A, K: Ordering](what: DecodeField)(
        elem: Node => Either[LedgerDecodeError, A]
    )(identity: A => K): Either[LedgerDecodeError, Vector[A]] =
      list(what).flatMap(_.traverse(elem)).map(Unique.firstsBy(_)(identity))
    def plutus(make: Bytes => BabbagePlutusScript) =
      CborRead
        .set(n, DecodeField.PlutusScripts, version)(CborRead.bytes(_, DecodeField.PlutusScript))
        .map(_.map(b => hashed(BabbageScript.plutus(make(b)))))
    key match
      case 0 =>
        witnessSet(DecodeField.VkeyWitnesses) { w =>
          CborRead
            .arrayN(w, 2, DecodeField.VkeyWitness)
            .flatMap(xs =>
              (
                CborRead.bytesN(xs(0), 32, DecodeField.Vkey),
                CborRead.bytesN(xs(1), 64, DecodeField.Signature)
              ).tupled
            )
        }(identity).map(v => fields.copy(vkeys = v))
      case 1 =>
        witnessSet(DecodeField.NativeScripts)(x =>
          BabbageTimelock.read(x).map(t => hashed(BabbageScript.native(t)))
        )(_._1).map(s => fields.copy(native = s))
      case 2 =>
        // every witness is kept for `BootstrapWitness.set`, which keeps the last of a root
        list(DecodeField.BootstrapWitnesses)
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
          .map(b => fields.copy(bootstrap = b))
      case 3 => plutus(b => BabbagePlutusScript.V1(Plutus(b))).map(s => fields.copy(v1 = s))
      case 6 => plutus(b => BabbagePlutusScript.V2(Plutus(b))).map(s => fields.copy(v2 = s))
      case 7 =>
        // PlutusV3 is newer than Babbage's languages: a list that holds a script is refused
        // (`alonzoPlutusScriptDecoder`, `Alonzo/TxWits.hs:725-740`, cardano-ledger f649f975), as
        // the flat reader refuses it, and an empty one sets nothing
        CborRead
          .set(n, DecodeField.PlutusScripts, version)(CborRead.bytes(_, DecodeField.PlutusScript))
          .flatMap(scripts =>
            Either.cond(
              scripts.isEmpty,
              fields,
              LedgerDecodeError
                .UnsupportedLanguage(ScriptKind.PlutusV3, ScriptKind.newest(Era.Babbage))
            )
          )
      case 4 =>
        witnessSet(DecodeField.Datums)(x =>
          Data.read(x).leftMap(LedgerDecodeError.PlutusData(DecodeField.Datums, _))
        )(d => Blake2b.hash256.hash(d.originalBytes))
          .map(d => fields.copy(datums = Some(n), datumSet = d))
      case 5 => redeemerList(n).map(r => fields.copy(redeemers = Some(n), redeemerList = r))
      // a key the witness set does not know was refused before its value is read
      case _ => Right(fields)

  /** The redeemers below decoder version 9 (`DecCBOR (Annotator (RedeemersRaw era))`,
    * `Alonzo/TxWits.hs:549-597`, cardano-ledger f649f975), as the flat decoder reads them at
    * version 7: the list form alone, empty or not, each redeemer a record of its purpose, data and
    * units; of the entries of one purpose the last read counts (`Map.fromList`), in the order of
    * the last ones.
    */
  private def redeemerList(n: Node): Either[LedgerDecodeError, Vector[ReadRedeemer]] =
    CborRead
      .array(n, DecodeField.Redeemers)
      .flatMap(_.traverse { r =>
        for
          xs <- CborRead.arrayN(r, 4, DecodeField.Redeemer)
          p <- purpose(xs(0), xs(1))
          d <- Data.read(xs(2)).leftMap(LedgerDecodeError.PlutusData(DecodeField.Redeemer, _))
          u <- Witnesses.exUnits(xs(3), version)
        yield ReadRedeemer(p, d, u)
      })
      .map(_.reverse.distinctBy(_.purpose).reverse)

  /** A redeemer's purpose, as the flat decoder's `Redeemer.purpose` reads it at Babbage: the tag a
    * `Word8` that names one of Alonzo's purposes, any other refused before the index is read, and
    * the index a `Word32` (`decCBORGroup` of `AlonzoPlutusPurpose`, `Alonzo/Scripts.hs:383-397`,
    * cardano-ledger f649f975).
    */
  private def purpose(
      tag: Node,
      index: Node
  ): Either[LedgerDecodeError, AlonzoPlutusPurpose[AsIx, Babbage]] =
    for
      t <- Unsigned.read[Unsigned.Width.W8](tag, DecodeField.RedeemerTag).map(_.value)
      of <- Option
        .when(t.isValidInt)(t.toInt)
        .flatMap(AlonzoPlutusPurpose.ofTag[Babbage])
        .toRight(LedgerDecodeError.UnknownTag(DecodeField.RedeemerTag, t))
      i <- Unsigned.read[Unsigned.Width.W32](index, DecodeField.RedeemerIndex)
    yield of(i)

  /** The witness set the keys read, in its representation: of the scripts with one hash, which are
    * one script, the first read, native ones first.
    */
  private def project(raw: Bytes, r: Fields): BabbageTxWits =
    val scripts = (r.native ++ r.v1 ++ r.v2).distinctBy(_._1)
    AlonzoTxWitsRep(
      raw,
      SortedSet.from(r.vkeys.map(WitVKey(_, _))),
      r.bootstrap,
      SortedMap.from(scripts),
      scripts.map(_._1),
      TxDats(r.datums.map(_.original), SortedMap.from(r.datumSet.map(d => d.hashData -> d))),
      Redeemers(
        r.redeemers.map(_.original),
        SortedMap.from(r.redeemerList.map(x => x.purpose -> (x.data, x.units)))
      )
    )

  /** `EraTxWits BabbageEra` and `AlonzoEraTxWits BabbageEra` (`Babbage/TxWits.hs:26-45`,
    * cardano-ledger f649f975): Alonzo's lenses.
    */
  val instance: AlonzoEraTxWits[Babbage] =
    AlonzoTxWitsRep.Instance[Babbage](BabbageScript.instance)
