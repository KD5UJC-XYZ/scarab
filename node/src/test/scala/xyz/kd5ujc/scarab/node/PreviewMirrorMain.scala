// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.EitherT
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.mtl.Handle.handleForApplicativeError
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.consensus.NodeState
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.ledger.state.{MirrorEnv, SlotNo, WithOrigin}
import xyz.kd5ujc.scarab.node.MirrorCorpus.{Report, SomeEra}
import xyz.kd5ujc.scarab.storage.{FileLedgerSnapshots, NodeCodecs}

/** Runs the ledger-side mirror over the ledger states of real snapshots of the node (a Preview
  * sync, one snapshot per epoch boundary), reading them one at a time so that a whole sync's 18 GB
  * is never held. Not a test: the input is data that is not in the repository, and it takes a large
  * heap.
  *
  * {{{
  * java -Xmx24g -cp <test classpath> xyz.kd5ujc.scarab.node.PreviewMirrorMain <snapshots> [stride] [tamper-stride]
  * }}}
  *
  * `stride` takes every n-th snapshot (default 1); the tampers, each a full conversion, are tried
  * on every `tamper-stride` -th of those (default 20).
  */
object PreviewMirrorMain:
  import NodeCodecs.given

  private type F[A] = EitherT[IO, StorageError, A]

  private val fingerprint: Array[Byte] =
    FileLedgerSnapshots.fingerprint(NodeCodecs.nodeSnapshot.shape)

  /** The snapshot as the current format has it: the files of the Preview sync were written by the
    * build before the packages were renamed (format version 1); the payload is the same, so only
    * the version and the schema fingerprint of the header, and the checksum, change.
    */
  private def upgraded(old: Array[Byte]): Array[Byte] =
    val magic = FileLedgerSnapshots.Magic.length
    val payload = old.slice(magic + 4 + fingerprint.length, old.length - 32)
    val body =
      FileLedgerSnapshots.Magic ++ Array[Byte](0, 0, 0, FileLedgerSnapshots.FormatVersion.toByte) ++
        fingerprint ++ payload
    body ++ java.security.MessageDigest.getInstance("SHA-256").digest(body)

  private def read(file: Path): Either[StorageError, Option[(Option[Long], NodeState)]] =
    val dir = Files.createTempDirectory("mirror-snapshot")
    try
      Files.write(dir.resolve(file.getFileName), upgraded(Files.readAllBytes(file)))
      FileLedgerSnapshots
        .open[F, NodeState](fs2.io.file.Path.fromNioPath(dir), keep = 1)
        .flatMap(_.restore(_ => EitherT.pure[IO, StorageError](true)))
        .value
        .unsafeRunSync()
        .map { restored =>
          restored.skipped.foreach(k => println(s"  skipped ${k.name}: ${k.error}"))
          restored.snapshot.map((_, s) => (s.anchor.map(_.slot.toLong), s.state))
        }
    finally
      Files.list(dir).iterator.asScala.foreach(Files.delete)
      Files.delete(dir)

  def main(args: Array[String]): Unit =
    val dir = Path.of(args(0))
    val stride = args.lift(1).fold(1)(_.toInt)
    val tamperStride = args.lift(2).fold(20)(_.toInt)
    val setup = NodeSetup
      .fromDirectory(Path.of("fixtures/preview/genesis"))
      .fold(e => sys.error(e.message), identity)
    val env = MirrorEnv(setup.globals.network, setup.geometry, setup.globals.updateQuorum)
    val files = Files
      .list(dir)
      .iterator
      .asScala
      .filter(_.getFileName.toString.startsWith("snapshot-"))
      .toVector
      .sortBy(_.getFileName.toString)
      .zipWithIndex
      .collect { case (f, i) if i % stride == 0 => f }
    val reports = Vector.newBuilder[(String, Report)]
    files.zipWithIndex.foreach { case (file, n) =>
      read(file) match
        case Left(e)     => println(s"${file.getFileName}: unreadable, $e")
        case Right(None) => println(s"${file.getFileName}: no snapshot")
        case Right(Some((slot, state))) =>
          val era = SomeEra.ofVersion(state.ledger.params.protocolMajor.toInt)
          val at = slot.fold[WithOrigin[SlotNo]](WithOrigin.Origin)(s =>
            WithOrigin.At(SlotNo.fromUnsigned(s))
          )
          val report =
            MirrorCorpus.check(env, era, at, state.ledger, withTampers = n % tamperStride == 0)
          val label =
            s"${file.getFileName} epoch ${state.ledger.epoch} v${state.ledger.params.protocolMajor}"
          reports += label -> report
          if !report.ok || n % 25 == 0 then println(s"$label: ok=${report.ok} gaps=${report.gaps}")
    }
    println(MirrorCorpus.summary("preview snapshots", reports.result()))
