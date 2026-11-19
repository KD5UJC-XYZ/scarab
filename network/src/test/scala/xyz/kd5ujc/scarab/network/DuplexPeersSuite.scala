// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import scala.concurrent.duration.*

/** The inbound governor's fresh and mature duplex peers (`freshDuplexPeers`, `matureDuplexPeers`,
  * `maturedPeers`, the `MaturedDuplexPeers` event and `unregisterConnection`; `InboundGovernor.hs`
  * and `InboundGovernor/State.hs` at ouroboros-network `c45735a5`, `4646039f` and `2f1c154f`, which
  * agree on all of it).
  */
class DuplexPeersSuite extends ScalaCheckSuite:
  private val delay = 900.seconds
  private def empty = DuplexPeers.empty[Int, Boolean]

  /** A step of the inbound governor that handles no event: `maturedPeers` at `time`, and the
    * `MaturedDuplexPeers` event when any peer is due.
    */
  private def step(peers: DuplexPeers[Int, Boolean], time: FiniteDuration) =
    peers.maturedPeers(time) match
      case (due, rest) if due.nonEmpty => peers.matured(due, rest)
      case _                           => peers

  test("the reference's constants: inboundMaturePeerDelay = 15 * 60, inactionTimeout = 31.415927") {
    assertEquals(DuplexPeers.MatureDelay, delay)
    assertEquals(DuplexPeers.InactionTimeout.toNanos, 31415927000L)
  }

  test("a duplex peer is fresh from its step and due 900 s later, not a nanosecond sooner") {
    val at = 5.seconds
    val registered = empty.registered(1, at, true)
    assertEquals(registered.fresh, Map(1 -> (at, true)), "registered as fresh")
    assertEquals(registered.mature, Map.empty[Int, Boolean], "nothing is public at once")
    val (early, kept) = registered.maturedPeers(at + delay - 1.nanosecond)
    assertEquals(early, Map.empty[Int, Boolean], "not due 1 ns before 900 s")
    assertEquals(kept, registered.fresh, "still fresh 1 ns before 900 s")
    val (due, rest) = registered.maturedPeers(at + delay)
    assertEquals(due, Map(1 -> true), "due at 900 s (atMostView takes times <= time - delay)")
    val matured = registered.matured(due, rest)
    assertEquals(matured.mature, Map(1 -> true), "public once the step matures it")
    assertEquals(matured.fresh, Map.empty[Int, (FiniteDuration, Boolean)], "no longer fresh")
  }

  test("unregistering a peer removes it, fresh or mature") {
    val peers =
      step(empty.registered(1, Duration.Zero, true).registered(2, 600.seconds, false), delay)
    assertEquals(peers.mature, Map(1 -> true))
    assertEquals(peers.fresh, Map(2 -> (600.seconds, false)))
    assertEquals(peers.unregistered(1).unregistered(2), empty)
  }

  test(
    "another connection of a fresh peer's address restarts its maturation; of a mature one leaves it public"
  ) {
    // `OrdPSQ.insert` of a new connection's peer replaces the time of a fresh one (every pin)
    val again = empty.registered(1, Duration.Zero, true).registered(1, 600.seconds, true)
    assertEquals(step(again, delay).mature, Map.empty[Int, Boolean], "not public at 900 s")
    assertEquals(step(again, 600.seconds + delay).mature, Map(1 -> true), "public at 1500 s")
    val mature = step(empty.registered(1, Duration.Zero, false), delay)
    val twice = mature.registered(1, 1000.seconds, true)
    assertEquals(twice.mature, Map(1 -> false), "a mature peer stays public")
    assertEquals(twice.fresh, Map(1 -> (1000.seconds, true)), "and is fresh again as well")
    assertEquals(
      step(twice, 1000.seconds + delay).mature,
      Map(1 -> true),
      "the newly mature data replaces the older (the left-biased union of the reference)"
    )
  }

  private enum Op:
    case Register(peer: Int, sharing: Boolean)
    case Unregister(peer: Int)
    case Step
    case Pass(by: FiniteDuration)

  private val ops: Gen[List[Op]] =
    val op = Gen.frequency(
      4 -> Gen.zip(Gen.choose(1, 4), Gen.oneOf(true, false)).map(Op.Register.apply),
      2 -> Gen.choose(1, 4).map(Op.Unregister.apply),
      3 -> Gen.const(Op.Step),
      4 -> Gen
        .oneOf(1.nanosecond, 1.second, 300.seconds, 600.seconds, delay - 1.nanosecond, delay)
        .map(Op.Pass.apply)
    )
    Gen.listOf(op)

  /** What a run leaves: the peers and, for each peer registered and not unregistered since, the
    * times of those registrations; the times of the maturation steps.
    */
  private final case class Run(
      now: FiniteDuration,
      peers: DuplexPeers[Int, Boolean],
      live: Map[Int, List[FiniteDuration]],
      steps: List[FiniteDuration]
  )

  private def run(ops: List[Op]): Run =
    ops.foldLeft(Run(Duration.Zero, empty, Map.empty, Nil)) { (r, op) =>
      op match
        case Op.Register(peer, sharing) =>
          r.copy(
            peers = r.peers.registered(peer, r.now, sharing),
            live = r.live.updated(peer, r.now :: r.live.getOrElse(peer, Nil))
          )
        case Op.Unregister(peer) => r.copy(peers = r.peers.unregistered(peer), live = r.live - peer)
        case Op.Step             => r.copy(peers = step(r.peers, r.now), steps = r.now :: r.steps)
        case Op.Pass(by)         => r.copy(now = r.now + by)
    }

  property("no peer is public before one of its registrations is 900 s old at a maturation step") {
    Prop.forAll(ops) { ops =>
      val r = run(ops)
      val premature = r.peers.mature.keySet.filterNot(peer =>
        r.live.getOrElse(peer, Nil).exists(at => r.steps.exists(step => at <= step - delay))
      )
      Prop(premature.isEmpty) :| s"public before 900 s: $premature after $ops"
    }
  }

  property("after a step no fresh peer is 900 s old, and every live peer is kept") {
    Prop.forAll(ops) { ops =>
      val r = run(ops)
      val stepped = step(r.peers, r.now)
      val stale = stepped.fresh.filter { case (_, (since, _)) => since <= r.now - delay }
      val kept = stepped.mature.keySet ++ stepped.fresh.keySet
      (Prop(stale.isEmpty) :| s"still fresh at 900 s: $stale") &&
      (Prop(
        kept == r.live.keySet
      ) :| s"kept $kept, registered and not unregistered ${r.live.keySet}")
    }
  }
