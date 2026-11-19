// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import scala.util.Random
import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.{DecodeResult, Point, Role, Tip, UInt64}

/** Each mini-protocol decoder against its decoder at the base of the change that made it resume
  * ([[BaseDecoders]], test-only copies of 3cad1f045): on every message of a corpus, copies with
  * bytes flipped and copies cut short or run on, the new decoder answers on each prefix as the
  * base's did, started afresh on that prefix and resumed over the cuts of the message alike: the
  * same message and bytes consumed, the same wait, and the same refusal with its typed error at the
  * same byte.
  */
class BaseDifferentialSuite extends munit.FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)
  private def right[A](value: Either[?, A]): A = value.fold(e => fail(e.toString), identity)

  /** `messages` and copies of each with bytes flipped, with a byte or a break appended, and cut. */
  private def variants(messages: Vector[Bytes], random: Random): Vector[Bytes] =
    messages
      .flatMap { m =>
        val flipped = (1 to 4).map(k => Paced.flipped(m, k, random))
        Vector(m, m ++ hex("00"), m ++ hex("ff"), m.take(m.size / 2)) ++ flipped
      }
      .filter(_.size > 0)

  /** Every prefix of a short input, random ones and the last 64 of a long one. */
  private def cutsOf(input: Bytes, random: Random): Vector[Int] =
    if input.size <= 1500 then (0 to input.size).toVector
    else
      (Vector.fill(300)(
        random.nextInt(input.size + 1)
      ) ++ ((input.size - 64) to input.size)).distinct.sorted

  /** The differences between `fresh` and `base` on every prefix of every input, the new decoder
    * started afresh on each, and resumed over the cuts until its first verdict.
    */
  private def differences[A](
      name: String,
      inputs: Vector[Bytes],
      fresh: IncrementalDecoder[A],
      base: Bytes => DecodeResult[A],
      seed: Long
  ): Vector[String] =
    val random = new Random(seed)
    variants(inputs, random).zipWithIndex.flatMap { (input, i) =>
      val cuts = cutsOf(input, random)
      val expected = cuts.map(at => base(input.take(at)))
      val afresh = cuts.map(at => fresh.decodePrefix(input.take(at)))
      val untilVerdict = expected.indexWhere(_ != DecodeResult.NeedMore) match
        case -1 => expected
        case at => expected.take(at + 1)
      val resumed = Paced.resumed(fresh, input, cuts)
      val firstAfresh = cuts.indices.find(k => afresh(k) != expected(k))
      expected.foreach(verdict =>
        seen.update(
          name.takeWhile(_ != ' '),
          seen.getOrElse(name.takeWhile(_ != ' '), Set.empty) + kind(verdict)
        )
      )
      Vector(
        firstAfresh.map(k =>
          s"$name input $i (${input.size} B) prefix ${cuts(k)}: afresh ${afresh(k)}, base ${expected(k)}"
        ),
        Option.when(resumed != untilVerdict)(
          s"$name input $i (${input.size} B): resumed ${resumed.lastOption}, base ${untilVerdict.lastOption}"
        )
      ).flatten
    }

  /** The kinds of verdict the base gave on each codec's corpus: each must have accepted some prefix
    * and refused some, or the comparison would show little.
    */
  private val seen = scala.collection.mutable.Map.empty[String, Set[String]]
  private def kind(verdict: DecodeResult[?]): String = verdict match
    case DecodeResult.NeedMore      => "need-more"
    case DecodeResult.Failed(_)     => "refused"
    case DecodeResult.Decoded(_, _) => "decoded"

  override def afterAll(): Unit =
    assertEquals(
      seen.toMap.filter((_, kinds) => kinds != Set("need-more", "refused", "decoded")),
      Map.empty[String, Set[String]]
    )

  private val reader = new TxSubmission2.TransactionReader:
    def body(original: Bytes): Either[ProtocolError, Bytes] = Right(original)
    // a transaction whose first byte is 0xee is one the era's decoders refuse
    def read(original: Bytes): Either[ProtocolError, Bytes] =
      if original.headOption.contains(0xee.toByte) then Left(ProtocolError.ExpectedConwayGenTx)
      else Right(original)

  test("TxSubmission2: every message of every state, as the base read it") {
    import TxSubmission2Engine.{Message, Offer, State}
    import TxSubmission2.{EraTx, EraTxId}
    val codec = TxSubmission2.eraCodec(reader)
    def id(n: Int) = EraTxId(Era.Conway, Bytes(Vector.fill(32)(n.toByte)))
    def tx(n: Int, size: Int) = EraTx(Era.Conway, Bytes(Vector.fill(size)(n.toByte)))
    def encoded(state: State, sender: Role, message: Message[EraTxId, EraTx]) =
      (state, sender, right(codec.encode(state, sender, message)))
    val corpus = Vector(
      encoded(State.Init, Role.Client, Message.Init),
      encoded(State.Idle, Role.Server, Message.RequestIds(true, 0, 3)),
      encoded(State.Idle, Role.Server, Message.RequestIds(false, 2, 8)),
      encoded(
        State.Ids(false),
        Role.Client,
        Message.ReplyIds((1 to 8).map(n => Offer(id(n), 300L)).toVector)
      ),
      encoded(State.Ids(true), Role.Client, Message.Done),
      encoded(State.Idle, Role.Server, Message.RequestTxs((1 to 8).map(id).toVector)),
      encoded(State.Txs, Role.Client, Message.ReplyTxs(Vector(tx(1, 30), tx(2, 300), tx(3, 900)))),
      encoded(State.Txs, Role.Client, Message.ReplyTxs((1 to 8).map(n => tx(n, 200)).toVector)),
      encoded(State.Txs, Role.Client, Message.ReplyTxs(Vector(tx(1, 40), tx(0xee, 60), tx(3, 40)))),
      encoded(State.Txs, Role.Client, Message.ReplyTxs(Vector.empty))
    ) :+ (State.Txs, Role.Client, hex("82039f" + "8206d81843010203" * 9 + "ff"))
    val found = corpus.zipWithIndex.flatMap { case ((state, _, bytes), i) =>
      Vector(State.Init, State.Idle, State.Txs, State.Ids(true), state).distinct.flatMap { at =>
        Vector(Role.Client, Role.Server).flatMap(who =>
          differences(
            s"txs $i $at $who",
            Vector(bytes),
            codec.incremental(at, who),
            BaseDecoders.txSubmission2(at, who, _, reader),
            i.toLong
          )
        )
      }
    }
    assertEquals(found, Vector.empty)
  }

  private def chainSyncIncremental(state: ChainSync.State, sender: Role) =
    ChainSyncEngine.incremental(
      state,
      sender,
      ChainSync.enginePointCodec,
      ChainSync.engineTipCodec,
      ChainSync.enginePayloadCodec(ChainSyncFixtures.rawItem),
      ChainSync.Limits()
    )

  test("ChainSync: every message of every state, as the base read it") {
    import ChainSyncEngine.Message
    def point(n: Int) = Point.Block(right(UInt64.from(BigInt(n))), Bytes(Vector.fill(32)(n.toByte)))
    def tip(n: Int) = Tip(point(n), right(UInt64.from(BigInt(n))))
    def encoded(state: ChainSync.State, sender: Role, message: Message[Point, Tip, Bytes]) =
      right(
        ChainSyncEngine.encode(
          state,
          sender,
          message,
          ChainSync.enginePointCodec,
          ChainSync.engineTipCodec,
          ChainSync.enginePayloadCodec(ChainSyncFixtures.rawItem)
        )
      )
    val header = hex("8a" + "01" * 9 + "5820" + "cc" * 32)
    val corpus = Vector(
      encoded(ChainSync.State.Idle, Role.Client, Message.RequestNext),
      encoded(
        ChainSync.State.Idle,
        Role.Client,
        Message.FindIntersect((1 to 64).map(point).toVector)
      ),
      encoded(ChainSync.State.Idle, Role.Client, Message.FindIntersect(Vector(Point.Origin))),
      encoded(ChainSync.State.Idle, Role.Client, Message.Done),
      encoded(ChainSync.State.NextCanAwait, Role.Server, Message.AwaitReply),
      encoded(ChainSync.State.NextCanAwait, Role.Server, Message.RollForward(header, tip(5))),
      encoded(ChainSync.State.NextCanAwait, Role.Server, Message.RollBackward(point(3), tip(5))),
      encoded(ChainSync.State.Intersect, Role.Server, Message.IntersectFound(point(2), tip(9))),
      encoded(ChainSync.State.Intersect, Role.Server, Message.IntersectNotFound(tip(9))),
      hex("83029f" + "01" * 3000 + "ff") ++ right(ChainSync.encodeTip(tip(1))),
      hex("8302" + "81" * 30 + "00") ++ right(ChainSync.encodeTip(tip(1))),
      hex("83025f" + "41aa" * 900 + "ff") ++ right(ChainSync.encodeTip(tip(1))),
      hex("82049f") ++ Vector
        .fill(65)(right(ChainSync.encodePoint(point(1))))
        .reduce(_ ++ _) ++ hex("ff")
    )
    val states = Vector(
      ChainSync.State.Idle -> Role.Client,
      ChainSync.State.NextCanAwait -> Role.Server,
      ChainSync.State.NextMustReply -> Role.Server,
      ChainSync.State.Intersect -> Role.Server
    )
    val found = states.flatMap((state, sender) =>
      differences(
        s"chainsync $state",
        corpus,
        chainSyncIncremental(state, sender),
        BaseDecoders.chainSync(state, sender, _, ChainSyncFixtures.rawItem),
        state.ordinal.toLong
      )
    )
    assertEquals(found, Vector.empty)
  }

  test("KeepAlive and BlockFetch: every message of every state, as the base read them") {
    import BlockFetchEngine.{Message, State}
    val keepAlive = Vector(hex("8200190102"), hex("8102"), hex("8201190304"), hex("82001a00010000"))
    val keepAliveFound = Vector(
      KeepAlive.State.Client -> KeepAlive.Role.Client,
      KeepAlive.State.Server -> KeepAlive.Role.Server
    ).flatMap((state, sender) =>
      differences(
        s"keepalive $state",
        keepAlive,
        KeepAlive.incremental(state, sender),
        BaseDecoders.keepAlive(state, sender, _),
        11L
      )
    )
    val point = Point.Block(right(UInt64.from(BigInt(7))), Bytes(Vector.fill(32)(7.toByte)))
    val blocks = WireCorpus.preprod.take(4).map(b => right(CardanoBlockFetch.RawNtNBlock.from(b)))
    def encoded(
        state: State,
        sender: BlockFetchEngine.Role,
        m: Message[Point, CardanoBlockFetch.RawNtNBlock]
    ) =
      right(
        BlockFetchEngine.encode(
          state,
          sender,
          m,
          CardanoBlockFetch.pointCodec,
          CardanoBlockFetch.payloadCodec()
        )
      )
    val fetch = Vector(
      encoded(State.Idle, BlockFetchEngine.Role.Client, Message.RequestRange(point, point)),
      encoded(State.Idle, BlockFetchEngine.Role.Client, Message.ClientDone),
      encoded(State.Busy, BlockFetchEngine.Role.Server, Message.StartBatch),
      encoded(State.Busy, BlockFetchEngine.Role.Server, Message.NoBlocks),
      encoded(State.Streaming, BlockFetchEngine.Role.Server, Message.BatchDone)
    ) ++ blocks.map(b => encoded(State.Streaming, BlockFetchEngine.Role.Server, Message.Block(b)))
    val fetchFound = Vector(
      State.Idle -> BlockFetchEngine.Role.Client,
      State.Busy -> BlockFetchEngine.Role.Server,
      State.Streaming -> BlockFetchEngine.Role.Server
    ).flatMap((state, sender) =>
      differences(
        s"blockfetch $state",
        fetch,
        BlockFetchEngine.incremental(
          state,
          sender,
          CardanoBlockFetch.pointCodec,
          CardanoBlockFetch.payloadCodec(),
          BlockFetchEngine.Limits()
        ),
        BaseDecoders.blockFetch(state, sender, _),
        12L
      )
    )
    assertEquals(keepAliveFound ++ fetchFound, Vector.empty)
  }

  test("LocalTxMonitor: every message of every state, as the base read it") {
    import LocalTxMonitor.{Kind, Measure, Measures, Message, Sizes, State}
    import scala.collection.immutable.SortedMap
    def encoded(state: State, sender: Role, m: Message) =
      right(LocalTxMonitor.encode(state, sender, m))
    val big = BigInt(2).pow(80)
    val corpus = Vector(
      encoded(State.Idle, Role.Client, Message.Acquire),
      encoded(State.Acquired, Role.Client, Message.HasTx(hex("8206" + "5820" + "11" * 32))),
      encoded(State.Acquired, Role.Client, Message.NextTx),
      encoded(State.Acquiring, Role.Server, Message.Acquired(BigInt(77))),
      encoded(
        State.Busy(Kind.NextTx),
        Role.Server,
        Message.ReplyNextTx(Some(hex("8206d8184401020304")))
      ),
      encoded(State.Busy(Kind.HasTx), Role.Server, Message.ReplyHasTx(true)),
      encoded(State.Busy(Kind.GetSizes), Role.Server, Message.ReplyGetSizes(Sizes(1, 2, 3))),
      encoded(
        State.Busy(Kind.GetMeasures),
        Role.Server,
        Message.ReplyGetMeasures(
          Measures(
            4,
            SortedMap(
              "a" * 40 -> Measure(big, big + 1),
              "transaction_bytes" -> Measure(BigInt(5), BigInt(9)),
              "z" -> Measure(BigInt(2).pow(300), BigInt(1))
            )
          )
        )
      ),
      hex("830c04a2616101820102616101820304"),
      hex("82079f" + "01" * 2000 + "ff")
    )
    val states = Vector(
      State.Idle -> Role.Client,
      State.Acquired -> Role.Client,
      State.Acquiring -> Role.Server,
      State.Busy(Kind.NextTx) -> Role.Server,
      State.Busy(Kind.HasTx) -> Role.Server,
      State.Busy(Kind.GetSizes) -> Role.Server,
      State.Busy(Kind.GetMeasures) -> Role.Server
    )
    val found = states.flatMap((state, sender) =>
      differences(
        s"localtxmonitor $state",
        corpus,
        LocalTxMonitor.incremental(state, sender),
        BaseDecoders.localTxMonitor(state, sender, _),
        13L
      )
    )
    assertEquals(found, Vector.empty)
  }

  test("LocalStateQuery with a reader of whole items: every message, as the base read it") {
    import LocalStateQuery.{Failure, Message, QueryPrefix, QueryReader, QuerySpan, State, Target}
    def encoded(state: State, sender: Role, m: Message) =
      right(LocalStateQuery.encode(state, sender, m))
    val point = Point.Block(right(UInt64.from(BigInt(9))), Bytes(Vector.fill(32)(9.toByte)))
    val corpus = Vector(
      encoded(State.Idle, Role.Client, Message.Acquire(Target.Specific(point))),
      encoded(State.Idle, Role.Client, Message.Acquire(Target.VolatileTip)),
      encoded(State.Acquired, Role.Client, Message.Query(hex("820082008206820601"))),
      encoded(State.Acquired, Role.Client, Message.ReAcquire(Target.ImmutableTip)),
      encoded(State.Acquired, Role.Client, Message.Release),
      encoded(State.Acquiring, Role.Server, Message.Acquired),
      encoded(State.Acquiring, Role.Server, Message.Failed(Failure.PointNotOnChain)),
      encoded(State.Querying, Role.Server, Message.Result(hex("9f" + "01" * 1000 + "ff"))),
      hex("82039f" + "01" * 3000 + "ff"),
      hex("8203" + "81" * 200 + "00")
    )
    val states = Vector(
      State.Idle -> Role.Client,
      State.Acquired -> Role.Client,
      State.Acquiring -> Role.Server,
      State.Querying -> Role.Server
    )
    val found = states.flatMap((state, sender) =>
      differences(
        s"localstatequery $state",
        corpus,
        LocalStateQueryEngine
          .incremental(state, sender, ChainSync.enginePointCodec, QueryReader.items),
        BaseDecoders.localStateQuery(state, sender, _, _ => QuerySpan.Item, _ => QueryPrefix.Short),
        14L
      )
    )
    assertEquals(found, Vector.empty)
  }

  test("PeerSharing: every message of every state, as the base read it") {
    import PeerSharing.{Role as PeerRole, State}
    def facade(m: PeerSharingEngine.Message[PeerSharing.Address]): PeerSharing.Message = m match
      case PeerSharingEngine.Message.ShareRequest(amount) =>
        PeerSharing.Message.ShareRequest(amount)
      case PeerSharingEngine.Message.SharePeers(peers) => PeerSharing.Message.SharePeers(peers)
      case PeerSharingEngine.Message.Done              => PeerSharing.Message.Done
    val corpus = Vector(
      hex("82000a"),
      hex("8200190100"),
      hex("8102"),
      hex("82019f" + "83000a01190bb9" * 40 + "ff"),
      hex("820180"),
      hex("82019f" + "83000000" * 300 + "ff"),
      hex("8201990064" + "83000000" * 100),
      hex("82019f" + "860100000000" * 50 + "ff"),
      hex("82019b8000000000000000")
    )
    val found = Vector(State.Idle -> PeerRole.Client, State.Busy -> PeerRole.Server).flatMap(
      (state, sender) =>
        differences(
          s"peersharing $state",
          corpus,
          PeerSharing.incremental(state, sender),
          bytes =>
            BaseDecoders.peerSharing(state, sender, bytes) match
              case PeerSharing.Decoding.NeedMore => DecodeResult.NeedMore
              case PeerSharing.Decoding.Failed(error) =>
                DecodeResult.Failed(ProtocolError.PeerSharing(error))
              case PeerSharing.Decoding.Decoded(value, used) =>
                DecodeResult.Decoded(facade(value), used),
          15L
        )
    )
    assertEquals(found, Vector.empty)
  }

  test("Handshake: proposals, replies and refusals, as the base read them") {
    import Handshake.{State, Suite}
    val data = Handshake.Data(764824073L)
    val (_, proposal) = right(
      Handshake.clientStart(Suite.NodeToNode, Handshake.nodeToNodeOffers(NtnVersion.Latest, data))
    )
    val proposed = right(Handshake.encode(Suite.NodeToNode, proposal))
    val accepted = right(
      Handshake.responder(
        Suite.NodeToNode,
        Handshake.nodeToNodeOffers(NtnVersion.Latest, data),
        proposal
      )
    )._1
    val refused = right(
      Handshake.responder(
        Suite.NodeToNode,
        Handshake.nodeToNodeOffers(NtnVersion.Latest, Handshake.Data(1L)),
        proposal
      )
    )._1
    val corpus = Vector(
      proposed,
      right(Handshake.encode(Suite.NodeToNode, accepted)),
      right(Handshake.encode(Suite.NodeToNode, refused)),
      hex("8200a2019f" + "01" * 600 + "ff0e8401f400f4"),
      hex("8200a2017f" + "60" * 600 + "ff0e8401f400f4"),
      hex("8200a201" + "81" * 500 + "000e8401f400f4"),
      hex("8200a201" + "c1" * 500 + "000e8401f400f4"),
      hex("82028300" + "9f" + "0e" * 400 + "ff00"),
      hex("8202830f0e6461626364"),
      hex("8201" + "0e" + "8401f400f4"),
      hex("8200a2019f" + "01" * 6000 + "ff0e8401f400f4")
    )
    val found = Vector(State.Propose, State.Confirm).flatMap { state =>
      differences[Handshake.WireMessage[NtnVersion]](
        s"handshake $state",
        corpus,
        Handshake.incremental[NtnVersion](state),
        bytes =>
          BaseHandshake.decodePrefix[NtnVersion](state, bytes) match
            case Right((message, rest))  => DecodeResult.Decoded(message, bytes.size - rest.size)
            case Left(e) if e.incomplete => DecodeResult.NeedMore
            case Left(e)                 => DecodeResult.Failed(e.cause),
        16L
      ) ++ corpus
        .filter(_.size <= 1500)
        .flatMap(m =>
          (0 to m.size).flatMap(at =>
            val prefix = m.take(at)
            Vector(
              Option.when(
                Handshake.decodePrefix[NtnVersion](state, prefix) !=
                  BaseHandshake.decodePrefix[NtnVersion](state, prefix)
              )(s"handshake decodePrefix $state ${m.size} B at $at"),
              Option.when(
                Handshake.decodeReason[NtnVersion](prefix) !=
                  BaseHandshake.decodeReason[NtnVersion](prefix)
              )(s"handshake decodeReason ${m.size} B at $at")
            ).flatten
          )
        )
    }
    assertEquals(found, Vector.empty)
  }
