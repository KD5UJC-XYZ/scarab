// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.differential

import cats.effect.Sync
import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.{BlockRef, NodeState}
import xyz.kd5ujc.scarab.consensus.state.{
  ConsensusMirrorEnv,
  SomeEra,
  StateMirror,
  StateMirrorError
}
import xyz.kd5ujc.scarab.ledger.{BlockFailure, Header}
import xyz.kd5ujc.scarab.ledger.rules.{EraNes, FromByron, Globals}
import xyz.kd5ujc.scarab.ledger.state.{
  Difference,
  MirrorError,
  NewEpochState,
  Path,
  ShelleyBasedEra,
  SlotNo,
  SnapshotBasis,
  WithOrigin
}
import xyz.kd5ujc.scarab.node.{LedgerRun, NodeRules}

/** The `Tick` stage of the harness (design §2.5, J2.3): the ledger state a block meets, after the
  * old rules' tick ([[NodeRules.tick]]) and after the new rules' ([[NewRules.tick]]), compared.
  *
  * The new side is run from the mirror of the state before the block, the old side's ticked ledger
  * is converted to the mirror and the two new-epoch states are compared ([[Relations]]). That is
  * two conversions of a whole state (about a second on Preview, R7), so the stage is taken where
  * the tick does something: at an epoch boundary, when the reward update starts, when genesis
  * delegations are adopted, and at the sampling points.
  */
