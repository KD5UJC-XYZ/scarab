// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.Async
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.ledger.{ScopedAdmission, AdaPool, ClusterTransition}
import lab.submission.{AdmissionProfile, AdmissionState, SignedTransaction, StatePin}

/** HTTP representation of scoped volatile admission. The transport owns this request resource from
  * before input reads through response writes. No original transaction or key material is exposed
  * by these representations; diagnostic exception messages are deliberately omitted.
  */
private[lab] object AdaHttpHandler:
  def apply[F[_]: Async](service: AdaSubmissionService[F]): AdaHttp.Handler[F] =
    val representation = new Representation(service.profile)
    new AdaHttp.Handler[F]:
      def request = service.request.map(_.map { permit =>
        new AdaHttp.RequestHandler[F]:
          private def safe(action: F[AdaHttp.Response]): F[AdaHttp.Response] =
            action.handleError(error => representation.failure(FailureResponse.unexpected(error)))
          def submit(original: Bytes) =
            safe(permit.submit(original).map(representation.resultResponse))
          def transaction(id: Bytes) =
            safe(service.status(id).map(representation.statusResponse(id, _)))
          def state = safe(service.snapshot.map(representation.snapshotResponse))
      })

  /** Closed response policy: callers cannot choose inconsistent HTTP/code/category triples. */
  private[lab] enum FailureResponse(val http: Int, val code: String, val category: String):
    case BudgetExhausted extends FailureResponse(422, "BudgetExhausted", "rejected")
    case Capacity extends FailureResponse(429, "Capacity", "capacity")
    case DecodeRejected extends FailureResponse(400, "DecodeRejected", "rejected")
    case EnvelopeConflict extends FailureResponse(409, "EnvelopeConflict", "conflict")
    case InputLimit extends FailureResponse(413, "InputLimit", "rejected")
    case InputsReserved extends FailureResponse(409, "InputsReserved", "conflict")
    case InternalFailure extends FailureResponse(503, "InternalFailure", "unavailable")
    case LocalLimit extends FailureResponse(413, "LocalLimit", "rejected")
    case MalformedShape extends FailureResponse(400, "MalformedShape", "rejected")
    case NonUnitReturn extends FailureResponse(422, "NonUnitReturn", "rejected")
    case Rejected extends FailureResponse(422, "Rejected", "rejected")
    case ScriptFailure extends FailureResponse(422, "ScriptFailure", "rejected")
    case StaleState extends FailureResponse(409, "StaleState", "stale")
    case Unavailable extends FailureResponse(503, "Unavailable", "unavailable")
    case Unsupported extends FailureResponse(422, "Unsupported", "unsupported")

  private[lab] object FailureResponse:
    def unexpected(error: Throwable): FailureResponse = error match
      case _: AdmissionState.Unavailable => FailureResponse.Unavailable
      case _                             => FailureResponse.InternalFailure

  /** ASCII JSON encoding also escapes surrogate code units, so arbitrary JVM strings cannot inject
    * fields, controls, or malformed UTF-8 into the response.
    */
  private[lab] def quote(value: String): String =
    val out = new java.lang.StringBuilder("\"")
    value.foreach {
      case '"'                     => out.append("\\\"")
      case '\\'                    => out.append("\\\\")
      case c if c < ' ' || c > '~' => out.append(f"\\u${c.toInt}%04x")
      case c                       => out.append(c)
    }
    out.append('"').toString
  private[lab] final class Representation(val profile: AdmissionProfile):
    require(profile != null)
    private def obj(fields: (String, String)*): String =
      fields.map((key, value) => quote(key) + ":" + value).mkString("{", ",", "}")
    private def pin(value: StatePin): String = obj(
      "ownerId" -> quote(value.ownerId.hex),
      "generation" -> value.generation.toString,
      "point" -> obj(
        "slot" -> value.point.slot.toString,
        "blockNo" -> value.point.blockNo.toString,
        "hash" -> quote(value.point.hash.hex)
      ),
      "coherentStateId" -> quote(value.coherentStateId.hex),
      "ledgerStateId" -> quote(value.ledgerStateId.hex),
      "environmentId" -> quote(value.environmentId.hex),
      "validationSlot" -> value.validationSlot.toString,
      "profileId" -> quote(value.profileId)
    )
    private def receipt(value: AdaPool.Receipt[StatePin]): String = obj(
      "transactionId" -> quote(value.transactionId.hex),
      "envelopeSHA256" -> quote(value.envelopeSHA256.hex),
      "pin" -> pin(value.pin),
      "volatile" -> "true",
      "fullLedgerValidated" -> "false"
    )
    private def response(http: Int, code: String, fields: (String, String)*): AdaHttp.Response =
      AdaHttp.Response(
        http,
        obj(
          (Vector(
            "code" -> quote(code),
            "profileId" -> quote(profile.id),
            "volatile" -> "true",
            "fullLedgerValidated" -> "false"
          ) ++ fields)*
        )
      )
    private[lab] def failure(value: FailureResponse): AdaHttp.Response =
      response(value.http, value.code, "status" -> quote(value.category))
    private[lab] def unavailable = failure(FailureResponse.Unavailable)

    private def validationFailure(error: ScopedAdmission.Failure): FailureResponse = error match
      case ScopedAdmission.Failure.Identity(SignedTransaction.Error.InputLimit) =>
        FailureResponse.InputLimit
      case ScopedAdmission.Failure.Identity(SignedTransaction.Error.DecodeRejected(_)) =>
        FailureResponse.DecodeRejected
      case ScopedAdmission.Failure.Identity(SignedTransaction.Error.MalformedShape(_)) =>
        FailureResponse.MalformedShape
      case ScopedAdmission.Failure.Unsupported(_) => FailureResponse.Unsupported
      case ScopedAdmission.Failure.Plutus(error) =>
        import lab.ledger.PlutusAdmission.Failure as P
        import lab.plutus.PlutusExecution.Failure as E
        error match
          case P.Identity(value) => validationFailure(ScopedAdmission.Failure.Identity(value))
          case P.PhaseOne(value) =>
            import lab.ledger.PlutusSpending.Failure as S
            value match
              case S.Malformed(_)       => FailureResponse.MalformedShape
              case S.Unsupported(_)     => FailureResponse.Unsupported
              case S.Rejected(_)        => FailureResponse.Rejected
              case S.InternalFailure(_) => FailureResponse.InternalFailure
          case P.BindingMismatch | P.InternalFailure(_) =>
            FailureResponse.InternalFailure
          case P.Execution(value) =>
            value match
              case E.MalformedInput(_)  => FailureResponse.DecodeRejected
              case E.Unsupported(_)     => FailureResponse.Unsupported
              case E.ScriptFailure(_)   => FailureResponse.ScriptFailure
              case E.NonUnitReturn      => FailureResponse.NonUnitReturn
              case E.BudgetExhausted    => FailureResponse.BudgetExhausted
              case E.InternalFailure(_) => FailureResponse.InternalFailure
      case ScopedAdmission.Failure.Ledger(error) =>
        error match
          case ClusterTransition.Failure.Unsupported(_) =>
            FailureResponse.Unsupported
          case ClusterTransition.Failure.DecodeRejected(_) =>
            FailureResponse.DecodeRejected
          case ClusterTransition.Failure.Malformed(_)     => FailureResponse.MalformedShape
          case ClusterTransition.Failure.ResourceLimit(_) => FailureResponse.LocalLimit
          case ClusterTransition.Failure.Rejected(_)      => FailureResponse.Rejected
          case ClusterTransition.Failure.StaleState(_)    => FailureResponse.StaleState
          case ClusterTransition.Failure.InternalFailure(_) =>
            FailureResponse.InternalFailure

    private[lab] def resultResponse(result: AdaSubmissionService.Result): AdaHttp.Response =
      import AdaSubmissionService.Result
      result match
        case Result.Accepted(value) =>
          response(202, "Accepted", "status" -> quote("pending"), "receipt" -> receipt(value))
        case Result.AlreadyPresent(value) =>
          response(200, "AlreadyPresent", "status" -> quote("pending"), "receipt" -> receipt(value))
        case Result.Rejected(error) => failure(validationFailure(error))
        case Result.PoolRejected(AdaPool.Rejection.EnvelopeConflict) =>
          failure(FailureResponse.EnvelopeConflict)
        case Result.PoolRejected(AdaPool.Rejection.InputsReserved(_)) =>
          failure(FailureResponse.InputsReserved)
        case Result.PoolRejected(AdaPool.Rejection.Capacity) => failure(FailureResponse.Capacity)
        case Result.Retry(current) =>
          response(409, "StaleState", "status" -> quote("stale"), "currentPin" -> pin(current))
        case Result.Unavailable => unavailable

    private[lab] def snapshotResponse(value: AdaSubmissionService.Snapshot): AdaHttp.Response =
      response(
        200,
        "State",
        "pin" -> pin(value.pin),
        "transactions" -> value.size.toString,
        "bytes" -> value.byteSize.toString,
        "eligibleTransactions" -> value.eligible.size.toString,
        "eligibleBytes" -> value.eligible.map(_.byteSize.toLong).sum.toString,
        "revalidating" -> value.rebuilding.toString,
        "closed" -> value.closed.toString
      )

    private def dropCode(reason: AdaPool.Drop): String = reason match
      case AdaPool.Drop.Expired       => "Expired"
      case AdaPool.Drop.Removed       => "Removed"
      case AdaPool.Drop.Shutdown      => "Shutdown"
      case AdaPool.Drop.Conflict      => "Conflict"
      case AdaPool.Drop.Validation(_) => "Validation"

    private[lab] def statusResponse(
        id: Bytes,
        value: Option[AdaPool.Status[StatePin]]
    ): AdaHttp.Response =
      value match
        case None =>
          response(404, "Unknown", "transactionId" -> quote(id.hex), "status" -> quote("unknown"))
        case Some(AdaPool.Status.Pending(value, eligible)) =>
          response(
            200,
            "Pending",
            "status" -> quote(if eligible then "pending" else "revalidating"),
            "eligible" -> eligible.toString,
            "receipt" -> receipt(value)
          )
        case Some(AdaPool.Status.Included(value)) =>
          response(
            200,
            "Included",
            "transactionId" -> quote(id.hex),
            "status" -> quote("included"),
            "pin" -> pin(value),
            "submittedEnvelopeByteEqualityVerified" -> "false"
          )
        case Some(AdaPool.Status.Dropped(reason)) =>
          response(
            200,
            "Dropped",
            "transactionId" -> quote(id.hex),
            "status" -> quote("dropped"),
            "reason" -> quote(dropCode(reason))
          )

  // Compatibility helpers for the existing default-profile schema tests.
  private[lab] def resultResponse(value: AdaSubmissionService.Result): AdaHttp.Response =
    new Representation(AdmissionProfile.AdaVkey).resultResponse(value)
  private[lab] def snapshotResponse(value: AdaSubmissionService.Snapshot): AdaHttp.Response =
    new Representation(AdmissionProfile.AdaVkey).snapshotResponse(value)
  private[lab] def statusResponse(
      id: Bytes,
      value: Option[AdaPool.Status[StatePin]]
  ): AdaHttp.Response =
    new Representation(AdmissionProfile.AdaVkey).statusResponse(id, value)
