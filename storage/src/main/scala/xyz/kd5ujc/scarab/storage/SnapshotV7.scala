// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.Sync
import cats.mtl.Ask
import cats.syntax.all.*
import java.nio.ByteBuffer
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, MirrorState}
import xyz.kd5ujc.scarab.ledger.StorageError

/** The snapshot of format version 7 (the current one): the V5 body behind the binding of the
  * checkpoint the snapshot was written with:
  *
  * {{{
  * "SCRBSNP7" | binding length u32 | binding | V5 body
  * binding = schema fingerprint[8] | Codec of CheckpointBinding
  * V5 body = sidecar length u32 | sidecar | Haskell state bytes   (as in SnapshotV5)
  * }}}
  *
  * The binding is read before anything else, so a restore checks the checkpoint
  * ([[StateBacking.admit]]) without decoding the state. The body is that of [[SnapshotV5]] byte for
  * byte: the migration from V5 ([[fromV5]]) puts a [[CheckpointBinding.Legacy]] binding in front of
  * it and copies the rest.
  */
object SnapshotV7:
  val Magic: Array[Byte] = "SCRBSNP7".getBytes("US-ASCII")

  /** Magic and the length of the binding. */
  private val HeaderSize = Magic.length + 4

  private def corrupt[E <: DomainError](reason: E): StorageError =
    StorageError.Corrupt("snapshot", reason)

  /** The format for a chain whose state is the mirror ([[MirrorState]]), writing with `check` (see
    * [[SnapshotCheck]]).
    *
    * With [[SnapshotCheck.Full]] the file is read back and written again: the bytes must be those
    * written, and the anchor equal (a pool registered with the empty relay list reads back as one
    * with none, which the state does not tell apart, so the bytes are what is compared, not the
    * state).
    */
  def format[F[_]: Sync](check: SnapshotCheck)(using
      env: Ask[F, ConsensusMirrorEnv]
  ): SnapshotFormat[F, MirrorState] =
    new SnapshotFormat[F, MirrorState]:
      def encode(snapshot: StateSnapshot[MirrorState]): F[Either[SnapshotRefusal, Array[Byte]]] =
        env
          .ask[ConsensusMirrorEnv]
          .flatMap(e => Sync[F].blocking(written(e, snapshot, check)))

      def decode(content: Array[Byte]): F[Either[StorageError, StateSnapshot[MirrorState]]] =
        env.ask[ConsensusMirrorEnv].flatMap(e => Sync[F].blocking(SnapshotV7.decode(e, content)))

  private def written(
      env: ConsensusMirrorEnv,
      snapshot: StateSnapshot[MirrorState],
      check: SnapshotCheck
  ): Either[SnapshotRefusal, Array[Byte]] =
    encoded(snapshot).flatTap { content =>
      check match
        case SnapshotCheck.Structure =>
          wellFormed(content).leftMap(SnapshotRefusal.WouldNotRead(_))
        case SnapshotCheck.Full =>
          for
            back <- decode(env, content).leftMap(SnapshotRefusal.WouldNotRead(_))
            again <- encoded(back)
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

  /** The binding decodes, and the body is well formed ([[SnapshotV5.bodyWellFormed]]). */
  private[storage] def wellFormed(content: Array[Byte]): Either[StorageError, Unit] =
    header(content).flatMap((_, at) => SnapshotV5.bodyWellFormed(content, at))

  private[storage] def encoded(
      snapshot: StateSnapshot[MirrorState]
  ): Either[SnapshotRefusal, Array[Byte]] =
    SnapshotV5
      .parts(snapshot)
      .map((sidecar, haskell) => framed(snapshot.checkpoint, sidecar, haskell))

  private def bindingBytes(binding: CheckpointBinding): Array[Byte] =
    CheckpointBinding.fingerprint ++ Codec.encode(binding)(using CheckpointBinding.codec)

  /** The file content, in one array of its exact size: the header, the binding, the sidecar and the
    * Haskell bytes are copied to it once (the Haskell section is a couple of hundred megabytes, and
    * a copy of it is one more humongous allocation for the collector).
    */
  private[storage] def framed(
      binding: CheckpointBinding,
      sidecar: SnapshotSidecar,
      haskell: Bytes
  ): Array[Byte] =
    val bound = bindingBytes(binding)
    val side = SnapshotV5.sidecarBytes(sidecar)
    val out = new Array[Byte](HeaderSize + bound.length + 4 + side.length + haskell.size)
    System.arraycopy(Magic, 0, out, 0, Magic.length)
    ByteBuffer.wrap(out, Magic.length, 4).putInt(bound.length)
    System.arraycopy(bound, 0, out, HeaderSize, bound.length)
    SnapshotV5.writeBody(out, HeaderSize + bound.length, side, haskell)
    out

  /** The V7 content of a V5 file's content: its sidecar is checked to decode under the V5
    * fingerprint, and the body after the magic is copied unchanged behind a
    * [[CheckpointBinding.Legacy]] binding.
    */
  def fromV5(content: Array[Byte]): Either[StorageError, Array[Byte]] =
    SnapshotV5.sections(content).map { _ =>
      val bound = bindingBytes(CheckpointBinding.Legacy)
      val body = content.length - SnapshotV5.Magic.length
      val out = new Array[Byte](HeaderSize + bound.length + body)
      System.arraycopy(Magic, 0, out, 0, Magic.length)
      ByteBuffer.wrap(out, Magic.length, 4).putInt(bound.length)
      System.arraycopy(bound, 0, out, HeaderSize, bound.length)
      System.arraycopy(content, SnapshotV5.Magic.length, out, HeaderSize + bound.length, body)
      out
    }

  // ---- read

  /** Read current-format content without changing its carried state or metadata. */
  def decode(
      env: ConsensusMirrorEnv,
      content: Array[Byte]
  ): Either[StorageError, StateSnapshot[MirrorState]] =
    header(content).flatMap { (binding, at) =>
      SnapshotV5
        .decodeBody(env, content, at)
        .map((anchor, state) => StateSnapshot(anchor, state, binding))
    }

  /** The binding of the content of a V7 file. */
  def bindingOf(content: Array[Byte]): Either[StorageError, CheckpointBinding] =
    header(content).map(_._1)

  /** The sidecar of the content of a V7 file. */
  def sidecarOf(content: Array[Byte]): Either[StorageError, SnapshotSidecar] =
    sections(content).map(_._2)

  /** The binding, the sidecar and the Haskell bytes of a file, after the magic, the lengths and the
    * fingerprints have been checked.
    */
  private[storage] def sections(
      content: Array[Byte]
  ): Either[StorageError, (CheckpointBinding, SnapshotSidecar, Bytes)] =
    header(content).flatMap { (binding, at) =>
      SnapshotV5.body(content, at).map((sidecar, haskell) => (binding, sidecar, haskell))
    }

  /** The binding and the offset of the body, after the magic, the binding length and its
    * fingerprint have been checked.
    */
  private def header(content: Array[Byte]): Either[StorageError, (CheckpointBinding, Int)] =
    if content.length < HeaderSize || !content.startsWith(Magic) then
      SnapshotMigration
        .versionOf("snapshot format", content)
        .flatMap(version =>
          xyz.kd5ujc.scarab.ledger.FileKind.SnapshotFile.readable("snapshot format", version)
        )
        .flatMap(_ => Left(corrupt(SnapshotReadError.BadMagic)))
    else
      val length = ByteBuffer.wrap(content, Magic.length, 4).getInt
      val fingerprint = CheckpointBinding.fingerprint
      val end = HeaderSize.toLong + length
      if length < fingerprint.length || end > content.length then
        Left(corrupt(SnapshotReadError.BindingLength(length, content.length)))
      else
        val found = content.slice(HeaderSize, HeaderSize + fingerprint.length)
        if !java.util.Arrays.equals(found, fingerprint) then
          Left(
            StorageError.VersionMismatch(
              "snapshot checkpoint binding schema",
              found.map(b => f"${b & 0xff}%02x").mkString,
              fingerprint.map(b => f"${b & 0xff}%02x").mkString
            )
          )
        else
          Codec
            .decode[CheckpointBinding](
              content.slice(HeaderSize + fingerprint.length, end.toInt)
            )(using CheckpointBinding.codec)
            .leftMap(corrupt)
            .map(_ -> end.toInt)
