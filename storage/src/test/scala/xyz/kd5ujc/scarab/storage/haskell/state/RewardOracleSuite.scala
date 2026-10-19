// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import io.circe.{Json, parser}
import munit.FunSuite
import scala.io.Source
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.rules.{EraNes, RewardPulserFixture, Rewards}
import xyz.kd5ujc.scarab.ledger.state.*

/** Actual unchanged Haskell API results; only locally missing newly computed likelihoods are
  * masked.
  */
class RewardOracleSuite extends FunSuite:
  import HaskellStateCodecs.pulsingRewUpdate
  import RewardOracleCases.Operation
  given ProtVer = Versions.eraLow(ShelleyBasedEra.SConway)

  private def load(name: String): Vector[Json] =
    val stream = Option(getClass.getClassLoader.getResourceAsStream(s"rewards-1.19.0.1/$name.gz"))
      .getOrElse(fail(s"missing oracle resource $name"))
    val source = Source.fromInputStream(new java.util.zip.GZIPInputStream(stream))
    val text =
      try source.mkString
      finally source.close()
    parser.parse(text).flatMap(_.as[Vector[Json]]).fold(e => fail(e.toString), identity)

  private def field[A: io.circe.Decoder](json: Json, name: String): A =
    json.hcursor.get[A](name).fold(e => fail(e.toString), identity)

  private def readState(json: Json, name: String): PulsingRewUpdate =
    val bytes = Bytes.fromHex(field[String](json, name)).fold(e => fail(e.toString), identity)
    pulsingRewUpdate.decodeBytes(bytes, HaskellState.limits).fold(e => fail(e.render), identity)

  private def accounting(state: PulsingRewUpdate): PulsingRewUpdate = state match
    case PulsingRewUpdate.Pulsing(snapshot, p) =>
      PulsingRewUpdate.Pulsing(snapshot.copy(likelihoods = Sorted.empty), p)
    case PulsingRewUpdate.Complete(r) =>
      PulsingRewUpdate.Complete(r.copy(nonMyopic = r.nonMyopic.copy(likelihoods = Sorted.empty)))

  test("oracle inputs remain bound to the exact shared deterministic fixtures") {
    assertEquals(
      Json.arr(RewardOracleCases.inputs.map(RewardOracleCases.json)*),
      Json.arr(load("inputs.json")*)
    )
  }

  test("start, every pulse and completion match the unchanged released Haskell API") {
    val reference = load("results.json")
    assertEquals(reference.size, RewardOracleCases.inputs.size)
    RewardOracleCases.inputs.zip(reference).foreach { (input, expected) =>
      assertEquals(field[String](expected, "name"), input.name)
      val initial = input.state match
        case None => input.pending.getOrElse(fail("missing imported state"))
        case Some(i: EraNes.InEra[e]) =>
          given ShelleyBasedEra[e] = i.era
          Rewards
            .start(i.nes.es, i.nes.bprev, RewardPulserFixture.globals, i.nes.epoch.toBigInt)
            .fold(e => fail(e.toString), identity)
      val mask = (s: PulsingRewUpdate) => if input.state.isDefined then accounting(s) else s
      def equal(got: PulsingRewUpdate, want: PulsingRewUpdate): Unit =
        assertEquals(
          pulsingRewUpdate.write(mask(got)).fold(e => fail(e.toString), identity),
          pulsingRewUpdate.write(mask(want)).fold(e => fail(e.toString), identity),
          input.name
        )
      equal(initial, readState(expected, "initial"))
      val transitions = field[Vector[Json]](expected, "transitions")
      assertEquals(transitions.size, input.operations.size)
      input.operations.zip(transitions).foldLeft(initial) { case (previous, (operation, row)) =>
        val got = (operation match
          case Operation.Pulse    => Rewards.pulse(previous)
          case Operation.Complete => Rewards.complete(previous).map(PulsingRewUpdate.Complete(_))
        )
          .fold(e => fail(e.toString), identity)
        equal(got, readState(row, "state"))
        got match
          case PulsingRewUpdate.Pulsing(_, pulser) =>
            val event = Wire
              .mapOf(HaskellStateCodecs.credential[Staking], HaskellStateCodecs.rewardSet)
              .write(pulser.answer.recent)
              .fold(e => fail(e.toString), identity)
            assertEquals(event.hex, field[String](row, "event"), input.name)
          case _ => ()
        got
      }
    }
  }

  test("typed pending state round trips the Haskell layout and resumes the captured prefix") {
    val row = load("results.json")
      .find(j => field[String](j, "name") == "conway-v10")
      .getOrElse(fail("no case"))
    val initial = readState(row, "initial")
    val bytes = pulsingRewUpdate.write(initial).fold(e => fail(e.toString), identity)
    val resumed =
      pulsingRewUpdate.decodeBytes(bytes, HaskellState.limits).fold(e => fail(e.render), identity)
    assertEquals(Rewards.pulse(resumed), Rewards.pulse(initial))
    assertEquals(Rewards.complete(resumed), Rewards.complete(initial))
  }
