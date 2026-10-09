// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import java.security.SecureRandom
import lab.cbor.Bytes
import lab.network.ChainSync
import lab.submission.*

/** Exclusive owner of the follower mutation surface and admission linearization gate. Runtime
  * creation must return a fresh unshared runtime. Callbacks are trusted, bounded memory operations:
  * they may neither reenter this owner nor perform validation or external I/O.
  */
private[lab] final class SubmissionOwner[F[_]] private (
    runtime: CoherentDriver[F],
    ownerId: Bytes,
    generation: Ref[F, BigInt],
    lifecycle: Ref[F, SubmissionOwner.Lifecycle[F]],
    gate: Semaphore[F]
)(using F: Async[F])
    extends CoherentDriver[F]
    with AdmissionState[F]:
  import CoherentSequence.*
  import SubmissionOwner.*
  import AdmissionState.UnavailableReason

  val maxBlocks: Int = runtime.maxBlocks
  def snapshot: F[Snapshot] = runtime.snapshot
  def prepare(block: SequenceInput.Block): F[Result[Candidate]] = runtime.prepare(block)
  private[lab] def prepareSyntheticBlock(fence: Fence, block: SequenceInput.Block) =
    runtime.prepareSyntheticBlock(fence, block)
  def prepareSyntheticSuccessor(fence: Fence, headerHash: Bytes, slot: BigInt) =
    runtime.prepareSyntheticSuccessor(fence, headerHash, slot)
  private[lab] def prepareSyntheticSuccessorBlock(
      fence: Fence,
      preview: SyntheticSuccessor,
      block: SequenceInput.Block
  ) = runtime.prepareSyntheticSuccessorBlock(fence, preview, block)

  private def unavailable[A](reason: UnavailableReason): F[A] =
    F.raiseError(new AdmissionState.Unavailable(reason))
  // Acquiring the permit remains cancelable. Publication and its notification are one mask.
  private def locked[A](operation: F[A]): F[A] =
    gate.permit.use(_ => F.uncancelable(_ => operation))
  private def active: F[AdmissionStateObserver[F]] = lifecycle.get.flatMap {
    case Lifecycle.Initializing()   => unavailable(UnavailableReason.Initializing)
    case Lifecycle.Active(observer) => F.pure(observer)
    case Lifecycle.Poisoned(_)      => unavailable(UnavailableReason.Poisoned)
    case Lifecycle.Closed()         => unavailable(UnavailableReason.Closed)
  }
  private def capacity: F[Unit] = generation.get.flatMap { value =>
    if value >= StatePin.MaxUInt64 then unavailable(UnavailableReason.GenerationExhausted)
    else F.unit
  }
  private def poisonOnError[A](operation: F[A]): F[A] =
    operation.onError { case _ =>
      lifecycle.update {
        case Lifecycle.Active(observer)    => Lifecycle.Poisoned(Some(observer))
        case state @ Lifecycle.Poisoned(_) => state
        case state @ Lifecycle.Closed()    => state
        case _                             => Lifecycle.Poisoned(None)
      }
    }
  private def view: F[AdmissionView] = for
    snapshot <- runtime.snapshot
    counter <- generation.get
    state = snapshot.state
    pin <- F.fromEither(
      StatePin
        .checked(
          ownerId,
          counter,
          state.certificates.state.tip,
          state.id,
          state.ledger.id,
          state.ledger.environment.id,
          state.ledger.slot,
          StatePin.Profile
        )
        .leftMap(new IllegalStateException(_))
    )
    result <- F.fromEither(
      AdmissionView.checked(pin, state.ledger).leftMap(new IllegalStateException(_))
    )
  yield result

  def current: F[AdmissionView] = locked {
    lifecycle.get.flatMap {
      case Lifecycle.Closed()    => unavailable(UnavailableReason.Closed)
      case Lifecycle.Poisoned(_) => unavailable(UnavailableReason.Poisoned)
      case _                     => view
    }
  }
  def attach(observer: AdmissionStateObserver[F]): F[Unit] = locked {
    lifecycle.get.flatMap {
      case Lifecycle.Initializing() if observer != null => lifecycle.set(Lifecycle.Active(observer))
      case _ =>
        F.raiseError(new IllegalStateException("submission observer can attach exactly once"))
    }
  }
  def withCurrent[A](expected: StatePin)(commit: F[A]): F[Either[StatePin, A]] = locked {
    active *> capacity *> view.flatMap { now =>
      if now.pin != expected then F.pure(Left(now.pin))
      else poisonOnError(commit).map(Right(_))
    }
  }
  private def mutate[A](kind: StateChangeKind, operation: F[Result[A]])(
      included: A => Vector[IncludedTransaction]
  ): F[Result[A]] = locked {
    for
      observer <- active
      _ <- capacity
      result <- poisonOnError(operation.flatTap {
        case Left(_) => F.unit
        case Right(value) =>
          generation.update(_ + 1) *> view.flatMap { current =>
            observer.changed(AdmissionStateChange(current, kind, included(value)))
          }
      })
    yield result
  }
  def publish(candidate: Candidate): F[Result[Applied]] =
    mutate(StateChangeKind.Published, runtime.publish(candidate)) { applied =>
      val receipt = applied.ledgerObservation.candidate
      receipt.transactionIds.zipWithIndex.map { (id, index) =>
        val spans = receipt.transactionMemos
          .lift(index)
          .flatMap(SignedTransaction.checked(_).toOption)
          .filter(_.transactionId == id)
        IncludedTransaction(id, spans.map(_.originalBody), spans.map(_.originalWitnesses))
      }
    }
  def rollbackTo(fence: Fence, target: ChainSync.Point): F[Result[Snapshot]] =
    mutate(StateChangeKind.RolledBack, runtime.rollbackTo(fence, target))(_ => Vector.empty)
  def advanceAnchor(fence: Fence, through: ChainSync.Point): F[Result[Snapshot]] =
    mutate(StateChangeKind.AnchorMoved, runtime.advanceAnchor(fence, through))(_ => Vector.empty)
  def close: F[Unit] = locked {
    lifecycle.getAndSet(Lifecycle.Closed()).flatMap {
      case Lifecycle.Active(observer)         => observer.closed
      case Lifecycle.Poisoned(Some(observer)) => observer.closed
      case _                                  => F.unit
    }
  }

