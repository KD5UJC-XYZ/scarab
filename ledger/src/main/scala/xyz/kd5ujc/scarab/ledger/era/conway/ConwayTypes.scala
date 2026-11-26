// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import scala.collection.immutable.{SortedSet, TreeMap}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{
  Address,
  ByteStrings,
  CborRead,
  Certificate,
  Datum as FlatDatum,
  DecodeField,
  DecoderVersion,
  DetachedRecords,
  GovActionId,
  LedgerDecodeError,
  Proposal,
  RewardAccount,
  ScriptKind,
  TxIn,
  Unsigned,
  Value as FlatValue,
  Voter,
  VotingProcedure
}
import xyz.kd5ujc.scarab.ledger.era.{
  LedgerLevelTxInfo,
  AlonzoEraTxBodyToConway,
  AlonzoScriptRep,
  AsIx,
  AsIxItem,
  BabbageEraTxOut,
  BabbageTxOutRep,
  BabbageTxOuts,
  BinaryData,
  ConwayEraScript,
  ConwayEraTxBody,
  ConwayEraTxCert,
  Data,
  Datum,
  EraField,
  EraTxError,
  Indexable,
  Language,
  MultiAssetValue,
  NativeScriptRep,
  Network,
  OSet,
  Plutus,
  PlutusLanguage,
  PParams,
  ProposalProcedure,
  Sized,
  Timelock,
  TimelockScripts,
  TopOnly,
  TxBody,
  TxLevel,
  Val,
  ValidityInterval,
  VotingProcedures
}
import xyz.kd5ujc.scarab.ledger.era.babbage.{
  BabbagePlutusScript,
  BabbageScript,
  BabbageTimelock,
  BabbageTxOut
}
import xyz.kd5ujc.scarab.ledger.state.{
  AccountAddress,
  Coin,
  Conway,
  KeyHash,
  StakePool,
  Withdrawals
}
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.plutus.PlutusData
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}

// Conway's types: the opaque types are transparent in this file only, so only this file makes a
// value of Conway.

/** Conway's native script: `Timelock ConwayEra` (cardano-ledger f649f975
  * `eras/conway/impl/src/Cardano/Ledger/Conway/Scripts.hs:74`).
  */
opaque type ConwayTimelock = Timelock

object ConwayTimelock:
  /** Conway's native-script decoder: the time-lock language. */
  def decode(n: Node)(using ByteStrings): Either[EraTxError, ConwayTimelock] =
    read(n).leftMap(EraTxError.Decode(_))

  private[conway] def read(n: Node)(using ByteStrings): Either[LedgerDecodeError, ConwayTimelock] =
    NativeScriptRep
      .decode[xyz.kd5ujc.scarab.ledger.era.NativeForm.Timelock](n)
      .leftMap(LedgerDecodeError.NativeScript(_))

  /** A Babbage script carried into Conway with its bytes (`translateTimelock`,
    * `eras/allegra/impl/src/Cardano/Ledger/Allegra/Scripts.hs:202-217`, cardano-ledger f649f975).
    */
  def translate(t: BabbageTimelock): ConwayTimelock = t.rep

  extension (t: ConwayTimelock)
    /** The shared representation, read only. */
    def rep: Timelock = t

  given CanEqual[ConwayTimelock, ConwayTimelock] = CanEqual.derived

/** Conway's Plutus script: the languages Conway has, V1 to V3 (`data PlutusScript ConwayEra`,
  * `Conway/Scripts.hs:90-93`, cardano-ledger f649f975).
  */
enum ConwayPlutusScript:
  case V1(plutus: Plutus[Language.PlutusV1])
  case V2(plutus: Plutus[Language.PlutusV2])
  case V3(plutus: Plutus[Language.PlutusV3])

  def kind: ScriptKind = this match
    case V1(_) => ScriptKind.PlutusV1
    case V2(_) => ScriptKind.PlutusV2
    case V3(_) => ScriptKind.PlutusV3

  def binary: Bytes = this match
    case V1(p) => p.binary
    case V2(p) => p.binary
    case V3(p) => p.binary

object ConwayPlutusScript:
  given CanEqual[ConwayPlutusScript, ConwayPlutusScript] = CanEqual.derived

  /** The Conway script of `kind`, if Conway has the language. */
  private[conway] def of(kind: ScriptKind): Option[Bytes => ConwayPlutusScript] = kind match
    case ScriptKind.PlutusV1                     => Some(b => V1(Plutus(b)))
    case ScriptKind.PlutusV2                     => Some(b => V2(Plutus(b)))
    case ScriptKind.PlutusV3                     => Some(b => V3(Plutus(b)))
    case ScriptKind.Native | ScriptKind.PlutusV4 => None

  /** A Babbage Plutus script carried into Conway (`upgradeScript`, `Conway/Scripts.hs:76-79`,
    * cardano-ledger f649f975).
    */
  def translate(p: BabbagePlutusScript): ConwayPlutusScript = p match
    case BabbagePlutusScript.V1(s) => V1(s)
    case BabbagePlutusScript.V2(s) => V2(s)

/** Conway's script: `AlonzoScript ConwayEra` (`Conway/Scripts.hs:73`, cardano-ledger f649f975), a
  * Conway native script or a Conway Plutus script.
  */
opaque type ConwayScript = AlonzoScriptRep[ConwayTimelock, ConwayPlutusScript]

