// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import cats.Monad
import cats.data.StateT
import cats.syntax.all.*
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes

import LeiosWord.Word64

/** The stream decoder the Leios mini-protocol codecs share: a cursor over the input, the CBOR heads
  * cborg reads, and the field decoders of the build the public Leios testnet runs
  * (ouroboros-consensus `2416493b`, `LeiosDemoTypes.hs`, `Ouroboros/Consensus/Leios/Types.hs`).
  * Byte strings are cborg's `decodeBytes`, definite only.
  */
private[protocol] object LeiosStream:
  private[protocol] type Result[A] = Either[LeiosWireError, A]
  private[protocol] final case class Cursor(input: Bytes, position: Int)
  private[protocol] type Decoder[A] = StateT[Result, Cursor, A]
  private[protocol] final case class Head(major: Int, argument: Option[BigInt])

  private[protocol] def pure[A](value: A): Decoder[A] = StateT.pure(value)
  private[protocol] def fail[A](error: LeiosWireError): Decoder[A] = StateT.liftF(Left(error))
  private[protocol] def check(condition: Boolean, error: => LeiosWireError): Decoder[Unit] =
    if condition then pure(()) else fail(error)
  private[protocol] val peek: Decoder[Int] = StateT { cursor =>
    cursor.input
      .lift(cursor.position)
      .toRight(LeiosWireError.Incomplete)
      .map(b => cursor -> (b & 255))
  }
  private[protocol] def take(length: BigInt): Decoder[Bytes] = StateT { cursor =>
    if length > cursor.input.size - cursor.position then Left(LeiosWireError.Incomplete)
    else
      val end = cursor.position + length.toInt
      Right(cursor.copy(position = end) -> cursor.input.slice(cursor.position, end))
  }
  private[protocol] val byte: Decoder[Int] = peek.flatMap(b => take(1).map(_ => b))
  private[protocol] val head: Decoder[Head] = byte.flatMap { first =>
    val ai = first & 31
    val argument: Decoder[Option[BigInt]] =
      if ai < 24 then pure(Some(BigInt(ai)))
      else if ai == 31 then pure(None)
      else if ai <= 27 then
        take(1 << (ai - 24)).map(bytes =>
          Some(bytes.value.foldLeft(BigInt(0))((n, b) => (n << 8) | BigInt(b & 255)))
        )
      else fail(LeiosWireError.Syntax(ProtocolError.ReservedCBORArgument))
    argument.map(Head(first >>> 5, _))
  }
  private[protocol] def unsigned(major: Int, max: BigInt, error: LeiosWireError): Decoder[BigInt] =
    head.flatMap {
      case Head(`major`, Some(value)) if value <= max => pure(value)
      case _                                          => fail(error)
    }
  private[protocol] def length(major: Int, error: LeiosWireError): Decoder[Option[BigInt]] =
    head.flatMap {
      case Head(`major`, size) if size.forall(_ <= Long.MaxValue) => pure(size)
      case _                                                      => fail(error)
    }
  private[protocol] def bytes(error: LeiosWireError): Decoder[Bytes] =
    head.flatMap {
      case Head(2, Some(size)) if size <= Long.MaxValue => take(size).map(_.compact)
      case _                                            => fail(error)
    }
  private[protocol] val break: Decoder[Boolean] = peek.flatMap { next =>
    if next == 255 then byte.as(true) else pure(false)
  }

  /** The entries of a collection read so far, newest first, and how many. */
  private[protocol] final case class Entries[A](count: Int, newestFirst: List[A]):
    def added(entry: A): Entries[A] = Entries(count + 1, entry :: newestFirst)
    def inOrder: Vector[A] = newestFirst.reverse.toVector

  private[protocol] object Entries:
    def none[A]: Entries[A] = Entries(0, Nil)

  /** The head of a collection of at most `maximum` entries, as Util.CBOR.decodeElemsUpTo reads it:
    * its length, definite or not, refused above the bound before any entry is read.
    */
  private[protocol] def collectionHead(
      tag: Int,
      field: LeiosField,
      major: Int,
      maximum: Int
  ): Decoder[Option[BigInt]] =
    length(major, LeiosWireError.BadField(tag, field)).flatMap { size =>
      check(
        size.forall(_ <= maximum),
        LeiosWireError.TooMany(tag, field, maximum, size.getOrElse(BigInt(maximum) + 1))
      ).as(size)
    }

  /** The next step of a collection whose head gave `size`, after the entries `read`: an indefinite
    * collection looks for its break before the bound, then decodes one entry
    * (Util.CBOR.decodeElemsUpTo). The collection once it has ended, else the entries with one more.
    */
  private[protocol] def collectionStep[A](
      tag: Int,
      field: LeiosField,
      maximum: Int,
      size: Option[BigInt],
      read: Entries[A]
  )(entry: (Int, Option[A]) => Decoder[A]): Decoder[Either[Entries[A], Vector[A]]] =
    size.fold(break)(n => pure(BigInt(read.count) == n)).flatMap { ended =>
      if ended then pure(Right(read.inOrder))
      else if read.count >= maximum then
        fail(LeiosWireError.TooMany(tag, field, maximum, BigInt(read.count) + 1))
      else entry(read.count, read.newestFirst.headOption).map(value => Left(read.added(value)))
    }

  /** A definite length: cborg's `decodeListLen` or `decodeMapLen`, which refuse an indefinite one.
    */
  private[protocol] def definite(major: Int, error: LeiosWireError): Decoder[BigInt] =
    length(major, error).flatMap(_.fold(fail[BigInt](error))(pure))

  /** `enforceSize`: a definite list of exactly `size` items, checked before its fields. */
  private[protocol] def enforceSize(size: Int, error: LeiosWireError): Decoder[Unit] =
    definite(4, error).flatMap(n => check(n == size, error))

  /** `decodeFixedSized` of a `PackedBytes n` (`Cardano.Binary.FixedSizeCodec`: `decodeBytes`, then
    * the length), or `decodeRbHash` (`Leios/Types.hs:127-133`, ouroboros-consensus 2416493b): a
    * definite byte string of exactly `size` bytes.
    */
  private[protocol] def fixed(size: Int, error: LeiosWireError): Decoder[Bytes] =
    bytes(error).flatMap(b => check(b.size == size, error).as(b))

  /** `decodeLeiosPoint` (`LeiosDemoTypes.hs:208-211`, ouroboros-consensus 2416493b): `enforceSize
    * 2`, the slot a `Word64`, the endorser block hash `decodeEbHash`, 32 bytes
    * (`Leios/Types.hs:105-106`).
    */
  private[protocol] def point(tag: Int): Decoder[EbPoint] =
    for
      _ <- enforceSize(2, LeiosWireError.BadField(tag, LeiosField.Point))
      slot <- unsigned(0, Word64.Max, LeiosWireError.BadField(tag, LeiosField.Slot))
        .flatMap(n => StateT.liftF(Word64.from(n)))
      hash <- fixed(32, LeiosWireError.BadField(tag, LeiosField.Hash))
    yield EbPoint(slot, EbHash.fromBytes(hash))

  /** `n` entries, as `replicateM` /`generateM` reads a counted collection: a reversed accumulator
    * and `tailRecM`, so a count from outside data is stack safe.
    */
  private[protocol] def repeat[A](n: BigInt)(entry: Int => Decoder[A]): Decoder[Vector[A]] =
    Monad[Decoder].tailRecM((0, List.empty[A])) { case (count, acc) =>
      if BigInt(count) >= n then pure(Right(acc.reverse.toVector))
      else entry(count).map(value => Left((count + 1, value :: acc)))
    }

  /** The position of the cursor. */
  private[protocol] val position: Decoder[Int] = StateT.inspect(_.position)

  /** Run `decoder` over `input` from its start: the value and the bytes consumed. */
  private[protocol] def run[A](decoder: Decoder[A], input: Bytes): Result[(A, Int)] =
    decoder.run(Cursor(input, 0)).map { case (cursor, value) => value -> cursor.position }

  /** Where a decoder run in steps stopped: short of input at the checkpoint `stage`, which begins
    * at `position`, or with its verdict on the message, the value and the bytes it took.
    */
  private[protocol] enum Resumed[+S, +A]:
    case Short(stage: S, position: Int)
    case Done(result: Result[(A, Int)])

  /** A message decoded in steps from the checkpoint `stage` at `position`, each step from where the
    * one before ended, until one gives the value, as cborg's incremental decoder
    * (`deserialiseIncremental`) reads a message token by token across the chunks it comes in and
    * fails at the first token it refuses. A step that runs short of input leaves its checkpoint, so
    * the next, longer prefix reads that step again and nothing before it: each step reads a bounded
    * number of heads, so a message that arrives in many parts is read once.
    */
  @tailrec private[protocol] def resume[S, A](stage: S, position: Int, input: Bytes)(
      step: S => Decoder[Either[S, A]]
  ): Resumed[S, A] =
    step(stage).run(Cursor(input, position)) match
      case Left(LeiosWireError.Incomplete) => Resumed.Short(stage, position)
      case Left(error)                     => Resumed.Done(Left(error))
      case Right((cursor, Left(next)))     => resume(next, cursor.position, input)(step)
      case Right((cursor, Right(value)))   => Resumed.Done(Right(value -> cursor.position))
