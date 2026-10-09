// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.header.PraosCertificateState.Point
import lab.ledger.{AdaAdmission, AdaPool, ClusterTransition, NativeSpending}
import lab.submission.{SignedTransaction, StatePin}
import ReferenceJson.{Json as J, field, string, uint}

class AdaHttpHandlerSuite extends munit.FunSuite:
  private def b(n: Int) = Bytes(Vector.fill(32)(n.toByte))
  private val pin =
    StatePin.checked(b(1), 2, Point(b(3), 4, 5), b(6), b(7), b(8), 4, StatePin.Profile).toOption.get
  private val receipt = AdaPool.Receipt(b(9), b(10), pin)
  private def json(response: AdaHttp.Response) =
    ReferenceJson.parse(Bytes.fromArray(response.json.getBytes("UTF-8")))
  private def fields(value: J): Set[String] = value match
    case J.Obj(xs) => xs.keySet
    case _         => fail("object required")
  private def checkPin(value: J): Unit =
    assertEquals(
      fields(value),
      Set(
        "ownerId",
        "generation",
        "point",
        "coherentStateId",
        "ledgerStateId",
        "environmentId",
        "validationSlot",
        "profileId"
      )
    )
    assertEquals(string(field(value, "ownerId")), pin.ownerId.hex)
    assertEquals(uint(field(value, "generation")), pin.generation)
    assertEquals(string(field(value, "coherentStateId")), pin.coherentStateId.hex)
    assertEquals(string(field(value, "ledgerStateId")), pin.ledgerStateId.hex)
    assertEquals(string(field(value, "environmentId")), pin.environmentId.hex)
    assertEquals(uint(field(value, "validationSlot")), pin.validationSlot)
    assertEquals(string(field(value, "profileId")), pin.profileId)
    val p = field(value, "point")
    assertEquals(fields(p), Set("slot", "blockNo", "hash"))
    assertEquals(uint(field(p, "slot")), pin.point.slot)
    assertEquals(uint(field(p, "blockNo")), pin.point.blockNo)
    assertEquals(string(field(p, "hash")), pin.point.hash.hex)

  test("accepted and duplicate receipts are volatile pending with every state pin field") {
    Vector(
      AdaSubmissionService.Result.Accepted(receipt),
      AdaSubmissionService.Result.AlreadyPresent(receipt)
    ).foreach { result =>
      val response = AdaHttpHandler.resultResponse(result)
      val value = json(response)
      assertEquals(string(field(value, "status")), "pending")
      assertEquals(field(value, "volatile"), J.Lit("true"))
      assertEquals(field(value, "fullLedgerValidated"), J.Lit("false"))
      val r = field(value, "receipt")
      assertEquals(string(field(r, "transactionId")), receipt.transactionId.hex)
      assertEquals(string(field(r, "envelopeSHA256")), receipt.envelopeSHA256.hex)
      checkPin(field(r, "pin"))
      assert(!response.json.contains("original"))
    }
  }

  test("failure classes have distinct machine codes and omit diagnostic or key payloads") {
    import AdaSubmissionService.Result
    val secret = "must-not-be-exposed"
    val cases = Vector(
      (Result.Rejected(AdaAdmission.Failure.Unsupported(secret)), 422, "Unsupported"),
      (
        Result.Rejected(
          AdaAdmission.Failure.Identity(SignedTransaction.Error.MalformedShape(secret))
        ),
        400,
        "MalformedShape"
      ),
      (
        Result.Rejected(
          AdaAdmission.Failure.Identity(SignedTransaction.Error.DecodeRejected(secret))
        ),
        400,
        "DecodeRejected"
      ),
      (
        Result.Rejected(AdaAdmission.Failure.Identity(SignedTransaction.Error.InputLimit)),
        413,
        "InputLimit"
      ),
      (
        Result.Rejected(
          AdaAdmission.Failure.Ledger(
            ClusterTransition.Failure.Rejected(NativeSpending.Error.MissingKeys(Set(b(91))))
          )
        ),
        422,
        "Rejected"
      ),
      (Result.PoolRejected(AdaPool.Rejection.EnvelopeConflict), 409, "EnvelopeConflict"),
      (Result.PoolRejected(AdaPool.Rejection.InputsReserved(Set.empty)), 409, "InputsReserved"),
      (Result.PoolRejected(AdaPool.Rejection.Capacity), 429, "Capacity"),
      (Result.Retry(pin), 409, "StaleState"),
      (Result.Unavailable, 503, "Unavailable"),
      (
        Result.Rejected(
          AdaAdmission.Failure.Ledger(ClusterTransition.Failure.InternalFailure(secret))
        ),
        503,
        "InternalFailure"
      )
    )
    cases.foreach { (result, status, code) =>
      val response = AdaHttpHandler.resultResponse(result)
      assertEquals(response.status, status)
      assertEquals(string(field(json(response), "code")), code)
      assert(!response.json.contains(secret))
      assert(!response.json.contains(b(91).hex))
    }
    checkPin(field(json(AdaHttpHandler.resultResponse(Result.Retry(pin))), "currentPin"))
  }

  test(
    "transaction states separate eligibility inclusion drop and unknown without original bytes"
  ) {
    val states: Vector[(Option[AdaPool.Status[StatePin]], String)] = Vector(
      Some(AdaPool.Status.Pending(receipt, true)) -> "pending",
      Some(AdaPool.Status.Pending(receipt, false)) -> "revalidating",
      Some(AdaPool.Status.Included(pin)) -> "included",
      Some(AdaPool.Status.Dropped(AdaPool.Drop.Expired)) -> "dropped",
      None -> "unknown"
    )
    states.foreach { (state, expected) =>
      val response = AdaHttpHandler.statusResponse(b(9), state)
      assertEquals(string(field(json(response), "status")), expected)
      assertEquals(response.status, if state.isEmpty then 404 else 200)
      assertEquals(field(json(response), "fullLedgerValidated"), J.Lit("false"))
      assert(!response.json.contains("original"))
    }
    val included = json(AdaHttpHandler.statusResponse(b(9), Some(AdaPool.Status.Included(pin))))
    checkPin(field(included, "pin"))
    assertEquals(field(included, "submittedEnvelopeByteEqualityVerified"), J.Lit("false"))
  }

  test("state exposes only aggregate counts flags and the complete pin") {
    val transaction =
      SignedTransaction.checked(Bytes.fromHex("84a0a0f5f6").toOption.get).toOption.get
    val response = AdaHttpHandler.snapshotResponse(
      AdaSubmissionService.Snapshot(pin, Vector(transaction), false, false, 2, 10)
    )
    val value = json(response)
    checkPin(field(value, "pin"))
    assertEquals(uint(field(value, "transactions")), BigInt(2))
    assertEquals(uint(field(value, "bytes")), BigInt(10))
    assertEquals(uint(field(value, "eligibleTransactions")), BigInt(1))
    assertEquals(uint(field(value, "eligibleBytes")), BigInt(transaction.byteSize))
    assert(!response.json.contains(transaction.original.hex))
    assert(!response.json.contains(transaction.transactionId.hex))
  }

  test("JSON quoting escapes quotes controls backslashes and non-ASCII code units") {
    val input = "quote\" slash\\ line\n tab\t " + 0.toChar + " é"
    val encoded = AdaHttpHandler.quote(input)
    assert(encoded.forall(c => c >= ' ' && c <= '~'))
    val decoded = ReferenceJson.parse(Bytes.fromArray(encoded.getBytes("UTF-8")))
    assertEquals(string(decoded), input)
  }

  test("handler keeps shared service request permits until the transport releases its resource") {
    SubmissionOwner
      .resource[IO](EphemeralStreamingFixture.runtime)
      .flatMap { owner =>
        AdaSubmissionService
          .resource[IO](owner)
          .evalTap(owner.attach)
          .map(service => (owner, service))
      }
      .use { (owner, service) =>
        val handler = AdaHttpHandler(service)
        for
          _ <- List.fill(8)(handler.request).sequence.use { held =>
            for
              _ <- IO(assert(held.forall(_.nonEmpty)))
              _ <- handler.request.use(extra => IO(assertEquals(extra, None)))
              state <- held.head.get.state
              _ = assertEquals(state.status, 200)
              _ <- handler.request.use(extra => IO(assertEquals(extra, None)))
            yield ()
          }
          _ <- handler.request.use { permitted =>
            for
              _ <- IO(assert(permitted.nonEmpty))
              response <- permitted.get.submit(Bytes.empty)
              _ = assertEquals(response.status, 400)
            yield ()
          }
          _ <- owner.close
          _ <- handler.request.use { permitted =>
            permitted.get.state.map(response => assertEquals(response.status, 503))
          }
        yield ()
      }
      .unsafeToFuture()
  }
