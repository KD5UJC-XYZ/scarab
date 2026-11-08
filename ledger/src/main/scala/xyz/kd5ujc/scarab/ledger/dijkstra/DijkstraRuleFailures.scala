// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Credential,
  DelegFailure,
  GovCertFailure,
  GovFailure,
  LedgerFailure,
  Mismatch,
  Nonce,
  PredicateFailure,
  TxOut,
  UtxosFailure
}
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.protocol.DijkstraNotYet

// The predicate failures of the Dijkstra rules of cardano-ledger 9df9f98c
// (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/`), each type with the constructors of
// the reference's in its order, and `tag` the number its `EncCBOR` writes (`Sum _ n`). The
// encoders themselves belong to the node-to-client queries. Where a Dijkstra failure type is a
// one-to-one copy of Conway's (`conwayToDijkstraGovPredFailure`, `Gov.hs:222-243`;
// `conwayToDijkstraGovCertPredFailure`, `GovCert.hs:118-126`), Conway's type is reused: the cases
// and their numbers are the same. The arms of the reference that are `error "Impossible"` have no
// case here.

/** `DijkstraBbodyPredFailure` (`Rules/Bbody.hs:79-88`, encoded at 109-117). */
enum DijkstraBbodyFailure(val tag: Int):
  case WrongBlockBodySize(mismatch: Mismatch[BigInt]) extends DijkstraBbodyFailure(0)
  case InvalidBodyHash(mismatch: Mismatch[Bytes]) extends DijkstraBbodyFailure(1)

  /** `LedgersFailure` of `ShelleyLedgersPredFailure` (`Rules/Ledgers.hs:22`), whose one case is
    * `LedgerFailure` of the transaction's LEDGER failure.
    */
  case LedgersFailure(failure: DijkstraLedgerFailure) extends DijkstraBbodyFailure(2)
  case TooManyExUnits(mismatch: Mismatch[ExUnits]) extends DijkstraBbodyFailure(3)
  case BodyRefScriptsSizeTooBig(mismatch: Mismatch[Long]) extends DijkstraBbodyFailure(4)

  /** The Peras certificate of the body, as its bytes, and the header's previous nonce. */
  case PerasCertValidationFailed(certificate: Bytes, nonce: Nonce) extends DijkstraBbodyFailure(5)

  /** The header's highest supported major version against the major of the parameters. */
  case HeaderProtVerTooLow(mismatch: Mismatch[BigInt]) extends DijkstraBbodyFailure(6)

/** `DijkstraLedgerPredFailure` (`Rules/Ledger.hs:98-105`, encoded at 252-259). ENTITIES and
  * SUBLEDGERS are rules of their own whose failures this build does not produce yet; their cases
  * carry the rules' failure types when those rules exist. `conwayToDijkstraLedgerPredFailure`
  * (`Ledger.hs:530-543`) has no counterpart of `ConwayWdrlNotDelegatedToDRep` or
  * `ConwayMempoolFailure`.
  */
enum DijkstraLedgerFailure(val tag: Int):
  case UtxowFailure(failure: DijkstraUtxowFailure) extends DijkstraLedgerFailure(1)
  case GovFailure(failure: xyz.kd5ujc.scarab.ledger.GovFailure) extends DijkstraLedgerFailure(3)
  case TreasuryValueMismatch(mismatch: Mismatch[BigInt]) extends DijkstraLedgerFailure(4)
  case TxRefScriptsSizeTooBig(mismatch: Mismatch[Int]) extends DijkstraLedgerFailure(5)

/** The tags of `DijkstraLedgerPredFailure` whose sub-rules this build does not run yet. */
object DijkstraLedgerFailure:
  /** `DijkstraEntitiesFailure`. */
  val EntitiesTag: Int = 2

  /** `DijkstraSubLedgersFailure`. */
  val SubLedgersTag: Int = 6

/** `DijkstraUtxowPredFailure` (`Rules/Utxow.hs:93-142`, encoded at 381-401). Key and script hashes,
  * data hashes and auxiliary data hashes are their bytes; a redeemer purpose is [[DijkstraPurpose]]
  * (`AsItem`, with the script hash) or its tag and index (`AsIx`).
  */
