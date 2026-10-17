// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.Show
import cats.effect.IO
import cats.mtl.Ask
import cats.syntax.all.*
import java.nio.file.Files as JFiles
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, NodeState}
import xyz.kd5ujc.scarab.consensus.state.{
  ConsensusMirrorEnv,
  EraLedgerState,
  MirrorState,
  StateMirrorError
}
import xyz.kd5ujc.scarab.ledger.rules.EraLedger
import xyz.kd5ujc.scarab.ledger.state.{ShelleyBasedEra, Sorted}
import xyz.kd5ujc.scarab.ledger.{Block, Header}
import xyz.kd5ujc.scarab.node.{LegacySeed, MirrorRules, MirrorRun, NodeRules, NodeSetup}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.Era

/** The snapshot of a chain whose state is the mirror (design `ledger-mirror-step2.md` §2.4, J2.8):
  * the file is the V4 file of the flat form, written from the mirror as it is held and read back as
  * it is, with no conversion; a V4 file the old rules wrote is read as the mirror of the state it
  * holds, which is what the flat writer put in its Haskell section.
  */
class SnapshotMirrorSuite extends CatsEffectSuite:
  private val fixtures = java.nio.file.Path.of("fixtures/eras")

  private val setup: NodeSetup =
    NodeSetup.fromDirectory(fixtures.resolve("genesis")).fold(e => fail(e.message), identity)
  private val env = setup.mirrorEnv()

  private given Ask[IO, ConsensusMirrorEnv] = Ask.const(env)
  private val flat: SnapshotFormat[IO, NodeState] = SnapshotV4.format[IO](SnapshotCheck.Full)
  private val mirror: SnapshotFormat[IO, MirrorState] =
    SnapshotV4.mirrorFormat[IO](SnapshotCheck.Full)

  private val raws: Vector[Bytes] =
    JFiles
      .readAllLines(fixtures.resolve("blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e), identity))
  private val blocks: Vector[Block] =
    raws.map(Block.decode(_, setup.geometry.byron.epochSlots).fold(e => fail(e.message), identity))
  private val headers: Vector[Header] = blocks.map(_.header)

  private def ref(h: Header) = BlockRef(h.slot, h.hash, h.blockNo, h.isBoundary)

  /** The states the new rules give, from the mirror of the start: after each block. */
  private lazy val states: Vector[(Era, StateSnapshot[MirrorState])] =
    val start = setup.mirrorStart.fold(e => fail(Show[StateMirrorError].show(e)), identity)
    blocks.indices
      .foldLeft(Vector.empty[(Era, StateSnapshot[MirrorState])]) { (acc, i) =>
        val before = acc.lastOption.fold(start)(_._2.state)
        MirrorRules.applyBlock(
          before,
          headers(i),
          raws(i),
          setup.praos,
          setup.globals,
          setup.byronRules,
          setup.handOver,
          setup.byronHistory
        ) match
          case Right(next) => acc :+ (blocks(i).era -> StateSnapshot(Some(ref(headers(i))), next))
          case Left(f)     => fail(s"block $i rejected by ${f.rule.name}: ${f.reason}")
      }

  private def written(s: StateSnapshot[MirrorState]): IO[Array[Byte]] =
    mirror
      .encode(s)
      .flatMap(
        _.fold(r => IO.raiseError(new AssertionError(Show[SnapshotRefusal].show(r))), IO.pure)
      )

  private def read(bytes: Array[Byte]): IO[StateSnapshot[MirrorState]] =
    mirror.decode(bytes).flatMap(_.fold(e => IO.raiseError(new AssertionError(e.message)), IO.pure))

  test("every state the new rules give over the eras capture is written and read back") {
    states.traverse_ { (era, snapshot) =>
      val label = s"$era block ${snapshot.anchor.map(_.blockNo)}"
      for
        bytes <- written(snapshot)
        back <- read(bytes)
      yield
        assertEquals(back.anchor, snapshot.anchor, label)
        // the header state, the node's bookkeeping and the telescopes come back as they were
        assertEquals(back.state.ext.headerState, snapshot.state.ext.headerState, label)
        assertEquals(back.state.extras, snapshot.state.extras, label)
        assertEquals(back.state.ext.ledgerState.era, snapshot.state.ext.ledgerState.era, label)
        assertEquals(back.state.utxo.toMap, snapshot.state.utxo.toMap, label)
    }
  }

  test("a state read back is written again to the same bytes: the format is stable") {
    states.traverse_ { (era, snapshot) =>
      for
        bytes <- written(snapshot)
        back <- read(bytes)
        again <- written(back)
      yield assert(java.util.Arrays.equals(bytes, again), s"$era block ${snapshot.anchor}")
    }
  }

  test("a V4 file the flat form wrote is read as the mirror of the state it holds") {
    // the old rules over the capture, each state written by the flat writer
    val old = blocks.indices
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
    blocks.indices.toList.traverse_ { i =>
      val snapshot = StateSnapshot(Some(ref(headers(i))), old(i))
      for
        bytes <- flat
          .encode(snapshot)
          .map(_.fold(r => fail(Show[SnapshotRefusal].show(r)), identity))
        back <- read(bytes)
      yield
        val derived = MirrorRun
          .run(env)(_.toMirror(snapshot.anchor, old(i)))
          .fold(e => fail(Show[StateMirrorError].show(e)), _.value)
        val at = s"${blocks(i).era} block $i"
        assertEquals(back.anchor, snapshot.anchor, at)
        assertEquals(back.state.ext.headerState, derived.ext.headerState, at)
        assertEquals(back.state.ext.ledgerState.past, derived.ext.ledgerState.past, at)
        assertEquals(back.state.consensus, derived.consensus, at)
        // what the old rules' bookkeeping is to the new rules is reset once, as the node restores
        val seeded = LegacySeed(back.state)
        assertEquals(seeded.consensus, derived.consensus, at)
        assertEquals(LegacySeed(seeded), seeded, s"seeding is idempotent, $at")
    }
  }

  /** A pool registered with the empty relay list holds the item as the certificate gave it (`80`)
    * and reads back as one with none (`Bytes.empty`): the Haskell encoding writes both as the empty
    * array. The state does not tell them apart, and nothing reads the relays.
    */
  private def bare(m: MirrorState): MirrorState =
    val empty = Bytes.fromArray(Array(0x80.toByte))
    def plain(r: Bytes) = if r == empty then Bytes.empty else r
    m.ext.ledgerState.state match
      case i: EraLedgerState.InShelleyEra[e] =>
        given ShelleyBasedEra[e] = i.era
        val nes = i.state.nes
        val ps = EraLedger.pState(nes.es.ls)
        val bared = ps.copy(
          stakePools =
            Sorted.map(ps.stakePools.toList.map((k, v) => k -> v.copy(relays = plain(v.relays)))),
          future = Sorted.map(ps.future.toList.map((k, v) => k -> v.copy(relays = plain(v.relays))))
        )
        val state = EraLedgerState.InShelleyEra(
          i.era,
          i.state.copy(nes =
            nes.copy(es = nes.es.copy(ls = EraLedger.withPState(nes.es.ls, bared)))
          )
        )
        m.copy(ext = m.ext.copy(ledgerState = m.ext.ledgerState.withState(state)))
      case _ => m

  test("a state read back is the state written, whole (the empty relay list apart)") {
    states.traverse_ { (era, snapshot) =>
      for
        bytes <- written(snapshot)
        back <- read(bytes)
      yield
        val difference = xyz.kd5ujc.scarab.ledger.state
          .Diff[MirrorState]
          .diff(
            bare(back.state),
            bare(snapshot.state)
          )
        assertEquals(
          difference.map(d => s"${d.path.render}: ${d.left} /= ${d.right}"),
          None,
          s"$era block ${snapshot.anchor.map(_.blockNo)}"
        )
    }
  }
