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
import lab.submission.{AdmissionProfile, SignedTransaction, StatePin}
import ReferenceJson.{Json as J, field, string, uint}
import scala.concurrent.duration.*

/** Test-only external client. The signed file crosses only the Scala HTTP API; this process has no
  * reference CLI or transaction-submission network access.
  */
object PlutusServiceClientMain extends IOApp:
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
  private def pin(j: J, profile: AdmissionProfile): Unit =
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
    requireField(j, "profileId", text(profile.id))
    val point = field(j, "point")
    require(
      objectKeys(point) == Set("slot", "blockNo", "hash"),
      "complete inclusion point required"
    )
    hash(field(point, "hash"))
    boundedUInt(field(point, "slot"))
    boundedUInt(field(point, "blockNo"))
  private def scoped(j: J, profile: AdmissionProfile): Unit =
    requireField(j, "profileId", text(profile.id))
    requireField(j, "fullLedgerValidated", flag(false))
    requireField(j, "volatile", flag(true))
  private def receipt(
      j: J,
      transaction: SignedTransaction,
      pendingOnly: Boolean = true,
      profile: AdmissionProfile = AdmissionProfile.AdaVkey
  ): Unit =
    scoped(j, profile)
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
    pin(field(value, "pin"), profile)

  /** Shared by the live client and an actual-handler schema regression. */
  private[lab] def validateIncluded(
      status: Int,
      json: J,
      transactionId: Bytes,
      profile: AdmissionProfile = AdmissionProfile.AdaVkey
  ): Unit =
    require(status == 200, "included HTTP status")
    scoped(json, profile)
    requireField(json, "code", text("Included"))
    requireField(json, "transactionId", text(transactionId.hex))
    requireField(json, "status", text("included"))
    requireField(json, "submittedEnvelopeByteEqualityVerified", flag(false))
    pin(field(json, "pin"), profile)

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
        "schema" -> text("plutus-service-client-result-v1"),
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
  private val profile = AdmissionProfile.PlutusV3
  private def sha(raw: Bytes): String = ClusterHeaderObservation.sha256(raw).hex
  private def snapshot(reply: Reply): Unit =
    require(reply.status == 200, "service state HTTP status")
    scoped(reply.json, profile)
    pin(field(reply.json, "pin"), profile)

  private def publication(
      root: Path,
      transaction: SignedTransaction,
      included: J
  ): IO[(String, Bytes, J)] =
    def scan: IO[Option[(String, Bytes, J)]] = IO.blocking {
      (0 until 128).iterator
        .flatMap { index =>
          val name = f"publication-$index%04d.json"
          val path = root.resolve(name)
          if !Files.exists(path) then None
          else
            require(
              Files.isRegularFile(path) && !Files.isSymbolicLink(path),
              "regular publication file required"
            )
            val stream = Files.newInputStream(path)
            val bytes =
              try stream.readNBytes(131073)
              finally stream.close()
            require(bytes.length <= 131072, "publication bound")
            val raw = Bytes.fromArray(bytes)
            val json = ReferenceJson.parse(raw)
            requireField(json, "schema", text("plutus-service-publication-v1"))
            requireField(json, "profileId", text(profile.id))
            requireField(json, "fullLedgerValidated", flag(false))
            requireField(json, "diagnosticOnly", flag(true))
            if field(json, "pin") == field(included, "pin") then Some((name, raw, json)) else None
        }
        .take(1)
        .toVector
        .headOption
    }
    scan.flatMap {
      case None => IO.sleep(100.millis) *> IO.defer(publication(root, transaction, included))
      case Some(found @ (_, _, json)) =>
        IO {
          val matches = ReferenceJson
            .array(field(json, "included"))
            .filter(row => string(field(row, "transactionId")) == transaction.transactionId.hex)
          require(matches.size == 1, "unique transaction in matching publication required")
          requireField(matches.head, "bodySHA256", text(sha(transaction.originalBody)))
          requireField(matches.head, "witnessesSHA256", text(sha(transaction.originalWitnesses)))
          found
        }
    }

  private def exercise(port: Int, root: Path, observations: Ref[IO, Vector[J]]): IO[J] =
    for
      originals <- Vector("transaction-1.cbor", "transaction-2.cbor", "conflict-1.cbor")
        .traverse(name => read(root.resolve("submission").resolve(name)))
      checked <- originals.traverse(raw =>
        IO.fromEither(
          SignedTransaction.checked(raw).left.map(e => new IllegalArgumentException(e.toString))
        )
      )
      _ <- IO(
        require(
          checked.map(_.transactionId).distinct.size == 3,
          "distinct independent/conflicting transaction identities required"
        )
      )
      result <- http.use { client =>
        def call(stage: String, target: String, body: Option[Bytes]): IO[Reply] =
          request(client, port, target, body).flatTap(reply =>
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
          )
        def stablePost(stage: String, raw: Bytes): IO[Reply] =
          call(stage, "/v1/transactions", Some(raw)).flatMap { reply =>
            val code = string(field(reply.json, "code"))
            if (reply.status == 503 && code == "Unavailable") || (reply.status == 409 && code == "StaleState")
            then IO.sleep(100.millis) *> IO.defer(stablePost(stage, raw))
            else IO.pure(reply)
          }
        def waitIncluded(tx: SignedTransaction, stage: String): IO[Reply] =
          call(stage, "/v1/transactions/" + tx.transactionId.hex, None).flatMap { reply =>
            scoped(reply.json, profile)
            string(field(reply.json, "code")) match
              case "Included" =>
                IO(validateIncluded(reply.status, reply.json, tx.transactionId, profile)).as(reply)
              case "Pending" =>
                IO(receipt(reply.json, tx, pendingOnly = false, profile = profile)) *>
                  IO.sleep(200.millis) *> IO.defer(waitIncluded(tx, stage))
              case "Unavailable" => IO.sleep(100.millis) *> IO.defer(waitIncluded(tx, stage))
              case other =>
                IO.raiseError(new IllegalStateException("unexpected pending status: " + other))
          }
        def accepted(raw: Bytes, tx: SignedTransaction, ordinal: Int): IO[(Reply, Long, Reply)] =
          for
            response <- stablePost(s"admission$ordinal", raw)
            at <- IO.monotonic
            _ <- IO {
              require(response.status == 202, "new acceptance required")
              requireField(response.json, "code", text("Accepted"))
              receipt(response.json, tx, profile = profile)
            }
            duplicate <- call(s"duplicate$ordinal", "/v1/transactions", Some(raw))
            _ <- IO {
              scoped(duplicate.json, profile)
              val code = string(field(duplicate.json, "code"))
              if code == "AlreadyPresent" then
                require(duplicate.status == 200, "duplicate HTTP status")
                receipt(duplicate.json, tx, profile = profile)
              else
                require(
                  Set("Unavailable", "StaleState", "Rejected", "Unsupported")(code),
                  "unexpected duplicate result"
                )
            }
          yield (response, at.toNanos, duplicate)
        def completed(tx: SignedTransaction, ordinal: Int, accepted: (Reply, Long, Reply)): IO[J] =
          for
            inclusion <- waitIncluded(tx, s"included$ordinal")
            at <- IO.monotonic
            pub <- publication(root, tx, inclusion.json)
            _ <- IO {
              val old = field(accepted._1.json, "receipt", "pin")
              val next = field(inclusion.json, "pin")
              require(
                field(old, "ownerId") == field(next, "ownerId"),
                "owner changed during transaction"
              )
              require(
                uint(field(next, "generation")) > uint(field(old, "generation")),
                "newer inclusion generation required"
              )
            }
          yield record(
            "ordinal" -> J.Num(ordinal.toString),
            "transactionId" -> text(tx.transactionId.hex),
            "envelopeSHA256" -> text(tx.envelopeSHA256.hex),
            "bodySHA256" -> text(sha(tx.originalBody)),
            "witnessesSHA256" -> text(sha(tx.originalWitnesses)),
            "includedBodySHA256" -> text(sha(tx.originalBody)),
            "includedWitnessesSHA256" -> text(sha(tx.originalWitnesses)),
            "acceptedResponse" -> accepted._1.json,
            "duplicateResponse" -> accepted._3.json,
            "includedResponse" -> inclusion.json,
            "acceptedObservedNanos" -> J.Num(accepted._2.toString),
            "includedObservedNanos" -> J.Num(at.toNanos.toString),
            "publicationFile" -> text(pub._1),
            "publicationSHA256" -> text(sha(pub._2)),
            "publication" -> pub._3
          )
        for
          one <- accepted(originals(0), checked(0), 1)
          conflict <- call("conflict1", "/v1/transactions", Some(originals(2)))
          _ <- IO {
            scoped(conflict.json, profile)
            require(
              conflict.status != 202 && Set(
                "InputsReserved",
                "Rejected",
                "Unsupported",
                "StaleState",
                "Unavailable"
              )(string(field(conflict.json, "code"))),
              "conflicting spend must not be admitted"
            )
          }
          first <- completed(checked(0), 1, one)
          between <- call("betweenState", "/v1/state", None)
          _ <- IO(snapshot(between))
          two <- accepted(originals(1), checked(1), 2)
          _ <- IO {
            val firstPin = field(first, "acceptedResponse", "receipt", "pin")
            val includedPin = field(first, "includedResponse", "pin")
            val secondPin = field(two._1.json, "receipt", "pin")
            require(
              field(firstPin, "ownerId") == field(secondPin, "ownerId"),
              "same service owner required"
            )
            require(
              uint(field(secondPin, "generation")) >= uint(field(includedPin, "generation")) &&
                uint(field(secondPin, "generation")) > uint(field(firstPin, "generation")),
              "second acceptance must use fresh confirmed generation"
            )
          }
          second <- completed(checked(1), 2, two)
          alive <- call("afterSecondState", "/v1/state", None)
          _ <- IO(snapshot(alive))
          oldStatus <- call(
            "firstStatusAfterSecond",
            "/v1/transactions/" + checked(0).transactionId.hex,
            None
          )
          _ <- IO {
            validateIncluded(oldStatus.status, oldStatus.json, checked(0).transactionId, profile)
            require(
              field(oldStatus.json, "pin") == field(first, "includedResponse", "pin"),
              "historical first inclusion pin changed"
            )
            val ownerId = field(first, "acceptedResponse", "receipt", "pin", "ownerId")
            require(
              field(between.json, "pin", "ownerId") == ownerId && field(
                alive.json,
                "pin",
                "ownerId"
              ) == ownerId,
              "state endpoint owner changed"
            )
            requireField(between.json, "closed", flag(false))
            requireField(alive.json, "closed", flag(false))
            require(
              uint(field(alive.json, "transactions")) == 0,
              "included entries must leave pool"
            )
            require(
              uint(field(alive.json, "eligibleTransactions")) == 0,
              "included entries must lose relay eligibility"
            )
          }
          rows <- observations.get
        yield record(
          "schema" -> text("plutus-service-client-result-v1"),
          "passed" -> flag(true),
          "ingress" -> text("scala-http"),
          "profileId" -> text(profile.id),
          "fullLedgerValidated" -> flag(false),
          "transactions" -> J.Arr(Vector(first, second)),
          "betweenStateResponse" -> between.json,
          "afterSecondStateResponse" -> alive.json,
          "firstStatusAfterSecondResponse" -> oldStatus.json,
          "conflictResponse" -> conflict.json,
          "conflictHttpStatus" -> J.Num(conflict.status.toString),
          "serviceAliveAfterSecondInclusion" -> flag(true),
          "sameOwner" -> flag(true),
          "firstInclusionThenSecondSubmission" -> flag(true),
          "apiAvailableAfterFirst" -> flag(true),
          "apiAvailableAfterSecond" -> flag(true),
          "staleReceiptSubmitted" -> flag(false),
          "observations" -> J.Arr(rows)
        )
      }
    yield result

  def run(args: List[String]): IO[ExitCode] = args match
    case portText :: durationText :: rootText :: Nil =>
      for
        observations <- Ref.of[IO, Vector[J]](Vector.empty)
        root <- IO(Path.of(rootText))
        result <- (for
          port <- IO(portText.toInt)
          duration <- IO(durationText.toInt)
          _ <- IO(
            require(
              port > 0 && port <= 65535 && duration >= 1 && duration <= 60,
              "bounded loopback client arguments"
            )
          )
          value <- exercise(port, root, observations).timeout(duration.seconds)
        yield value).attempt
        exit <- result match
          case Right(value) =>
            save(root.resolve("submission/service-client-result.json"), value).map(passed =>
              if passed then ExitCode.Success else ExitCode.Error
            )
          case Left(error) =>
            observations.get
              .flatMap(rows =>
                save(
                  root.resolve("submission/service-client-result.json"),
                  record(
                    "schema" -> text("plutus-service-client-result-v1"),
                    "passed" -> flag(false),
                    "ingress" -> text("scala-http"),
                    "failureType" -> text(error.getClass.getSimpleName.take(96)),
                    "fullLedgerValidated" -> flag(false),
                    "observations" -> J.Arr(rows)
                  )
                )
              )
              .as(ExitCode.Error)
      yield exit
    case _ => IO.raiseError(new IllegalArgumentException("expected PORT DURATION_SECONDS EXCHANGE"))
