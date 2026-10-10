// SPDX-License-Identifier: Apache-2.0
package lab

import lab.ledger.AdaPool
import lab.submission.StatePin
import AdaSubmissionService.Result
import EvaluationEvent.AdmissionOutcome

/** One classification binds the evidence event to the service result. */
private[lab] final class AdmissionDecision private (
    val event: EvaluationEvent.Admission,
    val result: Result
)

private[lab] object AdmissionDecision:
  def fromPool(outcome: AdaPool.Outcome[StatePin]): AdmissionDecision = outcome match
    case AdaPool.Outcome.Accepted(receipt) =>
      new AdmissionDecision(
        EvaluationEvent.Admission(AdmissionOutcome.Accepted),
        Result.Accepted(receipt)
      )
    case AdaPool.Outcome.AlreadyPresent(receipt) =>
      new AdmissionDecision(
        EvaluationEvent.Admission(AdmissionOutcome.AlreadyPresent),
        Result.AlreadyPresent(receipt)
      )
    case AdaPool.Outcome.Rejected(reason) =>
      new AdmissionDecision(
        EvaluationEvent.Admission(AdmissionOutcome.PoolRejected),
        Result.PoolRejected(reason)
      )
    case AdaPool.Outcome.Retry(pin) =>
      new AdmissionDecision(EvaluationEvent.Admission(AdmissionOutcome.Retry), Result.Retry(pin))
    case AdaPool.Outcome.Unavailable =>
      new AdmissionDecision(
        EvaluationEvent.Admission(AdmissionOutcome.Unavailable),
        Result.Unavailable
      )
