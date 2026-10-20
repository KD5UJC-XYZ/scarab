// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import io.circe.{Decoder, Json, parser}
import java.nio.file.{Files, Path}
import munit.Assertions
import scala.io.Source
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.rules.{EraNes, RewardPulserFixture}
import xyz.kd5ujc.scarab.ledger.state.*

/** Shared deterministic RUPD schedules; their results come from the unchanged native STS. */
object RewardTimingCases extends Assertions:
  private given ProtVer = Versions.eraLow(ShelleyBasedEra.SConway)
  final case class Schedule(name: String, offsets: Vector[Int])
  final case class Case(input: RewardOracleCases.Input, schedule: Schedule):
    def name: String = s"${input.name}/${schedule.name}"
    def slots: Vector[BigInt] =
      val first = input.state.getOrElse(fail("RUPD needs an epoch state")) match
        case i: EraNes.InEra[e] => RewardPulserFixture.globals.firstSlot(i.nes.epoch.toBigInt)
      schedule.offsets.map(first + _)
    def json: Json = RewardOracleCases
      .json(input)
      .deepMerge(
        Json.obj(
          "name" -> Json.fromString(name),
          "operations" -> Json.arr(Json.fromString("complete")),
          "rewardWindow" -> Json
            .fromBigInt(RewardPulserFixture.globals.randomnessStabilisationWindow),
          "rewardSlots" -> Json.arr(slots.map(Json.fromBigInt)*)
        )
      )

  val schedules: Vector[Schedule] = Vector(
    Schedule("dense", Vector(400) ++ (401 until 413) ++ Vector(800, 801, 9999)),
    Schedule("force-inclusive", Vector(400, 401, 800, 801, 9999)),
    Schedule("late-first", Vector(400, 801, 9999)),
    Schedule("sparse", Vector(401, 799, 801, 9999)),
    Schedule("first-at-force", Vector(800, 801, 9999))
  )
  val cases: Vector[Case] = RewardOracleCases.inputs.filter(_.state.nonEmpty).flatMap { input =>
    schedules.map(Case(input, _))
  }

  def load(name: String): Vector[Json] =
    val stream =
      Option(getClass.getClassLoader.getResourceAsStream(s"rewards-timing-1.19.0.1/$name.gz"))
        .getOrElse(fail(s"missing reward timing resource $name"))
    val source = Source.fromInputStream(new java.util.zip.GZIPInputStream(stream))
    val text =
      try source.mkString
      finally source.close()
    parser.parse(text).flatMap(_.as[Vector[Json]]).fold(e => fail(e.toString), identity)

  def field[A: Decoder](json: Json, name: String): A =
    json.hcursor.get[A](name).fold(e => fail(e.toString), identity)

  def state(hex: String): PulsingRewUpdate =
    val bytes = Bytes.fromHex(hex).fold(e => fail(e.toString), identity)
    HaskellStateCodecs.pulsingRewUpdate
      .decodeBytes(bytes, HaskellState.limits)
      .fold(e => fail(e.render), identity)

  def complete(row: Json): RewardUpdate =
    val transitions = field[Vector[Json]](row, "transitions")
    assertEquals(transitions.size, 1)
    state(field[String](transitions.head, "state")) match
      case PulsingRewUpdate.Complete(update) => update
      case _ => fail("native completeStep returned a pending reward update")

  def main(args: Array[String]): Unit =
    Files.writeString(Path.of(args(0)), Json.arr(cases.map(_.json)*).spaces2)
