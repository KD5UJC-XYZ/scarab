// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.differential

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, NodeState}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.{BlockFailure, Header}
import xyz.kd5ujc.scarab.protocol.Era

/** The two sides of the harness' state: the old node state, and the new side's, which is the mirror
  * (`MirrorState`: the Haskell `ExtLedgerState` and the node-only bookkeeping) until the
  * era-indexed rules make it the node's own (the plan's `NodeState(ext, extras)` of §2.1, which
  * does not exist yet).
  *
  * `neu` is the new side's state *after* the block `at` when it has one, and `None` when it has not
  * advanced with the old side: a side whose rules are not ported does not move, and is seeded again
  * from the old state when it is next needed (the harness does so at its sampling points,
  * [[Sampling]]). `at` is the block the old state is the state after, none before the first block
  * the harness saw.
  */
final case class Paired(old: NodeState, neu: Option[MirrorState], at: Option[BlockRef])

object Paired:
  /** The state of a node that starts from `old`, the new side not yet seeded. */
  def of(old: NodeState, at: Option[BlockRef] = None): Paired = Paired(old, None, at)

/** What the new side did with a block. */
enum NewOutcome:
  /** The block is valid and this is the state after it. */
  case Applied(next: MirrorState)

  /** The block is refused. */
  case Refused(failure: BlockFailure)

/** The era-indexed rules, as the harness sees them (design §2.5): given the state before a block,
  * as the mirror, they say what became of the block. A rule that is not ported is not asked: the
  * harness takes the old side's state at its sampling points instead (`Divergence.NotPorted`), so
  * the plan's "reseed everything" start shows zero divergences by construction and measures what
  * the conversion costs.
  */
trait NewRules[F[_]]:
  /** The rules for blocks of `era`, none while they are not ported. */
  def forEra(era: Era): Option[NewRules.Apply[F]]

object NewRules:
  /** The block `raw` (with header `header`) applied to the state before it. */
  type Apply[F[_]] = (MirrorState, Header, Bytes) => F[NewOutcome]

  /** Nothing is ported. */
  def notPorted[F[_]]: NewRules[F] = _ => None

  /** The rules as far as they are ported: the one place that grows with the ports (J2.3 on). The
    * node's `--rules differential` and the replay of a repro bundle both run these.
    */
  def current[F[_]]: NewRules[F] = notPorted
