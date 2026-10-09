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

/** The mutation owner implements this interface. commit must be a bounded in-memory operation, must
  * not re-enter the owner, and must not perform I/O. The entire callback and pin comparison share
  * the chain mutation gate and a short cancellation mask.
  */
trait AdmissionState[F[_]]:
  def current: F[AdmissionView]
  def withCurrent[A](expected: StatePin)(commit: F[A]): F[Either[StatePin, A]]

object AdmissionState:
  enum UnavailableReason:
    case Initializing, Closed, Poisoned, GenerationExhausted
  final class Unavailable(val reason: UnavailableReason)
      extends RuntimeException("submission state unavailable: " + reason.toString)

/** changed runs under the owner gate, after state publication and before admission may resume. It
  * must atomically invalidate eligibility/update reservations and only enqueue bounded revalidation
  * work; it must not wait for validation or call AdmissionState again.
  */
trait AdmissionStateObserver[F[_]]:
  def changed(change: AdmissionStateChange): F[Unit]
  def closed: F[Unit]
