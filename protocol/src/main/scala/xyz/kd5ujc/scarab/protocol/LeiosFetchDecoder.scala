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
  */
private[protocol] object LeiosFetchDecoder:
  import LeiosStream.*

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

  /** `decodeLeiosEb` (`LeiosDemoTypes.hs:1301-1310`): `decodeMapLen`, a definite map, whose count
    * must be between 1 and `maxTxsPerEb` before any entry is read; each entry a transaction hash of
    * 32 bytes (`decodeFixedSized` of `TxHash`, `PackedBytes 32`) and a `Word32` size.
    */
  private val block: Decoder[Message] =
    definite(5, LeiosWireError.BadField(2, LeiosField.EndorserBlock)).flatMap { n =>
      check(n != 0, LeiosWireError.EmptyEndorserBlock) *>
        check(
          n <= LeiosFetch.MaxTxsPerEb,
          LeiosWireError.TooMany(2, LeiosField.EndorserBlock, LeiosFetch.MaxTxsPerEb, n)
        ) *>
        repeat(n)(_ =>
          for
            hash <- fixed(32, LeiosWireError.BadField(2, LeiosField.Hash))
            size <- unsigned(0, Word32.Max, LeiosWireError.BadField(2, LeiosField.Word32))
              .flatMap(n => StateT.liftF(Word32.from(n)))
          yield LeiosTxHash.fromBytes(hash) -> size
        ).map(Message.Block(_))
    }

  private val transactions: Decoder[Message] =
    collection[Bytes](4, LeiosField.Transactions, 4, LeiosFetch.MaxTxsPerEb) { (index, _) =>
      val error = LeiosWireError.BadTransaction(index)
      unsigned(6, Word64.Max, error).flatMap(tag => check(tag == 24, error)) *> bytes(error)
    }.map(Message.BlockTxs(_))

  /** `decode` of `codecLeiosFetch` (ouroboros-consensus `2416493b`,
    * `LeiosDemoOnlyTestFetch.hs:279-282, 350-388`): `decodeListLen`, a definite list only, and
    * `decodeWord`, then the length and the key are matched together with the state before any field
    * is read, so a list of another length is refused before its fields.
    */
  def decode(state: State, input: Bytes): Result[(Message, Int)] =
    val decoder = for
      size <- length(4, LeiosWireError.NotAnArray).flatMap(n =>
        n.fold(fail[BigInt](LeiosWireError.NotAnArray))(pure)
      )
      tag <- unsigned(0, Word64.Max, LeiosWireError.BadField(-1, LeiosField.Word64))
      message <- (state, tag) match
        case (State.Idle, t) if t == 0 => fields(0, size, 1)(pure(Message.Done))
        case (State.Idle, t) if t == 1 => fields(1, size, 2)(point(1).map(Message.BlockRequest(_)))
        case (State.Idle, t) if t == 3 =>
          fields(3, size, 3)((point(3), bitmaps(3)).mapN(Message.BlockTxsRequest(_, _)))
        case (State.Block, t) if t == 2    => fields(2, size, 2)(block)
        case (State.BlockTxs, t) if t == 4 => fields(4, size, 2)(transactions)
        case (_, t) if t <= 4              => fail(LeiosWireError.NotAdmitted(t.toInt))
        case (_, t)                        => fail(LeiosWireError.UnknownTag(t))
    yield message
    run(decoder, input)

  /** The fields of message `tag` when the list has the `expected` length, key included. */
  private def fields(tag: Int, size: BigInt, expected: Int)(
      body: Decoder[Message]
  ): Decoder[Message] =
    check(size == expected, LeiosWireError.WrongArity(tag, expected - 1, size - 1)) *> body
