// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.{
  Address,
  ByteStrings,
  Datum,
  GovActionId,
  LedgerDecodeError,
  Proposal,
  RewardAccount,
  ScriptKind,
  TxIn,
  Voter,
  VotingProcedure
}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraScript,
  AlonzoScriptRep,
  BabbageEraTxOut,
  BabbageTxOutRep,
  ConwayEraTxBody,
  ConwayEraTxCert,
  EraField,
  EraTxError,
  Language,
  MultiAssetValue,
  NativeScriptRep,
  Plutus,
  Timelock,
  TimelockScripts,
  TopOnly,
  TxBody,
  TxLevel,
  Val,
  ValidityInterval
}
import xyz.kd5ujc.scarab.ledger.era.babbage.{
  BabbagePlutusScript,
  BabbageScript,
  BabbageTimelock,
  BabbageTxOut
}
import xyz.kd5ujc.scarab.ledger.state.{Coin, Conway}
import xyz.kd5ujc.scarab.protocol.Era

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
    case ScriptKind.PlutusV1 => Some(b => V1(Plutus(b)))
    case ScriptKind.PlutusV2 => Some(b => V2(Plutus(b)))
    case ScriptKind.PlutusV3 => Some(b => V3(Plutus(b)))
    case ScriptKind.Native   => None

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

  given CanEqual[ConwayScript, ConwayScript] = CanEqual.derived

  val instance: AlonzoEraScript[Conway] = new TimelockScripts[Conway] with AlonzoEraScript[Conway]:
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
    override def eraMaxLanguage: ScriptKind = ScriptKind.PlutusV3
    override def toPlutusScript(script: ConwayScript): Option[ConwayPlutusScript] = script match
      case AlonzoScriptRep.PlutusScript(p) => Some(p)
      case AlonzoScriptRep.NativeScript(_) => None
    override def fromPlutusScript(plutus: ConwayPlutusScript): ConwayScript =
      AlonzoScriptRep.PlutusScript(plutus)

/** A Conway output: `BabbageTxOut ConwayEra`, the representation Babbage's has with Conway's script
  * (`eras/conway/impl/src/Cardano/Ledger/Conway/TxOut.hs`), a type of its own.
  */
opaque type ConwayTxOut = BabbageTxOutRep[ConwayScript]

object ConwayTxOut:
  /** The stored output read as a Conway output (`ejectHardForkTxOut`, ouroboros-consensus 82ecba329
    * `Cardano/Ledger.hs:137-162`): its reference script read as a Conway script.
    */
  private[ledger] def fromStored(
      out: xyz.kd5ujc.scarab.ledger.TxOut
  ): Either[EraTxError, ConwayTxOut] =
    BabbageTxOutRep.fromStored(out)(n => ConwayScript.read(n)).leftMap(EraTxError.Decode(_))

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

  val instance: BabbageEraTxOut[Conway] = new BabbageEraTxOut[Conway]:
    override def scripts: AlonzoEraScript[Conway] = ConwayScript.instance
    override def valueOps: Val[MultiAssetValue] = summon[Val[MultiAssetValue]]
    override def address(out: ConwayTxOut): Address = out.address
    override def value(out: ConwayTxOut): MultiAssetValue = out.value
    override def raw(out: ConwayTxOut): Bytes = out.raw
    override def dataHash(out: ConwayTxOut): Option[Bytes] = out.datum match
      case Datum.Hash(h)                   => Some(h)
      case Datum.NoDatum | Datum.Inline(_) => None
    override def datum(out: ConwayTxOut): Datum = out.datum
    override def data(out: ConwayTxOut): Option[Bytes] = out.datum match
      case Datum.Inline(d)               => Some(d)
      case Datum.NoDatum | Datum.Hash(_) => None
    override def referenceScript(out: ConwayTxOut): Option[ConwayScript] = out.scriptRef

/** A Conway transaction body: `TxBody TopTx ConwayEra` (`data ConwayTxBodyRaw`,
  * `eras/conway/impl/src/Cardano/Ledger/Conway/TxBody.hs:122-144`, cardano-ledger f649f975), with
  * the bytes it arrived as, whose hash is the transaction's id. Its outputs and collateral return
  * are Conway outputs and its certificates Conway certificates. Conway's bodies are of the top
  * level only. Made only by [[ConwayTxBody.decode]].
  *
  * @param spendInputs
  *   the inputs, as the flat decoder keeps them
  * @param validity
  *   the slots the transaction is valid in (`ctbrVldt`)
  */
final case class ConwayTxBody private (
    raw: Bytes,
    spendInputs: Vector[TxIn],
    collateralInputs: Vector[TxIn],
    referenceInputs: Vector[TxIn],
    outputs: Vector[ConwayTxOut],
    collateralReturn: Option[ConwayTxOut],
    totalCollateral: Option[BigInt],
    certs: Vector[ConwayTxCert],
    withdrawals: Map[RewardAccount, Coin],
    fee: BigInt,
    validity: ValidityInterval,
    reqSignerHashes: Vector[Bytes],
    mint: Map[Bytes, Map[Bytes, BigInt]],
    scriptIntegrityHash: Option[Bytes],
    auxDataHash: Option[Bytes],
    networkId: Option[BigInt],
    votingProcedures: Vector[(Voter, Vector[(GovActionId, VotingProcedure)])],
    proposalProcedures: Vector[Proposal],
    currentTreasuryValue: Option[BigInt],
    treasuryDonation: BigInt
):
  lazy val id: Bytes = xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(raw)

