// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.alonzo

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{
  Address,
  ByteStrings,
  CborRead,
  Datum as FlatDatum,
  DecodeField,
  LedgerDecodeError,
  ScriptKind,
  TxIn,
  Unsigned,
  Value as FlatValue
}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraScript,
  AlonzoEraTxOut,
  AlonzoPlutusPurpose,
  AlonzoScriptRep,
  AlonzoTxOutRep,
  Datum,
  EncCBOR,
  Encoding,
  EraField,
  EraTxError,
  Language,
  MultiAssetValue,
  NativeScriptRep,
  Plutus,
  PlutusLanguage,
  Timelock,
  TimelockScripts,
  Val
}
import xyz.kd5ujc.scarab.ledger.era.mary.{MaryTimelock, MaryTxOut}
import xyz.kd5ujc.scarab.ledger.state.{AccountAddress, Alonzo}

// Alonzo's types: the opaque types are transparent in this file only, so only this file makes a
// value of Alonzo.

/** Alonzo's native script: `Timelock AlonzoEra` (cardano-ledger f649f975
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Scripts.hs:525`).
  */
opaque type AlonzoTimelock = Timelock

object AlonzoTimelock:
  /** Alonzo's native-script decoder: the time-lock language. */
  def decode(n: Node)(using ByteStrings): Either[EraTxError, AlonzoTimelock] =
    read(n).leftMap(EraTxError.Decode(_))

  private[alonzo] def read(n: Node)(using ByteStrings): Either[LedgerDecodeError, AlonzoTimelock] =
    NativeScriptRep
      .decode[xyz.kd5ujc.scarab.ledger.era.NativeForm.Timelock](n)
      .leftMap(LedgerDecodeError.NativeScript(_))

  /** A Mary script carried into Alonzo with its bytes (`translateTimelock`,
    * `eras/allegra/impl/src/Cardano/Ledger/Allegra/Scripts.hs:202-217`, cardano-ledger f649f975).
    */
  def translate(t: MaryTimelock): AlonzoTimelock = t.rep

  extension (t: AlonzoTimelock)
    /** The shared representation, read only: the translation into Babbage reads it. */
    def rep: Timelock = t

  given CanEqual[AlonzoTimelock, AlonzoTimelock] = CanEqual.derived

/** Alonzo's Plutus script: the one language Alonzo has, V1 (`newtype PlutusScript AlonzoEra =
  * AlonzoPlutusV1 (Plutus 'PlutusV1)`, `Alonzo/Scripts.hs:566-567`, cardano-ledger f649f975).
  */
enum AlonzoPlutusScript:
  case AlonzoPlutusV1(plutus: Plutus[Language.PlutusV1])

  def kind: ScriptKind = this match
    case AlonzoPlutusV1(_) => ScriptKind.PlutusV1

  def binary: Bytes = this match
    case AlonzoPlutusV1(p) => p.binary

object AlonzoPlutusScript:
  given CanEqual[AlonzoPlutusScript, AlonzoPlutusScript] = CanEqual.derived

  /** The Alonzo script of `kind`, if Alonzo has the language (`mkPlutusScript`,
    * `Alonzo/Scripts.hs:573-576`, cardano-ledger f649f975).
    */
  private[alonzo] def of(kind: ScriptKind): Option[Bytes => AlonzoPlutusScript] = kind match
    case ScriptKind.PlutusV1 => Some(b => AlonzoPlutusV1(Plutus(b)))
    case ScriptKind.PlutusV2 | ScriptKind.PlutusV3 | ScriptKind.PlutusV4 | ScriptKind.Native =>
      None

/** Alonzo's script: `AlonzoScript AlonzoEra` (`Alonzo/Scripts.hs:524`, cardano-ledger f649f975), an
  * Alonzo native script or an Alonzo Plutus script.
  */
opaque type AlonzoScript = AlonzoScriptRep[AlonzoTimelock, AlonzoPlutusScript]

