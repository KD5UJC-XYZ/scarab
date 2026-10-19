// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.Ask
import cats.syntax.all.*
import java.nio.file.Files as JFiles
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, EraLedgerState, MirrorState}
import xyz.kd5ujc.scarab.ledger.{Block, ParamUpdate, StorageError}
import xyz.kd5ujc.scarab.ledger.state.{ShelleyBasedEra, ShelleyGovState}
import xyz.kd5ujc.scarab.node.{MirrorSteps, NodeSetup}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.node.read.InEra
import xyz.kd5ujc.scarab.protocol.Era

import StorageTestKit.*

/** V5 writes and reads every era in the block capture. Immutable V5 conversions of the previous
  * fixture corpus retain its independently replayed chain position and UTxO accounting.
  */
class SnapshotV5Suite extends CatsEffectSuite:
  private val fixtures = java.nio.file.Path.of("fixtures/eras")

  private val setup: NodeSetup =
    NodeSetup.fromDirectory(fixtures.resolve("genesis")).fold(e => fail(e.message), identity)
  private val env = setup.mirrorEnv()

  private given Ask[IO, ConsensusMirrorEnv] = Ask.const(env)
  private given v5: SnapshotFormat[IO, MirrorState] = SnapshotV5.format[IO](SnapshotCheck.Full)

  private val raws: Vector[Bytes] =
    JFiles
      .readAllLines(fixtures.resolve("blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e.message), identity))
  private val blocks: Vector[Block] =
    raws.map(Block.decode(_, setup.geometry.byron.epochSlots).fold(e => fail(e.message), identity))

  /** The snapshots the chain passes through: the state after each block, with its anchor. */
  private lazy val snapshots: Vector[(Era, StateSnapshot[MirrorState])] =
    blocks.indices
      .foldLeft(Vector.empty[(Era, StateSnapshot[MirrorState])]) { (acc, i) =>
        val before = acc.lastOption.fold(setup.mirrorStart)(_._2.state)
        val h = blocks(i).header
        MirrorSteps.block(setup, before, h, raws(i)) match
          case Right(next) =>
            acc :+ (blocks(i).era -> StateSnapshot(
              Some(BlockRef(h.slot, h.hash, h.blockNo, h.isBoundary)),
              next
            ))
          case Left(f) => fail(s"block $i rejected by ${f.rule.name}: ${f.reason}")
      }

  private def decoded(bytes: Array[Byte]): IO[StateSnapshot[MirrorState]] =
    v5.decode(bytes).flatMap(_.fold(e => IO.raiseError(new AssertionError(e.message)), IO.pure))

  /** The writer's verdict on each state: the content, or the refusal. */
  private lazy val written
      : Vector[(Era, StateSnapshot[MirrorState], Either[SnapshotRefusal, Array[Byte]])] =
    snapshots.map((era, s) => (era, s, v5.encode(s).unsafeRunSync()))

  /** The states the writer accepts. */
  private lazy val accepted: Vector[(Era, StateSnapshot[MirrorState], Array[Byte])] =
    written.collect { case (era, s, Right(bytes)) => (era, s, bytes) }

  test("the eras fixture reaches every era the format must hold") {
    assertEquals(snapshots.map(_._1).distinct.toSet, Era.values.toSet)
    assertEquals(accepted.map(_._1).distinct.toSet, Era.values.toSet)
  }

  private def sameState(back: MirrorState, written: MirrorState, clue: String): Unit =
    assertEquals(MirrorSteps.firstDifference(back, written), None, clue)

  test("every state of the eras fixture comes back from its V5 snapshot, as it was") {
    accepted.traverse_ { (era, snapshot, bytes) =>
      val label = s"$era block ${snapshot.anchor.map(_.blockNo)}"
      decoded(bytes).map { back =>
        assertEquals(back.anchor, snapshot.anchor, label)
        sameState(back.state, snapshot.state, label)
      }
    }
  }

  test("a state read back is written again to the same bytes: the format is stable") {
    accepted.traverse_ { (era, snapshot, bytes) =>
      for
        back <- decoded(bytes)
        again <- v5.encode(back).map(_.fold(r => fail(r.toString), identity))
      yield assert(java.util.Arrays.equals(bytes, again), s"$era block ${snapshot.anchor}")
    }
  }

  test(
    "the writer refuses no state of the eras fixture, Alonzo states with a pending update included"
  ) {
    val refused = written.collect { case (era, s, Left(r)) => (era, s.anchor.map(_.blockNo), r) }
    assertEquals(refused, Vector.empty)
    // the capture does hold Alonzo states with an update that raises the version
    val pending = snapshots.count((era, s) => era == Era.Alonzo && proposals(s.state).nonEmpty)
    assert(pending > 0, "the capture has no Alonzo state with a pending update")
    assertEquals(written.size, snapshots.size)
  }

  /** The update proposals of the current epoch of a state (before Conway), by genesis key hash. */
  private def proposals(m: MirrorState): Map[Bytes, ParamUpdate] =
    MirrorSteps
      .ledger(m)
      .flatMap { case InEra.Of(_, l) => l.ppup.toOption.map(_._1) }
      .getOrElse(Map.empty)

  /** The state with every proposal of the current epoch replaced by `update` (an Alonzo state). */
  private def withProposals(m: MirrorState, update: ParamUpdate): MirrorState =
    m.ext.ledgerState.state match
      case i: EraLedgerState.InShelleyEra[e] =>
        i.era match
          case ShelleyBasedEra.SAlonzo =>
            val ls = i.state.nes.es.ls
            val gov: ShelleyGovState = ls.utxo.gov
            val next = gov.copy(curProposals =
              xyz.kd5ujc.scarab.ledger.state.Sorted
                .map(gov.curProposals.toList.map((k, _) => k -> update))
            )
            val nes = i.state.nes
              .copy(es = i.state.nes.es.copy(ls = ls.copy(utxo = ls.utxo.copy(gov = next))))
            m.copy(ext =
              m.ext.copy(ledgerState =
                m.ext.ledgerState.withState(
                  EraLedgerState.InShelleyEra(i.era, i.state.copy(nes = nes))
                )
              )
            )
          case _ => fail("not an Alonzo state")
      case _ => fail("not a Shelley-based state")

  test("an Alonzo update naming key 17, the lovelace per word, is written and comes back") {
    val Some((_, base)) =
      snapshots.find((era, s) => era == Era.Alonzo && proposals(s.state).nonEmpty): @unchecked
    val key17 = Bytes.fromHex("199c40").fold(e => fail(e.message), identity)
    val raise = Bytes.fromHex("820700").fold(e => fail(e.message), identity)
    List(Map(17 -> key17), Map(14 -> raise, 17 -> key17)).traverse_ { fields =>
      val snapshot = base.copy(state = withProposals(base.state, ParamUpdate(fields)))
      v5.encode(snapshot).flatMap {
        case Right(bytes) =>
          decoded(bytes).map(back => sameState(back.state, snapshot.state, fields.toString))
        case Left(r) => IO(fail(s"refused $fields: $r"))
      }
    }
  }

  test("a Shelley-based snapshot has the Haskell state after the sidecar, Byron has none") {
    accepted.foreach { (era, _, bytes) =>
      val sidecar = java.nio.ByteBuffer.wrap(bytes, 8, 4).getInt
      val haskell = bytes.length - 12 - sidecar
      assertEquals(new String(bytes.take(8), "US-ASCII"), "SCRBSNP5")
      if era == Era.Byron then assertEquals(haskell, 0, era.toString)
      // [1, [telescope, header state]]: the state file of the reference node
      else assert(haskell > 0 && (bytes(12 + sidecar) & 0xff) == 0x82, era.toString)
    }
  }

  /** The framing as it was written before the copies were removed (#94, stage 0): the Haskell bytes
    * copied to an array, the output stream and its `toByteArray`.
    */
  private def framedWithCopies(sidecar: SnapshotSidecar, haskell: Array[Byte]): Array[Byte] =
    val body = SnapshotSidecar.fingerprint ++ Codec.encode(sidecar)(using SnapshotSidecar.codec)
    val out = new java.io.ByteArrayOutputStream(12 + body.length + haskell.length)
    out.write(SnapshotV5.Magic)
    out.write(java.nio.ByteBuffer.allocate(4).putInt(body.length).array())
    out.write(body)
    out.write(haskell)
    out.toByteArray

  test("the framing without copies writes the bytes that the framing with copies wrote") {
    accepted.foreach { (era, _, bytes) =>
      val sidecar = SnapshotV5.sidecarOf(bytes).fold(e => fail(e.message), identity)
      val length = java.nio.ByteBuffer.wrap(bytes, 8, 4).getInt
      val haskell = Bytes.wrap(bytes).drop(12 + length)
      assert(
        java.util.Arrays.equals(framedWithCopies(sidecar, haskell.toArray), bytes),
        era.toString
      )
      assert(java.util.Arrays.equals(SnapshotV5.framed(sidecar, haskell), bytes), era.toString)
    }
  }

  test("the structure check writes the bytes the full check writes, and refuses the same states") {
    val structure = SnapshotV5.format[IO](SnapshotCheck.Structure)
    written.traverse_ { (era, snapshot, full) =>
      structure.encode(snapshot).map { cheap =>
        full match
          case Right(bytes) =>
            assert(cheap.exists(java.util.Arrays.equals(_, bytes)), s"$era ${snapshot.anchor}")
          // a state the full check refuses only on reading it back may pass the structure check;
          // what the encoder refuses, both refuse
          case Left(SnapshotRefusal.WouldNotRead(_) | SnapshotRefusal.WouldDiffer(_)) => ()
          case Left(refused) => assertEquals(cheap.left.toOption, Some(refused))
      }
    }
  }

  test("the structure check refuses content whose Haskell section is not one CBOR item") {
    accepted.filter(_._1 != Era.Byron).foreach { (era, _, bytes) =>
      assert(SnapshotV5.wellFormed(bytes).isRight, era.toString)
      assert(SnapshotV5.wellFormed(bytes.dropRight(1)).isLeft, s"$era truncated")
      assert(SnapshotV5.wellFormed(bytes :+ 0.toByte).isLeft, s"$era trailing byte")
    }
    assertEquals(SnapshotCheck.parse("structure"), Some(SnapshotCheck.Structure))
    assertEquals(SnapshotCheck.parse("FULL"), Some(SnapshotCheck.Full))
    assertEquals(SnapshotCheck.parse("none"), None)
  }

  test("the store writes the content and then its checksum: the file is the closed content") {
    tempDir.use { dir =>
      ok {
        FileLedgerSnapshots.open[IO, MirrorState](dir, keep = 100).flatMap { store =>
          snapshots.takeRight(2).traverse { (_, s) =>
            for
              content <- v5.encode(s).map(_.fold(r => fail(r.toString), identity))
              written <- store.write(s).map(_.fold(r => fail(r.toString), identity))
              file = JFiles.readAllBytes((dir / written.name).toNioPath)
            yield
              assert(java.util.Arrays.equals(file, FileLedgerSnapshots.closed(content)))
              assertEquals(written.bytes, file.length.toLong)
          }
        }
      }.void
    }
  }

  test("a damaged V5 file is a typed error: magic, sidecar length, fingerprint, Haskell section") {
    val bytes = accepted.last._3
    val cases: List[(String, Array[Byte])] = List(
      "magic" -> bytes.updated(7, '6'.toByte),
      "sidecar length" -> bytes.updated(8, 0x7f.toByte),
      "fingerprint" -> bytes.updated(12, (bytes(12) ^ 1).toByte),
      "haskell section" -> bytes.take(bytes.length - 40),
      "too short" -> bytes.take(5)
    )
    cases.traverse_((what, damaged) => v5.decode(damaged).map(r => assert(r.isLeft, what)))
  }

  test(
    "current migration preserves files, refuses future versions and leaves missing stores absent"
  ) {
    tempDir.use { dir =>
      val missing = dir / "missing"
      for
        absent <- ok(SnapshotMigration.toCurrent[IO].run(missing))
        empty <- ok(SnapshotMigration.toCurrent[IO].run(dir))
        _ <- IO {
          assertEquals(absent, SnapshotMigration.Report.empty)
          assertEquals(empty, SnapshotMigration.Report.empty)
          assert(!JFiles.exists(missing.toNioPath))
          val listing = JFiles.list(dir.toNioPath)
          try assert(!listing.findAny().isPresent)
          finally listing.close()
        }
        (_, snapshot, content) = accepted.lastOption.getOrElse(fail("no accepted V5 state"))
        anchor = snapshot.anchor.getOrElse(fail("V5 fixture has no anchor"))
        current = dir / FileLedgerSnapshots.nameOf(anchor)
        original = FileLedgerSnapshots.closed(content)
        _ <- IO(JFiles.write(current.toNioPath, original))
        modified <- IO(JFiles.getLastModifiedTime(current.toNioPath))
        first <- ok(SnapshotMigration.toCurrent[IO].run(dir))
        second <- ok(SnapshotMigration.toCurrent[IO].run(dir))
        _ <- IO {
          assertEquals(first, SnapshotMigration.Report.empty)
          assertEquals(second, SnapshotMigration.Report.empty)
          assert(java.util.Arrays.equals(JFiles.readAllBytes(current.toNioPath), original))
          assertEquals(JFiles.getLastModifiedTime(current.toNioPath), modified)
        }
        future = dir / "snapshot-99999999999999999999-9.bin"
        futureBytes = "SCRBSNP9".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
        _ <- IO(JFiles.write(future.toNioPath, futureBytes))
        refused <- attempt(SnapshotMigration.toCurrent[IO].run(dir))
        _ <- IO {
          assert(refused.left.exists(_.isInstanceOf[StorageError.UnknownVersion]))
          assert(java.util.Arrays.equals(JFiles.readAllBytes(current.toNioPath), original))
          assertEquals(JFiles.getLastModifiedTime(current.toNioPath), modified)
          assert(java.util.Arrays.equals(JFiles.readAllBytes(future.toNioPath), futureBytes))
          JFiles.delete(future.toNioPath)
        }
        damaged = original.updated(original.length - 1, (original.last ^ 1).toByte)
        _ <- IO(JFiles.write(current.toNioPath, damaged))
        unchanged <- ok(SnapshotMigration.toCurrent[IO].run(dir))
        restored <- ok(
          FileLedgerSnapshots.open[IO, MirrorState](dir).flatMap(_.restore(_ => IO.pure(true)))
        )
      yield
        // Migration probes only current-version headers; the ordinary reader verifies the checksum.
        assertEquals(unchanged, SnapshotMigration.Report.empty)
        assert(java.util.Arrays.equals(JFiles.readAllBytes(current.toNioPath), damaged))
        assert(FileLedgerSnapshots.verified(damaged).isLeft)
        assertEquals(restored.snapshot, None)
        assertEquals(restored.skipped.map(_.name), Vector(current.fileName.toString))
    }
  }

  // ---- the verified current-format corpus converted from the previous era fixtures

  private lazy val recorded: Vector[(String, Array[Byte], StateSnapshot[MirrorState])] =
    val dir = java.nio.file.Path.of("fixtures/snapshot-v5/eras")
    val listing = JFiles.list(dir)
    try
      listing.iterator.asScala.toVector.sortBy(_.getFileName.toString).map { file =>
        val name = file.getFileName.toString
        val blockNo = BigInt(name.stripSuffix(".bin").split('-').last)
        val expected = snapshots
          .map(_._2)
          .find(_.anchor.exists(_.blockNo == blockNo))
          .getOrElse(fail(s"$name: no state after block $blockNo"))
        val content = FileLedgerSnapshots
          .verified(JFiles.readAllBytes(file))
          .fold(e => fail(e.message), identity)
        (name, content, expected)
      }
    finally listing.close()

  /** The historical bookkeeping is not claimed equal to the current rules. The conversion's
    * position, consensus state and UTxO accounting are independently anchored in the block capture.
    */
  private def sameChain(back: MirrorState, rules: MirrorState, clue: String): Unit =
    assertEquals(back.era, rules.era, clue)
    assertEquals(back.ext.headerState, rules.ext.headerState, clue)
    assertEquals(back.ext.ledgerState.past, rules.ext.ledgerState.past, clue)
    assertEquals(back.consensus, rules.consensus, clue)
    assertEquals(back.plutus, rules.plutus, clue)
    assertEquals(back.utxo.stake, rules.utxo.stake, clue)
    assertEquals(back.utxo.coin, rules.utxo.coin, clue)
    assertEquals(back.utxo.count, rules.utxo.count, clue)

  test("recorded V5 replacements retain each era's chain position and UTxO accounting") {
    assertEquals(recorded.map(_._1.takeWhile(_ != '-')).toSet.size, 7)
    recorded.traverse_ { (name, content, expected) =>
      decoded(content).map { back =>
        assertEquals(back.anchor, expected.anchor, name)
        sameChain(back.state, expected.state, name)
      }
    }
  }
