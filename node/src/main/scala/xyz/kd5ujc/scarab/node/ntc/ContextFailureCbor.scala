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

/** The reference's `CollectError` and Conway `ContextError` payloads. Implementation-specific
  * translation failures have no such encoding and retain the existing text fallback.
  * (`Alonzo/Plutus/Context.hs:374-383`, `Babbage/TxInfo.hs:275-292`, `Conway/TxInfo.hs:232-248`,
  * cardano-ledger f649f975.)
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
  private given ProtVer = Versions.eraLow(ShelleyBasedEra.SConway)

  private def pair(tag: Int, payload: Bytes): Bytes =
    CborOut.build(w => w.array(2).uint(tag).raw(payload))

  def utxos(errors: NonEmptyVector[CollectError]): Option[Bytes] =
    errors.toVector.traverse(collect).map { encoded =>
      CborOut.build { w =>
        w.array(2).uint(1)
        list(w, encoded)((out, raw) => { out.raw(raw); () })
      }
    }

  private def collect(error: CollectError): Option[Bytes] = error match
    case CollectError.NoRedeemer(p) => purpose(p).map(pair(0, _))
    case CollectError.NoWitness(h)  => Some(pair(1, CborOut.build(_.bytes(h))))
    case CollectError.NoCostModel(language) =>
      Some(pair(2, CborOut.build(_.uint(language.id))))
    case CollectError.BadTranslation(error) => context(error).map(pair(3, _))

  private def source(value: TxOutSource): Bytes = CborOut.build { w =>
    value match
      case TxOutSource.TxOutFromInput(input)  => w.array(2).uint(0); txIn(w, input)
      case TxOutSource.TxOutFromOutput(index) => w.array(2).uint(1).uint(index)
  }

  private def babbage(tag: Int, payload: Bytes): Bytes = pair(8, pair(tag, payload))

  private def context(error: TranslationError): Option[Bytes] = error match
    case TranslationError.ByronTxOutInContext(from) => Some(babbage(0, source(from)))
    case TranslationError.UnresolvedInput(input) =>
      Some(babbage(1, CborOut.build(txIn(_, input))))
    case TranslationError.RedeemerPointerPointsToNothing(tag, index) =>
      Some(babbage(2, CborOut.build(_.array(2).uint(tag).uint(index))))
    case TranslationError.InlineDatumsNotSupported(from)     => Some(babbage(4, source(from)))
    case TranslationError.ReferenceScriptsNotSupported(from) => Some(babbage(5, source(from)))
    case TranslationError.ReferenceInputsNotSupported(inputs) =>
      Some(babbage(6, CborOut.build(set(_, inputs)(txIn))))
    case TranslationError.CertificateNotSupported(cert) => certificate(cert).map(pair(9, _))
    case TranslationError.PlutusPurposeNotSupported(p)  => purpose(p).map(pair(10, _))
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
    case TranslationError.DelegationWithoutDelegatee | TranslationError.Undecodable(_) |
        TranslationError.NoScriptBytes(_) | TranslationError.NativeScriptRedeemer |
        TranslationError.CostModelParameterTooLarge | TranslationError.NoEraHistory |
        TranslationError.NoClockSample(_) =>
      None

  private def votingProcedures(
      procedures: Vector[(Voter, Vector[(GovActionId, VotingProcedure)])]
  ): Bytes =
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

  private def purpose(p: Purpose): Option[Bytes] = p match
    case Purpose.Certifying(_, cert) => certificate(cert).map(pair(2, _))
    case Purpose.Proposing(_, p)     => proposal(p).map(pair(5, _))
    case other                       => Some(CborOut.build(item(_, other)))

  private def proposal(value: Proposal): Option[Bytes] =
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

  private def certificate(cert: Certificate): Option[Bytes] =
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
  private def encodeCertificate(cert: Certificate): Option[Bytes] = cert match
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
