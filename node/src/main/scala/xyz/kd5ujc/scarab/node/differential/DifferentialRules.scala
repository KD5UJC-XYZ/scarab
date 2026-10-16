// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.differential

import cats.effect.{Ref, Sync}
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, BlockRules, NodeState}
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, MirrorState, StateMirrorError}
import xyz.kd5ujc.scarab.ledger.{Header, NodeError, ValidationError}
import xyz.kd5ujc.scarab.ledger.state.firstDifference
import xyz.kd5ujc.scarab.node.MirrorRun

/** The old rules and the new ones side by side on one chain (design `ledger-mirror-step2.md` §2.5):
  * the block rules over [[Paired]] states. Each block goes to the old rules (the node's, whose
  * verdict and state are the node's) and to the new rules where they are ported, and the two are
  * compared: the verdicts at every block, the whole state at the sampling points ([[Sampling]]).
  *
  * The old side decides what the node does; the new side only observes. A disagreement is reported
  * ([[Event.Diverged]], with the repro bundle that [[Repro]] kept), counted, and then either stops
  * the run (`Policy.Halt`: raised through `Raise[F, Divergence]`) or adopts the old side
  * (`Policy.Reseed`: the new side is seeded again from the old state at that block).
  *
  * A rule that is not ported ([[NewRules.forEra]] is none) is the plan's "reseed everything" start:
  * the new side takes the old state, converted at the sampling points only, so the run has zero
  * divergences by construction and [[Stats]] shows what is left to port and what the conversion
  * costs.
  */
