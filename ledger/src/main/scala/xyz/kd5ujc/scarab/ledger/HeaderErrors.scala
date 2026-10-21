// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.crypto.vrf.{PraosLeaderThreshold as Leader, PraosVrfCertificate as Vrf}
import xyz.kd5ujc.scarab.ledger.state.VocabError

/** Why a header is refused before its block is fetched. Closed: every rule family has its case. */
enum HeaderError:
  case Byron(error: ByronChainError)

  /** The envelope rules (era, protocol version, header and body size). */
  case Envelope(error: EnvelopeError)

  /** The Praos rules: VRF key and proof, leader threshold, operational certificate and KES
    * signature, certificate counter.
    */
  case Praos(error: PraosError)

  /** The TPraos rules (Shelley to Alonzo): overlay schedule, VRF proofs, threshold, certificate. */
  case TPraos(error: TPraosError)

  /** The header cannot belong to the chain it claims to extend. */
  case Structure(cause: HeaderStructureProblem)

  /** The header's slot begins further ahead of our clock, at the moment it arrived, than the
    * tolerable clock skew (`FarFutureHeaderException`). Its issuer minted it before its slot, or
    * its clock differs from ours by more than we accept.
    *
    * @param slot
    *   the header's slot
    * @param aheadMillis
    *   how long after its arrival the slot begins
    * @param skewMillis
    *   the tolerable clock skew
    */
  case FarFuture(slot: BigInt, aheadMillis: BigInt, skewMillis: Long)

  def message: String = this match
    case FarFuture(s, ahead, skew) =>
      s"slot $s begins $ahead ms after the header arrived, beyond the tolerable clock skew of $skew ms"
    case Byron(e)     => s"byron: ${e.message}"
    case Envelope(e)  => s"envelope: ${e.message}"
    case Praos(e)     => s"praos: ${e.message}"
    case TPraos(e)    => s"tpraos: ${e.message}"
    case Structure(r) => s"structure: $r"

/** Why a header fails the envelope checks (ouroboros-consensus `EnvelopeError`, plus the era
  * history and the Conway ledger's header protocol-version rule). A closed set; `message` is the
  * rendering used in validation errors.
  */
enum EnvelopeError:
  /** The ledger (Praos) or the header (TPraos) announces a major version above what this node
    * supports.
    */
  case ObsoleteNode(version: BigInt, maxSupported: BigInt)
  case HeaderSizeTooLarge(size: BigInt, max: BigInt)
  case BlockSizeTooLarge(size: BigInt, max: BigInt)

  /** Conway BBODY on mainnet: the header's major version may exceed the ledger's by at most one. */
  case HeaderProtVerTooHigh(supplied: BigInt, expected: BigInt)

  /** The block's era is not the era the hard-fork combinator is in at its epoch. */
  case WrongEra(header: Era, expected: Era, epoch: BigInt, ledgerMajor: BigInt)

  def message: String = this match
    case ObsoleteNode(v, max) =>
      s"obsolete node: protocol major version $v exceeds the maximum $max this node supports"
    case HeaderSizeTooLarge(s, max) => s"header size $s exceeds $max"
    case BlockSizeTooLarge(s, max)  => s"body size $s exceeds $max"
    case HeaderProtVerTooHigh(s, e) => s"header protocol major version $s exceeds the allowed $e"
    case WrongEra(h, e, epoch, major) =>
      s"$h block in epoch $epoch at protocol major version $major, the chain is in $e"

