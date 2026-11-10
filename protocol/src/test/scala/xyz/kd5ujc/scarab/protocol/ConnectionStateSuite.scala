// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import scala.annotation.unused
import xyz.kd5ujc.scarab.protocol.ConnectionState.*
import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.*

/** The connection states against the reference's own oracle: every move the pure model makes is one
  * `verifyAbstractTransition` accepts (ouroboros-network `2fb69829`,
  * `Test/Ouroboros/Network/ConnectionManager/Utils.hs`), and the counters are
  * `connectionStateToCounters` (`ConnectionManager/Core.hs:173-212`).
  */
class ConnectionStateSuite extends ScalaCheckSuite:
  private val provenance: Gen[Provenance] = Gen.oneOf(Provenance.Inbound, Provenance.Outbound)
  private val flow: Gen[DataFlow] = Gen.oneOf(DataFlow.Unidirectional, DataFlow.Duplex)
  private val event: Gen[Event] = Gen.oneOf(
    Gen.oneOf(
      Event.Connected,
      Event.Overwritten,
      Event.AwakeLocal,
      Event.PromotedToWarmLocal,
      Event.DemotedToColdLocal,
      Event.AwakeRemote,
      Event.DemotedToColdRemote,
      Event.TimeoutExpired,
      Event.Commit,
      Event.HandshakeFailed,
      Event.Fail,
      Event.Released,
      Event.SelfConn,
      Event.SelfConnReversed
    ),
    flow.map(Event.Negotiated(_)),
    flow.map(Event.SelfConnNegotiated(_))
  )

  /** A state reached from an initial one by events that were allowed. */
  private val reachable: Gen[ConnectionState] =
    for
      p <- provenance
      events <- Gen.listOf(event)
    yield events.foldLeft(ConnectionState.initial(p))((s, e) =>
      ConnectionState.transition(s, e).getOrElse(s)
    )

  @unused private given Arbitrary[ConnectionState] = Arbitrary(reachable)

  private val every: Vector[ConnectionState] =
    val flows = Vector(DataFlow.Unidirectional, DataFlow.Duplex)
    Vector(ReservedOutbound, OutboundUni, Duplex, Terminating, Terminated) ++
      Vector(Provenance.Inbound, Provenance.Outbound).map(Unnegotiated(_)) ++
      Vector(TimeoutExpired.Ticking, TimeoutExpired.Expired).map(OutboundDup(_)) ++
      flows.map(OutboundIdle(_)) ++ flows.map(InboundIdle(_)) ++ flows.map(Inbound(_))

  /** The moves that leave a state as it is: `demotedToColdRemote` on a connection the remote side
    * already left (`ConnectionManager/Core.hs:2286-2305`).
    */
  private def leavesAsIs(s: ConnectionState, e: Event): Boolean = (s, e) match
    case (OutboundDup(_) | OutboundIdle(_) | InboundIdle(_), Event.DemotedToColdRemote) => true
    case _                                                                              => false

  property("every move the model makes is one the reference accepts") {
    forAll(reachable, event) { (s, e) =>
      ConnectionState.transition(s, e) match
        case Right(next) if next == s => leavesAsIs(s, e)
        case Right(next)              => ConnectionState.valid(Some(s), Some(next))
        case Left(_)                  => true
    }
  }

  test("the identity moves are the reference's, and its oracle admits all but one") {
    every.foreach { s =>
      val result = ConnectionState.transition(s, Event.DemotedToColdRemote)
      if leavesAsIs(s, Event.DemotedToColdRemote) then
        assertEquals(result, Right(s), s.abstractName)
    }
    // the runtime leaves an idle unidirectional inbound connection as it is (`Core.hs:2302-2305`);
    // `verifyAbstractTransition` admits that identity for the duplex flow only
    assert(
      ConnectionState.valid(Some(InboundIdle(DataFlow.Duplex)), Some(InboundIdle(DataFlow.Duplex)))
    )
    assert(
      !ConnectionState.valid(
        Some(InboundIdle(DataFlow.Unidirectional)),
        Some(InboundIdle(DataFlow.Unidirectional))
      )
    )
  }

  test("self-connection: SelfConn, SelfConn⁻¹ and SelfConnNegotiated are the reference's edges") {
    val cases = Vector(
      (Unnegotiated(Provenance.Outbound), Event.SelfConn, Unnegotiated(Provenance.Inbound)),
      (Unnegotiated(Provenance.Inbound), Event.SelfConnReversed, Unnegotiated(Provenance.Outbound)),
      (InboundIdle(DataFlow.Unidirectional), Event.SelfConnReversed, OutboundUni),
      (InboundIdle(DataFlow.Duplex), Event.SelfConnReversed, OutboundDup(TimeoutExpired.Ticking)),
      (
        Unnegotiated(Provenance.Outbound),
        Event.SelfConnNegotiated(DataFlow.Duplex),
        InboundIdle(DataFlow.Duplex)
      ),
      (
        Unnegotiated(Provenance.Outbound),
        Event.SelfConnNegotiated(DataFlow.Unidirectional),
        InboundIdle(DataFlow.Unidirectional)
      )
    )
    cases.foreach { (from, e, to) =>
      assertEquals(ConnectionState.transition(from, e), Right(to), s"${from.abstractName} $e")
      assert(
        ConnectionState.valid(Some(from), Some(to)),
        s"${from.abstractName} -> ${to.abstractName}"
      )
    }
  }

  // `Core.hs` 936-945 makes this move; `verifyAbstractTransition` has no case for it
  test(
    "SelfConnIdle: the reference's runtime move from OutboundIdle to InboundIdle, which its verifier lacks"
  ) {
    DataFlow.values.foreach { flow =>
      assertEquals(
        ConnectionState.transition(OutboundIdle(flow), Event.SelfConnIdle),
        Right(InboundIdle(flow))
      )
      assert(!ConnectionState.valid(Some(OutboundIdle(flow)), Some(InboundIdle(flow))))
    }
    assert(ConnectionState.transition(InboundIdle(DataFlow.Duplex), Event.SelfConnIdle).isLeft)
  }

  test("outbound: Reserve, Connected and Negotiated^{flow}_{Outbound}") {
    assertEquals(
      ConnectionState.through(
        ConnectionState.initial(Provenance.Outbound),
        Vector(Event.Connected, Event.Negotiated(DataFlow.Duplex))
      ),
      Right(OutboundDup(TimeoutExpired.Ticking))
    )
    assertEquals(
      ConnectionState.through(
        ReservedOutbound,
        Vector(Event.Connected, Event.Negotiated(DataFlow.Unidirectional))
      ),
      Right(OutboundUni)
    )
  }

  test("inbound: Accepted and Negotiated^{flow}_{Inbound} leave the connection idle") {
    assertEquals(
      ConnectionState.transition(
        ConnectionState.initial(Provenance.Inbound),
        Event.Negotiated(DataFlow.Duplex)
      ),
      Right(InboundIdle(DataFlow.Duplex))
    )
  }

  test("reuse of an inbound duplex connection and the way back (Core.hs 1335-1370, 1889-1985)") {
    assertEquals(
      ConnectionState.transition(InboundIdle(DataFlow.Duplex), Event.AwakeLocal),
      Right(OutboundDup(TimeoutExpired.Ticking))
    )
    assertEquals(
      ConnectionState.transition(Inbound(DataFlow.Duplex), Event.PromotedToWarmLocal),
      Right(Duplex)
    )
    assertEquals(
      ConnectionState.transition(Duplex, Event.DemotedToColdLocal),
      Right(Inbound(DataFlow.Duplex))
    )
    assertEquals(
      ConnectionState.transition(OutboundDup(TimeoutExpired.Ticking), Event.DemotedToColdLocal),
      Right(InboundIdle(DataFlow.Duplex))
    )
    assertEquals(
      ConnectionState.transition(OutboundDup(TimeoutExpired.Expired), Event.DemotedToColdLocal),
      Right(OutboundIdle(DataFlow.Duplex))
    )
  }

  test("a unidirectional inbound connection cannot be reused") {
    assert(
      ConnectionState.transition(InboundIdle(DataFlow.Unidirectional), Event.AwakeLocal).isLeft
    )
    assert(
      ConnectionState.transition(Inbound(DataFlow.Unidirectional), Event.PromotedToWarmLocal).isLeft
    )
  }

  test("an event the state does not allow is refused") {
    assertEquals(
      ConnectionState.transition(Unnegotiated(Provenance.Inbound), Event.Connected),
      Left(Illegal(Unnegotiated(Provenance.Inbound), Event.Connected))
    )
    assert(ConnectionState.transition(OutboundUni, Event.Commit).isLeft)
    assert(ConnectionState.transition(Terminated, Event.Fail).isLeft)
  }

  test("the counters are the reference's connectionStateToCounters") {
    val D = DataFlow.Duplex
    val U = DataFlow.Unidirectional
    val z = Counters.zero
    val expected = Map[ConnectionState, Counters](
      ReservedOutbound -> z,
      Unnegotiated(Provenance.Inbound) -> z.copy(inbound = 1),
      Unnegotiated(Provenance.Outbound) -> z.copy(outbound = 1),
      OutboundUni -> z.copy(unidirectional = 1, outbound = 1),
      OutboundDup(TimeoutExpired.Ticking) -> z.copy(duplex = 1, outbound = 1),
      OutboundDup(TimeoutExpired.Expired) -> z.copy(duplex = 1, outbound = 1),
      OutboundIdle(U) -> z.copy(unidirectional = 1, outbound = 1),
      OutboundIdle(D) -> z.copy(duplex = 1, outbound = 1),
      InboundIdle(U) -> z.copy(unidirectional = 1, inbound = 1),
      InboundIdle(D) -> z.copy(duplex = 1, inbound = 1),
      Inbound(U) -> z.copy(unidirectional = 1, inbound = 1),
      Inbound(D) -> z.copy(duplex = 1, inbound = 1),
      Duplex -> z.copy(fullDuplex = 1, duplex = 1, inbound = 1, outbound = 1),
      Terminating -> z.copy(terminating = 1),
      Terminated -> z
    )
    assertEquals(expected.keySet, every.toSet)
    every.foreach(s => assertEquals(Counters.ofState(s), expected(s), s.abstractName))
  }

  property("the counters of a table are the sum of its states'") {
    forAll(Gen.listOf(reachable)) { states =>
      Counters.of(states) == states.map(Counters.ofState).foldLeft(Counters.zero)(_ + _)
    }
  }

  test("the reference's oracle refuses what it refuses") {
    assert(!ConnectionState.valid(Some(Terminated), Some(Terminated)))
    assert(!ConnectionState.valid(None, None))
    assert(!ConnectionState.valid(Some(OutboundUni), Some(Duplex)))
    assert(
      !ConnectionState.valid(
        Some(InboundIdle(DataFlow.Unidirectional)),
        Some(Inbound(DataFlow.Duplex))
      )
    )
    assert(ConnectionState.valid(Some(Terminating), Some(Unnegotiated(Provenance.Inbound))))
  }
