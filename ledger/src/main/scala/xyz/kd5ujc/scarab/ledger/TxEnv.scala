// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.DomainError
import cats.Id
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.state.VocabError
import xyz.kd5ujc.scarab.plutus.{ScalusEvaluator, ScriptDecoder, ScriptEvaluator}

/** What a decoded transaction holds that its era cannot have. The reference ledger refuses all of
  * these in its transaction decoder (a `DecoderError`), before any rule runs, so none is a
  * predicate failure there; the node decodes one transaction type for every era and meets them in
  * the rules.
  */
enum TxDefect:
  /** A delegation certificate of Conway that names neither a pool nor a DRep. */
  case DelegationWithoutDelegatee

  /** A certificate of the eras before Conway (a genesis delegation, a MIR certificate) in a Conway
    * transaction.
    */
  case PreConwayCertificate(certificate: Certificate)

  /** A certificate of Conway (a DRep or committee certificate) in a transaction of an earlier era.
    */
  case ConwayCertificate(certificate: Certificate)

  /** Votes, proposals or a donation in a transaction before Conway. */
  case GovernanceFieldsBeforeConway

  /** Redeemers, datums or a script data hash in a transaction before Alonzo. */
  case PlutusFieldsBeforeAlonzo

  /** Auxiliary data is not admitted by the era's decoder. */
  case AuxiliaryData(cause: LedgerDecodeError)

  /** The protocol version of an update proposal is not a pair of numbers. */
  case UnreadableProtocolVersion

  def message: String = this match
    case DelegationWithoutDelegatee   => "a delegation to nothing"
    case PreConwayCertificate(c)      => s"a pre-Conway certificate in Conway: $c"
    case ConwayCertificate(c)         => s"certificate $c before Conway"
    case GovernanceFieldsBeforeConway => "governance fields before Conway"
    case PlutusFieldsBeforeAlonzo     => "Plutus fields before Alonzo"
    case AuxiliaryData(cause)         => s"malformed auxiliary data: $cause"
    case UnreadableProtocolVersion    => "an update with an unreadable protocol version"

/** Where this implementation could not supply a script verdict. */
enum ScriptLocation:
  case Phase1(scriptHash: Bytes)
  case Phase2(redeemer: Redeemer)

/** Why the ledger refused a transaction. */
enum LedgerFailure extends DomainError:
  /** A rule refused the transaction; `failure` is the ledger's predicate failure for it. */
  case Rejected(txId: Bytes, failure: PredicateFailure)

  /** The transaction holds what its era's decoder would not have admitted (see [[TxDefect]]). */
  case Undecodable(txId: Bytes, defect: TxDefect)

  /** This implementation could not evaluate a script; no consensus verdict exists. */
  case Unavailable(
      txId: Bytes,
      location: ScriptLocation,
      reason: xyz.kd5ujc.scarab.plutus.EvalFailure.Unsupported
  )

  /** A value of the transaction is not a member of the vocabulary type that the era-indexed state
    * holds it in (a credential of another length, a quantity outside its word). The decoder admits
    * none, so a decoded transaction does not reach it; it is the total form of a conversion the
    * rules of the era-indexed ledger need (design `ledger-mirror-step2.md` §4, J2.4).
    */
  case Vocab(error: VocabError)

  def message: String = this match
    case Rejected(id, f) =>
      s"tx ${id.hex.take(16)} violates ${f.detail}"
    case Undecodable(id, d) => s"tx ${id.hex.take(16)} cannot be decoded: ${d.message}"
    case Unavailable(id, location, why) =>
      val where = location match
        case ScriptLocation.Phase1(hash) => s"phase 1 script ${hash.hex.take(16)}"
        case ScriptLocation.Phase2(r)    => s"phase 2 redeemer ${r.tag}.${r.index}"
      s"tx ${id.hex.take(16)} evaluation unavailable at $where: ${why.message}"
    case Vocab(e) => s"transaction outside the state's vocabulary: $e"

/** Block-level context of a transaction.
  *
  * @param clock
  *   Slot to POSIX milliseconds through the era history (needed for the script context).
  * @param evaluator
  *   The Plutus evaluator; a pure function of the request (see `ScriptEvaluator`).
  * @param decoder
  *   Decides whether a Plutus script deserialises (phase 1 from Babbage on).
  * @param observer
  *   Tooling hook receiving the transaction's collected phase-2 outcome. Its return value does not
  *   supply a verdict; callbacks must complete normally.
  * @param verdicts
  *   Signature verdicts (memoised ahead of time in the node, computed in place otherwise).
  */
final case class TxEnv(
    slot: BigInt,
    epoch: BigInt,
    network: Int,
    firstSlotNextEpoch: BigInt = 0,
    stabilityWindow: BigInt = 0,
    txIndex: Int = 0,
    updateQuorum: Int = 5,
    clock: Option[BigInt => BigInt] = None,
    evaluator: ScriptEvaluator[Id] = ScalusEvaluator.default,
    decoder: ScriptDecoder[Id] = ScalusEvaluator.defaultDecoder,
    observer: Option[Phase2.Observation => Unit] = None,
    verdicts: xyz.kd5ujc.scarab.ledger.Verdicts = xyz.kd5ujc.scarab.ledger.Verdicts.Direct,
    private[ledger] val preparation: Option[Phase2.Preparation] = None
)

object TxEnv:
  type Result[A] = Either[LedgerFailure, A]
