// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.{DecodeResult, Role}
import ProtocolWire.{attempt, bad, get}

/** Node-to-client LocalTxSubmission (mini-protocol 6): the client sends one transaction at a time
  * and the node accepts or rejects it. `tx` and `reason` are single CBOR items whose contents (the
  * era-tagged transaction, the ledger's rejection) belong to the caller.
  */
object LocalTxSubmission:
  val MiniProtocolId = 6
  val MaxMessageBytes = 1048576
  private val wire = ProtocolWire.Limits(
    MaxMessageBytes,
    MaxMessageBytes,
    32,
    ProtocolWire.MaxItems,
    ProtocolWire.MaxItems
  )

  enum State:
    case Idle, Busy, Done
  enum Message:
    case SubmitTx(tx: Bytes)
    case AcceptTx
    case RejectTx(reason: Bytes)
    case Done

  def transition(state: State, sender: Role, message: Message): Either[String, State] =
    (state, sender, message) match
      case (State.Idle, Role.Client, Message.SubmitTx(_)) => Right(State.Busy)
      case (State.Idle, Role.Client, Message.Done)        => Right(State.Done)
      case (State.Busy, Role.Server, Message.AcceptTx)    => Right(State.Idle)
      case (State.Busy, Role.Server, Message.RejectTx(_)) => Right(State.Idle)
      case _ => Left(s"illegal LocalTxSubmission message/agency in $state from $sender")

  def encode(state: State, sender: Role, message: Message): Either[String, Bytes] = attempt {
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

  def decodePrefix(state: State, sender: Role, bytes: Bytes): DecodeResult[Message] =
    try
      if state == State.Done || (state == State.Idle) != (sender == Role.Client) then
        bad("wrong LocalTxSubmission agency")
      val r = new ProtocolWire.Reader(bytes, wire)
      val arity = r.arrayLength()
      val tag = r.uint().value
      val message = (arity, tag.toInt) match
        case (2, 0) if tag == 0 => Message.SubmitTx(r.item())
        case (1, 1) if tag == 1 => Message.AcceptTx
        case (2, 2) if tag == 2 => Message.RejectTx(r.item())
        case (1, 3) if tag == 3 => Message.Done
        case _                  => bad("unknown LocalTxSubmission tag or arity")
      get(transition(state, sender, message))
      DecodeResult.Decoded(message, r.position)
    catch
      case ProtocolWire.Failure(_, true)      => DecodeResult.NeedMore
      case ProtocolWire.Failure(error, false) => DecodeResult.Failed(error)
