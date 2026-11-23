// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.node.preview.{EraBodies, RecordedReplay, ReplayInputs}

/** The preprod capture of `PlutusReplaySuite` (the same inputs, environment variables and pinned
  * baseline), each Conway block's BBODY timed four ways from the state the mirror ticks it to: the
  * flat rules' and Conway's era run's, each serial and through four of the node's workers
  * (`EraBodies`). The reference state advances by the original serial interpreter, timed once a
  * block. Opt-in offline evidence, as `PlutusReplaySuite` is.
  */
class ConwayBodiesReplaySuite extends FailsOnUncaught:
  override def munitIOTimeout: Duration = 40.minutes

  test(
    "the flat rules' and the era run's bodies, serial and through four workers, on a captured window"
  ) {
    assume(sys.env.contains("SCARAB_PLUTUS_CAPTURE"), "requires an offline captured window")
    val baseline = Path.of(sys.env("SCARAB_PLUTUS_BASELINE"))
    val output = Path.of(sys.env("SCARAB_PLUTUS_OUTPUT"))
    val name = "snapshot-00000000000136511997-5300868"
    val workers = ParallelPlutus.Workers.from(4).fold(e => fail(e.toString), identity)
    val config = ReplayInputs.Config(
      capture = Path.of(sys.env("SCARAB_PLUTUS_CAPTURE")),
      snapshot = baseline.resolve(s"snapshots/$name.bin"),
      checkpoint = baseline.resolve(s"utxo/checkpoints/$name"),
      output = output,
      genesis = Path.of("fixtures/preprod/genesis"),
      workers = Vector.empty,
      rounds = 1,
      reference = None,
      trace = false,
      legacyOutputs = true,
      schedule = ReplayInputs.Schedule.RotatedMeasurements,
      eraBodies = Some(
        EraBodies.Timing(
          workers,
          sys.env.get("SCARAB_PLUTUS_ROUNDS").flatMap(_.toIntOption).getOrElse(3)
        )
      )
    )
    for
      summary <- RecordedReplay.replay(config)
      rows <- IO.blocking(
        Files
          .readAllLines(output.resolve("era-bodies.jsonl"))
          .asScala
          .toVector
          .map(line => io.circe.parser.parse(line).fold(e => fail(e.message), identity))
      )
      totals = EraBodies.Way.values.toVector.map { way =>
        // the median of a block's rounds, summed over the blocks
        val byBlock = rows.groupBy(_.hcursor.downField("slot").as[BigInt].toOption)
        val nanos = byBlock.values.toVector.map { rounds =>
          val sorted = rounds
            .flatMap(_.hcursor.downField("nanos").downField(way.toString).as[Long].toOption)
            .sorted
          sorted.lift(sorted.size / 2).getOrElse(0L)
        }
        way.toString -> (nanos.sum / 1e9).asJson
      }
      disagreeing = rows.filterNot(
        _.hcursor.downField("eraAgreesWithFlat").as[Boolean].contains(true)
      )
      report = Json.obj(
        "replay" -> summary,
        "conwayBlocks" -> rows
          .flatMap(_.hcursor.downField("slot").as[BigInt].toOption)
          .distinct
          .size
          .asJson,
        "medianSecondsSummed" -> Json.obj(totals*),
        "eraDiffersFromFlatAtSlots" -> disagreeing
          .flatMap(_.hcursor.downField("slot").as[BigInt].toOption)
          .distinct
          .asJson
      )
      _ <- IO.blocking(Files.writeString(output.resolve("era-bodies-summary.json"), report.spaces2))
      _ <- IO.println(report.noSpaces)
    yield ()
  }
