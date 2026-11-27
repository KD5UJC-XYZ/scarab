// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.babbage

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{
  Address,
  ByteStrings,
  CborRead,
  Datum as FlatDatum,
  DecodeField,
  DecodeSite,
  LedgerDecodeError,
  ScriptKind,
  Value as FlatValue
}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraScript,
  AlonzoScriptRep,
  BabbageEraTxOut,
  BabbageTxOutRep,
  BabbageTxOuts,
  BinaryData,
  Data,
  Datum,
  EraTxError,
  Language,
  MultiAssetValue,
  NativeScriptRep,
  Plutus,
  PlutusLanguage,
  Sized,
  Timelock,
  TimelockScripts,
  Val
}
import xyz.kd5ujc.scarab.ledger.state.Babbage
import xyz.kd5ujc.scarab.plutus.PlutusData

// Babbage's types: the opaque types are transparent in this file only, so only this file makes a
// value of Babbage.

/** Babbage's native script: `Timelock BabbageEra` (cardano-ledger f649f975
  * `eras/babbage/impl/src/Cardano/Ledger/Babbage/Scripts.hs:42`).
  */
opaque type BabbageTimelock = Timelock

object BabbageTimelock:
  /** Babbage's native-script decoder: the time-lock language. */
  def decode(n: Node)(using ByteStrings): Either[EraTxError, BabbageTimelock] =
    read(n).leftMap(EraTxError.Decode(_))

  private[babbage] def read(
      n: Node
  )(using ByteStrings): Either[LedgerDecodeError, BabbageTimelock] =
    NativeScriptRep
      .decode[xyz.kd5ujc.scarab.ledger.era.NativeForm.Timelock](n)
      .leftMap(LedgerDecodeError.NativeScript(_))

  extension (t: BabbageTimelock)
    /** The shared representation, read only: the translation into Conway reads it. */
    def rep: Timelock = t

  given CanEqual[BabbageTimelock, BabbageTimelock] = CanEqual.derived

/** Babbage's Plutus script: the languages Babbage has, V1 and V2 (`data PlutusScript BabbageEra`,
  * `Babbage/Scripts.hs:57-59`, cardano-ledger f649f975).
  */
enum BabbagePlutusScript:
  case V1(plutus: Plutus[Language.PlutusV1])
  case V2(plutus: Plutus[Language.PlutusV2])

  def kind: ScriptKind = this match
    case V1(_) => ScriptKind.PlutusV1
    case V2(_) => ScriptKind.PlutusV2

  def binary: Bytes = this match
    case V1(p) => p.binary
    case V2(p) => p.binary

object BabbagePlutusScript:
  given CanEqual[BabbagePlutusScript, BabbagePlutusScript] = CanEqual.derived

  /** The Babbage script of `kind`, if Babbage has the language. */
  private[babbage] def of(kind: ScriptKind): Option[Bytes => BabbagePlutusScript] = kind match
    case ScriptKind.PlutusV1                                           => Some(b => V1(Plutus(b)))
    case ScriptKind.PlutusV2                                           => Some(b => V2(Plutus(b)))
    case ScriptKind.PlutusV3 | ScriptKind.PlutusV4 | ScriptKind.Native => None

/** Babbage's script: `AlonzoScript BabbageEra` (`Babbage/Scripts.hs:41`, cardano-ledger f649f975),
  * a Babbage native script or a Babbage Plutus script.
  */
opaque type BabbageScript = AlonzoScriptRep[BabbageTimelock, BabbagePlutusScript]

