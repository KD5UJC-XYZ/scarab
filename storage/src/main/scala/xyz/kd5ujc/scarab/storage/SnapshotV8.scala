// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.nio.ByteBuffer
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{FormatVersion, StorageError}

/** The snapshot of format version 8, the previous one: the V7 layout whose sidecar keeps the
  * installer metadata of the Byron update state (issue #726):
  *
  * {{{
  * "SCRBSNP8" | binding length u32 | binding | V5 body
  * binding = schema fingerprint[8] | Codec of CheckpointBinding
  * V5 body = sidecar length u32 | sidecar | Haskell state bytes   (as in SnapshotV5)
  * }}}
  *
  * Its sidecar has the frozen V8 schema ([[SnapshotSidecarV8]]), whose eras are Byron to Conway. A
  * V8 file is read only to be migrated to V9 ([[SnapshotV9.fromV8]]); the migration from V7
  * ([[fromV7]]) keeps the binding and the Haskell bytes and reads the sidecar in the schema V7
  * wrote ([[SnapshotSidecarV7]]), then writes it in the V8 one.
  */
object SnapshotV8:
  val Magic: Array[Byte] = "SCRBSNP8".getBytes("US-ASCII")

  /** Magic and the length of the binding. */
  private val HeaderSize = Magic.length + 4

  /** A V8 file's content, in one array of its exact size: what the migration from V7 writes, and
    * the previous-version tests (the node writes [[SnapshotV10]]).
    */
  private[storage] def framed(
      binding: CheckpointBinding,
      sidecar: SnapshotSidecar,
      haskell: Bytes
  ): Array[Byte] =
    val bound = SnapshotV9.bindingBytes(binding)
    val side = SnapshotV5.v8SidecarBytes(sidecar)
    val out = new Array[Byte](HeaderSize + bound.length + 4 + side.length + haskell.size)
    System.arraycopy(Magic, 0, out, 0, Magic.length)
    ByteBuffer.wrap(out, Magic.length, 4).putInt(bound.length)
    System.arraycopy(bound, 0, out, HeaderSize, bound.length)
    SnapshotV5.writeBody(out, HeaderSize + bound.length, side, haskell)
    out

  /** The V8 content of a V7 file's content: its binding and Haskell bytes unchanged, its sidecar
    * read in the V7 schema and written in the V8 one. A V7 sidecar whose Byron update state lost
    * the installer metadata of a software proposal or an application version is refused
    * ([[StorageError.ResyncRequired]]); nothing is invented for it.
    */
  def fromV7(content: Array[Byte]): Either[StorageError, Array[Byte]] =
    SnapshotV7
      .sections(content)
      .map((binding, sidecar, haskell) => framed(binding, sidecar, haskell))

  /** The binding of the content of a V8 file. */
  def bindingOf(content: Array[Byte]): Either[StorageError, CheckpointBinding] =
    SnapshotV9.boundHeader(Magic, content).map(_._1)

  /** The sidecar of the content of a V8 file, read into the current types. */
  def sidecarOf(content: Array[Byte]): Either[StorageError, SnapshotSidecar] =
    sections(content).map(_._2)

  /** The binding, the sidecar and the Haskell bytes of a V8 file, after the magic, the lengths and
    * the fingerprints have been checked.
    */
  private[storage] def sections(
      content: Array[Byte]
  ): Either[StorageError, (CheckpointBinding, SnapshotSidecar, Bytes)] =
    SnapshotV9.boundHeader(Magic, content).flatMap { (binding, at) =>
      SnapshotV5
        .body(content, at, SnapshotV5.Schema.Previous(FormatVersion.V8))
        .map((sidecar, haskell) => (binding, sidecar, haskell))
    }