final class TickStage[F[_]](
    env: F[ConsensusMirrorEnv],
    sampling: Sampling
)(using F: Sync[F]):

  /** What comparing a tick found: nothing to say for the block, or what it cost and the
    * disagreement, if any.
    */
  def apply(
      port: NewRules.Tick[F],
      state: Paired,
      header: Header,
      block: BlockRef,
      first: Boolean,
      ticking: Either[BlockFailure, Option[NodeRules.OldTick]],
      oldNanos: Long
  ): F[Option[TickStage.Compared]] =
    ticking match
      case Right(Some(ot)) if due(state.old, ot, block, first) =>
        compare(port, state, header, block, ot, oldNanos).map(Some(_))
      case _ => F.pure(None)

  /** Whether the tick of the block did anything: crossed an epoch boundary (or entered Shelley),
    * started the reward update, adopted genesis delegations, or falls on a sampling point.
    */
  private def due(
      pre: NodeState,
      ot: NodeRules.OldTick,
      block: BlockRef,
      first: Boolean
  ): Boolean =
    ot.epochsCrossed > 0 || (ot.entered ne pre) ||
      ot.ticked.rewardUpdate.isDefined != pre.ledger.rewardUpdate.isDefined ||
      (ot.ticked.genDelegs ne pre.ledger.genDelegs) ||
      (ot.ticked.futureGenDelegs ne pre.ledger.futureGenDelegs) ||
      sampling.dueTick(first, block)

  private def timed[A](body: => A): (A, Long) =
    val began = System.nanoTime()
    val result = body
    (result, System.nanoTime() - began)

  private def atSlot(slot: Option[BigInt]): Either[MirrorError, WithOrigin[SlotNo]] =
    slot.traverse(SlotNo.fromBigInt).left.map(MirrorError.Vocab(_)).map {
      case None    => WithOrigin.Origin
      case Some(s) => WithOrigin.At(s)
    }

  /** The state the new side starts from: the mirror of the state before the block (its reward
    * update attributed, [[Relations.seed]]), or, for the first block after Byron, the Byron UTxO
    * and the context the old hand-over used. None where the state is not one the tick can start
    * from.
    */
  private def start(
      e: ConsensusMirrorEnv,
      state: Paired,
      ot: NodeRules.OldTick
  ): Option[Either[MirrorError, NewRules.TickFrom]] =
    val era = StateMirror.eraOf(e, state.old)
    // the new side's own state, where it holds one in the era the old state is in (J2.4: from the
    // first ported block on, the pair carries it)
    val own = state.neu.flatMap(NewRules.nesOf).filter(_.toEra == era)
    own match
      case Some(carried) => Some(Right(NewRules.TickFrom.Shelley(carried)))
      case None          => fromOld(e, state, ot, era)

  private def fromOld(
      e: ConsensusMirrorEnv,
      state: Paired,
      ot: NodeRules.OldTick,
      era: xyz.kd5ujc.scarab.protocol.Era
  ): Option[Either[MirrorError, NewRules.TickFrom]] =
    SomeEra.of(era) match
      case Some(pre: SomeEra.Of[p]) =>
        given ShelleyBasedEra[p] = pre.era
        Some(for
          at <- atSlot(state.at.map(_.slot))
          image <- LedgerRun
            .run(
              e.ledger.copy(geometry = ot.config.geometry, snapshots = SnapshotBasis.AsTaken)
            )(_.to[p](at, state.old.ledger))
          nes <- Relations
            .seed(image.value.nes, image.value.extras.rewardAmounts)
            .left
            .map(MirrorError.Vocab(_))
        yield NewRules.TickFrom.Shelley(EraNes.InEra(pre.era, nes)))
      case None =>
        state.old.byron.ledger.map(byron =>
          Right(
            NewRules.TickFrom.Byron(
              byron.utxo,
              FromByron(ot.entered.ledger.genDelegs, ot.entered.ledger.params, ot.globals.maxSupply)
            )
          )
        )

  private def compare(
      port: NewRules.Tick[F],
      state: Paired,
      header: Header,
      block: BlockRef,
      ot: NodeRules.OldTick,
      oldNanos: Long
  ): F[TickStage.Compared] =
    val stage = Stage.Tick(ot.epochsCrossed)
    def compared(cost: TickCost, d: Option[Divergence]) =
      TickStage.Compared(ot.epochsCrossed, cost, d)
    env.flatMap { e =>
      SomeEra.of(header.era) match
        case None => F.pure(compared(TickCost(oldNanos, 0, 0, 0), None))
        case Some(post: SomeEra.Of[q]) =>
          given ShelleyBasedEra[q] = post.era
          val globals = Globals(ot.globals, ot.config.geometry)
          // the slot after which the reward update starts (`RewardsTooEarly` until then)
          val rewardsStarted =
            header.slot > globals.firstSlot(globals.epochOf(header.slot)) +
              globals.randomnessStabilisationWindow
          val (seeded, seedNanos) = timed(start(e, state, ot))
          seeded match
            case None => F.pure(compared(TickCost(oldNanos, seedNanos, 0, 0), None))
            case Some(Left(error)) =>
              F.pure(
                compared(
                  TickCost(oldNanos, seedNanos, 0, 0),
                  Some(Divergence.Unconvertible(block, stage, StateMirrorError.Ledger(error)))
                )
              )
            case Some(Right(from)) =>
              val input = NewRules.TickInput(from, header, globals, ot.config.forks.eraAt)
              for
                began <- F.delay(System.nanoTime())
                outcome <- port(input)
                newNanos <- F.delay(System.nanoTime() - began)
              yield
                val (difference, diffNanos) =
                  timed[Either[MirrorError, Option[Difference]]](outcome match
                    case NewRules.TickOutcome.Refused(why) =>
                      Right(Some(Difference(Path.root, "ticked", s"the new tick refuses: $why")))
                    case NewRules.TickOutcome.Ticked(result) =>
                      for
                        at <- atSlot(Some(header.slot))
                        image <- LedgerRun
                          .run(e.ledger.copy(geometry = ot.config.geometry))(_.to[q](at, ot.ticked))
                        diff <- result match
                          case got: EraNes.InEra[r] =>
                            NewEpochState.retype(got.era, post.era)(got.nes) match
                              case Some(same) =>
                                Relations
                                  .compare[q](
                                    image.value.nes,
                                    image.value.extras.rewardAmounts,
                                    same,
                                    rewardsStarted,
                                    handover = from
                                      .isInstanceOf[NewRules.TickFrom.Byron] || state.at.isEmpty,
                                    carried = state.neu.isDefined,
                                    boundary = ot.epochsCrossed > 0
                                  )
                                  .left
                                  .map(MirrorError.Vocab(_))
                              case None =>
                                Right(
                                  Some(
                                    Difference(
                                      Path.root,
                                      s"era ${header.era}",
                                      s"era ${EraNes.eraOf(got.era)}"
                                    )
                                  )
                                )
                      yield diff)
                val cost = TickCost(oldNanos, seedNanos, newNanos, diffNanos)
                difference match
                  case Left(error) =>
                    compared(
                      cost,
                      Some(Divergence.Unconvertible(block, stage, StateMirrorError.Ledger(error)))
                    )
                  case Right(None) => compared(cost, None)
                  case Right(Some(d)) =>
                    compared(cost, Some(Divergence.StateDiffers(block, header.era, stage, d)))
    }

object TickStage:
  /** The old side's tick of a block (`NodeRules.tick`). */
  type OldTicking = (NodeState, Header) => Either[BlockFailure, Option[NodeRules.OldTick]]

  /** A tick that was compared: what it cost, and where the two sides differed, if they did. */
  final case class Compared(epochsCrossed: Int, cost: TickCost, divergence: Option[Divergence])
