// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import scala.annotation.tailrec
import scodec.{Attempt, Codec, DecodeResult, Err}
import scodec.bits.ByteVector
import scodec.codecs.{bytes as byteVector, int64, uint8}
import xyz.kd5ujc.scarab.codec.Bytes

/** What the fixed-layout formats of this module share: the scodec codecs of the building blocks
  * (our `Bytes`, a checked version byte, a non-negative `int64`) and the typed failures they raise.
  *
  * Fixed layouts (index files, record headers) are scodec codecs; CBOR is not, it has its own codec
  * (`xyz.kd5ujc.scarab.codec`) that keeps the original bytes of what it decodes. A codec only
  * describes the layout. What a malformed field means for a file is decided where the file is
  * parsed, by matching the [[Failure]] of the codec, never by reading its message.
  */
object Binary:

  /** The fields a codec can reject. */
  enum Field:
    case BlockOffset, Slot

  /** Why the bits of a field are not a value of its type. Extends scodec's `Err` so that it flows
    * through every combinator unchanged; consumers match on it instead of on text.
    */
  sealed trait Failure extends Err

  object Failure:
    /** A value outside the type's range (a negative offset, slot or block number). */
    final case class OutOfRange(field: Field, found: Long, context: List[String] = Nil)
        extends Failure:
      def message: String = s"$field $found out of range"
      def pushContext(ctx: String): Err = copy(context = ctx :: context)

    /** A format version this build does not read. */
    final case class UnknownVersion(found: Int, context: List[String] = Nil) extends Failure:
      def message: String = s"unknown version $found"
      def pushContext(ctx: String): Err = copy(context = ctx :: context)

  /** Exactly `n` bytes as our `Bytes`. scodec's own `bytes(n)` pads a shorter value on encoding; a
    * hash of the wrong size must be an error, not a hash with zeros appended.
    */
  def bytes(n: Int): Codec[Bytes] =
    byteVector(n).exmap(
      bv => Attempt.successful(Bytes.wrap(bv.toArray)),
      b =>
        if b.size == n then Attempt.successful(ByteVector.view(b.toArray))
        else Attempt.failure(Err(s"expected $n bytes, found ${b.size}"))
    )

  /** A 64-bit big-endian field that must not be negative: the in-range half of an unsigned 64-bit
    * value, which is all the formats ever hold (scodec has no unsigned 64-bit codec).
    */
  def nonNegative(field: Field): Codec[Long] =
    int64.exmap(
      v => if v >= 0 then Attempt.successful(v) else Attempt.failure(Failure.OutOfRange(field, v)),
      v => if v >= 0 then Attempt.successful(v) else Attempt.failure(Failure.OutOfRange(field, v))
    )

  /** One byte that must be `expected`. The format's version stays a typed value, so that a reader
    * can report which version it found.
    */
  def version(expected: Int): Codec[Unit] =
    uint8.exmap(
      v =>
        if v == expected then Attempt.successful(())
        else Attempt.failure(Failure.UnknownVersion(v)),
      _ => Attempt.successful(expected)
    )

  /** `count` consecutive values of `codec`, each in its own `size` bytes of `bytes` from `from`; on
    * the first failure its index and cause. Giving every value a window of its own keeps the bit
    * vector that the codec reads small and flat.
    */
  def decodeEach[A](
      codec: Codec[A],
      bytes: Array[Byte],
      from: Int,
      size: Int,
      count: Int
  ): Either[(Int, Err), Vector[A]] =
    val view = ByteVector.view(bytes)
    @tailrec def go(i: Int, out: Vector[A]): Either[(Int, Err), Vector[A]] =
      if i == count then Right(out)
      else
        codec.decode(view.slice(from + i.toLong * size, from + (i + 1L) * size).bits) match
          case Attempt.Successful(DecodeResult(a, _)) => go(i + 1, out :+ a)
          case Attempt.Failure(err)                   => Left(i -> err)
    go(0, Vector.empty)
