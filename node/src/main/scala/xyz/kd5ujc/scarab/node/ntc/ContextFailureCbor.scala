// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.data.NonEmptyVector
import cats.Order
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.state.{ProtVer, ShelleyBasedEra}
import xyz.kd5ujc.scarab.storage.haskell.state.{
  HaskellStateCodecs,
  NodeOrders,
  Params,
  Versions,
  Wire
}

/** The reference's `CollectError` and `ContextError` payloads, of Alonzo, Babbage and Conway.
  * Implementation-specific translation failures have no such encoding and retain the existing text
  * fallback. The sources are cardano-ledger `f649f975` (the revision of the baselines in
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
  *   - `Babbage/TxInfo.hs:249-292`: the Babbage `ContextError`, tags 0, 1, 2, 4, 5 and 6; Conway
  *     wraps those at 8 (`Conway/TxInfo.hs:232-248`) and adds its own from 9.
  *   - `Alonzo/Scripts.hs:370-397`: a purpose is `[tag, item]`, the item being the bare certificate
  *     (`AsItem` derives its `EncCBOR` from the item, line 285-287).
  *   - `Shelley/TxCert.hs:420-435` and `:577-586` (cardano-ledger-shelley at the baseline rev
  *     4c81e90, byte-identical to the `f649f975` copy): the certificates of Alonzo and Babbage,
  *     whose `TxCert` is `ShelleyTxCert` (`Alonzo/TxCert.hs`, `Babbage/TxCert.hs`). Only the two
  *     that a script can name are written (see [[shelleyCertificate]]).
  *
  * The `CollectError` tags are one instance for all three eras, and the Conway rows of the
  * reference's collection confirm them. Babbage's `ContextError` payloads match the matrix's
  * reference rows at protocol versions 7 and 8 (`ContextMatrixSuite`).
  *
  * Recorded from the reference node 11.1.3 by the `e2e` load (`erarefusal.py`) and byte-equal in
  * `PlutusFailureReferenceSuite`: the Alonzo (protocol 5), Babbage (7) and Conway (9) missing
  * redeemers of a script credential's delegation and unregistration (Conway also the unregistration
  * with a refund) with the certificate; the `NoRedeemer` collect error of a certifying and of a
  * spending purpose in the three eras (UTXO 7 under UTXOW 0 in Alonzo, UTXO 1 under UTXO 2 in
  * Babbage); Babbage's and Conway's translation faults for an inline datum, Babbage's for a
  * reference script and a reference input, and the unresolved input in Babbage and Conway. Not
  * observable on the devnet, so hand-derived in `PreConwayFailureCborSuite`: `NoWitness` and
  * `NoCostModel` (see `docs/compatibility.md`), the Alonzo unresolved input (the bad input stops
  * the rules before UTXOS) and the Byron-output and redeemer-pointer faults.
  */
