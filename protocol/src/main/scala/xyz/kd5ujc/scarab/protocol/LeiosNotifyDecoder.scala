// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import cats.data.StateT
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.bls.{BlsError, CborFault, RbHash, SeatId, Signature, Vote}

import LeiosNotify.{Message, State}
import LeiosWord.{Word16, Word32, Word64}

/** The concrete LeiosNotify decoder, as the build the Leios testnet runs reads every message:
  * `codecLeiosNotify` of ouroboros-consensus `2416493b`
  * (`ouroboros-consensus/src/ouroboros-consensus/LeiosDemoOnlyTestNotify.hs:259-396`), wired in
  * `Ouroboros/Consensus/Network/NodeToNode.hs:1074-1081` with `decodeLeiosPoint`, the node-to-node
  * header decoder and `decodeLeiosVote`.
  */
private[protocol] object LeiosNotifyDecoder:
  import LeiosStream.*

  private def malformed(index: Int, fault: CborFault): LeiosWireError =
    LeiosWireError.BadVote(index, BlsError.MalformedCbor(fault))

  /** `decodeLeiosVote` (`LeiosDemoTypes.hs:1379-1390`): `enforceSize 3`, the announcing ranking
    * block hash (`decodeRbHash`, 32 bytes, `Leios/Types.hs:127-133`), the seat a `Word16` of any
    * head width, and the signature `decodeFixedSized` (48 bytes, decompressed and checked in the
    * subgroup, the identity included: [[Signature.decoded]]).
    */
  private def vote(index: Int): Decoder[(Vote, Bytes)] =
    for
      start <- position
      _ <- enforceSize(3, malformed(index, CborFault.NotAnArray))
      hash <- bytes(malformed(index, CborFault.FieldNotBytes(0)))
      seat <- unsigned(0, Word16.Max, malformed(index, CborFault.SeatAbove16Bits))
      signature <- bytes(malformed(index, CborFault.FieldNotBytes(2)))
      end <- position
      raw <- StateT.inspect[Result, Cursor, Bytes](_.input.slice(start, end))
      decoded <- StateT.liftF[Result, Cursor, Vote](
        (
          RbHash
            .fromBytes(hash)
            .toRight(CborFault.HashNotThirtyTwoBytes(hash.size))
            .leftMap(f => BlsError.MalformedCbor(f)),
          SeatId.fromInt(seat.toInt),
          Signature.decoded(signature)
        ).mapN(Vote(_, _, _)).leftMap(LeiosWireError.BadVote(index, _))
      )
    yield decoded -> raw

  /** `decodeVotes`: `decodeListLen`, a definite list, of any count, `replicateM` of the votes. */
  private val votes: Decoder[Message] =
    definite(4, LeiosWireError.BadField(7, LeiosField.Votes))
      .flatMap(n => repeat(n)(vote))
      .map(vs => Message.Votes(vs.map(_._1), vs.map(_._2)))

  /** The announcing header, `Header blk` read by the node-to-node decoder: the hard-fork envelope
    * `[era, payload]` of one of the eras ([[HfcHeader.decode]]); its original bytes are kept.
    */
  private val announcement: Decoder[Message] =
    item.flatMap(raw =>
      StateT.liftF[Result, Cursor, Message](
        HfcHeader.decode(raw) match
          case Right(_: HfcHeader.Unrecognised) | Left(_) =>
            Left(LeiosWireError.BadField(4, LeiosField.Header))
          case Right(_) => Right(Message.BlockAnnouncement(raw))
      )
    )

  /** `decode` of `codecLeiosNotify` (`:279-283, 353-396`): `decodeListLen`, a definite list only,
    * and `decodeWord`, then the length, the key and the state are matched together before any field
    * is read.
    */
  def decode(state: State, input: Bytes): Result[(Message, Int)] =
    val decoder = for
      size <- definite(4, LeiosWireError.NotAnArray)
      key <- unsigned(0, Word64.Max, LeiosWireError.BadField(-1, LeiosField.Word64))
      message <- (state, key) match
        case (State.Quit, k) if k == 0 => fields(0, size, 1)(pure(Message.Done))
        case (State.Idle, k) if k == 1 => fields(1, size, 1)(pure(Message.Quit))
        case (State.Busy, k) if k == 2 => fields(2, size, 1)(pure(Message.Canceled))
        case (State.Idle, k) if k == 3 => fields(3, size, 1)(pure(Message.RequestNext))
        case (State.Busy, k) if k == 4 => fields(4, size, 2)(announcement)
        case (State.Busy, k) if k == 5 =>
          fields(5, size, 3)(
            for
              p <- point(5)
              ebSize <- unsigned(0, Word32.Max, LeiosWireError.BadField(5, LeiosField.Word32))
                .flatMap(n => StateT.liftF(Word32.from(n)))
            yield Message.BlockOffer(p, ebSize)
          )
        case (State.Busy, k) if k == 6 => fields(6, size, 2)(point(6).map(Message.BlockTxsOffer(_)))
        case (State.Busy, k) if k == 7 => fields(7, size, 2)(votes)
        case (_, k) if k <= 7          => fail(LeiosWireError.NotAdmitted(k.toInt))
        case (_, k)                    => fail(LeiosWireError.UnknownTag(k))
    yield message
    run(decoder, input)

  /** The fields of message `key` when the list has the `expected` length, key included. */
  private def fields(key: Int, size: BigInt, expected: Int)(
      body: Decoder[Message]
  ): Decoder[Message] =
    check(size == expected, LeiosWireError.WrongArity(key, expected - 1, size - 1)) *> body
