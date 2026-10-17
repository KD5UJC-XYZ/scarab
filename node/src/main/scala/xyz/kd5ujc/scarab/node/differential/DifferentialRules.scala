// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.differential

import cats.effect.{Ref, Sync}
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, BlockRules, NodeState}
import xyz.kd5ujc.scarab.consensus.state.{
  ConsensusMirrorEnv,
  MirrorExtras,
  MirrorState,
  Placement,
  StateMirrorError
}
import xyz.kd5ujc.scarab.ledger.{BlockFailure, BlockRule, Header, NodeError, ValidationError}
import xyz.kd5ujc.scarab.ledger.rules.{EraBlock, EraNes, Globals}
import xyz.kd5ujc.scarab.ledger.state.{
  Difference,
  MirrorError,
  NewEpochState,
  Path,
  ShelleyBasedEra,
  SnapshotBasis,
  VocabError
}
import xyz.kd5ujc.scarab.node.{MirrorRun, NodeRules}

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
    counters: Ref[F, Stats],
    oldTick: Option[TickStage.OldTicking],
    tickStage: Option[TickStage[F]]
)(using F: Sync[F], failures: Handle[F, NodeError], halt: Raise[F, Divergence])
    extends BlockRules[F, Paired]:

  /** What the run has counted so far. */
  def stats: F[Stats] = counters.get

  def apply(state: Paired, header: Header, raw: Bytes): F[Paired] =
    val block = BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary)
    for
      // the old side's tick of the block: the stage of its own, and the constants of the network
      // the new side's block rules are given
      ticking <- oldTick.traverse(f => F.delay(DifferentialRules.timed(f(state.old, header))))
      began <- F.monotonic
      attempted <- failures.attempt(old(state.old, header, raw))
      ended <- F.monotonic
      first <- counters.modify(s => (s.block, s.blocks == 0))
      _ <- ticked(state, header, raw, block, first, ticking)
      timedOutcome <- newSide(state, header, raw, block, ticking.flatMap(_._1.toOption.flatten))
      (outcome, newNanos) = timedOutcome
      _ <- outcome
        .filter(_ => attempted.isRight)
        .traverse_(_ => counters.update(_.blockTimed((ended - began).toNanos, newNanos)))
      next <- attempted match
        case Left(refused: ValidationError) =>
          agree(Left(refused.failure), outcome, state, header, raw, block) *>
            failures.raise[NodeError, Paired](refused)
        case Left(other) => failures.raise[NodeError, Paired](other)
        case Right(after) =>
          agree(Right(()), outcome, state, header, raw, block) *>
            following(
              state,
              after,
              header,
              raw,
              block,
              outcome,
              first,
              ticking.flatMap(_._1.toOption.flatten)
            )
    yield next

  /** The `Tick` stage: the tick of the block on both sides, where the new side's is ported. */
  private def ticked(
      state: Paired,
      header: Header,
      raw: Bytes,
      block: BlockRef,
      first: Boolean,
      ticking: Option[(Either[BlockFailure, Option[NodeRules.OldTick]], Long)]
  ): F[Unit] =
    (tickStage, neu.tick(header.era), ticking).tupled.traverse_ { (stage, port, tick) =>
      stage(port, state, header, block, first, tick._1, tick._2).flatMap(
        _.traverse_ { compared =>
          counters.update(_.ticked(compared.cost)) *>
            observe(Event.TickCompared(block, compared.epochsCrossed, compared.cost)) *>
            compared.divergence.traverse_(diverge(_, state, header, raw))
        }
      )
    }

  /** What the new rules say of the block, when they are ported for its era, and how long they took.
    * A pre-state the new side does not hold is seeded from the old one first. A block they say
    * nothing of ([[NewOutcome.NotPorted]]) is as one that is not ported.
    */
  private def newSide(
      state: Paired,
      header: Header,
      raw: Bytes,
      block: BlockRef,
      ot: Option[NodeRules.OldTick]
  ): F[(Option[NewOutcome], Long)] =
    neu.forEra(header.era) match
      case None => F.pure((None, 0L))
      case Some(rules) =>
        val context = ot.map(tick =>
          EraBlock.Context(Globals(tick.globals, tick.config.geometry), tick.config.forks.eraAt)
        )
        for
          e <- env
          placement = ot.map(tick => Placement(tick.config.geometry, e.byron, e.forks))
          pre <- state.neu.fold(convert(state.old, state.at, ot, SnapshotBasis.AsTaken).flatMap {
            case (Right(seeded), _) => F.pure(Option(NewRules.carried(seeded)))
            case (Left(error), _) =>
              diverge(Divergence.Unconvertible(block, Stage.Block, error), state, header, raw)
                .as(Option.empty[MirrorState])
          })(m => F.pure(Option(m)))
          began <- F.monotonic
          outcome <- pre.traverse(m => rules(BlockInput(m, header, raw, context, placement)))
          ended <- F.monotonic
        yield (
          outcome.filter {
            case NewOutcome.NotPorted => false
            case _                    => true
          },
          (ended - began).toNanos
        )

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
        case NewOutcome.NotPorted    => old
      // the new side judges the ledger's part of a block (the body and the rules under it); a
      // block the old side refuses for its header (the envelope, TPraos, Praos) is not its to judge
      if verdict == old || !DifferentialRules.ledgerFailure(old) then F.unit
      else diverge(Divergence.VerdictDiffers(block, Stage.Block, old, verdict), state, header, raw)
    }

  /** The pair after a block the old side accepted: the new side's state, compared with the old
    * one's where the block is a sampling point, or converted for the cost of it where the rules are
    * not ported (the new side then holds no state of its own).
    *
    * What is compared is the new-epoch state, the ledger's own (J2.4): the new side applies the
    * ledger's part of a block, so the header state and the node's bookkeeping of the mirror are the
    * old side's and are not compared, as at the `Tick` stage. The comparison is [[Relations]]'.
    */
  private def following(
      state: Paired,
      after: NodeState,
      header: Header,
      raw: Bytes,
      block: BlockRef,
      outcome: Option[NewOutcome],
      first: Boolean,
      ot: Option[NodeRules.OldTick]
  ): F[Paired] =
    val due = sampling.due(state.old, after, block, first)
    def paired(neu: Option[MirrorState]) = Paired(after, neu, Some(block))
    outcome match
      case Some(NewOutcome.Applied(theirs)) if !due => F.pure(paired(Some(theirs)))
      case Some(NewOutcome.Applied(theirs)) =>
        convert(after, Some(block), ot).flatMap {
          case (Left(error), _) =>
            diverge(Divergence.Unconvertible(block, Stage.Block, error), state, header, raw)
              .as(paired(Some(theirs)))
          case (Right(ours), toMirror) =>
            F.delay(
              DifferentialRules.timed(
                ledgerDifference(ours, theirs, header, state, ot)
              )
            ).flatMap { (compared, diffNanos) =>
              compared match
                case Left(error) =>
                  diverge(
                    Divergence.Unconvertible(
                      block,
                      Stage.Block,
                      StateMirrorError.Ledger(MirrorError.Vocab(error))
                    ),
                    state,
                    header,
                    raw
                  ).as(paired(Some(theirs)))
                case Right(difference) =>
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
                    // a disagreement that does not halt adopts the old side, seeded as a state
                    // the new side starts from is
                    *> adopted(
                      difference.isDefined,
                      state,
                      after,
                      header,
                      raw,
                      block,
                      ot,
                      ours,
                      theirs
                    )
                      .map(m => paired(Some(m)))
            }
        }
      // the new side refused a block the old side took: reported by `agree`; seeded again later
      case Some(NewOutcome.Refused(_)) => F.pure(paired(None))
      case Some(NewOutcome.NotPorted) | None =>
        counters.update(_.notPortedIn(header.era, Stage.Block)) *>
          (if !due then F.pure(paired(None))
           else
             convert(after, Some(block), ot).flatMap {
               // the conversion is the cost of the reseed; nothing reads the seeded state, and
               // keeping it would double the memory of the run, so it is not kept
               case (Right(_), toMirror) =>
                 sampled(block, after, header, Cost(toMirror, 0L), seeded = true).as(paired(None))
               case (Left(error), _) =>
                 diverge(Divergence.Unconvertible(block, Stage.Block, error), state, header, raw)
                   .as(paired(None))
             })

  /** The state the new side carries on with after a comparison: its own where the two agreed, else
    * the old side's seeded from the snapshots as they were taken ([[SnapshotBasis.AsTaken]]; the
    * comparison's conversion `ours` reads the accounts as they are, which differ from what a
    * snapshot holds). A state that does not convert twice is reported and `ours`, which did, is
    * adopted.
    */
  private def adopted(
      diverged: Boolean,
      state: Paired,
      after: NodeState,
      header: Header,
      raw: Bytes,
      block: BlockRef,
      ot: Option[NodeRules.OldTick],
      ours: MirrorState,
      theirs: MirrorState
  ): F[MirrorState] =
    if !diverged then F.pure(theirs)
    else
      convert(after, Some(block), ot, SnapshotBasis.AsTaken).flatMap {
        case (Right(seeded), _) => F.pure(NewRules.carried(seeded))
        case (Left(error), _) =>
          diverge(Divergence.Unconvertible(block, Stage.Block, error), state, header, raw)
            .as(NewRules.carried(ours))
      }

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

  /** The first difference of the new-epoch states of the two mirrors, where the old side's is
    * `ours` (taken to the terms the old side can express, [[Relations]]).
    */
  private def ledgerDifference(
      ours: MirrorState,
      theirs: MirrorState,
      header: Header,
      state: Paired,
      ot: Option[NodeRules.OldTick]
  ): Either[VocabError, Option[Difference]] =
    // the slot after which the reward update starts (`RewardsTooEarly` until then)
    val rewardsStarted = ot.forall { t =>
      val globals = Globals(t.globals, t.config.geometry)
      header.slot > globals.firstSlot(globals.epochOf(header.slot)) +
        globals.randomnessStabilisationWindow
    }
    val amounts = ours.extras match
      case MirrorExtras.InShelley(ledger, _) => ledger.rewardAmounts
      case MirrorExtras.InByron(_, _, _, _)  => Map.empty
    (NewRules.nesOf(ours), NewRules.nesOf(theirs)) match
      case (Some(a: EraNes.InEra[p]), Some(b: EraNes.InEra[q])) =>
        given ShelleyBasedEra[q] = b.era
        NewEpochState.retype(a.era, b.era)(a.nes) match
          case Some(same) =>
            Relations.compare[q](
              same,
              amounts,
              b.nes,
              rewardsStarted,
              // the hand-over from Byron and the epoch it begins (the first state the harness sees
              // too): the reference has `NoPParamsUpdate` in the first epoch of a chain, until its
              // first boundary, and the old mirror derives the prediction from the proposals
              handover = state.at.isEmpty || state.old.byron.ledger.isDefined ||
                state.old.byron.firstShelleyEpoch.contains(state.old.ledger.epoch),
              carried = true,
              // the state after the whole block: what the old mirror derives from the accounts is
              // theirs at the end of it, not at the tick that took `mark`, which `Stage.Tick` checks
              boundary = false
            )
          case None =>
            Right(
              Some(
                Difference(
                  Path.root,
                  s"era ${EraNes.eraOf(a.era)}",
                  s"era ${EraNes.eraOf(b.era)}"
                )
              )
            )
      case (None, None) => Right(None)
      case _ => Right(Some(Difference(Path.root, "a Shelley-based state", "a Byron state")))

  private def timed[A](body: => A): F[(A, Long)] =
    F.blocking {
      val began = System.nanoTime()
      val result = body
      (result, System.nanoTime() - began)
    }

  /** The old state in the mirror, with the nanoseconds the conversion took. The geometry is the
    * resolved one of the old side's tick where there is one (a chain that begins in Byron learns
    * its first Shelley epoch from the state, and the prediction of the future parameters of the
    * mirror is read off the geometry), as the tick stage's conversions have it. `basis` is where
    * the delegators of the `mark` snapshot come from: the accounts for a comparison, the snapshot
    * for a state the new side is seeded with.
    */
  private def convert(
      state: NodeState,
      at: Option[BlockRef],
      ot: Option[NodeRules.OldTick],
      basis: SnapshotBasis = SnapshotBasis.Accounts
  ): F[(Either[StateMirrorError, MirrorState], Long)] =
    env.flatMap { e =>
      val resolved = e.copy(ledger =
        e.ledger.copy(
          geometry = ot.fold(e.ledger.geometry)(_.config.geometry),
          snapshots = basis
        )
      )
      timed(MirrorRun.run(resolved)(_.toMirror(at, state)).map(_.value))
    }