object ConwayScript:
  /** A native script as a Conway script (`fromNativeScript`). */
  private[conway] def native(t: ConwayTimelock): ConwayScript = AlonzoScriptRep.NativeScript(t)

  /** A Plutus script as a Conway script (`fromPlutusScript`). */
  private[conway] def plutus(p: ConwayPlutusScript): ConwayScript = AlonzoScriptRep.PlutusScript(p)

  /** Conway's script decoder (`decodeAlonzoScript` at `eraMaxLanguage = PlutusV3`,
    * `Conway/Scripts.hs:98`, cardano-ledger f649f975).
    */
  def decode(n: Node)(using ByteStrings): Either[EraTxError, ConwayScript] =
    read(n).leftMap(EraTxError.Decode(_))

  private[conway] def read(n: Node)(using ByteStrings): Either[LedgerDecodeError, ConwayScript] =
    AlonzoScriptRep.decode(n, ScriptKind.PlutusV3)(ConwayTimelock.read(_), ConwayPlutusScript.of)

  /** A Babbage script carried into Conway (`upgradeScript`, `Conway/Scripts.hs:76-79`,
    * cardano-ledger f649f975).
    */
  def translate(s: BabbageScript): ConwayScript = s.rep match
    case AlonzoScriptRep.NativeScript(t) =>
      AlonzoScriptRep.NativeScript(ConwayTimelock.translate(t))
    case AlonzoScriptRep.PlutusScript(p) =>
      AlonzoScriptRep.PlutusScript(ConwayPlutusScript.translate(p))

  extension (s: ConwayScript)
    /** The shared representation, read only: the translation into Dijkstra reads it. */
    def rep: AlonzoScriptRep[ConwayTimelock, ConwayPlutusScript] = s

  given CanEqual[ConwayScript, ConwayScript] = CanEqual.derived

  val instance: ConwayEraScript[Conway] = new TimelockScripts[Conway] with ConwayEraScript[Conway]:
    override protected def timelock(native: ConwayTimelock): Timelock = native
    override protected def ofTimelock(t: Timelock): ConwayTimelock = t
    override def scriptPrefixTag(script: ConwayScript): Byte = script match
      case AlonzoScriptRep.NativeScript(_) => 0
      case AlonzoScriptRep.PlutusScript(p) => p.kind.tag.toByte
    override def originalBytes(script: ConwayScript): Bytes = script match
      case AlonzoScriptRep.NativeScript(t) => t.raw
      case AlonzoScriptRep.PlutusScript(p) => p.binary
    override def getNativeScript(script: ConwayScript): Option[ConwayTimelock] = script match
      case AlonzoScriptRep.NativeScript(t) => Some(t)
      case AlonzoScriptRep.PlutusScript(_) => None
    override def fromNativeScript(native: ConwayTimelock): ConwayScript =
      AlonzoScriptRep.NativeScript(native)
    override def eraMaxLanguage: xyz.kd5ujc.scarab.plutus.PlutusLanguage =
      xyz.kd5ujc.scarab.plutus.PlutusLanguage.V3
    override def toPlutusScript(script: ConwayScript): Option[ConwayPlutusScript] = script match
      case AlonzoScriptRep.PlutusScript(p) => Some(p)
      case AlonzoScriptRep.NativeScript(_) => None
    override def fromPlutusScript(plutus: ConwayPlutusScript): ConwayScript =
      AlonzoScriptRep.PlutusScript(plutus)
    // `withPlutusScript`, `Conway/Scripts.hs:107-109`, cardano-ledger f649f975
    override def withPlutusScript[A](plutus: ConwayPlutusScript)(
        f: [L <: Language] => (Plutus[L], PlutusLanguage[L]) => A
    ): A = plutus match
      case ConwayPlutusScript.V1(p) => f(p, PlutusLanguage.v1)
      case ConwayPlutusScript.V2(p) => f(p, PlutusLanguage.v2)
      case ConwayPlutusScript.V3(p) => f(p, PlutusLanguage.v3)

    // `hoistPlutusPurpose`, `Conway/Scripts.hs:111-117`, cardano-ledger f649f975
    override def hoistPlutusPurpose[G[_, _], F[_, _]](f: [ix, it] => G[ix, it] => F[ix, it])(
        purpose: ConwayPlutusPurpose[G]
    ): ConwayPlutusPurpose[F] = ConwayPlutusPurpose.hoistPlutusPurpose[G, F](f)(purpose)

    // `mkSpendingPurpose` to `toWithdrawingPurpose`, `Conway/Scripts.hs:119-137`, cardano-ledger
    // f649f975
    override def mkSpendingPurpose[F[_, _]](
        txIn: F[Unsigned[Unsigned.Width.W32], TxIn]
    ): ConwayPlutusPurpose[F] = ConwayPlutusPurpose.ConwaySpending(txIn)
    override def toSpendingPurpose[F[_, _]](
        purpose: ConwayPlutusPurpose[F]
    ): Option[F[Unsigned[Unsigned.Width.W32], TxIn]] = purpose match
      case ConwayPlutusPurpose.ConwaySpending(i) => Some(i)
      case ConwayPlutusPurpose.ConwayMinting(_) | ConwayPlutusPurpose.ConwayCertifying(_) |
          ConwayPlutusPurpose.ConwayWithdrawing(_) | ConwayPlutusPurpose.ConwayVoting(_) |
          ConwayPlutusPurpose.ConwayProposing(_) =>
        None
    override def mkMintingPurpose[F[_, _]](
        policy: F[Unsigned[Unsigned.Width.W32], Bytes]
    ): ConwayPlutusPurpose[F] = ConwayPlutusPurpose.ConwayMinting(policy)
    override def toMintingPurpose[F[_, _]](
        purpose: ConwayPlutusPurpose[F]
    ): Option[F[Unsigned[Unsigned.Width.W32], Bytes]] = purpose match
      case ConwayPlutusPurpose.ConwayMinting(i) => Some(i)
      case ConwayPlutusPurpose.ConwaySpending(_) | ConwayPlutusPurpose.ConwayCertifying(_) |
          ConwayPlutusPurpose.ConwayWithdrawing(_) | ConwayPlutusPurpose.ConwayVoting(_) |
          ConwayPlutusPurpose.ConwayProposing(_) =>
        None
    override def mkCertifyingPurpose[F[_, _]](
        txCert: F[Unsigned[Unsigned.Width.W32], ConwayTxCert]
    ): ConwayPlutusPurpose[F] = ConwayPlutusPurpose.ConwayCertifying(txCert)
    override def toCertifyingPurpose[F[_, _]](
        purpose: ConwayPlutusPurpose[F]
    ): Option[F[Unsigned[Unsigned.Width.W32], ConwayTxCert]] = purpose match
      case ConwayPlutusPurpose.ConwayCertifying(i) => Some(i)
      case ConwayPlutusPurpose.ConwaySpending(_) | ConwayPlutusPurpose.ConwayMinting(_) |
          ConwayPlutusPurpose.ConwayWithdrawing(_) | ConwayPlutusPurpose.ConwayVoting(_) |
          ConwayPlutusPurpose.ConwayProposing(_) =>
        None
    override def mkWithdrawingPurpose[F[_, _]](
        account: F[Unsigned[Unsigned.Width.W32], AccountAddress]
    ): ConwayPlutusPurpose[F] = ConwayPlutusPurpose.ConwayWithdrawing(account)
    override def toWithdrawingPurpose[F[_, _]](
        purpose: ConwayPlutusPurpose[F]
    ): Option[F[Unsigned[Unsigned.Width.W32], AccountAddress]] = purpose match
      case ConwayPlutusPurpose.ConwayWithdrawing(i) => Some(i)
      case ConwayPlutusPurpose.ConwaySpending(_) | ConwayPlutusPurpose.ConwayMinting(_) |
          ConwayPlutusPurpose.ConwayCertifying(_) | ConwayPlutusPurpose.ConwayVoting(_) |
          ConwayPlutusPurpose.ConwayProposing(_) =>
        None

    // `ConwayEraScript ConwayEra`, `Conway/Scripts.hs:145-154`, cardano-ledger f649f975
    override def mkVotingPurpose[F[_, _]](
        voter: F[Unsigned[Unsigned.Width.W32], Voter]
    ): ConwayPlutusPurpose[F] = ConwayPlutusPurpose.ConwayVoting(voter)
    override def toVotingPurpose[F[_, _]](
        purpose: ConwayPlutusPurpose[F]
    ): Option[F[Unsigned[Unsigned.Width.W32], Voter]] = purpose match
      case ConwayPlutusPurpose.ConwayVoting(voter) => Some(voter)
      case ConwayPlutusPurpose.ConwaySpending(_) | ConwayPlutusPurpose.ConwayMinting(_) |
          ConwayPlutusPurpose.ConwayCertifying(_) | ConwayPlutusPurpose.ConwayWithdrawing(_) |
          ConwayPlutusPurpose.ConwayProposing(_) =>
        None
    override def mkProposingPurpose[F[_, _]](
        proposal: F[Unsigned[Unsigned.Width.W32], ProposalProcedure[Conway]]
    ): ConwayPlutusPurpose[F] = ConwayPlutusPurpose.ConwayProposing(proposal)
    override def toProposingPurpose[F[_, _]](
        purpose: ConwayPlutusPurpose[F]
    ): Option[F[Unsigned[Unsigned.Width.W32], ProposalProcedure[Conway]]] = purpose match
      case ConwayPlutusPurpose.ConwayProposing(proposal) => Some(proposal)
      case ConwayPlutusPurpose.ConwaySpending(_) | ConwayPlutusPurpose.ConwayMinting(_) |
          ConwayPlutusPurpose.ConwayCertifying(_) | ConwayPlutusPurpose.ConwayWithdrawing(_) |
          ConwayPlutusPurpose.ConwayVoting(_) =>
        None

