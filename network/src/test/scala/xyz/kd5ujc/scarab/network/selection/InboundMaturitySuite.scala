// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import munit.FunSuite
import scala.collection.immutable.SortedMap
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.DuplexPeers

/** The outbound governor's inbound peers are the inbound governor's mature duplex peers
  * (`readInboundPeers`, `Diffusion.hs` 277-282 and 651-654; `inboundMaturePeerDelay`,
  * `InboundGovernor.hs` 84-85; ouroboros-network `c45735a5`), so an address that no other source
  * made known, and whose connection is less than 900 s old, never reaches the known set through
  * inbound discovery, nor through it the answers of peer sharing. Here the inbound governor takes a
  * step at each governor pass.
  */
class InboundMaturitySuite extends FunSuite:
  private val policy = Policy()

  private def state(honest: Int): GovernorState[Int] =
    GovernorState
      .initial[Int](7L, LocalRoots.empty, Targets.relay, Sensitivity.initial(false))
      .copy(
        targets = Targets.relay,
        peers = SortedMap.from((1 to honest).map(p => p -> PeerRecord.cold(Origin.Other))),
        sharing = PeerShare.Governor(enabled = true)
      )

  private final case class Round(at: FiniteDuration, hostileKnown: Int, hostileShareable: Int)

  /** A governor pass every `period`; before each, `connect` gives the hostile connections the
    * inbound governor registers then, and `held` says whether a connection opened at the first time
    * is still open at the second. The governor is shown `inbound` of the duplex peers: the mature
    * ones, as the reference shows them.
    */
  private def run(
      rounds: Int,
      period: FiniteDuration,
      connect: Int => Vector[Int],
      held: (FiniteDuration, FiniteDuration) => Boolean,
      inbound: DuplexPeers[Int, Boolean] => Map[Int, Boolean] = _.mature
  ): Vector[Round] =
    val start = (state(50), DuplexPeers.empty[Int, Boolean], Map.empty[Int, FiniteDuration])
    (0 until rounds)
      .foldLeft((start, Vector.empty[Round])) { case (((st, duplex, opened), log), i) =>
        val now = period * i.toLong
        val closed = opened.filter((_, at) => !held(at, now)).keySet
        val fresh = connect(i).filterNot(opened.contains)
        val registered = fresh.foldLeft(closed.foldLeft(duplex)(_.unregistered(_))) { (d, p) =>
          d.registered(p, now, true)
        }
        // a step of the inbound governor at the pass: `maturedPeers` and `MaturedDuplexPeers`
        val matured = registered.maturedPeers(now) match
          case (due, rest) if due.nonEmpty => registered.matured(due, rest)
          case _                           => registered
        val next = PeerShare.belowTarget(st, now, policy, inbound(matured)) match
          case PeerSelection.Guard.Go(d) => d.state
          case _                         => st
        val shareable = PeerShare
          .shareable(next.peers.toVector.map { (p, r) =>
            PeerShare.Candidate(p, r.advertise.contains(true), r.everConnected, r.failCount)
          })
          .count(_ >= 1000)
        val stillOpen = (opened -- closed) ++ fresh.map(_ -> now)
        (
          (next, matured, stillOpen),
          log :+ Round(now, next.peers.count((p, _) => p >= 1000), shareable)
        )
      }
      ._2

  test("connections held for less than 900 s put nothing in the known set or in peer sharing") {
    // every 60 s ten new connections, each gone a round later
    val connect = (i: Int) => Vector.tabulate(10)(j => 1000 + i * 10 + j)
    val held = (at: FiniteDuration, now: FiniteDuration) => now - at < 60.seconds
    val rounds = run(rounds = 30, period = 60.seconds, connect, held)
    assertEquals(rounds.map(_.hostileKnown).max, 0, s"hostile known peers: $rounds")
    assertEquals(rounds.map(_.hostileShareable).max, 0, s"hostile shareable peers: $rounds")
    // the same connections shown fresh, as when every registered peer was offered at once
    val unripe = run(
      rounds = 30,
      period = 60.seconds,
      connect,
      held,
      d => d.mature ++ d.fresh.map { case (peer, (_, sharing)) => peer -> sharing }
    )
    assertEquals(
      unripe.find(_.at == 540.seconds).map(r => (r.hostileKnown, r.hostileShareable)),
      Some((100, 100)),
      "shown at once, they fill every free known slot within ten passes, all shareable"
    )
  }

  test("connections held from the start are offered at the first governor pass at 900 s") {
    val rounds = run(
      rounds = 20,
      period = 60.seconds,
      connect = i => if i == 0 then Vector.tabulate(10)(1000 + _) else Vector.empty,
      held = (_, _) => true
    )
    val before = rounds.takeWhile(_.at < 900.seconds)
    assertEquals(before.map(_.hostileKnown).max, 0, s"known before 900 s: $before")
    assertEquals(
      rounds.find(_.at == 900.seconds).map(_.hostileKnown),
      Some(10),
      s"at 900 s the ten mature inbound peers are picked (maxInboundPeers): $rounds"
    )
  }
