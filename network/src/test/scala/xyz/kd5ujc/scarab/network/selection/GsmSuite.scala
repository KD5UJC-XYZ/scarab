// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import munit.FunSuite
import scala.concurrent.duration.*

import Gsm.*

/** The Genesis State Machine against the transitions of `GSM.hs` (ouroboros-consensus 4.2.1.0). */
class GsmSuite extends FunSuite:
  private val config = Config()
  private val idle = PeerReading(idle = true, Candidate.Better(false))
  private val busy = PeerReading(idle = false, Candidate.Better(false))

  private def reading(
      haa: Boolean = true,
      peers: Vector[PeerReading] = Vector(idle),
      selection: Long = 1L,
      age: Option[DurationFromNow] = Some(DurationFromNow.After(10.minutes))
  ) = Reading(haa, peers, selection, age)

  private def fresh(markerPresent: Boolean = false, now: Time = Duration.Zero) =
    Gsm.initial(markerPresent, Some(DurationFromNow.After(1.minute)), now, seed = 7L)

  test("the judgement of the ledger is too old until the node is caught up") {
    assertEquals(Gsm.judgement(Phase.PreSyncing), LedgerStateJudgement.TooOld)
    assertEquals(Gsm.judgement(Phase.Syncing), LedgerStateJudgement.TooOld)
    assertEquals(Gsm.judgement(Phase.CaughtUp), LedgerStateJudgement.YoungEnough)
  }

  test("a node without the marker starts pre-syncing; with it, caught up unless the tip is old") {
    assertEquals(fresh().phase, Phase.PreSyncing)
    assertEquals(fresh(markerPresent = true).phase, Phase.CaughtUp)
    assertEquals(
      Gsm.initial(true, Some(DurationFromNow.Already), Duration.Zero, 1L).phase,
      Phase.PreSyncing
    )
    // a chain with no age limit stays caught up
    assertEquals(Gsm.initial(true, None, Duration.Zero, 1L).phase, Phase.CaughtUp)
  }

  test("the honest availability assumption moves pre-syncing to syncing and back") {
    val a = Gsm.step(fresh(), reading(haa = false), 1.second, config)
    assertEquals((a.state.phase, a.events), (Phase.PreSyncing, Nil))
    val b = Gsm.step(a.state, reading(), 2.seconds, config)
    assertEquals((b.state.phase, b.events), (Phase.Syncing, List(Event.PreSyncingToSyncing)))
    val c = Gsm.step(b.state, reading(haa = false), 3.seconds, config)
    assertEquals((c.state.phase, c.events), (Phase.PreSyncing, List(Event.SyncingToPreSyncing)))
  }

  private def syncing = Gsm.step(fresh(), reading(), Duration.Zero, config).state

  test("syncing becomes caught up only when there is a peer, all are idle and none is better") {
    def phaseWith(r: Reading) = Gsm.step(syncing, r, 5.seconds, config).state.phase
    assertEquals(phaseWith(reading(peers = Vector.empty)), Phase.Syncing)
    assertEquals(phaseWith(reading(peers = Vector(idle, busy))), Phase.Syncing)
    assertEquals(
      phaseWith(reading(peers = Vector(PeerReading(true, Candidate.Better(true))))),
      Phase.Syncing
    )
    assertEquals(
      phaseWith(reading(peers = Vector(PeerReading(true, Candidate.DoesNotIntersect)))),
      Phase.Syncing
    )
    val done = Gsm.step(syncing, reading(peers = Vector(idle, idle)), 5.seconds, config)
    assertEquals((done.state.phase, done.events), (Phase.CaughtUp, List(Event.EnterCaughtUp(2))))
    assertEquals(done.mark, Some(true))
  }

  test("caught up stays at least the minimum duration, whatever the age of the tip") {
    val up = Gsm.step(syncing, reading(), 10.seconds, config).state
    val old = reading(age = Some(DurationFromNow.Already))
    assertEquals(Gsm.step(up, old, 10.minutes, config).events, Nil)
    assertEquals(Gsm.step(up, old, 20.minutes + 9.seconds, config).events, Nil)
    val left = Gsm.step(up, old, 20.minutes + 10.seconds, config)
    assertEquals(left.events, List(Event.LeaveCaughtUp(DurationFromNow.Already)))
    assertEquals((left.state.phase, left.mark), (Phase.PreSyncing, Some(false)))
  }

  test("caught up leaves when the tip is older than the limit plus the bonus, once per selection") {
    val up = Gsm.step(syncing, reading(), Duration.Zero, config).state
    val at = 21.minutes
    val first = Gsm.step(
      up,
      reading(selection = 1L, age = Some(DurationFromNow.After(3.minutes))),
      at,
      config
    )
    assertEquals(first.events, Nil)
    val deadline = first.state.watching.get._2
    assertEquals(deadline, at + 3.minutes + up.bonus)
    assert(up.bonus >= Duration.Zero && up.bonus <= 300.seconds)
    // before the deadline nothing happens
    val before = Gsm.step(
      first.state,
      reading(selection = 1L, age = Some(DurationFromNow.After(1.minute))),
      deadline - 1.second,
      config
    )
    assertEquals(before.events, Nil)
    // a new selection counts down again
    val moved = Gsm.step(
      first.state,
      reading(selection = 2L, age = Some(DurationFromNow.After(3.minutes))),
      deadline - 1.second,
      config
    )
    assertEquals(moved.state.watching.map(_._1), Some(2L))
    assertEquals(Gsm.step(moved.state, reading(selection = 2L), deadline, config).events, Nil)
    // the unchanged selection ends at its deadline
    val gone = Gsm.step(
      first.state,
      reading(selection = 1L, age = Some(DurationFromNow.After(1.minute))),
      deadline,
      config
    )
    assertEquals(gone.state.phase, Phase.PreSyncing)
  }

  test("a chain with no age limit stays caught up for ever") {
    val up = Gsm.step(syncing, reading(), Duration.Zero, config).state
    assertEquals(Gsm.step(up, reading(age = None), 100.days, config).events, Nil)
  }

  // ------------------------------------------------------------------ fetch mode

  test("the fetch mode is bulk sync while the node requires bootstrap peers or is far behind") {
    val now = Some(BigInt(100000))
    assertEquals(FetchMode.of(true, now, Some(BigInt(99999))), FetchMode.BulkSync)
    assertEquals(FetchMode.of(false, now, Some(BigInt(99001))), FetchMode.Deadline)
    assertEquals(FetchMode.of(false, now, Some(BigInt(99000))), FetchMode.BulkSync)
    assertEquals(FetchMode.of(false, None, Some(BigInt(99999))), FetchMode.BulkSync)
    assertEquals(FetchMode.of(false, Some(BigInt(0)), None), FetchMode.Deadline)
    assertEquals(FetchMode.of(false, Some(BigInt(5000)), None), FetchMode.BulkSync)
  }