enum DijkstraUtxowFailure(val tag: Int):
  case UtxoFailure(failure: DijkstraUtxoFailure) extends DijkstraUtxowFailure(0)
  case InvalidWitnesses(keys: Vector[Bytes]) extends DijkstraUtxowFailure(1)
  case MissingVKeyWitnesses(hashes: Set[Bytes]) extends DijkstraUtxowFailure(2)
  case MissingScriptWitnesses(hashes: Set[Bytes]) extends DijkstraUtxowFailure(3)
  case ScriptWitnessNotValidating(hashes: Set[Bytes]) extends DijkstraUtxowFailure(4)
  case MissingTxBodyMetadataHash(hash: Bytes) extends DijkstraUtxowFailure(5)
  case MissingTxMetadata(hash: Bytes) extends DijkstraUtxowFailure(6)
  case ConflictingMetadataHash(mismatch: Mismatch[Bytes]) extends DijkstraUtxowFailure(7)
  case InvalidMetadata extends DijkstraUtxowFailure(8)
  case ExtraneousScriptWitnesses(hashes: Set[Bytes]) extends DijkstraUtxowFailure(9)
  case MissingRedeemers(purposes: Vector[(DijkstraPurpose, Bytes)]) extends DijkstraUtxowFailure(10)
  case MissingRequiredDatums(missing: Set[Bytes], received: Set[Bytes])
      extends DijkstraUtxowFailure(11)
  case NotAllowedSupplementalDatums(unallowed: Set[Bytes], acceptable: Set[Bytes])
      extends DijkstraUtxowFailure(12)
  case PPViewHashesDontMatch(mismatch: Mismatch[Option[Bytes]]) extends DijkstraUtxowFailure(13)
  case UnspendableUTxONoDatumHash(inputs: Set[DijkstraTxIn]) extends DijkstraUtxowFailure(14)
  case ExtraRedeemers(purposes: Vector[(Int, Int)]) extends DijkstraUtxowFailure(15)
  case MalformedScriptWitnesses(hashes: Set[Bytes]) extends DijkstraUtxowFailure(16)
  case MalformedReferenceScripts(hashes: Set[Bytes]) extends DijkstraUtxowFailure(17)
  case ScriptIntegrityHashMismatch(mismatch: Mismatch[Option[Bytes]], expected: Option[Bytes])
      extends DijkstraUtxowFailure(18)

  /** Guards the sub-transactions require that the top-level body does not list. */
  case MissingRequiredGuards(credentials: Set[Credential]) extends DijkstraUtxowFailure(19)

  /** Required top-level guards whose datum's presence does not fit the credential's kind. */
  case MalformedGuardDatums(credentials: Set[Credential]) extends DijkstraUtxowFailure(20)

/** `DijkstraUtxoPredFailure` (`Rules/Utxo.hs:110-171`, encoded at 497-520; 8 is unused). An output
  * of the transaction is a [[DijkstraTxOut]]; one of the UTxO set is a [[TxOut]].
  */
enum DijkstraUtxoFailure(val tag: Int):
  case UtxosFailure(failure: xyz.kd5ujc.scarab.ledger.UtxosFailure) extends DijkstraUtxoFailure(0)
  case BadInputs(inputs: Set[DijkstraTxIn]) extends DijkstraUtxoFailure(1)

  /** The validity interval (`invalidBefore`, `invalidHereafter`) and the current slot. */
  case OutsideValidityInterval(notBefore: Option[BigInt], before: Option[BigInt], slot: BigInt)
      extends DijkstraUtxoFailure(2)
  case MaxTxSize(mismatch: Mismatch[BigInt]) extends DijkstraUtxoFailure(3)
  case InputSetEmpty extends DijkstraUtxoFailure(4)
  case FeeTooSmall(mismatch: Mismatch[BigInt]) extends DijkstraUtxoFailure(5)

  /** Consumed (supplied) against produced (expected). */
  case ValueNotConserved(mismatch: Mismatch[DijkstraValue]) extends DijkstraUtxoFailure(6)
  case WrongNetwork(expected: Int, addresses: Set[Bytes]) extends DijkstraUtxoFailure(7)
  case OutputBootAddrAttrsTooBig(outputs: Vector[DijkstraTxOut]) extends DijkstraUtxoFailure(9)
  case OutputTooBig(outputs: Vector[(Int, Int, DijkstraTxOut)]) extends DijkstraUtxoFailure(10)
  case InsufficientCollateral(balance: BigInt, required: BigInt) extends DijkstraUtxoFailure(11)
  case ScriptsNotPaid(outputs: Map[DijkstraTxIn, TxOut]) extends DijkstraUtxoFailure(12)
  case ExUnitsTooBig(mismatch: Mismatch[ExUnits]) extends DijkstraUtxoFailure(13)
  case CollateralContainsNonAda(value: DijkstraValue) extends DijkstraUtxoFailure(14)
  case WrongNetworkInTxBody(mismatch: Mismatch[Int]) extends DijkstraUtxoFailure(15)
  case OutsideForecast(slot: BigInt) extends DijkstraUtxoFailure(16)
  case TooManyCollateralInputs(mismatch: Mismatch[Int]) extends DijkstraUtxoFailure(17)
  case NoCollateralInputs extends DijkstraUtxoFailure(18)
  case IncorrectTotalCollateralField(provided: BigInt, declared: BigInt)
      extends DijkstraUtxoFailure(19)
  case BabbageOutputTooSmall(outputs: Vector[(DijkstraTxOut, BigInt)])
      extends DijkstraUtxoFailure(20)
  case BabbageNonDisjointRefInputs(inputs: Vector[DijkstraTxIn]) extends DijkstraUtxoFailure(21)
  case PtrPresentInCollateralReturn(output: DijkstraTxOut) extends DijkstraUtxoFailure(22)

  /** A legacy-mode top-level transaction that does not balance on its own. */
  case ValueNotConservedInLegacyMode(mismatch: Mismatch[DijkstraValue])
      extends DijkstraUtxoFailure(23)

