// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.state.{ProtVer, ShelleyBasedEra}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.{NodeOrders, Versions}

import ContextFailureCbor.ContextEra
import PredicateFailureCbor.*

/** The predicate-failure CBOR of the eras from Shelley to Babbage (`EncCBOR` of
  * `ShelleyLedgerPredFailure` and the rules it nests), as [[PredicateFailureCbor]] has it for
  * Conway. The tags and the nesting are those of the reference's instances, which differ from
  * Conway's and from era to era:
  *
  *   - LEDGER (`Shelley/Rules/Ledger.hs:210-221`, every era up to Babbage): UTXOW 0, DELEGS 1, the
  *     withdrawals that name no account 2 and the incomplete ones 3. DELEGS (`Delegs.hs`) holds
  *     DELPL at 1, and DELPL (`Delpl.hs:136-150`) POOL at 0 and DELEG at 1.
  *   - UTXOW: `Shelley/Rules/Utxow.hs:170-209`; Alonzo (`Alonzo/Rules/Utxow.hs:165-176`) holds the
  *     Shelley constructors at 0; Babbage (`Babbage/Rules/Utxow.hs:149-157`) holds Alonzo's at 1
  *     and UTXO at 2.
  *   - UTXO: `Shelley/Rules/Utxo.hs:226-240`; Allegra and Mary (`Allegra/Rules/Utxo.hs:335-351`)
  *     add the validity interval and the output size; Alonzo (`Alonzo/Rules/Utxo.hs:646-681`) adds
  *     UTXOS at 7 and the collateral; Babbage (`Babbage/Rules/Utxo.hs:549-556`) holds Alonzo's at 1.
  *   - DELEG: `Shelley/Rules/Deleg.hs:153-193`. POOL is the encoding Conway shares
  *     (`Shelley/Rules/Pool.hs:153-170`). PPUP: `Shelley/Rules/Ppup.hs:132-137`; it sits under UTXO
  *     at 7 before Alonzo and under UTXOS at 2 from Alonzo (`Alonzo/Rules/Utxos.hs:351-356`).
  *
  * A `Mismatch` here is the record `[supplied, expected]` in one field (`BaseTypes.hs:854`), in
  * that order; Conway writes the two in line and swaps some. Sets are tag 258 from protocol version
  * 9 only, so they are plain arrays here; the sets and maps are written at the lowest protocol
  * version of the era (`Versions.eraLow`).
  *
  * A failure that the era's ledger has no constructor for, or whose payload this encoding does not
  * write, has no encoding ([[applyTxError]] is `None`): the reference answers a refusal of the eras
  * before Conway with these types alone (they have no free-text failure like Conway's). The node's
  * submission is Conway's alone, so no client reads these today; they are the encodings of the
  * failures the rules raise. Against the reference's own answers (`PreConwayFailureReferenceSuite`,
  * recorded from the `e2e` scenario) those of Shelley, Allegra, Mary and Alonzo for a small output,
  * a large value and the MIR checks are byte-equal; the other tags and all of Babbage's are read
  * from the instances (`PreConwayFailureCborSuite`). The Plutus and witness failures of Alonzo and
  * Babbage that `erarefusal.py` provokes (missing redeemers with certificates, the `NoRedeemer` and
  * translation collect errors of [[ContextFailureCbor]], the integrity hash, the datum and redeemer
  * checks, a missing key witness, a small output) are byte-equal to the node's answers, Babbage's
  * at protocol version 7 (`PlutusFailureReferenceSuite`); Babbage's `ContextError` payloads are
  * also byte-equal to the reference's matrix rows (`ContextMatrixSuite`).
  */
