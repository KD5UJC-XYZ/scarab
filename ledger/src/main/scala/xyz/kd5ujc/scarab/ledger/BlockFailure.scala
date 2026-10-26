// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.show.*
import xyz.kd5ujc.scarab.codec.DomainError
import cats.{Eq, Show}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.ledger.rules.TickFailure
import xyz.kd5ujc.scarab.protocol.Era

/** The rule family a block was refused by. The names are those the JSON log has always carried in
  * its `rule` field (the devnet tools and the remote runs parse them), so [[name]] is fixed.
  */
enum BlockRule(val name: String):
  case Byron extends BlockRule("byron")
  case Structure extends BlockRule("structure")
  case Decode extends BlockRule("decode")
  case Envelope extends BlockRule("envelope")
  case Ledger extends BlockRule("ledger")
  case TPraos extends BlockRule("tpraos")
  case Praos extends BlockRule("praos")

object BlockRule:
  given Eq[BlockRule] = Eq.fromUniversalEquals
  given Show[BlockRule] = Show.show(_.name)

/** Why the node's block validation refused a block: a closed ADT shared by the bootstrap and
  * era-indexed rules, so that the differential harness compares verdicts as values.
  *
  * Every case carries what the rule found and renders its [[rule]] family and its [[reason]] text
  * (the two fields of the `rejected` log event, and of [[ValidationError]]); nothing branches on
  * the text. Nested rule and decoding failures retain their typed causes through this boundary.
  */
