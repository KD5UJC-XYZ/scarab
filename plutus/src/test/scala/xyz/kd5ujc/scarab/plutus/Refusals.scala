// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import xyz.kd5ujc.scarab.plutus.ReferenceDiagnostic as Native

/** Test views of a typed evaluation failure by the reference's stage, and typed native refusals for
  * synthetic evaluators. They read only the typed diagnostic, never provider text.
  */
private[scarab] object Refusals:
  /** `CodecError`, reconstructed or awaiting its decoder reason. */
  object Admission:
    def unapply(failure: EvalFailure): Boolean = failure match
      case EvalFailure.Refused(Native.EvaluationError.Codec(_), _)   => true
      case EvalFailure.Unresolved(DiagnosticGap.DecoderReason(_), _) => true
      case _                                                         => false

  /** The decoder's own cause of an admission refusal whose native reason is not derived. */
  object Decoder:
    def unapply(failure: EvalFailure): Option[ScriptProblem] = failure match
      case EvalFailure.Unresolved(DiagnosticGap.DecoderReason(problem), _) => Some(problem)
      case _                                                               => None

  /** `DeBruijnError (FreeIndex i)`. */
  object Scope:
    def unapply(failure: EvalFailure): Option[BigInt] = failure match
      case EvalFailure.Refused(
            Native.EvaluationError.DeBruijn(Native.FreeVariableError.FreeIndex(index)),
            _
          ) =>
        Some(index)
      case _ => None

  /** `CekOutOfExError` and its remaining budget. */
  object Budget:
    def unapply(failure: EvalFailure): Option[Native.Budget] = failure match
      case EvalFailure.Refused(
            Native.EvaluationError.Cek(
              Native.ErrorWithCause(
                Native.CekError.Operational(Native.CekUserError.OutOfBudget(remaining)),
                _
              )
            ),
            _
          ) =>
        Some(remaining)
      case _ => None

  /** Any other proven refusal: the machine refused the evaluation. */
  object Evaluation:
    def unapply(failure: EvalFailure): Boolean = failure match
      case Admission() | Scope(_) | Budget(_)                       => false
      case EvalFailure.Refused(_, _) | EvalFailure.Unresolved(_, _) => true
      case EvalFailure.Unsupported(_)                               => false

  /** The error the machine reports for `error` (`CekInternal.hs:874-875`). */
  val explicitError: Native.EvaluationError = Native.EvaluationError.Cek(
    Native.ErrorWithCause(
      Native.CekError.Operational(Native.CekUserError.EvaluationFailure),
      Some(Native.Term.Error)
    )
  )

  /** A refusal of `error`, signalled by `provider`. */
  def scripted(provider: Throwable): EvalFailure =
    EvalFailure.Refused(explicitError, Some(provider))

  /** A budget refusal with `remaining` left (negative where overspent). */
  def outOfBudget(remaining: Native.Budget = Native.Budget(-1, 0)): EvalFailure =
    EvalFailure.Refused(
      Native.EvaluationError.Cek(
        Native.ErrorWithCause(
          Native.CekError.Operational(Native.CekUserError.OutOfBudget(remaining)),
          None
        )
      ),
      None
    )

  def scope(index: BigInt): EvalFailure = NativeDiagnostics.scope(index)
