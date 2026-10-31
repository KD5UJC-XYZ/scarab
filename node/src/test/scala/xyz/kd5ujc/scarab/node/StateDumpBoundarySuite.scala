// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import cats.mtl.Handle
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.{Block, Header, StorageError}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.Era

/** [[StateDump.Dumper]] decides an epoch boundary from the state a block was applied to and the
  * state it produced, over the captured `devnet.py up-eras` chain (`fixtures/eras`): a Byron epoch,
  * then one Shelley-based epoch per protocol step.
  */
class StateDumpBoundarySuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  private val fixtures = Path.of("fixtures/eras")

  private val setup: NodeSetup =
    NodeSetup.fromDirectory(fixtures.resolve("genesis")).fold(e => fail(e.message), identity)

  private val headers: Vector[(Header, Bytes)] =
    Files
      .readAllLines(fixtures.resolve("blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map { line =>
        val raw = Bytes.fromHex(line).fold(e => fail(e.message), identity)
        Block
          .decode(raw, setup.geometry.byron.epochSlots)
          .fold(e => fail(e.message), b => b.header -> raw)
      }

  /** Each block with the state it was applied to and the state it produced, until the first block
    * that takes a Shelley-based state into a later epoch.
    */
  private lazy val steps: Vector[(Header, MirrorState, MirrorState)] =
    val all = headers.iterator
      .scanLeft(Option.empty[(Header, MirrorState, MirrorState)] -> setup.mirrorStart) {
        case ((_, state), (header, raw)) =>
          val next =
            MirrorSteps.block(setup, state, header, raw).fold(e => fail(e.toString), identity)
          Some((header, state, next)) -> next
      }
      .flatMap(_._1)
    val (before, rest) = all.span((_, a, b) => a.era == Era.Byron || a.ledgerEpoch == b.ledgerEpoch)
    before.toVector ++ rest.take(2).toVector

  private def digests(dir: Path): Vector[String] =
    Files
      .list(dir)
      .iterator
      .asScala
      .map(_.getFileName.toString)
      .filter(_.endsWith(".json"))
      .toVector

  private def after(
      dumper: StateDump.Dumper[IO, MirrorState],
      step: (Header, MirrorState, MirrorState)
  ) =
    Handle
      .allow[StorageError](
        dumper.afterBlock(step._1, step._2, step._3).map(_.asRight[StorageError])
      )
      .rescue(e => IO.pure(Left(e)))
      .map(_.fold(e => fail(e.toString), identity))

  test("a replacement of a rolled-back boundary block is a boundary; the end of Byron is not") {
    val handover = steps.indexWhere((_, a, b) => a.era == Era.Byron && b.era != Era.Byron)
    val boundary = steps.length - 2
    assert(handover >= 0 && handover < boundary, s"handover $handover, boundary $boundary")
    val (header, before, state) = steps(boundary)
    val shelley = header match
      case h: Header.Shelley => h
      case other             => fail(s"not a Shelley header: $other")
    val replacement = shelley.copy(hash = Bytes.wrap(Array.fill(32)(0x5a.toByte)))
    val dir = Files.createTempDirectory("state-dump-suite")
    val removed = IO.blocking {
      Files.list(dir).iterator.asScala.foreach(Files.delete)
      Files.delete(dir)
    }
    (for
      dumper <- StateDump.Dumper.create[IO, MirrorState](
        dir,
        StateDump.Targets.EpochBoundaries(1),
        None,
        _ => IO.unit
      )
      _ <- steps.take(boundary).traverse_(after(dumper, _))
      atHandover <- IO.blocking(digests(dir))
      _ <- after(dumper, steps(boundary))
      _ <- after(dumper, (replacement, before, state))
      atBoundary <- IO.blocking(digests(dir))
      _ <- after(dumper, steps(boundary + 1))
      atEnd <- IO.blocking(digests(dir))
    yield
      assertEquals(atHandover, Vector.empty, "no digest up to the block before the boundary")
      assertEquals(
        atBoundary.sorted,
        Vector(shelley, replacement)
          .map(h => f"${h.slot.toLong}%012d-${h.hash.hex}.json")
          .sorted,
        "one digest of the boundary block and one of its replacement"
      )
      assertEquals(atEnd.sorted, atBoundary.sorted, "a later block of the epoch is not a boundary")
    ).guarantee(removed)
  }