/** A Conway output: `BabbageTxOut ConwayEra`, the representation Babbage's has with Conway's script
  * (`eras/conway/impl/src/Cardano/Ledger/Conway/TxOut.hs`), a type of its own.
  */
opaque type ConwayTxOut = BabbageTxOutRep[ConwayScript]

object ConwayTxOut:
  /** The stored output read as a Conway output (`ejectHardForkTxOut`, ouroboros-consensus 82ecba329
    * `Cardano/Ledger.hs:137-162`): its inline datum taken unread, as the reference's `MemPack` of
    * `BinaryData` takes it, and its reference script read as a Conway script.
    */
  private[ledger] def fromStored(
      out: xyz.kd5ujc.scarab.ledger.TxOut
  ): Either[EraTxError, ConwayTxOut] =
    inverse(out)(BabbageTxOutRep.fromStored(out)(n => ConwayScript.read(n)))

  /** An output Conway's decoder read from a block or a transaction as a Conway output: its inline
    * datum read as data, as the reference's `DecCBOR` of `BinaryData` reads it (`makeBinaryData`),
    * and its reference script read as a Conway script.
    */
  private[conway] def fromDecoded(
      out: xyz.kd5ujc.scarab.ledger.TxOut
  ): Either[EraTxError, ConwayTxOut] =
    inverse(out)(BabbageTxOutRep.fromDecoded(out)(n => ConwayScript.read(n)))

  // the output written back is the one read, so `toStored` is its inverse
  private def inverse(out: xyz.kd5ujc.scarab.ledger.TxOut)(
      read: Either[LedgerDecodeError, ConwayTxOut]
  ): Either[EraTxError, ConwayTxOut] =
    read
      .leftMap(EraTxError.Decode(_))
      .flatMap(o => Either.cond(toStored(o) == out, o, EraTxError.StoredOutputDiffers))

  /** The output in the form the store keeps, the inverse of [[fromStored]] (which refuses a stored
    * output this does not give back): its bytes, fields and the value's size as they arrived, and
    * its reference script as the flat output keeps one, by its hash, with the tree of a native
    * script and without a Plutus script's bytes (which the store reads again from the output's
    * bytes).
    */
  private[ledger] def toStored(out: ConwayTxOut): xyz.kd5ujc.scarab.ledger.TxOut =
    xyz.kd5ujc.scarab.ledger.TxOut(
      out.raw,
      out.address,
      out.value.stored,
      Datum.toStored(out.datum),
      out.scriptRef.map(storedScript),
      out.valueSize
    )

  private def storedScript(s: ConwayScript): xyz.kd5ujc.scarab.ledger.Script =
    val hash = ConwayScript.instance.hashScript(s)
    s match
      case AlonzoScriptRep.NativeScript(t) =>
        xyz.kd5ujc.scarab.ledger.Script(ScriptKind.Native, hash, Some(t.script))
      case AlonzoScriptRep.PlutusScript(p) => xyz.kd5ujc.scarab.ledger.Script(p.kind, hash, None)

  /** A Babbage output carried into Conway (`upgradeBabbageTxOut`, `Conway/TxOut.hs:68-81`,
    * cardano-ledger f649f975): the same address, value, datum and bytes, its reference script
    * carried into Conway.
    */
  def translate(out: BabbageTxOut): ConwayTxOut =
    val r = out.rep
    BabbageTxOutRep(
      r.raw,
      r.address,
      r.value,
      r.valueSize,
      r.datum,
      r.scriptRef.map(ConwayScript.translate)
    )

  extension (out: ConwayTxOut)
    /** The shared representation, read only. */
    def rep: BabbageTxOutRep[ConwayScript] = out

  given CanEqual[ConwayTxOut, ConwayTxOut] = CanEqual.derived

  /** An output a body's one pass read ([[read]]), with the output the flat decoder reads from the
    * same item, which the body's second phase holds it to ([[checked]]).
    */
  private[conway] final case class Read(out: ConwayTxOut, flat: xyz.kd5ujc.scarab.ledger.TxOut)

  /** An output of a Conway body read from its item in one pass: `decodeBabbageTxOut` at decoder
    * version 9 (`DecCBOR (BabbageTxOut era)`,
    * `eras/babbage/impl/src/Cardano/Ledger/Babbage/TxOut.hs:549-553`, cardano-ledger f649f975),
    * with the forms, keys and refusals, and their order, of the flat output's decoder at Conway
    * ([[xyz.kd5ujc.scarab.ledger.TxOut.decode]] of
    * [[xyz.kd5ujc.scarab.ledger.TxOut.Forms.PairDatumHashOrMap]]). Its inline datum is read as data
    * once ([[BinaryData.makeBinaryData]], the flat decoder's `Data.readBytes` with its error), and
    * its reference script once, by Conway's script reader, whose refusals at Conway are the flat
    * script reader's (`decodeAlonzoScript`, `Alonzo/Scripts.hs:705-720`).
    */
  private[conway] def read(n: Node, mode: Address.Mode, values: FlatValue.Decoding)(using
      ByteStrings
  ): Either[LedgerDecodeError, Read] =
    val forms = xyz.kd5ujc.scarab.ledger.TxOut.Forms.PairDatumHashOrMap
    def address(x: Node) =
      CborRead.bytes(x, DecodeField.OutputAddress).flatMap(Address.decode(_, mode))
    def value(x: Node) = FlatValue.decode(x, DecodeField.OutputValue, values, forms.records)
    def made(
        address: Address,
        value: FlatValue,
        valueItem: Node,
        datum: (FlatDatum, Datum),
        script: Option[(xyz.kd5ujc.scarab.ledger.Script, ConwayScript)]
    ) =
      val size = valueItem.original.size
      Read(
        BabbageTxOutRep(
          n.original,
          address,
          MultiAssetValue.of(value),
          size,
          datum._2,
          script.map(_._2)
        ),
        xyz.kd5ujc.scarab.ledger.TxOut(n.original, address, value, datum._1, script.map(_._1), size)
      )
    n.value match
      case CborValue.Arr(xs) if forms.arities.contains(xs.size) =>
        for
          a <- address(xs(0))
          v <- value(xs(1))
          d <-
            if xs.sizeIs == 3 then
              CborRead
                .bytesN(xs(2), 32, DecodeField.DatumHash)
                .map(h => (FlatDatum.Hash(h), Datum.DatumHash(h)))
            else Right((FlatDatum.NoDatum, Datum.NoDatum))
        yield made(a, v, xs(1), d, None)
      // the map form, unless its length is written in eight bytes (`TypeMapLen64`, #701)
      case CborValue.Map(_) if !CborRead.mapLength64(n) =>
        for
          f <- CborRead.fields(n, DecodeField.Output, forms.keys)
          a <- f
            .get(0)
            .toRight(LedgerDecodeError.MissingField(DecodeField.Output, DecodeField.Address))
            .flatMap(address)
          vn <- f
            .get(1)
            .toRight(LedgerDecodeError.MissingField(DecodeField.Output, DecodeField.Value))
          v <- value(vn)
          _ <- Either.cond(
            f.keySet.subsetOf(outputKeys),
            (),
            LedgerDecodeError.UnknownFields(DecodeField.Output, f.keySet -- outputKeys)
          )
          d <- f.get(2).traverse(datumOption)
          s <- f.get(3).traverse(referenceScript)
        yield made(a, v, vn, d.getOrElse((FlatDatum.NoDatum, Datum.NoDatum)), s)
      case _ => Left(LedgerDecodeError.OutputForm(forms, n.value))

  private val outputKeys: Set[Int] = Set(0, 1, 2, 3)

  // `datum_option = [0, hash] / [1, #6.24(bytes .cbor plutus_data)]`, the inline datum read as
  // data once
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

  // `#6.24(bytes .cbor script)`, read once as a Conway script; the flat output keeps it by its
  // kind, hash and native tree, as the flat reader's `Script.decodeWrapped` gives it
  private def referenceScript(n: Node)(using
      ByteStrings
  ): Either[LedgerDecodeError, (xyz.kd5ujc.scarab.ledger.Script, ConwayScript)] =
    n.value match
      case CborValue.Tag(t, inner) if t == 24 =>
        CborRead
          .bytes(inner, DecodeField.ReferenceScript)
          .flatMap(CborRead.decode(_))
          .flatMap(ConwayScript.read)
          .map(s => (flatScript(s), s))
      case _ => Left(LedgerDecodeError.ExpectedTag(DecodeField.ReferenceScript, 24, n.value))

  private def flatScript(s: ConwayScript): xyz.kd5ujc.scarab.ledger.Script = s match
    case AlonzoScriptRep.NativeScript(t) =>
      xyz.kd5ujc.scarab.ledger.Script.nativeOf(t.raw, t.script).withoutBytes
    case AlonzoScriptRep.PlutusScript(p) =>
      xyz.kd5ujc.scarab.ledger.Script.plutus(p.kind, p.binary).withoutBytes

  /** The second phase of an output read in one pass: the output, with the size of the encoding it
    * was read from (`Sized`), when the form the store keeps of it is the flat output read from the
    * same item, as [[fromDecoded]] holds an output to it ([[EraTxError.StoredOutputDiffers]]).
    */
  private[conway] def checked(r: Read): Either[EraTxError, Sized[ConwayTxOut]] =
    Either.cond(
      toStored(r.out) == r.flat,
      Sized(r.out, r.flat.ledgerSize.toLong),
      EraTxError.StoredOutputDiffers
    )

  val instance: BabbageEraTxOut[Conway] = new BabbageEraTxOut[Conway]:
    override def scripts: ConwayEraScript[Conway] = ConwayScript.instance
    override def valueOps: Val[MultiAssetValue] = summon[Val[MultiAssetValue]]
    override def address(out: ConwayTxOut): Address = out.address
    override def value(out: ConwayTxOut): MultiAssetValue = out.value
    override def raw(out: ConwayTxOut): Bytes = out.raw
    override def dataHash(out: ConwayTxOut): Option[Bytes] = BabbageTxOuts.dataHash(out.datum)
    override def datum(out: ConwayTxOut): Datum = out.datum
    override def data(out: ConwayTxOut): Either[PlutusData.DecodeError, Option[Data]] =
      BabbageTxOuts.data(out.datum)
    override def referenceScript(out: ConwayTxOut): Option[ConwayScript] = out.scriptRef