object ConwayTxBody:
  given CanEqual[ConwayTxBody, ConwayTxBody] = CanEqual.derived

  /** A Conway body from its bytes: Conway's decoder (the flat decoder at Conway), its outputs read
    * as Conway outputs and its certificates as Conway certificates. A protocol-parameter update,
    * which Conway's decoder does not read, is refused.
    */
  def decode(raw: Bytes): Either[EraTxError, ConwayTxBody] =
    xyz.kd5ujc.scarab.ledger.TxBody
      .decode(raw, Era.Conway)
      .leftMap(EraTxError.Decode(_))
      .flatMap(fromFlat)

  private def fromFlat(b: xyz.kd5ujc.scarab.ledger.TxBody): Either[EraTxError, ConwayTxBody] =
    for
      _ <- Either.cond(b.update.isEmpty, (), EraTxError.NotInEra(EraField.Update))
      outputs <- b.outputs.traverse(ConwayTxOut.fromStored)
      collateralReturn <- b.collateralReturn.traverse(ConwayTxOut.fromStored)
      certs <- b.certificates.traverse(ConwayTxCert.fromFlat)
    yield ConwayTxBody(
      b.raw,
      b.inputs,
      b.collateral,
      b.referenceInputs,
      outputs,
      collateralReturn,
      b.totalCollateral,
      certs,
      b.withdrawals,
      b.fee,
      ValidityInterval(b.validityStart, b.ttl),
      b.requiredSigners,
      b.mint,
      b.scriptDataHash,
      b.auxDataHash,
      b.networkId,
      b.votes,
      b.proposals,
      b.currentTreasury,
      b.donation
    )

  val instance: ConwayEraTxBody[Conway] = new ConwayEraTxBody[Conway]:
    type Level[L <: TxLevel] = TopOnly[L]
    override def top: TopOnly[TxLevel.Top] = TopOnly.Top
    override def outputsOf: BabbageEraTxOut[Conway] = ConwayTxOut.instance
    override def certsOf: ConwayEraTxCert[Conway] = ConwayTxCert.instance
    override def id[L <: TxLevel](body: TxBody[L, Conway])(using l: TopOnly[L]): Bytes =
      l match
        case TopOnly.Top => body.id
    override def inputs[L <: TxLevel](body: TxBody[L, Conway])(using l: TopOnly[L]): Vector[TxIn] =
      l match
        case TopOnly.Top => body.spendInputs
    override def outputs[L <: TxLevel](body: TxBody[L, Conway])(using
        l: TopOnly[L]
    ): Vector[ConwayTxOut] =
      l match
        case TopOnly.Top => body.outputs
    override def fee(body: ConwayTxBody): BigInt = body.fee
    override def withdrawals[L <: TxLevel](body: TxBody[L, Conway])(using
        l: TopOnly[L]
    ): Map[RewardAccount, Coin] =
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
        case TopOnly.Top => body.certs
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
    override def collateralInputs(body: ConwayTxBody): Vector[TxIn] = body.collateralInputs
    override def reqSignerHashes[L <: TxLevel](body: TxBody[L, Conway])(using
        l: TopOnly[L]
    ): Vector[Bytes] =
      l match
        case TopOnly.Top => body.reqSignerHashes
    override def scriptIntegrityHash[L <: TxLevel](body: TxBody[L, Conway])(using
        l: TopOnly[L]
    ): Option[Bytes] =
      l match
        case TopOnly.Top => body.scriptIntegrityHash
    override def networkId[L <: TxLevel](body: TxBody[L, Conway])(using
        l: TopOnly[L]
    ): Option[BigInt] =
      l match
        case TopOnly.Top => body.networkId
    override def referenceInputs[L <: TxLevel](body: TxBody[L, Conway])(using
        l: TopOnly[L]
    ): Vector[TxIn] =
      l match
        case TopOnly.Top => body.referenceInputs
    override def totalCollateral(body: ConwayTxBody): Option[BigInt] = body.totalCollateral
    override def collateralReturn(body: ConwayTxBody): Option[ConwayTxOut] = body.collateralReturn
    override def currentTreasuryValue[L <: TxLevel](body: TxBody[L, Conway])(using
        l: TopOnly[L]
    ): Option[BigInt] =
      l match
        case TopOnly.Top => body.currentTreasuryValue
    override def votingProcedures[L <: TxLevel](body: TxBody[L, Conway])(using
        l: TopOnly[L]
    ): Vector[(Voter, Vector[(GovActionId, VotingProcedure)])] =
      l match
        case TopOnly.Top => body.votingProcedures
    override def proposalProcedures[L <: TxLevel](body: TxBody[L, Conway])(using
        l: TopOnly[L]
    ): Vector[Proposal] =
      l match
        case TopOnly.Top => body.proposalProcedures
    override def treasuryDonation[L <: TxLevel](body: TxBody[L, Conway])(using
        l: TopOnly[L]
    ): BigInt =
      l match
        case TopOnly.Top => body.treasuryDonation
