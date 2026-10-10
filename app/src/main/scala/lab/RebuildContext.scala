// SPDX-License-Identifier: Apache-2.0
package lab

import lab.ledger.AdaPool
import lab.submission.{AdmissionView, StatePin}

/** A checked pairing, not publication authority. The owner fence and pool token still decide
  * whether revalidated work may commit. Retains the original work identity for wake-up handling.
  */
private[lab] final class RebuildContext private[RebuildContext] (
    val view: AdmissionView,
    val work: AdaPool.Rebuild[StatePin]
):
  def sameWork(other: RebuildContext): Boolean = work eq other.work

private[lab] object RebuildContext:
  enum Mismatch:
    case MissingInput, PinMismatch, ProfileMismatch

  final class Invalid(val cause: Mismatch)
      extends IllegalStateException(cause match
        case Mismatch.MissingInput    => "rebuild view and work required"
        case Mismatch.PinMismatch     => "rebuild work differs from complete view pin"
        case Mismatch.ProfileMismatch => "rebuild profile differs from view profile")

  def checked(
      view: AdmissionView,
      work: AdaPool.Rebuild[StatePin]
  ): Either[Mismatch, RebuildContext] =
    if view == null || work == null then Left(Mismatch.MissingInput)
    else if view.pin != work.pin then Left(Mismatch.PinMismatch)
    else if work.profile.id != view.pin.profileId then Left(Mismatch.ProfileMismatch)
    else Right(new RebuildContext(view, work))
