// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import lab.cbor.Bytes
import ReferenceJson.{Json as J, field}

class PlutusServiceStateProbeSuite extends munit.FunSuite:
  private val hash = "ab" * 32
  private val raw = s"""{"code":"State","profileId":"${lab.submission.AdmissionProfile.PlutusV3.id}","fullLedgerValidated":false,"volatile":true,"closed":false,"pin":{"ownerId":"$hash","generation":1,"point":{"hash":"$hash","slot":20,"blockNo":2},"coherentStateId":"$hash","ledgerStateId":"$hash","environmentId":"$hash","validationSlot":20,"profileId":"${lab.submission.AdmissionProfile.PlutusV3.id}"}}""".getBytes(java.nio.charset.StandardCharsets.UTF_8)

  private def directory = Resource.make(IO.blocking(Files.createTempDirectory("state-probe-test")))(p => IO.blocking {
    Files.deleteIfExists(p.resolve("state.json"))
    Files.deleteIfExists(p.resolve("state.json.part"))
    Files.deleteIfExists(p)
    ()
  })

  private def server(status: Int, method: AtomicReference[String]) = Resource.make(IO.blocking {
    val s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext("/v1/state", exchange => {
      method.set(exchange.getRequestMethod)
      exchange.getResponseHeaders.set("Content-Type", "application/json")
      exchange.sendResponseHeaders(status, raw.length.toLong)
      try exchange.getResponseBody.write(raw)
      finally exchange.close()
    })
    s.start()
    s
  })(s => IO.blocking(s.stop(0))).map(_.getAddress.getPort)

  test("probe records actual GET state after resource finalization and never overwrites") {
    val method = new AtomicReference[String]()
    directory.use { root =>
      server(200, method).use { port =>
        val output = root.resolve("state.json")
        for
          _ <- PlutusServiceStateProbeMain.run(List(port.toString, output.toString))
          before <- IO.blocking(Files.readAllBytes(output))
          duplicate <- PlutusServiceStateProbeMain.run(List(port.toString, output.toString)).attempt
          after <- IO.blocking(Files.readAllBytes(output))
        yield
          val value = ReferenceJson.parse(Bytes.fromArray(before))
          assertEquals(method.get(), "GET")
          assertEquals(field(value, "resourcesFinalized"), J.Lit("true"))
          assertEquals(field(value, "state"), ReferenceJson.parse(Bytes.fromArray(raw)))
          assert(duplicate.isLeft)
          assertEquals(before.toVector, after.toVector)
      }
    }.timeout(10.seconds).unsafeToFuture()
  }

  test("failed HTTP status cannot publish a successful probe") {
    directory.use { root =>
      server(503, new AtomicReference[String]()).use { port =>
        val output = root.resolve("state.json")
        PlutusServiceStateProbeMain.run(List(port.toString, output.toString)).attempt.map { result =>
          assert(result.isLeft)
          assert(!Files.exists(output))
          assert(!Files.exists(root.resolve("state.json.part")))
        }
      }
    }.timeout(10.seconds).unsafeToFuture()
  }
