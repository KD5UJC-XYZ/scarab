// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.state.{ProtVer, ShelleyBasedEra}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellStateCodecs, NodeOrders, Versions}

import ContextFailureCbor.ContextEra
import FailureCbor.*

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
  /** The reason item sent with `MsgRejectTx` for a refused Conway transaction. A failure of a rule
    * the Conway ledger does not have (the update proposals, the MIR certificates, the time to live)
    * or whose payload this encoding does not write ([[inConway]]) is sent as the ledger's own
    * free-text failure, so that the client still shows the reason.
    */
  def applyTxError(failures: Vector[PredicateFailure]): Either[RefusalPresentationError, Bytes] =
    failures.traverse(prepareLedger).map { encoders =>
      CborOut.build { w =>
        w.array(1).array(2).uint(ConwayIndex)
        list(w, encoders)((k, encoder) => encoder.write(k))
      }
    }

  /** An unsupported era constructor has no encoding; a presentation defect retains its cause. */
  def applyTxError(
      era: Era,
      failures: Vector[PredicateFailure]
  ): Either[RefusalPresentationError, Option[Bytes]] = era match
    case Era.Conway => applyTxError(failures).map(Some(_))
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
    case UtxowFailure.MissingRedeemers(ps) =>
      ps.forall((purpose, _) => conwayPurpose(purpose).isDefined)
    case UtxowFailure.Utxo(UtxoFailure.Utxos(UtxosFailure.CollectErrors(errors))) =>
      ContextFailureCbor.utxos(ContextEra.Conway, errors).isDefined
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

  // ---- helpers

  /** The ledger values of a failure are written by the Haskell state codecs, at the version the
    * reference writes the Conway era at; `W` is the failure's own writer.
    */
  private given ProtVer = Versions.eraLow(ShelleyBasedEra.SConway)
  import NodeOrders.given

  private def govAction(w: W, a: GovAction): W = put(w, a)(using HaskellStateCodecs.govAction)

  private def votes(w: W, vs: Vector[(Voter, GovActionId)]): W =
    list(w, vs) { case (k, (v, id)) =>
      k.array(2)
      voter(k, v)
      govActionId(k, id)
    }

  // ---- LEDGER

  /** Only prepared encoders can write: every fallible rendering has already completed once. */
  private final case class Prepared(write: W => Unit)

  private def prepareLedger(f: PredicateFailure): Either[RefusalPresentationError, Prepared] =
    prepareConway(conwayForm(f))

  private[ntc] def ledger(f: PredicateFailure): Either[RefusalPresentationError, Bytes] =
    prepareLedger(f).map(encoder => CborOut.build(encoder.write))

  private def prepareConway(f: PredicateFailure): Either[RefusalPresentationError, Prepared] =
    f match
      case PredicateFailure.Utxow(u) =>
        prepareUtxow(u).map(child =>
          Prepared { w =>
            w.array(2).uint(1); child.write(w)
          }
        )
      case PredicateFailure.Certs(c) =>
        Right(Prepared { w =>
          w.array(2).uint(2); certs(w, c)
        })
      case PredicateFailure.Gov(g) =>
        Right(Prepared { w =>
          w.array(2).uint(3); gov(w, g)
        })
      case PredicateFailure.WdrlNotDelegatedToDRep(hs) =>
        Right(Prepared { w =>
          w.array(2).uint(4); list(w, hs)(hash)
        })
      case PredicateFailure.TreasuryValueMismatch(m) =>
        Right(Prepared { w =>
          w.array(3).uint(5); swapped(w, m)(coin)
        })
      case PredicateFailure.TxRefScriptsSizeTooBig(m) =>
        Right(Prepared { w =>
          w.array(3).uint(6); group(w, m)(int)
        })
      case PredicateFailure.Mempool(text) =>
        Right(Prepared { w =>
          w.array(2).uint(7).text(text.message)
        })
      case PredicateFailure.WithdrawalsMissingAccounts(ws) =>
        Right(Prepared { w =>
          w.array(2).uint(8)
          map(w, ws)(rewardAccount, coin)
        })
      case PredicateFailure.IncompleteWithdrawals(ms) =>
        Right(Prepared { w =>
          w.array(2).uint(9)
          map(w, ms)(
            rewardAccount,
            (k, m) => { k.array(2); group(k, m)(coin) }
          )
        })

  // ---- UTXOW

  private def prepareUtxow(f: UtxowFailure): Either[RefusalPresentationError, Prepared] = f match
    case UtxowFailure.Utxo(u) =>
      prepareUtxo(u).map(child =>
        Prepared { w =>
          w.array(2).uint(0); child.write(w)
        }
      )
    case UtxowFailure.InvalidWitnesses(keys) =>
      Right(Prepared { w =>
        w.array(2).uint(1); list(w, keys)(hash)
      })
    case UtxowFailure.MissingVKeyWitnesses(hs) =>
      Right(Prepared { w =>
        w.array(2).uint(2); hashSet(w, hs)
      })
    case UtxowFailure.MissingScriptWitnesses(hs) =>
      Right(Prepared { w =>
        w.array(2).uint(3); hashSet(w, hs)
      })
    case UtxowFailure.ScriptWitnessNotValidating(hs) =>
      Right(Prepared { w =>
        w.array(2).uint(4); hashSet(w, hs)
      })
    case UtxowFailure.MissingTxBodyMetadataHash(h) =>
      Right(Prepared { w =>
        w.array(2).uint(5).bytes(h)
      })
    case UtxowFailure.MissingTxMetadata(h) =>
      Right(Prepared { w =>
        w.array(2).uint(6).bytes(h)
      })
    case UtxowFailure.ConflictingMetadataHash(m) =>
      Right(Prepared { w =>
        w.array(3).uint(7); group(w, m)((k, h) => k.bytes(h))
      })
    case UtxowFailure.InvalidMetadata =>
      Right(Prepared { w =>
        w.array(1).uint(8)
      })
    case UtxowFailure.ExtraneousScriptWitnesses(hs) =>
      Right(Prepared { w =>
        w.array(2).uint(9); hashSet(w, hs)
      })
    case UtxowFailure.MissingRedeemers(ps) =>
      Right(Prepared { w =>
        ps
          .traverse((purpose, script) => conwayPurpose(purpose).map(_ -> script))
          .fold(notInConway(w)) { entries =>
            w.array(2).uint(10)
            list(w, entries) { case (k, (purpose, script)) =>
              k.array(2); k.raw(purpose); k.bytes(script)
            }
            ()
          }
      })
    case UtxowFailure.MissingRequiredDatums(missing, received) =>
      Right(Prepared { w =>
        w.array(3).uint(11); hashSet(w, missing); hashSet(w, received)
      })
    case UtxowFailure.NotAllowedSupplementalDatums(unallowed, acceptable) =>
      Right(Prepared { w =>
        w.array(3).uint(12); hashSet(w, unallowed); hashSet(w, acceptable)
      })
    case UtxowFailure.PPViewHashesDontMatch(m) =>
      Right(Prepared { w =>
        w.array(3).uint(13); group(w, m)(maybeHash(_, _))
      })
    case UtxowFailure.UnspendableUTxONoDatumHash(ins) =>
      Right(Prepared { w =>
        w.array(2).uint(14); set(w, ins)(txIn)
      })
    case UtxowFailure.ExtraRedeemers(ps) =>
      Right(Prepared { w =>
        w.array(2).uint(15)
        list(w, ps) { case (k, (tag, ix)) => k.array(2).uint(tag).uint(ix) }
      })
    case UtxowFailure.MalformedScriptWitnesses(hs) =>
      Right(Prepared { w =>
        w.array(2).uint(16); hashSet(w, hs)
      })
    case UtxowFailure.MalformedReferenceScripts(hs) =>
      Right(Prepared { w =>
        w.array(2).uint(17); hashSet(w, hs)
      })
    case UtxowFailure.ScriptIntegrityHashMismatch(m, expected) =>
      Right(Prepared { w =>
        w.array(3).uint(18); w.array(2); group(w, m)(maybeHash(_, _)); maybeHash(w, expected); ()
      })
    case UtxowFailure.MIRInsufficientGenesisSigs(_) =>
      Right(Prepared { w =>
        notInConway(w)
      })

  /** The Conway `[tag, item]` of a purpose, or None when its certificate has no encoding. */
  private def conwayPurpose(p: Purpose): Option[Bytes] = p match
    case Purpose.Certifying(_, _) => ContextFailureCbor.purpose(ContextEra.Conway, p)
    case other                    => Some(CborOut.build(item(_, other)))

  // ---- UTXO

  private def prepareUtxo(f: UtxoFailure): Either[RefusalPresentationError, Prepared] = f match
    case UtxoFailure.Utxos(u) =>
      prepareUtxos(u).map(child =>
        Prepared { w =>
          w.array(2).uint(0); child.write(w)
        }
      )
    case UtxoFailure.BadInputs(ins) =>
      Right(Prepared { w =>
        w.array(2).uint(1); set(w, ins)(txIn)
      })
    case UtxoFailure.OutsideValidityInterval(notBefore, before, slot) =>
      Right(Prepared { w =>
        w.array(3).uint(2)
        w.array(2)
        maybe(w, notBefore)((k, s) => k.uint(s))
        maybe(w, before)((k, s) => k.uint(s))
        w.uint(slot)
      })
    case UtxoFailure.MaxTxSize(m) =>
      Right(Prepared { w =>
        w.array(3).uint(3); group(w, m)(coin)
      })
    case UtxoFailure.InputSetEmpty =>
      Right(Prepared { w =>
        w.array(1).uint(4)
      })
    case UtxoFailure.FeeTooSmall(m) =>
      Right(Prepared { w =>
        w.array(3).uint(5); swapped(w, m)(coin)
      })
    case UtxoFailure.ValueNotConserved(m) =>
      Right(Prepared { w =>
        w.array(3).uint(6); group(w, m)(value)
      })
    case UtxoFailure.WrongNetwork(expected, addresses) =>
      Right(Prepared { w =>
        w.array(3).uint(7).uint(expected)
        set(w, addresses)((k, a) => k.bytes(a))
      })
    case UtxoFailure.WrongNetworkWithdrawal(expected, accounts) =>
      Right(Prepared { w =>
        w.array(3).uint(8).uint(expected)
        set(w, accounts)(rewardAccount)
      })
    case UtxoFailure.OutputTooSmall(outs) =>
      Right(Prepared { w =>
        w.array(2).uint(9); list(w, outs)(txOut)
      })
    case UtxoFailure.OutputBootAddrAttrsTooBig(outs) =>
      Right(Prepared { w =>
        w.array(2).uint(10); list(w, outs)(txOut)
      })
    case UtxoFailure.OutputTooBig(outs) =>
      Right(Prepared { w =>
        w.array(2).uint(11)
        list(w, outs) { case (k, (size, max, out)) =>
          k.array(3).uint(size).uint(max); txOut(k, out)
        }
      })
    case UtxoFailure.InsufficientCollateral(balance, required) =>
      Right(Prepared { w =>
        w.array(3).uint(12).int(balance).uint(required)
      })
    case UtxoFailure.ScriptsNotPaid(outs) =>
      Right(Prepared { w =>
        w.array(2).uint(13)
        map(w, outs)(txIn, txOut)
      })
    case UtxoFailure.ExUnitsTooBig(m) =>
      Right(Prepared { w =>
        w.array(3).uint(14); swapped(w, m)(exUnits(_, _))
      })
    case UtxoFailure.CollateralContainsNonAda(v) =>
      Right(Prepared { w =>
        w.array(2).uint(15); value(w, v)
      })
    case UtxoFailure.WrongNetworkInTxBody(m) =>
      Right(Prepared { w =>
        w.array(3).uint(16); swapped(w, m)(small)
      })
    case UtxoFailure.OutsideForecast(slot) =>
      Right(Prepared { w =>
        w.array(2).uint(17).uint(slot)
      })
    case UtxoFailure.TooManyCollateralInputs(m) =>
      Right(Prepared { w =>
        w.array(3).uint(18); swapped(w, m)(small)
      })
    case UtxoFailure.NoCollateralInputs =>
      Right(Prepared { w =>
        w.array(1).uint(19)
      })
    case UtxoFailure.IncorrectTotalCollateralField(provided, declared) =>
      Right(Prepared { w =>
        w.array(3).uint(20).int(provided).uint(declared)
      })
    case UtxoFailure.BabbageOutputTooSmall(outs) =>
      Right(Prepared { w =>
        w.array(2).uint(21)
        list(w, outs) { case (k, (out, min)) => k.array(2); txOut(k, out); k.uint(min) }
      })
    case UtxoFailure.BabbageNonDisjointRefInputs(ins) =>
      Right(Prepared { w =>
        w.array(2).uint(22); list(w, ins)(txIn)
      })
    case UtxoFailure.ExpiredUTxO(_) | UtxoFailure.Update(_) =>
      Right(Prepared { w =>
        notInConway(w)
      })

  private[ntc] def utxos(f: UtxosFailure): Either[RefusalPresentationError, Bytes] =
    prepareUtxos(f).map(encoder => CborOut.build(encoder.write))

  private def prepareUtxos(f: UtxosFailure): Either[RefusalPresentationError, Prepared] = f match
    case m: UtxosFailure.ValidationTagMismatch => validationTagMismatch(m).map(Prepared(_))
    case UtxosFailure.CollectErrors(errors) =>
      Right(Prepared { w =>
        ContextFailureCbor
          .utxos(ContextEra.Conway, errors)
          .fold(notInConway(w))(raw => { w.raw(raw); () })
      })

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
