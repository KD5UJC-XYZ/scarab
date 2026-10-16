// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.{Eq, Show}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.ExUnits
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

/** Why `NodeRules.applyBlock` refused a block (design D6 of the ledger-state mirror, step 2): a
  * closed ADT in place of the `(rule, reason)` pair of strings, used by the old rules now and by
  * the era-indexed ones when they are ported, so that the differential harness compares verdicts as
  * values.
  *
  * Every case carries what the rule found and renders its [[rule]] family and its [[reason]] text
  * (the two fields of the `rejected` log event, and of [[ValidationError]]); nothing branches on
  * the text. The three cases that still carry the text of a rule not yet closed (`Byron`, `Praos`,
  * `Lite` /`Handover`, `TxDecode`) say so: their upstream returns `String`, and they go when that
  * does (the transitional constructors, as `PredicateFailure.Unmapped`).
  */
enum BlockFailure:
  // ---- structure: the block against the chain and its own header

  /** The decoded block's header is not the announced one. */
  case HeaderDiffers

  /** The body does not match the size and hash the header commits to. */
  case BodyCommitment

  /** The decoded block is of another era than the one announced. */
  case EraAnnounced(actual: Era, announced: Era)

  /** The block's epoch precedes the ledger's. */
  case EpochPrecedesLedger(epoch: BigInt)

  /** A Byron block after the Shelley era began. */
  case ByronAfterShelley

  /** A Byron block in or after the epoch the chain enters Shelley. */
  case ByronAfterTransition(epoch: BigInt, first: BigInt)

  // ---- decode

  /** The block does not decode. */
  case Decode(error: ChainError)

  /** A transaction of the block does not decode (`text` is the decoder's text). */
  case TxDecode(txId: Bytes, text: String)

  // ---- byron

  /** A Byron block, but no Byron genesis is configured. */
  case NoByronGenesis

  /** The first Shelley block, but the Byron ledger has not decided on the transition. */
  case TransitionUndecided(era: Era)

  /** A Shelley block before the transition epoch, or inside the Byron span. */
  case ShelleyBeforeTransition(epoch: BigInt, first: BigInt, lastByronSlot: BigInt)

  /** The Byron rules refused the block (`text` is their text). */
  case Byron(text: String)

  // ---- envelope, headers

  /** The hard-fork combinator's envelope checks (era, versions, sizes). */
  case Envelope(error: EnvelopeError)

  /** A TPraos header rule. */
  case TPraos(error: TPraosError)

  /** A Praos header rule (`text` is its text). */
  case Praos(text: String)

  // ---- ledger

  /** The ledger-lite rules of the bootstrap mode refused the block (`text` is their text). */
  case Lite(text: String)

  /** The Byron-to-Shelley hand-over refused the final Byron state (`text` is its text). */
  case Handover(text: String)

  /** The total supply is not the maximum entering an epoch (before Conway). */
  case SupplyInvariant(epoch: BigInt, total: BigInt, maximum: BigInt)

  /** The reference scripts of the block exceed the Conway limit. */
  case RefScriptsTooLarge(limit: Long)

  /** The execution units of the block exceed the maximum of its parameters. */
  case BlockExUnitsTooBig(used: ExUnits, maximum: ExUnits)

  /** The ledger refused a transaction. */
  case Tx(failure: LedgerFailure)

  /** The rule family, as the log names it. */
  def rule: BlockRule = this match
    case HeaderDiffers | BodyCommitment | EraAnnounced(_, _) | EpochPrecedesLedger(_) |
        ByronAfterShelley | ByronAfterTransition(_, _) =>
      BlockRule.Structure
    case Decode(_) | TxDecode(_, _) => BlockRule.Decode
    case NoByronGenesis | TransitionUndecided(_) | ShelleyBeforeTransition(_, _, _) | Byron(_) =>
      BlockRule.Byron
    case Envelope(_) => BlockRule.Envelope
    case TPraos(_)   => BlockRule.TPraos
    case Praos(_)    => BlockRule.Praos
    case Lite(_) | Handover(_) | SupplyInvariant(_, _, _) | RefScriptsTooLarge(_) |
        BlockExUnitsTooBig(_, _) | Tx(_) =>
      BlockRule.Ledger

  /** The reason, as the log renders it (the text of the old `(rule, reason)` pair, unchanged). */
  def reason: String = this match
    case HeaderDiffers                   => "block header differs from announced header"
    case BodyCommitment                  => "body does not match the header commitment"
    case EraAnnounced(actual, announced) => s"$actual block announced as $announced"
    case EpochPrecedesLedger(epoch)      => s"block epoch $epoch precedes ledger epoch"
    case ByronAfterShelley               => "Byron block after the Shelley era began"
    case ByronAfterTransition(epoch, first) =>
      s"Byron block in epoch $epoch, the chain enters Shelley in epoch $first"
    case Decode(error)      => error.message
    case TxDecode(id, text) => s"tx ${id.hex.take(16)}: $text"
    case NoByronGenesis     => "no Byron genesis configured"
    case TransitionUndecided(era) =>
      s"$era block, but the Byron ledger has not decided on the transition to Shelley"
    case ShelleyBeforeTransition(epoch, first, last) =>
      s"Shelley block in epoch $epoch, the transition is to epoch $first after Byron slot $last"
    case Byron(text)     => text
    case Envelope(error) => error.message
    case TPraos(error)   => error.message
    case Praos(text)     => text
    case Lite(text)      => text
    case Handover(text)  => text
    case SupplyInvariant(epoch, total, maximum) =>
      s"supply invariant broken entering epoch $epoch: $total != $maximum"
    case RefScriptsTooLarge(limit) => s"reference scripts of the block exceed $limit bytes"
    case BlockExUnitsTooBig(used, maximum) =>
      s"block execution units ${used.mem} mem / ${used.steps} steps exceed the maximum ${maximum.mem} / ${maximum.steps}"
    case Tx(failure) => failure.message

object BlockFailure:
  // Plain data all through (Bytes and the ledger's failure records compare structurally).
  given Eq[BlockFailure] = Eq.fromUniversalEquals
  given Show[BlockFailure] = Show.show(f => s"${f.rule.name}: ${f.reason}")
