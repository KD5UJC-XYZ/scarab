// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.genesis

import cats.effect.IO
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import scala.concurrent.duration.*

import UpstreamGenesisCases.*

class UpstreamGenesisReplaySuite extends FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 180.seconds
  test("all original Genesis cases retain complete typed inputs and native outcomes") {
    UpstreamGenesisCases.read.flatMap {
      case Left(error) => IO(fail(error.toString))
      case Right(cases) =>
        IO {
          assertEquals(cases.size, 84)
          assertEquals(cases.map(_.label).distinct.size, 84)
          assertEquals(
            cases.groupMapReduce(_.family)(_ => 1)(_ + _),
            Map(
              Family.LoE -> 8,
              Family.DensityDisconnect -> 4,
              Family.LoP -> 28,
              Family.CSJ -> 16,
              Family.Uniform -> 24,
              Family.LongRangeAttack -> 4
            )
          )
          assertEquals(cases.flatMap(_.schedules).size, 218)
          assertEquals(cases.flatMap(_.timeline).size, 8636)
          cases.foreach { scenario =>
            assert(
              scenario.expected.propertyPassed && scenario.expected.expectedExceptionsPassed &&
                scenario.expected.combinedPassed,
              scenario.label
            )
            assertEquals(
              scenario.expected.peers.map(_.rendering),
              scenario.expected.renderings,
              scenario.label
            )
            assert(!scenario.expected.peers.exists(_.end == End.Unexpected), scenario.label)
            assertEquals(scenario.timeline.size, scenario.schedules.flatMap(_.events).size)

          }
          assert(cases.exists(!_.config.loe))
          assert(cases.exists(!_.config.lop))
          assert(cases.exists(!_.config.jumping))
          assert(cases.exists(_.config.downtimePicos.nonEmpty))
        }
    }
  }

  test("every original TestBlock point maps bijectively to decoded original CBOR header bytes") {
    UpstreamGenesisCases.read.flatMap {
      case Left(error) => IO(fail(error.toString))
      case Right(cases) =>
        IO {
          cases.foreach { scenario =>
            val universe =
              UpstreamGenesisBlocks.build(scenario).fold(p => fail(p.toString), identity)
            val points =
              scenario.timeline.flatMap(t => Vector(t.state.tip, t.state.header, t.state.block))
            points.foreach { point =>
              val encoded = universe.encode(point).fold(p => fail(p.toString), identity)
              assertEquals(universe.decode(encoded), Right(point), scenario.label)
            }
            universe.blocks.foreach {
              block =>
                assertEquals(block.header.blockNo, BigInt(block.native.hash.size / 8))
                assertEquals(block.header.slot, block.native.slot.value)
                assertEquals(block.header.raw, block.wire.raw)
                val decoded = xyz.kd5ujc.scarab.ledger.BlockTransport
                  .check(block.header, block.body)
                  .fold(e => fail(e.message), identity)
                assertEquals(decoded.header, block.header)
              assertEquals(block.header.bodySize + block.header.raw.size + 10, BigInt(1000))
              assertEquals(block.body.size, 1000)
            }
          }
        }
    }
  }

  test("original generated schedules select the native chain through production runtime") {
    UpstreamGenesisCases.read.flatMap {
      case Left(error) => IO(fail(error.toString))
      case Right(cases) =>
        import cats.syntax.all.*
        cases
          .traverse { scenario =>
            IO.println(
              s"replaying ${scenario.label} nativeSeed=${scenario.seed} runtimeSeed=${UpstreamGenesisReplay.runtimeSeed(scenario)}"
            ) *> UpstreamGenesisReplay
              .run(scenario)
              .flatTap(result =>
                IO.println(s"outcome ${scenario.label}: ${result
                    .map(r => (r.selection, r.closures, r.terminals, r.gdd.size, r.downloaded))}")
              )
              .map(scenario.label -> _)
          }
          .flatMap { results =>
            val scenarios = cases.map(s => s.label -> s).toMap
            val diagnostics = results.flatMap {
              case (_, Left(_)) => Vector.empty
              case (label, Right(actual)) =>
                val native = scenarios(label).expected
                val actualEnds = actual.terminals.map(t => (t.peer, t.component, t.end))
                val nativeEnds = native.peers.map(t => (t.peer, t.component, t.end))
                val policy = Set(End.DensityTooLow, End.PatienceExpired, End.CandidateTooSparse)
                Vector(
                  Option.when(
                    actualEnds.groupMapReduce(identity)(_ => 1)(_ + _) !=
                      nativeEnds.groupMapReduce(identity)(_ => 1)(_ + _)
                  )(
                    s"diagnostic $label component snapshot: actual=${actual.terminals} native=${native.peers}"
                  ),
                  Option.when(
                    actualEnds.filter(t => policy(t._3)) != nativeEnds.filter(t => policy(t._3))
                  )(
                    s"diagnostic $label cross-peer policy chronology: actual=${actual.terminals} native=${native.peers}"
                  )
                ).flatten
            }
            diagnostics.traverse_(IO.println) *> IO {
              val disagreements = results.flatMap {
                case (label, Left(problem)) => Vector(s"$label failed: $problem")
                case (label, Right(actual)) =>
                  val scenario = scenarios(label)
                  val laws = UpstreamGenesisLaws.evaluate(scenario, actual)
                  val terminals = UpstreamGenesisTerminals.evaluate(scenario, actual)
                  Vector(
                    Option.when(laws.nonEmpty)(s"$label original laws: $laws"),
                    Option.when(actual.selection != scenario.expected.selection)(
                      s"$label selection: actual=${actual.selection} expected=${scenario.expected.selection}"
                    ),
                    Option.when(terminals.nonEmpty)(s"$label terminal contract: $terminals"),
                    Option.when(
                      actual.closures.exists(c =>
                        c.problem match
                          case _: UpstreamGenesisReplay.Problem.Worker |
                              _: UpstreamGenesisReplay.Problem.Invariant |
                              _: UpstreamGenesisReplay.Problem.Defect |
                              _: UpstreamGenesisReplay.Problem.Blocks |
                              _: UpstreamGenesisReplay.Problem.MissingDensityPeer =>
                            true
                          case _ => false
                      )
                    )(s"$label invariant: ${actual.closures}")
                  ).flatten
              }
              assertEquals(disagreements, Vector.empty)
            }
          }
    }
  }
