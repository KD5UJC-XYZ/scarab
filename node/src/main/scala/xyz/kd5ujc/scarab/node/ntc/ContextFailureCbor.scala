// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.data.NonEmptyVector
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.state.{ProtVer, ShelleyBasedEra}
import xyz.kd5ujc.scarab.storage.haskell.state.{NodeOrders, Versions}

/** The reference's `CollectError` and `ContextError` payloads, of Alonzo and Babbage; Conway's are
  * written by its era encoder ([[ConwayFailureCbor]]) from its era types. Implementation-specific
  * translation failures have no such encoding and retain the existing text fallback. The sources
  * are cardano-ledger `f649f975` (the revision of the baselines in
  * `docs/conformance-source-review.md`), read from the released files of the pinned sources:
  *
  *   - `Alonzo/Plutus/Context.hs:357-383`: the `CollectError` constructors, tags 0 to 3; the
  *     `UTXOS` failure `CollectErrors` is tag 1 (`Alonzo/Rules/Utxos.hs:351-356`), the `UTXO` one
  *     tag 7 (`Alonzo/Rules/Utxo.hs`); Babbage holds Alonzo's at 1 (`Babbage/Rules/Utxo.hs`).
  *   - `Alonzo/Plutus/TxInfo.hs:213-231`: the Alonzo `ContextError`, of which
  *     `TranslationLogicMissingInput` (tag 1) and `TimeTranslationPastHorizon` (tag 7, the `Text`
  *     of [[xyz.kd5ujc.scarab.ledger.Horizon.pastHorizonText]]) are the translation failures this
  *     encoding writes; Babbage's `ContextError` holds the latter at 7 too
  *     (`Babbage/TxInfo.hs:289`).
  *   - `Babbage/TxInfo.hs:249-292`: the Babbage `ContextError`, tags 0, 1, 2, 4, 5 and 6.
  *   - `Alonzo/Scripts.hs:370-397`: a purpose is `[tag, item]`, the item being the bare certificate
  *     (`AsItem` derives its `EncCBOR` from the item, line 285-287).
  *   - `Shelley/TxCert.hs:420-435` and `:577-586` (cardano-ledger-shelley at the baseline rev
  *     4c81e90, byte-identical to the `f649f975` copy): the certificates of Alonzo and Babbage,
  *     whose `TxCert` is `ShelleyTxCert` (`Alonzo/TxCert.hs`, `Babbage/TxCert.hs`). Only the two
  *     that a script can name are written (see [[shelleyCertificate]]).
  *
  * Babbage's `ContextError` payloads match the matrix's reference rows at protocol versions 7 and 8
  * (`ContextMatrixSuite`).
  *
  * Recorded from the reference node 11.1.3 by the `e2e` load (`erarefusal.py`) and byte-equal in
  * `PlutusFailureReferenceSuite`: the Alonzo (protocol 5) and Babbage (7) missing redeemers of a
  * script credential's delegation and unregistration with the certificate; the `NoRedeemer` collect
  * error of a certifying and of a spending purpose in the two eras (UTXO 7 under UTXOW 0 in Alonzo,
  * UTXO 1 under UTXO 2 in Babbage); Babbage's translation faults for an inline datum, a reference
  * script and a reference input, and the unresolved input in Babbage. Not observable on the devnet,
  * so hand-derived in `PreConwayFailureCborSuite`: `NoWitness` and `NoCostModel` (see
  * `docs/compatibility.md`), the Alonzo unresolved input (the bad input stops the rules before
  * UTXOS) and the Byron-output and redeemer-pointer faults.
  */
