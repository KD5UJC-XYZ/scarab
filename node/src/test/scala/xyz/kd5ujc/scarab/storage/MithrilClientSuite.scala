// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{IO, Resource}
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.io.file.Path
import io.circe.Json
import java.nio.file.{Files, Path as JPath}
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.storage.mithril.MithrilClient

class MithrilClientSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  override def munitIOTimeout: Duration = 2.minutes

  import MithrilClient.{Error, ReceiptKind}
  private val root = MithrilClient.Artifact.parse("a" * 64).fold(e => fail(e.toString), identity)
  private val artifact = MithrilClient.artifact(BigInt(1), root)
  private val endpoint = MithrilClient.Endpoint
    .parse("http://127.0.0.1:1234/aggregator")
    .fold(e => fail(e.toString), identity)
  private val key = MithrilClient.VerificationKey
    .parse(Files.readString(JPath.of("fixtures/mempack/ancillary/preprod.vkey")))
    .fold(e => fail(e.toString), identity)
  private val roots = MithrilClient.Roots(key, key)
  private def quoted(text: String): String = "'" + text.replace("'", "'\"'\"'") + "'"
  private def sleepingChild(pidFile: Path, beforeWrite: String = ":"): String =
    val pending = quoted(pidFile.toString + ".pending")
    s"{\n$beforeWrite\nprintf '%s' \"$$$$\"\n} > $pending\n/bin/mv $pending ${quoted(pidFile.toString)}\nexec /bin/sleep 30"

  private def success(path: Path): String =
    Json
      .obj(
        "verified_db_directory" -> Json.fromString(path.toString),
        "certificate_network" -> Json.fromString("preview"),
        "certificate_hash" -> Json.fromString("b" * 64),
        "certificate_epoch" -> Json.fromInt(1),
        "certificate_merkle_root" -> Json.fromString("a" * 64)
      )
      .noSpaces
  private def attempt[A](body: Handle[IO, Error] ?=> IO[A]): IO[Either[Error, A]] =
    Handle.allow[Error](body.map(_.asRight[Error])).rescue(e => IO.pure(Left(e)))

  private def execute(
      root: Path,
      script: String,
      timeout: FiniteDuration = 5.seconds,
      digest: Option[Bytes] = None
  ): IO[Either[Error, MithrilClient.Verified]] =
    val original = root / "original-client"
    val work = root / "work"
    val database = root / "cached"
    val raw = s"#!/bin/sh\n$script\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)
    for
      _ <- IO.blocking {
        Files.createDirectories(work.toNioPath)
        Files.createDirectories(database.toNioPath)
        Files.write(original.toNioPath, raw)
        Files.setPosixFilePermissions(
          original.toNioPath,
          PosixFilePermissions.fromString("rwx------")
        )
      }
      expected = digest.getOrElse(Bytes.wrap(MessageDigest.getInstance("SHA-256").digest(raw)))
      request = MithrilClient.Request(
        artifact,
        endpoint,
        roots,
        MithrilClient.Network.Preview,
        MithrilClient.Executable(original, expected),
        MithrilClient.Source.Cached(database),
        timeout
      )
      result <- attempt(MithrilClient.official[IO].acquire(request, work))
    yield result

  test("exact artifact IDs and trusted transport endpoints exclude aliases and credentials") {
    assert(MithrilClient.Artifact.parse("latest").isLeft)
    assert(MithrilClient.Artifact.parse("A" * 64).isLeft)
    assert(MithrilClient.Endpoint.parse("https://example.test/aggregator").isRight)
    assert(MithrilClient.Endpoint.parse("http://example.test/aggregator").isLeft)
    assert(MithrilClient.Endpoint.parse("https://user:secret@example.test").isLeft)
    assert(MithrilClient.Endpoint.parse("https://example.test/#fragment").isLeft)
  }

  test("exit-zero immutable refusal is not a successful verification receipt") {
    val output = """{"timestamp":"test","verify_error":{"message":"failed"}}"""
    assertEquals(MithrilClient.receipt(output, ReceiptKind.Verify), Left(Error.RefusedVerification))
    assertEquals(
      MithrilClient.receipt(output + "\n" + success(Path("/db")), ReceiptKind.Verify),
      Left(Error.RefusedVerification)
    )
    StorageTestKit.tempDir.use(root =>
      execute(root, s"printf '%s\\n' ${quoted(output)}").map(result =>
        assertEquals(result, Left(Error.RefusedVerification))
      )
    )
  }

  test("only one explicit well-formed success receipt is accepted") {
    val one = success(Path("/db"))
    assertEquals(MithrilClient.receipt("{}", ReceiptKind.Verify), Left(Error.MissingReceipt))
    assertEquals(MithrilClient.receipt("not JSON", ReceiptKind.Verify), Left(Error.InvalidReceipt))
    assertEquals(
      MithrilClient.receipt(one + "\n" + one, ReceiptKind.Verify),
      Left(Error.AmbiguousReceipt)
    )
    assertEquals(MithrilClient.receipt(one, ReceiptKind.Download), Left(Error.MissingReceipt))
    assertEquals(MithrilClient.receipt(one, ReceiptKind.Verify).map(_.directory), Right("/db"))
  }

  test("a private hash-verified executable and an owned-directory receipt succeed") {
    StorageTestKit.tempDir.use { root =>
      execute(root, s"printf '%s\\n' ${quoted(success(root / "cached"))}").map { result =>
        assertEquals(result.map(_.directory), Right(root / "cached"))
        assert(Files.isExecutable((root / "work/mithril-client").toNioPath))
      }
    }
  }

  test("verified network and certificate-derived artifact must match the configured request") {
    StorageTestKit.tempDir.use { directory =>
      val receipt = success(directory / "network" / "cached")
      for
        wrongNetwork <- execute(
          directory / "network",
          s"printf '%s\\n' ${quoted(receipt.replace("preview", "preprod"))}"
        )
        wrongArtifact <- execute(
          directory / "artifact",
          s"printf '%s\\n' ${quoted(receipt.replace("\"certificate_epoch\":1", "\"certificate_epoch\":2"))}"
        )
      yield
        assertEquals(
          wrongNetwork,
          Left(Error.WrongNetwork(MithrilClient.Network.Preview, "preprod"))
        )
        assert(wrongArtifact.left.exists(_.isInstanceOf[Error.WrongArtifact]))
    }
  }

  test("a different executable digest is refused before launch") {
    StorageTestKit.tempDir.use { root =>
      val marker = root / "executed"
      execute(
        root,
        s"/usr/bin/touch ${quoted(marker.toString)}",
        digest = Some(Bytes.wrap(Array.fill(32)(0.toByte)))
      ).map { result =>
        assert(result.left.exists(_.isInstanceOf[Error.ExecutableDigest]))
        assert(!Files.exists(marker.toNioPath))
      }
    }
  }

  test("a successful receipt cannot redirect the importer outside its owned directory") {
    StorageTestKit.tempDir.use(root =>
      execute(root, s"printf '%s\\n' ${quoted(success(Path("/elsewhere")))}")
        .map(result => assert(result.left.exists(_.isInstanceOf[Error.WrongDirectory])))
    )
  }

  test("bounded capture drains both pipes without mistaking excess output for success") {
    StorageTestKit.tempDir.use(root =>
      execute(root, "/usr/bin/head -c 5000000 /dev/zero")
        .map(result => assertEquals(result, Left(Error.OutputLimit)))
    )
  }

  test("verbose progress diagnostics are drained with bounded retention") {
    StorageTestKit.tempDir.use(root =>
      execute(
        root,
        s"/usr/bin/head -c 5000000 /dev/zero >&2\nprintf '%s\\n' ${quoted(success(root / "cached"))}"
      )
        .map(result => assert(result.isRight))
    )
  }

  test("timeout releases the actual child process") {
    StorageTestKit.tempDir.use { root =>
      val pidFile = root / "pid"
      for
        result <- execute(
          root,
          sleepingChild(pidFile),
          250.millis
        )
        pid <- IO.blocking(Files.readString(pidFile.toNioPath).toLong)
        alive <- IO.blocking(java.lang.ProcessHandle.of(pid).stream().anyMatch(_.isAlive))
      yield
        assertEquals(result, Left(Error.TimedOut))
        assert(!alive, s"verifier child $pid survived its owner")
    }
  }

  test("PID readiness is published only after the complete PID has been written") {
    StorageTestKit.tempDir.use { root =>
      val pidFile = root / "ready-pid"
      val started = root / "writer-started"
      val gate = root / "allow-write"
      val beforeWrite =
        s"/usr/bin/touch ${quoted(started.toString)}\nwhile [ ! -f ${quoted(gate.toString)} ]; do /bin/sleep 0.01; done"
      Resource
        .make(execute(root, sleepingChild(pidFile, beforeWrite), 30.seconds).start)(_.cancel)
        .use { fiber =>
          for
            _ <- IO
              .blocking(Files.exists(started.toNioPath))
              .iterateUntil(identity)
              .timeout(5.seconds)
            published <- IO.blocking(Files.exists(pidFile.toNioPath))
            _ <- IO(
              assert(!published, "an incomplete PID was published while its writer was gated")
            )
            _ <- IO.blocking(Files.write(gate.toNioPath, Array.emptyByteArray)).void
            _ <- IO
              .blocking(Files.exists(pidFile.toNioPath))
              .iterateUntil(identity)
              .timeout(5.seconds)
            pid <- IO.blocking(Files.readString(pidFile.toNioPath).toLong)
            _ <- fiber.cancel
            outcome <- fiber.join
            alive <- IO.blocking(java.lang.ProcessHandle.of(pid).stream().anyMatch(_.isAlive))
          yield
            assert(outcome.isCanceled)
            assert(!alive, s"verifier child $pid survived cancellation")
        }
    }
  }

  test("cancelling the owner releases the actual child process") {
    StorageTestKit.tempDir.use { root =>
      val pidFile = root / "cancel-pid"
      Resource.make(execute(root, sleepingChild(pidFile), 30.seconds).start)(_.cancel).use {
        fiber =>
          for
            _ <- IO
              .blocking(Files.exists(pidFile.toNioPath))
              .iterateUntil(identity)
              .timeout(5.seconds)
            pid <- IO.blocking(Files.readString(pidFile.toNioPath).toLong)
            _ <- fiber.cancel
            outcome <- fiber.join
            alive <- IO.blocking(java.lang.ProcessHandle.of(pid).stream().anyMatch(_.isAlive))
          yield
            assert(outcome.isCanceled)
            assert(!alive, s"verifier child $pid survived cancellation")
      }
    }
  }

  test("a wide sequence of external receipt events is stack safe") {
    val output = Vector.fill(20000)("{}").mkString("\n") + "\n" + success(Path("/db"))
    val result =
      new java.util.concurrent.atomic.AtomicReference[Either[Error, MithrilClient.Receipt]]()
    val failure = new java.util.concurrent.atomic.AtomicReference[Throwable]()
    val run = new Runnable:
      def run(): Unit =
        try result.set(MithrilClient.receipt(output, ReceiptKind.Verify))
        catch case e: Throwable => failure.set(e)
    val thread = new Thread(null, run, "mithril-receipts-small-stack", 256 * 1024)
    thread.start()
    thread.join()
    assertEquals(Option(failure.get()), None)
    assertEquals(result.get().map(_.directory), Right("/db"))
  }
