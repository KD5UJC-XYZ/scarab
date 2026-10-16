// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.differential

import cats.Applicative
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, NodeState}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.{BlockFailure, Header, TxIn}
import xyz.kd5ujc.scarab.ledger.byron.ByronOut
import xyz.kd5ujc.scarab.ledger.rules.{EraNes, FromByron, Globals, Handover, Hfc, TickFailure}
import xyz.kd5ujc.scarab.ledger.state.{EpochNo, ShelleyBasedEra, SlotNo}
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

  /** The tick of a block of `era` (stage `Tick`, J2.3): the ledger state brought to the block's
    * slot, none while it is not ported. A port of a stage answers with the state of that stage, not
    * of the whole block, which is why it is not [[forEra]].
    */
  def tick(era: Era): Option[NewRules.Tick[F]] = None

object NewRules:
  /** The block `raw` (with header `header`) applied to the state before it. */
  type Apply[F[_]] = (MirrorState, Header, Bytes) => F[NewOutcome]

  /** The state a tick starts from. */
  enum TickFrom:
    /** The new-epoch state of the era the chain is in, which may be an earlier one than the
      * block's: the combinator translates it.
      */
    case Shelley(state: EraNes)

    /** The Byron ledger, for the first Shelley-based block of a chain that begins in Byron: the
      * hand-over builds the Shelley state ([[Handover]]) with the context of the Shelley genesis.
      */
    case Byron(utxo: Map[TxIn, ByronOut], context: FromByron)

  /** What the tick of a block is asked: the state before it, the block's header, the constants of
    * the network once the first Shelley epoch is known, and the hard-fork configuration's choice of
    * era.
    */
  final case class TickInput(from: TickFrom, header: Header, globals: Globals, eraAt: Hfc.EraAt)

  /** What the new side did with the tick of a block. */
  enum TickOutcome:
    /** The state at the block's slot, in the era the block is of. */
    case Ticked(state: EraNes)

    /** The tick is refused. */
    case Refused(failure: TickFailure)

  type Tick[F[_]] = TickInput => F[TickOutcome]

  /** Nothing is ported. */
  def notPorted[F[_]]: NewRules[F] = new NewRules[F]:
    def forEra(era: Era): Option[Apply[F]] = None

  /** The rules as far as they are ported: the one place that grows with the ports (J2.3 on). The
    * node's `--rules differential` and the replay of a repro bundle both run these.
    *
    * Ported: the tick of every Shelley-based era (J2.3).
    */
  def current[F[_]: Applicative]: NewRules[F] = new NewRules[F]:
    def forEra(era: Era): Option[Apply[F]] = None

    override def tick(era: Era): Option[Tick[F]] = era match
      case Era.Byron => None
      case _ =>
        Some { input =>
          val start: Either[TickFailure, EraNes] = input.from match
            case TickFrom.Shelley(state) => Right(state)
            case TickFrom.Byron(utxo, context) =>
              EpochNo
                .fromBigInt(input.globals.ledger.firstEpoch)
                .left
                .map(TickFailure.Vocab(_))
                .flatMap(Handover.toShelley(context, _, utxo))
                .map(EraNes.InEra(ShelleyBasedEra.SShelley, _))
          Applicative[F].pure(
            (start, SlotNo.fromBigInt(input.header.slot).left.map(TickFailure.Vocab(_))).tupled
              .flatMap((from, slot) => Hfc.tick(input.globals, input.eraAt)(from, slot))
              .fold(TickOutcome.Refused(_), TickOutcome.Ticked(_))
          )
        }
