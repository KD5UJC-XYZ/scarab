// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import cats.Monad
import cats.data.StateT
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}

import LeiosFetch.{Message, State}
import LeiosWord.{Word16, Word32, Word64}

/** The concrete LeiosFetch decoder at ouroboros-consensus `7b239a16`: record fields precede arity
  * checks; bounded collections reject their length before reading entries; bitmap checks run before
  * the next entry. Native transactions and hashes use cborg's definite decodeBytes. Protocol byte
  * limits belong to the receiving driver, not the CBOR codec.
  */
private[protocol] object LeiosFetchDecoder:
  private type Result[A] = Either[LeiosWireError, A]
  private final case class Cursor(input: Bytes, position: Int)
  private type Decoder[A] = StateT[Result, Cursor, A]
  private final case class Head(major: Int, argument: Option[BigInt])

  private def pure[A](value: A): Decoder[A] = StateT.pure(value)
  private def fail[A](error: LeiosWireError): Decoder[A] = StateT.liftF(Left(error))
  private def check(condition: Boolean, error: => LeiosWireError): Decoder[Unit] =
    if condition then pure(()) else fail(error)
  private val peek: Decoder[Int] = StateT { cursor =>
    cursor.input
      .lift(cursor.position)
      .toRight(LeiosWireError.Incomplete)
      .map(b => cursor -> (b & 255))
  }
  private def take(length: BigInt): Decoder[Bytes] = StateT { cursor =>
    if length > cursor.input.size - cursor.position then Left(LeiosWireError.Incomplete)
    else
      val end = cursor.position + length.toInt
      Right(cursor.copy(position = end) -> cursor.input.slice(cursor.position, end))
  }
  private val byte: Decoder[Int] = peek.flatMap(b => take(1).map(_ => b))
  private val head: Decoder[Head] = byte.flatMap { first =>
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
  private def unsigned(major: Int, max: BigInt, error: LeiosWireError): Decoder[BigInt] =
    head.flatMap {
      case Head(`major`, Some(value)) if value <= max => pure(value)
      case _                                          => fail(error)
    }
  private def length(major: Int, error: LeiosWireError): Decoder[Option[BigInt]] =
    head.flatMap {
      case Head(`major`, size) if size.forall(_ <= Long.MaxValue) => pure(size)
      case _                                                      => fail(error)
    }
  private def bytes(error: LeiosWireError): Decoder[Bytes] =
    head.flatMap {
      case Head(2, Some(size)) if size <= Long.MaxValue => take(size).map(_.compact)
      case _                                            => fail(error)
    }
  private val break: Decoder[Boolean] = peek.flatMap { next =>
    if next == 255 then byte.as(true) else pure(false)
  }

  /** Cardano.Ledger.Binary.Plain.decodeListLikeT checks the declared arity only after fields; an
    * indefinite record instead consumes exactly one terminating break.
    */
  private def endRecord(
      size: Option[BigInt],
      expected: Int,
      wrongSize: BigInt => LeiosWireError,
      excess: LeiosWireError
  ): Decoder[Unit] = size match
    case Some(actual) => check(actual == expected, wrongSize(actual))
    case None         => break.flatMap(ended => check(ended, excess))

  private def point(tag: Int): Decoder[EbPoint] =
    val error = LeiosWireError.BadField(tag, LeiosField.Point)
    for
      size <- length(4, error)
      slot <- unsigned(0, Word64.Max, LeiosWireError.BadField(tag, LeiosField.Slot))
        .flatMap(n => StateT.liftF(Word64.from(n)))
      hash <- bytes(LeiosWireError.BadField(tag, LeiosField.Hash))
      _ <- endRecord(size, 2, _ => error, error)
    yield EbPoint(slot, EbHash.fromBytes(hash))

  /** Util.CBOR.decodeElemsUpTo: indefinite collections look for break before the bound, then decode
    * one entry. The reversed accumulator and tailRecM keep externally sized input safe.
    */
  private def collection[A](
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

  private def bitmaps(tag: Int): Decoder[TxBitmaps] =
    collection[(Word16, Word64)](tag, LeiosField.Bitmaps, 5, LeiosFetch.MaxBitmapEntries) {
      (_, previous) =>
        for
          window <- unsigned(0, Word16.Max, LeiosWireError.BadField(tag, LeiosField.Word16))
            .flatMap(n => StateT.liftF(Word16.from(n)))
          bitmap <- unsigned(0, Word64.Max, LeiosWireError.BadField(tag, LeiosField.Word64))
            .flatMap(n => StateT.liftF(Word64.from(n)))
          _ <- check(bitmap.toBigInt != 0, LeiosWireError.ZeroBitmap(tag, window.toInt))
          _ <- check(
            window.toInt < LeiosFetch.MaxBitmapEntries,
            LeiosWireError.BitmapWindowOutOfRange(tag, window.toInt, LeiosFetch.MaxBitmapEntries)
          )
          _ <- check(
            previous.forall(_._1.toInt < window.toInt),
            LeiosWireError.BitmapWindowsNotAscending(tag, window.toInt)
          )
        yield window -> bitmap
    }.map(TxBitmaps(_))

  private val block: Decoder[Message] =
    collection[(LeiosTxHash, Word32)](2, LeiosField.EndorserBlock, 5, LeiosFetch.MaxTxsPerEb) {
      (_, _) =>
        for
          hash <- bytes(LeiosWireError.BadField(2, LeiosField.Hash))
          size <- unsigned(0, Word32.Max, LeiosWireError.BadField(2, LeiosField.Word32))
            .flatMap(n => StateT.liftF(Word32.from(n)))
        yield LeiosTxHash.fromBytes(hash) -> size
    }.flatMap(txs => check(txs.nonEmpty, LeiosWireError.EmptyEndorserBlock).as(Message.Block(txs)))

  private val transactions: Decoder[Message] =
    collection[Bytes](4, LeiosField.Transactions, 4, LeiosFetch.MaxTxsPerEb) { (index, _) =>
      val error = LeiosWireError.BadTransaction(index)
      unsigned(6, Word64.Max, error).flatMap(tag => check(tag == 24, error)) *> bytes(error)
    }.map(Message.BlockTxs(_))

  /** Only the owner-approved pre-#2416 form contains arbitrary CBOR items. The existing codec's
    * heap-stack decoder supplies their original extents; no normalization enters the result.
    */
  private val legacyItem: Decoder[Bytes] = StateT { cursor =>
    val remaining = cursor.input.drop(cursor.position)
    Cbor
      .decodePrefix(
        remaining,
        Cbor.Limits(
          maxInputBytes = Int.MaxValue,
          maxItems = Int.MaxValue,
          maxStringBytes = Int.MaxValue
        )
      )
      .left
      .map {
        case Cbor.Error.UnexpectedEnd | Cbor.Error.TruncatedString => LeiosWireError.Incomplete
        case cause => LeiosWireError.Syntax(ProtocolError.Codec(cause))
      }
      .map(node => cursor.copy(position = cursor.position + node.original.size) -> node.original)
  }
  private val legacy: Decoder[Message] =
    for
      p <- point(4)
      b <- bitmaps(4)
      txs <- collection[Bytes](4, LeiosField.Transactions, 4, LeiosFetch.MaxTxsPerEb)((_, _) =>
        legacyItem
      )
    yield Message.LegacyBlockTxs(p, b, txs)

  /** An indefinite outer legacy record has no arity discriminator. Its point starts with an
    * unsigned slot, whereas the current transaction list starts with tag 24 (or is empty).
    */
  private def isLegacy(size: Option[BigInt]): Decoder[Boolean] = size match
    case Some(n) => pure(n == 4)
    case None =>
      val inspect = for
        n <- length(4, LeiosWireError.BadField(4, LeiosField.Transactions))
        candidate <-
          if n.exists(size => size == 0 || size > LeiosFetch.MaxTxsPerEb) then pure(false)
          else peek.map(_ >>> 5 == 0)
      yield candidate
      StateT(cursor => inspect.run(cursor).map { case (_, result) => cursor -> result })

  def decode(state: State, input: Bytes): Result[(Message, Int)] =
    val decoder = for
      size <- length(4, LeiosWireError.NotAnArray)
      tag <- unsigned(0, Word64.Max, LeiosWireError.BadField(-1, LeiosField.Word64))
      result <- (state, tag) match
        case (State.Idle, t) if t == 0 => pure(Message.Done -> 1)
        case (State.Idle, t) if t == 1 => point(1).map(p => Message.BlockRequest(p) -> 2)
        case (State.Idle, t) if t == 3 =>
          (point(3), bitmaps(3)).mapN((p, b) => Message.BlockTxsRequest(p, b) -> 3)
        case (State.Block, t) if t == 2 => block.map(_ -> 2)
        case (State.BlockTxs, t) if t == 4 =>
          isLegacy(size).flatMap(old =>
            if old then legacy.map(_ -> 4) else transactions.map(_ -> 2)
          )
        case (_, t) if t <= 4 => fail(LeiosWireError.NotAdmitted(t.toInt))
        case (_, t)           => fail(LeiosWireError.UnknownTag(t))
      (message, expected) = result
      _ <- endRecord(
        size,
        expected,
        n => LeiosWireError.WrongArity(tag.toInt, expected - 1, n - 1),
        LeiosWireError.ExcessTerms(tag.toInt)
      )
    yield message
    decoder.run(Cursor(input, 0)).map { case (cursor, message) => message -> cursor.position }
