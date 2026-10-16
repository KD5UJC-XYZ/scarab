// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.differential

import cats.Show
import cats.effect.{ExitCode, IO, Ref}
import cats.mtl.Handle
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, NodeState}
import xyz.kd5ujc.scarab.consensus.state.{EraLedgerState, MirrorState, StateMirrorError, Telescope}
import xyz.kd5ujc.scarab.ledger.{Block, BlockFailure, BlockRule, Header, NodeError, ValidationError}
import xyz.kd5ujc.scarab.ledger.state.Coin
import xyz.kd5ujc.scarab.node.{MirrorRun, NodeRules, NodeSetup}
import xyz.kd5ujc.scarab.protocol.Era

/** The differential harness on the era-walking capture (`fixtures/eras`, Byron to Conway, 731
  * blocks): the old rules and a new side that is not ported run side by side, the new side being
  * seeded from the old one after every block (design `ledger-mirror-step2.md` §2.5, J2.2). Zero
  * divergences by construction, and the old side's states are the states without the harness.
  *
  * The divergences themselves are shown with new sides that are ported only in the sense that they
  * answer: one whose state is tampered with, one that refuses a block the old side took. The repro
  * bundle of such a divergence is written, read back and replayed.
  */
class DifferentialErasSuite extends CatsEffectSuite:
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

  /** The old rules alone, over the whole capture: the state before each block and after the last.
    */
  private lazy val plain: Vector[NodeState] =
    blocks.indices.foldLeft(Vector(setup.start)) { (acc, i) =>
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

  /** What a run of the harness leaves behind. */
  private final case class Run(
      paired: Paired,
      events: Vector[Event],
      stats: Stats,
      halted: Option[Divergence]
  )

  /** The harness over blocks `range`, from the pair `from`, with the new side `neu`. */
  private def run(
      range: Range,
      neu: NewRules[IO] = NewRules.notPorted[IO],
      policy: Policy = Policy.Halt,
      repro: Repro[IO] = Repro.none[IO],
      from: Paired = Paired.of(setup.start),
      ticking: Boolean = false
  ): IO[Run] =
    for
      events <- Ref.of[IO, Vector[Event]](Vector.empty)
      outcome <- Handle
        .allow[NodeError] {
          Handle
            .allow[Divergence] {
              val old = NodeRules[IO](setup.praos, setup.globals, setup.byronRules)
              for
                harness <- DifferentialRules.create[IO](
                  old,
                  neu,
                  IO.pure(env),
                  Sampling.EveryBlock,
                  policy,
                  repro,
                  e => events.update(_ :+ e),
                  Option.when(ticking)((s, h) =>
                    NodeRules.tick(s, h, setup.praos, setup.globals, setup.byronRules)
                  )
                )
                last <- range.toList.foldLeftM(from)((p, i) => harness(p, headers(i), raws(i)))
                stats <- harness.stats
              yield (last, stats, Option.empty[Divergence])
            }
            .rescue(d => IO.pure((from, Stats(), Some(d))))
        }
        .rescue(e => IO.raiseError(RuntimeException(e.message)))
      seen <- events.get
    yield Run(outcome._1, seen, outcome._2, outcome._3)

  // ---- the tick stage (J2.3): the ticked ledger of the old rules and of the new, at every block

  test("the tick of every block of the capture: the new rules reach the old rules' state") {
    run(blocks.indices, NewRules.current[IO](), Policy.Reseed, ticking = true).map { r =>
      val diverged = r.events.collect { case Event.Diverged(d, _) => Show[Divergence].show(d) }
      println(Show[Stats].show(r.stats))
      diverged.take(20).foreach(println)
      assertEquals(diverged, Vector.empty)
      assertEquals(r.stats.failures, 0L)
      // every block of Shelley to Conway is compared (EveryBlock); the 50 Byron blocks have no tick
      assertEquals(r.stats.ticks.size, blocks.count(_.era != Era.Byron))
      // and the epoch boundaries among them (the era changes are on boundaries)
      val crossings = r.events.collect { case Event.TickCompared(b, n, _) if n > 0 => b }
      assert(crossings.size >= 6, crossings.size.toString)
    }
  }

  // ---- the blocks of Conway (J2.4): the new side keeps its own state from the first of them

  test("the Conway blocks of the capture on the new rules: the verdicts and the states agree") {
    val conwayBlocks = blocks.count(_.era == Era.Conway)
    run(blocks.indices, NewRules.current[IO](), Policy.Halt, ticking = true).map { r =>
      assertEquals(r.halted, None)
      assertEquals(r.stats.failures, 0L)
      // the first block of Conway meets a state of Babbage, which the new side leaves to the old
      // one; the others are applied and compared with the old side's state after each
      assertEquals(r.stats.newBlocks.count, (conwayBlocks - 1).toLong)
      assert(r.paired.neu.isDefined, "the pair carries the new side's own state")
      assertEquals(r.stats.notPorted.get((Era.Conway, Stage.Block)), Some(1L))
    }
  }

  // ---- reseed everything: zero divergences, and what the conversion costs

  test("not ported, every block of the capture: zero divergences, the old states unchanged") {
    run(blocks.indices).map { r =>
      assertEquals(r.halted, None)
      assertEquals(r.events.collect { case d: Event.Diverged => d }, Vector.empty)
      assertEquals(r.stats.failures, 0L)
      assertEquals(r.stats.blocks, blocks.size.toLong)
      assertEquals(r.stats.sampled, blocks.size.toLong)
      assertEquals(r.paired.old, plain.last)
      // a side that is not ported holds no state of its own
      assertEquals(r.paired.neu, None)
      // every block is a rule that is not ported, and the report says in which era
      assertEquals(r.stats.notPorted.values.sum, blocks.size.toLong)
      assertEquals(
        r.stats.notPorted.keySet.map(_._1),
        Set(Era.Byron, Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway)
      )
      val toMirror = r.stats.samples.map(_.toMirrorNanos)
      println(
        f"conversion cost over the ${blocks.size} blocks of the eras fixture: toMirror " +
          f"median ${toMirror.sorted.apply(toMirror.size / 2) / 1e6}%.2f ms, " +
          f"mean ${toMirror.sum / toMirror.size / 1e6}%.2f ms, max ${toMirror.max / 1e6}%.2f ms"
      )
      println(Show[Stats].show(r.stats))
    }
  }

  test("sampled at the boundaries, the new side is converted at them only") {
    val sparse = for
      events <- Ref.of[IO, Vector[Event]](Vector.empty)
      r <- Handle
        .allow[NodeError] {
          Handle
            .allow[Divergence] {
              for
                harness <- DifferentialRules.create[IO](
                  NodeRules[IO](setup.praos, setup.globals, setup.byronRules),
                  NewRules.notPorted[IO],
                  IO.pure(env),
                  Sampling.Boundaries(100),
                  Policy.Halt,
                  Repro.none[IO],
                  e => events.update(_ :+ e)
                )
                _ <- blocks.indices.toList
                  .foldLeftM(Paired.of(setup.start))((p, i) => harness(p, headers(i), raws(i)))
                stats <- harness.stats
              yield stats
            }
            .rescue(d => IO.raiseError(RuntimeException(Show[Divergence].show(d))))
        }
        .rescue(e => IO.raiseError(RuntimeException(e.message)))
    yield r
    sparse.map { stats =>
      assertEquals(stats.blocks, blocks.size.toLong)
      assert(stats.sampled < blocks.size / 4, s"sampled ${stats.sampled}")
      assert(stats.sampled >= blocks.size / 100, s"sampled ${stats.sampled}")
    }
  }

  // ---- a refused block stays refused, as the same error

  test("a block the old rules refuse is refused as it was, through the harness") {
    val first = blocks.indexWhere(_.era == Era.Shelley)
    val before = Paired(plain(first + 1), None, Some(ref(headers(first))))
    val result = Handle
      .allow[NodeError] {
        Handle
          .allow[Divergence] {
            val old = NodeRules[IO](setup.praos, setup.globals, setup.byronRules)
            DifferentialRules
              .create[IO](
                old,
                NewRules.notPorted[IO],
                IO.pure(env),
                Sampling.EveryBlock,
                Policy.Halt,
                Repro.none[IO],
                _ => IO.unit
              )
              // a Byron block cannot follow the first Shelley block
              .flatMap(_(before, headers(10), raws(10)))
              .as(Option.empty[NodeError])
          }
          .rescue(d => IO.raiseError(RuntimeException(Show[Divergence].show(d))))
      }
      .rescue(e => IO.pure(Some(e)))
    result.map {
      case Some(v: ValidationError) =>
        val direct = NodeRules.applyBlock(
          before.old,
          headers(10),
          raws(10),
          setup.praos,
          setup.globals,
          setup.byronRules
        )
        assertEquals(v.failure.rule, BlockRule.Structure)
        assertEquals(direct.left.toOption, Some(v.failure))
      case other => fail(s"expected a validation error, got $other")
    }
  }

  // ---- divergences: a new side that answers, and is wrong

  /** The mirror of the old side's state after block `i`: what a correct new side would answer. */
  private def mirrorAfter(i: Int): MirrorState =
    MirrorRun
      .run(env)(_.toMirror(Some(ref(headers(i))), plain(i + 1)))
      .fold(e => fail(Show[StateMirrorError].show(e)), _.value)

  /** A new side that answers for the blocks of `era` with `answer`, given the block's index. */
  private def answering(era: Era, answer: Int => NewOutcome): NewRules[IO] = e =>
    Option.when(e == era)((input: BlockInput) =>
      IO.pure(answer(headers.indexWhere(_.hash == input.header.hash)))
    )

  private val conway = blocks.indexWhere(_.era == Era.Conway)

  /** The mirror with one lovelace more in the treasury: a new side that is wrong about the ledger.
    * (What is compared of two mirrors is their new-epoch state, as at the `Tick` stage.)
    */
  private def untipped(m: MirrorState): MirrorState =
    val state = m.ext.ledgerState.state match
      case i: EraLedgerState.InShelleyEra[e] =>
        val nes = i.state.nes
        val account = nes.es.chainAccount
        val richer = Coin
          .fromBigInt(account.treasury.value + 1)
          .fold(e => fail(e.toString), identity)
        EraLedgerState.InShelleyEra(
          i.era,
          i.state.copy(nes =
            nes.copy(es = nes.es.copy(chainAccount = account.copy(treasury = richer)))
          )
        )
      case other => other
    val telescope = Telescope
      .make(m.ext.ledgerState.past, m.ext.ledgerState.current.copy(state = state))
      .fold(e => fail(e.toString), identity)
    m.copy(ext = m.ext.copy(ledgerState = telescope))

  private def beforeConway = Paired.of(plain(conway), Some(ref(headers(conway - 1))))

  test("a new side that answers right agrees: its states are compared and equal") {
    val right = answering(Era.Conway, i => NewOutcome.Applied(mirrorAfter(i)))
    run(conway until conway + 3, right, from = beforeConway).map { r =>
      assertEquals(r.halted, None)
      assertEquals(r.stats.failures, 0L)
      assertEquals(r.stats.sampled, 3L)
      assertEquals(r.stats.notPorted, Map.empty)
      assertEquals(r.paired.old, plain(conway + 3))
    }
  }

  test("a new side whose state is wrong diverges, naming the path; under Halt the run stops") {
    val wrong = answering(Era.Conway, i => NewOutcome.Applied(untipped(mirrorAfter(i))))
    run(conway until conway + 3, wrong, from = beforeConway).map { r =>
      r.halted match
        case Some(Divergence.StateDiffers(block, era, stage, difference)) =>
          assertEquals(block, ref(headers(conway)))
          assertEquals(era, Era.Conway)
          assertEquals(stage, Stage.Block)
          assertEquals(difference.path.render, "es.chainAccount.treasury")
        case other => fail(s"expected a state divergence, got $other")
    }
  }

  test("under Reseed the run carries on from the old side, each divergence counted") {
    val wrong = answering(Era.Conway, i => NewOutcome.Applied(untipped(mirrorAfter(i))))
    run(conway until conway + 3, wrong, Policy.Reseed, from = beforeConway).map { r =>
      assertEquals(r.halted, None)
      assertEquals(r.stats.failures, 3L)
      assertEquals(r.events.count(_.isInstanceOf[Event.Diverged]), 3)
      assertEquals(r.paired.old, plain(conway + 3))
      // the pair after the last block holds the old side's mirror, not the wrong one
      assertEquals(r.paired.neu, Some(mirrorAfter(conway + 2)))
    }
  }

  test("a new side that refuses a block the old side took diverges in the verdict") {
    val refuses = answering(Era.Conway, _ => NewOutcome.Refused(BlockFailure.BodyCommitment))
    run(conway until conway + 1, refuses, from = beforeConway).map { r =>
      r.halted match
        case Some(Divergence.VerdictDiffers(_, _, old, neu)) =>
          assertEquals(old, Right(()))
          assertEquals(neu, Left(BlockFailure.BodyCommitment))
        case other => fail(s"expected a verdict divergence, got $other")
    }
  }

  // ---- the repro bundle

  test("the repro of a divergence is kept, reads back, and replays to the same state") {
    val dir = Files.createTempDirectory("differential-repro")
    val wrong = answering(Era.Conway, i => NewOutcome.Applied(untipped(mirrorAfter(i))))
    val pre = beforeConway
    for
      r <- run(
        conway until conway + 1,
        wrong,
        repro = Repro.files[IO](dir, IO.pure(env), None),
        from = pre
      )
      kept = r.events.collectFirst { case Event.Diverged(_, Some(bundle)) => bundle }
      path <- IO.fromOption(kept)(RuntimeException("no bundle kept"))
      bundle <- Repro.read[IO](path, IO.pure(env), setup.geometry.byron.epochSlots)
      // the rules as far as they are ported agree with the old ones (none are): the divergence of
      // this bundle, made by a new side the replay does not have, does not reproduce
      code <- DifferentialReplayMain.run(
        List(path.toString, "--genesis-dir", fixtures.resolve("genesis").toString)
      )
    yield
      assertEquals(code, ExitCode.Success)
      val read = bundle.fold(e => fail(e.message), identity)
      assertEquals(read.raw, raws(conway))
      assertEquals(read.header.hash, headers(conway).hash)
      assertEquals(read.pre.anchor, pre.at)
      assertEquals(read.description.hcursor.get[String]("kind"), Right("StateDiffers"))
      assertEquals(read.description.hcursor.get[String]("path"), Right("es.chainAccount.treasury"))
      // the state in the bundle is the one before the block, and applying the block gives the
      // state after it
      val replayed = NodeRules.applyBlock(
        read.state,
        read.header,
        read.raw,
        setup.praos,
        setup.globals,
        setup.byronRules
      )
      assertEquals(replayed.map(_.ledger.epoch), Right(plain(conway + 1).ledger.epoch))
      assertEquals(replayed.map(_.protocol), Right(plain(conway + 1).protocol))
      Files.walk(dir).iterator.asScala.toVector.reverse.foreach(Files.delete)
  }

  // ---- the node's own rules over the harness

  test(
    "Shadowed: the node's rules over the harness give the node's states, also after a rollback"
  ) {
    val wrong = answering(Era.Conway, i => NewOutcome.Applied(mirrorAfter(i)))
    val program = Handle
      .allow[NodeError] {
        Handle
          .allow[Divergence] {
            for
              harness <- DifferentialRules.create[IO](
                NodeRules[IO](setup.praos, setup.globals, setup.byronRules),
                wrong,
                IO.pure(env),
                Sampling.Boundaries(2),
                Policy.Halt,
                Repro.none[IO],
                _ => IO.unit
              )
              rules <- Shadowed(harness, 20L)
              // the Conway blocks, then the first of them again from the state before it: a fork
              states <- (conway until conway + 3).toList.foldLeftM(Vector(plain(conway))) {
                (acc, i) => rules(acc.last, headers(i), raws(i)).map(acc :+ _)
              }
              again <- rules(plain(conway), headers(conway), raws(conway))
              stats <- harness.stats
            yield (states, again, stats)
          }
          .rescue(d => IO.raiseError(RuntimeException(Show[Divergence].show(d))))
      }
      .rescue(e => IO.raiseError(RuntimeException(e.message)))
    program.map { (states, again, stats) =>
      assertEquals(states, plain.slice(conway, conway + 4))
      assertEquals(again, plain(conway + 1))
      assertEquals(stats.blocks, 4L)
      assertEquals(stats.failures, 0L)
    }
  }
