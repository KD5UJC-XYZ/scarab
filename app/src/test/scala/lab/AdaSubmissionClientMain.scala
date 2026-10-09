// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO, IOApp, Ref, Resource}
import cats.syntax.all.*
import java.net.{URI, Proxy, ProxySelector, SocketAddress}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, StandardOpenOption as Open}
import java.time.Duration
import java.util.concurrent.{ArrayBlockingQueue, ThreadPoolExecutor, TimeUnit}
import lab.cbor.Bytes
import lab.submission.{SignedTransaction, StatePin}
import ReferenceJson.{Json as J, field, string, uint}
import scala.concurrent.duration.*

/** Test-only external client. The signed file crosses only the Scala HTTP API; this process has no
  * reference CLI or transaction-submission network access.
  */
object AdaSubmissionClientMain extends IOApp:
  private def record(fields: (String, J)*): J = J.Obj(fields.toMap)
  private def text(value: String): J = J.Str(value)
  private def flag(value: Boolean): J = J.Lit(value.toString)
  private def requireField(j: J, key: String, expected: J): Unit =
    require(field(j, key) == expected, "response field mismatch: " + key)
  private def objectKeys(j: J): Set[String] = j match
    case J.Obj(fields) => fields.keySet
    case _             => throw new IllegalArgumentException("object required")
  private def hash(j: J): Unit = require(string(j).matches("[0-9a-f]{64}"), "invalid hash")
  private def boundedUInt(j: J): Unit =
    require(uint(j) <= StatePin.MaxUInt64, "uint64 overflow")
  private def pin(j: J): Unit =
    require(
      objectKeys(j) == Set(
        "ownerId",
        "generation",
        "point",
        "coherentStateId",
        "ledgerStateId",
        "environmentId",
        "validationSlot",
        "profileId"
      ),
      "complete pin required"
    )
    Vector("ownerId", "coherentStateId", "ledgerStateId", "environmentId").foreach(k =>
      hash(field(j, k))
    )
    Vector("generation", "validationSlot").foreach(k => boundedUInt(field(j, k)))
    requireField(j, "profileId", text(StatePin.Profile))
    val point = field(j, "point")
    require(
      objectKeys(point) == Set("slot", "blockNo", "hash"),
      "complete inclusion point required"
    )
    hash(field(point, "hash"))
    boundedUInt(field(point, "slot"))
    boundedUInt(field(point, "blockNo"))
  private def scoped(j: J): Unit =
    requireField(j, "profileId", text(StatePin.Profile))
    requireField(j, "fullLedgerValidated", flag(false))
    requireField(j, "volatile", flag(true))
  private def receipt(j: J, transaction: SignedTransaction, pendingOnly: Boolean = true): Unit =
    scoped(j)
    if pendingOnly then requireField(j, "status", text("pending"))
    else
      require(
        Set("pending", "revalidating").contains(string(field(j, "status"))),
        "pending/revalidating status required"
      )
    val value = field(j, "receipt")
    requireField(value, "transactionId", text(transaction.transactionId.hex))
    requireField(value, "envelopeSHA256", text(transaction.envelopeSHA256.hex))
    requireField(value, "volatile", flag(true))
    requireField(value, "fullLedgerValidated", flag(false))
    pin(field(value, "pin"))

  /** Shared by the live client and an actual-handler schema regression. */
  private[lab] def validateIncluded(status: Int, json: J, transactionId: Bytes): Unit =
    require(status == 200, "included HTTP status")
    scoped(json)
    requireField(json, "code", text("Included"))
    requireField(json, "transactionId", text(transactionId.hex))
    requireField(json, "status", text("included"))
    requireField(json, "submittedEnvelopeByteEqualityVerified", flag(false))
    pin(field(json, "pin"))

  private def read(path: Path): IO[Bytes] = IO.blocking {
    val stream = Files.newInputStream(path)
    val bytes =
      try stream.readNBytes(SignedTransaction.MaxBytes + 1)
      finally stream.close()
    require(bytes.length <= SignedTransaction.MaxBytes, "signed file too large")
    Bytes.fromArray(bytes)
  }
  private def optionalText(value: J, key: String): String = value match
    case J.Obj(values) =>
      values.get(key) match
        case Some(J.Str(text)) => text.take(64)
        case _                 => "unknown"
    case _ => "unknown"
  private def observationCode(value: J): String = value match
    case J.Obj(values) =>
      values.get("response") match
        case Some(response) => optionalText(response, "code")
        case None           => optionalText(value, "responseCode")
    case _ => "unknown"
  private def summarizeObservation(value: J): J = record(
    "stage" -> text(optionalText(value, "stage")),
    "responseCode" -> text(observationCode(value)),
    "responseOmitted" -> flag(true),
    "observationSHA256" -> text(
      ClusterHeaderObservation.sha256(SyntheticRewardProjection.encode(value)).hex
    )
  )

  /** Retain startup evidence, the first acceptance/duplicate and latest statuses. Both count and
    * encoded byte limits apply even to unexpectedly large replies.
    */
  private[lab] def retainObservation(previous: Vector[J], next: J): Vector[J] =
    val bounded =
      if SyntheticRewardProjection.encode(next).size <= 2048 then next
      else summarizeObservation(next)
    val all = previous :+ bounded
    val milestones = Vector(
      all.find(row =>
        optionalText(row, "stage") == "admission" && observationCode(row) == "Accepted"
      ),
      all.find(row => optionalText(row, "stage") == "exactDuplicate")
    ).flatten
    val protectedRows = (all.take(3) ++ milestones).distinct
    val recent = all.takeRight(24).filterNot(protectedRows.contains)
    def fit(tail: Vector[J]): Vector[J] =
      val result = protectedRows ++ tail
      if SyntheticRewardProjection.encode(J.Arr(result)).size <= 40960 then result
      else fit(tail.drop(1))
    fit(recent)

  /** A size overflow produces a bounded failing record, never a success claim or an unreadable
    * oversized result. The controller's ceiling is exactly 64 KiB.
    */
  private[lab] def encodeEvidence(value: J): Bytes =
    val original = SyntheticRewardProjection.encode(value)
    if original.size <= 65536 then original
    else
      val rows = value match
        case J.Obj(values) =>
          values.get("observations") match
            case Some(J.Arr(rows)) => rows.take(3) ++ rows.takeRight(24)
            case _                 => Vector.empty
        case _ => Vector.empty
      val fallback = record(
        "schema" -> text("ada-submission-client-result-v1"),
        "passed" -> flag(false),
        "ingress" -> text("scala-http"),
        "fullLedgerValidated" -> flag(false),
        "failureType" -> text("ClientEvidenceLimit"),
        "evidenceTruncated" -> flag(true),
        "originalBytes" -> J.Num(original.size.toString),
        "originalSHA256" -> text(ClusterHeaderObservation.sha256(original).hex),
        "observations" -> J.Arr(rows.distinct.map(summarizeObservation))
      )
      val encoded = SyntheticRewardProjection.encode(fallback)
      require(encoded.size <= 65536, "bounded metadata invariant")
      encoded

  private def save(path: Path, value: J): IO[Boolean] = IO.blocking {
    val encoded = encodeEvidence(value)
    require(encoded.size <= 65536, "client evidence bound")
    val temporary = path.resolveSibling(path.getFileName.toString + ".part")
    Files.write(temporary, encoded.toArray, Open.CREATE_NEW, Open.WRITE)
    // Same-filesystem hard-link publication is atomic and refuses replacement.
    // Preserve the completed .part too, as private evidence of the exact bytes.
    Files.createLink(path, temporary)
    field(ReferenceJson.parse(encoded), "passed") == flag(true)
  }
  private def http: Resource[IO, HttpClient] =
    Resource
      .make(
        IO.blocking(
          new ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue[Runnable](16),
            new ThreadPoolExecutor.AbortPolicy()
          )
        )
      )(pool => IO.blocking { pool.shutdownNow(); pool.awaitTermination(2, TimeUnit.SECONDS); () })
      .evalMap { pool =>
        IO.blocking {
          val direct = new ProxySelector:
            def select(uri: URI): java.util.List[Proxy] = java.util.List.of(Proxy.NO_PROXY)
            def connectFailed(uri: URI, address: SocketAddress, error: java.io.IOException): Unit =
              ()
          HttpClient
            .newBuilder()
            .executor(pool)
            .proxy(direct)
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build()
        }
      }
  private final case class Reply(status: Int, json: J)
  private def request(
      client: HttpClient,
      port: Int,
      target: String,
      body: Option[Bytes]
  ): IO[Reply] =
    val builder = HttpRequest
      .newBuilder(URI.create(s"http://127.0.0.1:$port$target"))
      .timeout(Duration.ofSeconds(5))
    val message = body match
      case Some(bytes) =>
        builder
          .header("Content-Type", "application/cbor")
          .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toArray))
          .build()
      case None => builder.GET().build()
    Resource
      .make(IO.interruptibleMany(client.send(message, HttpResponse.BodyHandlers.ofInputStream())))(
        response => IO.blocking(response.body().close())
      )
      .use { response =>
        IO.interruptibleMany(response.body().readNBytes(65537)).timeout(5.seconds).flatMap {
          bytes =>
            IO {
              require(bytes.length <= 65536, "HTTP response too large")
              require(
                response
                  .headers()
                  .firstValue("Content-Type")
                  .orElse("")
                  .equalsIgnoreCase("application/json"),
                "JSON content type required"
              )
              Reply(response.statusCode(), ReferenceJson.parse(Bytes.fromArray(bytes)))
            }
        }
      }
  private def exercise(port: Int, input: Path, observations: Ref[IO, Vector[J]]): IO[J] =
    for
      raw <- read(input)
      transaction <- IO.fromEither(
        SignedTransaction
          .checked(raw)
          .left
          .map(_ => new IllegalArgumentException("signed file structure rejected"))
      )
      result <- http.use { client =>
        def call(stage: String, target: String, body: Option[Bytes]): IO[Reply] =
          request(client, port, target, body).flatTap { reply =>
            observations.update(rows =>
              retainObservation(
                rows,
                record(
                  "stage" -> text(stage),
                  "httpStatus" -> J.Num(reply.status.toString),
                  "response" -> reply.json
                )
              )
            )
          }
        def stablePost(stage: String, bytes: Bytes): IO[Reply] =
          call(stage, "/v1/transactions", Some(bytes)).flatMap { reply =>
            val code = string(field(reply.json, "code"))
            if (reply.status == 503 && code == "Unavailable") || (reply.status == 409 && code == "StaleState")
            then IO.sleep(100.millis) *> IO.defer(stablePost(stage, bytes))
            else IO.pure(reply)
          }
        def included: IO[Reply] =
          call("status", "/v1/transactions/" + transaction.transactionId.hex, None).flatMap {
            reply =>
              IO {
                scoped(reply.json)
                val code = string(field(reply.json, "code"))
                require(
                  code == "Included" || code == "Pending" || code == "Unavailable",
                  "unexpected transaction status"
                )
                code
              }.flatMap {
                case "Included" =>
                  IO {
                    validateIncluded(reply.status, reply.json, transaction.transactionId)
                    reply
                  }
                case "Pending" =>
                  IO(receipt(reply.json, transaction, pendingOnly = false)) *> IO.sleep(
                    500.millis
                  ) *> IO.defer(included)
                case _ => IO.sleep(500.millis) *> IO.defer(included)
              }
          }
        for
          negative <- stablePost("malformed", Bytes(Vector(0x84.toByte))).timeout(10.seconds)
          _ <- IO {
            require(negative.status == 400, "malformed HTTP status")
            requireField(negative.json, "code", text("DecodeRejected"))
            scoped(negative.json)
          }
          accepted <- stablePost("admission", raw).timeout(10.seconds)
          _ <- IO {
            require(accepted.status == 202, "initial acceptance required")
            requireField(accepted.json, "code", text("Accepted"))
            receipt(accepted.json, transaction)
          }
          duplicate <- call("exactDuplicate", "/v1/transactions", Some(raw))
          _ <- IO {
            scoped(duplicate.json)
            val code = string(field(duplicate.json, "code"))
            if code == "AlreadyPresent" then
              require(duplicate.status == 200, "duplicate HTTP status")
              receipt(duplicate.json, transaction)
            else
              require(
                Set("Unavailable", "StaleState", "Rejected", "Unsupported").contains(code),
                "unexpected duplicate result"
              )
          }
          observed <- included.timeout(60.seconds)
          _ <- IO {
            val admittedPin = field(accepted.json, "receipt", "pin")
            val includedPin = field(observed.json, "pin")
            require(
              field(admittedPin, "ownerId") == field(includedPin, "ownerId"),
              "owner changed before inclusion"
            )
            require(
              uint(field(includedPin, "generation")) > uint(field(admittedPin, "generation")),
              "inclusion requires a newer publication generation"
            )
          }
          rows <- observations.get
        yield record(
          "schema" -> text("ada-submission-client-result-v1"),
          "passed" -> flag(true),
          "transactionId" -> text(transaction.transactionId.hex),
          "envelopeSHA256" -> text(transaction.envelopeSHA256.hex),
          "includedBodySHA256" -> text(
            ClusterHeaderObservation.sha256(transaction.originalBody).hex
          ),
          "includedWitnessesSHA256" -> text(
            ClusterHeaderObservation.sha256(transaction.originalWitnesses).hex
          ),
          "spanDigestSource" -> text("submitted-original-for-controller-comparison"),
          "accepted" -> flag(true),
          "included" -> flag(true),
          "ingress" -> text("scala-http"),
          "volatile" -> flag(true),
          "fullLedgerValidated" -> flag(false),
          "profileId" -> text(StatePin.Profile),
          "malformedRejected" -> flag(true),
          "duplicateAlreadyPresent" -> flag(
            string(field(duplicate.json, "code")) == "AlreadyPresent"
          ),
          "acceptedResponse" -> accepted.json,
          "duplicateResponse" -> duplicate.json,
          "includedResponse" -> observed.json,
          "observations" -> J.Arr(rows)
        )
      }
    yield result

  def run(args: List[String]): IO[ExitCode] = args match
    case portText :: inputText :: outputText :: Nil =>
      for
        observations <- Ref.of[IO, Vector[J]](Vector.empty)
        output <- IO(Path.of(outputText))
        outcome <- (for
          port <- IO(portText.toInt)
          _ <- IO(require(port > 0 && port <= 65535, "loopback port range"))
          value <- exercise(port, Path.of(inputText), observations).timeout(60.seconds)
        yield value).attempt
        exit <- outcome match
          case Right(value) =>
            save(output, value).map(passed => if passed then ExitCode.Success else ExitCode.Error)
          case Left(error) =>
            observations.get
              .flatMap { rows =>
                save(
                  output,
                  record(
                    "schema" -> text("ada-submission-client-result-v1"),
                    "passed" -> flag(false),
                    "ingress" -> text("scala-http"),
                    "fullLedgerValidated" -> flag(false),
                    "failureType" -> text(error.getClass.getSimpleName.take(96)),
                    "observations" -> J.Arr(rows)
                  )
                )
              }
              .as(ExitCode.Error)
      yield exit
    case _ =>
      IO.raiseError(
        new IllegalArgumentException("expected apiPort transaction.cbor client-result.json")
      )
