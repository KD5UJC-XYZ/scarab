// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import scala.collection.immutable.{SortedMap, SortedSet}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.{
  Address,
  ByteStrings,
  Datum,
  DecodeField,
  GovActionId,
  LedgerDecodeError,
  Proposal,
  RewardAccount,
  ScriptKind,
  TxIn,
  Unsigned,
  Voter,
  VotingProcedure
}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoScriptRep,
  BabbageEraTxOut,
  BabbageTxOutRep,
  ConwayEraScript,
  ConwayEraTxBody,
  ConwayEraTxCert,
  EraField,
  EraTxError,
  Language,
  MultiAssetValue,
  NativeScriptRep,
  Network,
  OSet,
  Plutus,
  Sized,
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
    override def eraMaxLanguage: ScriptKind = ScriptKind.PlutusV3
    override def toPlutusScript(script: ConwayScript): Option[ConwayPlutusScript] = script match
      case AlonzoScriptRep.PlutusScript(p) => Some(p)
      case AlonzoScriptRep.NativeScript(_) => None
    override def fromPlutusScript(plutus: ConwayPlutusScript): ConwayScript =
      AlonzoScriptRep.PlutusScript(plutus)

    // `ConwayEraScript ConwayEra`, `Conway/Scripts.hs:131-141`, cardano-ledger f649f975
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
        proposal: F[Unsigned[Unsigned.Width.W32], Proposal]
    ): ConwayPlutusPurpose[F] = ConwayPlutusPurpose.ConwayProposing(proposal)
    override def toProposingPurpose[F[_, _]](
        purpose: ConwayPlutusPurpose[F]
    ): Option[F[Unsigned[Unsigned.Width.W32], Proposal]] = purpose match
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
    * `Cardano/Ledger.hs:137-162`): its reference script read as a Conway script.
    */
  private[ledger] def fromStored(
      out: xyz.kd5ujc.scarab.ledger.TxOut
  ): Either[EraTxError, ConwayTxOut] =
    BabbageTxOutRep
      .fromStored(out)(n => ConwayScript.read(n))
      .leftMap(EraTxError.Decode(_))
      // the output written back is the one read, so `toStored` is its inverse
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
      out.datum,
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

  val instance: BabbageEraTxOut[Conway] = new BabbageEraTxOut[Conway]:
    override def scripts: ConwayEraScript[Conway] = ConwayScript.instance
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
  *   the inputs (`ctbrSpendInputs`), in the ledger's `TxIn` order
  * @param outputs
  *   the outputs, each with the size of the encoding it was read from (`Sized`)
  * @param validity
  *   the slots the transaction is valid in (`ctbrVldt`)
  */
final case class ConwayTxBody private (
    raw: Bytes,
    spendInputs: SortedSet[TxIn],
    collateralInputs: SortedSet[TxIn],
    referenceInputs: SortedSet[TxIn],
    outputs: Vector[Sized[ConwayTxOut]],
    collateralReturn: Option[Sized[ConwayTxOut]],
    totalCollateral: Option[Coin],
    certs: OSet[ConwayTxCert],
    withdrawals: Map[RewardAccount, Coin],
    fee: Coin,
    validity: ValidityInterval,
    reqSignerHashes: SortedSet[Bytes],
    mint: Map[Bytes, Map[Bytes, BigInt]],
    scriptIntegrityHash: Option[Bytes],
    auxDataHash: Option[Bytes],
    networkId: Option[Network],
    votingProcedures: SortedMap[Voter, SortedMap[GovActionId, VotingProcedure]],
    proposalProcedures: OSet[Proposal],
    currentTreasuryValue: Option[Coin],
    treasuryDonation: Coin
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
    // an output's size is the length of the encoding it was read from (`Sized`, as the flat
    // output's `ledgerSize`)
    def sized(out: xyz.kd5ujc.scarab.ledger.TxOut) =
      ConwayTxOut.fromStored(out).map(Sized(_, out.ledgerSize.toLong))
    for
      _ <- Either.cond(b.update.isEmpty, (), EraTxError.NotInEra(EraField.Update))
      outputs <- b.outputs.traverse(sized)
      collateralReturn <- b.collateralReturn.traverse(sized)
      certs <- b.certificates.traverse(ConwayTxCert.fromFlat)
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
      b.withdrawals,
      Coin(b.fee),
      ValidityInterval(b.validityStart, b.ttl),
      SortedSet.from(b.requiredSigners),
      b.mint,
      b.scriptDataHash,
      b.auxDataHash,
      network,
      SortedMap.from(b.votes.map((voter, votes) => voter -> SortedMap.from(votes))),
      OSet.decoded(b.proposals),
      b.currentTreasury.map(Coin(_)),
      Coin(b.donation)
    )

  val instance: ConwayEraTxBody[Conway] { type Level[L <: TxLevel] = TopOnly[L] } =
    new ConwayEraTxBody[Conway]:
      type Level[L <: TxLevel] = TopOnly[L]
      override def top: TopOnly[TxLevel.Top] = TopOnly.Top
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
      override def reqSignerHashes[L <: TxLevel](body: TxBody[L, Conway])(using
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
      override def referenceInputs[L <: TxLevel](body: TxBody[L, Conway])(using
          l: TopOnly[L]
      ): SortedSet[TxIn] =
        l match
          case TopOnly.Top => body.referenceInputs
      override def totalCollateral(body: ConwayTxBody): Option[Coin] = body.totalCollateral
      override def sizedCollateralReturn(body: ConwayTxBody): Option[Sized[ConwayTxOut]] =
        body.collateralReturn
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
      ): SortedMap[Voter, SortedMap[GovActionId, VotingProcedure]] =
        l match
          case TopOnly.Top => body.votingProcedures
      override def proposalProcedures[L <: TxLevel](body: TxBody[L, Conway])(using
          l: TopOnly[L]
      ): OSet[Proposal] =
        l match
          case TopOnly.Top => body.proposalProcedures
      override def treasuryDonation[L <: TxLevel](body: TxBody[L, Conway])(using
          l: TopOnly[L]
      ): Coin =
        l match
          case TopOnly.Top => body.treasuryDonation
