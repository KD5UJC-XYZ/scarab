// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.{Credential, DecodeField, LedgerDecodeError, TxIn, Unsigned, Voter}
import xyz.kd5ujc.scarab.ledger.dijkstra.V12
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoScriptRep,
  DijkstraEraScript,
  EraTxError,
  Language,
  Plutus,
  PlutusLanguage,
  ProposalProcedure
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPlutusScript, ConwayScript}
import xyz.kd5ujc.scarab.ledger.state.{AccountAddress, Dijkstra, ScriptHash}

// Dijkstra's scripts, at cardano-ledger 9df9f98c, the ledger of the Leios testnet's build (the
// Dijkstra Scarab implements, #54): the opaque type is transparent in this file only.

/** Dijkstra's Plutus script: the languages Dijkstra has, V1 to V4 (`data PlutusScript DijkstraEra`,
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Scripts.hs:476-481`, cardano-ledger 9df9f98c).
  */
enum DijkstraPlutusScript:
  case V1(plutus: Plutus[Language.PlutusV1])
  case V2(plutus: Plutus[Language.PlutusV2])
  case V3(plutus: Plutus[Language.PlutusV3])
  case V4(plutus: Plutus[Language.PlutusV4])

  /** The script's language (`plutusLanguage`). */
  def language: xyz.kd5ujc.scarab.plutus.PlutusLanguage = this match
    case V1(_) => summon[PlutusLanguage[Language.PlutusV1]].language
    case V2(_) => summon[PlutusLanguage[Language.PlutusV2]].language
    case V3(_) => summon[PlutusLanguage[Language.PlutusV3]].language
    case V4(_) => summon[PlutusLanguage[Language.PlutusV4]].language

  /** The prefix of its hash (`plutusLanguageTag`). */
  def tag: Byte = this match
    case V1(_) => summon[PlutusLanguage[Language.PlutusV1]].tag
    case V2(_) => summon[PlutusLanguage[Language.PlutusV2]].tag
    case V3(_) => summon[PlutusLanguage[Language.PlutusV3]].tag
    case V4(_) => summon[PlutusLanguage[Language.PlutusV4]].tag

  def binary: Bytes = this match
    case V1(p) => p.binary
    case V2(p) => p.binary
    case V3(p) => p.binary
    case V4(p) => p.binary

object DijkstraPlutusScript:
  given CanEqual[DijkstraPlutusScript, DijkstraPlutusScript] = CanEqual.derived

  /** The Plutus script the block decoder read, of its language and with its program's bytes. */
  private[dijkstra] def ofFlat(
      language: xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraLanguage,
      code: Bytes
  ): DijkstraPlutusScript = language match
    case xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraLanguage.PlutusV1 => V1(Plutus(code))
    case xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraLanguage.PlutusV2 => V2(Plutus(code))
    case xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraLanguage.PlutusV3 => V3(Plutus(code))
    case xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraLanguage.PlutusV4 => V4(Plutus(code))

  /** A Conway Plutus script carried into Dijkstra (`upgradeScript`, `Scripts.hs:423-428`,
    * cardano-ledger 9df9f98c): the same program in the same language.
    */
  def translate(p: ConwayPlutusScript): DijkstraPlutusScript = p match
    case ConwayPlutusScript.V1(s) => V1(s)
    case ConwayPlutusScript.V2(s) => V2(s)
    case ConwayPlutusScript.V3(s) => V3(s)

/** Dijkstra's script: `AlonzoScript DijkstraEra` (`Scripts.hs:420`, cardano-ledger 9df9f98c), a
  * Dijkstra native script or a Dijkstra Plutus script.
  */
opaque type DijkstraScript = AlonzoScriptRep[DijkstraNativeScript, DijkstraPlutusScript]

