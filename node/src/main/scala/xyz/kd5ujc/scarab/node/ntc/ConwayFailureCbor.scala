// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.data.NonEmptyList
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, DomainError, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{PlutusFailure, TranslationError, TxOutSource}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoContextError,
  AsItem,
  BabbageContextError,
  CollectError,
  EncCBOR,
  GovAction,
  IsPhase2Valid,
  Mismatch,
  PParamsUpdate,
  PlutusWithContext,
  ProposalProcedure,
  Relation,
  StoredTxOut,
  VotingProcedures
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayContextError, ConwayPlutusPurpose, ConwayTxCert}
import xyz.kd5ujc.scarab.ledger.era.{ConwayDelegCert, ConwayGovCert, Delegatee, PoolCert}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayCertPredFailure,
  ConwayCertsPredFailure,
  ConwayDelegPredFailure,
  ConwayGovCertPredFailure,
  ConwayGovPredFailure,
  ConwayLedgerPredFailure,
  ConwayUtxoPredFailure,
  ConwayUtxosPredFailure,
  ConwayUtxowPredFailure,
  FailureDescription,
  ShelleyPoolPredFailure,
  TagMismatchDescription
}
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{
  AccountAddress,
  Coin,
  Conway,
  Credential,
  DRep,
  KeyRole,
  PoolMetadata,
  ProtVer,
  ShelleyBasedEra,
  StakePoolParams
}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellStateCodecs, NodeOrders, Versions}

/** Conway's `ApplyTxError` written from the era's failures (`newtype ApplyTxError ConwayEra =
  * ConwayApplyTxError (NonEmpty (ConwayLedgerPredFailure ConwayEra))`, its `EncCBOR` derived from
  * the list's, `eras/conway/impl/src/Cardano/Ledger/Conway.hs:50-53`, cardano-ledger f649f975),
  * each failure by the `EncCBOR` instance of its type, constructor by constructor, at the version
  * the node writes Conway's refusals at (`Versions.eraLow`, 9), in the hard-fork combinator's
  * wrapper [[PredicateFailureCbor.applyTxError]] writes (Conway's index, 6).
  *
  * MEMPOOL's failure is LEDGER's (`type PredicateFailure (MEMPOOL era) = ConwayLedgerPredFailure
  * era`, `Conway/Rules/Mempool.hs:86`, cardano-ledger f649f975), so a MEMPOOL refusal is the
  * non-empty list of LEDGER's failures [[applyTxError]] takes, its own `ConwayMempoolFailure` among
  * them.
  *
  * The leaves are written by the writers the node's flat encoder uses, where the era's value has a
  * total form they take: an output and a value (`StoredTxOut.conway`), an account address and a
  * credential (`Convert`); those writers are the reference's `EncCBOR` of the same values. A
  * purpose with its item, a certificate, a governance action, a proposal and a context's error are
  * written from the era's values here, so that no failure needs what only the transaction gives.
  *
  * A failure has no bytes ([[Unencodable]]) where the reference has no constructor for what the
  * node holds ([[NoReferenceEncoding]]), or where a script's failure cannot be described
  * ([[RefusalPresentationError]]), as the flat encoder's.
  */
