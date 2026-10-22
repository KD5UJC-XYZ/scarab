// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.genesis

import cats.effect.IO
import xyz.kd5ujc.scarab.ledger.PeerError
import xyz.kd5ujc.scarab.network.{FailsOnUncaught, NetworkError, Protocols}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

import UpstreamGenesisCases.*
import UpstreamGenesisReplay.{Lifecycle, Problem, Result, Terminal}
import UpstreamGenesisTerminals.*

class UpstreamGenesisTerminalsSuite extends FailsOnUncaught:
  private def cases: IO[Vector[Scenario]] =
    UpstreamGenesisCases.read.map(_.fold(error => fail(error.toString), identity))

  private def cause(end: End): Option[Problem] = end match
    case End.DensityTooLow =>
      Some(Problem.Node(PeerError.Genesis(PeerError.GenesisProblem.DensityTooLow)))
    case End.PatienceExpired =>
      Some(Problem.Node(PeerError.Genesis(PeerError.GenesisProblem.LoPExpired)))
    case End.CandidateTooSparse =>
      Some(
        Problem.Node(
          PeerError.Genesis(
            PeerError.GenesisProblem.CandidateTooSparse(Point.Origin, Point.Origin, Point.Origin)
          )
        )
      )
    case End.Timeout =>
      Some(Problem.Network(NetworkError.Timeout(Protocols.ChainSync, "native test state")))
    case _ => None

  private def reference(scenario: Scenario): Result =
    val ended = scenario.expected.peers.map(p => Terminal(p.peer, p.component, p.end, cause(p.end)))
    val events = ended.flatMap { terminal =>
      val batch = Option
        .when(terminal.end == End.DensityTooLow)(Lifecycle.DensityBatch(Vector(terminal.peer)))
        .toVector
      val refusal = Option
        .when(Set(End.DensityTooLow, End.PatienceExpired, End.CandidateTooSparse)(terminal.end))(
          terminal.cause.map(Lifecycle.Refused(terminal.peer, _))
        )
        .flatten
        .toVector
      batch ++ refusal :+ Lifecycle.Ended(terminal)
    }
    Result(scenario.expected.selection, Vector.empty, ended, Vector.empty, Vector.empty, events)

  test("all84 native component snapshots satisfy the typed lifecycle contract") {
    cases.map(_.foreach { scenario =>
      assertEquals(evaluate(scenario, reference(scenario)), Vector.empty, scenario.label)
    })
  }

  test("native component multiplicity rejects omitted, duplicate and misattributed endings") {
    cases.map { scenarios =>
      val scenario = scenarios
        .find(_.law == UpstreamGenesisLaws.Law.WaitTooLong)
        .fold(fail("LoP fixture missing"))(identity)
      val actual = reference(scenario)
      val first = actual.terminals.headOption.fold(fail("terminal missing"))(identity)
      Vector(
        actual.terminals.drop(1),
        actual.terminals :+ first,
        actual.terminals.map(t => t.copy(peer = PeerId.Adversarial(99)))
      ).foreach { terminals =>
        assert(
          evaluate(scenario, actual.copy(terminals = terminals))
            .exists(_.isInstanceOf[Violation.ComponentMultiplicity])
        )
      }
    }
  }

  test("typed causes, policy ownership and enabled mechanisms cannot be fabricated") {
    cases.map { scenarios =>
      val scenario = scenarios
        .find(_.law == UpstreamGenesisLaws.Law.WaitTooLong)
        .fold(fail("LoP fixture missing"))(identity)
      val actual = reference(scenario)
      val refused = actual.terminals
        .find(_.end == End.PatienceExpired)
        .fold(fail("patience terminal missing"))(identity)
      def replace(next: Terminal): Result = actual.copy(
        terminals = actual.terminals.map(t => if t == refused then next else t),
        lifecycle = actual.lifecycle.map {
          case Lifecycle.Ended(t) if t == refused => Lifecycle.Ended(next)
          case event                              => event
        }
      )
      assert(
        evaluate(scenario, replace(refused.copy(end = End.DensityTooLow)))
          .exists(_.isInstanceOf[Violation.CauseTag])
      )
      assert(
        evaluate(scenario, replace(refused.copy(cause = None)))
          .exists(_.isInstanceOf[Violation.CauseTag])
      )
      assert(
        evaluate(scenario, replace(refused.copy(component = Component.BlockFetchServer)))
          .exists(_.isInstanceOf[Violation.PolicyComponent])
      )
      assert(
        evaluate(scenario.copy(config = scenario.config.copy(lop = false)), actual)
          .exists(_.isInstanceOf[Violation.DisabledPolicy])
      )
      val wrongCause =
        replace(refused.copy(end = End.DensityTooLow, cause = cause(End.DensityTooLow)))
      assert(evaluate(scenario, wrongCause).exists(_.isInstanceOf[Violation.MissingRefusal]))
    }
  }

  test("refusal precedes termination and peer cleanup; another peer cannot supply causality") {
    cases.map { scenarios =>
      val scenario = scenarios
        .find(_.law == UpstreamGenesisLaws.Law.WaitTooLong)
        .fold(fail("LoP fixture missing"))(identity)
      val actual = reference(scenario)
      val callbacksLast = actual.lifecycle.filterNot(_.isInstanceOf[Lifecycle.Refused]) ++
        actual.lifecycle.collect { case event: Lifecycle.Refused => event }
      assert(
        evaluate(scenario, actual.copy(lifecycle = callbacksLast))
          .exists(_.isInstanceOf[Violation.MissingRefusal])
      )
      val cleanupFirst = actual.lifecycle.collect {
        case event @ Lifecycle.Ended(t) if t.end == End.Cancelled => event
      } ++ actual.lifecycle.filterNot {
        case Lifecycle.Ended(t) => t.end == End.Cancelled
        case _                  => false
      }
      assert(
        evaluate(scenario, actual.copy(lifecycle = cleanupFirst))
          .exists(_.isInstanceOf[Violation.CleanupBeforeTermination])
      )
      val anotherPeer = actual.lifecycle.map {
        case Lifecycle.Refused(_, problem) => Lifecycle.Refused(PeerId.Adversarial(99), problem)
        case event                         => event
      }
      assert(
        evaluate(scenario, actual.copy(lifecycle = anotherPeer))
          .exists(_.isInstanceOf[Violation.MissingRefusal])
      )
    }
  }

  test("every original cause-constraining branch rejects a different allowed native exception") {
    cases.map { scenarios =>
      val constrained =
        scenarios.filter(s => Set(Family.LoE, Family.DensityDisconnect, Family.LoP)(s.family))
      assertEquals(constrained.size, 40)
      constrained.foreach { scenario =>
        val wrong = scenario.law match
          case UpstreamGenesisLaws.Law.LoETimeout | UpstreamGenesisLaws.Law.WaitTooLong |
              UpstreamGenesisLaws.Law.ServeSlow | UpstreamGenesisLaws.Law.DelayFails =>
            End.DensityTooLow
          case _ => End.Timeout
        val actual = reference(scenario)
        val peer = scenario.schedules.headOption.fold(fail("peer schedule missing"))(_.peer)
        val terminal = Terminal(peer, Component.ChainSyncClient, wrong, cause(wrong))
        val mutated = actual.copy(terminals =
          actual.terminals.filterNot(_.component == Component.ChainSyncClient) :+ terminal
        )
        assert(
          UpstreamGenesisLaws
            .evaluate(scenario, mutated)
            .exists(_.isInstanceOf[UpstreamGenesisLaws.Violation.ChainSyncExceptions]),
          scenario.label
        )
      }
    }
  }

  test("GDD callbacks preserve the complete losing-peer batch and its order") {
    cases.map { scenarios =>
      val scenario = scenarios
        .find(_.law == UpstreamGenesisLaws.Law.CsjSharedAdversaries)
        .fold(fail("CSJ fixture missing"))(identity)
      val a = PeerId.Adversarial(1)
      val b = PeerId.Adversarial(2)
      val problem = cause(End.DensityTooLow).fold(fail("density cause missing"))(identity)
      val actual = reference(scenario)
      val correct = Vector(
        Lifecycle.DensityBatch(Vector(a, b)),
        Lifecycle.Refused(a, problem),
        Lifecycle.Refused(b, problem)
      )
      assert(
        !evaluate(scenario, actual.copy(lifecycle = correct))
          .exists(_.isInstanceOf[Violation.DensityCallbacks])
      )
      Vector(
        correct.updated(0, Lifecycle.DensityBatch(Vector(b, a))),
        correct.dropRight(1),
        correct :+ Lifecycle.Refused(a, problem)
      ).foreach { lifecycle =>
        assert(
          evaluate(scenario, actual.copy(lifecycle = lifecycle))
            .exists(_.isInstanceOf[Violation.DensityCallbacks])
        )
      }
    }
  }
