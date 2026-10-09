// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.Semaphore
import cats.effect.syntax.all.*
import cats.syntax.all.*
import java.nio.file.Path
import java.util.concurrent.CancellationException
import scala.concurrent.duration.FiniteDuration
import lab.cbor.Bytes
import lab.network.ChainSync

/** Private runner-owned adapters. No validation, runtime, owner or mutable store escapes. */
private[lab] object ValidatorTransitions:
  import CoherentSequence as Sequence
  import ValidatedCheckpoint.Token

  enum Confirmation:
    case Volatile
    case LoadedVerified(token: Token)
    case Acknowledged(token: Token)
    def tokenOption: Option[Token] = this match
      case Volatile              => None
      case LoadedVerified(token) => Some(token)
      case Acknowledged(token)   => Some(token)

  final class ConfirmedState private[ValidatorTransitions] (
      val state: Sequence.State,
      val confirmation: Confirmation
  )
  final class View private[ValidatorTransitions] (
      private[ValidatorTransitions] val owner: AnyRef,
      private[ValidatorTransitions] val underlying: Sequence.Snapshot,
      val confirmed: ConfirmedState
  ):
    def state: Sequence.State = confirmed.state
    def confirmation: Confirmation = confirmed.confirmation

  final class Prepared private[ValidatorTransitions] (
      private[ValidatorTransitions] val owner: AnyRef,
      private[ValidatorTransitions] val before: View,
      private[ValidatorTransitions] val candidate: Sequence.Candidate
  )
  enum Rejection:
    case ForeignSession, StaleView
    case Validation(failure: Sequence.Failure)
  type Result[A] = Either[Rejection, A]

  /** A reporting value, never permission to retry or a claim about the current disk state. */
  final class StorageFailure private[ValidatorTransitions] (
      val lastConfirmed: ConfirmedState,
      val potentiallyOlderThanDisk: Boolean,
      cause: Throwable
  ) extends RuntimeException("transition backend terminated; cached confirmation only", cause)

  private enum Owned[F[_]]:
    case Memory(runtime: Sequence.Runtime[F])
    case Durable(runtime: Sequence.DurableRuntime[F])

  final class Backend[F[_]] private[ValidatorTransitions] (
      owned: Owned[F],
      owner: AnyRef,
      gate: Semaphore[F],
      view: Ref[F, View],
      confirmed: Ref[F, ConfirmedState],
      failed: Ref[F, Option[StorageFailure]],
      closed: Ref[F, Boolean]
  )(using F: Async[F]):
    private val durable = owned match
      case Owned.Durable(_) => true
      case _                => false

    /** Safe after terminal storage failure. This may be older than disk; never a fresh query. */
    def lastConfirmed: F[ConfirmedState] = confirmed.get
    private def active: F[Unit] =
      closed.get.flatMap(c => F.raiseWhen(c)(new IllegalStateException("backend closed"))) *>
        failed.get.flatMap(_.fold(F.unit)(F.raiseError))
    private def terminate(error: Throwable): F[StorageFailure] = confirmed.get.flatMap { last =>
      failed.modify {
        case Some(previous) => (Some(previous), previous)
        case None =>
          val failure = new StorageFailure(last, durable, error)
          (Some(failure), failure)
      }
    }
    private def locked[A](work: F[A]): F[A] = gate.permit.use { _ =>
      active *> work.handleErrorWith(e => terminate(e).flatMap(F.raiseError))
    }
    private def check(expected: View): F[Result[Unit]] =
      if expected.owner ne owner then F.pure(Left(Rejection.ForeignSession))
      else view.get.map(current => Either.cond(current eq expected, (), Rejection.StaleView))
    def snapshot: F[View] = locked(view.get)

    def prepare(expected: View, block: SequenceInput.Block): F[Result[Prepared]] = locked {
      check(expected).flatMap {
        case Left(error) => F.pure(Left(error))
        case Right(_) =>
          val prepare = owned match
            case Owned.Memory(runtime)  => runtime.prepare(block)
            case Owned.Durable(runtime) => runtime.prepare(block)
          prepare.map(_.leftMap(Rejection.Validation.apply).map(new Prepared(owner, expected, _)))
      }
    }
    private def install(snapshot: Sequence.Snapshot, status: Confirmation): F[View] =
      val receipt = new ConfirmedState(snapshot.state, status)
      val next = new View(owner, snapshot, receipt)
      confirmed.set(receipt) *> view.set(next).as(next)
    private def mutation[A](operation: F[A])(finish: A => F[Result[View]]): F[Result[View]] =
      F.uncancelable { poll =>
        poll(operation <* F.cede)
          .onCancel(terminate(new CancellationException("publication interrupted")).void)
          .flatMap(finish)
      }
    def publish(prepared: Prepared): F[Result[View]] = locked {
      if prepared.owner ne owner then F.pure(Left(Rejection.ForeignSession))
      else
        check(prepared.before).flatMap {
          case Left(error) => F.pure(Left(error))
          case Right(_) =>
            owned match
              case Owned.Memory(runtime) =>
                mutation(runtime.publish(prepared.candidate)) {
                  case Left(error) => F.pure(Left(Rejection.Validation(error)))
                  case Right(applied) =>
                    confirmed.set(new ConfirmedState(applied.state, Confirmation.Volatile)) *>
                      runtime.snapshot.flatMap(install(_, Confirmation.Volatile)).map(Right(_))
                }
              case Owned.Durable(runtime) =>
                mutation(
                  runtime.publish(prepared.candidate, prepared.before.confirmation.tokenOption.get)
                ) {
                  case Left(error) => F.pure(Left(Rejection.Validation(error)))
                  case Right(ack) =>
                    val status = Confirmation.Acknowledged(ack.token)
                    // Only a successfully returned acknowledgement can replace the reporting cache.
                    confirmed.set(new ConfirmedState(ack.value.state, status)) *>
                      runtime.snapshot.flatMap { saved =>
                        F.raiseUnless(
                          saved.token == ack.token && saved.snapshot.state.id == ack.value.state.id && saved.snapshot.state.revision == ack.value.state.revision
                        )(new IllegalStateException("acknowledgement/snapshot mismatch")) *>
                          install(saved.snapshot, status).map(Right(_))
                      }
                }
        }
    }
    def rollbackTo(expected: View, target: ChainSync.Point): F[Result[View]] = locked {
      check(expected).flatMap {
        case Left(error) => F.pure(Left(error))
        case Right(_) =>
          owned match
            case Owned.Memory(runtime) =>
              mutation(runtime.rollbackTo(expected.underlying.fence, target)) {
                case Left(error) => F.pure(Left(Rejection.Validation(error)))
                case Right(saved) if saved.state.revision == expected.state.revision =>
                  F.pure(Right(expected))
                case Right(saved) => install(saved, Confirmation.Volatile).map(Right(_))
              }
            case Owned.Durable(runtime) =>
              mutation(
                runtime.rollbackTo(
                  expected.underlying.fence,
                  target,
                  expected.confirmation.tokenOption.get
                )
              ) {
                case Left(error) => F.pure(Left(Rejection.Validation(error)))
                case Right(ack) if expected.confirmation.tokenOption.contains(ack.token) =>
                  F.raiseUnless(
                    ack.value.state.id == expected.state.id && ack.value.state.revision == expected.state.revision
                  )(new IllegalStateException("unchanged token with changed state")) *>
                    F.pure(Right(expected))
                case Right(ack) =>
                  install(ack.value, Confirmation.Acknowledged(ack.token)).map(Right(_))
              }
      }
    }
    private[ValidatorTransitions] def close: F[Unit] = F.uncancelable { _ =>
      gate.permit.use(_ => closed.set(true))
    }

  private def wrap[F[_]: Async](owned: Owned[F], loaded: Boolean): Resource[F, Backend[F]] =
    val F = Async[F]
    Resource.make {
      for
        initial <- owned match
          case Owned.Memory(runtime) => runtime.snapshot.map(_ -> Confirmation.Volatile)
          case Owned.Durable(runtime) =>
            runtime.snapshot.map(s =>
              s.snapshot -> (if loaded then Confirmation.LoadedVerified(s.token)
                             else Confirmation.Acknowledged(s.token))
            )
        owner <- F.delay(new Object())
        receipt = new ConfirmedState(initial._1.state, initial._2)
        view <- Ref.of[F, View](new View(owner, initial._1, receipt))
        confirmed <- Ref.of[F, ConfirmedState](receipt)
        failed <- Ref.of[F, Option[StorageFailure]](None)
        closed <- Ref.of[F, Boolean](false)
        gate <- Semaphore[F](1)
      yield new Backend(owned, owner, gate, view, confirmed, failed, closed)
    }(_.close)

  def inMemory[F[_]: Async](
      context: SequenceInput.Context,
      capacity: Int = Sequence.MaxBlocks
  ): Resource[F, Backend[F]] =
    Resource
      .eval(
        Sequence
          .create[F](context, capacity)
          .flatMap(r =>
            Async[F].fromEither(r.leftMap(e => new IllegalArgumentException(e.toString)))
          )
      )
      .flatMap(runtime => wrap(Owned.Memory(runtime), false))
  def durableCreate[F[_]: Async](
      root: Path,
      context: SequenceInput.Context,
      capacity: Int,
      recorderDeadline: FiniteDuration,
      record: Sequence.PendingTokens => F[Unit]
  ): Resource[F, Backend[F]] =
    Sequence
      .durableCreate[F](root, context, capacity, recorderDeadline, record)
      .flatMap(runtime => wrap(Owned.Durable(runtime), false))
  def durableResume[F[_]: Async](
      root: Path,
      expectedContext: Bytes,
      token: Token,
      recoveryDeadline: FiniteDuration,
      recorderDeadline: FiniteDuration,
      record: Sequence.PendingTokens => F[Unit]
  ): Resource[F, Backend[F]] =
    Sequence
      .durableResume[F](root, expectedContext, token, recoveryDeadline, recorderDeadline, record)
      .flatMap(runtime => wrap(Owned.Durable(runtime), true))

  /** Labels-only deterministic faults for tests; no external runtime or mutation capability. */
  private[lab] def durableCreateObserved[F[_]: Async](
      root: Path,
      context: SequenceInput.Context,
      recorderDeadline: FiniteDuration,
      record: Sequence.PendingTokens => F[Unit],
      faults: NioValidatedCheckpointStore.Faults,
      observe: Sequence.DurablePhase => F[Unit]
  ): Resource[F, Backend[F]] =
    Sequence
      .durableResource[F](
        root,
        Some(context),
        context.id,
        None,
        Sequence.MaxBlocks,
        recorderDeadline,
        recorderDeadline,
        record,
        faults,
        observe
      )
      .flatMap(runtime => wrap(Owned.Durable(runtime), false))
