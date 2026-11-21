// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import cats.effect.{ExitCode, IO}
import cats.mtl.Handle
import cats.syntax.all.*
import java.nio.file.{Files as JFiles, Path as JPath}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Block, BlockFailure, ChainError, DecodeSite, LedgerDecodeError}
import xyz.kd5ujc.scarab.consensus.BlockNoVerdict
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.protocol.{ChainSync, DijkstraNotYet}
import xyz.kd5ujc.scarab.storage.LeiosStateFixture
import xyz.kd5ujc.scarab.storage.StorageTestKit.tempDir
import xyz.kd5ujc.scarab.storage.haskell.{BlockPosition, ChunkSlots}
import xyz.kd5ujc.scarab.storage.haskell.HaskellDbKit.{Item, write}

import LeiosReplayMain.{ByteDifference, Error, Located, Outcome, Window}

/** The offline Leios replay on the devnet state of `fixtures/leios/state` with its tip moved to the
  * first Dijkstra block of `fixtures/dijkstra-devnet` ([[LeiosStateFixture]]), whose chain is
  * staged as a reference ImmutableDB: a window that applies no block is the start state again, byte
  * for byte, through import, the node's start and export; a window with a block after the start
  * halts at that block's slot while the node's rules apply no Dijkstra block
  * ([[DijkstraNotYet.Rules]], #85 D8).
  */
