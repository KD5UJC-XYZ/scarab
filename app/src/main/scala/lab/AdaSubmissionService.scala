// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.{Queue, Semaphore}
import cats.syntax.all.*
import scala.concurrent.duration.*
import lab.cbor.Bytes
import lab.ledger.{AdmissionValidation, ScopedAdmission, AdaPool}
import lab.ledger.runtime.AdaIngressBudget
import lab.submission.*

/** Volatile, scoped ADA pool. The supplied owner is the only chain mutation authority. */
private[lab] final class AdaSubmissionService[F[_]] private (
    owner: AdmissionState[F],
    val profile: AdmissionProfile,
    pool: Ref[F, AdaPool.State[StatePin]],
    budget: AdaIngressBudget[F],
    validations: Semaphore[F],
    pending: Ref[F, Option[(AdmissionView, AdaPool.Rebuild[StatePin])]],
    wake: Queue[F, Unit],
    val relaySource: lab.network.RelaySource[F],
    evidence: Option[PlutusEvaluationEvidence.Observer[F]]
)(using F: Async[F])
    extends AdmissionStateObserver[F]:
  import AdaSubmissionService.*

  final class Request private[AdaSubmissionService] (permit: budget.Request):
    def submit(original: Bytes): F[Result] = permit.validate(admit(original))

  def request: Resource[F, Option[Request]] = budget.request.map(_.map(new Request(_)))

  private def guarded[A](read: AdaPool.State[StatePin] => F[A]): F[A] =
    AdaSubmissionService.guarded(owner, pool)(read)

  def snapshot: F[Snapshot] = guarded(s =>
    F.monotonic.map(t =>
      Snapshot(s.pin, s.eligible(t.toNanos), s.rebuilding, s.closed, s.size, s.byteSize)
    )
  )
  def status(id: Bytes): F[Option[AdaPool.Status[StatePin]]] =
    guarded(s => F.monotonic.map(t => s.status(id, t.toNanos)))

  /** Runs while the owner fence is held. A failed sink closes relay eligibility before release. The
    * bounded filesystem sink waits for in-flight writes on cancellation; five seconds is a
    * cooperative sink deadline, not a hard real-time filesystem guarantee.
    */
  private def observe(
      candidate: ScopedAdmission.Candidate[StatePin],
      phase: String,
      outcome: String
  ): F[Unit] =
    evidence.fold(F.unit) { sink =>
      F.delay(PlutusEvaluationEvidence.checked(candidate, phase, outcome))
        .flatMap {
          case None        => F.unit
          case Some(value) => F.timeout(sink.observe(value), 5.seconds)
        }
        .handleErrorWith { _ =>
          F.monotonic.flatMap(t => pool.update(AdaPool.shutdown(_, t.toNanos))) *>
            F.raiseError(new EvidenceUnavailable)
        }
    }

  private def admit(original: Bytes): F[Result] =
    (for
      view <- owner.current
      available <- guarded(s =>
        F.pure(
          !s.closed && !s.rebuilding && s.profile == profile && s.pin.profileId == profile.id && view.pin.profileId == profile.id
        )
      )
      result <-
        if !available then F.pure(Result.Unavailable)
        else
          validations.permit.use { _ =>
            F.cede *> F
              .delay(
                AdmissionValidation.prepare(
                  profile,
                  view.pin,
                  view.ledger,
                  original,
                  Some(lab.vm.Pv9SubmissionEvaluator)
                )
              )
              .flatMap {
                case Left(error) => F.pure(Result.Rejected(error))
                case Right(candidate) =>
                  if candidate.profile != profile || view.pin.profileId != profile.id ||
                    candidate.ledgerStateId != view.pin.ledgerStateId ||
                    candidate.environmentId != view.pin.environmentId ||
                    candidate.validationSlot != view.pin.validationSlot || candidate.pin != view.pin
                  then F.pure(Result.Unavailable)
                  else
                    owner
                      .withCurrent(view.pin)(
                        F.uncancelable { _ =>
                          F.monotonic
                            .flatMap(t => pool.modify(s => AdaPool.admit(s, candidate, t.toNanos)))
                            .flatTap { outcome =>
                              val label = outcome match
                                case AdaPool.Outcome.Accepted(_)       => "accepted"
                                case AdaPool.Outcome.AlreadyPresent(_) => "already-present"
                                case AdaPool.Outcome.Rejected(_)       => "pool-rejected"
                                case AdaPool.Outcome.Retry(_)          => "retry"
                                case AdaPool.Outcome.Unavailable       => "unavailable"
                              observe(candidate, "admission", label)
                            }
                        }
                      )
                      .map {
                        case Left(pin)                                => Result.Retry(pin)
                        case Right(AdaPool.Outcome.Accepted(receipt)) => Result.Accepted(receipt)
                        case Right(AdaPool.Outcome.AlreadyPresent(receipt)) =>
                          Result.AlreadyPresent(receipt)
                        case Right(AdaPool.Outcome.Rejected(reason)) => Result.PoolRejected(reason)
                        case Right(AdaPool.Outcome.Retry(pin))       => Result.Retry(pin)
                        case Right(AdaPool.Outcome.Unavailable)      => Result.Unavailable
                      }
              }
          }
    yield result).handleErrorWith {
      case _: EvidenceUnavailable        => F.pure(Result.Unavailable)
      case _: AdmissionState.Unavailable => F.pure(Result.Unavailable)
      case error                         => F.raiseError(error)
    }

  /** Called under the owner gate; no validation, fiber joining, or owner re-entry here. */
  def changed(change: AdmissionStateChange): F[Unit] =
    F.raiseUnless(change.view.pin.profileId == profile.id)(
      new IllegalStateException("owner profile changed")
    ) *> F.monotonic.flatMap { now =>
      pool
        .modify { old =>
          val base = change.kind match
            case StateChangeKind.RolledBack | StateChangeKind.Reset =>
              AdaPool.invalidateIncluded(
                old,
                pin =>
                  change.kind == StateChangeKind.Reset ||
                    pin.point.blockNo > change.view.pin.point.blockNo ||
                    (pin.point.blockNo == change.view.pin.point.blockNo && pin.point.hash != change.view.pin.point.hash)
              )
            case _ => old
          AdaPool.move(
            base,
            change.view.pin,
            change.included.map(_.transactionId).toSet,
            now.toNanos
          )
        }
        .flatMap(work => pending.set(Some(change.view -> work)) *> wake.tryOffer(()).void)
    }

  def closed: F[Unit] = F.monotonic.flatMap(t => pool.update(AdaPool.shutdown(_, t.toNanos))) *>
    pending.set(None) *> wake.tryOffer(()).void

  private def rebuild(view: AdmissionView, work: AdaPool.Rebuild[StatePin]): F[Unit] =
    validations.permit
      .use { _ =>
        F.cede *> F
          .delay(AdaPool.revalidate(work, view.ledger, Some(lab.vm.Pv9SubmissionEvaluator)))
          .flatMap { result =>
            owner
              .withCurrent(view.pin)(
                F.uncancelable { _ =>
                  F.monotonic.flatMap { time =>
                    pool
                      .modify { old =>
                        val (next, committed) = AdaPool.finish(old, result, time.toNanos)
                        (next, (next, committed))
                      }
                      .flatMap { (next, committed) =>
                        result.evaluatedCandidates.traverse_ { candidate =>
                          val retained = committed && next
                            .status(candidate.transaction.transactionId, time.toNanos)
                            .exists {
                              case AdaPool.Status.Pending(receipt, true) =>
                                receipt.pin == candidate.pin && receipt.envelopeSHA256 == candidate.transaction.envelopeSHA256
                              case _ => false
                            }
                          observe(
                            candidate,
                            "revalidation",
                            if retained then "retained" else "discarded"
                          )
                        }
                      }
                  }
                }
              )
              .void
          }
      }
      .handleErrorWith {
        case _: AdmissionState.Unavailable => F.unit
        case error                         => F.raiseError(error)
      }

  private def worker(wait: Boolean): F[Unit] =
    (if wait then wake.take else F.unit) *> pending.get.flatMap {
      case None => worker(true)
      case Some((view, work)) =>
        F.race(rebuild(view, work), wake.take).flatMap {
          // A losing take may already have consumed a concurrent wake. Inspect the
          // authoritative pending slot before waiting again, whichever branch won.
          case Left(_) =>
            pending.update(_.filterNot { case (_, current) => current eq work }) *> worker(false)
          case Right(_) => worker(false)
        }
    }

  private def expiry: F[Unit] =
    (F.sleep(1.second) *> guarded(_ =>
      F.monotonic.flatMap(t => pool.update(AdaPool.expire(_, t.toNanos)))
    )
      .handleErrorWith {
        case _: AdmissionState.Unavailable => F.unit
        case error                         => F.raiseError(error)
      }).foreverM

  private def stop: F[Unit] =
    guarded(_ => closed).handleErrorWith {
      case _: AdmissionState.Unavailable => closed
      case error                         => F.raiseError(error)
    }

