// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import cats.Id
import lab.cbor.Bytes
import lab.node.plutus.{ScalusEvaluator, ScriptDecoder, ScriptEvaluator}

/** Why the ledger refused a transaction. `Unsupported` marks a valid-chain feature this node does
  * not implement yet; `Invalid` is a rule violation.
  */
enum LedgerFailure:
  case Unsupported(txId: Bytes, feature: String)

  /** A rule refused the transaction; `failure` is the ledger's predicate failure for it. */
  case Rejected(txId: Bytes, failure: PredicateFailure)
  case Malformed(detail: String)

  def message: String = this match
    case Unsupported(id, f) => s"tx ${id.hex.take(16)} uses unsupported $f"
    case Rejected(id, f) =>
      s"tx ${id.hex.take(16)} violates ${f.ruleName}: ${LedgerFailure.detail(f)}"
    case Malformed(d) => s"malformed transaction: $d"

object LedgerFailure:
  /** A refusal named by its rule and a free-text detail, for the rules whose payload is not
    * modelled (see [[PredicateFailure.Unmapped]]); pattern-matches any refusal as rule and detail.
    */
  object Invalid:
    def apply(txId: Bytes, rule: String, detail: String): LedgerFailure =
      Rejected(txId, PredicateFailure.Unmapped(rule, detail))
    def unapply(failure: LedgerFailure): Option[(Bytes, String, String)] = failure match
      case Rejected(id, f) => Some((id, f.ruleName, detail(f)))
      case _               => None

  /** The payload of a failure as text. */
  def detail(f: PredicateFailure): String = f match
    case PredicateFailure.Unmapped(_, d) => d
    case PredicateFailure.Mempool(t)     => t
    case other                           => other.toString.take(400)

/** The transaction rules of the current era: Conway ([[ConwayRules]]) from protocol version 9, else
  * [[PreConwayRules]].
  */
object TxRules:
  type Result[A] = Either[LedgerFailure, A]

  /** Block-level context of a transaction. */
  final case class Env(
      slot: BigInt,
      epoch: BigInt,
      network: Int,
      firstSlotNextEpoch: BigInt = 0,
      stabilityWindow: BigInt = 0,
      txIndex: Int = 0,
      updateQuorum: Int = 5,
      /** Slot to POSIX milliseconds through the era history (needed for the script context). */
      clock: Option[BigInt => BigInt] = None,
      /** The Plutus evaluator; a pure function of the request (see `ScriptEvaluator`). */
      evaluator: ScriptEvaluator[Id] = ScalusEvaluator.default,
      /** Decides whether a Plutus script deserialises (phase 1 from Babbage on). */
      decoder: ScriptDecoder[Id] = ScalusEvaluator.defaultDecoder,
      /** Tooling hook called after each phase-2 evaluation; never affects the result. */
      observer: Option[Phase2.Observation => Unit] = None
  )

  def apply(state: LedgerState, tx: Tx, env: Env): Result[LedgerState] =
    if state.params.protocolMajor >= 9 then ConwayRules(state, tx, env)
    else PreConwayRules(state, tx, env)
