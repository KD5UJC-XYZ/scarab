// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.EitherT
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.mtl.Ask
import cats.mtl.Handle.handleForApplicativeError
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.consensus.NodeState
import xyz.kd5ujc.scarab.consensus.state.ConsensusMirrorEnv
import xyz.kd5ujc.scarab.ledger.{FormatVersion, StorageError}
import xyz.kd5ujc.scarab.node.MirrorCorpus.Report
import xyz.kd5ujc.scarab.storage.haskell.state.HaskellLedgerFile
import xyz.kd5ujc.scarab.storage.{
  FileLedgerSnapshots,
  SnapshotFormat,
  SnapshotMigration,
  SnapshotV3,
  StateSnapshot
}

/** The corpus gate of the snapshot format V3 and of the mirror, over the real snapshots of a
  * Preview sync (one per epoch boundary), read one at a time so that a whole sync's 18 GB is never
  * held. Not a test: the input is data that is not in the repository, and it takes a large heap.
  *
  * Of each snapshot (the files of the Preview sync are format version 1, written before the
  * packages were renamed):
  *   - the V2 reader reads it directly ([[LegacySnapshots]]);
  *   - the whole mirror runs on that state ([[MirrorCorpus.check]]: retraction, and the tampers);
  *   - a copy in a scratch directory is migrated by [[SnapshotMigration]] (version 1 to 2 to 3),
  *     the V3 file is read back through the store, and the state is compared with the direct read
  *     in its canonical form ([[SnapshotV3.canonical]]), both by the mirror's own view of a state
  *     (which includes the UTxO bookkeeping) and by structural equality;
  *   - the cost of writing the V3 snapshot (the mirror and the encoding) and of reading it.
  *
  * {{{
  * java -Xmx32g -cp <test classpath> xyz.kd5ujc.scarab.node.PreviewMirrorMain <snapshots> [stride] [tamper-stride] [genesis-dir] [offset]
  * }}}
  *
  * `stride` takes every n-th snapshot (default 1); the tampers, each a full conversion, are tried
  * on every `tamper-stride` -th of those (default 20); `genesis-dir` is the network's (default
  * fixtures/preview/genesis); `offset` starts the stride at that file (default 0), so that several
  * runs can share a corpus. The snapshots directory is only read.
  */