object BabbageScript:
  /** Babbage's script decoder (`decodeAlonzoScript` at `eraMaxLanguage = PlutusV2`,
    * `Babbage/Scripts.hs:63`, cardano-ledger f649f975).
    */
  def decode(n: Node)(using ByteStrings): Either[EraTxError, BabbageScript] =
    read(n).leftMap(EraTxError.Decode(_))

  private[babbage] def read(n: Node)(using ByteStrings): Either[LedgerDecodeError, BabbageScript] =
    AlonzoScriptRep.decode(n, ScriptKind.PlutusV2)(BabbageTimelock.read(_), BabbagePlutusScript.of)

  extension (s: BabbageScript)
    /** The shared representation, read only: the translation into Conway reads it. */
    def rep: AlonzoScriptRep[BabbageTimelock, BabbagePlutusScript] = s

  given CanEqual[BabbageScript, BabbageScript] = CanEqual.derived

  val instance: AlonzoEraScript[Babbage] = new TimelockScripts[Babbage]
    with AlonzoEraScript[Babbage]:
    override protected def timelock(native: BabbageTimelock): Timelock = native
    override protected def ofTimelock(t: Timelock): BabbageTimelock = t
    override def scriptPrefixTag(script: BabbageScript): Byte = script match
      case AlonzoScriptRep.NativeScript(_) => 0
      case AlonzoScriptRep.PlutusScript(p) => p.kind.tag.toByte
    override def originalBytes(script: BabbageScript): Bytes = script match
      case AlonzoScriptRep.NativeScript(t) => t.raw
      case AlonzoScriptRep.PlutusScript(p) => p.binary
    override def getNativeScript(script: BabbageScript): Option[BabbageTimelock] = script match
      case AlonzoScriptRep.NativeScript(t) => Some(t)
      case AlonzoScriptRep.PlutusScript(_) => None
    override def fromNativeScript(native: BabbageTimelock): BabbageScript =
      AlonzoScriptRep.NativeScript(native)
    override def eraMaxLanguage: xyz.kd5ujc.scarab.plutus.PlutusLanguage =
      xyz.kd5ujc.scarab.plutus.PlutusLanguage.V2
    override def toPlutusScript(script: BabbageScript): Option[BabbagePlutusScript] = script match
      case AlonzoScriptRep.PlutusScript(p) => Some(p)
      case AlonzoScriptRep.NativeScript(_) => None
    override def fromPlutusScript(plutus: BabbagePlutusScript): BabbageScript =
      AlonzoScriptRep.PlutusScript(plutus)
    // `withPlutusScript`, `Babbage/Scripts.hs:71-72`, cardano-ledger f649f975
    override def withPlutusScript[A](plutus: BabbagePlutusScript)(
        f: [L <: Language] => (Plutus[L], PlutusLanguage[L]) => A
    ): A = plutus match
      case BabbagePlutusScript.V1(p) => f(p, PlutusLanguage.v1)
      case BabbagePlutusScript.V2(p) => f(p, PlutusLanguage.v2)

/** A Babbage output: `BabbageTxOut BabbageEra`
  * (`eras/babbage/impl/src/Cardano/Ledger/Babbage/TxOut.hs:133-158`, cardano-ledger f649f975), its
  * reference script a Babbage script.
  */
opaque type BabbageTxOut = BabbageTxOutRep[BabbageScript]

