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
  * creation must return a fresh unshared runtime. Publication observers only invalidate/enqueue
  * bounded in-memory work, without validation, external I/O, or owner reentry. The package-trusted
  * admission commit has the distinct AdmissionState contract: serialized evidence I/O may run
  * inside the gate and cancellation mask, so cancellation/shutdown depend on cooperative I/O
  * completion. Neither callback may re-enter this owner.
  */
private[lab] final class SubmissionOwner[F[_]] private (
    runtime: CoherentDriver[F],
    ownerId: Bytes,
    val profile: AdmissionProfile,
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
  // Acquiring the permit remains cancelable. Once acquired, the complete operation is masked.
  // Publication includes its notification; admission includes trusted evidence installation.
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
      pinFor(ownerId, counter, state, profile).leftMap(new ViewConstructionFailure(_))
    )
    result <- F.fromEither(
      AdmissionView
        .checkedTyped(pin, state.ledger)
        .leftMap(error => new ViewConstructionFailure(ViewConstructionError.View(error)))
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
  private[lab] def withCurrent[A](expected: StatePin)(commit: F[A]): F[Either[StatePin, A]] =
    locked {
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
  enum ViewConstructionError:
    case Domain(cause: PinDomain.Error)
    case Pin(cause: StatePin.ConstructionError)
    case View(cause: AdmissionView.ConstructionError)
    def message: String = this match
      case Domain(cause) => cause.message
      case Pin(cause)    => cause.message
      case View(cause)   => cause.message

  /** One effect-boundary renderer. The closed cause remains available without parsing this text. */
  final class ViewConstructionFailure(val error: ViewConstructionError)
      extends IllegalStateException(error.message)

  /** Pure role-safe assembly shared by the actual owner view path and boundary tests. */
  private[lab] def pinFor(
      ownerId: Bytes,
      counter: BigInt,
      state: CoherentSequence.State,
      profile: AdmissionProfile
  ): Either[ViewConstructionError, StatePin] =
    for
      owner <- PinDomain.OwnerId.checked(ownerId).left.map(ViewConstructionError.Domain(_))
      generation <- PinDomain.Generation.checked(counter).left.map(ViewConstructionError.Domain(_))
      coherent <- PinDomain.CoherentStateId
        .checked(state.id)
        .left
        .map(ViewConstructionError.Domain(_))
      ledger <- PinDomain.LedgerStateId
        .checked(state.ledger.id)
        .left
        .map(ViewConstructionError.Domain(_))
      environment <- PinDomain.EnvironmentId
        .checked(state.ledger.environment.id)
        .left
        .map(ViewConstructionError.Domain(_))
      slot <- PinDomain.ValidationSlot
        .checked(state.ledger.slot)
        .left
        .map(ViewConstructionError.Domain(_))
      pin <- StatePin
        .checkedTyped(
          owner,
          generation,
          state.certificates.state.tip,
          coherent,
          ledger,
          environment,
          slot,
          profile
        )
        .left
        .map(ViewConstructionError.Pin(_))
    yield pin

  private enum Lifecycle[F[_]]:
    case Initializing()
    case Active(observer: AdmissionStateObserver[F])
    case Poisoned(observer: Option[AdmissionStateObserver[F]])
    case Closed()

  def resource[F[_]: Async](
      freshRuntime: F[CoherentSequence.Runtime[F]],
      profile: AdmissionProfile = AdmissionProfile.AdaVkey
  ): Resource[F, SubmissionOwner[F]] =
    resourceAt(freshRuntime, BigInt(0), profile)

  /** Nonzero generation is exposed only for package-local exhaustion tests, never persisted state.
    */
  private[lab] def resourceAt[F[_]: Async](
      freshRuntime: F[CoherentSequence.Runtime[F]],
      initialGeneration: BigInt,
      profile: AdmissionProfile = AdmissionProfile.AdaVkey
  ): Resource[F, SubmissionOwner[F]] =
    val F = Async[F]
    Resource.make(for
      _ <- F.raiseUnless(profile != null)(
        new IllegalArgumentException("explicit admission profile required")
      )
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
    yield new SubmissionOwner(runtime, owner, profile, counter, lifecycle, gate))(_.close)