object DifferentialRules:
  /** The value of a pure computation, with the nanoseconds it took. */
  private[differential] def timed[A](body: => A): (A, Long) =
    val began = System.nanoTime()
    val result = body
    (result, System.nanoTime() - began)

  /** Whether a refusal of the old side is one of the ledger's part of a block, which the new rules
    * judge: the decoding and the commitment of the body, the epoch, the transactions and the limits
    * of the block. A refusal of the header (the envelope, TPraos, Praos) is not.
    */
  def ledgerFailure(verdict: Verdict): Boolean = verdict.fold(
    {
      case BlockFailure.HeaderDiffers | BlockFailure.BodyCommitment |
          BlockFailure.EraAnnounced(_, _) | BlockFailure.EpochPrecedesLedger(_) |
          BlockFailure.LedgerEra(_, _) =>
        true
      case other => other.rule == BlockRule.Ledger || other.rule == BlockRule.Decode
    },
    _ => true
  )

  def create[F[_]](
      old: BlockRules[F, NodeState],
      neu: NewRules[F],
      env: F[ConsensusMirrorEnv],
      sampling: Sampling = Sampling.Default,
      policy: Policy = Policy.Halt,
      repro: Repro[F],
      observe: Event => F[Unit],
      oldTick: Option[TickStage.OldTicking] = None
  )(using
      F: Sync[F],
      failures: Handle[F, NodeError],
      halt: Raise[F, Divergence]
  ): F[DifferentialRules[F]] =
    Ref
      .of[F, Stats](Stats())
      .map(
        new DifferentialRules[F](
          old,
          neu,
          env,
          sampling,
          policy,
          repro,
          observe,
          _,
          oldTick,
          oldTick.map(_ => new TickStage[F](env, sampling))
        )
      )
