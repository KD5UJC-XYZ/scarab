// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.genesis

import cats.effect.IO
import munit.CatsEffectSuite
import UpstreamGenesisCases.*

class UpstreamGenesisReplaySuite extends CatsEffectSuite:
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
            universe.blocks.foreach { block =>
              assertEquals(block.header.blockNo, BigInt(block.native.hash.size / 8))
              assertEquals(block.header.slot, block.native.slot.value)
              assertEquals(block.header.raw, block.wire.raw)
            }
          }
        }
    }
  }
