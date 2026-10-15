// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.Applicative
import cats.syntax.all.*
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.security.MessageDigest
import xyz.kd5ujc.scarab.ledger.{FileKind, FormatVersion, StorageError}

/** The snapshot of format version 1 and 2 (`CSLSNAP1`):
  *
  * {{{
  * "CSLSNAP1" | format number u32 | schema fingerprint[8] | payload
  * }}}
  *
  * The payload is the [[Codec]] of `StateSnapshot[S]` and the fingerprint the SHA-256 prefix of the
  * codec's `shape`, so a change to any persisted type makes older files fail with `VersionMismatch`
  * instead of decoding garbage. Version 1 was written before the packages were renamed and differs
  * from version 2 in the number and the fingerprint only.
  *
  * This is the reader of those files (the migration to V3 decodes with it), frozen with the node
  * state of its time; see [[SnapshotMigration]].
  */
object SnapshotV2:
  val Magic: Array[Byte] = "CSLSNAP1".getBytes("US-ASCII")

  /** The format number the build before [[SnapshotV3]] wrote. */
  val Number = 2

  /** Magic and format number. */
  val HeaderSize: Int = Magic.length + 4
  val FingerprintSize = 8

  def fingerprint(shape: String): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(shape.getBytes("UTF-8")).take(FingerprintSize)

  private def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString

  /** The format number a file starts with, if it starts like a `CSLSNAP1` file. */
  def numberOf(content: Array[Byte]): Option[Int] =
    Option.when(content.length >= HeaderSize && content.startsWith(Magic)) {
      java.nio.ByteBuffer.wrap(content, Magic.length, 4).getInt
    }

  /** The same file at version 2: the number and the fingerprint replaced (the payload is the same
    * bytes, S4 of the mirror work established it on the real snapshots of a Preview sync).
    */
  def upgraded(version1: Array[Byte], schema: Array[Byte]): Array[Byte] =
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

  /** The format of the snapshots of `S` through its codec. Only the file layout is here; nothing
    * refuses, and the effect is not used.
    */
  def format[F[_]: Applicative, S](using codec: Codec[StateSnapshot[S]]): SnapshotFormat[F, S] =
    new SnapshotFormat[F, S]:
      private val schema = fingerprint(codec.shape)

      def encode(snapshot: StateSnapshot[S]): F[Either[SnapshotRefusal, Array[Byte]]] =
        val bytes = new ByteArrayOutputStream()
        val out = Out(bytes)
        out.bytes(Magic, 0, Magic.length)
        out.fixed32(Number)
        out.bytes(schema, 0, schema.length)
        codec.write(out, snapshot)
        out.flush()
        bytes.toByteArray.asRight[SnapshotRefusal].pure[F]

      def decode(content: Array[Byte]): F[Either[StorageError, StateSnapshot[S]]] =
        read(content).pure[F]

      private def read(content: Array[Byte]): Either[StorageError, StateSnapshot[S]] =
        val current = FileKind.SnapshotFile.identifier(FileKind.SnapshotFile.current)
        numberOf(content) match
          case None =>
            Left(StorageError.Corrupt("snapshot", "bad magic or too short"))
          case Some(n) if n != Number =>
            Left(StorageError.VersionMismatch("snapshot format", n.toString, current))
          case Some(_) =>
            val found = content.slice(HeaderSize, HeaderSize + FingerprintSize)
            if !java.util.Arrays.equals(found, schema) then
              Left(StorageError.VersionMismatch("snapshot schema", hex(found), hex(schema)))
            else
              val in = In(
                new ByteArrayInputStream(content, HeaderSize + FingerprintSize, content.length),
                (content.length - HeaderSize - FingerprintSize).toLong
              )
              Either
                .catchOnly[CodecException](codec.read(in))
                .leftMap(e => StorageError.Corrupt("snapshot", e.getMessage))
                .flatMap { snapshot =>
                  Either.cond(
                    in.remaining == 0,
                    snapshot,
                    StorageError.Corrupt("snapshot", "trailing payload bytes")
                  )
                }

  /** The version a `CSLSNAP1` file is at, by its number (the text of
    * [[FileKind.SnapshotFile.identifier]]).
    */
  def versionOf(what: String, number: Int): Either[StorageError, FormatVersion] =
    FileKind.SnapshotFile.versionOf(what, s"${new String(Magic, "US-ASCII")} v$number")
