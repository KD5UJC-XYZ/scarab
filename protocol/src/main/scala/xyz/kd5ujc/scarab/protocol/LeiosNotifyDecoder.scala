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

  /** `decodeLeiosVote` (`LeiosDemoTypes.hs:1379-1390`, ouroboros-consensus 2416493b): `enforceSize
    * 3`, the announcing ranking block hash (`decodeRbHash`, 32 bytes, `Leios/Types.hs:127-133`),
    * the seat a `Word16` of any head width, and the signature `decodeFixedSized` (48 bytes,
    * decompressed and checked in the subgroup, the identity included: [[Signature.decoded]]).
    */
  private def vote(index: Int): Decoder[(Vote, Bytes)] =
    for
      start <- position
      _ <- enforceSize(3, malformed(index, CborFault.NotAnArray))
      hash <- bytes(malformed(index, CborFault.FieldNotBytes(0)))
      rbHash <- StateT.liftF[Result, Cursor, RbHash](
        RbHash.fromBytes(hash).toRight(malformed(index, CborFault.HashNotThirtyTwoBytes(hash.size)))
      )
      seat <- unsigned(0, Word16.Max, malformed(index, CborFault.SeatAbove16Bits))
      signature <- bytes(malformed(index, CborFault.FieldNotBytes(2)))
      end <- position
      raw <- StateT.inspect[Result, Cursor, Bytes](_.input.slice(start, end))
      decoded <- StateT.liftF[Result, Cursor, Vote](
        (
          SeatId.fromInt(seat.toInt),
          Signature.decoded(signature)
        ).mapN(Vote(rbHash, _, _)).leftMap(LeiosWireError.BadVote(index, _))
      )
    yield decoded -> raw

  /** Where the decoding of a message stands between its steps ([[LeiosStream.resume]]). */
  enum Checkpoint:
    case Frame

    /** The `size` votes of `MsgLeiosVotes`, after the votes `read`. */
    case Votes(size: BigInt, read: Entries[(Vote, Bytes)])

  /** A message read from its start, before any step. */
  val start: (Checkpoint, Int) = (Checkpoint.Frame, 0)

  /** `decodeVotes`: `decodeListLen`, a definite list, of any count, then `replicateM` of the votes,
    * each read in a step of its own, so that a vote is decoded, its signature checked in the
    * subgroup, once however the message is cut into parts.
    */
  private val votesHead: Decoder[Either[Checkpoint, Message]] =
    definite(4, LeiosWireError.BadField(7, LeiosField.Votes))
      .map(n => Left(Checkpoint.Votes(n, Entries.none)))

  private def votesStep(
      size: BigInt,
      read: Entries[(Vote, Bytes)]
  ): Decoder[Either[Checkpoint, Message]] =
    if BigInt(read.count) >= size then
      val vs = read.inOrder
      pure(Right(Message.Votes(vs.map(_._1), vs.map(_._2))))
    else vote(read.count).map(v => Left(Checkpoint.Votes(size, read.added(v))))

  /** The announcing header, `Header blk` read by the node-to-node decoder of its era: the hard-fork
    * envelope `[era, payload]` of one of the eras ([[HfcHeader.decode]]), then the header inside it
    * by the era's decoder, which the node supplies ([[LeiosNotify.AnnouncementReader]]); its
    * original bytes are kept.
    */
  private def announcement(using reader: LeiosNotify.AnnouncementReader): Decoder[Message] =
    announcementBytes.flatMap(raw =>
      StateT.liftF[Result, Cursor, Message](
        HfcHeader.decode(raw) match
          case Right(_: HfcHeader.Unrecognised) | Left(_) =>
            Left(LeiosWireError.BadField(4, LeiosField.Header))
          case Right(header) =>
            reader
              .read(header)
              .bimap(LeiosWireError.UndecodableAnnouncement(_), _ => Message.BlockAnnouncement(raw))
      )
    )

  /** The hard-fork wrapper has a fixed grammar ([[NtNHeader]]). Read it before the opaque header
    * bytes, as the reference's node-to-node decoder does; an invalid wrapper must not cause an
    * arbitrary CBOR tree to be scanned while waiting for a header that cannot be admitted.
    */
  private val announcementBytes: Decoder[Bytes] =
    val bad = LeiosWireError.BadField(4, LeiosField.Header)
    for
      start <- position
      _ <- enforceSize(2, bad)
      era <- unsigned(0, BigInt(7), bad)
      _ <-
        if era == 0 then
          enforceSize(2, bad) *> enforceSize(2, bad) *>
            unsigned(0, BigInt(1), bad) *> unsigned(0, Word32.Max, bad).void
        else pure(())
      tag <- unsigned(6, BigInt(24), bad)
      _ <- check(tag == 24, bad)
      _ <- bytes(bad)
      end <- position
      raw <- StateT.inspect[Result, Cursor, Bytes](_.input.slice(start, end))
    yield raw

  /** `decode` of `codecLeiosNotify` (`:279-283, 353-396`): `decodeListLen`, a definite list only,
    * and `decodeWord`, then the length, the key and the state are matched together before any field
    * is read.
    */
  def decode(state: State, input: Bytes)(using
      LeiosNotify.AnnouncementReader
  ): Result[(Message, Int)] =
    resumed(state, start, input) match
      case Resumed.Short(_, _)  => Left(LeiosWireError.Incomplete)
      case Resumed.Done(result) => result

  /** A message of `state` read on from the checkpoint `at` of a shorter prefix of `input`. */
  def resumed(state: State, at: (Checkpoint, Int), input: Bytes)(using
      LeiosNotify.AnnouncementReader
  ): Resumed[Checkpoint, Message] =
    resume(at._1, at._2, input)(step(state, announcement))

  /** The client's messages, of Idle, which carry no header: the server reads them with no reader of
    * one.
    */
  def decodeIdle(input: Bytes): Result[(Message, Int)] =
    resume(Checkpoint.Frame, 0, input)(
      step(State.Idle, fail(LeiosWireError.NotAdmitted(4)))
    ) match
      case Resumed.Short(_, _)  => Left(LeiosWireError.Incomplete)
      case Resumed.Done(result) => result

  /** One step of a message of `state` from `stage`: the frame, or one vote. */
  private def step(state: State, announced: => Decoder[Message])(
      stage: Checkpoint
  ): Decoder[Either[Checkpoint, Message]] =
    stage match
      case Checkpoint.Frame             => frame(state, announced)
      case Checkpoint.Votes(size, read) => votesStep(size, read)

  /** The frame of the message of `state` at the cursor, with every field but the votes, an
    * announcement read by `announced`.
    */
  private def frame(
      state: State,
      announced: => Decoder[Message]
  ): Decoder[Either[Checkpoint, Message]] =
    for
      size <- definite(4, LeiosWireError.NotAnArray)
      key <- unsigned(0, Word64.Max, LeiosWireError.BadField(-1, LeiosField.Word64))
      message <- (state, key) match
        case (State.Quit, k) if k == 0 => fields(0, size, 1)(pure(Right(Message.Done)))
        case (State.Idle, k) if k == 1 => fields(1, size, 1)(pure(Right(Message.Quit)))
        case (State.Busy, k) if k == 2 => fields(2, size, 1)(pure(Right(Message.Canceled)))
        case (State.Idle, k) if k == 3 => fields(3, size, 1)(pure(Right(Message.RequestNext)))
        case (State.Busy, k) if k == 4 => fields(4, size, 2)(announced.map(Right(_)))
        case (State.Busy, k) if k == 5 =>
          fields(5, size, 3)(
            for
              p <- point(5)
              ebSize <- unsigned(0, Word32.Max, LeiosWireError.BadField(5, LeiosField.Word32))
                .flatMap(n => StateT.liftF(Word32.from(n)))
            yield Right(Message.BlockOffer(p, ebSize))
          )
        case (State.Busy, k) if k == 6 =>
          fields(6, size, 2)(point(6).map(p => Right(Message.BlockTxsOffer(p))))
        case (State.Busy, k) if k == 7 => fields(7, size, 2)(votesHead)
        case (_, k) if k <= 7          => fail(LeiosWireError.NotAdmitted(k.toInt))
        case (_, k)                    => fail(LeiosWireError.UnknownTag(k))
    yield message

  /** The fields of message `key` when the list has the `expected` length, key included. */
  private def fields[A](key: Int, size: BigInt, expected: Int)(
      body: Decoder[A]
  ): Decoder[A] =
    check(size == expected, LeiosWireError.WrongArity(key, expected - 1, size - 1)) *> body
