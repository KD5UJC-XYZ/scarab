// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Show
import cats.effect.{IO, Ref}
import cats.mtl.Handle
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, NodeState}
import xyz.kd5ujc.scarab.consensus.state.{MirrorState, StateMirrorError}
import xyz.kd5ujc.scarab.ledger.{Block, Header, NodeError}
import xyz.kd5ujc.scarab.node.differential.*
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.Era

/** The node's era-indexed block rules ([[MirrorRules]]) on the era-walking capture
  * (`fixtures/eras`, Byron to Conway, 731 blocks), against the old rules ([[NodeRules]]) block by
  * block (design `ledger-mirror-step2.md` §4, J2.8): the production rules, run as the differential
  * harness' new side (the verdicts, the new-epoch state at every block), and then the parts the
  * harness does not compare. The header state (the tip and the chain-dependent state of each era's
  * protocol), the node's consensus bookkeeping (the epoch lines, the Byron state) and the
  * telescopes' bounds of the state after each block are those of the mirror of the old rules'
  * state.
  *
  * This covers what the harness left to the old side before J2.8: the hand-over from Byron, and the
  * first block of every era.
  */
class MirrorRulesSuite extends CatsEffectSuite:
  private val fixtures = Path.of("fixtures/eras")

  private val setup: NodeSetup =
    NodeSetup.fromDirectory(fixtures.resolve("genesis")).fold(e => fail(e.message), identity)

  private val env = setup.mirrorEnv()

  private val raws: Vector[Bytes] =
    Files
      .readAllLines(fixtures.resolve("blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e), identity))

  private val blocks: Vector[Block] =
    raws.map(Block.decode(_, setup.geometry.byron.epochSlots).fold(e => fail(e.message), identity))

  private val headers: Vector[Header] = blocks.map(_.header)

  private def ref(h: Header) = BlockRef(h.slot, h.hash, h.blockNo, h.isBoundary)

  /** The old rules alone: the state after each block. */
  private lazy val plain: Vector[NodeState] =
    blocks.indices
      .foldLeft(Vector(setup.start)) { (acc, i) =>
        NodeRules.applyBlock(
          acc.last,
          headers(i),
          raws(i),
          setup.praos,
          setup.globals,
          setup.byronRules
        ) match
          case Right(next) => acc :+ next
          case Left(f)     => fail(s"block $i rejected by ${f.rule.name}: ${f.reason}")
      }
      .tail

  private def derived(i: Int): MirrorState =
    MirrorRun
      .run(env)(_.toMirror(Some(ref(headers(i))), plain(i)))
      .fold(e => fail(Show[StateMirrorError].show(e)), _.value)

  /** The production rules as the harness' new side, recording the state each block gave. */
  private def production(seen: Ref[IO, Map[Bytes, MirrorState]]): NewRules[IO] = _ =>
    Some { input =>
      MirrorRules.applyBlock(
        input.from,
        input.header,
        input.raw,
        setup.praos,
        setup.globals,
        setup.byronRules,
        setup.handOver,
        setup.byronHistory
      ) match
        case Right(next) =>
          seen.update(_.updated(input.header.hash, next)).as(NewOutcome.Applied(next))
        case Left(failure) => IO.pure(NewOutcome.Refused(failure))
    }

  private def harnessRun(seen: Ref[IO, Map[Bytes, MirrorState]]): IO[(Stats, Vector[Event])] =
    for
      events <- Ref.of[IO, Vector[Event]](Vector.empty)
      stats <- Handle
        .allow[NodeError] {
          Handle
            .allow[Divergence] {
              for
                harness <- DifferentialRules.create[IO](
                  NodeRules[IO](setup.praos, setup.globals, setup.byronRules),
                  production(seen),
                  IO.pure(env),
                  Sampling.EveryBlock,
                  Policy.Halt,
                  Repro.none[IO],
                  e => events.update(_ :+ e),
                  Some((s, h) => NodeRules.tick(s, h, setup.praos, setup.globals, setup.byronRules))
                )
                _ <- blocks.indices.toList
                  .foldLeftM(Paired.of(setup.start))((p, i) => harness(p, headers(i), raws(i)))
                stats <- harness.stats
              yield stats
            }
            .rescue(d => IO.raiseError(RuntimeException(Show[Divergence].show(d))))
        }
        .rescue(e => IO.raiseError(RuntimeException(e.message)))
      seenEvents <- events.get
    yield (stats, seenEvents)

  test("the production rules over the whole capture: every block, Byron to Conway, agrees") {
    for
      seen <- Ref.of[IO, Map[Bytes, MirrorState]](Map.empty)
      run <- harnessRun(seen)
      (stats, events) = run
      states <- seen.get
    yield
      println(Show[Stats].show(stats))
      assertEquals(events.collect { case d: Event.Diverged => d }, Vector.empty)
      assertEquals(stats.failures, 0L)
      // nothing is left to the old side: not the Byron prefix, not the hand-over, not the first
      // block of any era
      assertEquals(stats.notPorted, Map.empty)
      assertEquals(stats.newBlocks.count, blocks.size.toLong)
      assertEquals(states.size, blocks.size)
      // the header state, the node's consensus bookkeeping and the telescopes of the state after
      // each block are those of the mirror of the old rules' state
      blocks.indices.foreach { i =>
        val ours = derived(i)
        val theirs = states.getOrElse(headers(i).hash, fail(s"no state after block $i"))
        val at = s"block $i (${blocks(i).era})"
        assertEquals(theirs.ext.headerState, ours.ext.headerState, s"the header state after $at")
        assertEquals(theirs.consensus, ours.consensus, s"the consensus extras after $at")
        assertEquals(theirs.era, ours.era, s"the era after $at")
        assertEquals(
          theirs.ext.ledgerState.past,
          ours.ext.ledgerState.past,
          s"the bounds of the past eras after $at"
        )
        assertEquals(
          theirs.ext.ledgerState.current.start,
          ours.ext.ledgerState.current.start,
          s"the start of the current era after $at"
        )
      }
      assertEquals(states(headers.last.hash).era, Era.Conway)
  }
