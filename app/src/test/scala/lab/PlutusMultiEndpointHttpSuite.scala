// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO, Resource}
import cats.effect.unsafe.implicits.global
import scala.concurrent.duration.*
import java.net.InetSocketAddress
import java.net.http.HttpClient
import com.sun.net.httpserver.HttpServer
import lab.cbor.Bytes
import lab.submission.{AdmissionProfile, SignedTransaction}
import ReferenceJson.{Json as J, field}
import PlutusMultiEndpointScenarioAdapter.*

class PlutusMultiEndpointHttpSuite extends munit.FunSuite:
  private def hash(n: String) = J.Str(n * 64)
  private val profile = J.Str(AdmissionProfile.PlutusV3.id)
  private def pin(owner: String = "1", generation: Int = 0): J = J.Obj(
    Map(
      "ownerId" -> hash(owner),
      "generation" -> J.Num(generation.toString),
      "point" -> J.Obj(
        Map(
          "hash" -> hash("2"),
          "slot" -> J.Num((10 + generation).toString),
          "blockNo" -> J.Num((1 + generation).toString)
        )
      ),
      "coherentStateId" -> hash("3"),
      "ledgerStateId" -> hash("4"),
      "environmentId" -> hash("5"),
      "validationSlot" -> J.Num((10 + generation).toString),
      "profileId" -> profile
    )
  )
  private def tx(spend: Int, collateral: Int) = SignedTransaction
    .checked(
      Bytes
        .fromHex(
          "84a20081825820" + "11" * 32 + f"$spend%02x" + "0d81825820" + "11" * 32 + f"$collateral%02x" + "a0f5f6"
        )
        .toOption
        .get
    )
    .toOption
    .get
  private val original = tx(0, 1)
  private val endpoint = Endpoint("service-1", 30001, Bytes.fromHex("11" * 32).toOption.get)
  private val flags = Map(
    "profileId" -> profile,
    "fullLedgerValidated" -> J.Lit("false"),
    "volatile" -> J.Lit("true")
  )
  private def state(owner: String = "1") =
    J.Obj(flags ++ Map("code" -> J.Str("State"), "closed" -> J.Lit("false"), "pin" -> pin(owner)))
  private val ready = J.Obj(Map("sourceJoinId" -> hash("8"), "initialManifestSHA256" -> hash("9")))
  private def publication(
      body: J = J.Str(PlutusMultiEndpointHttp.hash(original.originalBody).hex),
      owner: String = "1"
  ) = J.Obj(
    Map(
      "schema" -> J.Str("plutus-service-publication-v1"),
      "profileId" -> profile,
      "fullLedgerValidated" -> J.Lit("false"),
      "diagnosticOnly" -> J.Lit("true"),
      "sourceJoinId" -> hash("8"),
      "initialManifestSHA256" -> hash("9"),
      "pin" -> pin(owner, 1),
      "included" -> J.Arr(
        Vector(
          J.Obj(
            Map(
              "transactionId" -> J.Str(original.transactionId.hex),
              "bodySHA256" -> body,
              "witnessesSHA256" -> J.Str(
                PlutusMultiEndpointHttp.hash(original.originalWitnesses).hex
              )
            )
          )
        )
      )
    )
  )

  test("state requires actual successful scoped open response") {
    assertEquals(PlutusMultiEndpointHttp.stateReply(200, state()).pin.ownerId, endpoint.ownerId)
    intercept[IllegalArgumentException](PlutusMultiEndpointHttp.stateReply(503, state()))
    intercept[IllegalArgumentException](
      PlutusMultiEndpointHttp.stateReply(
        200,
        J.Obj(flags ++ Map("code" -> J.Str("State"), "closed" -> J.Lit("true"), "pin" -> pin()))
      )
    )
  }
  test("accepted response binds original envelope and observed owner") {
    def response(owner: String, envelope: J) = J.Obj(
      flags ++ Map(
        "code" -> J.Str("Accepted"),
        "status" -> J.Str("pending"),
        "receipt" -> J.Obj(
          Map(
            "transactionId" -> J.Str(original.transactionId.hex),
            "envelopeSHA256" -> envelope,
            "pin" -> pin(owner),
            "volatile" -> J.Lit("true"),
            "fullLedgerValidated" -> J.Lit("false")
          )
        )
      )
    )
    val good = response("1", J.Str(original.envelopeSHA256.hex))
    assertEquals(
      PlutusMultiEndpointHttp.acceptedReply(202, good, original, endpoint).transactionId,
      original.transactionId
    )
    intercept[IllegalArgumentException](
      PlutusMultiEndpointHttp.acceptedReply(
        202,
        response("6", J.Str(original.envelopeSHA256.hex)),
        original,
        endpoint
      )
    )
    intercept[IllegalArgumentException](
      PlutusMultiEndpointHttp.acceptedReply(202, response("1", hash("6")), original, endpoint)
    )
    intercept[IllegalArgumentException](
      PlutusMultiEndpointHttp.acceptedReply(200, good, original, endpoint)
    )
  }
  test("publication binds original spans, owner and bootstrap provenance") {
    def proof(j: J, binding: J = ready) = PlutusMultiEndpointHttp.publication(
      SyntheticRewardProjection.encode(j),
      "publication-0000.json",
      endpoint,
      original,
      binding
    )
    assert(proof(publication()).isDefined)
    intercept[IllegalArgumentException](proof(publication(hash("6"))))
    intercept[IllegalArgumentException](proof(publication(owner = "6")))
    intercept[IllegalArgumentException](
      proof(
        publication(),
        J.Obj(Map("sourceJoinId" -> hash("7"), "initialManifestSHA256" -> hash("9")))
      )
    )
  }
  test("two originals must have disjoint spending and collateral references") {
    PlutusMultiEndpointHttp.disjoint(Vector(original, tx(2, 3)))
    intercept[IllegalArgumentException](
      PlutusMultiEndpointHttp.disjoint(Vector(original, tx(2, 1)))
    )
    intercept[IllegalArgumentException](
      PlutusMultiEndpointHttp.disjoint(Vector(original, tx(1, 3)))
    )
  }
  private def server(body: Array[Byte], status: Int = 200): Resource[IO, Int] = Resource
    .make(IO.blocking {
      val s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      s.createContext(
        "/v1/state",
        exchange => {
          exchange.getResponseHeaders.set("Content-Type", "application/json")
          exchange.sendResponseHeaders(status, body.length.toLong)
          try exchange.getResponseBody.write(body)
          finally exchange.close()
        }
      )
      s.start(); s
    })(s => IO.blocking(s.stop(0)))
    .map(_.getAddress.getPort)
  test("actual loopback HTTP reads bounded JSON and refuses oversized bodies") {
    val raw = SyntheticRewardProjection.encode(state()).toArray
    (for
      good <- server(raw).use(port =>
        PlutusMultiEndpointHttp.http.use(c =>
          PlutusMultiEndpointHttp.request(c, port, "/v1/state", None)
        )
      )
      large <- server(Array.fill[Byte](65537)(32)).use(port =>
        PlutusMultiEndpointHttp.http
          .use(c => PlutusMultiEndpointHttp.request(c, port, "/v1/state", None))
          .attempt
      )
    yield
      assertEquals(good._1, 200)
      assertEquals(field(good._2, "code"), J.Str("State"))
      assert(large.isLeft)
    ).timeout(10.seconds).unsafeToFuture()
  }

  test("full-point substitution at equal coordinates is rejected") {
    val original = PlutusMultiEndpointHttp.pin(pin())
    val changed = lab.submission.StatePin
      .checked(
        original.ownerId,
        1,
        lab.header.PraosCertificateState.Point(
          Bytes.fromHex("66" * 32).toOption.get,
          original.point.slot,
          original.point.blockNo
        ),
        original.coherentStateId,
        original.ledgerStateId,
        original.environmentId,
        original.validationSlot,
        original.profileId
      )
      .toOption
      .get
    assert(!PlutusMultiEndpointHttp.follows(changed, original))
  }
  test("cancellation finalizes the owned Java HTTP client") {
    (for
      acquired <- Deferred[IO, HttpClient]
      fiber <- PlutusMultiEndpointHttp.http.use(c => acquired.complete(c) *> IO.never[Unit]).start
      client <- acquired.get
      _ <- fiber.cancel
      _ = assert(client.isTerminated)
    yield ()).timeout(10.seconds).unsafeToFuture()
  }

  test("oversized result becomes bounded failure and cannot preserve success exit") {
    val huge = J.Obj(
      Map(
        "passed" -> J.Lit("true"),
        "resourcesFinalized" -> J.Lit("true"),
        "padding" -> J.Str("x" * 1048576)
      )
    )
    val (raw, passed) = PlutusMultiEndpointClientMain.boundedResult(huge)
    assert(!passed)
    assert(raw.size < 4096)
    val json = ReferenceJson.parse(raw)
    assertEquals(field(json, "passed"), J.Lit("false"))
    assertEquals(field(json, "failureCause"), J.Str("EvidenceLimit"))
  }

  test("actual loopback POST preserves designated original envelope bytes") {
    val received = new java.util.concurrent.atomic.AtomicReference[Bytes]()
    val method = new java.util.concurrent.atomic.AtomicReference[String]()
    val service = Resource.make(IO.blocking {
      val s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      s.createContext(
        "/v1/transactions",
        exchange => {
          method.set(exchange.getRequestMethod)
          received.set(Bytes.fromArray(exchange.getRequestBody.readNBytes(65537)))
          val reply =
            SyntheticRewardProjection.encode(J.Obj(Map("code" -> J.Str("Accepted")))).toArray
          exchange.getResponseHeaders.set("Content-Type", "application/json")
          exchange.sendResponseHeaders(202, reply.length.toLong)
          try exchange.getResponseBody.write(reply)
          finally exchange.close()
        }
      )
      s.start(); s
    })(s => IO.blocking(s.stop(0)))
    service
      .use(s =>
        PlutusMultiEndpointHttp.http.use(c =>
          PlutusMultiEndpointHttp
            .request(c, s.getAddress.getPort, "/v1/transactions", Some(original.original))
        )
      )
      .map { reply =>
        assertEquals(reply._1, 202)
        assertEquals(method.get(), "POST")
        assertEquals(received.get(), original.original)
      }
      .timeout(10.seconds)
      .unsafeToFuture()
  }
