// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO, Resource}
import cats.effect.unsafe.implicits.global
import java.nio.file.{Files, Path}
import lab.ledger.runtime.NioReplayStore
import scala.jdk.CollectionConverters.*

class DurableReplayCommandSuite extends munit.FunSuite:
  private val packet =
    val local = Path.of("fixtures/restricted-replay")
    if Files.isDirectory(local) then local else Path.of("../fixtures/restricted-replay")
  private def temporary =
    Resource.make(IO.blocking(Files.createTempDirectory("durable-cli-")))(root =>
      IO.blocking {
        val stream = Files.walk(root)
        try
          stream
            .iterator()
            .asScala
            .toVector
            .sortBy(_.getNameCount)
            .reverse
            .foreach(p => Files.delete(p))
        finally stream.close()
      }
    )
  test("init inspect atomic commit stale retry and rollback command round trip") {
    temporary
      .use { root =>
        val directory = root.resolve("store")
        val checkpoint = root.resolve("checkpoint.trace.tsv")
        val batch = root.resolve("batch.trace.tsv")
        for
          _ <- IO.blocking {
            val rows = Files
              .readString(packet.resolve("value-conservation.trace.tsv"))
              .linesIterator
              .toVector
            Files.writeString(checkpoint, rows.head + "\n")
            Files.writeString(batch, rows.take(2).mkString("\n") + "\n")
          }
          initialized <- DurableReplayCommand.run(
            List("init", directory.toString, checkpoint.toString)
          )
          _ = assertEquals(initialized, ExitCode.Success)
          inspected <- DurableReplayCommand.run(List("inspect", directory.toString))
          _ = assertEquals(inspected, ExitCode.Success)
          before <- NioReplayStore.open[IO](directory).use(_.snapshot)
          args = List("commit", directory.toString, "0", before.version.head.hex, batch.toString)
          committed <- DurableReplayCommand.run(args)
          _ = assertEquals(committed, ExitCode.Success)
          repeated <- DurableReplayCommand.run(args)
          _ = assertEquals(repeated, ExitCode(2))
          after <- NioReplayStore.open[IO](directory).use(_.snapshot)
          _ = assertEquals(after.state.feesSinceCheckpoint, BigInt(167041))
          rolled <- DurableReplayCommand.run(
            List(
              "rollback",
              directory.toString,
              "1",
              after.version.head.hex,
              after.undo.get.transitionId.hex
            )
          )
          _ = assertEquals(rolled, ExitCode.Success)
          restored <- NioReplayStore.open[IO](directory).use(_.snapshot)
          _ = assertEquals(restored.state.stateId, before.state.stateId)
          _ = assertEquals(restored.state.revision.number, BigInt(2))
          rejected <- DurableReplayCommand.run(
            List(
              "commit",
              directory.toString,
              "2",
              restored.version.head.hex,
              packet.resolve("value-conservation.trace.tsv").toString
            )
          )
          _ = assertEquals(rejected, ExitCode.Error)
          unchanged <- NioReplayStore.open[IO](directory).use(_.snapshot)
          _ = assertEquals(unchanged.version.head, restored.version.head)
        yield ()
      }
      .unsafeToFuture()
  }
  test("CLI requires explicit fences and checkpoint-only init; malformed store is exit five") {
    temporary
      .use { root =>
        for
          withEvents <- DurableReplayCommand.run(
            List(
              "init",
              root.resolve("store").toString,
              packet.resolve("value-conservation.trace.tsv").toString
            )
          )
          _ = assertEquals(withEvents, ExitCode(2))
          missing <- DurableReplayCommand.run(List("inspect", root.resolve("absent").toString))
          _ = assertEquals(missing, ExitCode(5))
          absentFence <- DurableReplayCommand.run(
            List("commit", root.toString, packet.resolve("value-conservation.trace.tsv").toString)
          )
          _ = assertEquals(absentFence, ExitCode(2))
          noncanonical <- DurableReplayCommand.run(
            List("rollback", root.toString, "00", "0" * 64, "0" * 64)
          )
          _ = assertEquals(noncanonical, ExitCode(2))
          help <- DurableReplayCommand.run(List("--help"))
          _ = assertEquals(help, ExitCode.Success)
        yield ()
      }
      .unsafeToFuture()
  }