object AlonzoScript:
  /** Alonzo's script decoder (`decodeAlonzoScript` at `eraMaxLanguage = PlutusV1`,
    * `Alonzo/Scripts.hs:571, 691-721`, cardano-ledger f649f975), as the flat decoder reads it
    * ([[AlonzoScriptRep.decode]]).
    */
  def decode(n: Node)(using ByteStrings): Either[EraTxError, AlonzoScript] =
    read(n).leftMap(EraTxError.Decode(_))

  private[alonzo] def read(n: Node)(using ByteStrings): Either[LedgerDecodeError, AlonzoScript] =
    AlonzoScriptRep.decode(n, ScriptKind.PlutusV1)(AlonzoTimelock.read(_), AlonzoPlutusScript.of)

  /** A Mary script carried into Alonzo (`upgradeScript = NativeScript . translateTimelock`,
    * `Alonzo/Scripts.hs:527`, cardano-ledger f649f975).
    */
  def translate(t: MaryTimelock): AlonzoScript =
    AlonzoScriptRep.NativeScript(AlonzoTimelock.translate(t))

  extension (s: AlonzoScript)
    /** The shared representation, read only: the translation into Babbage reads it. */
    def rep: AlonzoScriptRep[AlonzoTimelock, AlonzoPlutusScript] = s

  given CanEqual[AlonzoScript, AlonzoScript] = CanEqual.derived

  /** `instance EraScript AlonzoEra` to `instance AlonzoEraScript AlonzoEra`
    * (`Alonzo/Scripts.hs:523-604`, cardano-ledger f649f975).
    */
  val instance: AlonzoEraScript[Alonzo] = new TimelockScripts[Alonzo] with AlonzoEraScript[Alonzo]:
    override protected def timelock(native: AlonzoTimelock): Timelock = native
    override protected def ofTimelock(t: Timelock): AlonzoTimelock = t
    // `alonzoScriptPrefixTag`, `Alonzo/Scripts.hs:537-543`, cardano-ledger f649f975
    override def scriptPrefixTag(script: AlonzoScript): Byte = script match
      case AlonzoScriptRep.NativeScript(_) => 0
      case AlonzoScriptRep.PlutusScript(p) => p.kind.tag.toByte
    override def originalBytes(script: AlonzoScript): Bytes = script match
      case AlonzoScriptRep.NativeScript(t) => t.raw
      case AlonzoScriptRep.PlutusScript(p) => p.binary
    override def getNativeScript(script: AlonzoScript): Option[AlonzoTimelock] = script match
      case AlonzoScriptRep.NativeScript(t) => Some(t)
      case AlonzoScriptRep.PlutusScript(_) => None
    override def fromNativeScript(native: AlonzoTimelock): AlonzoScript =
      AlonzoScriptRep.NativeScript(native)
    override def eraMaxLanguage: xyz.kd5ujc.scarab.plutus.PlutusLanguage =
      xyz.kd5ujc.scarab.plutus.PlutusLanguage.V1
    override def toPlutusScript(script: AlonzoScript): Option[AlonzoPlutusScript] = script match
      case AlonzoScriptRep.PlutusScript(p) => Some(p)
      case AlonzoScriptRep.NativeScript(_) => None
    override def fromPlutusScript(plutus: AlonzoPlutusScript): AlonzoScript =
      AlonzoScriptRep.PlutusScript(plutus)
    // `withPlutusScript (AlonzoPlutusV1 plutus) f = f plutus`, `Alonzo/Scripts.hs:578`,
    // cardano-ledger f649f975
    override def withPlutusScript[A](plutus: AlonzoPlutusScript)(
        f: [L <: Language] => (Plutus[L], PlutusLanguage[L]) => A
    ): A = plutus match
      case AlonzoPlutusScript.AlonzoPlutusV1(p) => f(p, PlutusLanguage.v1)

    // `hoistPlutusPurpose` and `mkSpendingPurpose` to `toWithdrawingPurpose`,
    // `Alonzo/Scripts.hs:580-604`, cardano-ledger f649f975: Alonzo's purpose, its builders the
    // constructors
    override def hoistPlutusPurpose[G[_, _], F[_, _]](f: [ix, it] => G[ix, it] => F[ix, it])(
        purpose: AlonzoPlutusPurpose[G, Alonzo]
    ): AlonzoPlutusPurpose[F, Alonzo] =
      AlonzoPlutusPurpose.hoistPlutusPurpose[G, F, Alonzo](f)(purpose)
    override def mkSpendingPurpose[F[_, _]](
        txIn: F[Unsigned[Unsigned.Width.W32], TxIn]
    ): AlonzoPlutusPurpose[F, Alonzo] = AlonzoPlutusPurpose.AlonzoSpending(txIn)
    override def toSpendingPurpose[F[_, _]](
        purpose: AlonzoPlutusPurpose[F, Alonzo]
    ): Option[F[Unsigned[Unsigned.Width.W32], TxIn]] = AlonzoPlutusPurpose.toSpending(purpose)
    override def mkMintingPurpose[F[_, _]](
        policy: F[Unsigned[Unsigned.Width.W32], Bytes]
    ): AlonzoPlutusPurpose[F, Alonzo] = AlonzoPlutusPurpose.AlonzoMinting(policy)
    override def toMintingPurpose[F[_, _]](
        purpose: AlonzoPlutusPurpose[F, Alonzo]
    ): Option[F[Unsigned[Unsigned.Width.W32], Bytes]] = AlonzoPlutusPurpose.toMinting(purpose)
    override def mkCertifyingPurpose[F[_, _]](
        txCert: F[Unsigned[Unsigned.Width.W32], AlonzoTxCert]
    ): AlonzoPlutusPurpose[F, Alonzo] = AlonzoPlutusPurpose.AlonzoCertifying(txCert)
    override def toCertifyingPurpose[F[_, _]](
        purpose: AlonzoPlutusPurpose[F, Alonzo]
    ): Option[F[Unsigned[Unsigned.Width.W32], AlonzoTxCert]] =
      AlonzoPlutusPurpose.toCertifying(purpose)
    override def mkWithdrawingPurpose[F[_, _]](
        account: F[Unsigned[Unsigned.Width.W32], AccountAddress]
    ): AlonzoPlutusPurpose[F, Alonzo] = AlonzoPlutusPurpose.AlonzoWithdrawing(account)
    override def toWithdrawingPurpose[F[_, _]](
        purpose: AlonzoPlutusPurpose[F, Alonzo]
    ): Option[F[Unsigned[Unsigned.Width.W32], AccountAddress]] =
      AlonzoPlutusPurpose.toWithdrawing(purpose)