object BabbageTxOut:
  /** A stored output read as a Babbage output (`ejectHardForkTxOut` of the current era,
    * ouroboros-consensus 82ecba329 `Cardano/Ledger.hs:137-162`): its inline datum taken unread, as
    * the reference's `MemPack` of `BinaryData` takes it, and its reference script read as a Babbage
    * script.
    */
  private[ledger] def fromStored(
      out: xyz.kd5ujc.scarab.ledger.TxOut
  ): Either[EraTxError, BabbageTxOut] =
    inverse(out)(BabbageTxOutRep.fromStored(out)(n => BabbageScript.read(n)))

  /** An output of a Babbage body read from its item: `decodeBabbageTxOut` below decoder version 12
    * (`eras/babbage/impl/src/Cardano/Ledger/Babbage/TxOut.hs:549-662`, cardano-ledger f649f975), at
    * Babbage's version. A list of two or three is read in its order; the map form (`SparseKeyed
    * "TxOut"` of `bodyFields`), chosen by `peekTokenType` for a map whose length is not written in
    * eight bytes (#701), has its keys read as written, as `decodeSparse` reads them: a key read
    * before is refused, then a key the output does not know, then its value; after the last a
    * missing address, then a missing value, is refused. Each value is read by the flat output
    * reader's reader of its field ([[xyz.kd5ujc.scarab.ledger.TxOut.decode]]), with its refusal;
    * the inline datum is read as data (`makeBinaryData`) and the reference script as a Babbage
    * script. The output is held to the form the store keeps of it, the flat output those fields
    * make ([[EraTxError.StoredOutputDiffers]]), with the size of the encoding it was read from
    * (`Sized`).
    */
  private[babbage] def read(n: Node, mode: Address.Mode, values: FlatValue.Decoding)(using
      ByteStrings
  ): Either[EraTxError, Sized[BabbageTxOut]] =
    val forms = xyz.kd5ujc.scarab.ledger.TxOut.Forms.PairDatumHashOrMap
    def address(x: Node) =
      CborRead.bytes(x, DecodeField.OutputAddress).flatMap(Address.decode(_, mode))
    def value(x: Node) = FlatValue.decode(x, DecodeField.OutputValue, values, forms.records)
    def made(f: Fields): Either[EraTxError, Sized[BabbageTxOut]] =
      (f.address, f.value) match
        case (Some(a), Some((v, item))) =>
          val (flatDatum, datum) = f.datum.getOrElse((FlatDatum.NoDatum, Datum.NoDatum))
          val out = BabbageTxOutRep(
            n.original,
            a,
            MultiAssetValue.of(v),
            item.original.size,
            datum,
            f.script.map(_._2)
          )
          val flat = xyz.kd5ujc.scarab.ledger
            .TxOut(n.original, a, v, flatDatum, f.script.map(_._1), item.original.size)
          Either.cond(
            toStored(out) == flat,
            Sized(out, flat.ledgerSize.toLong),
            EraTxError.StoredOutputDiffers
          )
        case (None, _) =>
          Left(
            EraTxError.Decode(
              LedgerDecodeError.MissingField(DecodeField.Output, DecodeField.Address)
            )
          )
        case (Some(_), None) =>
          Left(
            EraTxError.Decode(LedgerDecodeError.MissingField(DecodeField.Output, DecodeField.Value))
          )
    n.value match
      case CborValue.Arr(xs) if forms.arities.contains(xs.size) =>
        (for
          a <- address(xs(0))
          v <- value(xs(1))
          d <- xs
            .lift(2)
            .traverse(
              CborRead
                .bytesN(_, 32, DecodeField.DatumHash)
                .map(h => (FlatDatum.Hash(h), Datum.DatumHash(h)))
            )
        yield Fields(Some(a), Some((v, xs(1))), d, None))
          .leftMap(EraTxError.Decode(_))
          .flatMap(made)
      case CborValue.Map(_) if !CborRead.mapLength64(n) =>
        val what = DecodeField.Output
        CborRead
          .map(n, what)
          .flatMap(_.foldM((Fields(None, None, None, None), Set.empty[Int])) {
            case ((f, seen), (k, v)) =>
              for
                key <- CborRead
                  .uint(k, what / DecodeSite.Qualifier.Key)
                  .flatMap(key =>
                    Either.cond(key.isValidInt, key.toInt, LedgerDecodeError.UnknownKey(what, key))
                  )
                _ <- Either.cond(!seen.contains(key), (), LedgerDecodeError.DuplicateKey(what))
                _ <- Either.cond(
                  outputKeys.contains(key),
                  (),
                  LedgerDecodeError.UnknownFields(what, Set(key))
                )
                set <- key match
                  case 0 => address(v).map(a => f.copy(address = Some(a)))
                  case 1 => value(v).map(x => f.copy(value = Some((x, v))))
                  case 2 => datumOption(v).map(d => f.copy(datum = Some(d)))
                  case _ => referenceScript(v).map(s => f.copy(script = Some(s)))
              yield (set, seen + key)
          })
          .leftMap(EraTxError.Decode(_))
          .flatMap(r => made(r._1))
      case _ => Left(EraTxError.Decode(LedgerDecodeError.OutputForm(forms, n.value)))

  // `bodyFields` of the output's map, `Babbage/TxOut.hs:643-662`, cardano-ledger f649f975
  private val outputKeys: Set[Int] = Set(0, 1, 2, 3)

  /** What an output's keys read so far set, each field as the era's and as the flat output's. */
  private final case class Fields(
      address: Option[Address],
      value: Option[(FlatValue, Node)],
      datum: Option[(FlatDatum, Datum)],
      script: Option[(xyz.kd5ujc.scarab.ledger.Script, BabbageScript)]
  )

  // `datum_option = [0, hash] / [1, #6.24(bytes .cbor plutus_data)]`, the inline datum read as
  // data once, with the flat reader's refusal (`Data.readBytes`)
  private def datumOption(n: Node)(using
      ByteStrings
  ): Either[LedgerDecodeError, (FlatDatum, Datum)] =
    CborRead.arrayN(n, 2, DecodeField.DatumOption).flatMap { xs =>
      CborRead.uint(xs(0), DecodeField.DatumOptionTag).flatMap {
        case t if t == 0 =>
          CborRead
            .bytesN(xs(1), 32, DecodeField.DatumHash)
            .map(h => (FlatDatum.Hash(h), Datum.DatumHash(h)))
        case t if t == 1 =>
          xs(1).value match
            case CborValue.Tag(g, inner) if g == 24 =>
              CborRead
                .bytes(inner, DecodeField.InlineDatum)
                .flatMap(b =>
                  BinaryData
                    .makeBinaryData(b)
                    .bimap(
                      LedgerDecodeError.PlutusData(DecodeField.InlineDatum, _),
                      d => (FlatDatum.Inline(b), Datum.Datum(d))
                    )
                )
            case _ => Left(LedgerDecodeError.ExpectedTag(DecodeField.InlineDatum, 24, xs(1).value))
        case t => Left(LedgerDecodeError.UnknownTag(DecodeField.DatumOption, t))
      }
    }

  // `#6.24(bytes .cbor script)` (`decodeCIC "Script"`), read once as a Babbage script; the flat
  // output keeps it by its kind, hash and native tree
  private def referenceScript(n: Node)(using
      ByteStrings
  ): Either[LedgerDecodeError, (xyz.kd5ujc.scarab.ledger.Script, BabbageScript)] =
    n.value match
      case CborValue.Tag(t, inner) if t == 24 =>
        CborRead
          .bytes(inner, DecodeField.ReferenceScript)
          .flatMap(CborRead.decode(_))
          .flatMap(BabbageScript.read)
          .map(s => (flatScript(s), s))
      case _ => Left(LedgerDecodeError.ExpectedTag(DecodeField.ReferenceScript, 24, n.value))

  // the reference script as the flat reader keeps it (`Script.decodeWrapped`), without its bytes
  private def flatScript(s: BabbageScript): xyz.kd5ujc.scarab.ledger.Script = s match
    case AlonzoScriptRep.NativeScript(t) =>
      xyz.kd5ujc.scarab.ledger.Script.nativeOf(t.raw, t.script).withoutBytes
    case AlonzoScriptRep.PlutusScript(p) =>
      xyz.kd5ujc.scarab.ledger.Script.plutus(p.kind, p.binary).withoutBytes

  // the output written back is the one read, so `toStored` is its inverse
  private def inverse(out: xyz.kd5ujc.scarab.ledger.TxOut)(
      read: Either[LedgerDecodeError, BabbageTxOut]
  ): Either[EraTxError, BabbageTxOut] =
    read
      .leftMap(EraTxError.Decode(_))
      .flatMap(o => Either.cond(toStored(o) == out, o, EraTxError.StoredOutputDiffers))

  /** The output in the form the store keeps, the inverse of [[fromStored]] (which refuses a stored
    * output this does not give back): its bytes, fields and the value's size as they arrived, and
    * its reference script as the flat output keeps one, by its hash, with the tree of a native
    * script and without a Plutus script's bytes.
    */
  private[ledger] def toStored(out: BabbageTxOut): xyz.kd5ujc.scarab.ledger.TxOut =
    xyz.kd5ujc.scarab.ledger.TxOut(
      out.raw,
      out.address,
      out.value.stored,
      Datum.toStored(out.datum),
      out.scriptRef.map(storedScript),
      out.valueSize
    )

  private def storedScript(s: BabbageScript): xyz.kd5ujc.scarab.ledger.Script =
    val hash = BabbageScript.instance.hashScript(s)
    s match
      case AlonzoScriptRep.NativeScript(t) =>
        xyz.kd5ujc.scarab.ledger.Script(ScriptKind.Native, hash, Some(t.script))
      case AlonzoScriptRep.PlutusScript(p) => xyz.kd5ujc.scarab.ledger.Script(p.kind, hash, None)

  extension (out: BabbageTxOut)
    /** The shared representation, read only: the translation into the next era reads it. */
    def rep: BabbageTxOutRep[BabbageScript] = out

  given CanEqual[BabbageTxOut, BabbageTxOut] = CanEqual.derived

  val instance: BabbageEraTxOut[Babbage] = new BabbageEraTxOut[Babbage]:
    override def scripts: AlonzoEraScript[Babbage] = BabbageScript.instance
    override def valueOps: Val[MultiAssetValue] = summon[Val[MultiAssetValue]]
    override def address(out: BabbageTxOut): Address = out.address
    override def value(out: BabbageTxOut): MultiAssetValue = out.value
    override def raw(out: BabbageTxOut): Bytes = out.raw
    override def dataHash(out: BabbageTxOut): Option[Bytes] = BabbageTxOuts.dataHash(out.datum)
    override def datum(out: BabbageTxOut): Datum = out.datum
    override def data(out: BabbageTxOut): Either[PlutusData.DecodeError, Option[Data]] =
      BabbageTxOuts.data(out.datum)
    override def referenceScript(out: BabbageTxOut): Option[BabbageScript] = out.scriptRef