object PreConwayFailureCbor:
  import NodeOrders.given

  /** An item written by a function of the writer. */
  private type Enc = W => Unit

  /** A constructor of the reference's `Sum` form: `[tag, fields…]`. */
  private def sum(tag: Int)(fields: Enc*): Enc = w =>
    w.array(1 + fields.size).uint(tag)
    fields.foreach(_(w))

  /** A `Mismatch` as the record it is: `[supplied, expected]`. */
  private def rec[A](m: Mismatch[A])(each: (W, A) => Unit): Enc = w =>
    w.array(2)
    each(w, m.supplied)
    each(w, m.expected)

  /** The reason item for a refused transaction of `era`: the hard-fork combinator's `ApplyTxErr`
    * (the era index and the non-empty list of the ledger's failures), when the era has an encoding
    * for every failure.
    */
  def applyTxError(era: Era, failures: Vector[PredicateFailure]): Option[Bytes] =
    val encoded: Option[Vector[Enc]] = era match
      case Era.Shelley =>
        shelley(failures)(using Versions.eraLow(ShelleyBasedEra.SShelley))
      case Era.Allegra =>
        allegra(failures)(using Versions.eraLow(ShelleyBasedEra.SAllegra))
      case Era.Mary    => allegra(failures)(using Versions.eraLow(ShelleyBasedEra.SMary))
      case Era.Alonzo  => alonzo(failures)(using Versions.eraLow(ShelleyBasedEra.SAlonzo))
      case Era.Babbage => babbage(failures)(using Versions.eraLow(ShelleyBasedEra.SBabbage))
      case Era.Byron | Era.Conway => None
    encoded.map { es =>
      CborOut.build { w =>
        w.array(1).array(2).uint(era.index)
        list(w, es)((k, e) => e(k))
      }
    }

  private def shelley(failures: Vector[PredicateFailure])(using ProtVer): Option[Vector[Enc]] =
    failures.traverse(ledger(shelleyUtxow(shelleyUtxo), _))

  /** Allegra and Mary. */
  private def allegra(failures: Vector[PredicateFailure])(using ProtVer): Option[Vector[Enc]] =
    failures.traverse(ledger(shelleyUtxow(allegraUtxo), _))

  private def alonzo(failures: Vector[PredicateFailure])(using ProtVer): Option[Vector[Enc]] =
    failures.traverse(ledger(alonzoUtxow(ContextEra.Alonzo, alonzoUtxo(ContextEra.Alonzo)), _))

  private def babbage(failures: Vector[PredicateFailure])(using ProtVer): Option[Vector[Enc]] =
    failures.traverse(ledger(babbageUtxow, _))

  // ---- LEDGER

  private def ledger(utxow: UtxowFailure => Option[Enc], f: PredicateFailure)(using
      ProtVer
  ): Option[Enc] = f match
    case PredicateFailure.Utxow(u) => utxow(u).map(e => sum(0)(e))
    case PredicateFailure.Certs(CertsFailure.Cert(CertFailure.Deleg(d))) =>
      shelleyDeleg(d).map(e => sum(1)(sum(1)(sum(1)(e))))
    case PredicateFailure.Certs(CertsFailure.Cert(CertFailure.Pool(p))) =>
      Some(sum(1)(sum(1)(sum(0)(w => pool(w, p)))))
    case PredicateFailure.WithdrawalsMissingAccounts(ws) =>
      Some(sum(2)(w => map(w, ws)(rewardAccount, coin)))
    case PredicateFailure.IncompleteWithdrawals(ms) =>
      Some(
        sum(3)(w =>
          map(w, ms)(
            rewardAccount,
            (k, m) => { k.array(2); group(k, m)(coin) }
          )
        )
      )
    // Conway's rules
    case PredicateFailure.Certs(CertsFailure.WithdrawalsNotInRewards(_)) |
        PredicateFailure.Certs(CertsFailure.Cert(CertFailure.GovCert(_))) |
        PredicateFailure.Gov(_) | PredicateFailure.WdrlNotDelegatedToDRep(_) |
        PredicateFailure.TreasuryValueMismatch(_) | PredicateFailure.TxRefScriptsSizeTooBig(_) |
        PredicateFailure.Mempool(_) =>
      None

  // ---- UTXOW

  /** The constructors of `ShelleyUtxowPredFailure`; `utxo` writes the UTXO failure of the era. */
  private def shelleyUtxow(utxo: UtxoFailure => Option[Enc])(u: UtxowFailure)(using
      ProtVer
  ): Option[Enc] = u match
    case UtxowFailure.InvalidWitnesses(keys)         => Some(sum(0)(w => list(w, keys)(hash)))
    case UtxowFailure.MissingVKeyWitnesses(hs)       => Some(sum(1)(hashSet(_, hs)))
    case UtxowFailure.MissingScriptWitnesses(hs)     => Some(sum(2)(hashSet(_, hs)))
    case UtxowFailure.ScriptWitnessNotValidating(hs) => Some(sum(3)(hashSet(_, hs)))
    case UtxowFailure.Utxo(f)                        => utxo(f).map(e => sum(4)(e))
    case UtxowFailure.MIRInsufficientGenesisSigs(hs) => Some(sum(5)(hashSet(_, hs)))
    case UtxowFailure.MissingTxBodyMetadataHash(h)   => Some(sum(6)(_.bytes(h)))
    case UtxowFailure.MissingTxMetadata(h)           => Some(sum(7)(_.bytes(h)))
    case UtxowFailure.ConflictingMetadataHash(m)     => Some(sum(8)(rec(m)((k, h) => k.bytes(h))))
    case UtxowFailure.InvalidMetadata                => Some(sum(9)())
    case UtxowFailure.ExtraneousScriptWitnesses(hs)  => Some(sum(10)(hashSet(_, hs)))
    // Alonzo's and Babbage's
    case UtxowFailure.MissingRedeemers(_) | UtxowFailure.MissingRequiredDatums(_, _) |
        UtxowFailure.NotAllowedSupplementalDatums(_, _) | UtxowFailure.PPViewHashesDontMatch(_) |
        UtxowFailure.ScriptIntegrityHashMismatch(_, _) | UtxowFailure.UnspendableUTxONoDatumHash(_) | UtxowFailure.ExtraRedeemers(_) |
        UtxowFailure.MalformedScriptWitnesses(_) | UtxowFailure.MalformedReferenceScripts(_) =>
      None

  /** `AlonzoUtxowPredFailure`: the Shelley constructors at 0 (the UTXO failure inside them is the
    * era's), then Alonzo's own. A missing redeemer names its purposes as [[ContextFailureCbor]]
    * writes them, a certifying one with its certificate; a purpose with no encoding in the era
    * (voting and proposing) leaves the failure without one.
    */
  private def alonzoUtxow(era: ContextEra, utxo: UtxoFailure => Option[Enc])(u: UtxowFailure)(using
      ProtVer
  ): Option[Enc] = u match
    case UtxowFailure.MissingRedeemers(ps) =>
      ps.toVector
        .traverse((purpose, script) => ContextFailureCbor.purpose(era, purpose).map(_ -> script))
        .map(entries =>
          sum(1)(w =>
            list(w, entries) { case (k, (raw, script)) =>
              k.array(2); k.raw(raw); k.bytes(script)
            }
          )
        )
    case UtxowFailure.MissingRequiredDatums(missing, received) =>
      Some(sum(2)(hashSet(_, missing), hashSet(_, received)))
    case UtxowFailure.NotAllowedSupplementalDatums(unallowed, acceptable) =>
      Some(sum(3)(hashSet(_, unallowed), hashSet(_, acceptable)))
    case UtxowFailure.PPViewHashesDontMatch(m) =>
      Some(sum(4)(rec(m)(maybeHash(_, _))))
    case UtxowFailure.UnspendableUTxONoDatumHash(ins) =>
      Some(sum(6)(set(_, ins)(txIn)))
    case UtxowFailure.ExtraRedeemers(ps) =>
      Some(sum(7)(w => list(w, ps) { case (k, (tag, ix)) => k.array(2).uint(tag).uint(ix) }))
    case UtxowFailure.MalformedScriptWitnesses(_) | UtxowFailure.MalformedReferenceScripts(_) |
        UtxowFailure.ScriptIntegrityHashMismatch(_, _) =>
      None
    case shelley @ (UtxowFailure.InvalidWitnesses(_) | UtxowFailure.MissingVKeyWitnesses(_) |
        UtxowFailure.MissingScriptWitnesses(_) | UtxowFailure.ScriptWitnessNotValidating(_) |
        UtxowFailure.Utxo(_) | UtxowFailure.MIRInsufficientGenesisSigs(_) |
        UtxowFailure.MissingTxBodyMetadataHash(_) | UtxowFailure.MissingTxMetadata(_) |
        UtxowFailure.ConflictingMetadataHash(_) | UtxowFailure.InvalidMetadata |
        UtxowFailure.ExtraneousScriptWitnesses(_)) =>
      shelleyUtxow(utxo)(shelley).map(e => sum(0)(e))

  /** `BabbageUtxowPredFailure`: Alonzo's at 1, UTXO at 2, the malformed scripts at 3 and 4. */
  private def babbageUtxow(u: UtxowFailure)(using ProtVer): Option[Enc] = u match
    case UtxowFailure.Utxo(f)                       => babbageUtxo(f).map(e => sum(2)(e))
    case UtxowFailure.MalformedScriptWitnesses(hs)  => Some(sum(3)(hashSet(_, hs)))
    case UtxowFailure.MalformedReferenceScripts(hs) => Some(sum(4)(hashSet(_, hs)))
    case other => alonzoUtxow(ContextEra.Babbage, babbageUtxo)(other).map(e => sum(1)(e))

  // ---- UTXO

  /** `ShelleyUtxoPredFailure`, which the other eras extend. */
  private def shelleyUtxo(f: UtxoFailure)(using ProtVer): Option[Enc] = f match
    case UtxoFailure.ExpiredUTxO(m) => Some(sum(1)(rec(m)(coin)))
    case other                      => sharedUtxo(other)

  /** `AllegraUtxoPredFailure` (Allegra and Mary): the validity interval and the size of a value. */
  private def allegraUtxo(f: UtxoFailure)(using ProtVer): Option[Enc] = f match
    case UtxoFailure.OutsideValidityInterval(notBefore, before, slot) =>
      Some(sum(1)(validityInterval(notBefore, before), _.uint(slot)))
    case UtxoFailure.OutputTooBig(outs) =>
      Some(sum(12)(w => list(w, outs)((k, o) => txOut(k, o._3))))
    case UtxoFailure.ExpiredUTxO(_) => None
    case other                      => sharedUtxo(other)

  /** The constructors that Shelley, Allegra and Mary have in common, at the tags they share. */
  private def sharedUtxo(f: UtxoFailure)(using ProtVer): Option[Enc] = f match
    case UtxoFailure.BadInputs(ins)       => Some(sum(0)(set(_, ins)(txIn)))
    case UtxoFailure.MaxTxSize(m)         => Some(sum(2)(rec(m)(coin)))
    case UtxoFailure.InputSetEmpty        => Some(sum(3)())
    case UtxoFailure.FeeTooSmall(m)       => Some(sum(4)(rec(m)(coin)))
    case UtxoFailure.ValueNotConserved(m) => Some(sum(5)(rec(m)(value)))
    case UtxoFailure.OutputTooSmall(outs) => Some(sum(6)(w => list(w, outs)(txOut)))
    case UtxoFailure.Update(p)            => Some(sum(7)(ppup(p)))
    case UtxoFailure.WrongNetwork(expected, addresses) =>
      Some(sum(8)(_.uint(expected), set(_, addresses)((k, a) => k.bytes(a))))
    case UtxoFailure.WrongNetworkWithdrawal(expected, accounts) =>
      Some(sum(9)(_.uint(expected), set(_, accounts)(rewardAccount)))
    case UtxoFailure.OutputBootAddrAttrsTooBig(outs) =>
      Some(sum(10)(w => list(w, outs)(txOut)))
    // the constructors of Alonzo, Babbage and Conway, and the ones the callers took
    case UtxoFailure.Utxos(_) | UtxoFailure.ExpiredUTxO(_) |
        UtxoFailure.OutsideValidityInterval(_, _, _) | UtxoFailure.OutputTooBig(_) |
        UtxoFailure.InsufficientCollateral(_, _) | UtxoFailure.ScriptsNotPaid(_) |
        UtxoFailure.ExUnitsTooBig(_) | UtxoFailure.CollateralContainsNonAda(_) |
        UtxoFailure.WrongNetworkInTxBody(_) | UtxoFailure.OutsideForecast(_) |
        UtxoFailure.TooManyCollateralInputs(_) | UtxoFailure.NoCollateralInputs |
        UtxoFailure.IncorrectTotalCollateralField(_, _) | UtxoFailure.BabbageOutputTooSmall(_) |
        UtxoFailure.BabbageNonDisjointRefInputs(_) =>
      None

  /** `ValidityInterval`: the two bounds as maybes. */
  private def validityInterval(notBefore: Option[BigInt], before: Option[BigInt]): Enc = w =>
    w.array(2)
    maybe(w, notBefore)((k, s) => k.uint(s))
    maybe(w, before)((k, s) => k.uint(s))

  /** `AlonzoUtxoPredFailure`; PPUP is under UTXOS (`Alonzo/Rules/Utxos.hs:351-356`), and the
    * `CollectErrors` of UTXOS are written with the era's collect errors ([[ContextFailureCbor]]).
    */
  private def alonzoUtxo(era: ContextEra)(f: UtxoFailure)(using ProtVer): Option[Enc] = f match
    case UtxoFailure.BadInputs(ins) => Some(sum(0)(set(_, ins)(txIn)))
    case UtxoFailure.OutsideValidityInterval(notBefore, before, slot) =>
      Some(sum(1)(validityInterval(notBefore, before), _.uint(slot)))
    case UtxoFailure.MaxTxSize(m)         => Some(sum(2)(rec(m)(coin)))
    case UtxoFailure.InputSetEmpty        => Some(sum(3)())
    case UtxoFailure.FeeTooSmall(m)       => Some(sum(4)(rec(m)(coin)))
    case UtxoFailure.ValueNotConserved(m) => Some(sum(5)(rec(m)(value)))
    case UtxoFailure.OutputTooSmall(outs) => Some(sum(6)(w => list(w, outs)(txOut)))
    case UtxoFailure.Utxos(u) =>
      u match
        case UtxosFailure.ValidationTagMismatch(_, _) => Some(sum(7)(w => utxos(w, u)))
        case UtxosFailure.CollectErrors(errors) =>
          ContextFailureCbor.utxos(era, errors).map(raw => sum(7)(w => { w.raw(raw); () }))
    case UtxoFailure.Update(p) => Some(sum(7)(sum(2)(ppup(p))))
    case UtxoFailure.WrongNetwork(expected, addresses) =>
      Some(sum(8)(_.uint(expected), set(_, addresses)((k, a) => k.bytes(a))))
    case UtxoFailure.WrongNetworkWithdrawal(expected, accounts) =>
      Some(sum(9)(_.uint(expected), set(_, accounts)(rewardAccount)))
    case UtxoFailure.OutputBootAddrAttrsTooBig(outs) =>
      Some(sum(10)(w => list(w, outs)(txOut)))
    case UtxoFailure.OutputTooBig(outs) =>
      Some(
        sum(12)(w =>
          list(w, outs) { case (k, (size, max, out)) =>
            k.array(3).uint(size).uint(max); txOut(k, out)
          }
        )
      )
    case UtxoFailure.InsufficientCollateral(balance, required) =>
      Some(sum(13)(_.int(balance), _.uint(required)))
    case UtxoFailure.ScriptsNotPaid(outs)        => Some(sum(14)(map(_, outs)(txIn, txOut)))
    case UtxoFailure.ExUnitsTooBig(m)            => Some(sum(15)(rec(m)(exUnits(_, _))))
    case UtxoFailure.CollateralContainsNonAda(v) => Some(sum(16)(value(_, v)))
    case UtxoFailure.WrongNetworkInTxBody(m)     => Some(sum(17)(rec(m)(small)))
    case UtxoFailure.OutsideForecast(slot)       => Some(sum(18)(_.uint(slot)))
    case UtxoFailure.TooManyCollateralInputs(m)  => Some(sum(19)(rec(m)(small)))
    case UtxoFailure.NoCollateralInputs          => Some(sum(20)())
    // Shelley's, Babbage's
    case UtxoFailure.ExpiredUTxO(_) | UtxoFailure.IncorrectTotalCollateralField(_, _) |
        UtxoFailure.BabbageOutputTooSmall(_) | UtxoFailure.BabbageNonDisjointRefInputs(_) =>
      None

  /** `BabbageUtxoPredFailure`: Alonzo's at 1, then its own. */
  private def babbageUtxo(f: UtxoFailure)(using ProtVer): Option[Enc] = f match
    case UtxoFailure.IncorrectTotalCollateralField(provided, declared) =>
      Some(sum(2)(_.int(provided), _.uint(declared)))
    case UtxoFailure.BabbageOutputTooSmall(outs) =>
      Some(
        sum(3)(w =>
          list(w, outs) { case (k, (out, min)) => k.array(2); txOut(k, out); k.uint(min) }
        )
      )
    case UtxoFailure.BabbageNonDisjointRefInputs(ins) => Some(sum(4)(w => list(w, ins)(txIn)))
    case other => alonzoUtxo(ContextEra.Babbage)(other).map(e => sum(1)(e))

  // ---- PPUP

  private def ppup(f: PpupFailure)(using ProtVer): Enc = f match
    case PpupFailure.NonGenesisUpdate(m) => sum(0)(rec(m)((k, hs) => hashSet(k, hs)))
    case PpupFailure.PPUpdateWrongEpoch(current, proposed, period) =>
      sum(1)(
        _.uint(current),
        _.uint(proposed),
        _.uint(period match
          case VotingPeriod.VoteForThisEpoch => 0
          case VotingPeriod.VoteForNextEpoch => 1)
      )
    case PpupFailure.PVCannotFollow(major, minor) => sum(2)(protVer(_, (major, minor)))

  // ---- DELEG

  private def potCode(pot: Pot): Int = pot match
    case Pot.Reserves => 0
    case Pot.Treasury => 1

  /** `ShelleyDelegPredFailure`. */
  private def shelleyDeleg(f: DelegFailure)(using ProtVer): Option[Enc] = f match
    case DelegFailure.StakeKeyAlreadyRegistered(c)              => Some(sum(0)(credential(_, c)))
    case DelegFailure.StakeKeyNotRegistered(c)                  => Some(sum(1)(credential(_, c)))
    case DelegFailure.StakeKeyHasNonZeroAccountBalance(balance) => Some(sum(2)(_.uint(balance)))
    case DelegFailure.StakeDelegationImpossible(c)              => Some(sum(3)(credential(_, c)))
    case DelegFailure.WrongCertificateType                      => Some(sum(4)())
    case DelegFailure.GenesisKeyNotInMapping(g)                 => Some(sum(5)(_.bytes(g)))
    case DelegFailure.DuplicateGenesisDelegate(d)               => Some(sum(6)(_.bytes(d)))
    case DelegFailure.InsufficientForInstantaneousRewards(pot, m) =>
      Some(sum(7)(_.uint(potCode(pot)), rec(m)(coin)))
    case DelegFailure.MIRCertificateTooLateinEpoch(m) => Some(sum(8)(rec(m)(coin)))
    case DelegFailure.DuplicateGenesisVRF(vrf)        => Some(sum(9)(_.bytes(vrf)))
    case DelegFailure.MIRTransferNotCurrentlyAllowed  => Some(sum(11)())
    case DelegFailure.MIRNegativesNotCurrentlyAllowed => Some(sum(12)())
    case DelegFailure.InsufficientForTransfer(pot, m) =>
      Some(sum(13)(_.uint(potCode(pot)), rec(m)(coin)))
    case DelegFailure.MIRProducesNegativeUpdate => Some(sum(14)())
    case DelegFailure.MIRNegativeTransfer(pot, amount) =>
      Some(sum(15)(_.uint(potCode(pot)), _.uint(amount)))
    case DelegFailure.DelegateeNotRegistered(pool) => Some(sum(16)(_.bytes(pool)))
    // Conway's
    case DelegFailure.IncorrectDeposit(_) | DelegFailure.StakeKeyRegistered(_) |
        DelegFailure.DelegateeDRepNotRegistered(_) |
        DelegFailure.DelegateeStakePoolNotRegistered(_) | DelegFailure.DepositIncorrect(_) |
        DelegFailure.RefundIncorrect(_) =>
      None
