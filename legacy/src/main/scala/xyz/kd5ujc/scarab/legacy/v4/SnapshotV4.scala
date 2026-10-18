// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy.v4

import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.codec.BinaryError
import xyz.kd5ujc.scarab.legacy.SnapshotFramingError
import cats.syntax.all.*
import java.nio.ByteBuffer
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.legacy.Codec

/** FROZEN. The framing of a snapshot of format version 4 (`SCRBSNP4`), the file the era-indexed
  * rules wrote from J2.8 and the old rules before:
  *
  * {{{
  * "SCRBSNP4" | sidecar length u32 | sidecar | Haskell state bytes
  * sidecar = schema fingerprint[8] | Codec of SnapshotSidecar
  * }}}
  *
  * This is the reader of the framing (the migration to the current format decodes the sidecar with
  * it); the Haskell section is decoded by the live `storage` code. The fingerprint is the V4
  * sidecar's golden, which a test pins.
  */
object SnapshotV4:
  val Magic: Array[Byte] = "SCRBSNP4".getBytes("US-ASCII")

  /** Magic and the length of the sidecar. */
  val HeaderSize: Int = Magic.length + 4

  /** The content of a V4 file (without its checksum) from a sidecar and the Haskell state bytes:
    * the writer, for the tests of the migration.
    */
  def framed(sidecar: SnapshotSidecar, haskell: Array[Byte]): Array[Byte] =
    val body = SnapshotSidecar.fingerprint ++ Codec.encode(sidecar)(using SnapshotSidecar.codec)
    val out = new Array[Byte](HeaderSize + body.length + haskell.length)
    System.arraycopy(Magic, 0, out, 0, Magic.length)
    ByteBuffer.wrap(out, Magic.length, 4).putInt(body.length)
    System.arraycopy(body, 0, out, HeaderSize, body.length)
    System.arraycopy(haskell, 0, out, HeaderSize + body.length, haskell.length)
    out

  /** The sidecar and the Haskell bytes of a V4 file's content, after the magic, the length and the
    * fingerprint have been checked.
    */
  def sections(content: Array[Byte]): Either[StorageError, (SnapshotSidecar, Bytes)] =
    def corrupt[E <: DomainError](cause: E): StorageError = StorageError.Corrupt("snapshot", cause)
    if content.length < HeaderSize || !content.startsWith(Magic) then
      Left(corrupt(BinaryError.BadMagic))
    else
      val length = ByteBuffer.wrap(content, Magic.length, 4).getInt
      val fingerprint = SnapshotSidecar.fingerprint
      val end = HeaderSize.toLong + length
      if length < fingerprint.length || end > content.length then
        Left(
          corrupt(SnapshotFramingError.SidecarLength(length, content.length, fingerprint.length))
        )
      else
        val found = content.slice(HeaderSize, HeaderSize + fingerprint.length)
        if !java.util.Arrays.equals(found, fingerprint) then
          Left(
            StorageError.VersionMismatch(
              "snapshot sidecar schema",
              found.map(b => f"${b & 0xff}%02x").mkString,
              fingerprint.map(b => f"${b & 0xff}%02x").mkString
            )
          )
        else
          Codec
            .decode[SnapshotSidecar](content.slice(HeaderSize + fingerprint.length, end.toInt))(
              using SnapshotSidecar.codec
            )
            .leftMap(corrupt)
            .map(_ -> Bytes.wrap(content).drop(end.toInt))
