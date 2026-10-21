// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Rational

/** Explicit boundary inputs executed by the unchanged pinned native governor, including invalid
  * intermediate proposals and their native timeout outcomes. No native result is excluded.
  */
class NativeChurnPolicySuite extends FunSuite:
  private val path = Path.of("fixtures/peer-selection/churn/decisions.jsonl")

  private def proposal(values: List[Int]): Churn.Proposal = values match
    case List(root, known, established, active, knownBig, establishedBig, activeBig) =>
      Churn.Proposal(root, known, established, active, knownBig, establishedBig, activeBig)
    case other => fail(s"invalid target record: $other")

  private def target(values: List[Int]): Targets =
    proposal(values).validated.fold(e => fail(e.toString), identity)

  private val emptyCounters = GovernorState
    .initial[Int](0L, LocalRoots.empty, Targets.zero, Sensitivity.initial(false))
    .view(Duration.Zero)
    .counters

  private def counters(values: List[Int]): GovernorState.Counters =
    val t = proposal(values)
    emptyCounters.copy(
      root = t.root,
      known = t.known,
      established = t.established,
      active = t.active,
      knownBigLedger = t.knownBig,
      establishedBigLedger = t.establishedBig,
      activeBigLedger = t.activeBig
    )

  private def fetches(name: String): List[FetchMode] = name match
    case "PraosFetchMode FetchModeBulkSync" => List(FetchMode.BulkSync)
    case "PraosFetchMode FetchModeDeadline" => List(FetchMode.Deadline)
    case "GenesisFetchMode"                 => List(FetchMode.BulkSync, FetchMode.Deadline)
    case other                              => fail(s"unknown native fetch mode $other")

  private def bootstrap(name: String): Boolean = name match
    case "DontUseBootstrapPeers" => false
    case "UseBootstrapPeers []"  => true
    case other                   => fail(s"unknown bootstrap configuration $other")

  private def judgement(name: String): LedgerStateJudgement = name match
    case "TooOld"      => LedgerStateJudgement.TooOld
    case "YoungEnough" => LedgerStateJudgement.YoungEnough
    case other         => fail(s"unknown ledger judgement $other")

  private val actions = List(
    "DecreasedActiveBigLedgerPeers",
    "IncreasedActiveBigLedgerPeers",
    "DecreasedEstablishedBigLedgerPeers",
    "DecreasedKnownBigLedgerPeers",
    "IncreasedKnownBigLedgerPeers",
    "IncreasedEstablishedBigLedgerPeers",
    "DecreasedActivePeers",
    "IncreasedActivePeers",
    "DecreasedEstablishedPeers",
    "DecreasedKnownPeers",
    "IncreasedKnownPeers",
    "IncreasedEstablishedPeers"
  )

  test("native startup, transitions, exact waits and all first-cycle proposals agree") {
    val rows = Files.readAllLines(path).asScala.toList
    assertEquals(rows.size, 1152)
    val coverage = rows.zipWithIndex.flatMap { (line, index) =>
      val c = parse(line).fold(e => fail(e.message), identity).hcursor
      def get[A: io.circe.Decoder](field: String): A =
        c.get[A](field).fold(e => fail(s"case $index $field: ${e.message}"), identity)
      val consensus = get[String]("consensus") match
        case "PraosMode"   => ConsensusMode.PraosMode
        case "GenesisMode" => ConsensusMode.GenesisMode
        case other         => fail(s"unknown consensus mode $other")
      val deadline = target(get[List[Int]]("deadline"))
      val sync = target(get[List[Int]]("sync"))
      val localHot = get[Int]("localHot")
      val base = Churn.targetsFor(consensus, judgement(get[String]("judgement")), deadline, sync)
      val afterBase =
        Churn.targetsFor(consensus, judgement(get[String]("afterJudgement")), deadline, sync)
      for
        beforeFetch <- fetches(get[String]("fetch"))
        afterFetch <- fetches(get[String]("afterFetch"))
      yield
        val initialKind = Regime.kind(consensus, beforeFetch, bootstrap(get[String]("bootstrap")))
        val initial = Regime.initial(consensus, initialKind, base, localHot)
        assertEquals(initial, target(get[List[Int]]("initial")), s"case $index initial")
        val afterKind = Regime.kind(consensus, afterFetch, bootstrap(get[String]("afterBootstrap")))
        val after = Regime.afterHead(afterKind, afterBase, Churn.Proposal.from(initial))
        assertEquals(after, proposal(get[List[Int]]("afterHead")), s"case $index after head")
        val context = Churn.Context(afterKind, localHot, afterBase, consensus)
        val steps = get[List[io.circe.Json]]("steps")
        assertEquals(steps.size, Churn.Step.values.length)
        val (_, _, invalid, timeouts, elapsed) = steps
          .zip(Churn.Step.values.toList)
          .zip(actions)
          .foldLeft(
            (after, Map.empty[Churn.Step, Int], 0, 0, BigInt(0))
          ) { case ((current, achieved, invalid, timeouts, elapsed), ((entry, step), action)) =>
            val e = entry.hcursor
            def field[A: io.circe.Decoder](name: String): A =
              e.get[A](name).fold(err => fail(err.message), identity)
            assertEquals(field[String]("action"), action, s"case $index order")
            val expected = proposal(field[List[Int]]("proposal"))
            val actual = Churn.planRaw(step, context, current, s => achieved.getOrElse(s, 0))
            assertEquals(actual, expected, s"case $index $step")
            assertEquals(
              actual.validated.isRight,
              field[Boolean]("sane"),
              s"case $index $step sanity"
            )
            val (beforeCounters, afterCounters) = field[List[List[Int]]]("counters") match
              case List(before, after) => counters(before) -> counters(after)
              case other => fail(s"case $index $step unexpected counter reads: $other")
            val change = step.counter(afterCounters) - step.counter(beforeCounters)
            assertEquals(change, field[Int]("change"), s"case $index $step signed change")
            val timedOut = !step.reached(afterCounters, actual)
            assertEquals(timedOut, field[Boolean]("timeout"), s"case $index $step timeout")
            val expectedDuration =
              if timedOut then
                BigInt(step.timeout(Churn.Timeouts(peerRequest = 5.seconds)).toNanos) * 1000
              else BigInt(0)
            assertEquals(
              field[BigInt]("durationPicos"),
              expectedDuration,
              s"case $index $step duration"
            )
            (
              actual,
              achieved.updated(step, change.abs),
              invalid + (if field[Boolean]("sane") then 0 else 1),
              timeouts + (if timedOut then 1 else 0),
              elapsed + expectedDuration
            )
          }
        val waits = get[List[io.circe.Json]]("waits")
        assertEquals(waits.size, 2)
        val actualWaits =
          waits.zip(List(BigInt(3) * BigInt(10).pow(12), elapsed)).map { (entry, expectedElapsed) =>
            val w = entry.hcursor
            def field[A: io.circe.Decoder](name: String): A =
              w.get[A](name).fold(err => fail(err.message), identity)
            assertEquals(
              field[BigInt]("elapsedPicos"),
              expectedElapsed,
              s"case $index wait elapsed"
            )
            Churn.delayWithFuzz(
              consensus,
              afterFetch,
              Churn.Intervals(),
              expectedElapsed,
              Rational(field[BigInt]("fuzzNumerator"), field[BigInt]("fuzzDenominator"))
            )
          }
        val nativeWaits =
          waits.map(_.hcursor.get[BigInt]("delayPicos").fold(err => fail(err.message), identity))
        assertEquals(actualWaits, nativeWaits, s"case $index complete waits")
        invalid -> timeouts
    }
    assert(coverage.exists(_._1 > 0))
    assert(coverage.exists(_._2 > 0))
  }