final class DifferentialRules[F[_]] private (
    old: BlockRules[F, NodeState],
    neu: NewRules[F],
    env: F[ConsensusMirrorEnv],
    sampling: Sampling,
    policy: Policy,
    repro: Repro[F],
    observe: Event => F[Unit],
    counters: Ref[F, Stats]
)(using F: Sync[F], failures: Handle[F, NodeError], halt: Raise[F, Divergence])
    extends BlockRules[F, Paired]:

  /** What the run has counted so far. */
  def stats: F[Stats] = counters.get

  def apply(state: Paired, header: Header, raw: Bytes): F[Paired] =
    val block = BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary)
    for
      attempted <- failures.attempt(old(state.old, header, raw))
      first <- counters.modify(s => (s.block, s.blocks == 0))
      outcome <- newSide(state, header, raw, block)
      next <- attempted match
        case Left(refused: ValidationError) =>
          agree(Left(refused.failure), outcome, state, header, raw, block) *>
            failures.raise[NodeError, Paired](refused)
        case Left(other) => failures.raise[NodeError, Paired](other)
        case Right(after) =>
          agree(Right(()), outcome, state, header, raw, block) *>
            following(state, after, header, raw, block, outcome, first)
    yield next

  /** What the new rules say of the block, when they are ported for its era. A pre-state the new
    * side does not hold is seeded from the old one first.
    */
  private def newSide(
      state: Paired,
      header: Header,
      raw: Bytes,
      block: BlockRef
  ): F[Option[NewOutcome]] =
    neu.forEra(header.era).flatTraverse { rules =>
      state.neu
        .fold(convert(state.old, state.at).flatMap {
          case (Right(seeded), _) => F.pure(Some(seeded))
          case (Left(error), _) =>
            diverge(Divergence.Unconvertible(block, Stage.Block, error), state, header, raw)
              .as(None)
        })(pre => F.pure(Some(pre)))
        .flatMap(_.traverse(rules(_, header, raw)))
    }

  /** The verdicts of the two sides, compared. */
  private def agree(
      old: Verdict,
      outcome: Option[NewOutcome],
      state: Paired,
      header: Header,
      raw: Bytes,
      block: BlockRef
  ): F[Unit] =
    outcome.traverse_ { o =>
      val verdict: Verdict = o match
        case NewOutcome.Applied(_)   => Right(())
        case NewOutcome.Refused(why) => Left(why)
      if verdict == old then F.unit
      else diverge(Divergence.VerdictDiffers(block, Stage.Block, old, verdict), state, header, raw)
    }

  /** The pair after a block the old side accepted: the new side's state, compared with the old
    * one's where the block is a sampling point, or converted for the cost of it where the rules are
    * not ported (the new side then holds no state of its own).
    */
  private def following(
      state: Paired,
      after: NodeState,
      header: Header,
      raw: Bytes,
      block: BlockRef,
      outcome: Option[NewOutcome],
      first: Boolean
  ): F[Paired] =
    val due = sampling.due(state.old, after, block, first)
    def paired(neu: Option[MirrorState]) = Paired(after, neu, Some(block))
    outcome match
      case Some(NewOutcome.Applied(theirs)) if !due => F.pure(paired(Some(theirs)))
      case Some(NewOutcome.Applied(theirs)) =>
        convert(after, Some(block)).flatMap {
          case (Left(error), _) =>
            diverge(Divergence.Unconvertible(block, Stage.Block, error), state, header, raw)
              .as(paired(Some(theirs)))
          case (Right(ours), toMirror) =>
            timed(ours.firstDifference(theirs)).flatMap { (difference, diffNanos) =>
              sampled(block, after, header, Cost(toMirror, diffNanos), seeded = false) *>
                difference
                  .traverse_(d =>
                    diverge(
                      Divergence.StateDiffers(block, header.era, Stage.Block, d),
                      state,
                      header,
                      raw
                    )
                  )
                  // a disagreement that does not halt adopts the old side
                  .as(paired(Some(if difference.isDefined then ours else theirs)))
            }
        }
      // the new side refused a block the old side took: reported by `agree`; seeded again later
      case Some(NewOutcome.Refused(_)) => F.pure(paired(None))
      case None =>
        counters.update(_.notPortedIn(header.era, Stage.Block)) *>
          (if !due then F.pure(paired(None))
           else
             convert(after, Some(block)).flatMap {
               // the conversion is the cost of the reseed; nothing reads the seeded state, and
               // keeping it would double the memory of the run, so it is not kept
               case (Right(_), toMirror) =>
                 sampled(block, after, header, Cost(toMirror, 0L), seeded = true).as(paired(None))
               case (Left(error), _) =>
                 diverge(Divergence.Unconvertible(block, Stage.Block, error), state, header, raw)
                   .as(paired(None))
             })

  private def sampled(
      block: BlockRef,
      after: NodeState,
      header: Header,
      cost: Cost,
      seeded: Boolean
  ): F[Unit] =
    counters.update(_.sample(cost)) *>
      observe(Event.Sampled(block, after.ledger.epoch, header.era, cost, seeded))

  /** Reports a disagreement, keeps its repro, counts it and, under `Policy.Halt`, stops the run. */
  private def diverge(
      divergence: Divergence,
      state: Paired,
      header: Header,
      raw: Bytes
  ): F[Unit] =
    for
      bundle <- repro.keep(divergence, state, header, raw)
      _ <- observe(Event.Diverged(divergence, bundle))
      _ <- counters.update(_.failed)
      _ <- halt.raise[Divergence, Unit](divergence).whenA(policy == Policy.Halt)
    yield ()

  private def timed[A](body: => A): F[(A, Long)] =
    F.blocking {
      val began = System.nanoTime()
      val result = body
      (result, System.nanoTime() - began)
    }

  /** The old state in the mirror, with the nanoseconds the conversion took. */
  private def convert(
      state: NodeState,
      at: Option[BlockRef]
  ): F[(Either[StateMirrorError, MirrorState], Long)] =
    env.flatMap(e => timed(MirrorRun.run(e)(_.toMirror(at, state)).map(_.value)))

object DifferentialRules:
  def create[F[_]](
      old: BlockRules[F, NodeState],
      neu: NewRules[F],
      env: F[ConsensusMirrorEnv],
      sampling: Sampling = Sampling.Default,
      policy: Policy = Policy.Halt,
      repro: Repro[F],
      observe: Event => F[Unit]
  )(using
      F: Sync[F],
      failures: Handle[F, NodeError],
      halt: Raise[F, Divergence]
  ): F[DifferentialRules[F]] =
    Ref
      .of[F, Stats](Stats())
      .map(new DifferentialRules[F](old, neu, env, sampling, policy, repro, observe, _))
