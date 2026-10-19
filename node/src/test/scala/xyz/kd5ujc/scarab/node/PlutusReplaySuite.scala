// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import cats.mtl.{Ask, Handle}
import cats.syntax.all.*
import fs2.io.file.Path
import io.circe.parser.parse
import java.nio.file.{Files, Path as JPath}
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, MirrorStep}
import xyz.kd5ujc.scarab.ledger.{Block, StorageError, ValidationError}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.storage.*

/** Optional recorded-data integration comparison. Input capture and baseline are mounted read-only;
  * only SCARAB_PLUTUS_OUTPUT is written. See docs/design/parallel-plutus.md for the capture
  * contract.
  */
class PlutusReplaySuite extends CatsEffectSuite:
  override def munitIOTimeout: Duration = 15.minutes

  test("serial and bounded parallel full block validation agree on a captured window") {
    assume(sys.env.contains("SCARAB_PLUTUS_CAPTURE"), "requires an offline captured window")
    val setup = NodeSetup
      .fromDirectory(JPath.of("fixtures/preprod/genesis"))
      .fold(e => fail(e.message), identity)
    given Ask[IO, ConsensusMirrorEnv] = Ask.const(setup.mirrorEnv())
    val root = JPath.of(sys.env("SCARAB_PLUTUS_CAPTURE"))
    val baseline = JPath.of(sys.env("SCARAB_PLUTUS_BASELINE"))
    val output = JPath.of(sys.env("SCARAB_PLUTUS_OUTPUT"))
    Files.createDirectories(output)
    val snapshotName = "snapshot-00000000000136511997-5300868"
    val snapshot = baseline.resolve(s"snapshots/$snapshotName.bin")
    val checkpoint = Path.fromNioPath(baseline.resolve(s"utxo/checkpoints/$snapshotName"))
    val timings = Vector.newBuilder[io.circe.Json]
    val entries = Files
      .readAllLines(root.resolve("records.jsonl"))
      .asScala
      .toVector
      .map { line =>
        parse(line).fold(e => fail(e.message), identity).hcursor
      }
      .filter(_.get[Long]("slot").fold(e => fail(e.message), identity) > 136511997L)
    val started = System.nanoTime()
    Handle
      .allow[StorageError] {
        RocksUtxoStore.resource[IO](Path.fromNioPath(output.resolve("replay-rocks"))).use { store =>
          for
            content <- IO.blocking(Files.readAllBytes(snapshot))
            verified <- IO(
              FileLedgerSnapshots.verified(content).fold(e => fail(e.message), identity)
            )
            loaded <- SnapshotV5.format[IO](SnapshotCheck.Full).decode(verified)
            initial <- IO(loaded.fold(e => fail(e.message), identity))
            restored <- store.restore(checkpoint)
            _ <- IO(assert(restored))
            workers <- Vector(1, 2, 4, 8).traverse(n =>
              ParallelPlutus
                .create[IO](ParallelPlutus.Workers.from(n).fold(e => fail(e.toString), identity))
                .map(n -> _)
            )
            bounded = workers.map { (n, service) =>
              n -> MirrorRules[IO](
                setup.praos,
                setup.globals,
                setup.byronRules,
                setup.handOver,
                setup.byronHistory,
                utxoStore = Some(store),
                parallelPlutus = Some(service)
              )
            }.toMap
            rules = bounded.updated(
              0,
              MirrorRules[IO](
                setup.praos,
                setup.globals,
                setup.byronRules,
                setup.handOver,
                setup.byronHistory,
                utxoStore = Some(store)
              )
            )
            after <- Handle
              .allow[ValidationError] {
                entries.zipWithIndex.foldLeftM(initial.state) { case (state, (entry, i)) =>
                  val chunk = entry.get[String]("chunk").fold(e => fail(e.message), identity)
                  val offset = entry.get[Long]("offset").fold(e => fail(e.message), identity)
                  val length = entry.get[Int]("length").fold(e => fail(e.message), identity)
                  for
                    raw <- IO.blocking {
                      val channel =
                        java.nio.channels.FileChannel.open(root.resolve("immutable").resolve(chunk))
                      try
                        val buffer = java.nio.ByteBuffer.allocate(length)
                        channel.position(offset)
                        while buffer.hasRemaining do assert(channel.read(buffer) > 0)
                        Bytes.fromArray(buffer.array())
                      finally channel.close()
                    }
                    block <- IO(Block.decodeShelley(raw).fold(e => fail(e.message), identity))
                    reference <- rules(0)(state, block.header, raw)
                    _ <- (0 until 3).toVector.traverse_ { round =>
                      val order = Vector(0, 1, 2, 4, 8)
                      (order.drop((i + round) % 5) ++ order.take((i + round) % 5)).traverse_ { n =>
                        for
                          began <- IO.monotonic
                          actual <- rules(n)(state, block.header, raw)
                          ended <- IO.monotonic
                          _ <- IO {
                            assertEquals(actual, reference, s"slot=${block.header.slot} workers=$n")
                            timings += io.circe.Json.obj(
                              "slot" -> io.circe.Json.fromLong(block.header.slot.toLong),
                              "round" -> io.circe.Json.fromInt(round),
                              "workers" -> io.circe.Json.fromInt(n),
                              "nanos" -> io.circe.Json.fromLong((ended - began).toNanos)
                            )
                          }
                        yield ()
                      }
                    }
                    next = reference
                    _ <- if i % 128 == 127 then UtxoStore.advance(store, next.utxo) else IO.unit
                    stamp <- store.flushed
                    settled = MirrorStep.mapUtxo(next)(_.settled(stamp))
                    _ <- IO(
                      println(
                        s"PARALLEL_REPLAY blocks=${i + 1} slot=${block.header.slot} scripts=${settled.plutus.scripts}"
                      )
                    ).whenA(i % 500 == 499)
                  yield settled
                }
              }
              .rescue(e => IO(fail(e.message)))
            _ <- IO {
              val json = io.circe.Json.obj(
                "blocks" -> io.circe.Json.fromInt(entries.size),
                "initialScripts" -> io.circe.Json.fromLong(initial.state.plutus.scripts),
                "finalScripts" -> io.circe.Json.fromLong(after.plutus.scripts),
                "seconds" -> io.circe.Json.fromDoubleOrNull((System.nanoTime() - started) / 1e9)
              )
              Files.writeString(output.resolve("replay-summary.json"), json.spaces2)
              Files.writeString(
                output.resolve("timings.json"),
                io.circe.Json.arr(timings.result()*).noSpaces
              )
              println(json.noSpaces)
            }
          yield ()
        }
      }
      .rescue(e => IO(fail(e.message)))
  }
