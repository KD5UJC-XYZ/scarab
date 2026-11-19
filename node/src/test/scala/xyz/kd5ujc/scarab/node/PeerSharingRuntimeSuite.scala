// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Outcome}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}
import scala.collection.immutable.SortedMap
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.{FailsOnUncaught, PeerSharingAddress}
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.protocol.ConnectionState.DataFlow
import xyz.kd5ujc.scarab.protocol.PeerSharing

class PeerSharingRuntimeSuite extends FailsOnUncaught:
  private def ip(n: Int) = SocketAddress(
    IpAddress.fromString(s"127.0.0.$n").getOrElse(fail("ip")),
    Port.fromInt(3000 + n).getOrElse(fail("port"))
  )
  private def peer(n: Int): PeerNetwork.Addr = SocketAddress[Host](ip(n).host, ip(n).port)
  private def record(advertise: Boolean = true, ever: Boolean = true, failures: Int = 0) =
    PeerRecord
      .cold(Origin.Other)
      .copy(advertise = Some(advertise), everConnected = ever, failCount = failures)
  private def state = GovernorState
    .initial[PeerNetwork.Addr](1L, LocalRoots.empty, Targets.relay, Sensitivity.initial(false))

  test("published answers are exactly advertised successful zero-failure peers, bounded10") {
    for
      runtime <- PeerSharingRuntime.create[IO](42L)
      initial <- runtime.source(PeerSharing.Amount.saturating(255))
      _ = assertEquals(initial.size, 0)
      peers = SortedMap
        .from((1 to 20).map(n => peer(n) -> record()))
        .updated(peer(21), record(advertise = false))
        .updated(peer(22), record(ever = false))
        .updated(peer(23), record(failures = 1))
      _ <- runtime.publish(state.copy(peers = peers))
      answer <- runtime.source(PeerSharing.Amount.saturating(255))
      _ = assertEquals(answer.size, 10)
      _ = assert(
        answer.flatMap(PeerSharingAddress.socket).forall(a => (1 to 20).exists(n => ip(n) == a))
      )
      one <- runtime.source(PeerSharing.Amount.saturating(1))
      _ = assertEquals(one.size, 1)
      _ <- runtime.publish(state)
      cleared <- runtime.source(PeerSharing.Amount.saturating(10))
    yield assertEquals(cleared.size, 0)
  }

  test("a successfully resolved DNS root advertises its actual IP, not a guessed address") {
    val dns =
      SocketAddress[Host](Host.fromString("relay.example").getOrElse(fail("host")), ip(1).port)
    val st = state.copy(peers = SortedMap(dns -> record()))
    for
      runtime <- PeerSharingRuntime.create[IO](3L)
      _ <- runtime.publish(st)
      before <- runtime.source(PeerSharing.Amount.saturating(10))
      _ = assertEquals(before.size, 0)
      _ <- runtime.connected(dns, ip(1))
      _ <- runtime.publish(st)
      after <- runtime.source(PeerSharing.Amount.saturating(10))
    yield assertEquals(after.flatMap(PeerSharingAddress.socket), Vector(ip(1)))
  }

  // a step of the inbound governor's that no event wakes begins `inactionTimeout` (31.415927 s)
  // after the one before (`InboundGovernor.hs` 88-92 and 242-275, ouroboros-network c45735a5);
  // `inboundMaturePeerDelay` is 900 s (84-85)
  private def stepAt(k: Long): FiniteDuration = 31415927.microseconds * k
  private val none = Map.empty[PeerNetwork.Addr, Boolean]

  /** `program` on the simulated clock, with the inbound governor's steps running. The clock moves
    * through every timer up to each of `times` in turn (`tickFor`), and the gate of that time is
    * then opened; the program does what it does at that time behind its gate. A gate of `withTimer`
    * is opened at its time before the timers due then have fired, so that the program's step and
    * theirs are ready at once, in an order `seed` picks.
    */
  private def timeline[A](
      times: List[FiniteDuration],
      withTimer: Set[Int] = Set.empty,
      seed: Option[String] = None
  )(
      program: (PeerSharingRuntime[IO], List[Deferred[IO, Unit]]) => IO[A]
  ): IO[A] =
    for
      gates <- times.traverse(_ => Deferred[IO, Unit])
      control <- TestControl.execute(
        PeerSharingRuntime
          .create[IO](3L)
          .flatMap(runtime => runtime.maturing.background.surround(program(runtime, gates))),
        seed = seed
      )
      _ <- control.tick
      _ <- times
        .zip(Duration.Zero :: times)
        .zip(gates)
        .zipWithIndex
        .traverse_ { case (((at, before), gate), index) =>
          val reach =
            if withTimer(index) then
              control.tickFor(at - before - 1.nanosecond) *> control.advance(1.nanosecond)
            else control.tickFor(at - before)
          reach *> gate.complete(()) *> control.tick
        }
      outcome <- control.results
    yield outcome match
      case Some(Outcome.Succeeded(a)) => a
      case other                      => fail(s"the program did not end: $other")

  /** The seeds of `TestControl` that order the steps ready at one time: each orders them its own
    * way.
    */
  private val seeds: List[String] =
    (1L to 48L).toList.map(n => java.util.Base64.getEncoder.encodeToString(n.toString.getBytes))

  test(
    "an inbound duplex peer is offered from the inbound governor's first step 900 s or more after the one that registered it"
  ) {
    // registered by the step at 0; no event wakes the governor, so its steps are 31.415927 s
    // apart and the 29th, at 911.061883 s, is the first at or after 900 s
    val due = stepAt(29)
    timeline(List(Duration.Zero, 900.seconds, due - 1.nanosecond, due, due + 1.second)) {
      case (runtime, List(start, at900, justBefore, atDue, ended)) =>
        for
          seen <- runtime.tracked(ip(1), DataFlow.Duplex, sharing = true).use { _ =>
            for
              atOnce <- start.get *> runtime.inbound
              aged <- at900.get *> runtime.inbound
              early <- justBefore.get *> runtime.inbound
              matured <- atDue.get *> runtime.inbound
            yield (atOnce, aged, early, matured)
          }
          after <- ended.get *> runtime.inbound
        yield (seen, after)
      case (_, gates) => IO(fail(s"gates $gates"))
    }.map { case ((atOnce, aged, early, matured), after) =>
      assertEquals(atOnce, none, "not offered on the handshake")
      assertEquals(aged, none, "not offered at 900 s, between two steps of the governor")
      assertEquals(early, none, "not offered 1 ns before the governor's step")
      assertEquals(matured, Map(peer(1) -> true), "offered at that step, with its willingness")
      assertEquals(after, none, "gone with its connection")
    }
  }

  test(
    "an event of the inbound governor's matures the due peers at the next step, and a registration is fresh from the time its step handles it"
  ) {
    // peer 1 is registered by the step at 0; peer 2 connects at 905 s, which wakes the step that
    // began at 879.645956 s (the 28th); peer 2 is fresh from 905 s, when that step handles it,
    // and the step that follows, at 905 s, matures peer 1. Peer 2 is due at the first step at or
    // after 1805 s: 905 s plus 29 inactivity timeouts, 1816.061883 s. The reference stamps it with
    // its step's time read before the wait, and its other events wake it more often, so it can
    // offer peer 2 earlier or later (docs/compatibility/network-peersharing.md, Inbound peers)
    val woken = 905.seconds
    val secondDue = woken + stepAt(29)
    timeline(List(900.seconds, woken, woken, 1805.seconds, secondDue - 1.nanosecond, secondDue)) {
      case (runtime, List(at900, connect, afterEvent, at1805, justBefore, atDue)) =>
        runtime.tracked(ip(1), DataFlow.Duplex, sharing = true).use { _ =>
          for
            aged <- at900.get *> runtime.inbound
            seen <- connect.get *> runtime.tracked(ip(2), DataFlow.Duplex, sharing = false).use {
              _ =>
                for
                  woke <- afterEvent.get *> runtime.inbound
                  aged2 <- at1805.get *> runtime.inbound
                  early <- justBefore.get *> runtime.inbound
                  matured <- atDue.get *> runtime.inbound
                yield (woke, aged2, early, matured)
            }
          yield (aged, seen)
        }
      case (_, gates) => IO(fail(s"gates $gates"))
    }.map { case (aged, (woke, aged2, early, matured)) =>
      val first = Map(peer(1) -> true)
      assertEquals(aged, none, "peer 1 is not offered at 900 s: no step has run since 879.6 s")
      assertEquals(woke, first, "the step after the event at 905 s matures peer 1")
      assertEquals(aged2, first, "peer 2 is not offered 900 s after it connected: no step ran")
      assertEquals(early, first, "nor 1 ns before the first step at or after 1805 s")
      assertEquals(
        matured,
        Map(peer(1) -> true, peer(2) -> false),
        "peer 2 is offered at 1816.061883 s, the first step 900 s after its registration was handled"
      )
    }
  }

  test(
    "a second connection from a fresh peer's address at 905 s offers it no earlier than 900 s after that"
  ) {
    // registered at 0; another connection from its address at 905 s, before any step matured it,
    // is fresh from 905 s, so the address is offered at the first step at or after 1805 s,
    // 1816.061883 s, and not at 911.061883 s
    val woken = 905.seconds
    val due = woken + stepAt(29)
    timeline(List(woken, woken, stepAt(29), due - 1.nanosecond, due)) {
      case (runtime, List(connect, afterEvent, firstDue, justBefore, atDue)) =>
        runtime.tracked(ip(1), DataFlow.Duplex, sharing = true).use { _ =>
          connect.get *> runtime.tracked(ip(1), DataFlow.Duplex, sharing = true).use { _ =>
            for
              woke <- afterEvent.get *> runtime.inbound
              atFirstDue <- firstDue.get *> runtime.inbound
              early <- justBefore.get *> runtime.inbound
              matured <- atDue.get *> runtime.inbound
            yield (woke, atFirstDue, early, matured)
          }
        }
      case (_, gates) => IO(fail(s"gates $gates"))
    }.map { (woke, atFirstDue, early, matured) =>
      assertEquals(woke, none, "the step the event woke began at 879.6 s, when nothing was due")
      assertEquals(atFirstDue, none, "not at 911.061883 s: fresh again from 905 s")
      assertEquals(early, none, "not 1 ns before 1816.061883 s")
      assertEquals(matured, Map(peer(1) -> true), "offered at 1816.061883 s")
    }
  }

  test(
    "a registration arriving as the inactivity timeout fires is handled, whichever runs first"
  ) {
    // at 31.415927 s, the first step's timeout and the registration are ready at once; each seed
    // orders them its own way. The registration is never lost: the peer is offered at the first
    // step at or after 931.415927 s, 31.415927 s plus 29 inactivity timeouts
    val arrives = stepAt(1)
    val due = stepAt(30)
    seeds
      .traverse { seed =>
        timeline(List(arrives, due - 1.nanosecond, due), withTimer = Set(0), seed = Some(seed)) {
          case (runtime, List(connect, justBefore, atDue)) =>
            connect.get *> runtime.tracked(ip(1), DataFlow.Duplex, sharing = true).use { _ =>
              (justBefore.get *> runtime.inbound, atDue.get *> runtime.inbound).tupled
            }
          case (_, gates) => IO(fail(s"gates $gates"))
        }.map((seed, _))
      }
      .map { results =>
        val wrong = results.filter((_, seen) => seen != (none, Map(peer(1) -> true)))
        assertEquals(wrong, Nil, "seeds whose registration was lost or offered early")
      }
  }

  test(
    "an unregistration arriving as the inactivity timeout fires is handled, whichever runs first"
  ) {
    // registered at 0, unregistered at 314.15927 s as the tenth step's timeout fires, in an order
    // each seed picks; the address must never be offered, though it would be due at 911.061883 s
    val leaves = stepAt(10)
    seeds
      .traverse { seed =>
        timeline(List(leaves, 1000.seconds), withTimer = Set(0), seed = Some(seed)) {
          case (runtime, List(disconnect, later)) =>
            for
              registration <- runtime.tracked(ip(1), DataFlow.Duplex, sharing = true).allocated
              _ <- disconnect.get *> registration._2
              seen <- later.get *> runtime.inbound
            yield seen
          case (_, gates) => IO(fail(s"gates $gates"))
        }.map((seed, _))
      }
      .map { results =>
        val wrong = results.filter((_, seen) => seen != none)
        assertEquals(wrong, Nil, "seeds whose unregistration was lost")
      }
  }

  test(
    "a second connection from a mature peer's address leaves it offered, though nothing read it before"
  ) {
    // the peer matures at the governor's step at 911.061883 s with no reader; at 1000 s another
    // connection from its address is fresh as well, and the peer stays offered (`OrdPSQ.insert`
    // into the fresh peers leaves the mature ones, at every pin); the end of the first connection
    // then unregisters the address from both
    timeline(List.fill(4)(1000.seconds)) {
      case (runtime, List(second, afterSecond, firstEnds, afterFirstEnds)) =>
        for
          first <- runtime.tracked(ip(1), DataFlow.Duplex, sharing = true).allocated
          other <- second.get *> runtime.tracked(ip(1), DataFlow.Duplex, sharing = true).allocated
          offered <- afterSecond.get *> runtime.inbound
          _ <- firstEnds.get *> first._2
          gone <- afterFirstEnds.get *> runtime.inbound
          _ <- other._2
        yield (offered, gone)
      case (_, gates) => IO(fail(s"gates $gates"))
    }.map { (offered, gone) =>
      assertEquals(offered, Map(peer(1) -> true), "offered at 1000 s with a second connection")
      assertEquals(gone, none, "the end of the first connection unregisters the address")
    }
  }

  test(
    "a second connection from a fresh peer's address restarts its maturation, and the first one's end removes the peer"
  ) {
    // both pins: a new connection id's `NewConnection` re-inserts the peer at the time of its
    // step (`OrdPSQ.insert`), and `unregisterConnection` deletes by the remote address
    timeline(List(600.seconds, 1000.seconds, 1600.seconds, 1600.seconds, 1600.seconds)) {
      case (runtime, List(second, afterFirstDue, afterSecondDue, firstEnds, afterFirstEnds)) =>
        for
          first <- runtime.tracked(ip(1), DataFlow.Duplex, sharing = true).allocated
          other <- second.get *> runtime.tracked(ip(1), DataFlow.Duplex, sharing = true).allocated
          atFirstDue <- afterFirstDue.get *> runtime.inbound
          atSecondDue <- afterSecondDue.get *> runtime.inbound
          _ <- firstEnds.get *> first._2
          gone <- afterFirstEnds.get *> runtime.inbound
          _ <- other._2
        yield (atFirstDue, atSecondDue, gone)
      case (_, gates) => IO(fail(s"gates $gates"))
    }.map { (atFirstDue, atSecondDue, gone) =>
      assertEquals(atFirstDue, none, "at 1000 s, past the first connection's 900 s")
      assertEquals(atSecondDue, Map(peer(1) -> true), "at 1600 s, past the second one's")
      assertEquals(gone, none, "the end of the first connection unregisters the address")
    }
  }

  test(
    "the end of a unidirectional connection from a duplex peer's address unregisters the address"
  ) {
    // the inbound governor tracks connections of either data flow, and `unregisterConnection`
    // deletes the remote address from the fresh and the mature peers whatever the data flow
    // (`InboundGovernor/State.hs` 208-222 at c45735a5, 201-215 at 4646039f and 2f1c154f)
    timeline(List(stepAt(29), 1000.seconds, 1000.seconds, 1000.seconds, 2000.seconds)) {
      case (runtime, List(matured, connect, disconnect, afterEnd, later)) =>
        runtime.tracked(ip(1), DataFlow.Duplex, sharing = true).use { _ =>
          for
            offered <- matured.get *> runtime.inbound
            other <- connect.get *> runtime.tracked(ip(1), DataFlow.Unidirectional, true).allocated
            during <- disconnect.get *> runtime.inbound
            _ <- other._2
            gone <- afterEnd.get *> runtime.inbound
            stillGone <- later.get *> runtime.inbound
          yield (offered, during, gone, stillGone)
        }
      case (_, gates) => IO(fail(s"gates $gates"))
    }.map { (offered, during, gone, stillGone) =>
      val first = Map(peer(1) -> true)
      assertEquals(offered, first, "offered at 911.061883 s")
      assertEquals(during, first, "a unidirectional connection registers nothing")
      assertEquals(gone, none, "its end unregisters the address")
      assertEquals(stillGone, none, "and nothing registers it again while the duplex one lives")
    }
  }

  test("a unidirectional connection alone never makes its address a duplex peer") {
    timeline(List(2000.seconds)) {
      case (runtime, List(later)) =>
        runtime
          .tracked(ip(1), DataFlow.Unidirectional, sharing = true)
          .use(_ => later.get *> runtime.inbound)
      case (_, gates) => IO(fail(s"gates $gates"))
    }.map(seen => assertEquals(seen, none))
  }

  test("relay/producer defaults and explicit overrides follow POM") {
    IO {
      assert(ConfigSettings().peerSharingEnabled(producer = false))
      assert(!ConfigSettings().peerSharingEnabled(producer = true))
      assert(ConfigSettings(peerSharing = Some(true)).peerSharingEnabled(producer = true))
      assert(!ConfigSettings(peerSharing = Some(false)).peerSharingEnabled(producer = false))
      assert(
        !ConfigSettings(peerSharing = Some(true)).unsupported.exists(_.startsWith("PeerSharing:"))
      )
    }
  }
