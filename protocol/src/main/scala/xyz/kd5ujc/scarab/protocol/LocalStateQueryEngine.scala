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
      point: ChainSyncEngine.PointCodec[P],
      queries: LocalStateQuery.QueryReader = LocalStateQuery.QueryReader.items
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
        // a query that `queries` reads as one item spanning several of a tree is written whole
        queries.spanOf(q) match
          case LocalStateQuery.QuerySpan.Prefix(size) if size == q.size =>
            get(ProtocolWire.validateItems(q, wire))
          case LocalStateQuery.QuerySpan.Refused => bad(ProtocolError.UndecodableQuery)
          case _                                 => get(ProtocolWire.validateItem(q, wire))
        outer(2, 3); w.raw(q)
      case Message.Result(r) =>
        get(ProtocolWire.validateItem(r, wire)); outer(2, 4); w.raw(r)
      case Message.Release      => outer(1, 5)
      case Message.ReAcquire(t) => target(t, 6, 9, 11)
      case Message.Done         => outer(1, 7)
    w.result
  }

  /** The client's messages in `Idle` and `Acquired`, the server's in `Acquiring` and `Querying`.
    * Each message is read as before; while the query of a client's `Query` has not all arrived,
    * `queries` says whether its decoder has already failed on what has
    * ([[LocalStateQuery.QueryReader.prefix]]).
    */
  def decodePrefix[P](
      state: State,
      sender: Role,
      bytes: Bytes,
      point: ChainSyncEngine.PointCodec[P],
      queries: LocalStateQuery.QueryReader
  ): DecodeResult[Message[P]] = incremental(state, sender, point, queries).decodePrefix(bytes)

  /** [[decodePrefix]], resumed on each longer prefix of a message: the reader goes on with the item
    * it stopped in, and `queries` with its reading of the query.
    */
  def incremental[P](
      state: State,
      sender: Role,
      point: ChainSyncEngine.PointCodec[P],
      queries: LocalStateQuery.QueryReader
  ): IncrementalDecoder[Message[P]] = new IncrementalDecoder[Message[P]]:
    type Cursor = (queries.Reading, ProtocolWire.Progress)
    val initial: Cursor = (queries.unread, ProtocolWire.Progress.none)
    def resume(cursor: Cursor, input: Bytes): (Cursor, DecodeResult[Message[P]]) =
      decoded(state, sender, input, point, queries)(cursor._1, cursor._2) match
        case ProtocolWire.Attempt.Done(result) => (initial, result)
        case ProtocolWire.Attempt.Short(kept, progress) =>
          waiting(state, sender, input, queries)(kept.getOrElse(cursor._1)) match
            case (reading, DecodeResult.NeedMore) => ((reading, progress), DecodeResult.NeedMore)
            case (_, verdict)                     => (initial, verdict)

  /** An incomplete message: a `Query` whose query `queries` refuses ends here, anything else waits.
    */
  private def waiting[P](
      state: State,
      sender: Role,
      bytes: Bytes,
      queries: LocalStateQuery.QueryReader
  )(reading: queries.Reading): (queries.Reading, DecodeResult[Message[P]]) =
    ProtocolWire.attempt {
      val r = ProtocolWire.Reader(bytes, limits(sender))
      val arity = r.arrayLength()
      val tag = r.uint().value
      (state, sender, arity, tag.toInt) match
        case (State.Acquired, Role.Client, 2, 3) if tag == 3 =>
          queries.prefix(reading, bytes.drop(r.position)) match
            case (next, LocalStateQuery.QueryPrefix.Short) => (next, DecodeResult.NeedMore)
            case (next, LocalStateQuery.QueryPrefix.Refused(cause)) =>
              (next, DecodeResult.Failed(ProtocolError.UndecodableQueryPrefix(cause)))
        case _ => (reading, DecodeResult.NeedMore)
    } match
      case Right(judged)                   => judged
      case Left(error) if error.incomplete => (reading, DecodeResult.NeedMore)
      case Left(error)                     => (reading, DecodeResult.Failed(error))

  private def decoded[P](
      state: State,
      sender: Role,
      bytes: Bytes,
      point: ChainSyncEngine.PointCodec[P],
      queries: LocalStateQuery.QueryReader
  )(
      reading: queries.Reading,
      from: ProtocolWire.Progress
  ): ProtocolWire.Attempt[queries.Reading, Message[P]] =
    ProtocolWire
      .attempt {
        val clientTurn = state == State.Idle || state == State.Acquired
        if state == State.Done || clientTurn != (sender == Role.Client) then
          bad(ProtocolError.WrongLocalStateQueryAgency)
        ProtocolWire.Reader(bytes, limits(sender), from)
      }
      .fold(
        error =>
          ProtocolWire.Attempt.Done(
            if error.incomplete then DecodeResult.NeedMore else DecodeResult.Failed(error)
          ),
        r =>
          ProtocolWire.resumed[queries.Reading, Message[P]](r) {
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
              case (2, 3) if tag == 3 =>
                val (read, span) = queries.span(reading, bytes.drop(r.position))
                def held(query: ProtocolWire.Scope ?=> Bytes): Bytes =
                  ProtocolWire.short(query).getOrElse(ProtocolWire.keep(read, r))
                Message.Query(span match
                  case LocalStateQuery.QuerySpan.Item         => held(r.item())
                  case LocalStateQuery.QuerySpan.Prefix(size) => held(r.spanning(size))
                  case LocalStateQuery.QuerySpan.Short        =>
                    // what has arrived still meets the reader's checks, its depth among them; it is
                    // read, not copied, as the query has not all arrived
                    ProtocolWire.short(r.span(bytes.size - r.position))
                    ProtocolWire.keep(read, r)
                  case LocalStateQuery.QuerySpan.Refused => bad(ProtocolError.UndecodableQuery))
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
      )
