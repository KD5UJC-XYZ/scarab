// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.nio.ByteBuffer
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{FormatVersion, StorageError}

/** The snapshot of format version 7: the V5 body behind the binding of the checkpoint the snapshot
  * was written with:
  *
  * {{{
  * "SCRBSNP7" | binding length u32 | binding | V5 body
  * binding = schema fingerprint[8] | Codec of CheckpointBinding
  * V5 body = sidecar length u32 | sidecar | Haskell state bytes   (as in SnapshotV5)
  * }}}
  *
  * Its sidecar has the frozen V5 schema ([[SnapshotSidecarV7]]), without the installer metadata of
  * the Byron update state. A V7 file is read only to be migrated to V8 ([[SnapshotV8.fromV7]]); the
  * migration from V5 ([[fromV5]]) puts a [[CheckpointBinding.Legacy]] binding in front of the V5
  * body and copies the rest.
  */
object SnapshotV7:
  val Magic: Array[Byte] = "SCRBSNP7".getBytes("US-ASCII")

  /** Magic and the length of the binding. */
  private val HeaderSize = Magic.length + 4

  /** The V7 content of a V5 file's content: its sidecar is checked to decode under the V5
    * fingerprint, and the body after the magic is copied unchanged behind a
    * [[CheckpointBinding.Legacy]] binding.
    */
  def fromV5(content: Array[Byte]): Either[StorageError, Array[Byte]] =
    SnapshotV5.sections(content).map { _ =>
      val bound = SnapshotV9.bindingBytes(CheckpointBinding.Legacy)
      val body = content.length - SnapshotV5.Magic.length
      val out = new Array[Byte](HeaderSize + bound.length + body)
      System.arraycopy(Magic, 0, out, 0, Magic.length)
      ByteBuffer.wrap(out, Magic.length, 4).putInt(bound.length)
      System.arraycopy(bound, 0, out, HeaderSize, bound.length)
      System.arraycopy(content, SnapshotV5.Magic.length, out, HeaderSize + bound.length, body)
      out
    }

  /** A V7 file's content, in one array of its exact size (the previous-version tests write it; the
    * node writes [[SnapshotV10]]).
    */
  private[storage] def framed(
      binding: CheckpointBinding,
      sidecar: SnapshotSidecar,
      haskell: Bytes
  ): Array[Byte] =
    val bound = SnapshotV9.bindingBytes(binding)
    val side = SnapshotV5.previousSidecarBytes(sidecar)
    val out = new Array[Byte](HeaderSize + bound.length + 4 + side.length + haskell.size)
    System.arraycopy(Magic, 0, out, 0, Magic.length)
    ByteBuffer.wrap(out, Magic.length, 4).putInt(bound.length)
    System.arraycopy(bound, 0, out, HeaderSize, bound.length)
    SnapshotV5.writeBody(out, HeaderSize + bound.length, side, haskell)
    out

  /** The binding of the content of a V7 file. */
  def bindingOf(content: Array[Byte]): Either[StorageError, CheckpointBinding] =
    SnapshotV9.boundHeader(Magic, content).map(_._1)

  /** The sidecar of the content of a V7 file, read into the current types (a V7 file whose Byron
    * update state lost its installer metadata is refused, [[SnapshotSidecarV7]]).
    */
  def sidecarOf(content: Array[Byte]): Either[StorageError, SnapshotSidecar] =
    sections(content).map(_._2)

  /** The binding, the sidecar and the Haskell bytes of a V7 file, after the magic, the lengths and
    * the fingerprints have been checked.
    */
  private[storage] def sections(
      content: Array[Byte]
  ): Either[StorageError, (CheckpointBinding, SnapshotSidecar, Bytes)] =
    SnapshotV9.boundHeader(Magic, content).flatMap { (binding, at) =>
      SnapshotV5
        .body(content, at, SnapshotV5.Schema.Previous(FormatVersion.V7))
        .map((sidecar, haskell) => (binding, sidecar, haskell))
    }
