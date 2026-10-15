// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import scala.annotation.unused
import xyz.kd5ujc.scarab.protocol.ConnectionState.*
import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.*

class ConnectionStateSuite extends ScalaCheckSuite:
  private val provenance: Gen[Provenance] = Gen.oneOf(Provenance.Inbound, Provenance.Outbound)
  private val flow: Gen[DataFlow] = Gen.oneOf(DataFlow.Unidirectional, DataFlow.Duplex)
  private val event: Gen[Event] = Gen.oneOf(
    Gen.const(Event.Connected),
    flow.map(Event.Negotiated(_)),
    Gen.const(Event.Close),
    Gen.const(Event.Released)
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

  test("an outbound connection goes reserved, unnegotiated, established") {
    val steps = List(Event.Connected, Event.Negotiated(DataFlow.Duplex))
    val end = steps.foldLeft[Either[Illegal, ConnectionState]](Right(Reserved))((s, e) =>
      s.flatMap(ConnectionState.transition(_, e))
    )
    assertEquals(end, Right(Established(Provenance.Outbound, DataFlow.Duplex)))
  }

  test("an inbound connection is negotiated straight from unnegotiated") {
    assertEquals(
      ConnectionState.transition(
        ConnectionState.initial(Provenance.Inbound),
        Event.Negotiated(DataFlow.Unidirectional)
      ),
      Right(Established(Provenance.Inbound, DataFlow.Unidirectional))
    )
  }

  test("only a duplex inbound connection and any established outbound one may run our clients") {
    val table = for
      p <- Provenance.values.toList
      f <- DataFlow.values.toList
    yield (p, f) -> Established(p, f).canRunClients
    assertEquals(
      table.toMap,
      Map(
        (Provenance.Inbound, DataFlow.Unidirectional) -> false,
        (Provenance.Inbound, DataFlow.Duplex) -> true,
        (Provenance.Outbound, DataFlow.Unidirectional) -> true,
        (Provenance.Outbound, DataFlow.Duplex) -> true
      )
    )
    assert(!Reserved.canRunClients && !Unnegotiated(Provenance.Inbound).canRunClients)
  }

  property("every live state can be closed, and closing is idempotent") {
    forAll(reachable) { s =>
      val closed = ConnectionState.transition(s, Event.Close)
      closed.isRight && closed.flatMap(ConnectionState.transition(_, Event.Close)) == closed &&
      (!s.isLive || closed == Right(Terminating))
    }
  }

  property("nothing but Close is allowed once terminating, and Terminated is final") {
    forAll(reachable, event) { (s, e) =>
      val result = ConnectionState.transition(s, e)
      s match
        case Terminating =>
          e match
            case Event.Close | Event.Released => result.isRight
            case _                            => result.isLeft
        case Terminated =>
          e match
            case Event.Close => result == Right(Terminated)
            case _           => result.isLeft
        case _ => true
    }
  }

  property("a transition never changes the direction of a live connection") {
    forAll(reachable, event) { (s, e) =>
      ConnectionState.transition(s, e) match
        case Right(next) if s.isLive && next.isLive => s.direction == next.direction
        case _                                      => true
    }
  }

  property("the counters of a table are the sums over its connections") {
    forAll(Gen.listOf(reachable)) { states =>
      val c = Counters.of(states)
      val live = states.filter(_.isLive)
      c.inbound == live.count(_.direction.contains(Provenance.Inbound)) &&
      c.outbound == live.count(_.direction.contains(Provenance.Outbound)) &&
      c.total == live.size &&
      c.duplex == states.count {
        case Established(_, DataFlow.Duplex) => true
        case _                               => false
      } &&
      c.established >= c.duplex
    }
  }
