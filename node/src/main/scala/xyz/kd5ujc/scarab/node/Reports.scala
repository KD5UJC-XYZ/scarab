// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import scala.collection.immutable.SortedMap
import io.circe.Json
import io.circe.syntax.*
import xyz.kd5ujc.scarab.consensus.SchedulingReport
import xyz.kd5ujc.scarab.node.forge.ForgeEpochStats

/** The JSON of the forger's and the scheduler's counters, as the query server serves them and as
  * the epoch lines of the log carry them.
  */
object Reports:
  /** The counters of one epoch (or of the whole run), see [[ForgeEpochStats]]. */
  def forgeStats(s: ForgeEpochStats): List[(String, Json)] =
    List(
      "slotsChecked" -> s.slotsChecked.asJson,
      "leaderSlots" -> s.leaderSlots.asJson,
      "forged" -> s.forged.asJson,
      "adopted" -> s.adopted.asJson,
      "lostSlotBattles" -> s.lost.asJson,
      "failed" -> s.failed.asJson,
      "skipped" -> s.skippedTotal.asJson,
      "skippedByReason" -> s.skipped.toList
        .map((reason, count) => reason.key -> count)
        .sortBy(_._1)
        .toMap
        .asJson,
      "forgeLatencyMs" -> Json.obj(
        "count" -> s.latencyCount.asJson,
        "mean" -> (if s.latencyCount == 0 then Json.Null
                   else (s.latencyTotalMs.toDouble / s.latencyCount).asJson),
        "max" -> (if s.latencyCount == 0 then Json.Null else s.latencyMaxMs.asJson)
      )
    )

  /** The forger's counters per epoch and for the run, served at `/forging`. */
  def forgeReport(byEpoch: SortedMap[BigInt, ForgeEpochStats]): Json =
    val total = byEpoch.values.foldLeft(ForgeEpochStats())(_.merge(_))
    Json.obj(
      "total" -> Json.obj(forgeStats(total)*),
      "epochs" -> Json.obj(byEpoch.toList.map((e, s) => e.toString -> Json.obj(forgeStats(s)*))*)
    )

  /** The scheduling measurements of a window (one epoch of the log), see [[SchedulingReport]]. */
  def scheduling(r: SchedulingReport): Json =
    val longest = r.longest
    Json.obj(
      "timerSamples" -> r.samples.asJson,
      "maxTimerLatenessMs" -> r.maxTimerLatenessMs.asJson,
      "timersLateOver50Ms" -> r.timersLateOver50.asJson,
      "timersLateOver100Ms" -> r.timersLateOver100.asJson,
      "cpuSteps" -> r.stepCount.asJson,
      "longestStepMs" -> longest.fold(0L)(_._2.maxMs).asJson,
      "longestStep" -> longest.map(_._1).asJson
    )

  /** The measurements of the whole run, with every step label, for the query server. */
  def schedulingTotal(r: SchedulingReport): Json =
    scheduling(r).deepMerge(
      Json.obj(
        "steps" -> Json.obj(r.steps.toList.sortBy(_._1).map { (label, step) =>
          label -> Json.obj(
            "count" -> step.count.asJson,
            "maxMs" -> step.maxMs.asJson,
            "totalMs" -> step.totalMs.asJson
          )
        }*)
      )
    )
