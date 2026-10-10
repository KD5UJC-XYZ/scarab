// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import java.net.{URI, Proxy, ProxySelector, SocketAddress}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import java.time.Duration
import java.util.concurrent.{ArrayBlockingQueue, ThreadPoolExecutor, TimeUnit}
import scala.concurrent.duration.*
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.header.PraosCertificateState.Point
import lab.submission.{AdmissionProfile, SignedTransaction, StatePin}
import ReferenceJson.{Json as J, field, string, uint, array}
import PlutusMultiEndpointScenarioAdapter.*

/** Concrete bounded loopback transport. Publications are observations read from the independently
  * owned endpoint directory; this client neither starts nodes nor mutates confirmed state.
  */
object PlutusMultiEndpointHttp:
  private val profile = AdmissionProfile.PlutusV3.id
  private def flag(value: Boolean): J = J.Lit(value.toString)
  def hash(bytes: Bytes): Bytes = ClusterHeaderObservation.sha256(bytes)
  private def decodedHash(j: J): Bytes =
    val text = string(j)
    require(text.matches("[0-9a-f]{64}"), "canonical hash")
    Bytes.fromHex(text).toOption.get
  private[lab] def pin(j: J): StatePin =
    val p = field(j, "point")
    StatePin
      .checked(
        decodedHash(field(j, "ownerId")),
        uint(field(j, "generation")),
        Point(decodedHash(field(p, "hash")), uint(field(p, "slot")), uint(field(p, "blockNo"))),
        decodedHash(field(j, "coherentStateId")),
        decodedHash(field(j, "ledgerStateId")),
        decodedHash(field(j, "environmentId")),
        uint(field(j, "validationSlot")),
        string(field(j, "profileId"))
      )
      .fold(e => throw new IllegalArgumentException(e), identity)
  private def scoped(j: J): Unit =
    require(field(j, "profileId") == J.Str(profile))
    require(field(j, "fullLedgerValidated") == flag(false))
    require(field(j, "volatile") == flag(true))
  private def owned(p: StatePin, endpoint: Endpoint): Unit =
    require(
      p.ownerId == endpoint.ownerId && p.profileId == profile && p.validationSlot == p.point.slot
    )
  private[lab] def stateReply(status: Int, j: J): Snapshot =
    require(status == 200 && field(j, "code") == J.Str("State"))
    scoped(j)
    require(field(j, "closed") == flag(false), "open state required")
    Snapshot(pin(field(j, "pin")), false)
  private[lab] def acceptedReply(
      status: Int,
      j: J,
      tx: SignedTransaction,
      endpoint: Endpoint
  ): Accepted =
    require(status == 202 && field(j, "code") == J.Str("Accepted"))
    scoped(j)
    require(field(j, "status") == J.Str("pending"))
    val receipt = field(j, "receipt")
    require(
      field(receipt, "volatile") == flag(true) && field(receipt, "fullLedgerValidated") == flag(
        false
      )
    )
    val p = pin(field(receipt, "pin"))
    owned(p, endpoint)
    require(decodedHash(field(receipt, "transactionId")) == tx.transactionId)
    require(decodedHash(field(receipt, "envelopeSHA256")) == tx.envelopeSHA256)
    Accepted(tx.transactionId, tx.envelopeSHA256, p)

  private[lab] def follows(later: StatePin, earlier: StatePin): Boolean =
    later.ownerId == earlier.ownerId && later.generation >= earlier.generation &&
      later.point.slot >= earlier.point.slot && later.point.blockNo >= earlier.point.blockNo &&
      ((later.point.slot > earlier.point.slot && later.point.blockNo > earlier.point.blockNo) || later.point == earlier.point) &&
      (later.generation != earlier.generation || later == earlier)

  final case class Proof(file: String, original: Bytes, json: J, pin: StatePin)
  private[lab] def publication(
      raw: Bytes,
      file: String,
      endpoint: Endpoint,
      tx: SignedTransaction,
      ready: J
  ): Option[Proof] =
    require(raw.size <= 131072)
    val j = ReferenceJson.parse(raw)
    require(field(j, "schema") == J.Str("plutus-service-publication-v1"))
    require(
      field(j, "profileId") == J.Str(profile) && field(j, "fullLedgerValidated") == flag(
        false
      ) && field(j, "diagnosticOnly") == flag(true)
    )
    Vector("sourceJoinId", "initialManifestSHA256").foreach(k =>
      require(field(j, k) == field(ready, k))
    )
    val p = pin(field(j, "pin")); owned(p, endpoint)
    val included = array(field(j, "included"))
    require(included.size <= 4096)
    val matches =
      included.filter(row => decodedHash(field(row, "transactionId")) == tx.transactionId)
    require(matches.size <= 1)
    matches.headOption.map { row =>
      require(decodedHash(field(row, "bodySHA256")) == hash(tx.originalBody))
      require(decodedHash(field(row, "witnessesSHA256")) == hash(tx.originalWitnesses))
      Proof(file, raw, j, p)
    }

  def read(path: Path, max: Int): IO[Bytes] = IO.blocking {
    require(Files.isRegularFile(path) && !Files.isSymbolicLink(path), "regular input required")
    val in = Files.newInputStream(path)
    val raw =
      try in.readNBytes(max + 1)
      finally in.close()
    require(raw.length <= max, "bounded input")
    Bytes.fromArray(raw)
  }
  private[lab] def disjoint(txs: Vector[SignedTransaction]): Unit =
    require(txs.size == 2 && txs.map(_.transactionId).distinct.size == 2 && txs.forall(_.isValid))
    def one(n: Node): String =
      val plain = n.value match
        case V.Tag(t, inner) if t == 258 => inner
        case _                           => n
      plain.value match
        case V.Arr(Vector(ref)) =>
          ref.value match
            case V.Arr(Vector(id, ix)) =>
              (id.value, ix.value) match
                case (V.ByteString(h), V.UInt(i))
                    if h.size == 32 && i >= 0 && i <= StatePin.MaxUInt64 =>
                  s"${h.hex}#$i"
                case _ => throw new IllegalArgumentException("input reference")
            case _ => throw new IllegalArgumentException("input pair")
        case _ => throw new IllegalArgumentException("one input required")
    val refs = txs.flatMap { tx =>
      val n = Cbor.decode(tx.originalBody, Cbor.Limits(65536, 32, 16384, 65536)).toOption.get
      val fields = n.value match
        case V.Map(rows) =>
          rows.map { case (k, v) =>
            k.value match
              case V.UInt(key) => key -> v
              case _           => throw new IllegalArgumentException("body key")
          }.toMap
        case _ => throw new IllegalArgumentException("body map")
      Vector(one(fields(0)), one(fields(13)))
    }
    require(refs.distinct.size == 4, "disjoint spending and collateral inputs required")

  private[lab] def http: Resource[IO, HttpClient] =
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
      ) { pool =>
        IO.blocking {
          pool.shutdownNow();
          require(pool.awaitTermination(5, TimeUnit.SECONDS), "HTTP executor termination"); ()
        }
      }
      .flatMap { pool =>
        Resource.make(IO.blocking {
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
            .connectTimeout(Duration.ofSeconds(2))
            .build()
        })(value =>
          IO.blocking {
            value.shutdownNow()
            require(value.awaitTermination(Duration.ofSeconds(5)), "HTTP client termination")
          }
        )
      }
  private[lab] def request(
      client: HttpClient,
      port: Int,
      target: String,
      body: Option[Bytes]
  ): IO[(Int, J)] =
    require(
      port > 0 && port <= 65535 && (target == "/v1/state" || target == "/v1/transactions" || target
        .matches("/v1/transactions/[0-9a-f]{64}"))
    )
    val builder = HttpRequest
      .newBuilder(URI.create(s"http://127.0.0.1:$port$target"))
      .timeout(Duration.ofSeconds(3))
    val req = body match
      case Some(raw) =>
        builder
          .header("Content-Type", "application/cbor")
          .POST(HttpRequest.BodyPublishers.ofByteArray(raw.toArray))
          .build()
      case None => builder.GET().build()
    Resource
      .make(IO.interruptibleMany(client.send(req, HttpResponse.BodyHandlers.ofInputStream())))(r =>
        IO.blocking(r.body().close())
      )
      .use { reply =>
        IO.interruptibleMany(reply.body().readNBytes(65537)).timeout(3.seconds).flatMap { bytes =>
          IO {
            require(bytes.length <= 65536, "HTTP response limit")
            require(
              reply
                .headers()
                .firstValue("Content-Type")
                .orElse("")
                .equalsIgnoreCase("application/json")
            )
            (reply.statusCode(), ReferenceJson.parse(Bytes.fromArray(bytes)))
          }
        }
      }

  final class Live private[PlutusMultiEndpointHttp] (
      val endpoint: Endpoint,
      exchange: Path,
      tx: SignedTransaction,
      ready: J,
      client: HttpClient,
      initial: J,
      accepted: Ref[IO, Option[J]],
      included: Ref[IO, Option[J]],
      designated: Ref[IO, Option[Proof]],
      lastReply: Ref[IO, Option[J]]
  ) extends Client:
    private def call(target: String, raw: Option[Bytes] = None) =
      request(client, endpoint.loopbackPort, target, raw).attempt.flatMap {
        case Right(reply) =>
          lastReply
            .set(
              Some(
                J.Obj(
                  Map(
                    "target" -> J.Str(target),
                    "httpStatus" -> J.Num(reply._1.toString),
                    "response" -> reply._2
                  )
                )
              )
            )
            .as(reply)
        case Left(error) =>
          val cause = error match
            case _: java.net.ConnectException             => "ConnectionFailure"
            case _: java.util.concurrent.TimeoutException => "Deadline"
            case _: java.net.http.HttpTimeoutException    => "Deadline"
            case _: java.io.IOException                   => "TransportFailure"
            case _                                        => "InvalidObservation"
          lastReply.update { previous =>
            Some(
              J.Obj(
                Map(
                  "target" -> J.Str(target),
                  "failureCause" -> J.Str(cause),
                  "lastReceived" -> previous
                    .map {
                      case J.Obj(fields) if fields.contains("lastReceived") =>
                        fields("lastReceived")
                      case value => value
                    }
                    .getOrElse(J.Lit("null"))
                )
              )
            )
          } *> IO.raiseError(error)
      }
    def audit: IO[J] = (accepted.get, included.get, lastReply.get).mapN { (a, i, last) =>
      J.Obj(
        Map(
          "endpointId" -> J.Str(endpoint.id),
          "port" -> J.Num(endpoint.loopbackPort.toString),
          "observedOwnerId" -> J.Str(endpoint.ownerId.hex),
          "transactionId" -> J.Str(tx.transactionId.hex),
          "acceptedResponse" -> a.getOrElse(J.Lit("null")),
          "includedResponse" -> i.getOrElse(J.Lit("null")),
          "initialState" -> initial,
          "lastReply" -> last.getOrElse(J.Lit("null"))
        )
      )
    }
    def state: IO[Snapshot] = stateJson.map(_._1)
    private def stateJson: IO[(Snapshot, J)] = call("/v1/state").flatMap { (status, j) =>
      IO {
        val s = stateReply(status, j); owned(s.pin, endpoint); (s, j)
      }
    }
    def submit(original: Bytes): IO[Accepted] =
      if original != tx.original then
        IO.raiseError(new IllegalArgumentException("designated original mismatch"))
      else
        call("/v1/transactions", Some(original)).flatMap { (status, j) =>
          val code = string(field(j, "code"))
          if (status == 503 && code == "Unavailable") || (status == 409 && code == "StaleState")
          then IO(scoped(j)) *> IO.sleep(100.millis) *> IO.defer(submit(original))
          else IO(acceptedReply(status, j, tx, endpoint)).flatTap(_ => accepted.set(Some(j)))
        }
    def proof(transaction: SignedTransaction): IO[Proof] =
      def scan(index: Int): IO[Option[Proof]] =
        if index >= 128 then IO.pure(None)
        else
          val file = f"publication-$index%04d.json"
          val path = exchange.resolve(file)
          IO.blocking(Files.exists(path)).flatMap {
            case false => IO.pure(None)
            case true =>
              read(path, 131072)
                .flatMap(raw => IO(publication(raw, file, endpoint, transaction, ready)))
                .flatMap {
                  case found @ Some(_) => IO.pure(found)
                  case None            => IO.defer(scan(index + 1))
                }
          }
      scan(0).flatMap {
        case Some(found) => IO.pure(found)
        case None        => IO.sleep(100.millis) *> IO.defer(proof(transaction))
      }
    def awaitIncluded(id: Bytes): IO[Included] =
      require(id == tx.transactionId)
      call("/v1/transactions/" + id.hex).flatMap { (status, j) =>
        IO(scoped(j)) *> (string(field(j, "code")) match
          case "Included" =>
            for
              _ <- IO {
                PlutusServiceClientMain.validateIncluded(status, j, id, AdmissionProfile.PlutusV3)
                require(field(j, "submittedEnvelopeByteEqualityVerified") == flag(false))
                owned(pin(field(j, "pin")), endpoint)
              }
              p <- proof(tx)
              _ <- IO(require(p.pin == pin(field(j, "pin")), "HTTP/publication full pin"))
              _ <- included.set(Some(j)) *> designated.set(Some(p))
            yield Included(
              id,
              p.pin,
              Publication(p.pin, id, hash(tx.originalBody), hash(tx.originalWitnesses))
            )
          case "Pending" =>
            IO {
              require(status == 200)
              val receipt = field(j, "receipt")
              require(
                decodedHash(field(receipt, "transactionId")) == tx.transactionId && decodedHash(
                  field(receipt, "envelopeSHA256")
                ) == tx.envelopeSHA256
              )
              owned(pin(field(receipt, "pin")), endpoint)
            } *> IO.sleep(100.millis) *> IO.defer(awaitIncluded(id))
          case "Unavailable" =>
            IO(require(status == 503)) *> IO.sleep(100.millis) *> IO.defer(awaitIncluded(id))
          case _ => IO.raiseError(new IllegalArgumentException("inclusion observation rejected")))
      }
    def finish(all: Vector[SignedTransaction]): IO[J] = for
      proofs <- all.traverse(proof)
      finalState <- stateJson
      _ <- IO {
        val start = pin(field(initial, "pin"))
        proofs.foreach(p =>
          require(
            follows(finalState._1.pin, p.pin) && follows(
              p.pin,
              start
            ) && p.pin.generation > start.generation && p.pin.point.slot > start.point.slot && p.pin.point.blockNo > start.point.blockNo
          )
        )
      }
      a <- accepted.get.flatMap(IO.fromOption(_)(new IllegalStateException("acceptance missing")))
      i <- included.get.flatMap(IO.fromOption(_)(new IllegalStateException("inclusion missing")))
      p <- designated.get.flatMap(
        IO.fromOption(_)(new IllegalStateException("publication missing"))
      )
    yield J.Obj(
      Map(
        "endpointId" -> J.Str(endpoint.id),
        "port" -> J.Num(endpoint.loopbackPort.toString),
        "observedOwnerId" -> J.Str(endpoint.ownerId.hex),
        "transactionId" -> J.Str(tx.transactionId.hex),
        "envelopeSHA256" -> J.Str(tx.envelopeSHA256.hex),
        "bodySHA256" -> J.Str(hash(tx.originalBody).hex),
        "witnessesSHA256" -> J.Str(hash(tx.originalWitnesses).hex),
        "acceptedResponse" -> a,
        "includedResponse" -> i,
        "publicationFile" -> J.Str(p.file),
        "publicationSHA256" -> J.Str(hash(p.original).hex),
        "publication" -> p.json,
        "initialState" -> initial,
        "finalState" -> finalState._2,
        "observedTransactions" -> J.Arr(all.zip(proofs).map { (transaction, observed) =>
          J.Obj(
            Map(
              "transactionId" -> J.Str(transaction.transactionId.hex),
              "bodySHA256" -> J.Str(hash(transaction.originalBody).hex),
              "witnessesSHA256" -> J.Str(hash(transaction.originalWitnesses).hex),
              "publicationFile" -> J.Str(observed.file),
              "publicationSHA256" -> J.Str(hash(observed.original).hex),
              "publication" -> observed.json
            )
          )
        })
      )
    )

  def resource(id: String, port: Int, exchange: Path, tx: SignedTransaction): Resource[IO, Live] =
    for
      client <- http
      ready <- Resource.eval(
        read(exchange.resolve("bootstrap-ready.json"), 65536).map(ReferenceJson.parse)
      )
      _ <- Resource.eval(IO(require(field(ready, "schema") == J.Str("plutus-service-ready-v1"))))
      first <- Resource.eval(request(client, port, "/v1/state", None))
      snapshot <- Resource.eval(IO(stateReply(first._1, first._2)))
      accepted <- Resource.eval(Ref.of[IO, Option[J]](None))
      included <- Resource.eval(Ref.of[IO, Option[J]](None))
      proof <- Resource.eval(Ref.of[IO, Option[Proof]](None))
      last <- Resource.eval(Ref.of[IO, Option[J]](None))
    yield new Live(
      Endpoint(id, port, snapshot.pin.ownerId),
      exchange,
      tx,
      ready,
      client,
      first._2,
      accepted,
      included,
      proof,
      last
    )