enum BlockFailure:
  // ---- structure: the block against the chain and its own header

  /** The decoded block's header is not the announced one. */
  case HeaderDiffers

  /** A cached body fails the transport predicate; retain the exact typed cause. */
  case Transport(cause: BlockTransportError)

  /** The body does not match the size and hash the header commits to. */
  case BodyCommitment

  /** The decoded block is of another era than the one announced. */
  case EraAnnounced(actual: Era, announced: Era)

  /** The block's epoch precedes the ledger's. */
  case EpochPrecedesLedger(epoch: BigInt)

  /** The ledger state at the block's slot is of another era than the block's (the hard-fork
    * combinator has not brought the chain into the era the header announces).
    */
  case LedgerEra(ledger: Era, block: Era)

  /** A Byron block after the Shelley era began. */
  case ByronAfterShelley

  /** A Byron block in or after the epoch the chain enters Shelley. */
  case ByronAfterTransition(epoch: BigInt, first: BigInt)

  // ---- decode

  /** The block does not decode. */
  case Decode(error: ChainError)

  /** A transaction of the block does not decode; its decoder failure is retained. */
  case TxDecode(txId: Bytes, error: LedgerDecodeError)

  // ---- byron

  /** A Byron block, but no Byron genesis is configured. */
  case NoByronGenesis

  /** The first Shelley block, but the Byron ledger has not decided on the transition. */
  case TransitionUndecided(era: Era)

  /** A Shelley block before the transition epoch, or inside the Byron span. */
  case ShelleyBeforeTransition(epoch: BigInt, first: BigInt, lastByronSlot: BigInt)

  /** The Byron rules refused the block, retaining their typed cause. */
  case Byron(cause: ByronChainError)

  // ---- envelope, headers

  /** The hard-fork combinator's envelope checks (era, versions, sizes). */
  case Envelope(error: EnvelopeError)

  /** A TPraos header rule. */
  case TPraos(error: TPraosError)

  /** A Praos header rule, retaining its typed cause. */
  case Praos(error: PraosError)

  /** A crypto provider failed while the `family` header rules of the block verified it
    * ([[ProviderDefect]]). Not a refusal: this implementation has no verdict on the block, and
    * [[ValidationError.unverifiable]] says so.
    */
  case Unverifiable(family: BlockRule, defect: ProviderDefect)

  // ---- ledger

  /** The ledger-lite rules of the bootstrap mode refused the block. */
  case Lite(error: LedgerDecodeError)

  /** The Byron-to-Shelley hand-over refused the final Byron state. */
  case Handover(cause: HandoverProblem)

  /** The total supply is not the maximum entering an epoch (before Conway). */
  case SupplyInvariant(epoch: BigInt, total: BigInt, maximum: BigInt)

  /** The reference scripts of the block exceed the Conway limit. */
  case RefScriptsTooLarge(limit: Long)

  /** The execution units of the block exceed the maximum of its parameters. */
  case BlockExUnitsTooBig(used: ExUnits, maximum: ExUnits)

  /** The ledger refused a transaction. */
  case Tx(failure: LedgerFailure)

  /** The era-indexed rules' tick of the ledger state to the block's slot refused (the state left
    * the range of a vocabulary type, or holds what the node does not carry on).
    */
  case Tick(failure: TickFailure)

  /** The mirror of the chain's state cannot hold the state the block leaves (its telescopes cannot
    * be extended into the era, a value is outside the range of the Haskell type). A total refusal:
    * a state the rules made is always in the range, and the era history is consistent with it.
    */
  case Mirror[E <: DomainError](cause: E) extends BlockFailure

  /** The rule family, as the log names it. */
  def rule: BlockRule = this match
    case HeaderDiffers | Transport(_) | BodyCommitment | EraAnnounced(_, _) |
        EpochPrecedesLedger(_) | LedgerEra(_, _) | ByronAfterShelley | ByronAfterTransition(_, _) =>
      BlockRule.Structure
    case Decode(_) | TxDecode(_, _) => BlockRule.Decode
    case NoByronGenesis | TransitionUndecided(_) | ShelleyBeforeTransition(_, _, _) | Byron(_) =>
      BlockRule.Byron
    case Envelope(_)        => BlockRule.Envelope
    case TPraos(_)          => BlockRule.TPraos
    case Praos(_)           => BlockRule.Praos
    case Unverifiable(r, _) => r
    case Lite(_) | Handover(_) | SupplyInvariant(_, _, _) | RefScriptsTooLarge(_) |
        BlockExUnitsTooBig(_, _) | Tx(_) | Tick(_) | Mirror(_) =>
      BlockRule.Ledger

  /** The reason, as the log renders it (the text of the old `(rule, reason)` pair, unchanged). */
  def reason: String = this match
    case HeaderDiffers                   => "block header differs from announced header"
    case Transport(cause)                => cause.message
    case BodyCommitment                  => "body does not match the header commitment"
    case EraAnnounced(actual, announced) => s"$actual block announced as $announced"
    case EpochPrecedesLedger(epoch)      => s"block epoch $epoch precedes ledger epoch"
    case LedgerEra(ledger, block)        => s"the ledger is in $ledger, the block is of $block"
    case ByronAfterShelley               => "Byron block after the Shelley era began"
    case ByronAfterTransition(epoch, first) =>
      s"Byron block in epoch $epoch, the chain enters Shelley in epoch $first"
    case Decode(error)      => error.message
    case TxDecode(id, text) => s"tx ${id.hex.take(16)}: ${text.show}"
    case NoByronGenesis     => "no Byron genesis configured"
    case TransitionUndecided(era) =>
      s"$era block, but the Byron ledger has not decided on the transition to Shelley"
    case ShelleyBeforeTransition(epoch, first, last) =>
      s"Shelley block in epoch $epoch, the transition is to epoch $first after Byron slot $last"
    case Byron(error)            => error.message
    case Envelope(error)         => error.message
    case TPraos(error)           => error.message
    case Praos(error)            => error.message
    case Unverifiable(_, defect) => s"no verdict: ${defect.message}"
    case Lite(error)             => error.message
    case Handover(error)         => error.message
    case SupplyInvariant(epoch, total, maximum) =>
      s"supply invariant broken entering epoch $epoch: $total != $maximum"
    case RefScriptsTooLarge(limit) => s"reference scripts of the block exceed $limit bytes"
    case BlockExUnitsTooBig(used, maximum) =>
      s"block execution units ${used.mem} mem / ${used.steps} steps exceed the maximum ${maximum.mem} / ${maximum.steps}"
    case Tx(failure)   => failure.message
    case Tick(failure) => s"tick refused: ${Show[TickFailure].show(failure)}"
    case Mirror(text)  => s"the state cannot be held: $text"

object BlockFailure:
  extension (failure: BlockFailure)
    /** The quantity of the ledger state outside its vocabulary type that `failure` reports, when it
      * reports one: the tick's ([[TickFailure.Vocab]]) or a transaction rule's
      * ([[LedgerFailure.StateOutOfRange]]). The reference aborts there (`compactCoinOrError`), so
      * it is the node's stop ([[ChainError.StateOutOfRange]]) and never a refusal. A number taken
      * from the block ([[TickFailure.Input]], [[LedgerFailure.Vocab]]) is a refusal.
      */
    def stateOutOfRange: Option[xyz.kd5ujc.scarab.ledger.state.VocabError] = failure match
      case Tick(TickFailure.Vocab(error))              => Some(error)
      case Tx(LedgerFailure.StateOutOfRange(_, error)) => Some(error)
      case _                                           => None

  // Plain data all through (Bytes and the ledger's failure records compare structurally).
  given Eq[BlockFailure] = Eq.fromUniversalEquals
  given Show[BlockFailure] = Show.show(f => s"${f.rule.name}: ${f.reason}")

/** Missing configuration contexts at the Byron to Shelley transition. */
enum HandoverProblem:
  case MissingShelleyContext
  case MissingByronRules
  def message: String = this match
    case MissingShelleyContext => "the Shelley genesis gives no hand-over context"
    case MissingByronRules     => "a Byron state, and no Byron rules to leave it"
