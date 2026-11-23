// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.NonEmptyVector
import cats.effect.{Ref, Resource, Temporal, Unique}
import cats.effect.std.{Queue, Supervisor}
import cats.mtl.Raise
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.consensus.leios.{
  AddVoteResult,
  LeiosNotVotedReason,
  LeiosVoteTiming,
  LeiosVoting,
  LeiosVotingDefect,
  LeiosVotingEnv,
  VoteTally,
  VotingKey
}
import xyz.kd5ujc.scarab.crypto.bls.{RbHash, Vote, Weight}
import xyz.kd5ujc.scarab.protocol.EbPoint
import xyz.kd5ujc.scarab.storage.LeiosEbNotification

/** What the voting thread traces (`TraceLeiosKernel`'s voting cases, `LeiosDemoTypes.hs:1661-1700`,
  * ouroboros-consensus 2416493b), `I` and `U` the causes of a closure that does not apply and of
  * one that cannot be read back ([[LeiosNotVotedReason]]).
  */
enum LeiosKernelEvent[+I, +U]:
  /** The node holds no voting key, so it never votes (`runLeiosVoting`'s `[]` case,
    * `LeiosVoting.hs:266-269` at 2416493b).
    */
  case VotingDisabled

  /** A vote timer armed for an endorser block whose closure arrived (`TraceLeiosVoteScheduled`,
    * `LeiosVoting.hs:198-203` at 2416493b): the time until its window opens and until it shuts,
    * either past when negative.
    */
  case VoteScheduled(point: EbPoint, voteIn: FiniteDuration, deadlineIn: FiniteDuration)

  /** No wall-clock onset for the endorser block's slot, so no timer is armed for it
    * (`newVoteTimers`' `Left horizon`, `LeiosVoting.hs:185-190` at 2416493b).
    */
  case NoVoteOnset(point: EbPoint)

  /** A closure checked valid: the transactions reapplied and those applied in full, and how long
    * the check took (`TraceLeiosEbValidated`, `LeiosVoting.hs:360-372`,
    * `LeiosDemoTypes.hs:1691-1702` at 2416493b, without the applied ones' summed measure, #54).
    */
  case EbValidated(point: EbPoint, reapplied: Int, applied: Int, duration: FiniteDuration)

  /** A peer's transactions of an endorser block's closure pre-validated ([[LeiosPrevalidation]], an
    * Improvement, #79): of the `offered` ones, those `independent` of transactions off the chain,
    * and those of them `validated` in full and so tagged in the transaction cache, and how long it
    * took.
    */
  case TxsPrevalidated(
      point: EbPoint,
      offered: Int,
      independent: Int,
      validated: Int,
      duration: FiniteDuration
  )

  /** None of a reply's transactions pre-validated, and why ([[LeiosPrevalidation.Skip]], #79). */
  case PrevalidationSkipped(point: EbPoint, reason: LeiosPrevalidation.Skip)

  /** A vote of ours counted (`TraceLeiosVoted`, `LeiosVoting.hs:397` at 2416493b). */
  case Voted(vote: Vote, weight: Weight)

  /** A vote counted, with the tally after it (`TraceLeiosVoteAcquired`, `LeiosVoting.hs:398-404` at
    * 2416493b).
    */
  case VoteAcquired(vote: Vote, tally: VoteTally)

  /** The tally of the announcing block reached the quorum (`TraceLeiosCertified`,
    * `LeiosVoting.hs:409-411` at 2416493b).
    */
  case Certified(rbHash: RbHash)

  /** One seat's vote the vote state did not count, while others of ours may have been
    * (`TraceLeiosNotVoted` with `VoteRejected (show err)`, `LeiosVoting.hs:413-416` at 2416493b).
    */
  case SeatNotVoted(point: EbPoint, vote: Vote, answer: AddVoteResult)

  /** No vote was cast for the endorser block, and why (`TraceLeiosNotVoted`, `LeiosVoting.hs:293`
    * at 2416493b); a [[LeiosNotVotedReason.VoteRejected]] is the reference's `all n votes rejected`
    * (`:417-419`), after each seat's [[SeatNotVoted]].
    */
  case NotVoted(point: EbPoint, reason: LeiosNotVotedReason[I, U])

/** When the votes of an endorser block are due (`newVoteTimers`, `LeiosVoting.hs:162-222`,
  * ouroboros-consensus 2416493b): the onset of a slot on the wall clock, in time since the system
  * start, none where the node cannot place it (`slotOnset`, `:229-236`), and the vote timing of the
  * selection's ledger state (`getLeiosVoteTiming`, `:193-194`).
  */
final case class LeiosVoteSchedule[F[_]](
    onset: BigInt => F[Option[FiniteDuration]],
    timing: F[LeiosVoteTiming]
)

/** The node's Leios voting thread (`runLeiosVoting`, `LeiosVoting.hs:245-421`, ouroboros-consensus
  * 2416493b, forked by the node kernel, `NodeKernel.hs:655-671`).
  */
