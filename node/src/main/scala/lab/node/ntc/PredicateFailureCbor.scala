// SPDX-License-Identifier: Apache-2.0
package lab.node.ntc

import lab.cbor.Bytes
import lab.node.ledger.*
import lab.node.ntc.LedgerEncoders as E

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
  private type W = CborOut

  val ConwayIndex = 6

  /** The reason item sent with `MsgRejectTx` for a refused Conway transaction. */
  def applyTxError(failures: Vector[PredicateFailure]): Bytes =
    CborOut.build { w =>
      w.array(1).array(2).uint(ConwayIndex)
      list(w, failures)(ledger)
    }

  /** The hard-fork-combinator era mismatch: the era of the transaction, then the ledger's. */
  def eraMismatch(transactionEra: Int, transactionEraName: String): Bytes =
    CborOut.build { w =>
      w.array(2)
      w.array(2).uint(transactionEra).text(transactionEraName)
      w.array(2).uint(ConwayIndex).text("Conway")
    }

  // ---- helpers

  private def list[A](w: W, xs: Iterable[A])(each: (W, A) => Unit): W =
    E.listHead(w, xs.size)
    xs.foreach(each(w, _))
    E.end(w, xs.size)

  private def set[A](w: W, xs: Iterable[A], ordering: Ordering[A])(each: (W, A) => Unit): W =
    E.set(w, xs, ordering)(each)

  private def hash(w: W, h: Bytes): Unit = { w.bytes(h); () }

  private def hashSet(w: W, hs: Set[Bytes]): W = set(w, hs, E.bytesOrdering)(hash)

  private def maybeHash(w: W, h: Option[Bytes]): W = E.maybe(w, h)((k, x) => k.bytes(x))

  /** A mismatch as a group: supplied, then expected. */
  private def group[A](w: W, m: Mismatch[A])(each: (W, A) => Unit): Unit =
    each(w, m.supplied); each(w, m.expected)

  /** A mismatch the ledger writes in the reverse order. */
  private def swapped[A](w: W, m: Mismatch[A])(each: (W, A) => Unit): Unit =
    each(w, m.expected); each(w, m.supplied)

  private def coin(w: W, c: BigInt): Unit = { w.uint(c); () }
  private def small(w: W, n: Int): Unit = { w.uint(n); () }

  private def voter(w: W, v: Voter): W = v match
    case Voter.CommitteeHot(Credential.Key(h))    => w.array(2).uint(0).bytes(h)
    case Voter.CommitteeHot(Credential.Script(h)) => w.array(2).uint(1).bytes(h)
    case Voter.DRepVoter(Credential.Key(h))       => w.array(2).uint(2).bytes(h)
    case Voter.DRepVoter(Credential.Script(h))    => w.array(2).uint(3).bytes(h)
    case Voter.Pool(h)                            => w.array(2).uint(4).bytes(h)

  private def votes(w: W, vs: Vector[(Voter, GovActionId)]): W =
    list(w, vs) { case (k, (v, id)) =>
      k.array(2)
      voter(k, v)
      E.govActionId(k, id)
    }

  private def protVer(w: W, v: (BigInt, BigInt)): Unit = { w.array(2).uint(v._1).uint(v._2); () }

  private val txInOrdering = E.txInOrdering
  private val accountOrdering = E.rewardAccountOrdering

  // ---- LEDGER

  def ledger(w: W, f: PredicateFailure): Unit = f match
    case PredicateFailure.Utxow(u)   => w.array(2).uint(1); utxow(w, u)
    case PredicateFailure.Certs(c)   => w.array(2).uint(2); certs(w, c)
    case PredicateFailure.Gov(g)     => w.array(2).uint(3); gov(w, g)
    case PredicateFailure.WdrlNotDelegatedToDRep(hs) =>
      w.array(2).uint(4); list(w, hs)(hash)
    case PredicateFailure.TreasuryValueMismatch(m) =>
      w.array(3).uint(5); swapped(w, m)(coin)
    case PredicateFailure.TxRefScriptsSizeTooBig(m) =>
      w.array(3).uint(6); group(w, m)(small)
    case PredicateFailure.Mempool(text) => w.array(2).uint(7).text(text)
    case PredicateFailure.WithdrawalsMissingAccounts(ws) =>
      w.array(2).uint(8)
      E.map(w, ws, accountOrdering)(E.rewardAccount, coin)
    case PredicateFailure.IncompleteWithdrawals(ms) =>
      w.array(2).uint(9)
      E.map(w, ms, accountOrdering)(
        E.rewardAccount,
        (k, m) => { k.array(2); group(k, m)(coin) }
      )
    case PredicateFailure.Unmapped(rule, detail) => w.array(2).uint(7).text(s"$rule: $detail")

  // ---- UTXOW

  private def utxow(w: W, f: UtxowFailure): Unit = f match
    case UtxowFailure.Utxo(u)               => w.array(2).uint(0); utxo(w, u)
    case UtxowFailure.InvalidWitnesses(keys) => w.array(2).uint(1); list(w, keys)(hash)
    case UtxowFailure.MissingVKeyWitnesses(hs)   => w.array(2).uint(2); hashSet(w, hs)
    case UtxowFailure.MissingScriptWitnesses(hs) => w.array(2).uint(3); hashSet(w, hs)
    case UtxowFailure.ScriptWitnessNotValidating(hs) => w.array(2).uint(4); hashSet(w, hs)
    case UtxowFailure.MissingTxBodyMetadataHash(h) => w.array(2).uint(5).bytes(h)
    case UtxowFailure.MissingTxMetadata(h)         => w.array(2).uint(6).bytes(h)
    case UtxowFailure.ConflictingMetadataHash(m) =>
      w.array(3).uint(7); group(w, m)((k, h) => k.bytes(h))
    case UtxowFailure.InvalidMetadata => w.array(1).uint(8)
    case UtxowFailure.ExtraneousScriptWitnesses(hs) => w.array(2).uint(9); hashSet(w, hs)
    case UtxowFailure.MissingRequiredDatums(missing, received) =>
      w.array(3).uint(11); hashSet(w, missing); hashSet(w, received)
    case UtxowFailure.NotAllowedSupplementalDatums(unallowed, acceptable) =>
      w.array(3).uint(12); hashSet(w, unallowed); hashSet(w, acceptable)
    case UtxowFailure.PPViewHashesDontMatch(m) =>
      w.array(3).uint(13); group(w, m)(maybeHash(_, _))
    case UtxowFailure.UnspendableUTxONoDatumHash(ins) =>
      w.array(2).uint(14); set(w, ins, txInOrdering)(E.txIn)
    case UtxowFailure.ExtraRedeemers(ps) =>
      w.array(2).uint(15)
      list(w, ps) { case (k, (tag, ix)) => k.array(2).uint(tag).uint(ix) }
    case UtxowFailure.MalformedScriptWitnesses(hs)  => w.array(2).uint(16); hashSet(w, hs)
    case UtxowFailure.MalformedReferenceScripts(hs) => w.array(2).uint(17); hashSet(w, hs)

  // ---- UTXO

  private def value(w: W, v: Value): Unit = { E.value(w, v); () }

  private def utxo(w: W, f: UtxoFailure): Unit = f match
    case UtxoFailure.Utxos(u) => w.array(2).uint(0); utxos(w, u)
    case UtxoFailure.BadInputs(ins) => w.array(2).uint(1); set(w, ins, txInOrdering)(E.txIn)
    case UtxoFailure.OutsideValidityInterval(notBefore, before, slot) =>
      w.array(3).uint(2)
      w.array(2)
      E.maybe(w, notBefore)((k, s) => k.uint(s))
      E.maybe(w, before)((k, s) => k.uint(s))
      w.uint(slot)
    case UtxoFailure.MaxTxSize(m) => w.array(3).uint(3); group(w, m)(coin)
    case UtxoFailure.InputSetEmpty => w.array(1).uint(4)
    case UtxoFailure.FeeTooSmall(m) => w.array(3).uint(5); swapped(w, m)(coin)
    case UtxoFailure.ValueNotConserved(m) => w.array(3).uint(6); group(w, m)(value)
    case UtxoFailure.WrongNetwork(expected, addresses) =>
      w.array(3).uint(7).uint(expected)
      set(w, addresses, E.bytesOrdering)((k, a) => k.bytes(a))
    case UtxoFailure.WrongNetworkWithdrawal(expected, accounts) =>
      w.array(3).uint(8).uint(expected)
      set(w, accounts, accountOrdering)(E.rewardAccount)
    case UtxoFailure.OutputTooSmall(outs) => w.array(2).uint(9); list(w, outs)(E.txOut)
    case UtxoFailure.OutputBootAddrAttrsTooBig(outs) => w.array(2).uint(10); list(w, outs)(E.txOut)
    case UtxoFailure.OutputTooBig(outs) =>
      w.array(2).uint(11)
      list(w, outs) { case (k, (size, max, out)) =>
        k.array(3).uint(size).uint(max); E.txOut(k, out)
      }
    case UtxoFailure.InsufficientCollateral(balance, required) =>
      w.array(3).uint(12).int(balance).uint(required)
    case UtxoFailure.ScriptsNotPaid(outs) =>
      w.array(2).uint(13)
      E.map(w, outs.toVector, txInOrdering)(E.txIn, E.txOut)
    case UtxoFailure.ExUnitsTooBig(m) =>
      w.array(3).uint(14); swapped(w, m)(E.exUnits(_, _))
    case UtxoFailure.CollateralContainsNonAda(v) => w.array(2).uint(15); value(w, v)
    case UtxoFailure.WrongNetworkInTxBody(m) => w.array(3).uint(16); swapped(w, m)(small)
    case UtxoFailure.OutsideForecast(slot) => w.array(2).uint(17).uint(slot)
    case UtxoFailure.TooManyCollateralInputs(m) => w.array(3).uint(18); swapped(w, m)(small)
    case UtxoFailure.NoCollateralInputs => w.array(1).uint(19)
    case UtxoFailure.IncorrectTotalCollateralField(provided, declared) =>
      w.array(3).uint(20).int(provided).uint(declared)
    case UtxoFailure.BabbageOutputTooSmall(outs) =>
      w.array(2).uint(21)
      list(w, outs) { case (k, (out, min)) => k.array(2); E.txOut(k, out); k.uint(min) }
    case UtxoFailure.BabbageNonDisjointRefInputs(ins) =>
      w.array(2).uint(22); list(w, ins)(E.txIn)

  private def utxos(w: W, f: UtxosFailure): Unit = f match
    case UtxosFailure.ValidationTagMismatch(isValid, failures) =>
      w.array(3).uint(0).bool(isValid)
      failures match
        case None => w.array(1).uint(0)
        case Some(fs) =>
          w.array(2).uint(1)
          list(w, fs) { case (k, (text, detail)) => k.array(3).uint(1).text(text).bytes(detail) }

  // ---- CERTS, CERT, DELEG, POOL, GOVCERT

  private def certs(w: W, f: CertsFailure): Unit = f match
    case CertsFailure.WithdrawalsNotInRewards(ws) =>
      w.array(2).uint(0)
      E.map(w, ws, accountOrdering)(E.rewardAccount, coin)
    case CertsFailure.Cert(c) =>
      w.array(2).uint(1)
      c match
        case CertFailure.Deleg(d)    => w.array(2).uint(1); deleg(w, d)
        case CertFailure.Pool(p)     => w.array(2).uint(2); pool(w, p)
        case CertFailure.GovCert(g)  => w.array(2).uint(3); govCert(w, g)

  private def deleg(w: W, f: DelegFailure): Unit = f match
    case DelegFailure.IncorrectDeposit(c)            => w.array(2).uint(1).uint(c)
    case DelegFailure.StakeKeyRegistered(c)          => w.array(2).uint(2); E.credential(w, c)
    case DelegFailure.StakeKeyNotRegistered(c)       => w.array(2).uint(3); E.credential(w, c)
    case DelegFailure.StakeKeyHasNonZeroAccountBalance(c) => w.array(2).uint(4).uint(c)
    case DelegFailure.DelegateeDRepNotRegistered(c)  => w.array(2).uint(5); E.credential(w, c)
    case DelegFailure.DelegateeStakePoolNotRegistered(p) => w.array(2).uint(6).bytes(p)
    case DelegFailure.DepositIncorrect(m) => w.array(2).uint(7).array(2); group(w, m)(coin)
    case DelegFailure.RefundIncorrect(m)  => w.array(2).uint(8).array(2); group(w, m)(coin)

  private def pool(w: W, f: PoolFailure): Unit = f match
    case PoolFailure.StakePoolNotRegisteredOnKey(p) => w.array(2).uint(0).bytes(p)
    case PoolFailure.StakePoolRetirementWrongEpoch(current, latest) =>
      w.array(4).uint(1).uint(current.expected).uint(latest.supplied).uint(latest.expected)
    case PoolFailure.StakePoolCostTooLow(m) => w.array(3).uint(3); group(w, m)(coin)
    case PoolFailure.WrongNetworkPool(m, p) =>
      w.array(4).uint(4); swapped(w, m)(small); w.bytes(p)
    case PoolFailure.VRFKeyHashAlreadyRegistered(p, vrf) =>
      w.array(3).uint(6).bytes(p).bytes(vrf)

  private def govCert(w: W, f: GovCertFailure): Unit = f match
    case GovCertFailure.DRepAlreadyRegistered(c) => w.array(2).uint(0); E.credential(w, c)
    case GovCertFailure.DRepNotRegistered(c)     => w.array(2).uint(1); E.credential(w, c)
    case GovCertFailure.DRepIncorrectDeposit(m)  => w.array(3).uint(2); group(w, m)(coin)
    case GovCertFailure.CommitteeHasPreviouslyResigned(c) => w.array(2).uint(3); E.credential(w, c)
    case GovCertFailure.DRepIncorrectRefund(m)   => w.array(3).uint(4); group(w, m)(coin)
    case GovCertFailure.CommitteeIsUnknown(c)    => w.array(2).uint(5); E.credential(w, c)

  // ---- GOV

  private def gov(w: W, f: GovFailure): Unit = f match
    case GovFailure.GovActionsDoNotExist(ids) =>
      w.array(2).uint(0); list(w, ids)(E.govActionId)
    case GovFailure.MalformedProposal(a) => w.array(2).uint(1); E.govAction(w, a)
    case GovFailure.ProposalProcedureNetworkIdMismatch(account, network) =>
      w.array(3).uint(2); E.rewardAccount(w, account); w.uint(network)
    case GovFailure.TreasuryWithdrawalsNetworkIdMismatch(accounts, network) =>
      w.array(3).uint(3); set(w, accounts, accountOrdering)(E.rewardAccount); w.uint(network)
    case GovFailure.ProposalDepositIncorrect(m) => w.array(3).uint(4); group(w, m)(coin)
    case GovFailure.DisallowedVoters(vs) => w.array(2).uint(5); votes(w, vs)
    case GovFailure.ConflictingCommitteeUpdate(members) =>
      w.array(2).uint(6); set(w, members, E.credentialOrdering)(E.credential)
    case GovFailure.ExpirationEpochTooSmall(members) =>
      w.array(2).uint(7)
      E.map(w, members.toVector, E.credentialOrdering)(E.credential, (k, e) => k.uint(e))
    case GovFailure.InvalidPrevGovActionId(p) => w.array(2).uint(8); E.proposalProcedure(w, p)
    case GovFailure.VotingOnExpiredGovAction(vs) => w.array(2).uint(9); votes(w, vs)
    case GovFailure.ProposalCantFollow(previous, m) =>
      w.array(4).uint(10); E.maybe(w, previous)(E.govActionId); group(w, m)(protVer)
    case GovFailure.InvalidGuardrailsScriptHash(supplied, expected) =>
      w.array(3).uint(11); maybeHash(w, supplied); maybeHash(w, expected)
    case GovFailure.DisallowedProposalDuringBootstrap(p) =>
      w.array(2).uint(12); E.proposalProcedure(w, p)
    case GovFailure.DisallowedVotesDuringBootstrap(vs) => w.array(2).uint(13); votes(w, vs)
    case GovFailure.VotersDoNotExist(vs) => w.array(2).uint(14); list(w, vs)(voter(_, _))
    case GovFailure.ZeroTreasuryWithdrawals(a) => w.array(2).uint(15); E.govAction(w, a)
    case GovFailure.ProposalReturnAccountDoesNotExist(a) =>
      w.array(2).uint(16); E.rewardAccount(w, a)
    case GovFailure.TreasuryWithdrawalReturnAccountsDoNotExist(as) =>
      w.array(2).uint(17); list(w, as)(E.rewardAccount)
    case GovFailure.UnelectedCommitteeVoters(cs) =>
      w.array(2).uint(18); list(w, cs)(E.credential)
