// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era

/** Why a header is refused before its block is fetched. Closed: every rule family has its case. */
enum HeaderError:
  /** The envelope rules (era, protocol version, header and body size). */
  case Envelope(error: EnvelopeError)

  /** The Praos rules: VRF key and proof, leader threshold, operational certificate and KES
    * signature, certificate counter.
    */
  case Praos(reason: String)

  /** The TPraos rules (Shelley to Alonzo): overlay schedule, VRF proofs, threshold, certificate. */
  case TPraos(error: TPraosError)

  /** The header cannot belong to the chain it claims to extend. */
  case Structure(reason: String)

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
    case Envelope(e)  => s"envelope: ${e.message}"
    case Praos(r)     => s"praos: $r"
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

/** Why a TPraos (Shelley..Alonzo) header is rejected. Closed: every rule has its own case. */
enum TPraosError:
  case NotTPraos(era: String)
  case Malformed(reason: String)
  case VrfKeyUnknown(pool: Bytes)
  case VrfKeyWrong(pool: Bytes)
  case BadNonceVrf(reason: String)
  case BadLeaderVrf(reason: String)
  case LeaderValueTooBig(slot: BigInt)
  case NotActiveOverlaySlot(slot: BigInt)
  case UnknownGenesisKey(key: Bytes)
  case WrongGenesisColdKey(issuer: Bytes, expected: Bytes)
  case WrongGenesisVrfKey(got: Bytes, expected: Bytes)
  case OCert(error: OCertError)

  def message: String = this match
    case NotTPraos(e)            => s"$e headers are not TPraos headers"
    case Malformed(r)            => r
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
