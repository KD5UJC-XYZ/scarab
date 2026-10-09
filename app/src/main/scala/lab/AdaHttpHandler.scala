// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.Async
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.ledger.{AdaAdmission, AdaPool, ClusterTransition}
import lab.submission.{AdmissionState, SignedTransaction, StatePin}

/** HTTP representation of scoped volatile admission. The transport owns this request resource from
  * before input reads through response writes. No original transaction or key material is exposed
  * by these representations; diagnostic exception messages are deliberately omitted.
  */
private[lab] object AdaHttpHandler:
  def apply[F[_]: Async](service: AdaSubmissionService[F]): AdaHttp.Handler[F] =
    new AdaHttp.Handler[F]:
      def request = service.request.map(_.map { permit =>
        new AdaHttp.RequestHandler[F]:
          private def safe(action: F[AdaHttp.Response]): F[AdaHttp.Response] =
            action.handleError {
              case _: AdmissionState.Unavailable => unavailable
              case _                             => failure(503, "InternalFailure", "unavailable")
            }
          def submit(original: Bytes) = safe(permit.submit(original).map(resultResponse))
          def transaction(id: Bytes) = safe(service.status(id).map(statusResponse(id, _)))
          def state = safe(service.snapshot.map(snapshotResponse))
      })

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
          "profileId" -> quote(StatePin.Profile),
          "volatile" -> "true",
          "fullLedgerValidated" -> "false"
        ) ++ fields)*
      )
    )
  private def failure(http: Int, code: String, category: String): AdaHttp.Response =
    response(http, code, "status" -> quote(category))
  private def unavailable = failure(503, "Unavailable", "unavailable")

  private def validationFailure(error: AdaAdmission.Failure): AdaHttp.Response = error match
    case AdaAdmission.Failure.Identity(SignedTransaction.Error.InputLimit) =>
      failure(413, "InputLimit", "rejected")
    case AdaAdmission.Failure.Identity(SignedTransaction.Error.DecodeRejected(_)) =>
      failure(400, "DecodeRejected", "rejected")
    case AdaAdmission.Failure.Identity(SignedTransaction.Error.MalformedShape(_)) =>
      failure(400, "MalformedShape", "rejected")
    case AdaAdmission.Failure.Unsupported(_) => failure(422, "Unsupported", "unsupported")
    case AdaAdmission.Failure.Ledger(error) =>
      error match
        case ClusterTransition.Failure.Unsupported(_) => failure(422, "Unsupported", "unsupported")
        case ClusterTransition.Failure.DecodeRejected(_) =>
          failure(400, "DecodeRejected", "rejected")
        case ClusterTransition.Failure.Malformed(_) => failure(400, "MalformedShape", "rejected")
        case ClusterTransition.Failure.ResourceLimit(_) => failure(413, "LocalLimit", "rejected")
        case ClusterTransition.Failure.Rejected(_)      => failure(422, "Rejected", "rejected")
        case ClusterTransition.Failure.StaleState(_)    => failure(409, "StaleState", "stale")
        case ClusterTransition.Failure.InternalFailure(_) =>
          failure(503, "InternalFailure", "unavailable")

  private[lab] def resultResponse(result: AdaSubmissionService.Result): AdaHttp.Response =
    import AdaSubmissionService.Result
    result match
      case Result.Accepted(value) =>
        response(202, "Accepted", "status" -> quote("pending"), "receipt" -> receipt(value))
      case Result.AlreadyPresent(value) =>
        response(200, "AlreadyPresent", "status" -> quote("pending"), "receipt" -> receipt(value))
      case Result.Rejected(error) => validationFailure(error)
      case Result.PoolRejected(AdaPool.Rejection.EnvelopeConflict) =>
        failure(409, "EnvelopeConflict", "conflict")
      case Result.PoolRejected(AdaPool.Rejection.InputsReserved(_)) =>
        failure(409, "InputsReserved", "conflict")
      case Result.PoolRejected(AdaPool.Rejection.Capacity) => failure(429, "Capacity", "capacity")
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