/** Praos rule failures, preserving the crypto, vocabulary and certificate causes. */
enum PraosError:
  case NotPraos(era: Era)
  case TPraosHeader(era: Era)
  case WrongState(era: Era)
  case Vocabulary(cause: VocabError)
  case IssuerWithoutStake(pool: Bytes)
  case UnregisteredPool(pool: Bytes)
  case VrfKeyMismatch(pool: Bytes, actual: Bytes, registered: Set[Bytes])
  case VrfInput(cause: Vrf.Failure)
  case VrfRejected(cause: Vrf.Result)
  case LeaderThreshold(cause: Leader.Error)
  case NotElected(slot: BigInt, decision: Leader.Decision)
  case OCert(cause: OCertError)

  def message: String = this match
    case NotPraos(e)     => s"$e headers are not Praos headers"
    case TPraosHeader(e) => s"$e headers use TPraos, not Praos"
    case WrongState(e) =>
      s"$e headers use Praos, but the chain-dependent state is not Praos's"
    case Vocabulary(e)           => e.toString
    case IssuerWithoutStake(p)   => s"issuer ${p.hex} has no stake in the pool distribution"
    case UnregisteredPool(p)     => s"issuer ${p.hex} is not a registered pool"
    case VrfKeyMismatch(_, _, _) => "VRF key is not the pool's registered key"
    case VrfInput(e)             => e.toString
    case VrfRejected(e)          => s"VRF proof rejected: $e"
    case LeaderThreshold(e)      => e.message
    case NotElected(s, d)        => s"issuer not elected for slot $s: $d"
    case OCert(e)                => e.message

/** Why a TPraos (Shelley..Alonzo) header is rejected. Closed: every rule has its own case. */
enum TPraosError:
  case NotTPraos(era: Era)
  case PraosHeader(era: Era)
  case WrongState(era: Era)
  case Vocabulary(cause: VocabError)
  case LeaderThreshold(cause: Leader.Error)
  case VrfKeyUnknown(pool: Bytes)
  case VrfKeyWrong(pool: Bytes)
  case BadNonceVrf(cause: Vrf.Result)
  case BadLeaderVrf(cause: Vrf.Result)
  case LeaderValueTooBig(slot: BigInt)
  case NotActiveOverlaySlot(slot: BigInt)
  case UnknownGenesisKey(key: Bytes)
  case WrongGenesisColdKey(issuer: Bytes, expected: Bytes)
  case WrongGenesisVrfKey(got: Bytes, expected: Bytes)
  case OCert(error: OCertError)

  def message: String = this match
    case NotTPraos(e)   => s"$e headers are not TPraos headers"
    case PraosHeader(e) => s"$e (Praos) headers are not TPraos headers"
    case WrongState(e) =>
      s"$e (the chain-dependent state is not TPraos's) headers are not TPraos headers"
    case Vocabulary(e)           => e.toString
    case LeaderThreshold(e)      => e.message
    case VrfKeyUnknown(p)        => s"issuer ${p.hex} is not a registered pool"
    case VrfKeyWrong(p)          => s"VRF key is not the registered key of pool ${p.hex}"
    case BadNonceVrf(r)          => s"nonce VRF proof rejected: $r"
    case BadLeaderVrf(r)         => s"leader VRF proof rejected: $r"
    case LeaderValueTooBig(s)    => s"issuer not elected for slot $s: leader value above threshold"
    case NotActiveOverlaySlot(s) => s"slot $s is a non-active overlay slot: no block allowed"
    case UnknownGenesisKey(k)    => s"genesis key ${k.hex} has no delegation"
    case WrongGenesisColdKey(i, e) =>
      s"issuer ${i.hex} is not the genesis delegate ${e.hex} for this overlay slot"
    case WrongGenesisVrfKey(g, e) =>
      s"VRF key hash ${g.hex} is not the genesis delegate's ${e.hex}"
    case OCert(e) => e.message

/** Structural header failures with their original ledger-read cause. */
enum HeaderStructureProblem:
  case BlockNumber(actual: BigInt, expected: BigInt)
  case SlotNumber(actual: BigInt, minimum: BigInt)
  case PreviousHash(actual: Option[Bytes], expected: Option[Bytes])
  case UnexpectedBoundarySlot(slot: BigInt, epochSlots: BigInt)
  case Transition(cause: BlockFailure)
  case MissingByronLedger
  case LedgerRead(cause: xyz.kd5ujc.scarab.ledger.state.MirrorError)
  case EpochPrecedes(epoch: BigInt, ledgerEpoch: BigInt)
