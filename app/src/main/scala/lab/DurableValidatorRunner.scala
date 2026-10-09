// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Async, Ref, Resource}
import cats.syntax.all.*
import cats.effect.syntax.all.*
import lab.cbor.{Bytes, Cbor, Value}
import lab.network.ChainSync

/** Bounded durable runner; only cached confirmations are used for terminal reporting. */
private[lab] object DurableValidatorRunner:
  import BoundedChainFollower.{Event, Original, Peer}
  import BoundedValidatorRunner.{Policy, Stop as RunnerStop, Unavailable}
  import ValidatorTransitions.{Backend, ConfirmedState, StorageFailure, View}

  enum Stop:
    case Completed(reason: RunnerStop)
    case StorageFailure(potentiallyOlderThanDisk: Boolean)
  final case class Outcome(
      confirmed: ConfirmedState,
      reason: Stop,
      events: Int,
      returnedBytes: Long,
      reconnects: Int,
      cleanupFailure: Option[RunnerStop] = None
  )
  private final case class Uncertain(confirmed: ConfirmedState, potentiallyOlderThanDisk: Boolean)
  private final case class Halt(reason: RunnerStop) extends RuntimeException
  private final case class Retry(reason: String) extends RuntimeException
  private def describe(e: Throwable): String = e.getClass.getName + ": " +
    Option(e.getMessage).getOrElse("").take(512)

  final class Runner[F[_]: Async] private[DurableValidatorRunner] (
      context: SequenceInput.Context,
      backend: Backend[F],
      peer: Resource[F, Peer[F]],
      policy: Policy,
      started: Ref[F, Boolean],
      events: Ref[F, Int],
      bytes: Ref[F, Long],
      retries: Ref[F, Int],
      cleanup: Ref[F, Option[RunnerStop]],
      uncertain: Ref[F, Option[Uncertain]],
      between: String => F[Unit]
  ):
    private val F = Async[F]

    /** Safe reporting cache, including after storage failure or cancellation. */
    def snapshot: F[ConfirmedState] = uncertain.get.flatMap {
      case Some(failure) => F.pure(failure.confirmed)
      case None          => backend.lastConfirmed
    }
    private def operation[A](action: F[A]): F[A] = action.handleErrorWith {
      case failure: StorageFailure =>
        uncertain.set(Some(Uncertain(failure.lastConfirmed, failure.potentiallyOlderThanDisk))) *>
          F.raiseError(failure)
      case error => F.raiseError(error)
    }
    private def mutation[A](action: F[A]): F[A] = operation(action).onCancel {
      // Backend cancellation may poison a publication after disk installation. Never query it.
      backend.lastConfirmed.flatMap(c => uncertain.update(_.orElse(Some(Uncertain(c, true)))))
    }
    private def view: F[View] = operation(backend.snapshot)
    private def stop[A](reason: RunnerStop): F[A] = F.raiseError(Halt(reason))
    private def checked[A](result: ValidatorTransitions.Result[A]): F[A] = result.fold(
      {
        case ValidatorTransitions.Rejection.Validation(failure) =>
          stop(BoundedValidatorRunner.sequenceFailure(failure))
        case other => stop(RunnerStop.Internal(other.toString))
      },
      F.pure
    )
    private def wire[A](action: F[A]): F[A] = action.handleErrorWith {
      case e: Unavailable => F.raiseError(Retry(describe(e)))
      case e              => stop(RunnerStop.PeerFailure(describe(e)))
    }
    private def charge(n: Int, bound: Int): F[Unit] =
      bytes.updateAndGet(_ + n.toLong).flatMap { total =>
        if total > policy.maxBytes then stop(RunnerStop.ByteBudget)
        else if n <= 0 || n > bound then stop(RunnerStop.Rejected("input", "object size bound"))
        else F.unit
      }
    private def rollback(point: ChainSync.Point): F[Unit] =
      view.flatMap(s => mutation(backend.rollbackTo(s, point))).flatMap(checked).void *>
        between("after-rollback")
    private def header(envelope: Bytes): Either[RunnerStop, ReferenceCaptureCommand.Header] =
      Cbor
        .decode(envelope, Cbor.Limits(65535, 24, 8192, 65535))
        .leftMap(RunnerStop.Rejected("header", _))
        .flatMap { root =>
          val unsupportedEra = root.value match
            case Value.Arr(parts) if parts.size == 2 =>
              (parts(0).value, parts(1).value) match
                case (Value.UInt(era), Value.Tag(tag, body)) if era != 6 && tag == 24 =>
                  body.value match
                    case Value.ByteString(raw) if raw.size <= 4096 => Some(era)
                    case _                                         => None
                case _ => None
            case _ => None
          unsupportedEra match
            case Some(era) =>
              Left(RunnerStop.Unsupported("era", s"NtN era $era; Conway index 6 only"))
            case None =>
              ReferenceCaptureCommand.header(envelope).leftMap(RunnerStop.Rejected("header", _))
        }
    private def forward(p: Peer[F], envelope: Bytes): F[Unit] =
      for
        _ <- charge(envelope.size, 65535)
        h <- F.delay(header(envelope)).flatMap(_.fold(stop, F.pure))
        _ <-
          if h.major != 11 || h.minor != 2 then stop(RunnerStop.Unsupported("header", "11.2 only"))
          else F.unit
        _ <-
          if h.slot / context.nonces.context.epochLength != context.epoch then
            stop(RunnerStop.Unsupported("epoch", "same supplied epoch only"))
          else F.unit
        current <- view
        extendsTip = current.state.acquisition.tip match
          case ChainSync.Point.Block(slot, hash) => h.parent == hash && h.slot > slot.value
          case _                                 => false
        _ <-
          if extendsTip then F.unit
          else stop(RunnerStop.Rejected("header", "announcement does not extend scoped tip"))
        point = ChainSync.Point.Block(ChainSync.UInt64.from(h.slot).toOption.get, h.hash)
        raw <- wire(p.fetch(point))
        _ <- charge(raw.size, 1048576)
        block <- F
          .delay(SequenceInput.block(Original(envelope, raw)))
          .flatMap(
            _.fold(
              {
                case SequenceInput.Failure.Unsupported(feature) =>
                  stop(RunnerStop.Unsupported("input", feature))
                case SequenceInput.Failure.Rejected(stage, detail) =>
                  stop(RunnerStop.Rejected(stage, detail))
              },
              F.pure
            )
          )
        _ <- F.cede *> between("before-prepare")
        before <- view
        candidate <- operation(backend.prepare(before, block)).flatMap(checked)
        _ <- F.cede *> between("before-publish")
        _ <- mutation(backend.publish(candidate)).flatMap(checked)
        _ <- between("after-publish")
      yield ()
    private def loop(p: Peer[F]): F[RunnerStop] = view.flatMap { s =>
      if s.state.depth >= policy.target then F.pure(RunnerStop.TargetReached)
      else
        events.modify(n => if n < policy.maxEvents then (n + 1, true) else (n, false)).flatMap {
          case false => F.pure(RunnerStop.EventBudget)
          case true =>
            wire(p.next).flatMap {
              case Event.Await             => loop(p)
              case Event.Backward(point)   => rollback(point) *> loop(p)
              case Event.Forward(envelope) => forward(p, envelope) *> loop(p)
            }
        }
    }
    private def session: F[RunnerStop] = F.uncancelable { poll =>
      // allocated remains cancelable; Resource itself releases any partially acquired resources.
      // Acquisition can include failed partial-resource cleanup. Without a typed release channel,
      // even Unavailable here is terminal; only established Peer operations may request a retry.
      poll(peer.allocated.handleErrorWith(e => stop(RunnerStop.PeerFailure(describe(e))))).flatMap {
        (p, release) =>
          val work = for
            s <- view
            offered = s.state.acquisition.candidates
            selected <- wire(p.intersect(offered))
            _ <-
              if offered.contains(selected) then rollback(selected)
              else stop(RunnerStop.OutsideRetainedWindow)
            result <- loop(p)
          yield result
          poll(work).guarantee(release.handleErrorWith { e =>
            val reason = RunnerStop.CleanupFailed(describe(e))
            cleanup.set(Some(reason)) *> stop(reason)
          })
      }
    }
    private def attempt: F[RunnerStop] = session.handleErrorWith { error =>
      uncertain.get.flatMap {
        case Some(_) => F.pure(RunnerStop.Internal("storage backend terminated"))
        case None =>
          cleanup.get.flatMap {
            case Some(reason) => F.pure(reason)
            case None =>
              error match
                case Retry(reason) =>
                  retries
                    .modify(n => if n < policy.reconnects then (n + 1, true) else (n, false))
                    .flatMap {
                      case true  => F.cede *> attempt
                      case false => F.pure(RunnerStop.TransportExhausted(reason))
                    }
                case Halt(reason) => F.pure(reason)
                case other        => F.pure(RunnerStop.Internal(describe(other)))
          }
      }
    }
    private def outcome(reason: RunnerStop): F[Outcome] =
      (snapshot, uncertain.get, cleanup.get, events.get, bytes.get, retries.get).mapN {
        (confirmed, failure, cleanupFailure, e, b, r) =>
          failure match
            case Some(stale) =>
              Outcome(
                stale.confirmed,
                Stop.StorageFailure(stale.potentiallyOlderThanDisk),
                e,
                b,
                r,
                cleanupFailure
              )
            case None =>
              Outcome(
                confirmed,
                Stop.Completed(cleanupFailure.getOrElse(reason)),
                e,
                b,
                r,
                cleanupFailure
              )
      }
    def run: F[Outcome] = started.getAndSet(true).flatMap {
      case true => outcome(RunnerStop.AlreadyRun)
      case false =>
        val work = snapshot.flatMap { cached =>
          if cached.state.depth >= policy.target then F.pure(RunnerStop.TargetReached)
          else attempt
        }
        F.timeoutTo(work, policy.duration, F.pure(RunnerStop.TimeBudget)).flatMap(outcome)
    }

  /** Policy validation precedes backend acquisition; backend verification precedes any peer IO. */
  def resource[F[_]: Async](
      context: SequenceInput.Context,
      backend: Resource[F, Backend[F]],
      peer: Resource[F, Peer[F]],
      policy: Policy,
      between: String => F[Unit]
  ): Resource[F, Runner[F]] =
    val F = Async[F]
    Resource
      .eval(
        F.raiseUnless(
          policy.valid && !policy.advanceWindow && policy.target <= policy.rollbackCapacity
        )(new IllegalArgumentException("bounded durable policy required; compaction unsupported"))
      )
      .flatMap { _ =>
        backend.flatMap { owned =>
          Resource.eval {
            for
              initial <- owned.snapshot
              _ <- F.raiseUnless(
                owned.capacity == policy.rollbackCapacity && initial.state.contextId == context.id &&
                  initial.confirmation.tokenOption.nonEmpty && initial.state.derivedAnchorId.isEmpty &&
                  initial.state.compactedBlocks == 0 && initial.state.depth <= policy.rollbackCapacity
              )(new IllegalArgumentException("durable backend context/capacity/profile mismatch"))
              started <- Ref.of[F, Boolean](false)
              events <- Ref.of[F, Int](0)
              bytes <- Ref.of[F, Long](0L)
              retries <- Ref.of[F, Int](0)
              cleanup <- Ref.of[F, Option[RunnerStop]](None)
              uncertain <- Ref.of[F, Option[Uncertain]](None)
            yield new Runner(
              context,
              owned,
              peer,
              policy,
              started,
              events,
              bytes,
              retries,
              cleanup,
              uncertain,
              between
            )
          }
        }
      }
