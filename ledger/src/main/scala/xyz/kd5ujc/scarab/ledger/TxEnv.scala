// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.show.*
import xyz.kd5ujc.scarab.codec.DomainError
import cats.Id
import cats.data.NonEmptyChain
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

  case PoolMetadata(cause: LedgerDecodeError)

  def message: String = this match
    case DelegationWithoutDelegatee   => "a delegation to nothing"
    case PreConwayCertificate(c)      => s"a pre-Conway certificate in Conway: $c"
    case ConwayCertificate(c)         => s"certificate $c before Conway"
    case GovernanceFieldsBeforeConway => "governance fields before Conway"
    case PlutusFieldsBeforeAlonzo     => "Plutus fields before Alonzo"
    case AuxiliaryData(cause)         => s"malformed auxiliary data: ${cause.show}"
    case UnreadableProtocolVersion    => "an update with an unreadable protocol version"
    case PoolMetadata(cause)          => s"invalid pool metadata: ${cause.message}"

/** Where this implementation could not supply a script verdict. */
enum ScriptLocation:
  case Phase1(scriptHash: Bytes)
  case Phase2(redeemer: Redeemer)

/** Why the ledger refused a transaction: the predicate failures of the rules
  * ([[LedgerFailure.Rejected]]), or a defect ([[LedgerDefect]]) that leaves no verdict of the
  * rules.
  */
sealed trait LedgerFailure extends DomainError:
  def message: String = this match
    case LedgerFailure.Rejected(id, fs) =>
      s"tx ${id.hex.take(16)} violates ${fs.iterator.map(_.detail).mkString("; ")}"
    case LedgerFailure.Undecodable(id, d) =>
      s"tx ${id.hex.take(16)} cannot be decoded: ${d.message}"
    case LedgerFailure.Unavailable(id, location, why) =>
      val where = location match
        case ScriptLocation.Phase1(hash) => s"phase 1 script ${hash.hex.take(16)}"
        case ScriptLocation.Phase2(r)    => s"phase 2 redeemer ${r.tag}.${r.index}"
      s"tx ${id.hex.take(16)} evaluation unavailable at $where: ${why.message}"
    case LedgerFailure.Vocab(e) => s"transaction outside the state's vocabulary: $e"

/** What ends a judgment of the rules without a verdict: the transaction cannot be what its era
  * decodes, a script cannot be evaluated here, or a value is outside the state's vocabulary. The
  * reference has no predicate failure for any of them, so none is part of a refusal's list.
  */
sealed trait LedgerDefect extends LedgerFailure

object LedgerFailure:
  /** The rules refused the transaction; `failures` are the ledger's predicate failures for it, in
    * the order the reference reports them (`ApplyTxError (NonEmpty …)`).
    */
  final case class Rejected(txId: Bytes, failures: NonEmptyChain[PredicateFailure])
      extends LedgerFailure

  object Rejected:
    /** A refusal for one predicate failure. */
    def apply(txId: Bytes, failure: PredicateFailure): Rejected =
      Rejected(txId, NonEmptyChain.one(failure))

    /** A refusal whose list is exactly one predicate failure. */
    object Single:
      def unapply(rejected: Rejected): Option[(Bytes, PredicateFailure)] =
        rejected.failures.uncons match
          case (failure, rest) if rest.isEmpty => Some((rejected.txId, failure))
          case _                               => None

  /** The transaction holds what its era's decoder would not have admitted (see [[TxDefect]]). */
  final case class Undecodable(txId: Bytes, defect: TxDefect) extends LedgerDefect

  /** This implementation could not evaluate a script; no consensus verdict exists. */
  final case class Unavailable(
      txId: Bytes,
      location: ScriptLocation,
      reason: xyz.kd5ujc.scarab.plutus.EvalFailure.Unsupported
  ) extends LedgerDefect

  /** A value of the transaction is not a member of the vocabulary type that the era-indexed state
    * holds it in (a credential of another length, a quantity outside its word). The decoder admits
    * none, so a decoded transaction does not reach it; it is the total form of a conversion the
    * rules of the era-indexed ledger need (design `ledger-mirror-step2.md` §4, J2.4).
    */
  final case class Vocab(error: VocabError) extends LedgerDefect

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
