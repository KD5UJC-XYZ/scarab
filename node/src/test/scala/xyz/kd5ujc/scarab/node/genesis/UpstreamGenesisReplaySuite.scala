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
                    .map(r => (r.selection, r.closures, r.terminals, r.gdd.size))}")
              )
              .map(scenario.label -> _)
          }
          .flatMap { results =>
            IO {
              val expected = cases.map(s => s.label -> s.expected).toMap
              val disagreements = results.flatMap {
                case (label, Left(problem)) => Vector(s"$label failed: $problem")
                case (label, Right(actual)) =>
                  val native = expected(label)
                  val actualEnds = actual.terminals.map(t => (t.peer, t.component, t.end))
                  val nativeEnds = native.peers.map(t => (t.peer, t.component, t.end))
                  val policy = Set(End.DensityTooLow, End.PatienceExpired, End.CandidateTooSparse)
                  val actualPolicy = actualEnds.filter(t => policy(t._3))
                  val nativePolicy = nativeEnds.filter(t => policy(t._3))
                  val causal = actual.terminals.zipWithIndex.forall { (terminal, at) =>
                    terminal.end != End.Cancelled || terminal.component == Component.ChainSyncClient || actual.terminals
                      .exists(t =>
                        t.peer == terminal.peer && t.component == Component.ChainSyncClient && t.end == End.Success
                      ) || actual.terminals
                      .take(at)
                      .exists(t =>
                        t.peer == terminal.peer && t.component == Component.ChainSyncClient
                      )
                  }
                  Vector(
                    Option.when(actual.selection != native.selection)(
                      s"$label selection: actual=${actual.selection} expected=${native.selection}"
                    ),
                    Option.when(
                      actualEnds.groupMapReduce(identity)(_ => 1)(_ + _) != nativeEnds
                        .groupMapReduce(identity)(_ => 1)(_ + _)
                    )(s"$label components: actual=${actual.terminals} expected=${native.peers}"),
                    Option.when(actualPolicy != nativePolicy)(
                      s"$label policy ordering: actual=$actualPolicy expected=$nativePolicy"
                    ),
                    Option.when(!causal)(s"$label cleanup preceded refusal: ${actual.terminals}"),
                    Option.when(
                      actual.closures.exists(c =>
                        c.problem match
                          case _: UpstreamGenesisReplay.Problem.Invariant |
                              _: UpstreamGenesisReplay.Problem.Defect |
                              _: UpstreamGenesisReplay.Problem.Blocks =>
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
