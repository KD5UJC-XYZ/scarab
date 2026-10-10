// SPDX-License-Identifier: Apache-2.0
package lab

/** Only valid evaluation phase/outcome combinations are representable. */
private[lab] enum EvaluationEvent:
  case Admission(outcome: EvaluationEvent.AdmissionOutcome)
  case Revalidation(outcome: EvaluationEvent.RevalidationOutcome)

private[lab] object EvaluationEvent:
  enum AdmissionOutcome:
    case Accepted, AlreadyPresent, PoolRejected, Retry, Unavailable
  enum RevalidationOutcome:
    case Retained, Discarded
  def newlyAdmitted(event: EvaluationEvent): Boolean = event match
    case EvaluationEvent.Admission(AdmissionOutcome.Accepted)           => true
    case EvaluationEvent.Admission(_) | EvaluationEvent.Revalidation(_) => false
  def wire(event: EvaluationEvent): (String, String) = event match
    case EvaluationEvent.Admission(outcome) =>
      val code = outcome match
        case AdmissionOutcome.Accepted       => "accepted"
        case AdmissionOutcome.AlreadyPresent => "already-present"
        case AdmissionOutcome.PoolRejected   => "pool-rejected"
        case AdmissionOutcome.Retry          => "retry"
        case AdmissionOutcome.Unavailable    => "unavailable"
      ("admission", code)
    case EvaluationEvent.Revalidation(outcome) =>
      val code = outcome match
        case RevalidationOutcome.Retained  => "retained"
        case RevalidationOutcome.Discarded => "discarded"
      ("revalidation", code)
