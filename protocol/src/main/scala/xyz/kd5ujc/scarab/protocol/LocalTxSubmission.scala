// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}

import ChainSync.{DecodeResult, Role}
import ProtocolWire.{attempt, bad, get}

/** Node-to-client LocalTxSubmission (mini-protocol 6): the client sends one transaction at a time
  * and the node accepts or rejects it. `tx` and `reason` are single CBOR items whose contents (the
  * era-tagged transaction, the ledger's rejection) belong to the caller.
  */
object LocalTxSubmission:
  val MiniProtocolId = 6

  /** The most a message may take: the reference runs LocalTxSubmission with no byte limit
    * (`runPeer` over `codecLocalTxSubmission`,
    * `Ouroboros/Consensus/Network/NodeToClient.hs:455-465`, ouroboros-consensus 82ecba32), so only
    * its mux ingress queue bounds a transaction ([[ProtocolWire.Bound.Local]]), which the ledger
    * then judges by `maxTxSize` (#749).
    */
  val MaxMessageBytes: Int = Int.MaxValue
  private val wire = ProtocolWire.Limits(
    MaxMessageBytes,
    MaxMessageBytes,
    32,
    Int.MaxValue,
    Int.MaxValue,
    bound = ProtocolWire.Bound.Local
  )

  enum State:
    case Idle, Busy, Done
  enum Message:
    case SubmitTx(tx: Bytes)
    case AcceptTx
    case RejectTx(reason: Bytes)
    case Done

  /** How the decoder of a `MsgSubmitTx` transaction judges one, all of it or the part that has
    * arrived. The reference's codec reads the transaction with the block type's decoder from the
    * stream (`decodeWithByteSpan decodeTx`, ouroboros-network c45735a5
    * `ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/LocalTxSubmission/Codec.hs:148-154`),
    * so a transaction that decoder fails on ends the connection, a prefix of one before the rest
    * arrives; the node, which knows the transactions' structure, says where. The codec asks of a
    * whole transaction too, so a message ends alike whether it arrives whole or in parts.
    */
  trait TxReader:
    /** What the transaction's decoder makes of `input`, the transaction or the part of it that has
      * arrived.
      */
    def prefix(input: Bytes): TxPrefix

  object TxReader:
    /** Judges no prefix: the transaction is read whole before anything is said of it. */
    val whole: TxReader = _ => TxPrefix.Short

  enum TxPrefix:
    /** The transaction's decoder has not failed on it: it waits for more, or, of a whole
      * transaction, has read what the reader judges.
      */
    case Short

    /** The transaction's decoder fails on it, whatever follows. */
    case Refused(cause: DomainError)

  private enum Tag:
    case Submit, Accept, Reject, Done

  private def readTag(state: State, sender: Role, r: ProtocolWire.Reader)(using
      ProtocolWire.Scope
  ): Tag =
    if state == State.Done || (state == State.Idle) != (sender == Role.Client) then
      bad(ProtocolError.WrongLocalTxSubmissionAgency)
    val arity = r.arrayLength()
    val tag = r.uint().value
    (arity, tag.toInt) match
      case (2, 0) if tag == 0 => Tag.Submit
      case (1, 1) if tag == 1 => Tag.Accept
      case (2, 2) if tag == 2 => Tag.Reject
      case (1, 3) if tag == 3 => Tag.Done
      case _                  => bad(ProtocolError.UnknownLocalTxSubmissionTagOrArity)

  def transition(state: State, sender: Role, message: Message): Either[ProtocolError, State] =
    (state, sender, message) match
      case (State.Idle, Role.Client, Message.SubmitTx(_)) => Right(State.Busy)
      case (State.Idle, Role.Client, Message.Done)        => Right(State.Done)
      case (State.Busy, Role.Server, Message.AcceptTx)    => Right(State.Idle)
      case (State.Busy, Role.Server, Message.RejectTx(_)) => Right(State.Idle)
      case _ => Left(ProtocolError.LocalTxSubmissionAgency(state, sender))

  def encode(state: State, sender: Role, message: Message): Either[ProtocolError, Bytes] = attempt {
    get(transition(state, sender, message))
    val w = new ProtocolWire.Writer(wire)
    def outer(n: Int, tag: Int): Unit = { w.head(4, n); w.head(0, tag) }
    message match
      case Message.SubmitTx(tx) =>
        get(ProtocolWire.validateItem(tx, wire)); outer(2, 0); w.raw(tx)
      case Message.AcceptTx => outer(1, 1)
      case Message.RejectTx(reason) =>
        get(ProtocolWire.validateItem(reason, wire)); outer(2, 2); w.raw(reason)
      case Message.Done => outer(1, 3)
    w.result
  }

  /** Each message read as before, then a client's `SubmitTx` judged by `txs` ([[TxReader]]): a
    * transaction it refuses, whole or while it has not all arrived, ends there.
    */
  def decodePrefix(
      state: State,
      sender: Role,
      bytes: Bytes,
      txs: TxReader = TxReader.whole
  ): DecodeResult[Message] =
    judged(state, sender, bytes, txs, decoded(state, sender, bytes))

  /** `result`, the message as read, judged by `txs`: a `SubmitTx` whose transaction it refuses ends
    * here; an incomplete message waits unless [[waiting]] ends it. A message the framing fails on
    * is judged as an incomplete one first: `txs` reads the transaction's heads in order from its
    * start, so a token it refuses is at or before the one the framing failed at, and that refusal
    * is the reference's, which reads the transaction from the stream; the message then ends as it
    * would in parts. Any other failure stands.
    */
  private def judged(
      state: State,
      sender: Role,
      bytes: Bytes,
      txs: TxReader,
      result: DecodeResult[Message]
  ): DecodeResult[Message] =
    result match
      case DecodeResult.NeedMore => waiting(state, sender, bytes, txs)
      // read whole, a `SubmitTx` is a client's in `Idle`: [[transition]] allows it nowhere else
      case DecodeResult.Decoded(Message.SubmitTx(tx), _) =>
        txs.prefix(tx) match
          case TxPrefix.Short => result
          case TxPrefix.Refused(cause) =>
            DecodeResult.Failed(ProtocolError.UndecodableTransaction(cause))
      case DecodeResult.Decoded(
            Message.AcceptTx | Message.RejectTx(_) | Message.Done,
            _
          ) =>
        result
      case DecodeResult.Failed(_) =>
        waiting(state, sender, bytes, txs) match
          case refused @ DecodeResult.Failed(ProtocolError.UndecodableTransaction(_)) => refused
          case DecodeResult.NeedMore | DecodeResult.Failed(_) | DecodeResult.Decoded(_, _) =>
            result

  private def decoded(state: State, sender: Role, bytes: Bytes): DecodeResult[Message] =
    ProtocolWire.prefix {
      val r = new ProtocolWire.Reader(bytes, wire)
      val message = readTag(state, sender, r) match
        case Tag.Submit => Message.SubmitTx(r.item())
        case Tag.Accept => Message.AcceptTx
        case Tag.Reject => Message.RejectTx(r.item())
        case Tag.Done   => Message.Done
      get(transition(state, sender, message))
      DecodeResult.Decoded(message, r.position)
    }

  /** An incomplete message: a client's `SubmitTx` in `Idle` whose transaction `txs` refuses ends
    * here, anything else waits. Only the heads of the first tokens are read, so a transaction that
    * arrives in many parts is not read again from its start each time (#749).
    */
  private def waiting(
      state: State,
      sender: Role,
      bytes: Bytes,
      txs: TxReader
  ): DecodeResult[Message] =
    ProtocolWire.prefix {
      val r = new ProtocolWire.Reader(bytes, wire)
      (state, sender, readTag(state, sender, r)) match
        case (State.Idle, Role.Client, Tag.Submit) =>
          txs.prefix(bytes.drop(r.position)) match
            case TxPrefix.Short          => DecodeResult.NeedMore
            case TxPrefix.Refused(cause) => bad(ProtocolError.UndecodableTransaction(cause))
        case (
              State.Idle | State.Busy | State.Done,
              Role.Client | Role.Server,
              Tag.Submit | Tag.Accept | Tag.Reject | Tag.Done
            ) =>
          DecodeResult.NeedMore
    }

  /** The same codec with framing progress retained across incomplete prefixes; an incomplete
    * message is judged as [[decodePrefix]] judges it.
    */
  def incremental(
      state: State,
      sender: Role,
      txs: TxReader = TxReader.whole
  ): IncrementalDecoder[Message] =
    def prefix(bytes: Bytes): DecodeResult[Option[Int]] = ProtocolWire.prefix {
      val r = new ProtocolWire.Reader(bytes, wire)
      val payload = readTag(state, sender, r) match
        case Tag.Submit | Tag.Reject => Some(r.position)
        case _                       => None
      DecodeResult.Decoded(payload, r.position)
    }
    val framed = CborMessageDecoder(prefix, decoded(state, sender, _))
    new IncrementalDecoder[Message]:
      type Cursor = framed.Cursor
      val initial: Cursor = framed.initial
      def resume(cursor: Cursor, input: Bytes): (Cursor, DecodeResult[Message]) =
        val (next, result) = framed.resume(cursor, input)
        (next, judged(state, sender, input, txs, result))
