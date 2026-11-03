// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import cats.Monad
import cats.data.StateT
import cats.syntax.all.*
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

  /** Util.CBOR.decodeElemsUpTo: indefinite collections look for break before the bound, then decode
    * one entry. The reversed accumulator and tailRecM keep externally sized input safe.
    */
  private[protocol] def collection[A](
      tag: Int,
      field: LeiosField,
      major: Int,
      maximum: Int
  )(entry: (Int, Option[A]) => Decoder[A]): Decoder[Vector[A]] =
    length(major, LeiosWireError.BadField(tag, field)).flatMap { size =>
      def tooMany(actual: BigInt): LeiosWireError =
        LeiosWireError.TooMany(tag, field, maximum, actual)
      check(size.forall(_ <= maximum), tooMany(size.getOrElse(BigInt(maximum) + 1))) *>
        Monad[Decoder].tailRecM((0, List.empty[A])) { case (count, acc) =>
          val done = size.fold(break)(n => pure(BigInt(count) == n))
          done.flatMap { ended =>
            if ended then pure(Right(acc.reverse.toVector))
            else if count >= maximum then fail(tooMany(BigInt(count) + 1))
            else entry(count, acc.headOption).map(value => Left((count + 1, value :: acc)))
          }
        }
    }

  /** A definite length: cborg's `decodeListLen` or `decodeMapLen`, which refuse an indefinite one.
    */
  private[protocol] def definite(major: Int, error: LeiosWireError): Decoder[BigInt] =
    length(major, error).flatMap(_.fold(fail[BigInt](error))(pure))

  /** `enforceSize`: a definite list of exactly `size` items, checked before its fields. */
  private[protocol] def enforceSize(size: Int, error: LeiosWireError): Decoder[Unit] =
    definite(4, error).flatMap(n => check(n == size, error))

  /** `decodeFixedSized` of a `PackedBytes n` (`Cardano.Binary.FixedSizeCodec`: `decodeBytes`, then
    * the length), or `decodeRbHash` (`Leios/Types.hs:127-133`): a definite byte string of exactly
    * `size` bytes.
    */
  private[protocol] def fixed(size: Int, error: LeiosWireError): Decoder[Bytes] =
    bytes(error).flatMap(b => check(b.size == size, error).as(b))

  /** `decodeLeiosPoint` (`LeiosDemoTypes.hs:208-211`): `enforceSize 2`, the slot a `Word64`, the
    * endorser block hash `decodeEbHash`, 32 bytes (`Leios/Types.hs:105-106`).
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

  /** One complete CBOR item, as its original bytes, with no bound on its depth or its number of
    * items but the input's size, as cborg's decoders have none: an explicit stack of the items
    * still to read in each open item (`None` for an indefinite one), so a nesting depth from
    * outside data costs no recursion.
    */
  private[protocol] val item: Decoder[Bytes] =
    def open(h: Head): Decoder[Option[Option[BigInt]]] = h match
      case Head(2 | 3, Some(size))     => take(size).as(None)
      case Head(major @ (2 | 3), None) =>
        // the chunks of an indefinite string: definite strings of its major type, then a break
        Monad[Decoder]
          .tailRecM(()) { _ =>
            peek.flatMap(b =>
              if b == 255 then byte.as(Right(()))
              else
                head.flatMap {
                  case Head(`major`, Some(size)) => take(size).as(Left(()))
                  case _ => fail(LeiosWireError.Syntax(ProtocolError.ReservedCBORArgument))
                }
            )
          }
          .as(None)
      case Head(4, size)            => pure(Some(size))
      case Head(5, size)            => pure(Some(size.map(_ * 2)))
      case Head(6, Some(_))         => pure(Some(Some(BigInt(1))))
      case Head(0 | 1 | 7, Some(_)) => pure(None)
      case Head(7, None)            => fail(LeiosWireError.Syntax(ProtocolError.UnexpectedBreak))
      case _ => fail(LeiosWireError.Syntax(ProtocolError.ReservedCBORArgument))
    for
      start <- position
      _ <- Monad[Decoder].tailRecM(List(Option(BigInt(1)))) {
        case Nil                       => pure(Right(()))
        case Some(n) :: rest if n == 0 => pure(Left(rest))
        case None :: rest =>
          peek.flatMap(b =>
            if b == 255 then byte.as(Left(rest))
            else head.flatMap(open).map(child => Left(child.toList ++ (None :: rest)))
          )
        case Some(n) :: rest =>
          head.flatMap(open).map(child => Left(child.toList ++ (Some(n - 1) :: rest)))
      }
      end <- position
      raw <- StateT.inspect[Result, Cursor, Bytes](_.input.slice(start, end))
    yield raw

  /** Run `decoder` over `input` from its start: the value and the bytes consumed. */
  private[protocol] def run[A](decoder: Decoder[A], input: Bytes): Result[(A, Int)] =
    decoder.run(Cursor(input, 0)).map { case (cursor, value) => value -> cursor.position }