/** An Alonzo output: `AlonzoTxOut AlonzoEra` (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxOut.hs:
  * 144-160, 303-333`, cardano-ledger f649f975), an address, a Mary value and an optional datum
  * hash. The reference's four compact constructors are how it holds one in memory; their fields are
  * these.
  */
opaque type AlonzoTxOut = AlonzoTxOutRep

object AlonzoTxOut:
  /** A stored output read as an Alonzo output (`ejectHardForkTxOut` of the current era,
    * ouroboros-consensus 82ecba329 `Cardano/Ledger.hs:137-162`): refused if it holds an inline
    * datum or a reference script, which Alonzo's output has not. Its compact address is the
    * address's bytes as stored. The reference's stored output holds the compact address its output
    * held when made: written again for one read from an Alonzo body of lovelace only
    * ([[compactAddress]]), carried for one translated from Mary. The store keeps neither which, so
    * the two differ only for a bootstrap address not written as the reference writes one, of an
    * output of lovelace only read from an Alonzo body (#85).
    */
  private[ledger] def fromStored(
      out: xyz.kd5ujc.scarab.ledger.TxOut
  ): Either[EraTxError, AlonzoTxOut] =
    for
      hash <- out.datum match
        case FlatDatum.NoDatum   => Right(None)
        case FlatDatum.Hash(h)   => Right(Some(h))
        case FlatDatum.Inline(_) => Left(EraTxError.NotInEra(EraField.Datum))
      _ <- Either.cond(out.scriptRef.isEmpty, (), EraTxError.NotInEra(EraField.ReferenceScript))
    yield AlonzoTxOutRep(
      out.raw,
      out.address,
      MultiAssetValue.of(out.value),
      out.valueSize,
      hash,
      out.address.raw
    )

  /** The output in the form the store keeps, the inverse of [[fromStored]]: its bytes, fields and
    * the value's size as they arrived.
    */
  private[ledger] def toStored(out: AlonzoTxOut): xyz.kd5ujc.scarab.ledger.TxOut =
    xyz.kd5ujc.scarab.ledger.TxOut(
      out.raw,
      out.address,
      out.value.stored,
      out.dataHash.fold(FlatDatum.NoDatum)(FlatDatum.Hash(_)),
      None,
      out.valueSize
    )

  /** An output of an Alonzo body read from its item: `DecCBOR (AlonzoTxOut era)`
    * (`Alonzo/TxOut.hs:388-411`, cardano-ledger f649f975), a list of two, the address and the
    * value, or of three, with the datum hash, a definite or an indefinite list. Each value is read
    * by the flat output reader's reader of its field ([[xyz.kd5ujc.scarab.ledger.TxOut.decode]]),
    * with its refusal, and a list of another length is refused as that reader refuses it. The
    * output is held to the form the store keeps of it, the flat output those fields make
    * ([[EraTxError.StoredOutputDiffers]]).
    */
  private[alonzo] def read(n: Node, mode: Address.Mode, values: FlatValue.Decoding)(using
      ByteStrings
  ): Either[EraTxError, AlonzoTxOut] =
    val forms = xyz.kd5ujc.scarab.ledger.TxOut.Forms.PairOrDatumHash
    n.value match
      case CborValue.Arr(xs) if forms.arities.contains(xs.size) =>
        (for
          a <- CborRead.bytes(xs(0), DecodeField.OutputAddress).flatMap(Address.decode(_, mode))
          v <- FlatValue.decode(xs(1), DecodeField.OutputValue, values, forms.records)
          d <- xs.lift(2).traverse(CborRead.bytesN(_, 32, DecodeField.DatumHash))
        yield (a, v, d))
          .leftMap(EraTxError.Decode(_))
          .flatMap { (a, v, d) =>
            val value = MultiAssetValue.of(v)
            compactAddress(a, value).flatMap { compact =>
              val out = AlonzoTxOutRep(n.original, a, value, xs(1).original.size, d, compact)
              val flat = xyz.kd5ujc.scarab.ledger.TxOut(
                n.original,
                a,
                v,
                d.fold(FlatDatum.NoDatum)(FlatDatum.Hash(_)),
                None,
                xs(1).original.size
              )
              Either.cond(toStored(out) == flat, out, EraTxError.StoredOutputDiffers)
            }
          }
      case _ => Left(EraTxError.Decode(LedgerDecodeError.OutputForm(forms, n.value)))

  /** The address as an output read from a body holds it (`mkTxOutCompact`,
    * `Alonzo/TxOut.hs:471-481`, cardano-ledger f649f975): of a value of lovelace only, the output
    * is built again from the address read (`AlonzoTxOut addr`, `:303-331`), which holds it as
    * `compactAddr` of the address, its fields written again (`putAddr`,
    * `libs/cardano-ledger-core/src/Cardano/Ledger/Address.hs:277-279`): a bootstrap address encoded
    * as the reference encodes a Byron address
    * ([[xyz.kd5ujc.scarab.ledger.byron.ByronAddress.reencoded]]), a Shelley address as the bytes
    * read, which the reader already cut and normalised as `putAddr` writes them
    * ([[Address.decode]]). Of any other value, the bytes read (`fromCborBothAddr`).
    */
  private def compactAddress(a: Address, value: MultiAssetValue): Either[EraTxError, Bytes] =
    if !value.assets.isEmpty then Right(a.raw)
    else
      a.kind match
        case Address.Kind.Byron(_) =>
          xyz.kd5ujc.scarab.ledger.byron.ByronAddress.reencoded(a.raw).leftMap(EraTxError.Decode(_))
        case Address.Kind.Shelley(_, _, _, _) => Right(a.raw)

  /** A Mary output carried into Alonzo (`upgradeTxOut (Shelley.TxOutCompact addr value) =
    * TxOutCompact' addr value`, `Alonzo/TxOut.hs:340`, cardano-ledger f649f975): the same address,
    * value and bytes, and no datum hash.
    */
  def translate(out: MaryTxOut): AlonzoTxOut =
    val r = out.rep
    AlonzoTxOutRep(r.raw, r.address, r.value, r.valueSize, None, r.address.raw)

  /** `instance EncCBOR (AlonzoTxOut era)` (`Alonzo/TxOut.hs:374-386`, cardano-ledger f649f975):
    * `[address, value]`, or `[address, value, datum hash]`, each field written again, the address
    * as the compact bytes the output holds ([[AlonzoTxOutRep.compactAddress]]) and the value as
    * Mary's.
    */
  given encCBOR: EncCBOR[AlonzoTxOut] = out =>
    import Encoding.*
    val fields = encodeBytes(out.compactAddress) |+| EncCBOR.multiAssetValue.encCBOR(out.value)
    out.dataHash.fold(encodeListLen(2) |+| fields)(dh =>
      encodeListLen(3) |+| fields |+| encodeBytes(dh)
    )

  extension (out: AlonzoTxOut)
    /** The shared representation, read only: the translation into Babbage reads it. */
    def rep: AlonzoTxOutRep = out

  given CanEqual[AlonzoTxOut, AlonzoTxOut] = CanEqual.derived

  /** `instance EraTxOut AlonzoEra` and `instance AlonzoEraTxOut AlonzoEra`
    * (`Alonzo/TxOut.hs:335-372, 519-528`, cardano-ledger f649f975).
    */
  val instance: AlonzoEraTxOut[Alonzo] = new AlonzoEraTxOut[Alonzo]:
    override def valueOps: Val[MultiAssetValue] = summon[Val[MultiAssetValue]]
    override def address(out: AlonzoTxOut): Address = out.address
    override def value(out: AlonzoTxOut): MultiAssetValue = out.value
    override def raw(out: AlonzoTxOut): Bytes = out.raw
    override def dataHash(out: AlonzoTxOut): Option[Bytes] = out.dataHash
    // `datumTxOutF`, `Alonzo/TxOut.hs:524-527`, cardano-ledger f649f975
    override def datum(out: AlonzoTxOut): Datum =
      out.dataHash.fold(Datum.NoDatum)(Datum.DatumHash(_))
