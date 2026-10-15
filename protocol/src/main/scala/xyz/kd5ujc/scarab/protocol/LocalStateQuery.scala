// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes
import ChainSync.{DecodeResult, Point, Role}
import ProtocolWire.{attempt, bad, get}

/** Node-to-client LocalStateQuery (mini-protocol 7): the client acquires the ledger state at a
  * point, asks queries of it and releases it. `query` and `result` are single CBOR items whose
  * contents (the hard-fork-combinator query envelope and its answer) belong to the caller.
  *
  * States and agency (ouroboros-network `Protocol.LocalStateQuery.Type`):
  *   - `Idle` (client): `Acquire` or `Done`;
  *   - `Acquiring` (server): `Acquired` or `Failure`;
  *   - `Acquired` (client): `Query`, `ReAcquire` or `Release`;
  *   - `Querying` (server): `Result`.
  */
object LocalStateQuery:
  val MiniProtocolId = 7
  val MaxMessageBytes = 2500000
  private val wire = ProtocolWire.Limits(
    MaxMessageBytes,
    MaxMessageBytes,
    32,
    ProtocolWire.MaxItems,
    ProtocolWire.MaxItems
  )

  enum State:
    case Idle, Acquiring, Acquired, Querying, Done

  /** What a client asks to acquire. The immutable tip exists from node-to-client version 16. */
  enum Target:
    case Specific(point: Point)
    case VolatileTip
    case ImmutableTip

  enum Failure:
    /** The point is older than the immutable tip (tag 0). */
    case PointTooOld

    /** The point is not on the node's chain (tag 1). */
    case PointNotOnChain

  enum Message:
    case Acquire(target: Target)
    case Acquired
    case Failed(failure: Failure)
    case Query(query: Bytes)
    case Result(result: Bytes)
    case Release
    case ReAcquire(target: Target)
    case Done

  def transition(state: State, sender: Role, message: Message): Either[String, State] =
    (state, sender, message) match
      case (State.Idle, Role.Client, Message.Acquire(_))       => Right(State.Acquiring)
      case (State.Idle, Role.Client, Message.Done)             => Right(State.Done)
      case (State.Acquiring, Role.Server, Message.Acquired)    => Right(State.Acquired)
      case (State.Acquiring, Role.Server, Message.Failed(_))   => Right(State.Idle)
      case (State.Acquired, Role.Client, Message.Query(_))     => Right(State.Querying)
      case (State.Acquired, Role.Client, Message.ReAcquire(_)) => Right(State.Acquiring)
      case (State.Acquired, Role.Client, Message.Release)      => Right(State.Idle)
      case (State.Querying, Role.Server, Message.Result(_))    => Right(State.Acquired)
      case _ => Left(s"illegal LocalStateQuery message/agency in $state from $sender")

  def encode(state: State, sender: Role, message: Message): Either[String, Bytes] = attempt {
    get(transition(state, sender, message))
    val w = new ProtocolWire.Writer(wire)
    def outer(n: Int, tag: Int): Unit = { w.head(4, n); w.head(0, tag) }
    def target(t: Target, specific: Int, volatile: Int, immutable: Int): Unit = t match
      case Target.Specific(p)  => outer(2, specific); w.point(p)
      case Target.VolatileTip  => outer(1, volatile)
      case Target.ImmutableTip => outer(1, immutable)
    message match
      case Message.Acquire(t) => target(t, 0, 8, 10)
      case Message.Acquired   => outer(1, 1)
      case Message.Failed(f) =>
        outer(2, 2)
        w.head(
          0,
          f match
            case Failure.PointTooOld     => 0
            case Failure.PointNotOnChain => 1
        )
      case Message.Query(q) =>
        get(ProtocolWire.validateItem(q, wire)); outer(2, 3); w.raw(q)
      case Message.Result(r) =>
        get(ProtocolWire.validateItem(r, wire)); outer(2, 4); w.raw(r)
      case Message.Release      => outer(1, 5)
      case Message.ReAcquire(t) => target(t, 6, 9, 11)
      case Message.Done         => outer(1, 7)
    w.result
  }

  /** The client's messages in `Idle` and `Acquired`, the server's in `Acquiring` and `Querying`. */
  def decodePrefix(state: State, sender: Role, bytes: Bytes): DecodeResult[Message] =
    try
      val clientTurn = state == State.Idle || state == State.Acquired
      if state == State.Done || clientTurn != (sender == Role.Client) then
        bad("wrong LocalStateQuery agency")
      val r = new ProtocolWire.Reader(bytes, wire)
      val arity = r.arrayLength()
      val tag = r.uint().value
      val message = (arity, tag.toInt) match
        case (2, 0) if tag == 0   => Message.Acquire(Target.Specific(r.point()))
        case (1, 8) if tag == 8   => Message.Acquire(Target.VolatileTip)
        case (1, 10) if tag == 10 => Message.Acquire(Target.ImmutableTip)
        case (1, 1) if tag == 1   => Message.Acquired
        case (2, 2) if tag == 2 =>
          r.uint().value.toInt match
            case 0 => Message.Failed(Failure.PointTooOld)
            case 1 => Message.Failed(Failure.PointNotOnChain)
            case _ => bad("unknown LocalStateQuery failure")
        case (2, 3) if tag == 3   => Message.Query(r.item())
        case (2, 4) if tag == 4   => Message.Result(r.item())
        case (1, 5) if tag == 5   => Message.Release
        case (2, 6) if tag == 6   => Message.ReAcquire(Target.Specific(r.point()))
        case (1, 9) if tag == 9   => Message.ReAcquire(Target.VolatileTip)
        case (1, 11) if tag == 11 => Message.ReAcquire(Target.ImmutableTip)
        case (1, 7) if tag == 7   => Message.Done
        case _                    => bad("unknown LocalStateQuery tag or arity")
      get(transition(state, sender, message))
      DecodeResult.Decoded(message, r.position)
    catch
      case ProtocolWire.Failure(_, true)      => DecodeResult.NeedMore
      case ProtocolWire.Failure(error, false) => DecodeResult.Failed(error)