class LeiosReplayMainSuite extends FailsOnUncaught:
  override def munitIOTimeout: Duration = 3.minutes

  private val setup = LeiosStateFixture.setup
  private val config = JPath.of("fixtures/leios/state/genesis/config.json")
  private val chunk: ChunkSlots = setup.immutableChunkSlots.getOrElse(fail("chunk size"))

  /** The devnet chain of `fixtures/dijkstra-devnet` up to `slot`, as a reference ImmutableDB. */
  private def reference(root: JPath, slot: BigInt): Unit =
    val items = JFiles
      .readAllLines(JPath.of("fixtures/dijkstra-devnet/blocks.tsv"))
      .toArray(Array.empty[String])
      .toVector
      .drop(1)
      .map(row => Bytes.fromHex(row.split('\t')(4)).fold(e => fail(e.message), identity))
      .map { raw =>
        val h = Block
          .decode(raw, setup.geometry.byron.epochSlots)
          .fold(e => fail(e.message), identity)
          .header
        Item(
          BlockPosition.Regular(h.slot.toLong),
          raw.toArray,
          h.hash,
          raw.toArray.indexOfSlice(h.raw.toArray),
          h.raw.size
        )
      }
      .filter(_.position match
        case BlockPosition.Regular(s) => s <= slot
        case BlockPosition.Ebb(_)     => false)
    write(root, setup.identity.magic, chunk, items)

  /** A reference ledger directory `<root>/<slot>` holding `files`. */
  private def ledger(root: JPath, slot: BigInt, files: Vector[(String, Bytes)]): JPath =
    val dir = root.resolve(slot.toString)
    JFiles.createDirectories(dir)
    files.foreach((name, bytes) => JFiles.write(dir.resolve(name), bytes.toArray))
    dir

  private def run(window: Window): IO[Either[Error, Outcome]] =
    Handle
      .allow[Error](LeiosReplayMain.window(window).map(_.asRight[Error]))
      .rescue(e => IO.pure(Left(e)))

  private val start = LeiosStateFixture.first.point
  private val next = LeiosStateFixture.dijkstraBlocks.lift(1).getOrElse(fail("a second block"))

  private def windowOf(root: JPath, startDir: JPath, endDir: JPath, last: BigInt) =
    IO.blocking(reference(root.resolve("haskell"), last))
      .as(
        Window(
          config,
          root.resolve("haskell"),
          startDir,
          endDir,
          root.resolve("db"),
          root.resolve("out")
        )
      )

  test("a window of no block is its start state again, state, tables and meta byte for byte") {
    tempDir.use { dir =>
      val root = dir.toNioPath
      val files = LeiosStateFixture.movedFiles
      for
        startDir <- IO.blocking(ledger(root.resolve("start"), start.slot, files))
        window <- windowOf(root, startDir, startDir, start.slot)
        outcome <- run(window)
      yield
        assertEquals(
          outcome,
          Right(Outcome.Matched(start, 0, LeiosReplayMain.Compared, Vector.empty))
        )
        assertEquals(outcome.map(LeiosReplayMain.exitCode), Right(ExitCode.Success))
    }
  }

  test("an end directory without tables and meta is not a pass: they are reported not compared") {
    tempDir.use { dir =>
      val root = dir.toNioPath
      val files = LeiosStateFixture.movedFiles
      for
        startDir <- IO.blocking(ledger(root.resolve("start"), start.slot, files))
        endDir <- IO.blocking(ledger(root.resolve("end"), start.slot, files.take(1)))
        window <- windowOf(root, startDir, endDir, start.slot)
        outcome <- run(window)
      yield
        assertEquals(
          outcome,
          Right(Outcome.Matched(start, 0, Vector("state"), Vector("tables", "meta")))
        )
        assertEquals(outcome.map(LeiosReplayMain.exitCode), Right(ExitCode(6)))
    }
  }

  test("the replay halts at the first Dijkstra block after the start, with its slot and hash") {
    tempDir.use { dir =>
      val root = dir.toNioPath
      for
        startDir <- IO.blocking(
          ledger(root.resolve("start"), start.slot, LeiosStateFixture.movedFiles)
        )
        // the end state is not read before the replay reaches it
        endDir <- IO.blocking(ledger(root.resolve("end"), next.point.slot, Vector.empty))
        window <- windowOf(root, startDir, endDir, next.point.slot)
        outcome <- run(window)
      yield
        val point = ChainSync.Point.Block(
          ChainSync.UInt64.saturating(next.point.slot),
          next.point.hash
        )
        assertEquals(outcome, Right(Outcome.Halted(point, DijkstraNotYet.Rules, 0)))
        assertEquals(outcome.map(LeiosReplayMain.exitCode), Right(ExitCode(3)))
        assert(!JFiles.exists(root.resolve("out")), "a halted window exports nothing")
    }
  }

  test("a database that is not fresh is refused before anything is written") {
    tempDir.use { dir =>
      val root = dir.toNioPath
      for
        startDir <- IO.blocking(
          ledger(root.resolve("start"), start.slot, LeiosStateFixture.movedFiles)
        )
        window <- windowOf(root, startDir, startDir, start.slot)
        _ <- IO.blocking(JFiles.createDirectories(window.db.resolve("immutable")))
        outcome <- run(window)
      yield assertEquals(outcome, Left(Error.NotFresh(window.db)))
    }
  }

  test("an end directory not named after a slot is refused") {
    val window =
      Window(config, JPath.of("h"), JPath.of("1"), JPath.of("end"), JPath.of("d"), JPath.of("o"))
    run(window).map(outcome => assertEquals(outcome, Left(Error.EndSlot("end"))))
  }

  test("a state that differs is located: the first byte, and the first difference as a state") {
    tempDir.use { dir =>
      val root = dir.toNioPath
      // the moved state against the state it was moved from: the header's tip differs
      val original =
        Vector("state", "tables", "meta").map(n => n -> LeiosStateFixture.read("dijkstra-203", n))
      for
        expected <- IO.blocking(ledger(root.resolve("expected"), 203, original))
        actual <- IO.blocking(ledger(root.resolve("actual"), 203, LeiosStateFixture.movedFiles))
        outcome <- LeiosReplayMain.compare(LeiosStateFixture.layout, expected, actual, start, 0)
      yield outcome match
        case Outcome.Differs(_, _, first, located, others) =>
          assertEquals(first.file, "state")
          assertEquals(others.map(_.file), Vector("meta"))
          located match
            case Some(Located.At(d)) => assert(d.path.render.nonEmpty, d.toString)
            case other               => fail(s"not located as a state: $other")
        case other => fail(s"not a difference: $other")
    }
  }

  test("a file that is a prefix of the other differs where it ends") {
    tempDir.use { dir =>
      val a = dir / "a"
      val b = dir / "b"
      for
        _ <- IO.blocking {
          JFiles.write(a.toNioPath, Array[Byte](1, 2, 3))
          JFiles.write(b.toNioPath, Array[Byte](1, 2, 3, 4))
        }
        same <- LeiosReplayMain.firstDifference("x", a, a)
        longer <- LeiosReplayMain.firstDifference("x", a, b)
      yield
        assertEquals(same, None)
        assertEquals(longer, Some(ByteDifference("x", 3, None, Some(4), 3, 4)))
    }
  }

  test("the end's slot is --end-slot where the directory is not named after it") {
    val args = List("--config", "c", "--haskell-db", "h", "--start", "s", "--end", "e", "--db", "d")
    assertEquals(
      LeiosReplayMain.parse(args ++ List("--out", "o", "--end-slot", "2168639")).map(_.endSlot),
      Right(Some(BigInt(2168639)))
    )
    assertEquals(LeiosReplayMain.parse(args ++ List("--out", "o")).map(_.endSlot), Right(None))
    assertEquals(
      LeiosReplayMain.parse(args ++ List("--out", "o", "--end-slot", "x")),
      Left(Error.EndSlot("x"))
    )
  }

  test(
    "a part not implemented yet is a halt wherever the rules put it; any other failure a refusal"
  ) {
    val point =
      ChainSync.Point.Block(ChainSync.UInt64.saturating(204), Bytes.wrap(Array.fill(32)(1)))
    val id = Bytes.wrap(Array.fill(32)(2))
    val notYet = LedgerDecodeError.NotYet(DijkstraNotYet.Rules)
    val halts = Vector(
      BlockFailure.NotYet(DijkstraNotYet.Rules),
      BlockFailure.Decode(ChainError.Decode(DecodeSite.Index(0), notYet)),
      BlockFailure.Decode(
        ChainError.Decode(DecodeSite.Index(0), LedgerDecodeError.Transaction(id, notYet))
      ),
      BlockFailure.TxDecode(id, notYet)
    )
    halts.foreach(f =>
      assertEquals(
        LeiosReplayMain.verdict(point, f, 3),
        Outcome.Halted(point, DijkstraNotYet.Rules, 3)
      )
    )
    val refusals = Vector(BlockFailure.HeaderDiffers, BlockFailure.BodyCommitment)
    refusals.foreach(f =>
      assertEquals(LeiosReplayMain.verdict(point, f, 3), Outcome.Refused(point, f, 3))
    )
  }

  test(
    "a block withheld for a part the node does not hold yet is not a pass; other errors stay errors"
  ) {
    val cause = BlockNoVerdict.StateOutOfRange(ChainError.NoIntersection)
    assertEquals(LeiosReplayMain.exitCode(Outcome.Withheld(204, cause, 1)), ExitCode(3))
    assert(!LeiosReplayMain.noVerdictNotYet.isDefinedAt(ChainError.NoIntersection))
  }

  test("the slot of a reference ledger directory is its name") {
    assertEquals(LeiosReplayMain.slotOf(JPath.of("x/1943999")), Right(BigInt(1943999)))
    assertEquals(LeiosReplayMain.slotOf(JPath.of("x/-1")), Left(Error.EndSlot("-1")))
  }