object ConwayFailureCbor:
  import PredicateFailureCbor.{
    coin as uint,
    exUnits,
    hash,
    hashSet,
    int,
    list,
    map,
    maybe,
    maybeHash,
    put,
    rewardAccount,
    set,
    small,
    txIn,
    txOut,
    value,
    voter
  }
  import NodeOrders.given

  private type W = CborOut

  /** The values of a failure are written at the version the reference writes Conway's at. */
  private given ProtVer = Versions.eraLow(ShelleyBasedEra.SConway)

  /** What the reference has no constructor for: a context error of the node's own, or of a build
    * whose Conway never raises it, and a pool registration's relays or metadata that do not read as
    * the reference's types. The flat encoder sends such a failure as `ConwayMempoolFailure` 's text
    * of the flat failure, which the era's failure has no form for.
    */
  enum NoReferenceEncoding extends DomainError:
    /** `Undecodable` (the node's own), a time past the horizon whose cause is not the clock's
      * `PastHorizon` (a missing era history or clock sample, the node's own), and the unsupported
      * certificate, unsupported purpose and impossible context Alonzo's error holds at 9df9f98c
      * (`Alonzo/Plutus/TxInfo.hs:222-229`), which f649f975's has not, and which Conway's injection
      * never puts there ([[xyz.kd5ujc.scarab.ledger.era.Inject.conwayAlonzo]]).
      */
    case ContextError(error: AlonzoContextError[Conway])

    /** A pool registration whose relays or metadata do not read as the reference's
      * `StrictSeq StakePoolRelay` and `PoolMetadata`, which its `EncCBOR` writes from their values.
      */
    case PoolParameters(params: StakePoolParams)

    def message: String = this match
      case ContextError(error) => s"no reference encoding of the context error $error"
      case PoolParameters(params) =>
        s"no reference encoding of the pool registration of ${params.id.bytes.hex}"

  /** Why a failure has no bytes. */
  type Unencodable = RefusalPresentationError | NoReferenceEncoding

  private type Result[A] = Either[Unencodable, A]

  /** The reason item of `MsgRejectTx` for a refused Conway transaction: the hard-fork combinator's
    * `ApplyTxErr`, Conway's index and the list of the failures (`EncCBOR (ApplyTxError ConwayEra)`,
    * `Conway.hs:50-53`, cardano-ledger f649f975).
    */
  def applyTxError(failures: NonEmptyList[ConwayLedgerPredFailure[Conway]]): Result[Bytes] =
    failures.traverse(ledger).map { encoded =>
      CborOut.build { w =>
        w.array(1).array(2).uint(PredicateFailureCbor.ConwayIndex)
        list(w, encoded.toList)(raw)
      }
    }

  // ---- helpers

  private def raw(w: W, b: Bytes): Unit = { w.raw(b); () }

  private def build(body: W => Unit): Bytes = CborOut.build(body)

  /** `Sum c tag !> To x`: `[tag, x]`, `x` already written. */
  private def sum(tag: Int)(payload: Bytes): Bytes = build(w => raw(w.array(2).uint(tag), payload))

  /** `ToGroup mm` (`EncCBORGroup (Mismatch r a)`, `BaseTypes.hs:868-870`, cardano-ledger f649f975):
    * the supplied value, then the expected, in line.
    */
  private def group[R <: Relation, A](w: W, m: Mismatch[R, A])(each: (W, A) => Unit): Unit =
    each(w, m.supplied); each(w, m.expected)

  /** `ToGroup (swapMismatch mm)`: the expected value, then the supplied, in line. */
  private def swapped[R <: Relation, A](w: W, m: Mismatch[R, A])(each: (W, A) => Unit): Unit =
    each(w, m.expected); each(w, m.supplied)

  /** `To mm` (`EncCBOR (Mismatch r a)`, `BaseTypes.hs:854-859`, cardano-ledger f649f975):
    * `[supplied, expected]`.
    */
  private def mismatch[R <: Relation, A](w: W, m: Mismatch[R, A])(each: (W, A) => Unit): Unit =
    w.array(2); group(w, m)(each)

  /** `EncCBOR Coin`, its `Integer` 's (`deriving newtype EncCBOR`, `Coin.hs:93-106`, cardano-ledger
    * f649f975): `encodeInteger`, a bignum past 64 bits.
    */
  private def coin(w: W, c: Coin): Unit = { w.integer(c.value); () }

  private def credential[R <: KeyRole](w: W, c: Credential[R]): W =
    put(w, c)(using HaskellStateCodecs.credential[R])

  private def account(w: W, a: AccountAddress): W = rewardAccount(w, Convert.rewardAccountBack(a))

  private def drep(w: W, d: DRep): W = put(w, d)(using HaskellStateCodecs.drep)

  private def anchor(w: W, a: xyz.kd5ujc.scarab.ledger.Anchor): W =
    put(w, a)(using HaskellStateCodecs.anchor)

  private def nullAnchor(w: W, a: Option[xyz.kd5ujc.scarab.ledger.Anchor]): Unit =
    a.fold({ w.nul; () })(x => { anchor(w, x); () })

  private def nullHash(w: W, h: Option[Bytes]): Unit =
    h.fold({ w.nul; () })(x => { w.bytes(x); () })

  private def era[A](enc: EncCBOR[A])(w: W, a: A): Unit = raw(w, enc.encCBOR(a).bytes)

  private val stored = StoredTxOut.conway

  private def output(w: W, o: xyz.kd5ujc.scarab.ledger.era.TxOut[Conway]): W =
    txOut(w, stored.toStored(o))

  private def eraValue(w: W, v: xyz.kd5ujc.scarab.ledger.era.Value[Conway]): Unit =
    value(w, stored.storedValue(v))

  // ---- LEDGER

  /** `EncCBOR (ConwayLedgerPredFailure era)` (`Conway/Rules/Ledger.hs:221-240`, cardano-ledger
    * f649f975): tags 1 to 9; the treasury's mismatch swapped (`swapMismatch`), the size of the
    * reference scripts in line, the mempool's failure its text, each missing account with the
    * amount, each incomplete withdrawal with `[amount, balance]`.
    */
  def ledger(f: ConwayLedgerPredFailure[Conway]): Result[Bytes] =
    import ConwayLedgerPredFailure.*
    f match
      case ConwayUtxowFailure(u) => utxow(u).map(sum(1))
      case ConwayCertsFailure(c) => certs(c).map(sum(2))
      case ConwayGovFailure(g)   => gov(g).map(sum(3))
      case ConwayWdrlNotDelegatedToDRep(keys) =>
        Right(build { w =>
          w.array(2).uint(4); list(w, keys.toList)((k, h) => hash(k, h.bytes))
        })
      case ConwayTreasuryValueMismatch(m) =>
        Right(build { w =>
          w.array(3).uint(5); swapped(w, m)(coin)
        })
      case ConwayTxRefScriptsSizeTooBig(m) =>
        Right(build { w =>
          w.array(3).uint(6); group(w, m)(int)
        })
      case ConwayMempoolFailure(problem) =>
        Right(build(_.array(2).uint(7).text(problem.message)))
      case ConwayWithdrawalsMissingAccounts(ws) =>
        Right(build { w =>
          w.array(2).uint(8)
          map(w, ws.map((a, c) => Convert.rewardAccountBack(a) -> c))(rewardAccount, coin)
        })
      case ConwayIncompleteWithdrawals(ms) =>
        Right(build { w =>
          w.array(2).uint(9)
          map(w, ms.toSortedMap.toMap.map((a, m) => Convert.rewardAccountBack(a) -> m))(
            rewardAccount,
            (k, m) => mismatch(k, m)(coin)
          )
        })

  // ---- UTXOW

  /** `EncCBOR (ConwayUtxowPredFailure era)` (`Conway/Rules/Utxow.hs:219-245`, cardano-ledger
    * f649f975): tags 0 to 18, each hash set a set, a mismatch of hashes in line but for the
    * integrity hash's, which is `[supplied, expected]` before the bytes it is over.
    */
  def utxow(f: ConwayUtxowPredFailure[Conway]): Result[Bytes] =
    import ConwayUtxowPredFailure.*
    def hashes(tag: Int, hs: cats.data.NonEmptySet[Bytes]): Result[Bytes] =
      Right(build { w =>
        w.array(2).uint(tag); hashSet(w, hs.toSortedSet)
      })
    f match
      case UtxoFailure(u) => utxo(u).map(sum(0))
      case InvalidWitnessesUTXOW(keys) =>
        Right(build { w =>
          w.array(2).uint(1); list(w, keys.toList)(hash)
        })
      case MissingVKeyWitnessesUTXOW(hs)       => hashes(2, hs)
      case MissingScriptWitnessesUTXOW(hs)     => hashes(3, hs)
      case ScriptWitnessNotValidatingUTXOW(hs) => hashes(4, hs)
      case MissingTxBodyMetadataHash(h)        => Right(build(_.array(2).uint(5).bytes(h)))
      case MissingTxMetadata(h)                => Right(build(_.array(2).uint(6).bytes(h)))
      case ConflictingMetadataHash(m) =>
        Right(build { w =>
          w.array(3).uint(7); group(w, m)((k, h) => { k.bytes(h); () })
        })
      case InvalidMetadata()                  => Right(build(_.array(1).uint(8)))
      case ExtraneousScriptWitnessesUTXOW(hs) => hashes(9, hs)
      case MissingRedeemers(ps) =>
        ps.traverse((p, h) => purpose(p).map(_ -> h)).map { entries =>
          build { w =>
            w.array(2).uint(10)
            list(w, entries.toList) { case (k, (p, h)) => k.array(2).raw(p).bytes(h); () }
          }
        }
      case MissingRequiredDatums(missing, received) =>
        Right(build { w =>
          w.array(3).uint(11); hashSet(w, missing.toSortedSet); hashSet(w, received)
        })
      case NotAllowedSupplementalDatums(unallowed, acceptable) =>
        Right(build { w =>
          w.array(3).uint(12); hashSet(w, unallowed.toSortedSet); hashSet(w, acceptable)
        })
      case PPViewHashesDontMatch(m) =>
        Right(build { w =>
          w.array(3).uint(13); group(w, m)(maybeHash(_, _))
        })
      case UnspendableUTxONoDatumHash(ins) =>
        Right(build { w =>
          w.array(2).uint(14); set(w, ins.toSortedSet)(txIn)
        })
      case ExtraRedeemers(ps) =>
        Right(build { w =>
          w.array(2).uint(15)
          list(w, ps.toList)((k, p) => { k.array(2).uint(p.tag).uint(p.ix.value); () })
        })
      case MalformedScriptWitnesses(hs)  => hashes(16, hs)
      case MalformedReferenceScripts(hs) => hashes(17, hs)
      case ScriptIntegrityHashMismatch(m, expected) =>
        Right(build { w =>
          w.array(3).uint(18); mismatch(w, m)(maybeHash(_, _)); maybeHash(w, expected); ()
        })

  // ---- UTXO

  /** `EncCBOR (ConwayUtxoPredFailure era)` (`Conway/Rules/Utxo.hs:301-333`, cardano-ledger
    * f649f975): tags 0 to 22; the fee's, the units', the body's network's and the collateral
    * count's mismatches swapped (`swapMismatch`), the others in line; a validity interval
    * `[strict maybe, strict maybe]` (`EncCBOR ValidityInterval`, `Allegra/Scripts.hs:144-148`); a
    * balance a `DeltaCoin` 's integer.
    */
  def utxo(f: ConwayUtxoPredFailure[Conway]): Result[Bytes] =
    import ConwayUtxoPredFailure.*
    def outs(tag: Int, os: NonEmptyList[xyz.kd5ujc.scarab.ledger.era.TxOut[Conway]]) =
      Right(build { w =>
        w.array(2).uint(tag); list(w, os.toList)(output)
      })
    f match
      case UtxosFailure(u) => utxos(u).map(sum(0))
      case BadInputsUTxO(ins) =>
        Right(build { w =>
          w.array(2).uint(1); set(w, ins.toSortedSet)(txIn)
        })
      case OutsideValidityIntervalUTxO(vi, slot) =>
        Right(build { w =>
          w.array(3).uint(2).array(2)
          maybe(w, vi.invalidBefore)((k, s) => k.uint(s))
          maybe(w, vi.invalidHereafter)((k, s) => k.uint(s))
          w.uint(slot); ()
        })
      case MaxTxSizeUTxO(m) =>
        Right(build { w =>
          w.array(3).uint(3); group(w, m)(uint)
        })
      case InputSetEmptyUTxO() => Right(build(_.array(1).uint(4)))
      case FeeTooSmallUTxO(m) =>
        Right(build { w =>
          w.array(3).uint(5); swapped(w, m)(coin)
        })
      case ValueNotConservedUTxO(m) =>
        Right(build { w =>
          w.array(3).uint(6); group(w, m)(eraValue)
        })
      case WrongNetwork(network, addresses) =>
        Right(build { w =>
          w.array(3).uint(7).uint(network.wire)
          set(w, addresses.toSortedSet.toList.map(_.raw).toSet)((k, a) => { k.bytes(a); () })
        })
      case WrongNetworkWithdrawal(network, accounts) =>
        Right(build { w =>
          w.array(3).uint(8).uint(network.wire); set(w, accounts.toSortedSet)(rewardAccount)
        })
      case OutputTooSmallUTxO(os)        => outs(9, os)
      case OutputBootAddrAttrsTooBig(os) => outs(10, os)
      case OutputTooBigUTxO(os) =>
        Right(build { w =>
          w.array(2).uint(11)
          list(w, os.toList) { case (k, (size, max, o)) =>
            k.array(3).int(size).int(max); output(k, o); ()
          }
        })
      case InsufficientCollateral(balance, required) =>
        Right(build { w =>
          w.array(3).uint(12).int(balance.value); coin(w, required)
        })
      case ScriptsNotPaidUTxO(os) =>
        Right(build { w =>
          w.array(2).uint(13); map(w, os.toSortedMap.toMap)(txIn, output)
        })
      case ExUnitsTooBigUTxO(m) =>
        Right(build { w =>
          w.array(3).uint(14); swapped(w, m)(exUnits(_, _))
        })
      case CollateralContainsNonADA(v) =>
        Right(build { w =>
          w.array(2).uint(15); eraValue(w, v)
        })
      case WrongNetworkInTxBody(m) =>
        Right(build { w =>
          w.array(3).uint(16); swapped(w, m)((k, n) => small(k, n.wire))
        })
      case OutsideForecast(slot) => Right(build(_.array(2).uint(17).uint(slot)))
      case TooManyCollateralInputs(m) =>
        Right(build { w =>
          w.array(3).uint(18); swapped(w, m)((k, n) => { k.uint(n.value); () })
        })
      case NoCollateralInputs() => Right(build(_.array(1).uint(19)))
      case IncorrectTotalCollateralField(provided, declared) =>
        Right(build { w =>
          w.array(3).uint(20).int(provided.value); coin(w, declared)
        })
      case BabbageOutputTooSmallUTxO(os) =>
        Right(build { w =>
          w.array(2).uint(21)
          list(w, os.toList) { case (k, (o, least)) => k.array(2); output(k, o); coin(k, least) }
        })
      case BabbageNonDisjointRefInputs(ins) =>
        Right(build { w =>
          w.array(2).uint(22); list(w, ins.toList)(txIn)
        })

  // ---- UTXOS

  /** `EncCBOR (ConwayUtxosPredFailure era)` (`Conway/Rules/Utxos.hs:110-120`, cardano-ledger
    * f649f975): `[0, isValid, description]` and `[1, errors]`; the description (`EncCBOR
    * TagMismatchDescription`, `Alonzo/Rules/Utxos.hs:299-301`) `[0]` or `[1, failures]`, each
    * failure `[1, text, bytes]` (`EncCBOR FailureDescription`, `:265-270`), the text and the script
    * with its context in Base64 as `scriptFailureToFailureDescription` makes them (`:288-290`),
    * written by the node's presentation of a script's failure.
    */
  def utxos(f: ConwayUtxosPredFailure[Conway]): Result[Bytes] = f match
    case ConwayUtxosPredFailure.ValidationTagMismatch(isValid, description) =>
      val valid = isValid == IsPhase2Valid.Phase2Valid
      description match
        case TagMismatchDescription.PassedUnexpectedly =>
          Right(build(_.array(3).uint(0).bool(valid).array(1).uint(0)))
        case TagMismatchDescription.FailedUnexpectedly(fs) =>
          fs.traverse(describe).map { described =>
            build { w =>
              w.array(3).uint(0).bool(valid).array(2).uint(1)
              list(w, described.toList) { case (k, (text, base64)) =>
                k.array(3).uint(1).text(text).bytes(base64); ()
              }
            }
          }
    case ConwayUtxosPredFailure.CollectErrors(errors) =>
      errors.traverse(collect).map { encoded =>
        build { w =>
          w.array(2).uint(1); list(w, encoded.toList)(raw)
        }
      }

  /** The text and the Base64 bytes of a script's failure: the failure as the node presents it
    * ([[PlutusFailureCbor]]), the evaluator's request the script with its context.
    */
  private def describe(d: FailureDescription): Result[(String, Bytes)] = d match
    case FailureDescription.PlutusFailure(cause, pwc) =>
      val failure = PlutusFailure(cause, Bytes.empty, Some(PlutusWithContext.evalRequest(pwc)))
      PlutusFailureCbor
        .description(failure)
        .leftMap(e => RefusalPresentationError.InvalidPlutusContext(failure, e))
        .map(text =>
          (text.getOrElse(failure.cause.message), PlutusFailureCbor.reconstruction(failure))
        )

  /** `EncCBOR (CollectError era)` (`Alonzo/Plutus/Context.hs:376-379`, cardano-ledger f649f975):
    * tags 0 to 3.
    */
  private def collect(e: CollectError[Conway]): Result[Bytes] = e match
    case CollectError.NoRedeemer(p)     => purpose(p).map(sum(0))
    case CollectError.NoWitness(h)      => Right(build(_.array(2).uint(1).bytes(h)))
    case CollectError.NoCostModel(lang) => Right(build(_.array(2).uint(2).uint(lang.id)))
    case CollectError.BadTranslation(c) => context(c).map(sum(3))

  /** `EncCBOR (ConwayContextError era)` (`Conway/TxInfo.hs:223-248`, cardano-ledger f649f975):
    * Babbage's error at 8, Conway's own from 9; the proposals an ordered set, tag 258 over their
    * list (`EncCBOR (OSet a)`).
    */
  private def context(e: ConwayContextError[Conway]): Result[Bytes] = e match
    case ConwayContextError.BabbageContextError(b)       => babbage(b).map(sum(8))
    case ConwayContextError.CertificateNotSupported(c)   => certificate(c).map(sum(9))
    case ConwayContextError.PlutusPurposeNotSupported(p) => purpose(p).map(sum(10))
    case ConwayContextError.CurrentTreasuryFieldNotSupported(c) =>
      Right(build { w =>
        w.array(2).uint(11); coin(w, c)
      })
    case ConwayContextError.VotingProceduresFieldNotSupported(vs) =>
      Right(sum(12)(votingProcedures(vs)))
    case ConwayContextError.ProposalProceduresFieldNotSupported(ps) =>
      Right(build { w =>
        w.array(2).uint(13).tag(258); list(w, ps.toVector)((k, p) => raw(k, proposal(p)))
      })
    case ConwayContextError.TreasuryDonationFieldNotSupported(c) =>
      Right(build { w =>
        w.array(2).uint(14); coin(w, c)
      })
    case ConwayContextError.ReferenceInputsNotDisjointFromInputs(common) =>
      Right(build { w =>
        w.array(2).uint(15); list(w, common.toVector)(txIn)
      })

  /** `EncCBOR (BabbageContextError era)` (`Babbage/TxInfo.hs:275-290`, cardano-ledger f649f975):
    * tags 0, 1, 2, 4, 5, 6 and 7, Alonzo's two errors (`TranslationLogicMissingInput` and
    * `TimeTranslationPastHorizon`) at their own tags in line, a pointer `[tag, index]`.
    */
  private def babbage(e: BabbageContextError[Conway]): Result[Bytes] = e match
    case BabbageContextError.ByronTxOutInContext(s) => Right(sum(0)(source(s)))
    case BabbageContextError.AlonzoContextError(a)  => alonzo(a)
    case BabbageContextError.RedeemerPointerPointsToNothing(p) =>
      Right(build(_.array(2).uint(2).array(2).uint(p.tag).uint(p.ix.value)))
    case BabbageContextError.InlineDatumsNotSupported(s)     => Right(sum(4)(source(s)))
    case BabbageContextError.ReferenceScriptsNotSupported(s) => Right(sum(5)(source(s)))
    case BabbageContextError.ReferenceInputsNotSupported(ins) =>
      Right(build { w =>
        w.array(2).uint(6); set(w, ins)(txIn)
      })

  /** Alonzo's errors in Babbage's (`Babbage/TxInfo.hs:279-280, 289-290`, cardano-ledger f649f975):
    * the missing input `[1, input]`, and a time past the horizon `[7, text]`, the text the node
    * gives the clock's `PastHorizon` as the reference's. The rest have no reference constructor at
    * f649f975 ([[NoReferenceEncoding.ContextError]]).
    */
  private def alonzo(e: AlonzoContextError[Conway]): Result[Bytes] = e match
    case AlonzoContextError.TranslationLogicMissingInput(in) =>
      Right(build { w =>
        w.array(2).uint(1); txIn(w, in); ()
      })
    case AlonzoContextError.TimeTranslationPastHorizon(TranslationError.PastHorizon(slot, h)) =>
      Right(build(_.array(2).uint(7).text(h.pastHorizonText(slot))))
    case other @ (AlonzoContextError.TimeTranslationPastHorizon(_) |
        AlonzoContextError.CertificateNotSupported(_) |
        AlonzoContextError.PlutusPurposeNotSupported(_) | AlonzoContextError.Undecodable(_, _) |
        AlonzoContextError.ImpossibleContextError(_)) =>
      Left(NoReferenceEncoding.ContextError(other))

  /** `EncCBOR TxOutSource` (`libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/TxInfo.hs:98-101`,
    * cardano-ledger f649f975): `[0, input]` or `[1, index]`.
    */
  private def source(s: TxOutSource): Bytes = build { w =>
    s match
      case TxOutSource.TxOutFromInput(in)     => w.array(2).uint(0); txIn(w, in); ()
      case TxOutSource.TxOutFromOutput(index) => w.array(2).uint(1).uint(index); ()
  }

  /** `EncCBOR (VotingProcedures era)`, derived from its map's (`Procedures.hs:400-404`,
    * cardano-ledger f649f975): the map of each voter's map of votes, in the reference's orders.
    */
  private def votingProcedures(vs: VotingProcedures[Conway]): Bytes =
    ContextFailureCbor.votingProcedures(
      vs.unVotingProcedures.toVector.map((v, votes) => v -> votes.toVector)
    )

  // ---- purposes, certificates, actions and proposals

  /** `EncCBORGroup (ConwayPlutusPurpose AsItem era)` in its list of two (`CBORGroup`,
    * `Conway/Scripts.hs:223-232, 264-278`, cardano-ledger f649f975): the purpose's tag, then the
    * item (`AsItem`'s `EncCBOR` is its item's, `Alonzo/Scripts.hs:285-287`).
    */
  private def purpose(p: ConwayPlutusPurpose[AsItem]): Result[Bytes] = p match
    case ConwayPlutusPurpose.ConwaySpending(AsItem(in)) =>
      Right(build { w =>
        w.array(2).uint(0); txIn(w, in); ()
      })
    case ConwayPlutusPurpose.ConwayMinting(AsItem(policy)) =>
      Right(build(_.array(2).uint(1).bytes(policy)))
    case ConwayPlutusPurpose.ConwayCertifying(AsItem(c)) => certificate(c).map(sum(2))
    case ConwayPlutusPurpose.ConwayWithdrawing(AsItem(a)) =>
      Right(build { w =>
        w.array(2).uint(3); rewardAccount(w, a); ()
      })
    case ConwayPlutusPurpose.ConwayVoting(AsItem(v)) =>
      Right(build { w =>
        w.array(2).uint(4); voter(w, v); ()
      })
    case ConwayPlutusPurpose.ConwayProposing(AsItem(pp)) => Right(sum(5)(proposal(pp)))

  /** `EncCBOR (ConwayTxCert era)` (`Conway/TxCert.hs:737-741`, cardano-ledger f649f975): a
    * delegation certificate (`EncCBOR ConwayDelegCert`, `:488-534`, Shelley's three at tags 0 to 2,
    * `encodeShelleyDelegCert`, `Shelley/TxCert.hs:428-435`), a pool certificate (`encodePoolCert`,
    * `:437-448`) or a governance certificate (`EncCBOR ConwayGovCert`, `Conway/TxCert.hs:614-641`).
    */
  private def certificate(c: ConwayTxCert): Result[Bytes] = c match
    case ConwayTxCert.ConwayTxCertDeleg(d) => Right(delegation(d))
    case ConwayTxCert.ConwayTxCertPool(PoolCert.RegPool(params)) =>
      registration(params).toRight(NoReferenceEncoding.PoolParameters(params))
    case ConwayTxCert.ConwayTxCertPool(PoolCert.RetirePool(pool, epoch)) =>
      Right(build { w =>
        w.array(3).uint(4).bytes(pool.bytes)
        put(w, epoch)(using HaskellStateCodecs.epochNo); ()
      })
    case ConwayTxCert.ConwayTxCertGov(g) => Right(governance(g))

  private def delegation(d: ConwayDelegCert): Bytes = build { w =>
    import ConwayDelegCert as D
    d match
      case D.ConwayRegCert(c, None)       => w.array(2).uint(0); credential(w, c)
      case D.ConwayUnRegCert(c, None)     => w.array(2).uint(1); credential(w, c)
      case D.ConwayRegCert(c, Some(dep))  => w.array(3).uint(7); credential(w, c); coin(w, dep)
      case D.ConwayUnRegCert(c, Some(rf)) => w.array(3).uint(8); credential(w, c); coin(w, rf)
      case D.ConwayDelegCert(c, Delegatee.DelegStake(p)) =>
        w.array(3).uint(2); credential(w, c); w.bytes(p.bytes)
      case D.ConwayDelegCert(c, Delegatee.DelegVote(r)) =>
        w.array(3).uint(9); credential(w, c); drep(w, r)
      case D.ConwayDelegCert(c, Delegatee.DelegStakeVote(p, r)) =>
        w.array(4).uint(10); credential(w, c); w.bytes(p.bytes); drep(w, r)
      case D.ConwayRegDelegCert(c, Delegatee.DelegStake(p), dep) =>
        w.array(4).uint(11); credential(w, c); w.bytes(p.bytes); coin(w, dep)
      case D.ConwayRegDelegCert(c, Delegatee.DelegVote(r), dep) =>
        w.array(4).uint(12); credential(w, c); drep(w, r); coin(w, dep)
      case D.ConwayRegDelegCert(c, Delegatee.DelegStakeVote(p, r), dep) =>
        w.array(5).uint(13); credential(w, c); w.bytes(p.bytes); drep(w, r); coin(w, dep)
    ()
  }

  private def governance(g: ConwayGovCert): Bytes = build { w =>
    import ConwayGovCert.*
    g match
      case ConwayAuthCommitteeHotKey(cold, hot) =>
        w.array(3).uint(14); credential(w, cold); credential(w, hot)
      case ConwayResignCommitteeColdKey(cold, a) =>
        w.array(3).uint(15); credential(w, cold); nullAnchor(w, a)
      case ConwayRegDRep(c, deposit, a) =>
        w.array(4).uint(16); credential(w, c); coin(w, deposit); nullAnchor(w, a)
      case ConwayUnRegDRep(c, refund) => w.array(3).uint(17); credential(w, c); coin(w, refund)
      case ConwayUpdateDRep(c, a)     => w.array(3).uint(18); credential(w, c); nullAnchor(w, a)
    ()
  }

  /** A pool registration (`encodePoolCert`, `Shelley/TxCert.hs:437-443`, cardano-ledger f649f975):
    * `[3, ...]` with the parameters' nine fields in line (`withStakePoolParamsFlatEncoding`,
    * `libs/cardano-ledger-core/src/Cardano/Ledger/State/StakePool.hs:555-572`), the relays and the
    * metadata written from their values; none if those do not read as the reference's.
    */
  private def registration(p: StakePoolParams): Option[Bytes] =
    (
      ContextFailureCbor.relayBytes(p.relays),
      p.metadata.traverse(metadata)
    ).mapN { (relays, metadata) =>
      build { w =>
        w.array(10).uint(3).bytes(p.id.bytes).bytes(p.vrf.bytes)
        coin(w, p.pledge); coin(w, p.cost)
        era(EncCBOR.unitInterval)(w, p.margin)
        account(w, p.accountAddress)
        hashSet(w, p.owners.iterator.map(_.bytes).toSet)
        w.raw(relays)
        metadata.fold({ w.nul; () })(raw(w, _))
      }
    }

  /** `EncCBOR PoolMetadata`
    * (`libs/cardano-ledger-core/src/Cardano/Ledger/State/StakePool.hs:516-520`, cardano-ledger
    * f649f975): `[url, hash]`, the url read from the term the decoder kept, the hash a byte string
    * of whatever length it was read with.
    */
  private def metadata(m: PoolMetadata): Option[Bytes] =
    Cbor.decode(m.bytes).toOption.map(_.value).flatMap {
      case CborValue.Arr(Vector(url, _)) =>
        url.value match
          case CborValue.Text(u) => Some(build(_.array(2).text(u).bytes(m.hash)))
          case _                 => None
      case _ => None
    }

  /** `EncCBOR (GovAction era)` (`Procedures.hs:928-946`, cardano-ledger f649f975): `[tag, ...]`,
    * each parent `null` or its id (`encodeNullStrictMaybe`), the update by its `EncCBOR`
    * (`Core/PParams.hs:244-255`), the withdrawals a map, the members removed a set and those added
    * a map.
    */
  private def govAction(a: GovAction[Conway]): Bytes = build { w =>
    def parent(id: Option[xyz.kd5ujc.scarab.ledger.GovActionId]): Unit =
      id.fold({ w.nul; () })(i => { PredicateFailureCbor.govActionId(w, i); () })
    a match
      case GovAction.ParameterChange(prev, update, guardrails) =>
        w.array(4).uint(0); parent(prev.map(_.unGovPurposeId))
        era(PParamsUpdate.encCBOR[Conway])(w, update)
        nullHash(w, guardrails.map(_.bytes))
      case GovAction.HardForkInitiation(prev, version) =>
        w.array(3).uint(1); parent(prev.map(_.unGovPurposeId))
        era(EncCBOR.protVer)(w, version)
      case GovAction.TreasuryWithdrawals(withdrawals, guardrails) =>
        w.array(3).uint(2); map(w, withdrawals)(rewardAccount, coin)
        nullHash(w, guardrails.map(_.bytes))
      case GovAction.NoConfidence(prev) => w.array(2).uint(3); parent(prev.map(_.unGovPurposeId))
      case GovAction.UpdateCommittee(prev, removed, added, threshold) =>
        w.array(5).uint(4); parent(prev.map(_.unGovPurposeId))
        set(w, removed)(credential)
        map(w, added)(credential, (k, e) => { put(k, e)(using HaskellStateCodecs.epochNo); () })
        era(EncCBOR.unitInterval)(w, threshold)
      case GovAction.NewConstitution(prev, constitution) =>
        w.array(3).uint(5); parent(prev.map(_.unGovPurposeId))
        put(w, constitution)(using HaskellStateCodecs.constitution)
      case GovAction.InfoAction() => w.array(1).uint(6)
    ()
  }

  /** `EncCBOR (ProposalProcedure era)` (`Procedures.hs:537-544`, cardano-ledger f649f975):
    * `[deposit, return account, action, anchor]`.
    */
  private def proposal(p: ProposalProcedure[Conway]): Bytes = build { w =>
    w.array(4); coin(w, p.pProcDeposit); rewardAccount(w, p.pProcReturnAddr)
    raw(w, govAction(p.pProcGovAction)); anchor(w, p.pProcAnchor); ()
  }

  // ---- CERTS, CERT, DELEG, POOL, GOVCERT

  /** `EncCBOR (ConwayCertsPredFailure era)` (`Conway/Rules/Certs.hs:158-167`, cardano-ledger
    * f649f975): the withdrawals `[0, map]`, CERT's failure `[1, failure]` (`EncCBOR
    * (ConwayCertPredFailure era)`, `Conway/Rules/Cert.hs:258-269`, tags 1 to 3).
    */
  def certs(f: ConwayCertsPredFailure[Conway]): Result[Bytes] = f match
    case ConwayCertsPredFailure.WithdrawalsNotInRewardsCERTS(ws) =>
      Right(build { w =>
        w.array(2).uint(0)
        map(w, ws.map((a, c) => Convert.rewardAccountBack(a) -> c))(rewardAccount, coin)
      })
    case ConwayCertsPredFailure.CertFailure(c) =>
      Right(sum(1)(c match
        case ConwayCertPredFailure.DelegFailure(d)   => sum(1)(deleg(d))
        case ConwayCertPredFailure.PoolFailure(p)    => sum(2)(pool(p))
        case ConwayCertPredFailure.GovCertFailure(g) => sum(3)(govCert(g))))

  /** `EncCBOR (ConwayDelegPredFailure era)` (`Conway/Rules/Deleg.hs:124-142`, cardano-ledger
    * f649f975): tags 1 to 8, the deposit's and the refund's mismatches `[supplied, expected]`
    * (`To`).
    */
  private def deleg(f: ConwayDelegPredFailure[Conway]): Bytes = build { w =>
    import ConwayDelegPredFailure.*
    f match
      case IncorrectDepositDELEG(c)                 => w.array(2).uint(1); coin(w, c)
      case StakeKeyRegisteredDELEG(c)               => w.array(2).uint(2); credential(w, c)
      case StakeKeyNotRegisteredDELEG(c)            => w.array(2).uint(3); credential(w, c)
      case StakeKeyHasNonZeroAccountBalanceDELEG(c) => w.array(2).uint(4); coin(w, c)
      case DelegateeDRepNotRegisteredDELEG(c)       => w.array(2).uint(5); credential(w, c)
      case DelegateeStakePoolNotRegisteredDELEG(p)  => w.array(2).uint(6).bytes(p.bytes)
      case DepositIncorrectDELEG(m)                 => w.array(2).uint(7); mismatch(w, m)(coin)
      case RefundIncorrectDELEG(m)                  => w.array(2).uint(8); mismatch(w, m)(coin)
    ()
  }

  /** `EncCBOR (ShelleyPoolPredFailure era)` (`Shelley/Rules/Pool.hs:153-170`, cardano-ledger
    * f649f975): the retirement's current epoch (the expected of its first mismatch) then its second
    * mismatch, the cost's in line, the network's swapped.
    */
  private def pool(f: ShelleyPoolPredFailure[Conway]): Bytes = build { w =>
    import ShelleyPoolPredFailure.*
    def epoch(e: xyz.kd5ujc.scarab.ledger.state.EpochNo): Unit =
      put(w, e)(using HaskellStateCodecs.epochNo); ()
    f match
      case StakePoolNotRegisteredOnKeyPOOL(p) => w.array(2).uint(0).bytes(p.bytes)
      case StakePoolRetirementWrongEpochPOOL(current, latest) =>
        w.array(4).uint(1); epoch(current.expected); epoch(latest.supplied)
        epoch(latest.expected)
      case StakePoolCostTooLowPOOL(m) => w.array(3).uint(3); group(w, m)(coin)
      case WrongNetworkPOOL(m, p) =>
        w.array(4).uint(4); swapped(w, m)((k, n) => small(k, n.wire)); w.bytes(p.bytes)
      case PoolMedataHashTooBig(p, size)     => w.array(3).uint(5).bytes(p.bytes).int(size)
      case VRFKeyHashAlreadyRegistered(p, v) => w.array(3).uint(6).bytes(p.bytes).bytes(v.bytes)
    ()
  }

  /** `EncCBOR (ConwayGovCertPredFailure era)` (`Conway/Rules/GovCert.hs:128-136`, cardano-ledger
    * f649f975): tags 0 to 5, the deposit's and the refund's mismatches in line (`ToGroup`).
    */
  private def govCert(f: ConwayGovCertPredFailure[Conway]): Bytes = build { w =>
    import ConwayGovCertPredFailure.*
    f match
      case ConwayDRepAlreadyRegistered(c)          => w.array(2).uint(0); credential(w, c)
      case ConwayDRepNotRegistered(c)              => w.array(2).uint(1); credential(w, c)
      case ConwayDRepIncorrectDeposit(m)           => w.array(3).uint(2); group(w, m)(coin)
      case ConwayCommitteeHasPreviouslyResigned(c) => w.array(2).uint(3); credential(w, c)
      case ConwayDRepIncorrectRefund(m)            => w.array(3).uint(4); group(w, m)(coin)
      case ConwayCommitteeIsUnknown(c)             => w.array(2).uint(5); credential(w, c)
    ()
  }

  // ---- GOV

  /** `EncCBOR (ConwayGovPredFailure era)` (`Conway/Rules/Gov.hs:258-298`, cardano-ledger f649f975):
    * tags 0 to 18, each list in the order the failure holds it, the deposit's mismatch and the hard
    * fork's versions in line (`ToGroup`), each version `[major, minor]`.
    */
  def gov(f: ConwayGovPredFailure[Conway]): Result[Bytes] =
    import ConwayGovPredFailure.*
    def votes(
        w: W,
        vs: NonEmptyList[(xyz.kd5ujc.scarab.ledger.Voter, xyz.kd5ujc.scarab.ledger.GovActionId)]
    ): Unit =
      list(w, vs.toList) { case (k, (v, id)) =>
        k.array(2); voter(k, v); PredicateFailureCbor.govActionId(k, id); ()
      }; ()
    def tagged(tag: Int)(body: W => Unit): Result[Bytes] = Right(build { w =>
      w.array(2).uint(tag); body(w)
    })
    f match
      case GovActionsDoNotExist(ids) =>
        tagged(0) { w =>
          list(w, ids.toList)(PredicateFailureCbor.govActionId(_, _)); ()
        }
      case MalformedProposal(a) => tagged(1)(raw(_, govAction(a)))
      case ProposalProcedureNetworkIdMismatch(a, network) =>
        Right(build { w =>
          w.array(3).uint(2); account(w, a); w.uint(network.wire); ()
        })
      case TreasuryWithdrawalsNetworkIdMismatch(as, network) =>
        Right(build { w =>
          w.array(3).uint(3)
          set(w, as.toSortedSet.toList.map(Convert.rewardAccountBack).toSet)(rewardAccount)
          w.uint(network.wire); ()
        })
      case ProposalDepositIncorrect(m) =>
        Right(build { w =>
          w.array(3).uint(4); group(w, m)(coin)
        })
      case DisallowedVoters(vs) => tagged(5)(votes(_, vs))
      case ConflictingCommitteeUpdate(ms) =>
        tagged(6) { w =>
          set(w, ms.toSortedSet)(credential); ()
        }
      case ExpirationEpochTooSmall(ms) =>
        tagged(7) { w =>
          map(w, ms.toSortedMap.toMap)(
            credential,
            (k, e) => { put(k, e)(using HaskellStateCodecs.epochNo); () }
          ); ()
        }
      case InvalidPrevGovActionId(p)    => tagged(8)(raw(_, proposal(p)))
      case VotingOnExpiredGovAction(vs) => tagged(9)(votes(_, vs))
      case ProposalCantFollow(parent, m) =>
        Right(build { w =>
          w.array(4).uint(10)
          maybe(w, parent.map(_.unGovPurposeId))(PredicateFailureCbor.govActionId(_, _))
          group(w, m)(era(EncCBOR.protVer))
        })
      case InvalidGuardrailsScriptHash(proposed, constitution) =>
        Right(build { w =>
          w.array(3).uint(11)
          maybeHash(w, proposed.map(_.bytes)); maybeHash(w, constitution.map(_.bytes)); ()
        })
      case DisallowedProposalDuringBootstrap(p) => tagged(12)(raw(_, proposal(p)))
      case DisallowedVotesDuringBootstrap(vs)   => tagged(13)(votes(_, vs))
      case VotersDoNotExist(vs) =>
        tagged(14) { w =>
          list(w, vs.toList)(voter(_, _)); ()
        }
      case ZeroTreasuryWithdrawals(a) => tagged(15)(raw(_, govAction(a)))
      case ProposalReturnAccountDoesNotExist(a) =>
        tagged(16) { w =>
          account(w, a); ()
        }
      case TreasuryWithdrawalReturnAccountsDoNotExist(as) =>
        tagged(17) { w =>
          list(w, as.toList)(account(_, _)); ()
        }
      case UnelectedCommitteeVoters(cs) =>
        tagged(18) { w =>
          list(w, cs.toList)(PredicateFailureCbor.credential(_, _)); ()
        }
