// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import cats.effect.unsafe.implicits.global
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, NodeState}
import xyz.kd5ujc.scarab.ledger.{Block, Header}
import xyz.kd5ujc.scarab.node.MirrorCheck.{Outcome, Stage}
import xyz.kd5ujc.scarab.protocol.Era

/** `--mirror-check` on the eras capture: every state checks out, the follower's hook reports at the
  * first block and once per epoch, and a state the mirror cannot hold is a reported refusal, not a
  * failure of the follower.
  */
class MirrorCheckSuite extends FunSuite:
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

  private lazy val states: Vector[(Header, NodeState)] =
    blocks.indices.foldLeft(Vector.empty[(Header, NodeState)]) { (acc, i) =>
      val before = acc.lastOption.fold(setup.start)(_._2)
      val h = blocks(i).header
      NodeRules.applyBlock(before, h, raws(i), setup.praos, setup.globals, setup.byronRules) match
        case Right(next) => acc :+ (h -> next)
        case Left(f)     => fail(s"block $i rejected by ${f.rule.name}: ${f.reason}")
    }

  private def anchor(h: Header) = Some(BlockRef(h.slot, h.hash, h.blockNo, h.isBoundary))

  test("every state of the capture, Byron to Conway, checks out") {
    val outcomes = states.map((h, s) => MirrorCheck.check(env, anchor(h), s))
    val bad = outcomes.filterNot(_.ok)
    assert(bad.isEmpty, bad.take(3).toString)
    assertEquals(outcomes.map(_.era).distinct.size, 7)
  }

  test("the hook reports at the first block and at the first block of each epoch, and only then") {
    val reported = (for
      seen <- Ref.of[IO, Vector[(BigInt, Outcome)]](Vector.empty)
      checker <- MirrorCheck.Checker.create[IO](
        IO.pure(env),
        (_, epoch, outcome, _) => seen.update(_ :+ (epoch -> outcome))
      )
      _ <- states.traverse_((h, s) => checker.afterBlock(h, s))
      out <- seen.get
    yield out).unsafeRunSync()
    val epochs = states.map(_._2.ledger.epoch)
    val expected = 1 + epochs.zip(epochs.drop(1)).count((a, b) => a != b)
    assertEquals(reported.size, expected)
    assertEquals(reported.map(_._1), epochs.distinct)
    assert(reported.forall(_._2.ok), reported.filterNot(_._2.ok).toString)
  }

  test("a state the mirror cannot hold is a refusal, reported and not raised") {
    val (h, good) = states.find(_._1.era == Era.Conway).getOrElse(fail("no Conway block"))
    // a negative treasury has no `Coin`
    val s = good.copy(ledger = good.ledger.copy(treasury = BigInt(-1)))
    MirrorCheck.check(env, anchor(h), s) match
      case Outcome.Refused(Era.Conway, Stage.ToMirror, _) => ()
      case other => fail(s"expected a refusal of the conversion, got $other")
    val reported = (for
      seen <- Ref.of[IO, Vector[Outcome]](Vector.empty)
      checker <- MirrorCheck.Checker.create[IO](
        IO.pure(env),
        (_, _, outcome, _) => seen.update(_ :+ outcome)
      )
      _ <- checker.afterBlock(h, s)
      out <- seen.get
    yield out).unsafeRunSync()
    assertEquals(reported.map(_.ok), Vector(false))
  }
