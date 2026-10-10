// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.Sync
import cats.syntax.all.*
import lab.ledger.ScopedAdmission
import lab.submission.{AdmissionProfile, AdmissionView, StatePin}

/** Checked preparation is immutable data, not publication authority. The caller retains the
  * validation permit and supplies the owner fence when committing. No owner read, retry, mask,
  * resource allocation or exception translation occurs here.
  */
private[lab] object AdmissionPreparation:
  enum Failure:
    case Rejected(error: ScopedAdmission.Failure)
    case BindingMismatch

  final class Prepared private[AdmissionPreparation] (
      val view: AdmissionView,
      val candidate: ScopedAdmission.Candidate[StatePin]
  )

  def checked(
      profile: AdmissionProfile,
      view: AdmissionView,
      candidate: ScopedAdmission.Candidate[StatePin]
  ): Either[Failure, Prepared] =
    if candidate.profile != profile || view.pin.profileId != profile.id ||
      candidate.ledgerStateId != view.pin.ledgerStateId ||
      candidate.environmentId != view.pin.environmentId ||
      candidate.validationSlot != view.pin.validationSlot || candidate.pin != view.pin
    then Left(Failure.BindingMismatch)
    else Right(new Prepared(view, candidate))

  /** Suspend the existing pure validator exactly once. Expected rejection stays in Either;
    * unexpected exceptions and cancellation stay in the base effect. The caller's cede remains
    * outside this Sync-only boundary, inside its existing validation permit.
    */
  def evaluate[F[_]: Sync](profile: AdmissionProfile, view: AdmissionView)(
      validation: => Either[ScopedAdmission.Failure, ScopedAdmission.Candidate[StatePin]]
  ): F[Either[Failure, Prepared]] =
    Sync[F]
      .delay(validation)
      .map(_.leftMap(Failure.Rejected.apply).flatMap(checked(profile, view, _)))