private[ntc] object ContextFailureCbor:
  import FailureCbor.{credential, list, rewardAccount, set, txIn}
  import NodeOrders.given

  /** The era whose ledger writes the payload. */
  enum ContextEra:
    case Alonzo, Babbage

    /** The protocol version that sets and maps are written at (`Versions.eraLow`); below version 9
      * sets are plain arrays.
      */
    def protVer: ProtVer = this match
      case Alonzo  => Versions.eraLow(ShelleyBasedEra.SAlonzo)
      case Babbage => Versions.eraLow(ShelleyBasedEra.SBabbage)

  private def pair(tag: Int, payload: Bytes): Bytes =
    CborOut.build(w => w.array(2).uint(tag).raw(payload))

  /** The `UTXOS` failure `CollectErrors`: `[1, [errors…]]`. */
  def utxos(era: ContextEra, errors: NonEmptyVector[CollectError]): Option[Bytes] =
    given ProtVer = era.protVer
    errors.toVector.traverse(error => collect(era)(error)).map { encoded =>
      CborOut.build { w =>
        w.array(2).uint(1)
        list(w, encoded)((out, raw) => { out.raw(raw); () })
      }
    }

  private def collect(era: ContextEra)(error: CollectError)(using ProtVer): Option[Bytes] =
    error match
      case CollectError.NoRedeemer(p) => purpose(p).map(pair(0, _))
      case CollectError.NoWitness(h)  => Some(pair(1, CborOut.build(_.bytes(h))))
      case CollectError.NoCostModel(language) =>
        Some(pair(2, CborOut.build(_.uint(language.id))))
      case CollectError.BadTranslation(error) => context(era, error).map(pair(3, _))

  private def source(value: TxOutSource): Bytes = CborOut.build { w =>
    value match
      case TxOutSource.TxOutFromInput(input)  => w.array(2).uint(0); txIn(w, input)
      case TxOutSource.TxOutFromOutput(index) => w.array(2).uint(1).uint(index)
  }

  /** The `PlutusPurpose AsItem` of Alonzo and Babbage as `[tag, item]`: they have spending,
    * minting, certifying and rewarding purposes; the voting and proposing ones are Conway's, which
    * the era encoder ([[ConwayFailureCbor]]) writes.
    */
  private[ntc] def purpose(p: Purpose): Option[Bytes] = p match
    case Purpose.Spending(_, input, _) =>
      Some(CborOut.build { w =>
        w.array(2).uint(0); txIn(w, input); ()
      })
    case Purpose.Minting(_, policy) =>
      Some(CborOut.build { w =>
        w.array(2).uint(1).bytes(policy); ()
      })
    case Purpose.Certifying(_, cert) => shelleyCertificate(cert).map(pair(2, _))
    case Purpose.Rewarding(_, account) =>
      Some(CborOut.build { w =>
        w.array(2).uint(3); rewardAccount(w, account); ()
      })
    case Purpose.Voting(_, _) | Purpose.Proposing(_, _) => None

  /** The `BabbageContextError` cases Babbage has, at their tags. */
  private def babbageCase(error: TranslationError)(using ProtVer): Option[Bytes] = error match
    case TranslationError.ByronTxOutInContext(from) => Some(pair(0, source(from)))
    case TranslationError.UnresolvedInput(input) =>
      Some(pair(1, CborOut.build(txIn(_, input))))
    case TranslationError.RedeemerPointerPointsToNothing(tag, index) =>
      Some(pair(2, CborOut.build(_.array(2).uint(tag).uint(index))))
    case TranslationError.PastHorizon(slot, horizon) =>
      Some(pair(7, CborOut.build(_.text(horizon.pastHorizonText(slot)))))
    case TranslationError.InlineDatumsNotSupported(from)     => Some(pair(4, source(from)))
    case TranslationError.ReferenceScriptsNotSupported(from) => Some(pair(5, source(from)))
    case TranslationError.ReferenceInputsNotSupported(inputs) =>
      Some(pair(6, CborOut.build(set(_, inputs)(txIn))))
    // Conway's own, which have no Babbage tag, and the failures that have no encoding
    case TranslationError.CertificateNotSupported(_) |
        TranslationError.PlutusPurposeNotSupported(_) |
        TranslationError.DelegationWithoutDelegatee |
        TranslationError.VotingProceduresFieldNotSupported(_) |
        TranslationError.ProposalProceduresFieldNotSupported(_) |
        TranslationError.TreasuryDonationFieldNotSupported(_) |
        TranslationError.CurrentTreasuryFieldNotSupported(_) |
        TranslationError.ReferenceInputsNotDisjointFromInputs(_) | TranslationError.Undecodable(_) |
        TranslationError.NoScriptBytes(_) | TranslationError.NativeScriptRedeemer |
        TranslationError.LanguageWithoutContext(_) | TranslationError.CostModelParameterTooLarge |
        TranslationError.NoEraHistory | TranslationError.NoClockSample(_) =>
      None

  /** The `ContextError` of the era. Alonzo's `AlonzoContextError` has the one translation failure
    * that the input lookup raises; Babbage's are the cases of [[babbageCase]].
    */
  private def context(era: ContextEra, error: TranslationError)(using ProtVer): Option[Bytes] =
    era match
      case ContextEra.Alonzo =>
        error match
          case TranslationError.UnresolvedInput(input) =>
            Some(pair(1, CborOut.build(txIn(_, input))))
          case TranslationError.PastHorizon(slot, horizon) =>
            Some(pair(7, CborOut.build(_.text(horizon.pastHorizonText(slot)))))
          case _ => None
      case ContextEra.Babbage => babbageCase(error)

  /** The certificates a script can name in a purpose before Conway: `getScriptWitnessShelleyTxCert`
    * (`Shelley/TxCert.hs:577-586`) gives a script requirement for an unregistration and a
    * delegation by a script credential only, so those two are the ones written; the other
    * certificates have no purpose in the reference's collection and are not encoded here. The
    * `ShelleyTxCert` tags are `Shelley/TxCert.hs:420-435` (cardano-ledger f649f975): the
    * unregistration is `[1, credential]`, the delegation `[2, credential, pool]`.
    */
  private def shelleyCertificate(cert: Certificate): Option[Bytes] = cert match
    case Certificate.Unregister(c @ Credential.Script(_), None) =>
      Some(CborOut.build { w =>
        w.array(2).uint(1); credential(w, c); ()
      })
    case Certificate.Delegate(c @ Credential.Script(_), Some(pool), None, None) =>
      Some(CborOut.build { w =>
        w.array(3).uint(2); credential(w, c); w.bytes(pool); ()
      })
    case _ => None
