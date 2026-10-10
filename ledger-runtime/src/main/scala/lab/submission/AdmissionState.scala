// SPDX-License-Identifier: Apache-2.0
package lab.submission

import lab.cbor.Bytes
import lab.ledger.ClusterTransition

/** An immutable ledger view bound to an owner pin. Shape/identity checks do not authenticate its
  * source.
  */
final class AdmissionView private (val pin: StatePin, val ledger: ClusterTransition.State)
object AdmissionView:
  def checked(pin: StatePin, ledger: ClusterTransition.State): Either[String, AdmissionView] =
    if pin == null || ledger == null then Left("pin and ledger view required")
    else if pin.ledgerStateId != ledger.id || pin.environmentId != ledger.environment.id ||
      pin.validationSlot != ledger.slot
    then Left("ledger view differs from complete state pin")
    else Right(new AdmissionView(pin, ledger))

/** Called only after coherent block publication. Memos reconstruct an envelope; these fields
  * deliberately report original body/witness spans instead of full submitted-envelope equality.
  */
final case class IncludedTransaction(
    transactionId: Bytes,
    originalBody: Option[Bytes],
    originalWitnesses: Option[Bytes]
)

enum StateChangeKind:
  case Published, RolledBack, AnchorMoved, Reset

final case class AdmissionStateChange(
    view: AdmissionView,
    kind: StateChangeKind,
    included: Vector[IncludedTransaction]
)

/** The mutation owner supplies the admission linearization gate. The package-trusted commit and
  * complete pin comparison run serialized under a cancellation mask. A commit must not re-enter
  * this owner or perform validation. It may install bounded admission evidence with serialized
  * filesystem I/O before releasing eligibility. Such I/O has cooperative liveness: cancellation and
  * shutdown wait for the masked commit; this interface promises no hard filesystem deadline.
  * Callers must not detach writes or release eligibility before persistence/fail-closed handling.
  * This callback is internal authority, not a general extension point.
  */
trait AdmissionState[F[_]]:
  def current: F[AdmissionView]
  private[lab] def withCurrent[A](expected: StatePin)(commit: F[A]): F[Either[StatePin, A]]

object AdmissionState:
  enum UnavailableReason:
    case Initializing, Closed, Poisoned, GenerationExhausted
  final class Unavailable(val reason: UnavailableReason)
      extends RuntimeException("submission state unavailable: " + reason.toString)

/** changed runs under the owner gate, after state publication and before admission may resume. It
  * must atomically invalidate eligibility/update reservations and only enqueue bounded revalidation
  * work; it must not wait for validation, perform external I/O, or re-enter the owner through
  * AdmissionState. Unlike the trusted admission commit, publication observers remain bounded
  * in-memory notifications. closed follows the same no-reentry/no-external-I/O restriction.
  */
trait AdmissionStateObserver[F[_]]:
  def changed(change: AdmissionStateChange): F[Unit]
  def closed: F[Unit]