object DijkstraScript:
  /** Dijkstra's script decoder: `decodeAlonzoScript` at `eraMaxLanguage = PlutusV4`
    * (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Scripts.hs:728-758`, `Scripts.hs:485`,
    * cardano-ledger 9df9f98c) at decoder version 12, as the Leios build's block decoder reads it
    * ([[xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraScript.script]]): `decodeRecordSum` of a native
    * script `[0, script]` or a Plutus script `[1..4, bytes]` of Plutus V1 to V4, its bytes a byte
    * string, chunked from version 12, the program not deserialised.
    */
  def decode(n: Node): Either[EraTxError, DijkstraScript] = read(n).leftMap(EraTxError.Decode(_))

  private[dijkstra] def read(n: Node): Either[LedgerDecodeError, DijkstraScript] =
    def plutus(make: Bytes => DijkstraPlutusScript)(xs: Vector[Node]) =
      V12
        .bytes(xs(1), DecodeField.PlutusScript)
        .map(b => AlonzoScriptRep.PlutusScript(make(b)): DijkstraScript)
    V12.sum[DijkstraScript](n, DecodeField.Script) {
      case t if t == 0 =>
        (2, xs => DijkstraNativeScript.read(xs(1)).map(AlonzoScriptRep.NativeScript(_)))
      case t if t == 1 => (2, plutus(b => DijkstraPlutusScript.V1(Plutus(b))))
      case t if t == 2 => (2, plutus(b => DijkstraPlutusScript.V2(Plutus(b))))
      case t if t == 3 => (2, plutus(b => DijkstraPlutusScript.V3(Plutus(b))))
      case t if t == 4 => (2, plutus(b => DijkstraPlutusScript.V4(Plutus(b))))
    }

  /** A Conway script carried into Dijkstra (`upgradeScript`, `Scripts.hs:423-428`, cardano-ledger
    * 9df9f98c): a native script rebuilt in Dijkstra's encoding
    * ([[DijkstraNativeScript.translate]]), a Plutus script in the same language.
    */
  def translate(s: ConwayScript): Either[EraTxError, DijkstraScript] = s.rep match
    case AlonzoScriptRep.NativeScript(native) =>
      DijkstraNativeScript.translate(native).map(AlonzoScriptRep.NativeScript(_))
    case AlonzoScriptRep.PlutusScript(p) =>
      Right(AlonzoScriptRep.PlutusScript(DijkstraPlutusScript.translate(p)))

  extension (s: DijkstraScript)
    /** The shared representation, read only. */
    def rep: AlonzoScriptRep[DijkstraNativeScript, DijkstraPlutusScript] = s

  given CanEqual[DijkstraScript, DijkstraScript] = CanEqual.derived

  /** `AlonzoEraScript DijkstraEra` (`Scripts.hs:475-534`, cardano-ledger 9df9f98c), with the
    * native-script readers of `ShelleyEraScript` and `AllegraEraScript` (`Scripts.hs:536-554`),
    * `ConwayEraScript DijkstraEra` (`Scripts.hs:556-565`) and `DijkstraEraScript DijkstraEra`
    * (`Scripts.hs:574-581`), the guard's reader the native script's
    * ([[DijkstraNativeScript.getRequireGuard]]).
    */
  val instance: DijkstraEraScript[Dijkstra] = new DijkstraEraScript[Dijkstra]:
    override def scriptPrefixTag(script: DijkstraScript): Byte = script match
      case AlonzoScriptRep.NativeScript(_) => 0
      case AlonzoScriptRep.PlutusScript(p) => p.tag
    override def originalBytes(script: DijkstraScript): Bytes = script match
      case AlonzoScriptRep.NativeScript(t) => t.raw
      case AlonzoScriptRep.PlutusScript(p) => p.binary
    override def getNativeScript(script: DijkstraScript): Option[DijkstraNativeScript] =
      script match
        case AlonzoScriptRep.NativeScript(t) => Some(t)
        case AlonzoScriptRep.PlutusScript(_) => None
    override def fromNativeScript(native: DijkstraNativeScript): DijkstraScript =
      AlonzoScriptRep.NativeScript(native)
    override def getRequireSignature(native: DijkstraNativeScript): Option[Bytes] =
      native.getRequireSignature
    override def getRequireAllOf(
        native: DijkstraNativeScript
    ): Option[Vector[DijkstraNativeScript]] =
      native.getRequireAllOf
    override def getRequireAnyOf(
        native: DijkstraNativeScript
    ): Option[Vector[DijkstraNativeScript]] =
      native.getRequireAnyOf
    override def getRequireMOf(
        native: DijkstraNativeScript
    ): Option[(BigInt, Vector[DijkstraNativeScript])] = native.getRequireMOf
    override def getTimeStart(native: DijkstraNativeScript): Option[BigInt] = native.getTimeStart
    override def getTimeExpire(native: DijkstraNativeScript): Option[BigInt] = native.getTimeExpire
    override def eraMaxLanguage: xyz.kd5ujc.scarab.plutus.PlutusLanguage =
      xyz.kd5ujc.scarab.plutus.PlutusLanguage.V4
    override def toPlutusScript(script: DijkstraScript): Option[DijkstraPlutusScript] =
      script match
        case AlonzoScriptRep.PlutusScript(p) => Some(p)
        case AlonzoScriptRep.NativeScript(_) => None
    override def fromPlutusScript(plutus: DijkstraPlutusScript): DijkstraScript =
      AlonzoScriptRep.PlutusScript(plutus)
    // `withPlutusScript`, `Scripts.hs:494-497`, cardano-ledger 9df9f98c
    override def withPlutusScript[A](plutus: DijkstraPlutusScript)(
        f: [L <: Language] => (Plutus[L], PlutusLanguage[L]) => A
    ): A = plutus match
      case DijkstraPlutusScript.V1(p) => f(p, PlutusLanguage.v1)
      case DijkstraPlutusScript.V2(p) => f(p, PlutusLanguage.v2)
      case DijkstraPlutusScript.V3(p) => f(p, PlutusLanguage.v3)
      case DijkstraPlutusScript.V4(p) => f(p, PlutusLanguage.v4)

    // `hoistPlutusPurpose`, `Scripts.hs:499-506`, cardano-ledger 9df9f98c
    override def hoistPlutusPurpose[G[_, _], F[_, _]](f: [ix, it] => G[ix, it] => F[ix, it])(
        purpose: DijkstraPlutusPurpose[G]
    ): DijkstraPlutusPurpose[F] = purpose match
      case DijkstraPlutusPurpose.DijkstraSpending(x) => DijkstraPlutusPurpose.DijkstraSpending(f(x))
      case DijkstraPlutusPurpose.DijkstraMinting(x)  => DijkstraPlutusPurpose.DijkstraMinting(f(x))
      case DijkstraPlutusPurpose.DijkstraCertifying(x) =>
        DijkstraPlutusPurpose.DijkstraCertifying(f(x))
      case DijkstraPlutusPurpose.DijkstraWithdrawing(x) =>
        DijkstraPlutusPurpose.DijkstraWithdrawing(f(x))
      case DijkstraPlutusPurpose.DijkstraVoting(x) => DijkstraPlutusPurpose.DijkstraVoting(f(x))
      case DijkstraPlutusPurpose.DijkstraProposing(x) =>
        DijkstraPlutusPurpose.DijkstraProposing(f(x))
      case DijkstraPlutusPurpose.DijkstraGuarding(x) => DijkstraPlutusPurpose.DijkstraGuarding(f(x))

    // `mkSpendingPurpose` to `toWithdrawingPurpose`, `Scripts.hs:508-526`, cardano-ledger 9df9f98c
    override def mkSpendingPurpose[F[_, _]](
        txIn: F[Unsigned[Unsigned.Width.W32], TxIn]
    ): DijkstraPlutusPurpose[F] = DijkstraPlutusPurpose.DijkstraSpending(txIn)
    override def toSpendingPurpose[F[_, _]](
        purpose: DijkstraPlutusPurpose[F]
    ): Option[F[Unsigned[Unsigned.Width.W32], TxIn]] = purpose match
      case DijkstraPlutusPurpose.DijkstraSpending(i) => Some(i)
      case DijkstraPlutusPurpose.DijkstraMinting(_) | DijkstraPlutusPurpose.DijkstraCertifying(_) |
          DijkstraPlutusPurpose.DijkstraWithdrawing(_) | DijkstraPlutusPurpose.DijkstraVoting(_) |
          DijkstraPlutusPurpose.DijkstraProposing(_) | DijkstraPlutusPurpose.DijkstraGuarding(_) =>
        None
    override def mkMintingPurpose[F[_, _]](
        policy: F[Unsigned[Unsigned.Width.W32], Bytes]
    ): DijkstraPlutusPurpose[F] = DijkstraPlutusPurpose.DijkstraMinting(policy)
    override def toMintingPurpose[F[_, _]](
        purpose: DijkstraPlutusPurpose[F]
    ): Option[F[Unsigned[Unsigned.Width.W32], Bytes]] = purpose match
      case DijkstraPlutusPurpose.DijkstraMinting(i) => Some(i)
      case DijkstraPlutusPurpose.DijkstraSpending(_) | DijkstraPlutusPurpose.DijkstraCertifying(_) |
          DijkstraPlutusPurpose.DijkstraWithdrawing(_) | DijkstraPlutusPurpose.DijkstraVoting(_) |
          DijkstraPlutusPurpose.DijkstraProposing(_) | DijkstraPlutusPurpose.DijkstraGuarding(_) =>
        None
    override def mkCertifyingPurpose[F[_, _]](
        txCert: F[Unsigned[Unsigned.Width.W32], DijkstraTxCert]
    ): DijkstraPlutusPurpose[F] = DijkstraPlutusPurpose.DijkstraCertifying(txCert)
    override def toCertifyingPurpose[F[_, _]](
        purpose: DijkstraPlutusPurpose[F]
    ): Option[F[Unsigned[Unsigned.Width.W32], DijkstraTxCert]] = purpose match
      case DijkstraPlutusPurpose.DijkstraCertifying(i) => Some(i)
      case DijkstraPlutusPurpose.DijkstraSpending(_) | DijkstraPlutusPurpose.DijkstraMinting(_) |
          DijkstraPlutusPurpose.DijkstraWithdrawing(_) | DijkstraPlutusPurpose.DijkstraVoting(_) |
          DijkstraPlutusPurpose.DijkstraProposing(_) | DijkstraPlutusPurpose.DijkstraGuarding(_) =>
        None
    override def mkWithdrawingPurpose[F[_, _]](
        account: F[Unsigned[Unsigned.Width.W32], AccountAddress]
    ): DijkstraPlutusPurpose[F] = DijkstraPlutusPurpose.DijkstraWithdrawing(account)
    override def toWithdrawingPurpose[F[_, _]](
        purpose: DijkstraPlutusPurpose[F]
    ): Option[F[Unsigned[Unsigned.Width.W32], AccountAddress]] = purpose match
      case DijkstraPlutusPurpose.DijkstraWithdrawing(i) => Some(i)
      case DijkstraPlutusPurpose.DijkstraSpending(_) | DijkstraPlutusPurpose.DijkstraMinting(_) |
          DijkstraPlutusPurpose.DijkstraCertifying(_) | DijkstraPlutusPurpose.DijkstraVoting(_) |
          DijkstraPlutusPurpose.DijkstraProposing(_) | DijkstraPlutusPurpose.DijkstraGuarding(_) =>
        None

    // `ConwayEraScript DijkstraEra`, `Scripts.hs:556-565`, cardano-ledger 9df9f98c
    override def mkVotingPurpose[F[_, _]](
        voter: F[Unsigned[Unsigned.Width.W32], Voter]
    ): DijkstraPlutusPurpose[F] = DijkstraPlutusPurpose.DijkstraVoting(voter)
    override def toVotingPurpose[F[_, _]](
        purpose: DijkstraPlutusPurpose[F]
    ): Option[F[Unsigned[Unsigned.Width.W32], Voter]] = purpose match
      case DijkstraPlutusPurpose.DijkstraVoting(voter) => Some(voter)
      case DijkstraPlutusPurpose.DijkstraSpending(_) | DijkstraPlutusPurpose.DijkstraMinting(_) |
          DijkstraPlutusPurpose.DijkstraCertifying(_) |
          DijkstraPlutusPurpose.DijkstraWithdrawing(_) |
          DijkstraPlutusPurpose.DijkstraProposing(_) | DijkstraPlutusPurpose.DijkstraGuarding(_) =>
        None
    override def mkProposingPurpose[F[_, _]](
        proposal: F[Unsigned[Unsigned.Width.W32], ProposalProcedure[Dijkstra]]
    ): DijkstraPlutusPurpose[F] = DijkstraPlutusPurpose.DijkstraProposing(proposal)
    override def toProposingPurpose[F[_, _]](
        purpose: DijkstraPlutusPurpose[F]
    ): Option[F[Unsigned[Unsigned.Width.W32], ProposalProcedure[Dijkstra]]] = purpose match
      case DijkstraPlutusPurpose.DijkstraProposing(proposal) => Some(proposal)
      case DijkstraPlutusPurpose.DijkstraSpending(_) | DijkstraPlutusPurpose.DijkstraMinting(_) |
          DijkstraPlutusPurpose.DijkstraCertifying(_) |
          DijkstraPlutusPurpose.DijkstraWithdrawing(_) | DijkstraPlutusPurpose.DijkstraVoting(_) |
          DijkstraPlutusPurpose.DijkstraGuarding(_) =>
        None

    // `DijkstraEraScript DijkstraEra`, `Scripts.hs:574-581`, cardano-ledger 9df9f98c
    override def mkGuardingPurpose[F[_, _]](
        guard: F[Unsigned[Unsigned.Width.W32], ScriptHash]
    ): DijkstraPlutusPurpose[F] = DijkstraPlutusPurpose.DijkstraGuarding(guard)
    override def toGuardingPurpose[F[_, _]](
        purpose: DijkstraPlutusPurpose[F]
    ): Option[F[Unsigned[Unsigned.Width.W32], ScriptHash]] = purpose match
      case DijkstraPlutusPurpose.DijkstraGuarding(guard) => Some(guard)
      case DijkstraPlutusPurpose.DijkstraSpending(_) | DijkstraPlutusPurpose.DijkstraMinting(_) |
          DijkstraPlutusPurpose.DijkstraCertifying(_) |
          DijkstraPlutusPurpose.DijkstraWithdrawing(_) | DijkstraPlutusPurpose.DijkstraVoting(_) |
          DijkstraPlutusPurpose.DijkstraProposing(_) =>
        None
    override def getRequireGuard(native: DijkstraNativeScript): Option[Credential] =
      native.getRequireGuard