/** A Conway transaction body: `TxBody TopTx ConwayEra` (`data ConwayTxBodyRaw`,
  * `eras/conway/impl/src/Cardano/Ledger/Conway/TxBody.hs:122-144`, cardano-ledger f649f975), with
  * the bytes it arrived as, whose hash is the transaction's id. Its outputs and collateral return
  * are Conway outputs and its certificates Conway certificates. Conway's bodies are of the top
  * level only. Made only by [[ConwayTxBody.decode]].
  *
  * @param spendInputs
  *   the inputs (`ctbrSpendInputs`), in the ledger's `TxIn` order
  * @param outputs
  *   the outputs, each with the size of the encoding it was read from (`Sized`)
  * @param validity
  *   the slots the transaction is valid in (`ctbrVldt`)
  */
final class ConwayTxBody private (
    val raw: Bytes,
    val spendInputs: SortedSet[TxIn],
    val collateralInputs: SortedSet[TxIn],
    val referenceInputs: SortedSet[TxIn],
    val outputs: Vector[Sized[ConwayTxOut]],
    val collateralReturn: Option[Sized[ConwayTxOut]],
    val totalCollateral: Option[Coin],
    val certs: OSet[ConwayTxCert],
    val withdrawals: Withdrawals,
    val fee: Coin,
    val validity: ValidityInterval,
    val reqSignerHashes: SortedSet[Bytes],
    val mint: Map[Bytes, Map[Bytes, BigInt]],
    val scriptIntegrityHash: Option[Bytes],
    val auxDataHash: Option[Bytes],
    val networkId: Option[Network],
    val votingProcedures: VotingProcedures[Conway],
    val proposalProcedures: OSet[ProposalProcedure[Conway]],
    val currentTreasuryValue: Option[Coin],
    val treasuryDonation: Coin
):
  // Not a case class: its derived `Mirror` would make one from any fields, whatever its bytes.
  private def fields: Any = (
    raw,
    spendInputs,
    collateralInputs,
    referenceInputs,
    outputs,
    collateralReturn,
    totalCollateral,
    certs,
    withdrawals,
    fee,
    validity,
    reqSignerHashes,
    mint,
    scriptIntegrityHash,
    auxDataHash,
    networkId,
    votingProcedures,
    proposalProcedures,
    currentTreasuryValue,
    treasuryDonation
  )
  override def equals(that: Any): Boolean = that match
    case o: ConwayTxBody => fields == o.fields
    case _               => false
  override def hashCode(): Int = fields.hashCode
  override def toString: String = s"ConwayTxBody($fields)"
  lazy val id: Bytes = xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(raw)