object PreviewMirrorMain:
  private type F[A] = EitherT[IO, StorageError, A]

  /** What the migration did to one snapshot, as compared with the direct read. */
  final case class Gate(
      migrated: Boolean,
      sameView: Boolean,
      equal: Boolean,
      from: Option[FormatVersion],
      v3Bytes: Long,
      writeMillis: Long,
      readMillis: Long,
      toMirrorMillis: Long,
      haskellMillis: Long,
      haskellBytes: Long
  ):
    def ok: Boolean = migrated && sameView && equal

  private def millis[A](body: => A): (A, Long) =
    val began = System.nanoTime()
    val result = body
    (result, (System.nanoTime() - began) / 1000000)

  /** Migrate a copy of `file`, read it back, and compare it with `direct`. */
  private def gate(
      env: ConsensusMirrorEnv,
      file: Path,
      direct: StateSnapshot[NodeState]
  ): Either[StorageError, Gate] =
    given Ask[F, ConsensusMirrorEnv] = Ask.const(env)
    given SnapshotFormat[F, NodeState] = SnapshotV3.format[F]
    val dir = Files.createTempDirectory("snapshot-gate")
    try
      Files.copy(file, dir.resolve(file.getFileName))
      val store = fs2.io.file.Path.fromNioPath(dir)
      val program = for
        report <- SnapshotMigration.toV3[F].run(store)
        snapshots <- FileLedgerSnapshots.open[F, NodeState](store, keep = 1)
        restored <- snapshots.restore(_ => EitherT.pure[IO, StorageError](true))
      yield (report, restored)
      val (outcome, readMillis) = millis(program.value.unsafeRunSync())
      val expected = direct.copy(state = SnapshotV3.canonical(env, direct.state))
      // The cost of the V3 write of this state, on its own: the mirror, the Haskell encoding.
      val (encoded, writeMillis) =
        millis(SnapshotV3.format[F].encode(direct).value.unsafeRunSync())
      // ... and its parts: the mirror, and the Haskell encoding of the ledger state it gives
      val (mirrored, toMirrorMillis) =
        millis(MirrorRun.run(env)(_.toMirror(direct.anchor, direct.state)))
      val (haskell, haskellMillis) = millis(
        mirrored.toOption.flatMap(m =>
          HaskellLedgerFile.of(m.value.ext).toOption.flatMap(HaskellLedgerFile.encode(_).toOption)
        )
      )
      outcome.map { (report, restored) =>
        val back = restored.snapshot.map(_._2)
        Gate(
          migrated = report.migrated.nonEmpty && report.refused.isEmpty,
          sameView = back.exists(b =>
            b.anchor == expected.anchor && MirrorCheck.sameState(b.state, expected.state)
          ),
          equal = back.contains(expected),
          from = report.migrated.headOption.map(_.from),
          v3Bytes = encoded.toOption.flatMap(_.toOption).fold(0L)(_.length.toLong),
          writeMillis = writeMillis,
          readMillis = readMillis,
          toMirrorMillis = toMirrorMillis,
          haskellMillis = haskellMillis,
          haskellBytes = haskell.fold(0L)(_.size.toLong)
        )
      }
    finally
      Files.list(dir).iterator.asScala.foreach(Files.delete)
      Files.delete(dir)

  def main(args: Array[String]): Unit =
    val dir = Path.of(args(0))
    val stride = args.lift(1).fold(1)(_.toInt)
    val tamperStride = args.lift(2).fold(20)(_.toInt)
    val offset = args.lift(4).fold(0)(_.toInt)
    val setup = NodeSetup
      .fromDirectory(Path.of(args.lift(3).getOrElse("fixtures/preview/genesis")))
      .fold(e => sys.error(e.message), identity)
    val env = setup.mirrorEnv()
    val files = Files
      .list(dir)
      .iterator
      .asScala
      .filter(_.getFileName.toString.startsWith("snapshot-"))
      .toVector
      .sortBy(_.getFileName.toString)
      .zipWithIndex
      .collect { case (f, i) if i % stride == offset => f }
    val reports = Vector.newBuilder[(String, Report)]
    val gates = Vector.newBuilder[(String, Gate)]
    files.zipWithIndex.foreach { case (file, n) =>
      LegacySnapshots.read(file) match
        case Left(e) => println(s"${file.getFileName}: unreadable, $e")
        case Right(direct) =>
          val state = direct.state
          val report =
            MirrorCorpus.check(env, direct.anchor, state, withTampers = n % tamperStride == 0)
          val label =
            s"${file.getFileName} epoch ${state.ledger.epoch} v${state.ledger.params.protocolMajor}"
          reports += label -> report
          val gated = gate(env, file, direct)
          gated.foreach(g => gates += label -> g)
          if !report.ok || !gated.exists(_.ok) || n % 25 == 0 then
            println(s"$label: ok=${report.ok} era=${report.era} gaps=${report.gaps} gate=$gated")
    }
    println(MirrorCorpus.summary("preview snapshots", reports.result()))
    val all = gates.result()
    val bad = all.filterNot(_._2.ok)
    def stats(xs: Seq[Long]): String =
      if xs.isEmpty then "-"
      else s"avg ${xs.sum / xs.size} max ${xs.max}"
    println(s"""V3 migration gate: ${all.size} snapshots, ${bad.size} not equal after migration
               |  migrated: ${all.count(_._2.migrated)}; from version ${all
                .flatMap(_._2.from)
                .groupBy(identity)
                .view
                .mapValues(_.size)
                .toList
                .sortBy(_._1)
                .mkString(", ")}
               |  equal up to SnapshotV3.canonical: view ${all.count(
                _._2.sameView
              )}, structural ${all
                .count(_._2.equal)}
               |  V3 size in bytes: ${stats(all.map(_._2.v3Bytes))}
               |  write (toMirror + encode + the read back that verifies it) ms: ${stats(
                all.map(_._2.writeMillis)
              )}
               |    of which toMirror ms: ${stats(all.map(_._2.toMirrorMillis))}
               |    of which the Haskell encoding ms: ${stats(
                all.map(_._2.haskellMillis)
              )}; its size in bytes: ${stats(all.map(_._2.haskellBytes))}
               |  migrate + read back ms: ${stats(all.map(_._2.readMillis))}
               |${bad.take(5).map((n, g) => s"  FAILED $n: $g").mkString("\n")}""".stripMargin)
    if bad.nonEmpty || all.size != files.size then sys.exit(1)
