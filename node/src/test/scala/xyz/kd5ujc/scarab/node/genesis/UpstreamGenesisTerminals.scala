// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.genesis

import xyz.kd5ujc.scarab.ledger.PeerError

import UpstreamGenesisCases.*
import UpstreamGenesisReplay.{Lifecycle, Problem, Result, Terminal}

/** PeerSimulator.Run.startNode owns one independent registry per peer and waits for any of its four
  * components. Setup.hasOnlyExpectedExceptions and the original ctProperty constrain causes;
  * concurrent peer finalizers have no common ordering law. Density callbacks do: Genesis.Governor
  * publishes the complete losing-peer batch before invoking its ordered disconnect callbacks.
  */
object UpstreamGenesisTerminals:
  enum Violation:
    case ComponentMultiplicity(
        actual: Map[(PeerId, Component), Int],
        native: Map[(PeerId, Component), Int]
    )
    case LifecycleTerminals(actual: Vector[Terminal], recorded: Vector[Terminal])
    case CauseTag(terminal: Terminal)
    case PolicyComponent(terminal: Terminal)
    case DisabledPolicy(terminal: Terminal)
    case MissingRefusal(terminal: Terminal)
    case CleanupBeforeTermination(terminal: Terminal)
    case DensityCallbacks(expected: Vector[PeerId], actual: Vector[PeerId])

  private def policy(end: End): Boolean = end match
    case End.PatienceExpired | End.DensityTooLow | End.CandidateTooSparse => true
    case _                                                                => false

  def evaluate(scenario: Scenario, actual: Result): Vector[Violation] =
    val identities = actual.terminals.groupMapReduce(t => (t.peer, t.component))(_ => 1)(_ + _)
    val native = scenario.expected.peers.groupMapReduce(t => (t.peer, t.component))(_ => 1)(_ + _)
    val recorded = actual.lifecycle.collect { case Lifecycle.Ended(terminal) => terminal }
    val shape = Vector(
      Option.when(identities != native)(Violation.ComponentMultiplicity(identities, native)),
      Option.when(actual.terminals != recorded)(
        Violation.LifecycleTerminals(actual.terminals, recorded)
      )
    ).flatten
    val causes = actual.lifecycle.zipWithIndex.flatMap {
      case (Lifecycle.Ended(terminal), index) =>
        val prior = actual.lifecycle.take(index)
        val matched = terminal.cause match
          case Some(cause) => UpstreamGenesisReplay.end(cause) == terminal.end
          case None        => terminal.end == End.Success || terminal.end == End.Cancelled
        val disabled = terminal.end match
          case End.PatienceExpired => !scenario.config.lop
          case End.DensityTooLow   => !scenario.config.loe
          case End.Timeout =>
            !scenario.config.chainSyncTimeouts && !scenario.config.blockFetchTimeouts
          case _ => false
        val refusal = prior.exists {
          case Lifecycle.Refused(peer, cause) =>
            peer == terminal.peer && terminal.cause.contains(cause)
          case _ => false
        }
        val startedCleanup = prior.exists {
          case Lifecycle.Ended(first) => first.peer == terminal.peer && first.end != End.Cancelled
          case _                      => false
        }
        Vector(
          Option.when(!matched)(Violation.CauseTag(terminal)),
          Option.when(policy(terminal.end) && terminal.component != Component.ChainSyncClient)(
            Violation.PolicyComponent(terminal)
          ),
          Option.when(disabled)(Violation.DisabledPolicy(terminal)),
          Option.when(policy(terminal.end) && !refusal)(Violation.MissingRefusal(terminal)),
          Option.when(terminal.end == End.Cancelled && !startedCleanup)(
            Violation.CleanupBeforeTermination(terminal)
          )
        ).flatten
      case _ => Vector.empty
    }
    val callbacks = actual.lifecycle.zipWithIndex.flatMap {
      case (Lifecycle.DensityBatch(expected), index) =>
        val observed = actual.lifecycle
          .drop(index + 1)
          .takeWhile {
            case _: Lifecycle.DensityBatch => false
            case _                         => true
          }
          .collect {
            case Lifecycle.Refused(
                  peer,
                  Problem.Node(
                    PeerError
                      .Genesis(PeerError.GenesisProblem.DensityTooLow)
                  )
                ) =>
              peer
          }
        Option.when(expected != observed)(Violation.DensityCallbacks(expected, observed)).toVector
      case _ => Vector.empty
    }
    shape ++ causes ++ callbacks
