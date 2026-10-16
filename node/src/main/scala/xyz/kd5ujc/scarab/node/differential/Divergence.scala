// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.differential

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.show.*
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.StateMirrorError
import xyz.kd5ujc.scarab.ledger.BlockFailure
import xyz.kd5ujc.scarab.ledger.state.Difference
import xyz.kd5ujc.scarab.protocol.Era

/** Where in the application of a block two sides were compared. The harness compares after the
  * whole block today (`Block`: header and body); the stages of design §2.5 (`Tick(epochsCrossed)`,
  * `Header`, `Tx(index, txId)`, `Bbody`) arrive with the first rule that is ported, because only a
  * ported rule has a state of its own to compare at them, and the old side exposes them then
  * (J2.3).
  */
enum Stage derives Eq:
  case Block

object Stage:
  given Show[Stage] = Show.fromToString

/** What a side said about a block: it accepted it, or refused it for a reason. */
type Verdict = Either[BlockFailure, Unit]

/** A point at which the two sides did not agree, or could not be compared (design §2.5). Closed.
  *
  *   - `StateDiffers`: the state after the block is not the same; `difference` names the first
  *     differing path of the mirror records (`Diff`).
  *   - `VerdictDiffers`: one side accepted the block and the other refused it, or they refused it
  *     for different reasons.
  *   - `Unconvertible`: the old state does not convert to the mirror, so there is nothing to
  *     compare (the same refusal `--mirror-check` reports).
  *   - `NotPorted`: the new side has no rules for this era and stage yet and takes the old side's
  *     state (it is *reseeded*). It is counted for the coverage report and is not a failure.
  */
enum Divergence:
  case StateDiffers(block: BlockRef, era: Era, stage: Stage, difference: Difference)
  case VerdictDiffers(block: BlockRef, stage: Stage, old: Verdict, neu: Verdict)
  case Unconvertible(block: BlockRef, stage: Stage, error: StateMirrorError)
  case NotPorted(era: Era, stage: Stage)

  /** Whether the divergence is a disagreement (everything but a rule that is not ported yet). */
  def isFailure: Boolean = this match
    case NotPorted(_, _) => false
    case _               => true

object Divergence:
  given Eq[Divergence] = Eq.fromUniversalEquals

  private def verdict(v: Verdict): String = v.fold(f => s"refused: ${f.show}", _ => "accepted")

  given Show[Divergence] = Show.show {
    case StateDiffers(b, era, stage, d) =>
      s"state differs after block ${b.blockNo} (slot ${b.slot}, $era, $stage): ${Show[Difference].show(d)}"
    case VerdictDiffers(b, stage, old, neu) =>
      s"verdicts differ at block ${b.blockNo} (slot ${b.slot}, $stage): old ${verdict(old)}, new ${verdict(neu)}"
    case Unconvertible(b, stage, e) =>
      s"block ${b.blockNo} (slot ${b.slot}, $stage): the old state does not convert: ${Show[StateMirrorError].show(e)}"
    case NotPorted(era, stage) => s"$era $stage: not ported"
  }
