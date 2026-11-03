// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.{DecodeResult, Point, Role}

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

  /** The most a client message (`Acquire`, `Query`, ...) may take: the reference reads it with the
    * same codec as an answer, with no limit and bounded only by the mux ingress queue
    * (`codecLocalStateQuery` is `Stateful.mkCodecCborLazyBS`,
    * ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/LocalStateQuery/Codec.hs:41-59;
    * `maximumMiniProtocolLimits`, cardano-diffusion/lib/Cardano/Network/NodeToClient.hs:192), so
    * here the largest byte array.
    */
  val MaxQueryBytes: Int = Int.MaxValue

  /** The deepest nesting of a client message. The reference has no bound; ours keeps the effective
    * depth the former 65,535-item bound gave a chain of single-item wrappers (two items each),
    * which `DeepQuerySuite` exercises. The walkers of a query are stack safe, so this bounds only
    * the heap one query takes. Deviation approved by the owner on 2026-10-25: the bound stays;
    * trade-off: it guards the JVM against stack overflow, where Haskell would not overflow, on a
    * local socket only, and real queries nest far less deeply.
    */
  val MaxQueryDepth = 32767

  /** The most a server message (`Result`, ...) may take: the reference reads it with no limit
    * (`maximumMiniProtocolLimits`), so here the largest byte array.
    */
  val MaxResultBytes: Int = Int.MaxValue
  enum State:
    case Idle, Acquiring, Acquired, Querying, Done

  /** Where a query of `MsgQuery` ends. The reference's codec reads the query with the block type's
    * query decoder from the stream (`codecLocalStateQuery` takes `decodeQuery`,
    * `ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/LocalStateQuery/Codec.hs`), so a
    * query holding a `RecD` record written as an empty indefinite list `9f ff` spans the record's
    * fields after it, which a tree of items does not (#700). The ledger that knows the queries'
    * structure says where; the codec still reads the bytes as whole items.
    */
  trait QueryReader:
    /** How the query at the start of `input` reads; `input` may hold less than it or more. */
    def span(input: Bytes): QuerySpan

  object QueryReader:
    /** Each query one item of a tree: the queries of a profile that holds no `RecD` record. */
    val items: QueryReader = _ => QuerySpan.Item

  enum QuerySpan:
    /** One item, as a tree of items reads it. */
    case Item

    /** The first `size` bytes: one item as the query's decoder reads it, items one after another as
      * a tree reads them.
      */
    case Prefix(size: Int)

    /** The input ends before the query does. */
    case Short

    /** The query's decoder fails on it, whatever follows. */
    case Refused

  type Target = LocalStateQueryEngine.Target[Point]
  val Target: LocalStateQueryEngine.Target.type = LocalStateQueryEngine.Target

  enum Failure:
    /** The point is older than the immutable tip (tag 0). */
    case PointTooOld

    /** The point is not on the node's chain (tag 1). */
    case PointNotOnChain

  type Message = LocalStateQueryEngine.Message[Point]
  val Message: LocalStateQueryEngine.Message.type = LocalStateQueryEngine.Message

  def transition(state: State, sender: Role, message: Message): Either[ProtocolError, State] =
    LocalStateQueryEngine.transition(state, sender, message)

  def encode(
      state: State,
      sender: Role,
      message: Message,
      queries: QueryReader = QueryReader.items
  ): Either[ProtocolError, Bytes] =
    LocalStateQueryEngine.encode(state, sender, message, ChainSync.enginePointCodec, queries)

  /** Strict Cardano point validation retains the original bounded reader and error ordering. */
  def decodePrefix(
      state: State,
      sender: Role,
      bytes: Bytes,
      queries: QueryReader = QueryReader.items
  ): DecodeResult[Message] =
    LocalStateQueryEngine.decodePrefix(state, sender, bytes, ChainSync.enginePointCodec, queries)
