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

  /** How the decoder of a `MsgSubmitTx` transaction judges one only part of which has arrived. The
    * reference's codec reads the transaction with the block type's decoder from the stream
    * (`decodeWithByteSpan decodeTx`, ouroboros-network c45735a5
    * `ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/LocalTxSubmission/Codec.hs:148-154`),
    * so a prefix that decoder fails on ends the connection before the rest arrives; the node, which
    * knows the transactions' structure, says where. A whole transaction is read as one item, as
    * before.
    */
  trait TxReader:
    /** What the transaction's decoder makes of `input`, the part of it that has arrived. */
    def prefix(input: Bytes): TxPrefix

  object TxReader:
    /** Judges no prefix: the transaction is read whole before anything is said of it. */
    val whole: TxReader = _ => TxPrefix.Short

  enum TxPrefix:
    /** The transaction's decoder waits for more. */
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

  /** Each message read as before; while the transaction of a client's `SubmitTx` has not all
    * arrived, `txs` says whether its decoder has already failed on what has ([[TxReader]]).
    */
  def decodePrefix(
      state: State,
      sender: Role,
      bytes: Bytes,
      txs: TxReader = TxReader.whole
  ): DecodeResult[Message] =
    decoded(state, sender, bytes) match
      case DecodeResult.NeedMore => waiting(state, sender, bytes, txs)
      case other                 => other

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

  /** An incomplete message: a `SubmitTx` whose transaction `txs` refuses ends here, anything else
    * waits. Only the heads of the first tokens are read, so a transaction that arrives in many
    * parts is not read again from its start each time (#749).
    */
  private def waiting(
      state: State,
      sender: Role,
      bytes: Bytes,
      txs: TxReader
  ): DecodeResult[Message] =
    ProtocolWire.prefix {
      val r = new ProtocolWire.Reader(bytes, wire)
      readTag(state, sender, r) match
        case Tag.Submit =>
          txs.prefix(bytes.drop(r.position)) match
            case TxPrefix.Short          => DecodeResult.NeedMore
            case TxPrefix.Refused(cause) => bad(ProtocolError.UndecodableTransaction(cause))
        case Tag.Accept | Tag.Reject | Tag.Done => DecodeResult.NeedMore
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
        framed.resume(cursor, input) match
          case (next, DecodeResult.NeedMore) => (next, waiting(state, sender, input, txs))
          case done                          => done
