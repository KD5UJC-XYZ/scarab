// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.{DecodeResult, Role}
import LocalStateQuery.{Failure, State}
import ProtocolWire.{attempt, bad, get}

/** Generic LocalStateQuery wire and agency rules, shared by the strict Cardano facade and original
  * upstream point-domain conversations. Point values occupy one codec-owned CBOR item.
  */
object LocalStateQueryEngine:
  /** What a client sends. As for an answer, the reference has no byte or item limit on a query
    * ([[LocalStateQuery.MaxQueryBytes]]); only the nesting is bounded
    * ([[LocalStateQuery.MaxQueryDepth]]).
    */
  private val queries = ProtocolWire.Limits(
    LocalStateQuery.MaxQueryBytes,
    LocalStateQuery.MaxQueryBytes,
    32,
    LocalStateQuery.MaxQueryDepth,
    LocalStateQuery.MaxQueryBytes,
    bound = ProtocolWire.Bound.Local
  )

  /** What the server sends. The reference bounds an answer only by the mux ingress queue
    * ([[ProtocolWire.Bound.Local]]): a `GetCBOR DebugEpochState` answer of preprod, which
    * `cardano-cli query leadership-schedule --next` asks, is tens of megabytes in one byte string.
    * The nesting keeps its bound; an answer is written by our own codecs.
    */
  private val answers = ProtocolWire.Limits(
    LocalStateQuery.MaxResultBytes,
    LocalStateQuery.MaxResultBytes,
    32,
    ProtocolWire.MaxItems,
    LocalStateQuery.MaxResultBytes,
    bound = ProtocolWire.Bound.Local
  )

  private def limits(sender: Role): ProtocolWire.Limits = sender match
    case Role.Client => queries
    case Role.Server => answers

  /** What a client asks to acquire. The immutable tip exists from node-to-client version 16. */
  enum Target[+P]:
    case Specific(point: P)
    case VolatileTip
    case ImmutableTip

  enum Message[+P]:
    case Acquire(target: Target[P])
    case Acquired
    case Failed(failure: Failure)
    case Query(query: Bytes)
    case Result(result: Bytes)
    case Release
    case ReAcquire(target: Target[P])
    case Done

  def transition[P](state: State, sender: Role, message: Message[P]): Either[ProtocolError, State] =
    (state, sender, message) match
      case (State.Idle, Role.Client, Message.Acquire(_))       => Right(State.Acquiring)
      case (State.Idle, Role.Client, Message.Done)             => Right(State.Done)
      case (State.Acquiring, Role.Server, Message.Acquired)    => Right(State.Acquired)
      case (State.Acquiring, Role.Server, Message.Failed(_))   => Right(State.Idle)
      case (State.Acquired, Role.Client, Message.Query(_))     => Right(State.Querying)
      case (State.Acquired, Role.Client, Message.ReAcquire(_)) => Right(State.Acquiring)
      case (State.Acquired, Role.Client, Message.Release)      => Right(State.Idle)
      case (State.Querying, Role.Server, Message.Result(_))    => Right(State.Acquired)
      case _ => Left(ProtocolError.LocalStateQueryAgency(state, sender))

  def encode[P](
      state: State,
      sender: Role,
      message: Message[P],
      point: ChainSyncEngine.PointCodec[P]
  ): Either[ProtocolError, Bytes] = attempt {
    get(transition(state, sender, message))
    val wire = limits(sender)
    val w = new ProtocolWire.Writer(wire)
    def outer(n: Int, tag: Int): Unit = { w.head(4, n); w.head(0, tag) }
    def target(t: Target[P], specific: Int, volatile: Int, immutable: Int): Unit = t match
      case Target.Specific(p)  => outer(2, specific); point.writeTo(w, p, wire)
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
  def decodePrefix[P](
      state: State,
      sender: Role,
      bytes: Bytes,
      point: ChainSyncEngine.PointCodec[P]
  ): DecodeResult[Message[P]] =
    ProtocolWire.prefix {
      val clientTurn = state == State.Idle || state == State.Acquired
      if state == State.Done || clientTurn != (sender == Role.Client) then
        bad(ProtocolError.WrongLocalStateQueryAgency)
      val wire = limits(sender)
      val r = new ProtocolWire.Reader(bytes, wire)
      val arity = r.arrayLength()
      val tag = r.uint().value
      val message = (arity, tag.toInt) match
        case (2, 0) if tag == 0   => Message.Acquire(Target.Specific(point.readFrom(r)))
        case (1, 8) if tag == 8   => Message.Acquire(Target.VolatileTip)
        case (1, 10) if tag == 10 => Message.Acquire(Target.ImmutableTip)
        case (1, 1) if tag == 1   => Message.Acquired
        case (2, 2) if tag == 2 =>
          r.uint().value.toInt match
            case 0 => Message.Failed(Failure.PointTooOld)
            case 1 => Message.Failed(Failure.PointNotOnChain)
            case _ => bad(ProtocolError.UnknownLocalStateQueryFailure)
        case (2, 3) if tag == 3   => Message.Query(r.item())
        case (2, 4) if tag == 4   => Message.Result(r.item())
        case (1, 5) if tag == 5   => Message.Release
        case (2, 6) if tag == 6   => Message.ReAcquire(Target.Specific(point.readFrom(r)))
        case (1, 9) if tag == 9   => Message.ReAcquire(Target.VolatileTip)
        case (1, 11) if tag == 11 => Message.ReAcquire(Target.ImmutableTip)
        case (1, 7) if tag == 7   => Message.Done
        case _                    => bad(ProtocolError.UnknownLocalStateQueryTagOrArity)
      get(transition(state, sender, message))
      DecodeResult.Decoded(message, r.position)
    }
