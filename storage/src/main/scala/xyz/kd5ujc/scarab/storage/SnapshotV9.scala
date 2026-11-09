// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.syntax.all.*
import java.nio.ByteBuffer
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.ledger.{FileKind, StorageError}

/** The snapshot of format version 9, the previous one: the V5 body behind the binding of the
  * checkpoint the snapshot was written with, as V7 and V8 have it, whose sidecar names the eras
  * with Dijkstra among them and holds the protocol parameters with the fields Dijkstra adds (#54);
  * V8's named Byron to Conway and held Conway's parameters ([[SnapshotSidecarV8]]):
  *
  * {{{
  * "SCRBSNP9" | binding length u32 | binding | V5 body
  * binding = schema fingerprint[8] | Codec of CheckpointBinding
  * V5 body = sidecar length u32 | sidecar | Haskell state bytes   (as in SnapshotV5)
  * }}}
  *
  * Its sidecar has the schema V10 has, the current one. A V9 file is read only to be migrated to
  * V10 ([[SnapshotV10.fromV9]]), and by the export of a database not yet opened by this build; the
  * migration from V8 ([[fromV8]]) keeps the binding and the Haskell bytes and reads the sidecar in
  * the schema V8 wrote ([[SnapshotSidecarV8]]), then writes it in the V9 one. The binding and its
  * readers here are those of V7 to V10.
  */
object SnapshotV9:
  val Magic: Array[Byte] = "SCRBSNP9".getBytes("US-ASCII")

  /** Magic and the length of the binding. */
  private val HeaderSize = Magic.length + 4

  private def corrupt[E <: DomainError](reason: E): StorageError =
    StorageError.Corrupt("snapshot", reason)

  private[storage] def bindingBytes(binding: CheckpointBinding): Array[Byte] =
    CheckpointBinding.fingerprint ++ Codec.encode(binding)(using CheckpointBinding.codec)

  /** A V9 file's content, in one array of its exact size: what the migration from V8 writes, and
    * the previous-version tests (the node writes [[SnapshotV10]]).
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

  /** The V9 content of a V8 file's content: its binding and Haskell bytes unchanged, its sidecar
    * read in the V8 schema and written in the V9 one. Every V8 sidecar converts: its eras are all
    * eras of the V9 schema, and their encodings are unchanged; its parameters take the fields
    * Dijkstra adds at their pre-Dijkstra defaults ([[FrozenProtocolParams.live]]).
    */
  def fromV8(content: Array[Byte]): Either[StorageError, Array[Byte]] =
    SnapshotV8
      .sections(content)
      .map((binding, sidecar, haskell) => framed(binding, sidecar, haskell))

  // ---- read

  /** The binding of the content of a V9 file. */
  def bindingOf(content: Array[Byte]): Either[StorageError, CheckpointBinding] =
    boundHeader(Magic, content).map(_._1)

  /** The sidecar of the content of a V9 file. */
  def sidecarOf(content: Array[Byte]): Either[StorageError, SnapshotSidecar] =
    sections(content).map(_._2)

  /** The binding, the sidecar and the Haskell bytes of a V9 file, after the magic, the lengths and
    * the fingerprints have been checked.
    */
  private[storage] def sections(
      content: Array[Byte]
  ): Either[StorageError, (CheckpointBinding, SnapshotSidecar, Bytes)] =
    boundHeader(Magic, content).flatMap { (binding, at) =>
      SnapshotV5
        .body(content, at, SnapshotV5.Schema.Current)
        .map((sidecar, haskell) => (binding, sidecar, haskell))
    }

  /** The binding and the offset of the body of a file that starts with `magic` (V7, V8 or V9, whose
    * magics have the same length and are followed by the binding), after the magic, the binding
    * length and its fingerprint have been checked.
    */
  private[storage] def boundHeader(
      magic: Array[Byte],
      content: Array[Byte]
  ): Either[StorageError, (CheckpointBinding, Int)] =
    if content.length < HeaderSize || !content.startsWith(magic) then
      SnapshotMigration
        .versionOf("snapshot format", content)
        .flatMap(version => FileKind.SnapshotFile.readable("snapshot format", version))
        .flatMap(_ => Left(corrupt(SnapshotReadError.BadMagic)))
    else bindingAt(content, magic.length)

  /** The binding whose length is at `at` and the offset of the body after it, once the length and
    * the binding's fingerprint have been checked: the binding of V7 to V10 files.
    */
  private[storage] def bindingAt(
      content: Array[Byte],
      at: Int
  ): Either[StorageError, (CheckpointBinding, Int)] =
    if content.length < at.toLong + 4 then
      Left(corrupt(SnapshotReadError.BindingLength(0, content.length)))
    else
      val length = ByteBuffer.wrap(content, at, 4).getInt
      val fingerprint = CheckpointBinding.fingerprint
      val start = at + 4
      val end = start.toLong + length
      if length < fingerprint.length || end > content.length then
        Left(corrupt(SnapshotReadError.BindingLength(length, content.length)))
      else
        val found = content.slice(start, start + fingerprint.length)
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
              content.slice(start + fingerprint.length, end.toInt)
            )(using CheckpointBinding.codec)
            .leftMap(DecodeFailure.binary("snapshot checkpoint binding", _))
            .map(_ -> end.toInt)
