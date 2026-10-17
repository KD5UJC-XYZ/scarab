// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.{Ask, Raise}
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import java.nio.file.Files as JFiles
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, ConsensusExtras}
import xyz.kd5ujc.scarab.consensus.state.{
  ConsensusMirrorEnv,
  EraLedgerState,
  ExtLedgerState,
  MirrorExtras,
  MirrorState,
  MirrorStep
}
import xyz.kd5ujc.scarab.ledger.{Block, FileKind, FormatVersion, ParamUpdate, StorageError, Utxo}
import xyz.kd5ujc.scarab.ledger.rules.EraLedger
import xyz.kd5ujc.scarab.ledger.state.{ShelleyBasedEra, ShelleyGovState, SlotNo, Sorted, WithOrigin}
import xyz.kd5ujc.scarab.legacy
import xyz.kd5ujc.scarab.legacy.Located
import xyz.kd5ujc.scarab.node.{MirrorSteps, NodeSetup}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.node.read.InEra
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.HaskellLedgerFile

import StorageTestKit.*

/** The snapshot format V5 and the migration to it, on the era-walking capture (`fixtures/eras`):
  * the state before the first block and after every block, from Byron to Conway, is written as a V5
  * snapshot and read back as it was, and the files of the older formats (V1 and V2 of generated
  * states, V3 of the build before J1.5, V4 of the builds that held the old rules' bookkeeping in
  * the sidecar) are migrated.
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
      .map(l => Bytes.fromHex(l).fold(e => fail(e), identity))
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
              Sorted.map(gov.curProposals.toList.map((k, _) => k -> update))
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
    val key17 = Bytes.fromHex("199c40").fold(e => fail(e), identity)
    val raise = Bytes.fromHex("820700").fold(e => fail(e), identity)
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

  // ---- the migration

  private def restoreAll(dir: Path): IO[List[StateSnapshot[MirrorState]]] =
    ok {
      FileLedgerSnapshots.open[IO, MirrorState](dir, keep = 100).flatMap { store =>
        store.list.flatMap(
          _.toList.traverse(n =>
            store
              .restore(a => IO.pure(a.exists(x => FileLedgerSnapshots.nameOf(x) == n)))
              .map(_.snapshot.map(_._2).getOrElse(fail(s"$n does not read")))
          )
        )
      }
    }

  private def migration(using Ask[IO, ConsensusMirrorEnv], Raise[IO, StorageError]) =
    SnapshotMigration.toCurrent[IO]

  private def writeFile(dir: Path, anchor: Option[BlockRef], content: Array[Byte]): IO[String] =
    IO {
      val name = FileLedgerSnapshots.nameOf(anchor.getOrElse(fail("a snapshot of the origin")))
      JFiles.createDirectories(dir.toNioPath)
      JFiles.write((dir / name).toNioPath, FileLedgerSnapshots.closed(content))
      name
    }

  // -- V1 and V2: the frozen reader, brought to the mirror by the frozen conversion

  private val generating: ConsensusMirrorEnv = env

  private def at(anchor: Option[BlockRef]): WithOrigin[SlotNo] =
    anchor.fold[WithOrigin[SlotNo]](WithOrigin.Origin)(a =>
      WithOrigin.At(SlotNo.fromUnsigned(a.slot.toLong))
    )

  /** The flat node state a state of the eras fixture stands for: what the old rules held at it. */
  private def flatState(s: StateSnapshot[MirrorState]): legacy.FlatNodeState =
    val m = s.state
    m.ledger(s.anchor) match
      case Right(Some(InEra.Of(era, read))) =>
        val ledger = read.nes
          .flatMap(nes =>
            legacy.LedgerMirrorBack.flatOf(env.ledger, at(s.anchor), nes, m.plutus)(using era)
          )
          .fold(e => fail(s"flat read-back: $e"), identity)
        legacy.FlatNodeState(m.protocol, ledger, m.consensus.boot, m.consensus.byron)
      case Right(None) =>
        legacy.FlatNodeState(
          m.protocol,
          legacy.FlatTestLedger.state(0, Vector.empty),
          m.consensus.boot,
          m.consensus.byron
        )
      case Left(e) => fail(s"no ledger state: $e")

  /** Flat states of three families of era, with a tip. */
  private lazy val flats: Vector[Located] =
    Vector(
      snapshots.find(_._1 == Era.Byron),
      snapshots.findLast(_._1 == Era.Alonzo),
      snapshots.findLast(_._1 == Era.Conway)
    ).flatten.map((_, s) => Located(s.anchor, flatState(s)))

  /** What a V2 file of a flat state is brought to: the state the frozen reader gives, in the
    * mirror.
    */
  private def expectedOf(l: Located): StateSnapshot[MirrorState] =
    val upgraded = legacy.Upgrade
      .node(legacy.Downgrade.node(l.state), generating.forks, l.anchor)
      .fold(e => fail(e.toString), identity)
    StateSnapshot(
      l.anchor,
      legacy.Mirroring
        .toMirror(generating, l.anchor, upgraded)
        .fold(e => fail(e.toString), _.value)
    )

  private def writeV2(dir: Path, ls: Vector[Located]): IO[List[String]] =
    ls.toList.traverse { l =>
      val upgraded = legacy.Upgrade
        .node(legacy.Downgrade.node(l.state), generating.forks, l.anchor)
        .fold(e => fail(e.toString), identity)
      val content = legacy.SnapshotV2.encode(
        legacy.StateSnapshot(l.anchor, legacy.Downgrade.node(upgraded))
      )(using legacy.NodeCodecs.nodeSnapshot)
      writeFile(dir, l.anchor, content)
    }

  test("version 2 snapshots of every family of era are migrated, once, and read back as V5") {
    tempDir.use { dir =>
      for
        _ <- writeV2(dir, flats)
        first <- ok(migration.run(dir))
        second <- ok(migration.run(dir))
        back <- restoreAll(dir)
      yield
        assertEquals(
          first,
          SnapshotMigration.Report(
            List(Migrated(FileKind.SnapshotFile, FormatVersion.V2, FormatVersion.V5, 3)),
            Nil
          )
        )
        assertEquals(second, SnapshotMigration.Report.empty)
        assertEquals(back.map(_.anchor).toSet, flats.map(_.anchor).toSet)
        assertEquals(
          back.map(b => b.anchor -> b.state.era).toMap,
          flats.map(l => l.anchor -> expectedOf(l).state.era).toMap
        )
    }
  }

  test("version 1 snapshots (the former package names) are migrated through version 2") {
    tempDir.use { dir =>
      val l = flats.last
      for
        names <- writeV2(dir, Vector(l))
        file = (dir / names.head).toNioPath
        // as the build before the rename wrote it: number 1, another fingerprint, the same payload
        content = FileLedgerSnapshots
          .verified(JFiles.readAllBytes(file))
          .fold(e => fail(e.message), identity)
        old = content.clone()
        _ = java.nio.ByteBuffer.wrap(old, 8, 4).putInt(1)
        _ = java.util.Arrays.fill(old, 12, 20, 0x5a.toByte)
        _ = JFiles.write(file, FileLedgerSnapshots.closed(old))
        report <- ok(migration.run(dir))
        back <- restoreAll(dir)
      yield
        assertEquals(
          report.migrated,
          List(Migrated(FileKind.SnapshotFile, FormatVersion.V1, FormatVersion.V5, 1))
        )
        assertEquals(back.map(_.anchor), List(l.anchor))
        sameState(back.head.state, expectedOf(l).state, "V1")
    }
  }

  // -- V3: the files the build before J1.5 wrote

  /** The V3 files that the build before J1.5 wrote of states of the eras fixture, named
    * `<era>-<block number>.bin` (fixtures/snapshot-v3/eras), each with the state the rules give
    * after that block.
    */
  private lazy val v3Files: Vector[(String, Path, StateSnapshot[MirrorState])] =
    val dir = java.nio.file.Path.of("fixtures/snapshot-v3/eras")
    JFiles
      .list(dir)
      .iterator
      .asScala
      .toVector
      .sortBy(_.getFileName.toString)
      .map { f =>
        val name = f.getFileName.toString
        val blockNo = BigInt(name.stripSuffix(".bin").split('-').last)
        val expected = snapshots
          .map(_._2)
          .find(_.anchor.exists(_.blockNo == blockNo))
          .getOrElse(fail(s"$name: no state after block $blockNo"))
        (name, Path.fromNioPath(f), expected)
      }

  /** What a migrated file must have of the state the rules give after its block: the chain's
    * position and bookkeeping and the UTxO set. (The old rules' state carried the reward update of
    * a pool-less amount list and the pulser of the era translation with the fields Conway's
    * parameters lack; the migration puts those in the terms of the era-indexed rules, which is what
    * `LegacySeedSuite` asserts, so the ledger state is not compared whole.)
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

  test("V3 snapshots written by the previous build are migrated to the chain the rules give") {
    assert(v3Files.map(_._1.takeWhile(_ != '-')).toSet.size >= 7, "every era must be covered")
    v3Files.traverse_ { (name, file, expected) =>
      tempDir.use { dir =>
        val target = dir / FileLedgerSnapshots.nameOf(expected.anchor.get)
        for
          _ <- Files.forAsync[IO].copy(file, target)
          v3 = FileLedgerSnapshots
            .verified(JFiles.readAllBytes(target.toNioPath))
            .flatMap(legacy.SnapshotV3.sections)
            .fold(e => fail(e.message), _._1)
          report <- ok(migration.run(dir))
          again <- ok(migration.run(dir))
          back <- restoreAll(dir)
          bytes = FileLedgerSnapshots
            .verified(JFiles.readAllBytes(target.toNioPath))
            .fold(e => fail(e.message), identity)
          v5sidecar = SnapshotV5.sidecarOf(bytes).fold(e => fail(e.message), identity)
        yield
          assertEquals(
            report,
            SnapshotMigration.Report(
              List(Migrated(FileKind.SnapshotFile, FormatVersion.V3, FormatVersion.V5, 1)),
              Nil
            ),
            name
          )
          assertEquals(again, SnapshotMigration.Report.empty, name)
          assertEquals(back.map(_.anchor), List(expected.anchor), name)
          sameChain(back.head.state, expected.state, name)
          // nothing is invented: the gaps and the eras seen are those of the file replaced
          assertEquals(v5sidecar.gaps, v3.gaps, name)
          assertEquals(v5sidecar.observed, v3.observed, name)
          assertEquals(v5sidecar.anchor, v3.anchor, name)
      }
    }
  }

  // -- V4: the files the builds before this one wrote

  /** The V4 file of a state, as the builds that had the old rules' bookkeeping in the sidecar wrote
    * it (the frozen writer of the `legacy` module): the same Haskell section, and the extras `old`
    * (the state's own bookkeeping otherwise).
    */
  private def v4Of(s: StateSnapshot[MirrorState], old: legacy.NodeExtras): Array[Byte] =
    val sidecar = (s.state.extras, s.state.ext.ledgerState.state) match
      case (MirrorExtras.InByron(pending, boot, protocol), EraLedgerState.ByronCurrent(b)) =>
        legacy.v4.SnapshotSidecar(
          s.anchor,
          env.observed,
          Set.empty,
          legacy.v4.SnapshotSidecar.Chain
            .InByron(flats.head.state.ledger, pending, ConsensusExtras(boot, b, protocol))
        )
      case (shelley: MirrorExtras.InShelley, i: EraLedgerState.InShelleyEra[e]) =>
        given ShelleyBasedEra[e] = i.era
        val gov = i.state.nes.es.ls.utxo.gov
        legacy.v4.SnapshotSidecar(
          s.anchor,
          env.observed,
          Set(xyz.kd5ujc.scarab.ledger.state.Gap.NonMyopic),
          legacy.v4.SnapshotSidecar.Chain.InShelley(
            i.state.nes.es.ls.utxo.utxo.utxo,
            EraLedger.curPParams(gov),
            Some(EraLedger.prevPParams(gov)),
            legacy.v4.MirrorExtras.InShelley(old, shelley.consensus)
          )
        )
      case _ => fail("extras of another kind than the ledger state")
    val haskell = s.state.ext.ledgerState.state match
      case _: EraLedgerState.ByronCurrent => Array.emptyByteArray
      case _ =>
        HaskellLedgerFile
          .of(s.state.ext)
          .flatMap(f => HaskellLedgerFile.encode(f).left.map(e => fail(e.toString)))
          .fold(e => fail(e.toString), _.toArray)
    legacy.v4.SnapshotV4.framed(sidecar, haskell)

  private def oldExtrasOf(s: MirrorState): legacy.NodeExtras =
    legacy.NodeExtras.empty.copy(plutus = s.plutus)

  test("the frozen V4 sidecar has the fingerprint of the sidecar that version wrote") {
    assertEquals(
      legacy.v4.SnapshotSidecar.fingerprint.map(b => f"${b & 0xff}%02x").mkString,
      "467459aeddcceecd"
    )
  }

  test("V4 snapshots of every era are migrated, once, to the state they hold") {
    tempDir.use { dir =>
      val chosen = snapshots.filter(_._1 == Era.Byron).take(1) ++
        Era.values.toVector.drop(1).flatMap(e => snapshots.findLast(_._1 == e))
      for
        _ <- chosen.traverse_((_, s) => writeFile(dir, s.anchor, v4Of(s, oldExtrasOf(s.state))))
        first <- ok(migration.run(dir))
        second <- ok(migration.run(dir))
        back <- restoreAll(dir)
      yield
        assertEquals(
          first,
          SnapshotMigration.Report(
            List(
              Migrated(FileKind.SnapshotFile, FormatVersion.V4, FormatVersion.V5, chosen.size)
            ),
            Nil
          )
        )
        assertEquals(second, SnapshotMigration.Report.empty)
        assertEquals(back.map(_.anchor).toSet, chosen.map(_._2.anchor).toSet)
        chosen.foreach { (era, s) =>
          val migrated = back.find(_.anchor == s.anchor).getOrElse(fail(s"$era is not restored"))
          // a state of the era-indexed rules is read as it is: the migration drops the old
          // bookkeeping and seeds nothing from what is empty
          sameState(migrated.state, s.state, era.toString)
        }
    }
  }

  test(
    "seeding a state the migration reads is idempotent, and leaves a state the rules made as it is"
  ) {
    snapshots.foreach { (era, snapshot) =>
      val seeded = legacy.LegacySeed(snapshot.state, oldExtrasOf(snapshot.state))
      assertEquals(legacy.LegacySeed(seeded, oldExtrasOf(seeded)), seeded, s"idempotent, $era")
      assertEquals(seeded.consensus, snapshot.state.consensus, s"consensus, $era")
    }
  }

  test("a V4 file whose sidecar is of another schema is refused with the typed version error") {
    val s = accepted.last
    val bytes = v4Of(s._2, oldExtrasOf(s._2.state))
    val damaged = bytes.updated(12, (bytes(12) ^ 1).toByte)
    assert(
      legacy.v4.SnapshotV4
        .sections(damaged)
        .left
        .exists(_.isInstanceOf[StorageError.VersionMismatch])
    )
    // and a V4 file is not a V5 one: the reader names the format it found
    assert(
      SnapshotV5.sidecarOf(bytes).left.exists {
        case StorageError.VersionMismatch("snapshot format", found, _) => found == "SCRBSNP4"
        case _                                                         => false
      }
    )
  }

  test("a snapshot that does not read is reported and left as it was; the others migrate") {
    tempDir.use { dir =>
      for
        names <- writeV2(dir, flats)
        bad = (dir / names.head).toNioPath
        _ = JFiles.write(bad, JFiles.readAllBytes(bad).updated(40, 0x77.toByte))
        before = JFiles.readAllBytes(bad).toList
        report <- ok(migration.run(dir))
      yield
        assertEquals(report.migrated.map(_.count), List(2))
        assertEquals(report.refused.map(_.name), List(names.head))
        assertEquals(JFiles.readAllBytes(bad).toList, before)
    }
  }

  test(
    "a snapshot of a format this build does not know refuses the run, and nothing is rewritten"
  ) {
    tempDir.use { dir =>
      for
        names <- writeV2(dir, Vector(flats.last))
        future = dir / "snapshot-99999999999999999999-9.bin"
        _ <- IO(JFiles.write(future.toNioPath, FileLedgerSnapshots.closed("SCRBSNP6....".getBytes)))
        before = JFiles.readAllBytes((dir / names.head).toNioPath).toList
        result <- attempt(migration.run(dir))
      yield
        assert(
          result.left.exists(_.isInstanceOf[StorageError.UnknownVersion]),
          result.toString
        )
        assertEquals(JFiles.readAllBytes((dir / names.head).toNioPath).toList, before)
    }
  }

  test("a database without snapshots migrates nothing") {
    tempDir.use { dir =>
      ok(migration.run(dir / "snapshots")).map(r =>
        assertEquals(r, SnapshotMigration.Report.empty)
      ) *>
        Files.forAsync[IO].exists(dir / "snapshots").map(e => assert(!e))
    }
  }

  // keep the ledger-state and UTxO handles used by the helpers above in scope of the compiler
  private val _ = (MirrorStep, Utxo, ExtLedgerState)
