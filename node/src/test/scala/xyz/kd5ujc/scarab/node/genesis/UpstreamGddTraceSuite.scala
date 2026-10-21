// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.genesis

import cats.effect.IO
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity.{LatestSlot, PeerState}
import xyz.kd5ujc.scarab.network.FailsOnUncaught

import UpstreamGenesisCases.*

class UpstreamGddTraceSuite extends FailsOnUncaught:
  test("every original native simulator GDD decision retains exact ordered bounds and refusals") {
    UpstreamGenesisCases.read.flatMap {
      case Left(error) => IO(fail(error.toString))
      case Right(cases) =>
        IO {
          assertEquals(cases.flatMap(_.gddTrace).size, 1349)
          val debug = cases.flatMap(s =>
            s.gddTrace.collect { case GddTrace(at, event: GddEvent.Debug, _) =>
              (s, at, event)
            }
          )
          assertEquals(debug.size, 1304)
          debug.foreach { (scenario, at, observed) =>
            val GddEvent.Debug(window, current, candidates, suffixes, result, loeHead) = observed
            val clue = s"${scenario.label} at $at"
            val shared = GenesisDensity.sharedCandidatePrefix(current, candidates)
            assertEquals(shared.suffixes, suffixes, clue)
            assertEquals(shared.prefix.tip, loeHead, clue)
            val known = result.bounds.toMap
            val states = candidates.map { (peer, candidate) =>
              val bounds = known.get(peer)
              // Native GDDDebug omits bounds exactly when csLatestSlot is Unknown. Idling cannot
              // affect that branch; known peers retain the original complete bound inputs.
              peer -> PeerState(
                candidate,
                bounds.fold[LatestSlot](LatestSlot.Unknown)(_.latestSlot),
                bounds.exists(_.idling)
              )
            }.toMap
            assertEquals(
              GenesisDensity.densityDisconnect(window, scenario.k, states, suffixes, shared.prefix),
              result,
              clue
            )
          }
          cases.foreach { scenario =>
            scenario.gddTrace.zipWithIndex.foreach {
              case (GddTrace(at, GddEvent.Disconnected(peers), _), index) =>
                val preceding = scenario.gddTrace.take(index).lastOption
                assert(
                  preceding.exists {
                    case GddTrace(time, GddEvent.Debug(_, _, _, _, result, _), _) =>
                      time == at && result.losingPeers == peers
                    case _ => false
                  },
                  s"${scenario.label} native callback order at $at"
                )
              case _ => ()
            }
          }
        }
    }
  }
