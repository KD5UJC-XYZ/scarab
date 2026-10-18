// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import xyz.kd5ujc.scarab.codec.BinaryError
import cats.syntax.all.*
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.security.MessageDigest
import xyz.kd5ujc.scarab.ledger.{FileKind, FormatVersion, StorageError}

/** FROZEN. The snapshot of format version 1 and 2 (`CSLSNAP1`):
  *
  * {{{
  * "CSLSNAP1" | format number u32 | schema fingerprint[8] | payload
  * }}}
  *
  * The payload is the [[Codec]] of `StateSnapshot[NodeState]` ([[NodeCodecs.nodeSnapshot]]) and the
  * fingerprint the SHA-256 prefix of the codec's `shape`, so a change to any persisted type makes
  * older files fail with `VersionMismatch` instead of decoding garbage. Version 1 was written
  * before the packages were renamed and differs from version 2 in the number and the fingerprint
  * only.
  *
  * This is the reader of those files (the migration to the current format decodes with it), frozen
  * with the node state of its time; the writer is kept for the tests of the migration. The
  * fingerprint [[schema]] is the V2 reader's golden (`2293c5cf7bb77492`).
  */
object SnapshotV2:
  val Magic: Array[Byte] = "CSLSNAP1".getBytes("US-ASCII")

  /** The format number the last build that wrote this format wrote. */
  val Number = 2

  /** Magic and format number. */
  val HeaderSize: Int = Magic.length + 4
  val FingerprintSize = 8

  def fingerprint(shape: String): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(shape.getBytes("UTF-8")).take(FingerprintSize)

  /** The schema fingerprint of the node snapshot, as a version 2 file starts with it. */
  def schema: Array[Byte] = fingerprint(NodeCodecs.nodeSnapshot.shape)

  private def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString

  /** The format number a file starts with, if it starts like a `CSLSNAP1` file. */
  def numberOf(content: Array[Byte]): Option[Int] =
    Option.when(content.length >= HeaderSize && content.startsWith(Magic)) {
      java.nio.ByteBuffer.wrap(content, Magic.length, 4).getInt
    }

  /** The same file at version 2: the number and the fingerprint replaced (the payload is the same
    * bytes, S4 of the mirror work established it on the real snapshots of a Preview sync).
    */
  def upgraded(version1: Array[Byte]): Array[Byte] =
    val out = new ByteArrayOutputStream(version1.length)
    out.write(Magic)
    out.write(java.nio.ByteBuffer.allocate(4).putInt(Number).array())
    out.write(schema)
    out.write(
      version1,
      HeaderSize + FingerprintSize,
      version1.length - HeaderSize - FingerprintSize
    )
    out.toByteArray

  /** The content of a version 2 file (without the checksum that closes the file). */
  def encode[S](snapshot: StateSnapshot[S])(using codec: Codec[StateSnapshot[S]]): Array[Byte] =
    val bytes = new ByteArrayOutputStream()
    val out = Out(bytes)
    out.bytes(Magic, 0, Magic.length)
    out.fixed32(Number)
    val found = fingerprint(codec.shape)
    out.bytes(found, 0, found.length)
    codec.write(out, snapshot)
    out.flush()
    bytes.toByteArray

  /** The snapshot in the content of a version 1 file (brought to version 2 first) or a version 2
    * file.
    */
  def decodeNode(content: Array[Byte]): Either[StorageError, StateSnapshot[NodeState]] =
    decode[NodeState](
      if numberOf(content).contains(1) then upgraded(content) else content
    )(using NodeCodecs.nodeSnapshot)

  /** The snapshot in the content of a version 2 file. */
  def decode[S](
      content: Array[Byte]
  )(using codec: Codec[StateSnapshot[S]]): Either[StorageError, StateSnapshot[S]] =
    val current = FileKind.SnapshotFile.identifier(FileKind.SnapshotFile.current)
    val expected = fingerprint(codec.shape)
    numberOf(content) match
      case None =>
        Left(StorageError.Corrupt("snapshot", BinaryError.BadMagic))
      case Some(n) if n != Number =>
        Left(StorageError.VersionMismatch("snapshot format", n.toString, current))
      case Some(_) =>
        val found = content.slice(HeaderSize, HeaderSize + FingerprintSize)
        if !java.util.Arrays.equals(found, expected) then
          Left(StorageError.VersionMismatch("snapshot schema", hex(found), hex(expected)))
        else
          val in = In(
            new ByteArrayInputStream(content, HeaderSize + FingerprintSize, content.length),
            (content.length - HeaderSize - FingerprintSize).toLong
          )
          codec.read(in).leftMap(e => StorageError.Corrupt("snapshot", e))
            .flatMap { snapshot =>
              Either.cond(
                in.remaining == 0,
                snapshot,
                StorageError.Corrupt("snapshot", BinaryError.TrailingBytes(in.remaining))
              )
            }

  /** The version a `CSLSNAP1` file is at, by its number (the text of
    * [[FileKind.SnapshotFile.identifier]]).
    */
  def versionOf(what: String, number: Int): Either[StorageError, FormatVersion] =
    FileKind.SnapshotFile.versionOf(what, s"${new String(Magic, "US-ASCII")} v$number")
