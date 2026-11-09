// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.Sync
import cats.mtl.Ask
import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, MirrorState}
import xyz.kd5ujc.scarab.ledger.{FileKind, StorageError}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import xyz.kd5ujc.scarab.storage.haskell.state.ReferenceLayout

/** The snapshot of format version 10 (the current one): the V9 layout with the reference build
  * whose layout its Haskell state bytes are in recorded after the magic (#826):
  *
  * {{{
  * "SCRBSNPA" | build length u8 | build name, ASCII | binding length u32 | binding | V5 body
  * binding = schema fingerprint[8] | Codec of CheckpointBinding     (as in SnapshotV9)
  * V5 body = sidecar length u32 | sidecar | Haskell state bytes     (as in SnapshotV5)
  * }}}
  *
  * The build is the name `--reference-build` takes ([[ReferenceBuild.name]]), so the file says what
  * wrote it even to a build that does not know that name. It is in the header, not in the binding:
  * the binding is what admits the UTxO checkpoint, a schema of its own behind its own fingerprint
  * that the V7 to V9 readers share; the build is a property of the Haskell bytes, and it is read
  * before anything else because it decides their codec. A file recorded under a build whose layout
  * is not the configured one is not read ([[StorageError.ReferenceBuildMismatch]],
  * [[StorageError.UnknownReferenceBuild]]): no conversion between builds exists.
  *
  * The migration from V9 ([[fromV9]]) records the 11.1 build and keeps the binding, the sidecar and
  * the Haskell bytes byte for byte: a V9 file holds only 11.1-layout bytes. Every V9 writer either
  * encodes with the 11.1 codec ([[SnapshotV5.parts]]) or keeps bytes that the 11.1 codec decoded
  * completely (the imports), and that codec refuses every other layout: a Dijkstra telescope or tip
  * (`DijkstraNotYet.LedgerState`), and the Leios build's three-part `ExtLedgerState`
  * (ouroboros-consensus 2416493b `Ledger/Extended.hs:420-442`), as the reference's 11.1 decoder
  * does (`decodeListLenOf 2`, 82ecba32 `Ledger/Extended.hs:268-280`).
  */