object LeiosVotingRun:
  /** What wakes the thread: a closure acquired, or an armed timer's window opening. */
  private enum Wake:
    case Acquired(point: EbPoint)
    case Due(point: EbPoint, armed: Unique.Token, deadline: FiniteDuration)

  /** `runLeiosVoting` with the voting keys `keys` (`topLevelConfigVotingKeys`, read once at start,
    * `NodeKernel.hs:659-671` at 2416493b): none traces [[LeiosKernelEvent.VotingDisabled]] and
    * ends. Otherwise, for as long as it runs:
    *   - each `AcquiredEbTxs` of the store's `notifications` arms a timer for its endorser block at
    *     the moment its vote window opens (`scheduleVoteTime`, `:179-211`): the onset of its slot
    *     plus the timing's opening, the deadline that plus the window; a second arming of a point
    *     replaces the first, whose timer then wakes nothing (`Map.insert`, `:208-211`); an
    *     `AcquiredEb` arms nothing (`takeEbNotification`, `:279-282`);
    *   - each timer that fires is disarmed and its endorser block voted on (`waitNextVoteTime`,
    *     `:215-221`; `goVote`, [[LeiosVoting.vote]]), each counted vote traced as voted and
    *     acquired, and as certified when it brought the certificate; each seat's refused vote, and
    *     the reason no vote was cast, traced as not voted.
    *
    * One fiber takes every wake from one queue, so a closure's arrival and a timer's firing are
    * never raced against each other: the store's notifications are moved into it by a second fiber,
    * and each armed timer is a fiber of the thread's own supervisor that offers its firing once it
    * has slept, ending with that. A defect of the vote raises `defect`, which ends the thread.
    */
  def run[F[_], L, I, U](
      env: LeiosVotingEnv[F, L, I, U],
      schedule: LeiosVoteSchedule[F],
      keys: Vector[VotingKey],
      notifications: Resource[F, Queue[F, LeiosEbNotification]],
      trace: LeiosKernelEvent[I, U] => F[Unit]
  )(using F: Temporal[F], defect: Raise[F, LeiosVotingDefect]): F[Unit] =
    NonEmptyVector.fromVector(keys) match
      case None => trace(LeiosKernelEvent.VotingDisabled)
      case Some(_) =>
        (
          notifications,
          Supervisor[F],
          Resource.eval(Queue.unbounded[F, Wake]),
          Resource.eval(Ref.of[F, Map[EbPoint, Unique.Token]](Map.empty))
        ).tupled.use { (acquired, timers, wakes, armed) =>
          val moved: F[Unit] =
            acquired.take
              .flatMap {
                case LeiosEbNotification.AcquiredEbTxs(point) => wakes.offer(Wake.Acquired(point))
                case LeiosEbNotification.AcquiredEb(_, _)     => F.unit
              }
              .foreverM[Unit]

          def scheduleVoteTime(point: EbPoint): F[Unit] =
            schedule.onset(point.slot.toBigInt).flatMap {
              case None => trace(LeiosKernelEvent.NoVoteOnset(point))
              case Some(announced) =>
                (schedule.timing, env.now).flatMapN { (timing, now) =>
                  val (voteAt, deadline) = timing.windowFrom(announced)
                  val voteIn = voteAt - now
                  trace(LeiosKernelEvent.VoteScheduled(point, voteIn, deadline - now)) *>
                    F.unique.flatMap { token =>
                      armed.update(_.updated(point, token)) *>
                        timers
                          .supervise(
                            F.sleep(voteIn.max(Duration.Zero)) *>
                              wakes.offer(Wake.Due(point, token, deadline))
                          )
                          .void
                    }
                }
            }

          def fired(point: EbPoint, token: Unique.Token): F[Boolean] =
            armed.modify(m =>
              if m.get(point).contains(token) then (m - point, true) else (m, false)
            )

          val woken: F[Unit] =
            wakes.take
              .flatMap {
                case Wake.Acquired(point) => scheduleVoteTime(point)
                case Wake.Due(point, token, deadline) =>
                  fired(point, token).ifM(voted(env, keys, point, deadline, trace), F.unit)
              }
              .foreverM[Unit]

          // both loops are joined here, and neither ends but by the thread's end or a defect
          F.both(moved, woken).void
        }

  /** `goVote` of the endorser block at `point` and what it traces (`LeiosVoting.hs:287-294,
    * 393-419` at 2416493b).
    */
  private def voted[F[_], L, I, U](
      env: LeiosVotingEnv[F, L, I, U],
      keys: Vector[VotingKey],
      point: EbPoint,
      deadline: FiniteDuration,
      trace: LeiosKernelEvent[I, U] => F[Unit]
  )(using F: Temporal[F], defect: Raise[F, LeiosVotingDefect]): F[Unit] =
    def answered(vote: Vote, answer: AddVoteResult): F[Unit] = answer match
      case AddVoteResult.Added(tally, certificate) =>
        trace(LeiosKernelEvent.Voted(vote, tally.weight)) *>
          trace(LeiosKernelEvent.VoteAcquired(vote, tally)) *>
          certificate.traverse_(_ => trace(LeiosKernelEvent.Certified(vote.announcingRbHash)))
      case AddVoteResult.NoCommittee | AddVoteResult.Invalid(_) | AddVoteResult.AlreadyKnown =>
        trace(LeiosKernelEvent.SeatNotVoted(point, vote, answer))
    LeiosVoting.vote(env, keys, point, deadline).flatMap {
      case Right(answers) => answers.traverse_(answered.tupled)
      case Left(rejected @ LeiosNotVotedReason.VoteRejected(answers)) =>
        answers.traverse_(answered.tupled) *> trace(LeiosKernelEvent.NotVoted(point, rejected))
      case Left(reason) => trace(LeiosKernelEvent.NotVoted(point, reason))
    }