private[lab] object SubmissionOwner:
  private enum Lifecycle[F[_]]:
    case Initializing()
    case Active(observer: AdmissionStateObserver[F])
    case Poisoned(observer: Option[AdmissionStateObserver[F]])
    case Closed()

  def resource[F[_]: Async](
      freshRuntime: F[CoherentSequence.Runtime[F]]
  ): Resource[F, SubmissionOwner[F]] =
    resourceAt(freshRuntime, BigInt(0))

  /** Nonzero generation is exposed only for package-local exhaustion tests, never persisted state.
    */
  private[lab] def resourceAt[F[_]: Async](
      freshRuntime: F[CoherentSequence.Runtime[F]],
      initialGeneration: BigInt
  ): Resource[F, SubmissionOwner[F]] =
    val F = Async[F]
    Resource.make(for
      _ <- F.raiseUnless(initialGeneration >= 0 && initialGeneration <= StatePin.MaxUInt64)(
        new IllegalArgumentException("uint64 initial generation required")
      )
      owner <- F.blocking {
        val bytes = new Array[Byte](32)
        new SecureRandom().nextBytes(bytes)
        Bytes.fromArray(bytes)
      }
      runtime <- freshRuntime
      counter <- Ref.of[F, BigInt](initialGeneration)
      lifecycle <- Ref.of[F, Lifecycle[F]](Lifecycle.Initializing())
      gate <- Semaphore[F](1)
    yield new SubmissionOwner(runtime, owner, counter, lifecycle, gate))(_.close)
