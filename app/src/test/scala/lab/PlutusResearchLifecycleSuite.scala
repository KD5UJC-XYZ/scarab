// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import java.net.{InetAddress, InetSocketAddress, ServerSocket}
import lab.cbor.Bytes
import lab.submission.AdmissionProfile

/** Retained checked source geometry; only the local API socket is opened, no peer or transaction.
  */
class PlutusResearchLifecycleSuite extends munit.FunSuite:
  private def get[A](value: Either[?, A]): A = value.fold(e => fail(e.toString), identity)
  sys.env.get("PLUTUS_BOOTSTRAP_BUNDLE").foreach { directory =>
    lazy val pin = sys.env.getOrElse(
      "PLUTUS_BOOTSTRAP_MANIFEST_SHA256",
      fail("independent manifest pin required")
    )
    lazy val joined = PlutusResearchIO.initial(Path.of(directory), pin, AdmissionProfile.PlutusV3)
    def fresh = IO(get(SequenceInput.fromNativeDiagnostic(joined, joined.id))).flatMap(context =>
      CoherentSequence
        .createPlutusDiagnosticWithStake[IO](context, joined.ledger.epochComponents.stake)
        .map(get(_))
    )
    def exercise(cancel: Boolean): IO[Unit] = for
      root <- IO.blocking(Files.createTempDirectory("plutus-cli-lifecycle-"))
      opened <- Deferred[IO, (PlutusResearchNode.Session[IO], Int)]
      action = PlutusResearchNode
        .resource(fresh, root.resolve("receipts"), joined.id, get(Bytes.fromHex(pin)))
        .use { node =>
          node
            .http()
            .use(api => opened.complete(node -> api.port) *> (if cancel then IO.never else IO.unit))
        }
      fiber <- action.start
      live <- opened.get
      _ <- if cancel then fiber.cancel else fiber.joinWithNever
      unavailable <- live._1.service.snapshot.attempt
      _ = assert(unavailable.isLeft, "closed owner must refuse admission views")
      _ <- IO.blocking {
        val socket = new ServerSocket()
        try socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), live._2))
        finally socket.close()
      }
    yield ()
    test("normal resource completion releases API socket and closes admission owner") {
      exercise(false).unsafeToFuture()
    }
    test("external cancellation releases API socket and closes admission owner") {
      exercise(true).unsafeToFuture()
    }
  }
