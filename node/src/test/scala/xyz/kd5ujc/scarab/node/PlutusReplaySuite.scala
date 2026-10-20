// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import java.nio.file.Path
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.node.preview.{RecordedReplay, ReplayInputs}

/** The original preprod capture contract, executed by the shared recorded-window runner. */
class PlutusReplaySuite extends FailsOnUncaught:
  override def munitIOTimeout: Duration = 15.minutes

  test("serial and bounded parallel full block validation agree on a captured window") {
    assume(sys.env.contains("SCARAB_PLUTUS_CAPTURE"), "requires an offline captured window")
    val baseline = Path.of(sys.env("SCARAB_PLUTUS_BASELINE"))
    val name = "snapshot-00000000000136511997-5300868"
    val config = ReplayInputs.Config(
      capture = Path.of(sys.env("SCARAB_PLUTUS_CAPTURE")),
      snapshot = baseline.resolve(s"snapshots/$name.bin"),
      checkpoint = baseline.resolve(s"utxo/checkpoints/$name"),
      output = Path.of(sys.env("SCARAB_PLUTUS_OUTPUT")),
      genesis = Path.of("fixtures/preprod/genesis"),
      workers = Vector(1, 2, 4, 8).map(n =>
        ParallelPlutus.Workers.from(n).fold(e => fail(e.toString), identity)
      ),
      rounds = 3,
      reference = None,
      trace = false,
      legacyOutputs = true,
      schedule = ReplayInputs.Schedule.RotatedMeasurements
    )
    RecordedReplay.replay(config).flatMap(summary => IO.println(summary.noSpaces))
  }