/** `DijkstraPoolPredFailure` (`Rules/Pool.hs:57-85`, encoded at 87-105; 2 is unused). */
enum DijkstraPoolFailure(val tag: Int):
  case StakePoolNotRegisteredOnKey(pool: Bytes) extends DijkstraPoolFailure(0)

  /** The epoch after the current (`RelGT`) and the latest allowed (`RelLTEQ`). */
  case StakePoolRetirementWrongEpoch(early: Mismatch[BigInt], late: Mismatch[BigInt])
      extends DijkstraPoolFailure(1)
  case StakePoolCostTooLow(mismatch: Mismatch[BigInt]) extends DijkstraPoolFailure(3)
  case WrongNetworkPool(mismatch: Mismatch[Int], pool: Bytes) extends DijkstraPoolFailure(4)
  case PoolMedataHashTooBig(pool: Bytes, size: Int) extends DijkstraPoolFailure(5)
  case VRFKeyHashAlreadyRegistered(pool: Bytes, vrf: Bytes) extends DijkstraPoolFailure(6)
  case BlsKeyInvalidProofOfPossession(pool: Bytes, key: DijkstraBlsKey)
      extends DijkstraPoolFailure(7)

/** `ConwayCertPredFailure` (`Conway/Rules/Cert.hs`), the failure of Dijkstra's CERT
  * (`Dijkstra/Rules/Cert.hs:74`): DELEG's is Conway's (`Rules/Deleg.hs:14`), POOL's and GOVCERT's
  * Dijkstra's ([[DijkstraPoolFailure]], and `DijkstraGovCertPredFailure`, a copy of Conway's
  * [[GovCertFailure]]).
  */
enum DijkstraCertFailure(val tag: Int):
  case Deleg(failure: DelegFailure) extends DijkstraCertFailure(1)
  case Pool(failure: DijkstraPoolFailure) extends DijkstraCertFailure(2)
  case GovCert(failure: GovCertFailure) extends DijkstraCertFailure(3)

/** `ConwayCertsPredFailure` at 9df9f98c (`Conway/Rules/Certs.hs:111-112`, encoded at 165): a
  * newtype of CERT's failure, tag 1.
  */
final case class DijkstraCertsFailure(failure: DijkstraCertFailure):
  def tag: Int = 1

/** What, of the Dijkstra rules, this build does not run yet. */
enum DijkstraPending:
  /** SUBLEDGERS over a transaction's sub-transactions (`Rules/SubLedgers.hs`). */
  case SubLedgers

  /** ENTITIES, the withdrawals, deposits and certificates of a valid transaction
    * (`Rules/Entities.hs`).
    */
  case Entities

  /** UTXOW and UTXO of a Dijkstra transaction (`Rules/Utxow.hs`, `Rules/Utxo.hs`). */
  case Utxow

  /** A pool registration: the pool state of the node holds the original encodings of a
    * registration's relays and metadata and no BLS key (`StakePoolParams`, `sppBlsKey`), which a
    * Dijkstra registration does not keep.
    */
  case PoolRegistration

  /** A parameter-change proposal: the store of proposals holds Conway updates
    * ([[xyz.kd5ujc.scarab.ledger.ConwayParamUpdate]]), not the Dijkstra one it carries.
    */
  case ParameterChangeProposal

/** What ends a judgment of the Dijkstra rules without a verdict. */
enum DijkstraHalt:
  /** A part of the rules this build does not implement yet. */
  case NotYet(part: DijkstraNotYet, pending: DijkstraPending)

  /** A defect raised by a Conway rule that Dijkstra reuses. */
  case Conway(failure: LedgerFailure)

  /** A predicate failure of a reused Conway rule that the Dijkstra rule has no case for. The reused
    * rules record none; it is a defect of the mapping.
    */
  case Unmapped(failure: PredicateFailure)

object DijkstraHalt:
  /** The refusal of a part not implemented yet (`DijkstraNotYet.Rules`). */
  def pending(what: DijkstraPending): DijkstraHalt = NotYet(DijkstraNotYet.Rules, what)

/** A block refused by Dijkstra's BBODY, or halted. */
enum DijkstraBlockFailure:
  case Rejected(failure: DijkstraBbodyFailure)
  case Halted(cause: DijkstraHalt)