object SnapshotV10:
  val Magic: Array[Byte] = "SCRBSNPA".getBytes(StandardCharsets.US_ASCII)

  private def corrupt[E <: DomainError](reason: E): StorageError =
    StorageError.Corrupt("snapshot", reason)

  /** The format for a chain whose state is the mirror ([[MirrorState]]) in the layout of `layout`,
    * writing with `check` (see [[SnapshotCheck]]); a file recorded under a build of another layout
    * does not decode.
    *
    * With [[SnapshotCheck.Full]] the file is read back and written again: the bytes must be those
    * written, and the anchor equal (a pool registered with the empty relay list reads back as one
    * with none, which the state does not tell apart, so the bytes are what is compared, not the
    * state).
    */
  def format[F[_]: Sync](check: SnapshotCheck, layout: ReferenceLayout)(using
      env: Ask[F, ConsensusMirrorEnv]
  ): SnapshotFormat[F, MirrorState] =
    new SnapshotFormat[F, MirrorState]:
      def encode(snapshot: StateSnapshot[MirrorState]): F[Either[SnapshotRefusal, Array[Byte]]] =
        env
          .ask[ConsensusMirrorEnv]
          .flatMap(e => Sync[F].blocking(written(e, layout, snapshot, check)))

      def decode(content: Array[Byte]): F[Either[StorageError, StateSnapshot[MirrorState]]] =
        env
          .ask[ConsensusMirrorEnv]
          .flatMap(e => Sync[F].blocking(SnapshotV10.decode(e, layout, content)))

  private def written(
      env: ConsensusMirrorEnv,
      layout: ReferenceLayout,
      snapshot: StateSnapshot[MirrorState],
      check: SnapshotCheck
  ): Either[SnapshotRefusal, Array[Byte]] =
    encoded(snapshot, layout).flatTap { content =>
      check match
        case SnapshotCheck.Structure =>
          wellFormed(content).leftMap(SnapshotRefusal.WouldNotRead(_))
        case SnapshotCheck.Full =>
          for
            back <- decode(env, layout, content).leftMap(SnapshotRefusal.WouldNotRead(_))
            again <- encoded(back, layout)
            _ <- Either.cond(
              java.util.Arrays.equals(again, content) && back.anchor == snapshot.anchor,
              (),
              SnapshotRefusal.WouldDiffer(
                List(
                  Option.when(!java.util.Arrays.equals(again, content))(
                    SnapshotDifference.ReencodedBytes
                  ),
                  Option.when(back.anchor != snapshot.anchor)(SnapshotDifference.Anchor)
                ).flatten
              )
            )
          yield ()
    }

  /** The build and the binding decode, and the body is well formed ([[SnapshotV5.bodyWellFormed]]).
    */
  private[storage] def wellFormed(content: Array[Byte]): Either[StorageError, Unit] =
    header(content).flatMap((_, _, at) => SnapshotV5.bodyWellFormed(content, at))

  private[storage] def encoded(
      snapshot: StateSnapshot[MirrorState],
      layout: ReferenceLayout
  ): Either[SnapshotRefusal, Array[Byte]] =
    SnapshotV5
      .parts(snapshot, layout)
      .map((sidecar, haskell) => framed(layout.build, snapshot.checkpoint, sidecar, haskell))

  /** The recorded build: its length and its name, ASCII. */
  private[storage] def buildBytes(build: ReferenceBuild): Array[Byte] =
    val name = build.name.getBytes(StandardCharsets.US_ASCII)
    Array(name.length.toByte) ++ name

  /** The file content, in one array of its exact size: the header, the build, the binding, the
    * sidecar and the Haskell bytes are copied to it once (the Haskell section is a couple of
    * hundred megabytes, and a copy of it is one more humongous allocation for the collector).
    */
  private[storage] def framed(
      build: ReferenceBuild,
      binding: CheckpointBinding,
      sidecar: SnapshotSidecar,
      haskell: Bytes
  ): Array[Byte] =
    val recorded = buildBytes(build)
    val bound = SnapshotV9.bindingBytes(binding)
    val side = SnapshotV5.sidecarBytes(sidecar)
    val at = Magic.length + recorded.length
    val out = new Array[Byte](at + 4 + bound.length + 4 + side.length + haskell.size)
    System.arraycopy(Magic, 0, out, 0, Magic.length)
    System.arraycopy(recorded, 0, out, Magic.length, recorded.length)
    ByteBuffer.wrap(out, at, 4).putInt(bound.length)
    System.arraycopy(bound, 0, out, at + 4, bound.length)
    SnapshotV5.writeBody(out, at + 4 + bound.length, side, haskell)
    out

  /** The V10 content of a V9 file's content: the 11.1 build recorded, and the binding, the sidecar
    * and the Haskell bytes copied as they were, after they have been read (so a file that does not
    * read does not convert). Nothing after the magic is encoded again: a V9 file whose binding or
    * sidecar is in an encoding the reader takes but the writer would not produce keeps it. Every V9
    * file converts, its Haskell bytes being in the 11.1 layout (see above).
    */
  def fromV9(content: Array[Byte]): Either[StorageError, Array[Byte]] =
    SnapshotV9
      .sections(content)
      .map(_ =>
        Magic ++ buildBytes(ReferenceBuild.Release11_1) ++ content.drop(SnapshotV9.Magic.length)
      )

  // ---- read

  /** Read current-format content in the layout of `layout`, without changing its carried state or
    * metadata. Content recorded under a build of another layout, or of an unknown one, is refused
    * before its Haskell bytes are touched.
    */
  def decode(
      env: ConsensusMirrorEnv,
      layout: ReferenceLayout,
      content: Array[Byte]
  ): Either[StorageError, StateSnapshot[MirrorState]] =
    admitted(content, layout).flatMap { (_, binding, at) =>
      SnapshotV5
        .decodeBody(env, layout, content, at)
        .map((anchor, state) => StateSnapshot(anchor, state, binding))
    }

  /** The name of the build recorded in the content of a V10 file, as written. */
  def recordedOf(content: Array[Byte]): Either[StorageError, String] =
    recorded(content).map(_._1)

  /** The binding of the content of a V10 file. */
  def bindingOf(content: Array[Byte]): Either[StorageError, CheckpointBinding] =
    header(content).map(_._2)

  /** The sidecar of the content of a V10 file. */
  def sidecarOf(content: Array[Byte]): Either[StorageError, SnapshotSidecar] =
    header(content).flatMap((_, _, at) =>
      SnapshotV5.body(content, at, SnapshotV5.Schema.Current).map(_._1)
    )

  /** The recorded build, the binding, the sidecar and the Haskell bytes of a file whose Haskell
    * bytes `layout` reads, after the magic, the lengths and the fingerprints have been checked.
    */
  private[storage] def sections(
      content: Array[Byte],
      layout: ReferenceLayout
  ): Either[StorageError, (ReferenceBuild, CheckpointBinding, SnapshotSidecar, Bytes)] =
    admitted(content, layout).flatMap { (build, binding, at) =>
      SnapshotV5
        .body(content, at, SnapshotV5.Schema.Current)
        .map((sidecar, haskell) => (build, binding, sidecar, haskell))
    }

  /** The recorded build, the binding and the offset of the body, after the magic and the recorded
    * build have been checked (the build is known, and `layout` reads its layout) and then the
    * binding length and its fingerprint: nothing after the build is read for a file of another
    * build.
    */
  private def admitted(
      content: Array[Byte],
      layout: ReferenceLayout
  ): Either[StorageError, (ReferenceBuild, CheckpointBinding, Int)] =
    for
      found <- recorded(content)
      (name, at) = found
      build <- ReferenceBuild
        .parse(name)
        .leftMap(_ => StorageError.UnknownReferenceBuild("snapshot", name, layout.build))
      _ <- Either.cond(
        layout.reads(build),
        (),
        StorageError.ReferenceBuildMismatch("snapshot", build, layout.build)
      )
      bound <- SnapshotV9.bindingAt(content, at)
    yield (build, bound._1, bound._2)

  /** The recorded build's name, the binding and the offset of the body, after the magic, the
    * build's framing, the binding length and its fingerprint have been checked.
    */
  private def header(content: Array[Byte]): Either[StorageError, (String, CheckpointBinding, Int)] =
    recorded(content).flatMap((name, at) =>
      SnapshotV9.bindingAt(content, at).map((binding, end) => (name, binding, end))
    )

  /** The recorded build's name, as written, and the offset of the binding length after it. */
  private def recorded(content: Array[Byte]): Either[StorageError, (String, Int)] =
    if content.length < Magic.length + 1 || !content.startsWith(Magic) then
      SnapshotMigration
        .versionOf("snapshot format", content)
        .flatMap(version =>
          FileKind.SnapshotFile.readable("snapshot format", version).flatMap { _ =>
            val current = FileKind.SnapshotFile.current
            // a previous file its migration did not convert (its sidecar or binding is of another
            // schema): of another version, not corrupt
            if version != current then
              Left(
                StorageError.VersionMismatch(
                  "snapshot format",
                  FileKind.SnapshotFile.identifier(version),
                  FileKind.SnapshotFile.identifier(current)
                )
              )
            else Left(corrupt(SnapshotReadError.BadMagic))
          }
        )
    else
      val length = content(Magic.length) & 0xff
      val start = Magic.length + 1
      val bytes = content.slice(start, start + length)
      if length == 0 || bytes.length < length then
        Left(corrupt(SnapshotReadError.BuildLength(length, content.length)))
      else if !bytes.forall(b => b >= 0x21 && b <= 0x7e) then
        Left(corrupt(SnapshotReadError.BuildName))
      else Right(new String(bytes, StandardCharsets.US_ASCII) -> (start + length))