private[lab] object AdaSubmissionService:
  private final class EvidenceUnavailable
      extends RuntimeException("evaluation evidence unavailable; pool closed")
  enum Result:
    case Accepted(receipt: AdaPool.Receipt[StatePin])
    case AlreadyPresent(receipt: AdaPool.Receipt[StatePin])
    case Rejected(error: ScopedAdmission.Failure)
    case PoolRejected(reason: AdaPool.Rejection)
    case Retry(currentPin: StatePin)
    case Unavailable
  final case class Snapshot(
      pin: StatePin,
      eligible: Vector[SignedTransaction],
      rebuilding: Boolean,
      closed: Boolean,
      size: Int,
      byteSize: Int
  )

  private def guarded[F[_]: Async, A](
      owner: AdmissionState[F],
      pool: Ref[F, AdaPool.State[StatePin]]
  )(read: AdaPool.State[StatePin] => F[A], attempts: Int = 3): F[A] =
    val F = Async[F]
    owner.current.flatMap(v => owner.withCurrent(v.pin)(pool.get.flatMap(read))).flatMap {
      case Right(value)            => F.pure(value)
      case Left(_) if attempts > 0 => guarded(owner, pool)(read, attempts - 1)
      case Left(_) =>
        F.raiseError(new AdmissionState.Unavailable(AdmissionState.UnavailableReason.Initializing))
    }

  def resource[F[_]: Async](
      owner: AdmissionState[F],
      limits: AdaPool.Limits = AdaPool.Limits(),
      evidence: Option[PlutusEvaluationEvidence.Observer[F]] = None
  ): Resource[F, AdaSubmissionService[F]] =
    for
      initial <- Resource.eval(owner.current)
      profile <- Resource.eval(
        Async[F].fromOption(
          AdmissionProfile.fromId(initial.pin.profileId),
          new IllegalArgumentException("unsupported owner profile")
        )
      )
      pool <- Resource.eval(
        Ref.of[F, AdaPool.State[StatePin]](AdaPool.empty(initial.pin, limits, profile))
      )
      budget <- Resource.eval(AdaIngressBudget.create[F]())
      validations <- Resource.eval(Semaphore[F](2))
      pending <- Resource.eval(Ref.of[F, Option[(AdmissionView, AdaPool.Rebuild[StatePin])]](None))
      wake <- Resource.eval(Queue.bounded[F, Unit](1))
      relay <- AdaRelaySource.resource[F](
        new AdaRelaySource.Selection[F]:
          def withEligible[A](take: Vector[SignedTransaction] => F[A]): F[A] =
            guarded(owner, pool)(s => Async[F].monotonic.flatMap(t => take(s.eligible(t.toNanos))))
      )
      service <- Resource.make(
        Async[F].pure(
          new AdaSubmissionService(
            owner,
            profile,
            pool,
            budget,
            validations,
            pending,
            wake,
            relay,
            evidence
          )
        )
      )(_.stop)
      _ <- Resource
        .make(Async[F].start(service.worker(true).handleErrorWith(_ => service.stop)))(_.cancel)
      _ <- Resource
        .make(Async[F].start(service.expiry.handleErrorWith(_ => service.stop)))(_.cancel)
    yield service
