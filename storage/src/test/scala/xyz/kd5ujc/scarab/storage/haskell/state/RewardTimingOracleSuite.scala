// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import io.circe.Json
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.rules.{
  EraNes,
  NewEpoch,
  RewardPulserFixture,
  Rewards,
  Rupd,
  RupdEnv,
  Sts
}
import xyz.kd5ujc.scarab.ledger.state.*

/** Observable reward availability and accounting, independent of internal pulse partitioning. */
class RewardTimingOracleSuite extends FunSuite:
  import RewardTimingCases.*
  private val reference = load("results.json")
  private val oracleVersion = Versions.eraLow(ShelleyBasedEra.SConway)

  test("RUPD inputs remain bound to the exact deterministic era fixtures and slot schedules") {
    assertEquals(Json.arr(cases.map(_.json)*), Json.arr(load("inputs.json")*))
    assertEquals(reference.size, cases.size)
    assertEquals(cases.size, 55)
  }

  cases.zipWithIndex.foreach { (sample, index) =>
    test(s"native RUPD availability and completed accounting: ${sample.name}") {
      val expected = reference(index)
      assertEquals(field[String](expected, "name"), sample.name)
      val rows = field[Vector[Json]](expected, "timing")
      assertEquals(rows.map(field[BigInt](_, "slot")), sample.slots)
      val wanted = complete(expected)
      val native = sample.input.state.getOrElse(fail("RUPD needs an epoch state"))
      native match
        case i: EraNes.InEra[e] =>
          given ShelleyBasedEra[e] = i.era
          def nativeEpoch(row: Json): EpochState[e] =
            val bytes = Bytes
              .fromHex(field[String](row, "appliedEpochState"))
              .fold(e => fail(e.toString), identity)
            HaskellStateCodecs
              .epochState[e](using i.era, oracleVersion)
              .decodeBytes(bytes, HaskellState.limits)
              .fold(e => fail(e.render), identity)
          val nativeCredited = nativeEpoch(rows.lastOption.getOrElse(fail("empty native schedule")))
          val actual = sample.slots.zip(rows).foldLeft(Option.empty[PulsingRewUpdate]) {
            case (previous, (slot, row)) =>
              val signal = SlotNo.fromBigInt(slot).fold(e => fail(e.toString), identity)
              val next = Sts(Rupd.sts[e])(
                RewardPulserFixture.globals,
                RupdEnv(i.nes.bprev, i.nes.es),
                previous,
                signal
              ).fold(e => fail(e.toString), identity)
              (next, field[Option[String]](row, "state").map(state)) match
                case (None, None) => ()
                case (Some(got), Some(nativeUpdate)) =>
                  val completed = Rewards.complete(got).fold(e => fail(e.toString), identity)
                  assertEquals(completed, wanted)
                  nativeUpdate match
                    case PulsingRewUpdate.Complete(update) =>
                      assertEquals(completed, update)
                    case _: PulsingRewUpdate.Pulsing => ()
                  val force = RewardPulserFixture.globals.firstSlot(i.nes.epoch.toBigInt) +
                    2 * RewardPulserFixture.globals.randomnessStabilisationWindow
                  // Work partitioning and early completion inside the interval may differ.
                  // Completion is mandatory on the first observed slot strictly past force.
                  if slot > force then assert(got.isInstanceOf[PulsingRewUpdate.Complete])
                  val applied = NewEpoch
                    .applyRewardUpdate(i.nes.es, completed)
                    .fold(e => fail(e.toString), identity)
                  val nativeApplied = field[Option[String]](row, "appliedEpochState")
                    .fold(nativeCredited)(_ => nativeEpoch(row))
                  // The era codec omits fields in the common Scala parameter record that
                  // this era does not have (for example Conway's old minUTxOValue).
                  val codec = HaskellStateCodecs.epochState[e](using i.era, oracleVersion)
                  def written(es: EpochState[e]): Bytes =
                    codec.write(es).fold(e => fail(e.toString), identity)
                  assertEquals(written(applied), written(nativeApplied))
                  got match
                    case p: PulsingRewUpdate.Pulsing =>
                      val nextEpoch = EpochNo
                        .fromBigInt(i.nes.epoch.toBigInt + 1)
                        .fold(e => fail(e.toString), identity)
                      val boundary = Sts(NewEpoch.sts[e])(
                        RewardPulserFixture.globals,
                        (),
                        i.nes.copy(ru = Some(p)),
                        nextEpoch
                      ).fold(e => fail(e.toString), identity)
                      // EPOCH also rotates snapshots/parameters. Its delivered accounting
                      // must match the independent native applyRUpd result.
                      assertEquals(boundary.ru, None)
                      assertEquals(boundary.es.chainAccount, nativeCredited.chainAccount)
                      assertEquals(boundary.es.ls.utxo.fees, nativeCredited.ls.utxo.fees)
                      assertEquals(
                        xyz.kd5ujc.scarab.ledger.rules.EraLedger.accounts(boundary.es.ls).toVector,
                        xyz.kd5ujc.scarab.ledger.rules.EraLedger
                          .accounts(nativeCredited.ls)
                          .toVector
                      )
                    case _: PulsingRewUpdate.Complete => ()
                case other => fail(s"reward availability differs at $slot: $other")
              next
          }
          assert(actual.exists(_.isInstanceOf[PulsingRewUpdate.Complete]))
          if sample.input.name != "empty-active" then
            assert(wanted.rs.nonEmpty, "positive control must exercise attributed rewards")
    }
  }