object ConwayTxBody:
  given CanEqual[ConwayTxBody, ConwayTxBody] = CanEqual.derived

  private val version: DecoderVersion = DecoderVersion.of(Era.Conway)

  /** A Conway body from its bytes, read in one pass (#85): `DecCBOR (ConwayTxBodyRaw l ConwayEra)`
    * (`SparseKeyed "TxBodyRaw"` of `bodyFields` and `requiredFields`,
    * `eras/conway/impl/src/Cardano/Ledger/Conway/TxBody.hs:180-265`, cardano-ledger f649f975) at
    * decoder version 9, the bytes parsed once and each field read once into Conway's types; an
    * input that holds an empty indefinite list and that the plain parse refuses is parsed again
    * with the detached records a walk finds, as the flat decoder parses it
    * ([[DetachedRecords.read]]). A pool's parameters are read in `layout`, the layout of the build
    * that reads it ([[ConwayTxCert.fromFlat]]).
    *
    * It refuses what the two passes it replaced refuse ([[decodeByFlat]]: the flat decoder at
    * Conway, then the projection of what it read), with the same first refusal: the first phase
    * ([[read]]) reads the keys in the flat decoder's order with its leaf readers and refusals, and
    * the second ([[project]]) makes the era's values in the projection's order, so a refusal only
    * the projection makes still comes after every refusal of the flat decoder. That order is not
    * always the reference's, whose `SparseKeyed` reads the keys as written and refuses an unknown
    * key at its turn; it is kept here, and left to the decoder's order item of #85
    * (https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7162357644).
    */
  def decode(raw: Bytes, layout: StateLayout): Either[EraTxError, ConwayTxBody] =
    DetachedRecords
      .read(raw, Cbor.Limits.Unbounded, DetachedRecords.Shapes.body(Era.Conway, version))(
        LedgerDecodeError.Syntax(_)
      )(read(raw, _))
      .leftMap(EraTxError.Decode(_))
      .flatMap(project(_, layout))

  /** [[decode]] of a body's item, parsed already with no detached record in it
    * ([[ConwayTx.fromEnvelope]]).
    */
  private[conway] def fromItem(n: Node, layout: StateLayout): Either[EraTxError, ConwayTxBody] =
    read(n.original, n).leftMap(EraTxError.Decode(_)).flatMap(project(_, layout))

  /** What the first phase read: each field at the flat decoder's types where the projection makes
    * the era's from them, the outputs read already as Conway's ([[ConwayTxOut.read]]).
    */
  private final case class Read(
      raw: Bytes,
      inputs: Vector[TxIn],
      outputs: Vector[ConwayTxOut.Read],
      fee: BigInt,
      ttl: Option[BigInt],
      start: Option[BigInt],
      certs: Vector[Certificate],
      withdrawals: TreeMap[RewardAccount, Coin],
      auxHash: Option[Bytes],
      mint: Map[Bytes, Map[Bytes, BigInt]],
      scriptDataHash: Option[Bytes],
      collateral: Vector[TxIn],
      signers: Vector[Bytes],
      network: Option[BigInt],
      collateralReturn: Option[ConwayTxOut.Read],
      totalCollateral: Option[BigInt],
      references: Vector[TxIn],
      votes: Vector[(Voter, Vector[(GovActionId, VotingProcedure)])],
      proposals: Vector[Proposal],
      treasury: Option[BigInt],
      donation: Option[BigInt]
  )

  /** The first phase: the flat decoder's reading of a body at Conway
    * ([[xyz.kd5ujc.scarab.ledger.TxBody.decode]] at decoder version 9), its keys in its order, each
    * with its leaf reader and refusal: the keys Conway knows (key 6 among the unknown ones), then
    * 0, 1, 2, 3, 8, 4, 5, 7, 9 (then a mint of no policy), 11 and 13 to 22 (then a donation of
    * zero). A present optional collection that is empty is refused (`fieldGuarded`), and the sets,
    * ordered sets and maps refuse a duplicate.
    */
  private def read(raw: Bytes, root: Node): Either[LedgerDecodeError, Read] =
    val mode = Address.Mode.at(version)
    val values = FlatValue.Decoding.of(Era.Conway, version)
    given ByteStrings = ByteStrings.of(version)
    val known = xyz.kd5ujc.scarab.ledger.TxBody.known(Era.Conway)
    def required(f: Map[Int, Node], key: Int, site: DecodeField) =
      f.get(key).toRight(LedgerDecodeError.MissingField(DecodeField.TransactionBody, site))
    // `fieldGuarded`: a key that is present with an empty value is refused
    def present[A](what: DecodeField)(
        r: Either[LedgerDecodeError, Option[Vector[A]]]
    ): Either[LedgerDecodeError, Vector[A]] =
      r.flatMap(_.traverse(CborRead.nonEmpty(_, what, true))).map(_.orEmpty)
    def txIns(what: DecodeField)(n: Node) = CborRead.set(n, what, version)(TxIn.decode)
    def output(n: Node) = ConwayTxOut.read(n, mode, values)
    for
      f <- CborRead.fields(root, DecodeField.TransactionBody)
      _ <- Either.cond(
        f.keySet.subsetOf(known),
        (),
        LedgerDecodeError.UnknownFields(DecodeField.TransactionBody, f.keySet -- known)
      )
      inputs <- required(f, 0, DecodeField.Inputs).flatMap(txIns(DecodeField.Inputs))
      outputs <- required(f, 1, DecodeField.Outputs)
        .flatMap(CborRead.array(_, DecodeField.Outputs))
        .flatMap(_.traverse(output))
      fee <- required(f, 2, DecodeField.Fee).flatMap(CborRead.uint(_, DecodeField.Fee))
      ttl <- f.get(3).traverse(CborRead.uint(_, DecodeField.Ttl))
      start <- f.get(8).traverse(CborRead.uint(_, DecodeField.ValidityStart))
      certs <- present(DecodeField.Certificates)(
        f.get(4)
          .traverse(
            CborRead.oset(_, DecodeField.Certificates, version)(
              Certificate.decode(_, Era.Conway, version)
            )(Certificate.identity)
          )
      )
      withdrawals <- present(DecodeField.Withdrawals)(
        f.get(5)
          .traverse(
            CborRead
              .map(_, DecodeField.Withdrawals)
              .flatMap(_.traverse { case (k, v) =>
                (
                  CborRead.bytes(k, DecodeField.WithdrawalAccount).flatMap(RewardAccount.decode),
                  CborRead.uint(v, DecodeField.Withdrawal).map(Coin(_))
                ).tupled
              })
              // every pair is read before a repeated account is refused (`decodeMapByKey`)
              .flatMap(ws => CborRead.unique(ws.map(_._1), DecodeField.Withdrawals).as(ws))
          )
      ).map(TreeMap.from(_))
      auxHash <- f.get(7).traverse(CborRead.bytesN(_, 32, DecodeField.AuxiliaryDataHash))
      mint <- f.get(9).traverse(FlatValue.multiasset(_, DecodeField.Mint, signed = true, values))
      _ <- Either.cond(!mint.exists(_.isEmpty), (), LedgerDecodeError.Empty(DecodeField.Mint))
      dataHash <- f.get(11).traverse(CborRead.bytesN(_, 32, DecodeField.ScriptDataHash))
      collateral <- present(DecodeField.Collateral)(
        f.get(13).traverse(txIns(DecodeField.Collateral))
      )
      signers <- present(DecodeField.RequiredSigners)(
        f.get(14)
          .traverse(
            CborRead.set(_, DecodeField.RequiredSigners, version)(
              CborRead.bytesN(_, 28, DecodeField.Signer)
            )
          )
      )
      network <- f.get(15).traverse(xyz.kd5ujc.scarab.ledger.TxBody.networkId)
      collateralReturn <- f.get(16).traverse(output)
      totalCollateral <- f.get(17).traverse(CborRead.uint(_, DecodeField.TotalCollateral))
      references <- present(DecodeField.ReferenceInputs)(
        f.get(18).traverse(txIns(DecodeField.ReferenceInputs))
      )
      votes <- present(DecodeField.VotingProcedures)(
        f.get(19)
          .traverse(
            xyz.kd5ujc.scarab.ledger.VotingProcedures
              .decode(_, version)
              .flatMap(xyz.kd5ujc.scarab.ledger.VotingProcedures.requireBodyDecodable)
              .leftMap(LedgerDecodeError.Voting(_))
          )
      )
      proposals <- present(DecodeField.ProposalProcedures)(
        f.get(20)
          .traverse(
            CborRead.oset(_, DecodeField.ProposalProcedures, version)(Proposal.decode(_, version))(
              Proposal.identity
            )
          )
      )
      treasury <- f.get(21).traverse(CborRead.uint(_, DecodeField.CurrentTreasuryValue))
      donation <- f.get(22).traverse(CborRead.uint(_, DecodeField.Donation))
      _ <- Either.cond(
        !donation.contains(BigInt(0)),
        (),
        LedgerDecodeError.Zero(DecodeField.Donation)
      )
    yield Read(
      raw,
      inputs,
      outputs,
      fee,
      ttl,
      start,
      certs,
      withdrawals,
      auxHash,
      mint.getOrElse(Map.empty),
      dataHash,
      collateral,
      signers,
      network,
      collateralReturn,
      totalCollateral,
      references,
      votes,
      proposals,
      treasury,
      donation
    )

  /** The second phase: the era's values made from what the first read, in the projection's order
    * ([[fromFlat]]): the outputs and the collateral return held to the flat outputs
    * ([[ConwayTxOut.checked]]), the certificates, the withdrawals' accounts, the proposals and the
    * network.
    */
  private def project(r: Read, layout: StateLayout): Either[EraTxError, ConwayTxBody] =
    for
      outputs <- r.outputs.traverse(ConwayTxOut.checked)
      collateralReturn <- r.collateralReturn.traverse(ConwayTxOut.checked)
      certs <- r.certs.traverse(ConwayTxCert.fromFlat(_, layout))
      withdrawals <- Convert.withdrawals(r.withdrawals).leftMap(EraTxError.Vocab(_))
      proposals <- r.proposals.traverse(ConwayProcedures.proposal)
      network <- r.network.traverse(v =>
        // a `Word8` of 0 or 1, as the first phase read it
        Network
          .of(v)
          .toRight(
            EraTxError.Decode(
              LedgerDecodeError.NumberOutOfRange(DecodeField.NetworkId, v, 0, 1)
            )
          )
      )
    // the first phase refused a repeated certificate or proposal (`decodeOSet`) and a repeated voter
    // or action (`decodeMapByKey`), so the ordered sets and maps below hold what it read
    yield ConwayTxBody(
      r.raw,
      SortedSet.from(r.inputs),
      SortedSet.from(r.collateral),
      SortedSet.from(r.references),
      outputs,
      collateralReturn,
      r.totalCollateral.map(Coin(_)),
      OSet.decoded(certs),
      withdrawals,
      Coin(r.fee),
      ValidityInterval(r.start, r.ttl),
      SortedSet.from(r.signers),
      r.mint,
      r.scriptDataHash,
      r.auxHash,
      network,
      ConwayProcedures.votes(r.votes),
      OSet.decoded(proposals),
      r.treasury.map(Coin(_)),
      Coin(r.donation.getOrElse(BigInt(0)))
    )

  /** The two passes [[decode]] replaced (#85): Conway's decoder (the flat decoder at Conway), its
    * outputs read again as Conway outputs and its certificates as Conway certificates, a pool's
    * parameters in `layout` ([[ConwayTxCert.fromFlat]]), and its withdrawals as the reference's
    * `Withdrawals` (`ctbrWithdrawals`, `Conway/TxBody.hs:131`, cardano-ledger f649f975), each
    * account the `AccountAddress` the decoder read ([[Convert.withdrawals]]). A protocol-parameter
    * update, which Conway's decoder does not read, is refused. Kept for the differential and the
    * bench until the flat decoder goes (stage 6a of `docs/design/era-typed-transactions.md`).
    */
  def decodeByFlat(raw: Bytes, layout: StateLayout): Either[EraTxError, ConwayTxBody] =
    xyz.kd5ujc.scarab.ledger.TxBody
      .decode(raw, Era.Conway)
      .leftMap(EraTxError.Decode(_))
      .flatMap(fromFlat(_, layout))

  private def fromFlat(
      b: xyz.kd5ujc.scarab.ledger.TxBody,
      layout: StateLayout
  ): Either[EraTxError, ConwayTxBody] =
    // an output's size is the length of the encoding it was read from (`Sized`, as the flat
    // output's `ledgerSize`)
    def sized(out: xyz.kd5ujc.scarab.ledger.TxOut) =
      ConwayTxOut.fromDecoded(out).map(Sized(_, out.ledgerSize.toLong))
    for
      _ <- Either.cond(b.update.isEmpty, (), EraTxError.NotInEra(EraField.Update))
      outputs <- b.outputs.traverse(sized)
      collateralReturn <- b.collateralReturn.traverse(sized)
      certs <- b.certificates.traverse(ConwayTxCert.fromFlat(_, layout))
      withdrawals <- Convert.withdrawals(b.withdrawals).leftMap(EraTxError.Vocab(_))
      proposals <- b.proposals.traverse(ConwayProcedures.proposal)
      network <- b.networkId.traverse(v =>
        Network
          .of(v)
          .toRight(
            EraTxError.Decode(
              LedgerDecodeError.NumberOutOfRange(DecodeField.NetworkId, v, 0, 1)
            )
          )
      )
    // the decoder refused a repeated certificate or proposal (`decodeOSet`) and a repeated voter
    // or action (`decodeMapByKey`), so the ordered sets and maps below hold what it read
    yield ConwayTxBody(
      b.raw,
      SortedSet.from(b.inputs),
      SortedSet.from(b.collateral),
      SortedSet.from(b.referenceInputs),
      outputs,
      collateralReturn,
      b.totalCollateral.map(Coin(_)),
      OSet.decoded(certs),
      withdrawals,
      Coin(b.fee),
      ValidityInterval(b.validityStart, b.ttl),
      SortedSet.from(b.requiredSigners),
      b.mint,
      b.scriptDataHash,
      b.auxDataHash,
      network,
      ConwayProcedures.votes(b.votes),
      OSet.decoded(proposals),
      b.currentTreasury.map(Coin(_)),
      Coin(b.donation)
    )

  /** `conwayRedeemerPointerInverse` (`Conway/TxBody.hs:662-679`, cardano-ledger f649f975): the item
    * at the pointer's index among the policies minted (in their order), the inputs, the
    * withdrawals' accounts (in theirs), the certificates, the voters (in theirs) or the proposals.
    */
  def conwayRedeemerPointerInverse(
      txBody: ConwayTxBody,
      ptr: ConwayPlutusPurpose[AsIx]
  ): Option[ConwayPlutusPurpose[AsIxItem]] =
    import ConwayPlutusPurpose.*
    ptr match
      case ConwayMinting(idx) =>
        Indexable
          .fromIndexSet(idx, SortedSet.from(txBody.mint.keySet))
          .map(ConwayMinting(_))
      case ConwaySpending(idx) =>
        Indexable.fromIndexSet(idx, txBody.spendInputs).map(ConwaySpending(_))
      case ConwayWithdrawing(idx) =>
        Indexable
          .fromIndexMap(idx, txBody.withdrawals.unWithdrawals)
          .map(ConwayWithdrawing(_))
      case ConwayCertifying(idx) =>
        Indexable.fromIndexOSet(idx, txBody.certs).map(ConwayCertifying(_))
      case ConwayVoting(idx) =>
        Indexable
          .fromIndexMap(idx, txBody.votingProcedures.unVotingProcedures)
          .map(ConwayVoting(_))
      case ConwayProposing(idx) =>
        Indexable.fromIndexOSet(idx, txBody.proposalProcedures).map(ConwayProposing(_))

  val instance: (ConwayEraTxBody[Conway] & AlonzoEraTxBodyToConway[Conway]) {
    type Level[L <: TxLevel] = TopOnly[L]
  } =
    new ConwayEraTxBody[Conway] with AlonzoEraTxBodyToConway[Conway]:
      type Level[L <: TxLevel] = TopOnly[L]
      override def top: TopOnly[TxLevel.Top] = TopOnly.Top
      override def levelOf[L <: TxLevel](level: LedgerLevelTxInfo[L, Conway]): TopOnly[L] =
        level match
          case LedgerLevelTxInfo.LedgerTopTxInfo(_) => TopOnly.Top
      override def outputsOf: BabbageEraTxOut[Conway] = ConwayTxOut.instance
      override def certsOf: ConwayEraTxCert[Conway] = ConwayTxCert.instance
      override def scriptsOf: ConwayEraScript[Conway] = ConwayScript.instance
      override def id[L <: TxLevel](body: TxBody[L, Conway])(using l: TopOnly[L]): Bytes =
        l match
          case TopOnly.Top => body.id
      override def inputs[L <: TxLevel](body: TxBody[L, Conway])(using
          l: TopOnly[L]
      ): SortedSet[TxIn] =
        l match
          case TopOnly.Top => body.spendInputs
      override def outputs[L <: TxLevel](body: TxBody[L, Conway])(using
          l: TopOnly[L]
      ): Vector[ConwayTxOut] =
        l match
          case TopOnly.Top => body.outputs.map(_.value)
      override def sizedOutputs[L <: TxLevel](body: TxBody[L, Conway])(using
          l: TopOnly[L]
      ): Vector[Sized[ConwayTxOut]] =
        l match
          case TopOnly.Top => body.outputs
      override def fee(body: ConwayTxBody): Coin = body.fee
      override def withdrawals[L <: TxLevel](body: TxBody[L, Conway])(using
          l: TopOnly[L]
      ): Withdrawals =
        l match
          case TopOnly.Top => body.withdrawals
      override def auxDataHash[L <: TxLevel](body: TxBody[L, Conway])(using
          l: TopOnly[L]
      ): Option[Bytes] =
        l match
          case TopOnly.Top => body.auxDataHash
      override def certs[L <: TxLevel](body: TxBody[L, Conway])(using
          l: TopOnly[L]
      ): Vector[ConwayTxCert] =
        l match
          case TopOnly.Top => body.certs.toVector
      override def vldt[L <: TxLevel](body: TxBody[L, Conway])(using
          l: TopOnly[L]
      ): ValidityInterval =
        l match
          case TopOnly.Top => body.validity
      override def mint[L <: TxLevel](body: TxBody[L, Conway])(using
          l: TopOnly[L]
      ): Map[Bytes, Map[Bytes, BigInt]] =
        l match
          case TopOnly.Top => body.mint
      override def collateralInputs(body: ConwayTxBody): SortedSet[TxIn] = body.collateralInputs
      override def reqSignerHashesField[L <: TxLevel](body: TxBody[L, Conway])(using
          l: TopOnly[L]
      ): SortedSet[Bytes] =
        l match
          case TopOnly.Top => body.reqSignerHashes
      override def scriptIntegrityHash[L <: TxLevel](body: TxBody[L, Conway])(using
          l: TopOnly[L]
      ): Option[Bytes] =
        l match
          case TopOnly.Top => body.scriptIntegrityHash
      override def networkId[L <: TxLevel](body: TxBody[L, Conway])(using
          l: TopOnly[L]
      ): Option[Network] =
        l match
          case TopOnly.Top => body.networkId
      // `redeemerPointerInverse = conwayRedeemerPointerInverse`, `Conway/TxBody.hs:425`,
      // cardano-ledger f649f975
      override def redeemerPointerInverse[L <: TxLevel](
          body: TxBody[L, Conway],
          ptr: ConwayPlutusPurpose[AsIx]
      )(using l: TopOnly[L]): Option[ConwayPlutusPurpose[AsIxItem]] =
        l match
          case TopOnly.Top => ConwayTxBody.conwayRedeemerPointerInverse(body, ptr)
      override def referenceInputs[L <: TxLevel](body: TxBody[L, Conway])(using
          l: TopOnly[L]
      ): SortedSet[TxIn] =
        l match
          case TopOnly.Top => body.referenceInputs
      override def totalCollateral(body: ConwayTxBody): Option[Coin] = body.totalCollateral
      override def sizedCollateralReturn(body: ConwayTxBody): Option[Sized[ConwayTxOut]] =
        body.collateralReturn
      // `allInputsTxBodyF = babbageAllInputsTxBodyF`, `Conway/TxBody.hs:344`, cardano-ledger f649f975
      override def allInputs(body: ConwayTxBody): SortedSet[TxIn] = allInputsBabbage(body)
      // `spendableInputsTxBodyF = to (`withTopTxLevelOnly` (^. babbageSpendableInputsTxBodyF))`,
      // `Conway/TxBody.hs:341`, cardano-ledger f649f975
      override def spendableInputs[L <: TxLevel](body: TxBody[L, Conway])(using
          l: TopOnly[L]
      ): SortedSet[TxIn] =
        l match
          case TopOnly.Top => spendableInputsBabbage(body)
      // `getTotalDepositsTxBody = conwayTotalDepositsTxBody`, `Conway/TxBody.hs:356,363-380`,
      // cardano-ledger f649f975: the certificates' deposits and the proposals'
      override def getTotalDepositsTxBody(
          pp: PParams[Conway],
          isPoolRegistered: KeyHash[StakePool] => Boolean,
          body: ConwayTxBody
      ): Coin =
        Coin(
          certsOf
            .getTotalDepositsTxCerts(pp, isPoolRegistered, certs[TxLevel.Top](body)(using top))
            .value +
            conwayProposalsDeposits[TxLevel.Top](pp, body)(using top).value
        )
      // `withTopTxLevelOnly`, `Conway/TxBody.hs:453`, cardano-ledger f649f975
      override def allSizedOutputs[L <: TxLevel](body: TxBody[L, Conway])(using
          l: TopOnly[L]
      ): Vector[Sized[ConwayTxOut]] =
        l match
          case TopOnly.Top => allSizedOutputsBabbage(body)
      override def currentTreasuryValue[L <: TxLevel](body: TxBody[L, Conway])(using
          l: TopOnly[L]
      ): Option[Coin] =
        l match
          case TopOnly.Top => body.currentTreasuryValue
      override def votingProcedures[L <: TxLevel](body: TxBody[L, Conway])(using
          l: TopOnly[L]
      ): VotingProcedures[Conway] =
        l match
          case TopOnly.Top => body.votingProcedures
      override def proposalProcedures[L <: TxLevel](body: TxBody[L, Conway])(using
          l: TopOnly[L]
      ): OSet[ProposalProcedure[Conway]] =
        l match
          case TopOnly.Top => body.proposalProcedures
      override def treasuryDonation[L <: TxLevel](body: TxBody[L, Conway])(using
          l: TopOnly[L]
      ): Coin =
        l match
          case TopOnly.Top => body.treasuryDonation
