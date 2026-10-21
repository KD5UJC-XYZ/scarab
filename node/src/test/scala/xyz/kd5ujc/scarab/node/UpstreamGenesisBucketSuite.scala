// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import io.circe.{Decoder, Json}
import io.circe.parser.parse
import munit.FunSuite
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.network.genesis.LeakyBucket

/** Complete states and empty events from native LeakyBucket.runAgainstBucket under IOSim. */
class UpstreamGenesisBucketSuite extends FunSuite:
  private def field[A: Decoder](j: Json, name: String): A =
    j.hcursor.get[A](name).fold(e => fail(s"$name: $e"), identity)
  private def rational(j: Json): Rational =
    Rational(field[BigInt](j, "numerator"), field[BigInt](j, "denominator"))
  private def config(j: Json): LeakyBucket.Config = LeakyBucket.Config(
    rational(field[Json](j, "capacity")),
    rational(field[Json](j, "rate")),
    field[Boolean](j, "fillOnOverflow")
  )
  private final case class Simulation(
      state: LeakyBucket.State,
      wake: Option[BigInt],
      emptyTimes: Vector[BigInt]
  )
  private def nextWake(state: LeakyBucket.State): Option[BigInt] =
    state.waitMicros.map(micros => state.timePicos + micros.max(0) * 1000000)

  /** Native fills and pauses retain the existing timer; only a configuration change restarts it. */
  @tailrec private def elapse(sim: Simulation, until: BigInt): Simulation = sim.wake match
    case Some(time) if time <= until =>
      val state = sim.state.snapshot(time)
      if state.expired then Simulation(state, None, sim.emptyTimes :+ time)
      else elapse(Simulation(state, nextWake(state), sim.emptyTimes), until)
    case _ => sim

  private def run(row: Json): Simulation =
    val initial = LeakyBucket.initial(config(row), 0)
    val result = field[Vector[Json]](row, "events")
      .foldLeft(Simulation(initial, nextWake(initial), Vector.empty)) { (before, event) =>
        val time = field[BigInt](event, "timePicos")
        field[String](event, "tag") match
          case "wait" =>
            val elapsed = elapse(before, time)
            elapsed.copy(state = elapsed.state.snapshot(time))
          case "fill" =>
            before.copy(state = before.state.fill(rational(field[Json](event, "amount")), time)._1)
          case "pause" =>
            before.copy(state = before.state.setPaused(field[Boolean](event, "paused"), time))
          case "configure" =>
            val state =
              before.state.reconfigure(config(event), rational(field[Json](event, "level")), time)
            before.copy(state = state, wake = nextWake(state))
          case other => fail(s"unknown native bucket event $other")
      }
    result

  private val rows = Files
    .readString(Path.of("fixtures/reference/genesis-density/bucket.jsonl"))
    .linesIterator
    .map(line => parse(line).fold(e => fail(e.message), identity))
    .toVector

  test("every native event prefix remains in the corpus") {
    assertEquals(rows.size, 40)
    assertEquals(rows.map(field[String](_, "label")).distinct.size, 40)
  }

  rows.foreach { row =>
    test(field[String](row, "label")) {
      val actual = run(row)
      val expected = field[Json](row, "state")
      assertEquals(actual.state.level, rational(field[Json](expected, "level")))
      assertEquals(actual.state.timePicos, field[BigInt](expected, "timePicos"))
      assertEquals(actual.state.paused, field[Boolean](expected, "paused"))
      assertEquals(actual.state.generation, field[BigInt](expected, "generation"))
      assertEquals(actual.state.config, config(expected))
      assertEquals(actual.emptyTimes, field[Vector[BigInt]](row, "emptyTimesPicos"))
    }
  }
