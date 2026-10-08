// SPDX-License-Identifier: Apache-2.0
package lab.ledger.runtime

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import ReplayFixtures.*

/** Real process termination, not power-cut emulation. The classpath is resolved independently of
  * the sbt launcher and any pre-generated application classpath.
  */
class ReplayProcessInterruptionSuite extends munit.FunSuite:
  override val munitTimeout = 120.seconds
  test("process-integration runtime profile and actual classpath are explicit") {
    val runtime = Runtime.getRuntime
    if java.lang.Boolean.getBoolean("cardano.replay.test.forked") then
      assertEquals(runtime.availableProcessors(), 2)
      assert(runtime.maxMemory() <= 512L * 1048576)
    println(
      s"replay-test-processors=${runtime.availableProcessors()} replay-test-max-memory=${runtime.maxMemory()} forked=${java.lang.Boolean.getBoolean("cardano.replay.test.forked")}"
    )
    assert(ReplayProcessClasspath.current.nonEmpty)
  }
  private def runProbe(
      root: Path,
      phase: NioReplayStore.Phase,
      action: String,
      exitCode: Int
  ): ReplayProbeOutput.Captured =
    val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString
    val command = Vector(
      javaBin,
      "-XX:ActiveProcessorCount=2",
      "-Xmx256m",
      "-cp",
      ReplayProcessClasspath.current,
      "lab.ledger.runtime.ReplayInterruptionProbe",
      root.toAbsolutePath.toString,
      phase.toString,
      action,
      packet.resolve(a).toAbsolutePath.toString
    )
    val process = new ProcessBuilder(command*).redirectErrorStream(true).start()
    val drain = new ReplayProbeOutput(process.getInputStream)
    var cleanupDone = false
    var forced = false
    var exited = false
    val cleanup = new AutoCloseable:
      def close(): Unit =
        if !cleanupDone then
          cleanupDone = true
          NioReplayStore.closing(process.getInputStream) { _ =>
            NioReplayStore.closing(process.getOutputStream) { _ =>
              NioReplayStore.closing(process.getErrorStream) { _ =>
                if process.isAlive then
                  forced = true
                  process.destroyForcibly()
                  if !process.waitFor(5, TimeUnit.SECONDS) then
                    throw new java.io.IOException(
                      "owned child did not terminate after forced cleanup"
                    )
                exited = !process.isAlive
                drain.finish()
                ()
              }
            }
          }
    NioReplayStore.closing(cleanup) { _ =>
      val completed = process.waitFor(30, TimeUnit.SECONDS)
      val cleanupFailure = scala.util.Try(cleanup.close()).failed.toOption
      val output = drain.snapshot
      if !completed then
        val error = new java.io.IOException(
          s"child process timeout; command=${command.mkString(" ")}; forced=$forced; exited=$exited; retained=${output.retainedBytes}; total=${output.totalBytes}\n${output.rendered}"
        )
        cleanupFailure.foreach(error.addSuppressed)
        throw error
      cleanupFailure.foreach(throw _)
      assertEquals(process.exitValue(), exitCode, output.rendered)
      assert(output.text.contains("probe-processors=2"), output.rendered)
      output
    }
  test("an independent JVM cannot acquire an already owned directory") {
    directory
      .use { root =>
        NioReplayStore.create[IO](root, checkpoint).use { store =>
          for
            _ <- IO.blocking(runProbe(root, NioReplayStore.Phase.BeforeObjectWrite, "lock", 92))
            current <- store.snapshot
            _ = assertEquals(current.state.revision.number, BigInt(0))
          yield ()
        }
      }
      .unsafeToFuture()
  }
  test("child output beyond pipe capacity is drained with bounded retention") {
    directory
      .use { root =>
        IO.blocking {
          val output = runProbe(root, NioReplayStore.Phase.BeforeObjectWrite, "flood", 93)
          assert(output.totalBytes >= 1048576L)
          assertEquals(output.retainedBytes, ReplayProbeOutput.MaxRetained)
          assert(output.droppedBytes > 0)
          assert(output.rendered.contains("probe-output-truncated"))
          println(
            s"probe-output-retained=${output.retainedBytes} total=${output.totalBytes} dropped=${output.droppedBytes}"
          )
        }
      }
      .unsafeToFuture()
  }
  for phase <- NioReplayStore.Phase.values do
    for undo <- Vector(false, true) do
      test(s"process halt at $phase during ${
          if undo then "undo" else "apply"
        } preserves one complete head") {
        directory
          .use { root =>
            for
              before <- NioReplayStore.create[IO](root, checkpoint).use { store =>
                store.snapshot.flatMap { snapshot =>
                  if undo then store.commit(snapshot.version, Vector(raw(a))).flatMap(good)
                  else IO.pure(snapshot)
                }
              }
              _ <- IO.blocking {
                val output = runProbe(root, phase, if undo then "undo" else "apply", 91)
                if phase == NioReplayStore.Phase.BeforeObjectWrite && !undo then
                  println(output.rendered.trim)
              }
              _ <- NioReplayStore.open[IO](root).use { store =>
                for
                  after <- store.snapshot
                  isNew = NioReplayStore.Phase.values.indexOf(phase) >= NioReplayStore.Phase.values
                    .indexOf(NioReplayStore.Phase.HeadReplaced)
                  expectedRevision = before.state.revision.number + (if isNew then 1 else 0)
                  expectedFees =
                    if undo then (if isNew then 0 else 167041) else (if isNew then 167041 else 0)
                  _ = assertEquals(after.state.revision.number, expectedRevision)
                  _ = assertEquals(after.state.feesSinceCheckpoint, BigInt(expectedFees))
                  _ = assertEquals(after.undo.nonEmpty, expectedFees != 0)
                  _ <-
                    if expectedFees != 0 then
                      store.commit(after.version, Vector(raw(a))).map { repeated =>
                        assert(repeated.isLeft, "repeating an uncertain commit cannot charge twice")
                      }
                    else IO.unit
                yield ()
              }
            yield ()
          }
          .unsafeToFuture()
      }