private[ntc] object ContextFailureCbor:
  import PredicateFailureCbor.{
    credential,
    element,
    item,
    list,
    put,
    rewardAccount,
    set,
    txIn,
    voter
  }
  import NodeOrders.given

  /** The era whose ledger writes the payload. */
  enum ContextEra:
    case Alonzo, Babbage, Conway

    /** The protocol version that sets and maps are written at (`Versions.eraLow`); below version 9
      * sets are plain arrays.
      */
    def protVer: ProtVer = this match
      case Alonzo  => Versions.eraLow(ShelleyBasedEra.SAlonzo)
      case Babbage => Versions.eraLow(ShelleyBasedEra.SBabbage)
      case Conway  => Versions.eraLow(ShelleyBasedEra.SConway)

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
      case CollectError.NoRedeemer(p) => purpose(era, p).map(pair(0, _))
      case CollectError.NoWitness(h)  => Some(pair(1, CborOut.build(_.bytes(h))))
      case CollectError.NoCostModel(language) =>
        Some(pair(2, CborOut.build(_.uint(language.id))))
      case CollectError.BadTranslation(error) => context(era, error).map(pair(3, _))

  private def source(value: TxOutSource): Bytes = CborOut.build { w =>
    value match
      case TxOutSource.TxOutFromInput(input)  => w.array(2).uint(0); txIn(w, input)
      case TxOutSource.TxOutFromOutput(index) => w.array(2).uint(1).uint(index)
  }

  /** The `PlutusPurpose AsItem` of the era as `[tag, item]`. Alonzo and Babbage have spending,
    * minting, certifying and rewarding purposes; the voting and proposing ones are Conway's.
    */
  private[ntc] def purpose(era: ContextEra, p: Purpose)(using ProtVer): Option[Bytes] = p match
    case Purpose.Certifying(_, cert) => certificate(era, cert).map(pair(2, _))
    case Purpose.Proposing(_, proposing) if era == ContextEra.Conway =>
      proposal(proposing).map(pair(5, _))
    case Purpose.Voting(_, _) | Purpose.Proposing(_, _) if era != ContextEra.Conway => None
    case other => Some(CborOut.build(item(_, other)))

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
    // Conway's own, and the failures that have no encoding
    case TranslationError.CertificateNotSupported(_) |
        TranslationError.PlutusPurposeNotSupported(_) |
        TranslationError.DelegationWithoutDelegatee |
        TranslationError.VotingProceduresFieldNotSupported(_) |
        TranslationError.ProposalProceduresFieldNotSupported(_) |
        TranslationError.TreasuryDonationFieldNotSupported(_) |
        TranslationError.CurrentTreasuryFieldNotSupported(_) |
        TranslationError.ReferenceInputsNotDisjointFromInputs(_) | TranslationError.Undecodable(_) |
        TranslationError.NoScriptBytes(_) | TranslationError.NativeScriptRedeemer |
        TranslationError.CostModelParameterTooLarge | TranslationError.NoEraHistory |
        TranslationError.NoClockSample(_) =>
      None

  /** The `ContextError` of the era. Alonzo's `AlonzoContextError` has the one translation failure
    * that the input lookup raises; Babbage's and Conway's are the cases of [[babbageCase]], Conway
    * wrapping them at 8 and adding its own.
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
      case ContextEra.Conway  => conwayContext(error)

  private def conwayContext(error: TranslationError)(using ProtVer): Option[Bytes] = error match
    case TranslationError.ByronTxOutInContext(_) | TranslationError.UnresolvedInput(_) |
        TranslationError.RedeemerPointerPointsToNothing(_, _) |
        TranslationError.InlineDatumsNotSupported(_) |
        TranslationError.ReferenceScriptsNotSupported(_) |
        TranslationError.ReferenceInputsNotSupported(_) | TranslationError.PastHorizon(_, _) =>
      babbageCase(error).map(pair(8, _))
    case TranslationError.CertificateNotSupported(cert) =>
      certificate(ContextEra.Conway, cert).map(pair(9, _))
    case TranslationError.PlutusPurposeNotSupported(p) =>
      purpose(ContextEra.Conway, p).map(pair(10, _))
    case TranslationError.CurrentTreasuryFieldNotSupported(coin) =>
      Some(pair(11, CborOut.build(_.uint(coin))))
    case TranslationError.VotingProceduresFieldNotSupported(procedures) =>
      Some(pair(12, votingProcedures(procedures)))
    case TranslationError.ProposalProceduresFieldNotSupported(procedures) =>
      procedures.traverse(proposal).map { encoded =>
        pair(
          13,
          CborOut.build { w =>
            w.tag(258)
            list(w, encoded)((out, raw) => { out.raw(raw); () })
          }
        )
      }
    case TranslationError.TreasuryDonationFieldNotSupported(coin) =>
      Some(pair(14, CborOut.build(_.uint(coin))))
    case TranslationError.ReferenceInputsNotDisjointFromInputs(inputs) =>
      Some(pair(15, CborOut.build(list(_, inputs.toVector)((w, in) => { txIn(w, in); () }))))
    // No encoding. These are decoder or setup faults of this implementation with no reference
    // constructor that names them: the reference's `TimeTranslationPastHorizon` is the
    // `PastHorizon` case, a clock that is only missing a recorded sample (`NoClockSample`) or an
    // era history (`NoEraHistory`) is not mapped to it. The text fallback keeps the reason; the
    // pre-Conway eras answer `None`.
    case TranslationError.DelegationWithoutDelegatee | TranslationError.Undecodable(_) |
        TranslationError.NoScriptBytes(_) | TranslationError.NativeScriptRedeemer |
        TranslationError.CostModelParameterTooLarge | TranslationError.NoEraHistory |
        TranslationError.NoClockSample(_) =>
      None

  private def votingProcedures(
      procedures: Vector[(Voter, Vector[(GovActionId, VotingProcedure)])]
  )(using ProtVer): Bytes =
    CborOut.build { w =>
      given Order[Voter] = Order.from((a, b) => Voter.ordering.compare(a, b))
      val procedure = element[VotingProcedure] { (out, value) =>
        out.array(2)
        put(out, value.vote)(using HaskellStateCodecs.vote)
        put(out, value.anchor)(using Wire.nullable(HaskellStateCodecs.anchor)); ()
      }
      put(w, procedures.map { case (v, votes) => v -> votes.toMap }.toMap)(using
        Wire.sortedMapOf(
          element((out, v: Voter) => { voter(out, v); () }),
          Wire.sortedMapOf(HaskellStateCodecs.govActionId, procedure)
        )
      ); ()
    }

  private def proposal(value: Proposal)(using ProtVer): Option[Bytes] =
    val projected = value.action match
      case GovAction.ParameterChange(previous, update, policy) =>
        Params.canonicalConwayUpdate(update).toOption.map { canonical =>
          value.copy(action = GovAction.ParameterChange(previous, canonical, policy))
        }
      case _ => Some(value)
    projected.map(p => HaskellStateCodecs.proposal.encodeUnchecked(p))

  private def relayBytes(raw: Bytes): Option[Bytes] =
    PoolRelays.decode(raw).toOption.map { relays =>
      CborOut.build { w =>
        list(w, relays) { (out, relay) =>
          def optional[A](value: Option[A])(write: A => CborOut): Unit =
            value.fold({ out.nul; () })(item => { write(item); () })
          relay match
            case PoolRelay.SingleHostAddr(port, ipv4, ipv6) =>
              out.array(4).uint(0)
              optional(port)(p => out.uint(p.toInt))
              optional(ipv4)(a => out.bytes(a.octets))
              optional(ipv6)(a => out.bytes(a.wire))
            case PoolRelay.SingleHostName(port, name) =>
              out.array(3).uint(1); optional(port)(p => out.uint(p.toInt)); out.text(name); ()
            case PoolRelay.MultiHostName(name) => out.array(2).uint(2).text(name); ()
        }
      }
    }

  /** The certificate of the era in a purpose. */
  private def certificate(era: ContextEra, cert: Certificate)(using ProtVer): Option[Bytes] =
    era match
      case ContextEra.Conway                      => conwayCertificate(cert)
      case ContextEra.Alonzo | ContextEra.Babbage => shelleyCertificate(cert)

  /** The certificates a script can name in a purpose before Conway: `getScriptWitnessShelleyTxCert`
    * (`Shelley/TxCert.hs:577-586`) gives a script requirement for an unregistration and a
    * delegation by a script credential only, so those two are the ones written; the other
    * certificates have no purpose in the reference's collection and are not encoded here. The
    * `ShelleyTxCert` tags are `Shelley/TxCert.hs:420-435`: the unregistration is `[1, credential]`,
    * the delegation `[2, credential, pool]`.
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

  private def conwayCertificate(cert: Certificate)(using ProtVer): Option[Bytes] =
    val projected = cert match
      case Certificate.RegisterPool(params) =>
        (
          relayBytes(params.relays),
          params.metadata.traverse { raw =>
            HaskellStateCodecs.anchor
              .decodeBytes(raw)
              .toOption
              .map(HaskellStateCodecs.anchor.encodeUnchecked)
          }
        ).mapN { (relays, metadata) =>
          Certificate.RegisterPool(params.copy(relays = relays, metadata = metadata))
        }
      case _ => Some(cert)
    projected.flatMap(encodeCertificate)

  /** Conway transaction certificate tags (`Conway/TxCert.hs:490-534,615-641`). */
  private def encodeCertificate(cert: Certificate)(using ProtVer): Option[Bytes] = cert match
    case Certificate.GenesisDelegate(_, _, _) | Certificate.MoveRewards(_, _) |
        Certificate.Delegate(_, None, None, _) =>
      None
    case _ =>
      Some(CborOut.build { w =>
        def anchor(value: Option[Anchor]): Unit =
          put(w, value)(using Wire.nullable(HaskellStateCodecs.anchor)); ()
        def drep(value: DRep): Unit = value match
          case DRep.Registered(c)      => credential(w, c); ()
          case DRep.AlwaysAbstain      => w.array(1).uint(2); ()
          case DRep.AlwaysNoConfidence => w.array(1).uint(3); ()
        cert match
          case Certificate.Register(c, deposit) =>
            w.array(if deposit.isDefined then 3 else 2).uint(if deposit.isDefined then 7 else 0)
            credential(w, c); deposit.foreach(w.uint)
          case Certificate.Unregister(c, refund) =>
            w.array(if refund.isDefined then 3 else 2).uint(if refund.isDefined then 8 else 1)
            credential(w, c); refund.foreach(w.uint)
          case Certificate.Delegate(c, pool, rep, deposit) =>
            val tag = (pool, rep, deposit) match
              case (Some(_), None, None)    => 2
              case (None, Some(_), None)    => 9
              case (Some(_), Some(_), None) => 10
              case (Some(_), None, Some(_)) => 11
              case (None, Some(_), Some(_)) => 12
              case _                        => 13
            w.array(2 + Vector(pool.isDefined, rep.isDefined, deposit.isDefined).count(identity))
              .uint(tag)
            credential(w, c); pool.foreach(w.bytes); rep.foreach(drep); deposit.foreach(w.uint)
          case Certificate.RegisterPool(p) =>
            w.array(10).uint(3).bytes(p.operator).bytes(p.vrfKeyHash).uint(p.pledge).uint(p.cost)
            w.rational(p.margin.numerator, p.margin.denominator)
            rewardAccount(w, p.rewardAccount)
            set(w, p.owners)((out, hash) => { out.bytes(hash); () })
            w.raw(p.relays); p.metadata.fold({ w.nul; () })(raw => { w.raw(raw); () })
          case Certificate.RetirePool(pool, epoch) => w.array(3).uint(4).bytes(pool).uint(epoch)
          case Certificate.RegisterDRep(c, deposit, a) =>
            w.array(4).uint(16); credential(w, c); w.uint(deposit); anchor(a)
          case Certificate.UnregisterDRep(c, refund) =>
            w.array(3).uint(17); credential(w, c); w.uint(refund)
          case Certificate.UpdateDRep(c, a) => w.array(3).uint(18); credential(w, c); anchor(a)
          case Certificate.CommitteeAuthorize(cold, hot) =>
            w.array(3).uint(14); credential(w, cold); credential(w, hot)
          case Certificate.CommitteeResign(cold, a) =>
            w.array(3).uint(15); credential(w, cold); anchor(a)
          case Certificate.GenesisDelegate(_, _, _) | Certificate.MoveRewards(_, _) => ()
      })
