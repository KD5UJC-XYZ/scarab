// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.differential

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.consensus.state.MirrorStep
import xyz.kd5ujc.scarab.ledger.rules.{EraNes, Globals, Hfc}
import xyz.kd5ujc.scarab.ledger.state.{ShelleyBasedEra, SlotNo}
import xyz.kd5ujc.scarab.legacy
import xyz.kd5ujc.scarab.node.{LegacySnapshots, NodeSetup}
import xyz.kd5ujc.scarab.storage.ImmutableReader
import xyz.kd5ujc.scarab.storage.haskell.state.HaskellState

/** Times the tick of the first block of an epoch (the epoch boundary) on the era-indexed rules,
  * from the state of a snapshot taken in the middle of the epoch before, and prints a digest of the
  * ticked `NewEpochState` as the Haskell format writes it (issue #131, `docs/performance.md`
  * section 16). Not a test: the input is the recorded K1 sync of Preview (`BoundaryDigestSuite` is
  * the opt-in test that checks the digests). The snapshots of that run are in the flat form of the
  * builds before the mirror; they are read by the frozen reader of the `legacy` module and
  * converted to the mirror, as the node's migration does.
  *
  * {{{
  * java -Xmx12g -cp <test classpath> xyz.kd5ujc.scarab.node.differential.BoundaryTickMain <db> <genesis-dir> <first> <last> [repeats]
  * }}}
  *
  * `first` and `last` are indices into the sorted snapshot files (the snapshots of epochs 1401 to
  * 1450 of the run are 1400 to 1449). One line per snapshot, `repeats` times each tick (the first
  * is the one a state just built meets):
  * `BOUNDARY snapshot <i> epoch <e> new_ms <ms,...> new_cpu <ms,...> digest <sha256>`.
  */
object BoundaryTickMain:
  /** What was measured at one snapshot. */
  final case class Row(
      index: Int,
      epoch: BigInt,
      newNanos: List[Long],
      newCpuNanos: List[Long],
      digest: String
  )

  private val SnapshotFile = """snapshot-(\d{20})-(\d+)\.bin""".r

  private val threads = java.lang.management.ManagementFactory.getThreadMXBean

  /** The value, the wall nanoseconds and the CPU nanoseconds of this thread (which the collector's
    * threads are not in).
    */
  private def timed[A](body: => A): (A, Long, Long) =
    val (wall, cpu) = (System.nanoTime(), threads.getCurrentThreadCpuTime)
    val result = body
    (result, System.nanoTime() - wall, threads.getCurrentThreadCpuTime - cpu)

  private def fail(e: Any): Nothing = sys.error(e.toString)

  def snapshotFiles(db: Path): Vector[Path] =
    Files
      .list(db.resolve("snapshots"))
      .iterator
      .asScala
      .filter(f => SnapshotFile.matches(f.getFileName.toString))
      .toVector
      .sortBy(_.getFileName.toString)

  private def hex(bytes: Array[Byte]): String = bytes.map("%02x".format(_)).mkString

  /** Ticks the first block of the epoch after snapshot `i`'s, `repeats` times. */
  def measure(db: Path, setup: NodeSetup, snapshots: Vector[Path], i: Int, repeats: Int): Row =
    val env = setup.mirrorEnv()
    val snap = LegacySnapshots.read(snapshots(i), env.forks).fold(e => fail(e.message), identity)
    val anchor = snap.anchor.getOrElse(fail("no anchor"))
    val mirror = legacy.Mirroring
      .toMirror(env, snap.anchor, snap.state)
      .fold(fail, identity)
      .value
    val nes = MirrorStep.nesOf(mirror).getOrElse(fail("Byron"))
    // the snapshots are taken in the middle of an epoch: the first block of the next one
    val firstSlot = ImmutableReader
      .blocks(db.resolve("immutable"), anchor.slot, anchor.slot + 200000)
      .map(_.ref.slot)
      .find(slot => env.ledger.geometry.epochOf(slot) > nes.epoch.toBigInt)
      .getOrElse(fail("no block in the next epoch"))
    val globals = Globals(setup.globals, env.ledger.geometry)
    val slot = SlotNo.fromBigInt(firstSlot).fold(fail, identity)
    val runs = List.fill(repeats)(timed(Hfc.tick(globals, setup.praos.forks.eraAt)(nes, slot)))
    val digest = runs.head._1.fold(
      e => s"REFUSED $e",
      { case got: EraNes.InEra[r] =>
        given ShelleyBasedEra[r] = got.era
        HaskellState
          .encode(got.nes)
          .fold(
            e => s"UNWRITABLE $e",
            b => hex(MessageDigest.getInstance("SHA-256").digest(b.toArray))
          )
      }
    )
    Row(i, nes.epoch.toBigInt, runs.map(_._2), runs.map(_._3), digest)

  def main(args: Array[String]): Unit =
    val db = Path.of(args(0))
    val setup = NodeSetup.fromDirectory(Path.of(args(1))).fold(e => fail(e.message), identity)
    val (first, last) = (args(2).toInt, args(3).toInt)
    val repeats = args.lift(4).fold(1)(_.toInt)
    val snapshots = snapshotFiles(db)
    def ms(ns: List[Long]) = ns.map(_ / 1000000).mkString(",")
    for i <- first to last do
      val r = measure(db, setup, snapshots, i, repeats)
      println(
        s"BOUNDARY snapshot ${r.index} epoch ${r.epoch} new_ms ${ms(r.newNanos)} " +
          s"new_cpu ${ms(r.newCpuNanos)} digest ${r.digest}"
      )
