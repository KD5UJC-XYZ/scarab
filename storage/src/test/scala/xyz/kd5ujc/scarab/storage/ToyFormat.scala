// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.Applicative
import cats.syntax.all.*
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.security.MessageDigest
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.codec.BinaryError

/** A one-codec snapshot format for the tests of the snapshot store, which exercise the file layout
  * (the atomic write, the pruning, the restore) with a small state rather than the node state. It
  * uses the historical V2 framing: the magic, a format number, the schema fingerprint of the codec,
  * the payload. It is independent of the retired node snapshot readers.
  */
object ToyFormat:
  private val Magic: Array[Byte] = "CSLSNAP1".getBytes("US-ASCII")
  private val Number = 2
  private val HeaderSize = Magic.length + 4
  private val FingerprintSize = 8

  private def fingerprint(shape: String): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(shape.getBytes("UTF-8")).take(FingerprintSize)

  private def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString

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
        if content.length < HeaderSize || !content.startsWith(Magic) then
          Left(StorageError.Corrupt("snapshot", BinaryError.BadMagic))
        else
          val n = java.nio.ByteBuffer.wrap(content, Magic.length, 4).getInt
          if n != Number then
            Left(StorageError.VersionMismatch("snapshot format", n.toString, Number.toString))
          else
            val found = content.slice(HeaderSize, HeaderSize + FingerprintSize)
            if !java.util.Arrays.equals(found, schema) then
              Left(StorageError.VersionMismatch("snapshot schema", hex(found), hex(schema)))
            else
              val in = In(
                new ByteArrayInputStream(content, HeaderSize + FingerprintSize, content.length),
                (content.length - HeaderSize - FingerprintSize).toLong
              )
              codec
                .read(in)
                .leftMap(e => StorageError.Corrupt("snapshot", e))
                .flatMap { snapshot =>
                  Either.cond(
                    in.remaining == 0,
                    snapshot,
                    StorageError.Corrupt("snapshot", BinaryError.TrailingBytes(in.remaining))
                  )
                }
