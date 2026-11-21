// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import cats.data.StateT
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes

import LeiosFetch.{Message, State}
import LeiosWord.{Word16, Word32, Word64}

/** The concrete LeiosFetch decoder, as the build the Leios testnet runs reads every message
  * (ouroboros-consensus `2416493b`, [[LeiosFetch]]): the message frame, the point
  * ([[LeiosStream.point]]), the endorser block, the bitmaps and `MsgLeiosBlockTxs`. Bounded
  * collections reject their length before reading entries; bitmap checks run before the next entry.
  * Transactions and hashes use cborg's definite decodeBytes. Protocol byte limits belong to the
  * receiving driver, not the CBOR codec.
  *
  * A message is read in steps ([[LeiosStream.resume]]): the frame, with the point and the head of
  * the collection that follows, then each entry of the collection. A prefix that runs out of input
  * waits at the step it is in, and a token the codec refuses fails the message as soon as it has
  * arrived, however the message is cut into parts, as cborg's decoder fails a message the driver
  * feeds it chunk by chunk (`runDecoderWithLimit`).
  */
private[protocol] object LeiosFetchDecoder:
  import LeiosStream.*

  /** Where the decoding of a message stands between its steps. */
  enum Checkpoint:
    case Frame

    /** The bitmaps of `MsgLeiosBlockTxsRequest` for `point`, after the entries `read`. */
    case Bitmaps(point: EbPoint, size: Option[BigInt], read: Entries[(Word16, Word64)])

    /** The `size` entries of an endorser block, after the entries `read`. */
    case EndorserBlock(size: BigInt, read: Entries[(LeiosTxHash, Word32)])

    /** The transactions of `MsgLeiosBlockTxs`, after the transactions `read`. */
    case Transactions(size: Option[BigInt], read: Entries[Bytes])

  /** A message read from its start, before any step. */
  val start: (Checkpoint, Int) = (Checkpoint.Frame, 0)

  private def bitmap(tag: Int)(previous: Option[(Word16, Word64)]): Decoder[(Word16, Word64)] =
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

  /** `decodeLeiosEb` (`LeiosDemoTypes.hs:1301-1310`, ouroboros-consensus 2416493b): `decodeMapLen`,
    * a definite map, whose count must be between 1 and `maxTxsPerEb` before any entry is read; each
    * entry a transaction hash of 32 bytes (`decodeFixedSized` of `TxHash`, `PackedBytes 32`) and a
    * `Word32` size.
    */
  private val blockHead: Decoder[BigInt] =
    definite(5, LeiosWireError.BadField(2, LeiosField.EndorserBlock)).flatMap { n =>
      check(n != 0, LeiosWireError.EmptyEndorserBlock) *>
        check(
          n <= LeiosFetch.MaxTxsPerEb,
          LeiosWireError.TooMany(2, LeiosField.EndorserBlock, LeiosFetch.MaxTxsPerEb, n)
        ).as(n)
    }

  private val blockEntry: Decoder[(LeiosTxHash, Word32)] =
    for
      hash <- fixed(32, LeiosWireError.BadField(2, LeiosField.Hash))
      size <- unsigned(0, Word32.Max, LeiosWireError.BadField(2, LeiosField.Word32))
        .flatMap(n => StateT.liftF(Word32.from(n)))
    yield LeiosTxHash.fromBytes(hash) -> size

  private def transaction(index: Int): Decoder[Bytes] =
    val error = LeiosWireError.BadTransaction(index)
    unsigned(6, Word64.Max, error).flatMap(tag => check(tag == 24, error)) *> bytes(error)

  /** The frame of `decode` of `codecLeiosFetch` (ouroboros-consensus `2416493b`,
    * `LeiosDemoOnlyTestFetch.hs:279-282, 350-388`): `decodeListLen`, a definite list only, and
    * `decodeWord`, then the length and the key are matched together with the state before any field
    * is read, so a list of another length is refused before its fields. The point, and the head of
    * the collection that follows, are read in the same step.
    */
  private def frame(state: State): Decoder[Either[Checkpoint, Message]] =
    for
      size <- definite(4, LeiosWireError.NotAnArray)
      tag <- unsigned(0, Word64.Max, LeiosWireError.BadField(-1, LeiosField.Word64))
      next <- (state, tag) match
        case (State.Idle, t) if t == 0 => fields(0, size, 1)(pure(Right(Message.Done)))
        case (State.Idle, t) if t == 1 =>
          fields(1, size, 2)(point(1).map(p => Right(Message.BlockRequest(p))))
        case (State.Idle, t) if t == 3 =>
          fields(3, size, 3)(
            (
              point(3),
              collectionHead(3, LeiosField.Bitmaps, 5, LeiosFetch.MaxBitmapEntries)
            ).mapN((p, n) => Left(Checkpoint.Bitmaps(p, n, Entries.none)))
          )
        case (State.Block, t) if t == 2 =>
          fields(2, size, 2)(blockHead.map(n => Left(Checkpoint.EndorserBlock(n, Entries.none))))
        case (State.BlockTxs, t) if t == 4 =>
          fields(4, size, 2)(
            collectionHead(4, LeiosField.Transactions, 4, LeiosFetch.MaxTxsPerEb)
              .map(n => Left(Checkpoint.Transactions(n, Entries.none)))
          )
        case (_, t) if t <= 4 => fail(LeiosWireError.NotAdmitted(t.toInt))
        case (_, t)           => fail(LeiosWireError.UnknownTag(t))
    yield next

  /** One step of a message of `state` from `stage`: the frame, or one entry of its collection. */
  private def step(state: State)(stage: Checkpoint): Decoder[Either[Checkpoint, Message]] =
    stage match
      case Checkpoint.Frame => frame(state)
      case Checkpoint.Bitmaps(p, size, read) =>
        collectionStep(3, LeiosField.Bitmaps, LeiosFetch.MaxBitmapEntries, size, read)(
          (_, previous) => bitmap(3)(previous)
        ).map(
          _.bimap(Checkpoint.Bitmaps(p, size, _), b => Message.BlockTxsRequest(p, TxBitmaps(b)))
        )
      case Checkpoint.EndorserBlock(size, read) =>
        if BigInt(read.count) >= size then pure(Right(Message.Block(read.inOrder)))
        else blockEntry.map(entry => Left(Checkpoint.EndorserBlock(size, read.added(entry))))
      case Checkpoint.Transactions(size, read) =>
        collectionStep(4, LeiosField.Transactions, LeiosFetch.MaxTxsPerEb, size, read)((index, _) =>
          transaction(index)
        ).map(_.bimap(Checkpoint.Transactions(size, _), Message.BlockTxs(_)))

  /** A message of `state` read on from the checkpoint `at` of a shorter prefix of `input`. */
  def resumed(state: State, at: (Checkpoint, Int), input: Bytes): Resumed[Checkpoint, Message] =
    resume(at._1, at._2, input)(step(state))

  /** A message of `state` read from the start of `input`. */
  def decode(state: State, input: Bytes): Result[(Message, Int)] =
    resumed(state, start, input) match
      case Resumed.Short(_, _)  => Left(LeiosWireError.Incomplete)
      case Resumed.Done(result) => result

  /** The fields of message `tag` when the list has the `expected` length, key included. */
  private def fields[A](tag: Int, size: BigInt, expected: Int)(body: Decoder[A]): Decoder[A] =
    check(size == expected, LeiosWireError.WrongArity(tag, expected - 1, size - 1)) *> body
