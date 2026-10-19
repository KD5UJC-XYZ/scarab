// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.Order
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.state.{ProtVer, ShelleyBasedEra}
import xyz.kd5ujc.scarab.models.runtime.Codec
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.{
  HaskellStateCodecs,
  NodeOrders,
  Params,
  UtxoCodecs,
  Versions,
  Wire
}

/** The ledger's predicate-failure CBOR (`EncCBOR` of `ConwayLedgerPredFailure` and the rules it
  * nests), which `cardano-cli transaction submit` decodes to render a rejection.
  *
  * A failure is a list whose first element is the constructor's tag followed by its payload
  * (`Sum`); a `Mismatch` is written as two fields in line (supplied, expected), or in the reverse
  * order where the ledger swaps them (fee too small, execution units, network in the body, the
  * collateral input count, the treasury value). Sets are tag 258, `NonEmpty` is a list, a
  * `StrictMaybe` is an empty or one-element list.
  *
  * The whole answer of a LocalTxSubmission refusal is the hard-fork-combinator `ApplyTxErr`: a
  * one-element list holding the era index (Conway, 6) and the non-empty list of failures.
  */
object PredicateFailureCbor:
  private[ntc] type W = CborOut

  val ConwayIndex = 6

  /** The reason item sent with `MsgRejectTx` for a refused Conway transaction. A failure of a rule
    * the Conway ledger does not have (the update proposals, the MIR certificates, the time to live)
    * or whose payload this encoding does not write ([[inConway]]) is sent as the ledger's own
    * free-text failure, so that the client still shows the reason.
    */
  def applyTxError(failures: Vector[PredicateFailure]): Bytes =
    CborOut.build { w =>
      w.array(1).array(2).uint(ConwayIndex)
      list(w, failures)(ledger)
    }

  /** The reason item for a refused transaction of `era`, when the era's ledger has a constructor
    * for every failure: Conway's [[applyTxError]], or the earlier eras' ([[PreConwayFailureCbor]]).
    */
  def applyTxError(era: Era, failures: Vector[PredicateFailure]): Option[Bytes] = era match
    case Era.Conway => Some(applyTxError(failures))
    case other      => PreConwayFailureCbor.applyTxError(other, failures)

  /** The failure as Conway's ledger reports it: itself, or its text (`ConwayMempoolFailure`). */
  private def conwayForm(f: PredicateFailure): PredicateFailure =
    if inConway(f) then f else PredicateFailure.Mempool(MempoolProblem.Rule(f))

  /** Whether Conway's ledger has a constructor for the failure that this encoding writes. The
    * failures of the rules of the earlier eras (`ExpiredUTxO`, PPUP, `MIRInsufficientGenesisSigs`,
    * the Shelley DELEG failures) are not. Reference-shaped `CollectErrors` payloads are written;
    * implementation-specific translation failures retain their text fallback.
    */
  def inConway(f: PredicateFailure): Boolean = f match
    case PredicateFailure.Utxow(u)                                       => utxowInConway(u)
    case PredicateFailure.Certs(CertsFailure.Cert(CertFailure.Deleg(d))) => delegInConway(d)
    case _                                                               => true

  private def utxowInConway(u: UtxowFailure): Boolean = u match
    case UtxowFailure.MIRInsufficientGenesisSigs(_) => false
    case UtxowFailure.Utxo(UtxoFailure.Utxos(UtxosFailure.CollectErrors(errors))) =>
      ContextFailureCbor.utxos(errors).isDefined
    case UtxowFailure.Utxo(UtxoFailure.ExpiredUTxO(_) | UtxoFailure.Update(_)) =>
      false
    case _ => true

  private def delegInConway(d: DelegFailure): Boolean = d match
    case DelegFailure.StakeKeyAlreadyRegistered(_) | DelegFailure.StakeDelegationImpossible(_) |
        DelegFailure.WrongCertificateType | DelegFailure.GenesisKeyNotInMapping(_) |
        DelegFailure.DuplicateGenesisDelegate(_) | DelegFailure.DuplicateGenesisVRF(_) |
        DelegFailure.InsufficientForInstantaneousRewards(_, _) |
        DelegFailure.MIRCertificateTooLateinEpoch(_) | DelegFailure.MIRTransferNotCurrentlyAllowed |
        DelegFailure.MIRNegativesNotCurrentlyAllowed | DelegFailure.InsufficientForTransfer(_, _) |
        DelegFailure.MIRProducesNegativeUpdate | DelegFailure.MIRNegativeTransfer(_, _) |
        DelegFailure.DelegateeNotRegistered(_) =>
      false
    case _ => true

  /** The place of a failure Conway's ledger has no constructor for: [[applyTxError]] sends those as
    * text ([[inConway]]), so no encoding reaches it.
    */
  private def notInConway(w: W): Unit = { w.nul; () }

  /** The hard-fork-combinator era mismatch: the era of the transaction, then the ledger's. */
  def eraMismatch(transactionEra: Int, transactionEraName: String): Bytes =
    CborOut.build { w =>
      w.array(2)
      w.array(2).uint(transactionEra).text(transactionEraName)
      w.array(2).uint(ConwayIndex).text("Conway")
    }

  // ---- helpers

  /** The ledger values of a failure are written by the Haskell state codecs, at the version the
    * reference writes the Conway era at; `W` is the failure's own writer.
    */
  private given ProtVer = Versions.eraLow(ShelleyBasedEra.SConway)
  import NodeOrders.given

  private[ntc] def put[A](w: W, a: A)(using codec: Codec[A]): W = w.raw(codec.encodeUnchecked(a))

  /** The codec of an item written by a function of the failure's writer. */
  private[ntc] def element[A](each: (W, A) => Unit): Codec[A] =
    Wire.writeOnly("a failure item")((a, out) => out.raw(CborOut.build(each(_, a))))

  private[ntc] def list[A](w: W, xs: Iterable[A])(each: (W, A) => Unit): W =
    put(w, xs.toVector)(using Wire.seqOf(element(each)))

  private[ntc] def set[A: Order](w: W, xs: Iterable[A])(each: (W, A) => Unit)(using ProtVer): W =
    put(w, xs.toSet)(using Wire.sortedSetOf(element(each)))

  private[ntc] def map[K: Order, V](w: W, entries: Map[K, V])(
      key: (W, K) => Unit,
      value: (W, V) => Unit
  )(using ProtVer): W = put(w, entries)(using Wire.sortedMapOf(element(key), element(value)))

  private[ntc] def maybe[A](w: W, a: Option[A])(each: (W, A) => Unit): W =
    put(w, a)(using Wire.strictMaybe(element(each)))

  private[ntc] def credential(w: W, c: Credential): W =
    put(w, c)(using HaskellStateCodecs.nodeCredential)
  private[ntc] def txIn(w: W, i: TxIn): W = put(w, i)(using UtxoCodecs.txIn)
  private[ntc] def txOut(w: W, o: TxOut): W = put(w, o)(using UtxoCodecs.txOut)
  private[ntc] def rewardAccount(w: W, a: RewardAccount): W =
    put(w, a)(using HaskellStateCodecs.rewardAccount)
  private[ntc] def govActionId(w: W, i: GovActionId): W =
    put(w, i)(using HaskellStateCodecs.govActionId)
  private def govAction(w: W, a: GovAction): W = put(w, a)(using HaskellStateCodecs.govAction)
  private def proposalProcedure(w: W, p: Proposal): W =
    put(w, p)(using HaskellStateCodecs.proposal)
  private[ntc] def exUnits(w: W, e: ExUnits): W = put(w, e)(using Params.exUnits)

  private[ntc] def hash(w: W, h: Bytes): Unit = { w.bytes(h); () }

  private[ntc] def hashSet(w: W, hs: Set[Bytes])(using ProtVer): W = set(w, hs)(hash)

  private[ntc] def maybeHash(w: W, h: Option[Bytes]): W = maybe(w, h)((k, x) => k.bytes(x))

  /** A mismatch as a group: supplied, then expected. */
  private[ntc] def group[A](w: W, m: Mismatch[A])(each: (W, A) => Unit): Unit =
    each(w, m.supplied); each(w, m.expected)

  /** A mismatch the ledger writes in the reverse order. */
  private[ntc] def swapped[A](w: W, m: Mismatch[A])(each: (W, A) => Unit): Unit =
    each(w, m.expected); each(w, m.supplied)

  private[ntc] def coin(w: W, c: BigInt): Unit = { w.uint(c); () }
  private[ntc] def small(w: W, n: Int): Unit = { w.uint(n); () }

  private[ntc] def voter(w: W, v: Voter): W = v match
    case Voter.CommitteeHot(Credential.Key(h))    => w.array(2).uint(0).bytes(h)
    case Voter.CommitteeHot(Credential.Script(h)) => w.array(2).uint(1).bytes(h)
    case Voter.DRepVoter(Credential.Key(h))       => w.array(2).uint(2).bytes(h)
    case Voter.DRepVoter(Credential.Script(h))    => w.array(2).uint(3).bytes(h)
    case Voter.Pool(h)                            => w.array(2).uint(4).bytes(h)

  private def votes(w: W, vs: Vector[(Voter, GovActionId)]): W =
    list(w, vs) { case (k, (v, id)) =>
      k.array(2)
      voter(k, v)
      govActionId(k, id)
    }

  private[ntc] def protVer(w: W, v: (BigInt, BigInt)): Unit = {
    w.array(2).uint(v._1).uint(v._2); ()
  }

  // ---- LEDGER

  def ledger(w: W, f: PredicateFailure): Unit = conway(w, conwayForm(f))

  private def conway(w: W, f: PredicateFailure): Unit = f match
    case PredicateFailure.Utxow(u) => w.array(2).uint(1); utxow(w, u)
    case PredicateFailure.Certs(c) => w.array(2).uint(2); certs(w, c)
    case PredicateFailure.Gov(g)   => w.array(2).uint(3); gov(w, g)
    case PredicateFailure.WdrlNotDelegatedToDRep(hs) =>
      w.array(2).uint(4); list(w, hs)(hash)
    case PredicateFailure.TreasuryValueMismatch(m) =>
      w.array(3).uint(5); swapped(w, m)(coin)
    case PredicateFailure.TxRefScriptsSizeTooBig(m) =>
      w.array(3).uint(6); group(w, m)(small)
    case PredicateFailure.Mempool(text) => w.array(2).uint(7).text(text.message)
    case PredicateFailure.WithdrawalsMissingAccounts(ws) =>
      w.array(2).uint(8)
      map(w, ws)(rewardAccount, coin)
    case PredicateFailure.IncompleteWithdrawals(ms) =>
      w.array(2).uint(9)
      map(w, ms)(
        rewardAccount,
        (k, m) => { k.array(2); group(k, m)(coin) }
      )

  // ---- UTXOW

  private def utxow(w: W, f: UtxowFailure): Unit = f match
    case UtxowFailure.Utxo(u)                        => w.array(2).uint(0); utxo(w, u)
    case UtxowFailure.InvalidWitnesses(keys)         => w.array(2).uint(1); list(w, keys)(hash)
    case UtxowFailure.MissingVKeyWitnesses(hs)       => w.array(2).uint(2); hashSet(w, hs)
    case UtxowFailure.MissingScriptWitnesses(hs)     => w.array(2).uint(3); hashSet(w, hs)
    case UtxowFailure.ScriptWitnessNotValidating(hs) => w.array(2).uint(4); hashSet(w, hs)
    case UtxowFailure.MissingTxBodyMetadataHash(h)   => w.array(2).uint(5).bytes(h)
    case UtxowFailure.MissingTxMetadata(h)           => w.array(2).uint(6).bytes(h)
    case UtxowFailure.ConflictingMetadataHash(m) =>
      w.array(3).uint(7); group(w, m)((k, h) => k.bytes(h))
    case UtxowFailure.InvalidMetadata               => w.array(1).uint(8)
    case UtxowFailure.ExtraneousScriptWitnesses(hs) => w.array(2).uint(9); hashSet(w, hs)
    case UtxowFailure.MissingRedeemers(ps) =>
      w.array(2).uint(10)
      list(w, ps) { case (k, (purpose, script)) =>
        k.array(2); item(k, purpose); k.bytes(script)
      }
    case UtxowFailure.MissingRequiredDatums(missing, received) =>
      w.array(3).uint(11); hashSet(w, missing); hashSet(w, received)
    case UtxowFailure.NotAllowedSupplementalDatums(unallowed, acceptable) =>
      w.array(3).uint(12); hashSet(w, unallowed); hashSet(w, acceptable)
    case UtxowFailure.PPViewHashesDontMatch(m) =>
      w.array(3).uint(13); group(w, m)(maybeHash(_, _))
    case UtxowFailure.UnspendableUTxONoDatumHash(ins) =>
      w.array(2).uint(14); set(w, ins)(txIn)
    case UtxowFailure.ExtraRedeemers(ps) =>
      w.array(2).uint(15)
      list(w, ps) { case (k, (tag, ix)) => k.array(2).uint(tag).uint(ix) }
    case UtxowFailure.MalformedScriptWitnesses(hs)  => w.array(2).uint(16); hashSet(w, hs)
    case UtxowFailure.MalformedReferenceScripts(hs) => w.array(2).uint(17); hashSet(w, hs)
    case UtxowFailure.MIRInsufficientGenesisSigs(_) => notInConway(w)

  /** A Plutus purpose as the ledger names it with the item it applies to (`PlutusPurpose AsItem`):
    * `[tag, item]`. A certifying purpose carries the certificate, which is not re-encoded here.
    */
  private[ntc] def item(w: W, p: Purpose): Unit = p match
    case Purpose.Spending(_, input, _)  => w.array(2).uint(0); txIn(w, input)
    case Purpose.Minting(_, policy)     => w.array(2).uint(1).bytes(policy)
    case Purpose.Certifying(_, _)       => w.array(2).uint(2); w.nul
    case Purpose.Rewarding(_, account)  => w.array(2).uint(3); rewardAccount(w, account)
    case Purpose.Voting(_, v)           => w.array(2).uint(4); voter(w, v)
    case Purpose.Proposing(_, proposal) => w.array(2).uint(5); proposalProcedure(w, proposal)

  // ---- UTXO

  private[ntc] def value(w: W, v: Value): Unit = { put(w, v)(using UtxoCodecs.value); () }

  private def utxo(w: W, f: UtxoFailure): Unit = f match
    case UtxoFailure.Utxos(u)       => w.array(2).uint(0); utxos(w, u)
    case UtxoFailure.BadInputs(ins) => w.array(2).uint(1); set(w, ins)(txIn)
    case UtxoFailure.OutsideValidityInterval(notBefore, before, slot) =>
      w.array(3).uint(2)
      w.array(2)
      maybe(w, notBefore)((k, s) => k.uint(s))
      maybe(w, before)((k, s) => k.uint(s))
      w.uint(slot)
    case UtxoFailure.MaxTxSize(m)         => w.array(3).uint(3); group(w, m)(coin)
    case UtxoFailure.InputSetEmpty        => w.array(1).uint(4)
    case UtxoFailure.FeeTooSmall(m)       => w.array(3).uint(5); swapped(w, m)(coin)
    case UtxoFailure.ValueNotConserved(m) => w.array(3).uint(6); group(w, m)(value)
    case UtxoFailure.WrongNetwork(expected, addresses) =>
      w.array(3).uint(7).uint(expected)
      set(w, addresses)((k, a) => k.bytes(a))
    case UtxoFailure.WrongNetworkWithdrawal(expected, accounts) =>
      w.array(3).uint(8).uint(expected)
      set(w, accounts)(rewardAccount)
    case UtxoFailure.OutputTooSmall(outs)            => w.array(2).uint(9); list(w, outs)(txOut)
    case UtxoFailure.OutputBootAddrAttrsTooBig(outs) => w.array(2).uint(10); list(w, outs)(txOut)
    case UtxoFailure.OutputTooBig(outs) =>
      w.array(2).uint(11)
      list(w, outs) { case (k, (size, max, out)) =>
        k.array(3).uint(size).uint(max); txOut(k, out)
      }
    case UtxoFailure.InsufficientCollateral(balance, required) =>
      w.array(3).uint(12).int(balance).uint(required)
    case UtxoFailure.ScriptsNotPaid(outs) =>
      w.array(2).uint(13)
      map(w, outs)(txIn, txOut)
    case UtxoFailure.ExUnitsTooBig(m) =>
      w.array(3).uint(14); swapped(w, m)(exUnits(_, _))
    case UtxoFailure.CollateralContainsNonAda(v) => w.array(2).uint(15); value(w, v)
    case UtxoFailure.WrongNetworkInTxBody(m)     => w.array(3).uint(16); swapped(w, m)(small)
    case UtxoFailure.OutsideForecast(slot)       => w.array(2).uint(17).uint(slot)
    case UtxoFailure.TooManyCollateralInputs(m)  => w.array(3).uint(18); swapped(w, m)(small)
    case UtxoFailure.NoCollateralInputs          => w.array(1).uint(19)
    case UtxoFailure.IncorrectTotalCollateralField(provided, declared) =>
      w.array(3).uint(20).int(provided).uint(declared)
    case UtxoFailure.BabbageOutputTooSmall(outs) =>
      w.array(2).uint(21)
      list(w, outs) { case (k, (out, min)) => k.array(2); txOut(k, out); k.uint(min) }
    case UtxoFailure.BabbageNonDisjointRefInputs(ins) =>
      w.array(2).uint(22); list(w, ins)(txIn)
    case UtxoFailure.ExpiredUTxO(_) | UtxoFailure.Update(_) => notInConway(w)

  private[ntc] def utxos(w: W, f: UtxosFailure)(using ProtVer): Unit = f match
    case UtxosFailure.ValidationTagMismatch(isValid, failures) =>
      w.array(3).uint(0).bool(isValid)
      failures match
        case None => w.array(1).uint(0)
        case Some(fs) =>
          w.array(2).uint(1)
          list(w, fs) { (k, failure) =>
            k.array(3).uint(1).text(failure.cause.message).bytes(failure.reconstructionDetail)
          }
    case UtxosFailure.CollectErrors(errors) =>
      ContextFailureCbor.utxos(errors).fold(notInConway(w))(raw => { w.raw(raw); () })

  // ---- CERTS, CERT, DELEG, POOL, GOVCERT

  private def certs(w: W, f: CertsFailure): Unit = f match
    case CertsFailure.WithdrawalsNotInRewards(ws) =>
      w.array(2).uint(0)
      map(w, ws)(rewardAccount, coin)
    case CertsFailure.Cert(c) =>
      w.array(2).uint(1)
      c match
        case CertFailure.Deleg(d)   => w.array(2).uint(1); deleg(w, d)
        case CertFailure.Pool(p)    => w.array(2).uint(2); pool(w, p)
        case CertFailure.GovCert(g) => w.array(2).uint(3); govCert(w, g)

  private def deleg(w: W, f: DelegFailure): Unit = f match
    case DelegFailure.IncorrectDeposit(c)                 => w.array(2).uint(1).uint(c)
    case DelegFailure.StakeKeyRegistered(c)               => w.array(2).uint(2); credential(w, c)
    case DelegFailure.StakeKeyNotRegistered(c)            => w.array(2).uint(3); credential(w, c)
    case DelegFailure.StakeKeyHasNonZeroAccountBalance(c) => w.array(2).uint(4).uint(c)
    case DelegFailure.DelegateeDRepNotRegistered(c)       => w.array(2).uint(5); credential(w, c)
    case DelegFailure.DelegateeStakePoolNotRegistered(p)  => w.array(2).uint(6).bytes(p)
    case DelegFailure.DepositIncorrect(m) => w.array(2).uint(7).array(2); group(w, m)(coin)
    case DelegFailure.RefundIncorrect(m)  => w.array(2).uint(8).array(2); group(w, m)(coin)
    case DelegFailure.StakeKeyAlreadyRegistered(_) | DelegFailure.StakeDelegationImpossible(_) |
        DelegFailure.WrongCertificateType | DelegFailure.GenesisKeyNotInMapping(_) |
        DelegFailure.DuplicateGenesisDelegate(_) | DelegFailure.DuplicateGenesisVRF(_) |
        DelegFailure.InsufficientForInstantaneousRewards(_, _) |
        DelegFailure.MIRCertificateTooLateinEpoch(_) | DelegFailure.MIRTransferNotCurrentlyAllowed |
        DelegFailure.MIRNegativesNotCurrentlyAllowed | DelegFailure.InsufficientForTransfer(_, _) |
        DelegFailure.MIRProducesNegativeUpdate | DelegFailure.MIRNegativeTransfer(_, _) |
        DelegFailure.DelegateeNotRegistered(_) =>
      notInConway(w)

  private[ntc] def pool(w: W, f: PoolFailure)(using ProtVer): Unit = f match
    case PoolFailure.StakePoolNotRegisteredOnKey(p) => w.array(2).uint(0).bytes(p)
    case PoolFailure.StakePoolRetirementWrongEpoch(current, latest) =>
      w.array(4).uint(1).uint(current.expected).uint(latest.supplied).uint(latest.expected)
    case PoolFailure.StakePoolCostTooLow(m) => w.array(3).uint(3); group(w, m)(coin)
    case PoolFailure.WrongNetworkPool(m, p) =>
      w.array(4).uint(4); swapped(w, m)(small); w.bytes(p)
    case PoolFailure.VRFKeyHashAlreadyRegistered(p, vrf) =>
      w.array(3).uint(6).bytes(p).bytes(vrf)

  private def govCert(w: W, f: GovCertFailure): Unit = f match
    case GovCertFailure.DRepAlreadyRegistered(c)          => w.array(2).uint(0); credential(w, c)
    case GovCertFailure.DRepNotRegistered(c)              => w.array(2).uint(1); credential(w, c)
    case GovCertFailure.DRepIncorrectDeposit(m)           => w.array(3).uint(2); group(w, m)(coin)
    case GovCertFailure.CommitteeHasPreviouslyResigned(c) => w.array(2).uint(3); credential(w, c)
    case GovCertFailure.DRepIncorrectRefund(m)            => w.array(3).uint(4); group(w, m)(coin)
    case GovCertFailure.CommitteeIsUnknown(c)             => w.array(2).uint(5); credential(w, c)

  // ---- GOV

  private def gov(w: W, f: GovFailure): Unit = f match
    case GovFailure.GovActionsDoNotExist(ids) =>
      w.array(2).uint(0); list(w, ids)(govActionId)
    case GovFailure.MalformedProposal(a) => w.array(2).uint(1); govAction(w, a)
    case GovFailure.ProposalProcedureNetworkIdMismatch(account, network) =>
      w.array(3).uint(2); rewardAccount(w, account); w.uint(network)
    case GovFailure.TreasuryWithdrawalsNetworkIdMismatch(accounts, network) =>
      w.array(3).uint(3); set(w, accounts)(rewardAccount); w.uint(network)
    case GovFailure.ProposalDepositIncorrect(m) => w.array(3).uint(4); group(w, m)(coin)
    case GovFailure.DisallowedVoters(vs)        => w.array(2).uint(5); votes(w, vs)
    case GovFailure.ConflictingCommitteeUpdate(members) =>
      w.array(2).uint(6); set(w, members)(credential)
    case GovFailure.ExpirationEpochTooSmall(members) =>
      w.array(2).uint(7)
      map(w, members)(credential, (k, e) => k.uint(e))
    case GovFailure.InvalidPrevGovActionId(p)    => w.array(2).uint(8); proposalProcedure(w, p)
    case GovFailure.VotingOnExpiredGovAction(vs) => w.array(2).uint(9); votes(w, vs)
    case GovFailure.ProposalCantFollow(previous, m) =>
      w.array(4).uint(10); maybe(w, previous)(govActionId); group(w, m)(protVer)
    case GovFailure.InvalidGuardrailsScriptHash(supplied, expected) =>
      w.array(3).uint(11); maybeHash(w, supplied); maybeHash(w, expected)
    case GovFailure.DisallowedProposalDuringBootstrap(p) =>
      w.array(2).uint(12); proposalProcedure(w, p)
    case GovFailure.DisallowedVotesDuringBootstrap(vs) => w.array(2).uint(13); votes(w, vs)
    case GovFailure.VotersDoNotExist(vs)       => w.array(2).uint(14); list(w, vs)(voter(_, _))
    case GovFailure.ZeroTreasuryWithdrawals(a) => w.array(2).uint(15); govAction(w, a)
    case GovFailure.ProposalReturnAccountDoesNotExist(a) =>
      w.array(2).uint(16); rewardAccount(w, a)
    case GovFailure.TreasuryWithdrawalReturnAccountsDoNotExist(as) =>
      w.array(2).uint(17); list(w, as)(rewardAccount)
    case GovFailure.UnelectedCommitteeVoters(cs) =>
      w.array(2).uint(18); list(w, cs)(credential)
