// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.fuzz

import cats.syntax.all.*
import fs2.Chunk
import java.nio.file.Files
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.protocol.*
import xyz.kd5ujc.scarab.protocol.ChainSync.{DecodeResult, Point, Tip, UInt64}
import xyz.kd5ujc.scarab.protocol.CardanoBlockFetch.RawNtNBlock
import xyz.kd5ujc.scarab.ledger.{Block, Header}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.TestLedger
import xyz.kd5ujc.scarab.ledger.byron.{ByronAddress, ByronBody}
import xyz.kd5ujc.scarab.node.mempool.{LocalMonitor, LocalSubmission, MempoolState}
import xyz.kd5ujc.scarab.node.ntc.NtcQuery
import xyz.kd5ujc.scarab.plutus.PlutusData

/** One decoder entry point under test: the seeds its inputs are mutated from, and the property. */
final case class Target(name: String, corpus: () => Vector[Array[Byte]], check: Input => Unit)

/** Registered decoder targets for peer, client, block and persisted inputs. Each probe checks
  * bounded time and allocation without unexpected exceptions; targets with writers also check
  * round-trip values or canonical encoding, according to their wire contract.
  */
object Targets:
  private def raw(i: Input): Bytes = Bytes.fromArray(i.bytes)
  private def req(ok: Boolean, what: => String): Unit = if !ok then throw new Violation(what)

  /** Inputs each target accepted (decoded to a value): the seeds must be valid, or the fuzzing is
    * shallow.
    */
  val accepted =
    new java.util.concurrent.ConcurrentHashMap[String, java.util.concurrent.atomic.LongAdder]()
  private def hit(name: String): Unit =
    accepted.computeIfAbsent(name, _ => new java.util.concurrent.atomic.LongAdder).increment()
  private def seen(name: String, ok: Boolean): Unit = if ok then hit(name)

  private def probe(name: String)(body: Bytes => Unit): Input => Unit = in =>
    Probe.run(name, in)(body(raw(in)))

  // --- core: CBOR ----------------------------------------------------------------------------------

  private val cborSeeds: () => Vector[Array[Byte]] = () =>
    Corpus.tricky ++ Corpus.blocks ++ Corpus.headerEnvelopes.take(10) ++ Corpus.networkFixtures ++
      Corpus.sampled("conway:block", 10) ++ Corpus.sampled("conway:plutus_data", 30)

  private def cbor(name: String, limits: Cbor.Limits): Target =
    Target(
      name,
      cborSeeds,
      probe(name) { b =>
        Cbor.decode(b, limits).foreach { node =>
          hit(name)
          req(node.original.size <= b.size, "node original is longer than the input")
          Cbor.encode(node.value) match
            case Left(e) => throw new Violation(s"a decoded value does not re-encode: $e")
            case Right(encoded) =>
              val again = Cbor.decode(encoded) match
                case Right(n) => n
                case Left(e)  => throw new Violation(s"the re-encoding does not decode: $e")
              req(again.original == encoded, "re-encoding is not decoded as itself")
              req(Cbor.encode(again.value) == Right(encoded), "encoding is not idempotent")
        }
      }
    )

  // --- network -------------------------------------------------------------------------------------

  private def roundTrip[S, R, M, E](
      name: String,
      states: Seq[S],
      roles: Seq[R],
      corpus: () => Vector[Array[Byte]],
      decode: (S, R, Bytes) => DecodeResult[M],
      encode: (S, R, M) => Either[E, Bytes]
  ): Target =
    Target(
      name,
      corpus,
      probe(name) { b =>
        for s <- states; r <- roles do
          decode(s, r, b) match
            case DecodeResult.Decoded(message, used) =>
              hit(name)
              req(used > 0 && used <= b.size, s"consumed $used of ${b.size} bytes")
              val prefix = b.take(used)
              req(
                decode(s, r, prefix) == DecodeResult.Decoded(message, used),
                s"prefix of $used bytes differs"
              )
              req(
                !decode(s, r, b.take(used - 1)).isInstanceOf[DecodeResult.Decoded[?]],
                "a shorter prefix decodes too"
              )
              encode(s, r, message) match
                case Left(e) => throw new Violation(s"$s/$r: decoded $message does not encode: $e")
                case Right(enc) =>
                  req(
                    decode(s, r, enc) == DecodeResult.Decoded(message, enc.size),
                    s"$s/$r: $message re-decodes differently"
                  )
            case _ => ()
      }
    )

  private def netCorpus(rules: String*): () => Vector[Array[Byte]] = () =>
    Corpus.tricky ++ Corpus.networkFixtures ++ rules.toVector.flatMap(Corpus.sampled(_, 80))

  private val chainSyncSeeds: () => Vector[Array[Byte]] = () =>
    val tip = Tip(
      Point.Block(UInt64.saturating(100), Bytes.fromArray(Array.fill(32)(7.toByte))),
      UInt64.saturating(5)
    )
    val state = ChainSync.State.values.head
    val forward = Corpus.headerEnvelopes.take(10).flatMap { env =>
      ChainSync
        .encode(
          ChainSync.State.NextCanAwait,
          ChainSync.Role.Server,
          ChainSync.Message
            .RollForward(NtNHeader.codec.decode(Bytes.fromArray(env)).toOption.get, tip),
          NtNHeader.codec
        )
        .toOption
        .map(_.toArray)
    }
    require(state != null)
    forward ++ netCorpus("chain-sync:chainSyncMessage")()

  private val blockFetchSeeds: () => Vector[Array[Byte]] = () =>
    val blocks = Corpus.blocks.take(12).flatMap { b =>
      BlockFetch
        .encode(
          BlockFetch.State.Streaming,
          BlockFetch.Role.Server,
          BlockFetch.Message.Block(RawNtNBlock.from(Bytes.fromArray(b)).toOption.get),
          CardanoBlockFetch.payloadCodec()
        )
        .toOption
        .map(_.toArray)
    }
    blocks ++ netCorpus("block-fetch:blockFetchMessage")()

  private val peerSharingSeeds: () => Vector[Array[Byte]] = () =>
    def word(i: Int) = PeerSharing.Word32.ofBits(i * 0x01010101)
    def port(i: Int) =
      PeerSharing.Port.from(i * 257 % 65536).fold(throw new IllegalStateException("port"))(identity)
    val addresses = Vector(
      PeerSharing.Address.V4(word(7), port(1)),
      PeerSharing.Address.V4(word(-1), port(255)),
      PeerSharing.Address.V6(word(0), word(0), word(0), word(1), port(11)),
      PeerSharing.Address.V6(word(-3), word(5), word(255), word(65535), port(0))
    )
    val replies = (0 to addresses.size).flatMap(n =>
      PeerSharing
        .encode(
          PeerSharing.State.Busy,
          PeerSharing.Role.Server,
          PeerSharing.Message.SharePeers(addresses.take(n))
        )
        .toOption
        .map(_.toArray)
    )
    replies.toVector ++ netCorpus("peer-sharing-v14:peerSharingMessage")()

  /** The Leios codecs return their own closed error; the round-trip probe reads the shared one. */
  private def leiosResult[M](r: Either[LeiosWireError, (M, Int)]): DecodeResult[M] = r match
    case Right((message, used))          => DecodeResult.Decoded(message, used)
    case Left(LeiosWireError.Incomplete) => DecodeResult.NeedMore
    case Left(e)                         => DecodeResult.Failed(ProtocolError.Leios(e))

  private val leiosFetchSeeds: () => Vector[Array[Byte]] = () =>
    val point = EbPoint(371614L, EbHash.fromBytes(Bytes.fromArray(Array.fill(32)(7.toByte))).get)
    val bitmaps = TxBitmaps(Vector(0 -> BigInt(1), 3 -> BigInt("ffffffffffffffff", 16)))
    val seeds = Vector(
      LeiosFetch.Message.Done,
      LeiosFetch.Message.BlockRequest(point),
      LeiosFetch.Message.Block(Vector(point.hash -> 100L, point.hash -> 0xffffffffL)),
      LeiosFetch.Message.BlockTxsRequest(point, bitmaps),
      LeiosFetch.Message.BlockTxs(point, bitmaps, Corpus.transactions.take(3).map(Bytes.fromArray))
    )
    seeds.flatMap(LeiosFetch.encode(_).toOption.map(_.toArray)) ++
      netCorpus("leios-fetch:leiosBlockFetchMessage")()

  private val leiosNotifySeeds: () => Vector[Array[Byte]] = () =>
    val point = EbPoint(371614L, EbHash.fromBytes(Bytes.fromArray(Array.fill(32)(7.toByte))).get)
    val seeds = Vector(
      LeiosNotify.Message.Done,
      LeiosNotify.Message.Quit,
      LeiosNotify.Message.Canceled,
      LeiosNotify.Message.RequestNext,
      LeiosNotify.Message.BlockOffer(point, 199983L),
      LeiosNotify.Message.BlockTxsOffer(point)
    )
    seeds.flatMap(LeiosNotify.encode(_).toOption.map(_.toArray)) ++
      Corpus.leiosEnvelopes.take(3).map(e => Array(0x82.toByte, 0x04.toByte) ++ e) ++
      netCorpus("leios-notify:leiosNotifyMessage")()

  private val txSubmissionSeeds: () => Vector[Array[Byte]] = () =>
    val replies = Corpus.transactions.take(6).flatMap { tx =>
      TxSubmission2
        .encode(
          TxSubmission2.State.Txs,
          ChainSync.Role.Client,
          TxSubmission2.Message.ReplyTxs(Vector(Bytes.fromArray(tx)))
        )
        .toOption
        .map(_.toArray)
    }
    replies ++ netCorpus("tx-submission2:txSubmission2Message")()

  private val txSubStates: Vector[TxSubmission2.State] =
    Vector(
      TxSubmission2.State.Init,
      TxSubmission2.State.Idle,
      TxSubmission2.State.Txs,
      TxSubmission2.State.Done,
      TxSubmission2.State.Ids(true),
      TxSubmission2.State.Ids(false)
    )

  private val monitorStates: Vector[LocalTxMonitor.State] =
    Vector(
      LocalTxMonitor.State.Idle,
      LocalTxMonitor.State.Acquiring,
      LocalTxMonitor.State.Acquired,
      LocalTxMonitor.State.Done
    ) ++ LocalTxMonitor.Kind.values.toVector.map(LocalTxMonitor.State.Busy(_))

  /** A ChainSync payload that is any single CBOR item, as the node-to-client ChainSync carries
    * blocks.
    */
  private val anyItem: ChainSync.PayloadCodec[Bytes] = new ChainSync.PayloadCodec[Bytes]:
    def encode(value: Bytes): Either[ProtocolError, Bytes] = Right(value)
    def decode(bytes: Bytes): Either[ProtocolError, Bytes] = Right(bytes)

  private val opaquePoint: ChainSyncEngine.PointCodec[Bytes] =
    ChainSyncEngine.pointCodec[Bytes](Right(_), Right(_))
  private val opaqueTip: ChainSyncEngine.TipCodec[Bytes] =
    ChainSyncEngine.tipCodec[Bytes](Right(_), Right(_))
  private val opaqueChainPayload: ChainSyncEngine.PayloadCodec[Bytes] =
    ChainSyncEngine.payloadCodec[Bytes](Right(_), Right(_))
  private val opaqueFetchPoint: BlockFetchEngine.PointCodec[Bytes] =
    BlockFetchEngine.pointCodec[Bytes](Right(_), Right(_))
  private val opaqueFetchPayload: BlockFetchEngine.PayloadCodec[Bytes] =
    new BlockFetchEngine.PayloadCodec[Bytes]:
      def encode(value: Bytes): Either[ProtocolError, Bytes] = Right(value)
      def decode(item: Bytes): Either[ProtocolError, Bytes] = Right(item)

  private def pinnedLines(path: String): Vector[String] =
    Files.readAllLines(java.nio.file.Path.of(path)).asScala.toVector

  private def encodedSeed(value: Either[ProtocolError, Bytes]): Vector[Array[Byte]] =
    value.toOption.toVector.map(_.toArray)

  /** Exact opaque-item messages seeded from the pinned Haskell-generated mock-chain fixtures. */
  private val chainSyncEngineSeeds: () => Vector[Array[Byte]] = () =>
    val fixture = "fixtures/reference/ouroboros-network-protocol-codecs"
    val cases = pinnedLines(s"$fixture/chainsync-behavior-cases.tsv").map(_.split("\\t", -1))
    val blocks = pinnedLines(s"$fixture/chainsync-behavior-blocks.tsv").map(_.split("\\t", -1))
    val points = pinnedLines(s"$fixture/chainsync-behavior-points.tsv").map(_.split("\\t", -1))
    cases.take(12).flatMap { row =>
      val seed = row(1)
      val ordinal = row(2)
      val tip = Bytes.fromHex(row(8)).toOption
      val genesis = Bytes.fromHex(row(12)).toOption
      val candidates = points.collect {
        case p if p(1) == seed && p(2) == ordinal => Bytes.fromHex(p(4)).toOption
      }.flatten
      val payloads = blocks.collect {
        case b if b(1) == seed && b(2) == ordinal && b(3) == "producer" =>
          Bytes.fromHex(b(6)).toOption
      }.flatten
      val seeds = for
        serverTip <- tip
        genesisPoint <- genesis
        candidate <- candidates.headOption
        firstBlock <- payloads.headOption
      yield encodedSeed(
        ChainSyncEngine.encode(
          ChainSync.State.Idle,
          ChainSync.Role.Client,
          ChainSyncEngine.Message.FindIntersect(candidates.take(8)),
          opaquePoint,
          opaqueTip,
          opaqueChainPayload
        )
      ) ++
        encodedSeed(
          ChainSyncEngine.encode(
            ChainSync.State.Intersect,
            ChainSync.Role.Server,
            ChainSyncEngine.Message.IntersectFound(candidate, serverTip),
            opaquePoint,
            opaqueTip,
            opaqueChainPayload
          )
        ) ++
        encodedSeed(
          ChainSyncEngine.encode(
            ChainSync.State.NextCanAwait,
            ChainSync.Role.Server,
            ChainSyncEngine.Message.RollBackward(genesisPoint, serverTip),
            opaquePoint,
            opaqueTip,
            opaqueChainPayload
          )
        ) ++
        encodedSeed(
          ChainSyncEngine.encode(
            ChainSync.State.NextCanAwait,
            ChainSync.Role.Server,
            ChainSyncEngine.Message.RollForward(firstBlock, serverTip),
            opaquePoint,
            opaqueTip,
            opaqueChainPayload
          )
        )
      seeds.toVector.flatten
    }

  /** Exact upstream BlockFetch range endpoints and serialized block items seed the generic engine.
    */
  private val blockFetchEngineSeeds: () => Vector[Array[Byte]] = () =>
    val fixture =
      "network/src/test/resources/upstream/ouroboros-network-protocol-codecs/blockfetch-behavior-ranges.tsv"
    pinnedLines(fixture).take(24).flatMap { line =>
      val row = line.split("\\t", -1)
      val from = Bytes.fromHex(row(4)).toOption
      val to = Bytes.fromHex(row(5)).toOption
      val bodies = row.drop(7).flatMap(hex => Bytes.fromHex(hex).toOption).take(3)
      (from, to) match
        case (Some(first), Some(last)) =>
          encodedSeed(
            BlockFetchEngine.encode(
              BlockFetchEngine.State.Idle,
              BlockFetchEngine.Role.Client,
              BlockFetchEngine.Message.RequestRange(first, last),
              opaqueFetchPoint,
              opaqueFetchPayload
            )
          ) ++ bodies.toVector.flatMap { body =>
            encodedSeed(
              BlockFetchEngine.encode(
                BlockFetchEngine.State.Streaming,
                BlockFetchEngine.Role.Server,
                BlockFetchEngine.Message.Block(body),
                opaqueFetchPoint,
                opaqueFetchPayload
              )
            )
          }
        case _ => Vector.empty
    }

  private def handshake(name: String, suite: Handshake.Suite, rule: String): Target =
    Target(
      name,
      () => Corpus.tricky ++ Corpus.networkFixtures ++ Corpus.sampled(rule, 120),
      probe(name) { b =>
        for state <- Handshake.State.values do
          Handshake.decodePrefix(suite, state, b) match
            case Right((message, rest)) =>
              hit(name)
              req(rest.size < b.size, "decoded nothing")
              Handshake.encode(suite, message) match
                case Left(e) => throw new Violation(s"$state: decoded $message does not encode: $e")
                case Right(enc) =>
                  // the versions a refusal lists that we do not know are parsed, not re-encodable
                  val kept = message match
                    case Handshake.Message.Refuse(r: Handshake.Reason.VersionMismatch) =>
                      Handshake.Message.Refuse(r.copy(unknown = Vector.empty))
                    case other => other
                  req(
                    Handshake.decode(suite, state, enc) == Right(kept),
                    s"$state: $message re-decodes differently"
                  )
            case Left(_) => ()
      }
    )

  private enum MockVersion(val number: Int):
    case V0 extends MockVersion(0)
    case V1 extends MockVersion(1)
    case V2 extends MockVersion(2)
  private object MockVersion:
    given Ordering[MockVersion] = Ordering.by(_.number)
    given Handshake.VersionCodec[MockVersion] with
      def decode(wire: Int): Option[MockVersion] = MockVersion.values.find(_.number == wire)
      def encode(version: MockVersion): Either[ProtocolError, Int] = Right(version.number)

  private def genericHandshakeSeeds(file: String, column: Int): Vector[Array[Byte]] =
    pinnedLines(s"protocol/src/test/resources/upstream/generic-handshake/$file.tsv")
      .flatMap(row =>
        Bytes.fromHex(row.split('\t')(column)).toOption.map(_.toArray)
      ) ++ Corpus.tricky

  private def knownReason(
      reason: Handshake.WireReason[MockVersion]
  ): Handshake.WireReason[MockVersion] = reason match
    case mismatch: Handshake.WireReason.VersionMismatch[MockVersion] =>
      mismatch.copy(unknown = Vector.empty)
    case other => other

  private val genericHandshake: Target = Target(
    "handshake-generic",
    () => genericHandshakeSeeds("codec", 3),
    probe("handshake-generic") { b =>
      for state <- Handshake.State.values do
        Handshake.decodePrefix[MockVersion](state, b).foreach { case (message, rest) =>
          hit("handshake-generic")
          val used = b.size - rest.size
          req(used > 0, "decoded nothing")
          req(
            Handshake.decode[MockVersion](state, b.take(used)) == Right(message),
            "consumed prefix decodes differently"
          )
          req(
            Handshake.decode[MockVersion](state, b.take(used - 1)).isLeft,
            "a shorter prefix decodes too"
          )
          val kept = message match
            case Handshake.WireMessage.Refuse(reason) =>
              Handshake.WireMessage.Refuse(knownReason(reason))
            case other => other
          Handshake.encode(message) match
            case Left(e) => throw new Violation(s"$state: decoded $message does not encode: $e")
            case Right(encoded) =>
              req(
                Handshake.decode[MockVersion](state, encoded) == Right(kept),
                s"$state: $message re-decodes differently"
              )
        }
    }
  )

  private val handshakeReason: Target = Target(
    "handshake-refusal",
    () => genericHandshakeSeeds("refuse", 2),
    probe("handshake-refusal") { b =>
      Handshake.decodeReason[MockVersion](b).foreach { reason =>
        hit("handshake-refusal")
        Handshake.encodeReason(reason) match
          case Left(e) => throw new Violation(s"decoded $reason does not encode: $e")
          case Right(encoded) =>
            req(
              Handshake.decodeReason[MockVersion](encoded) == Right(knownReason(reason)),
              s"$reason re-decodes differently"
            )
      }
    }
  )

  private val network: Vector[Target] = Vector(
    cbor("cbor", Cbor.Limits()),
    cbor(
      "cbor-tight-limits",
      Cbor.Limits(maxInputBytes = 300, maxDepth = 3, maxItems = 12, maxStringBytes = 20)
    ),
    handshake(
      "handshake-ntn",
      Handshake.Suite.NodeToNode,
      "handshake-node-to-node-v14:handshakeMessage"
    ),
    handshake(
      "handshake-ntc",
      Handshake.Suite.NodeToClient,
      "handshake-node-to-client:handshakeMessage"
    ),
    genericHandshake,
    handshakeReason,
    Target(
      "mux-header-and-sdu",
      () => Corpus.networkFixtures ++ Corpus.tricky,
      probe("mux-header-and-sdu") { b =>
        Mux.decodeHeader(b).foreach { h =>
          hit("mux-header-and-sdu")
          req(Mux.encodeHeader(h) == Right(b), "header does not re-encode to its input")
        }
        Mux.decode(b).foreach { sdu =>
          req(Mux.encode(sdu) == Right(b), "SDU does not re-encode to its input")
        }
      }
    ),
    Target(
      "mux-stream",
      () => Corpus.networkFixtures ++ Corpus.tricky,
      in =>
        // a frame of one byte costs an SDU, its header and a payload copy: linear, but a large constant
        Probe.run("mux-stream", in, perByte = 2000) {
          val b = raw(in)
          // cut the input into pieces at positions derived from its own bytes
          val cuts = in.bytes.take(8).map(x => (x & 0xff) % 23 + 1)
          var decoder = Mux.Decoder.create(Mux.Limits(maxPayloadBytes = 4096)).toOption.get
          var at = 0
          var i = 0
          val sdus = Vector.newBuilder[Mux.Sdu]
          var failed = false
          while at < b.size && !failed do
            val n = if cuts.isEmpty then b.size else cuts(i % cuts.length)
            i += 1
            decoder.feed(b.slice(at, at + n)) match
              case Right((next, out)) => decoder = next; sdus ++= out
              case Left(_)            => failed = true
            at += n
          if !failed then
            seen("mux-stream", sdus.result().nonEmpty)
            val framed = sdus.result().map(s => Mux.encode(s).toOption.get)
            val joined = framed.foldLeft(Bytes.empty)(_ ++ _)
            req(
              joined.size + decoder.pendingBytes == b.size,
              "emitted and pending bytes differ from the input"
            )
            req(joined == b.take(joined.size), "emitted frames do not re-encode to the input")
        }
    ),
    Target(
      "ntn-header",
      () => Corpus.headerEnvelopes ++ Corpus.tricky,
      probe("ntn-header") { b =>
        NtNHeader.codec.decode(b).foreach { h =>
          hit("ntn-header")
          req(
            NtNHeader.codec.encode(h).flatMap(NtNHeader.codec.decode) == Right(h),
            "header re-decodes differently"
          )
        }
      }
    ),
    Target(
      "chainsync-point-tip",
      netCorpus("network.base:tip", "network.base:point"),
      probe("chainsync-point-tip") { b =>
        seen("chainsync-point-tip", ChainSync.decodePoint(b).isInstanceOf[DecodeResult.Decoded[?]])
        ChainSync.decodeTip(b)
        ChainSync.decodePoint(b, ChainSync.Limits.NodeToClient)
      }
    ),
    roundTrip(
      "chainsync-ntn",
      ChainSync.State.values.toSeq,
      ChainSync.Role.values.toSeq,
      chainSyncSeeds,
      (s, r, b) => ChainSync.decodePrefix(s, r, b, NtNHeader.codec),
      (s, r, m) => ChainSync.encode(s, r, m, NtNHeader.codec)
    ),
    roundTrip(
      "chainsync-ntc",
      ChainSync.State.values.toSeq,
      ChainSync.Role.values.toSeq,
      () =>
        Corpus.blocks
          .take(8)
          .flatMap(blk =>
            ChainSync
              .encode(
                ChainSync.State.NextCanAwait,
                ChainSync.Role.Server,
                ChainSync.Message.RollForward(Bytes.fromArray(blk), Tip.Origin),
                anyItem,
                ChainSync.Limits.NodeToClient
              )
              .toOption
              .map(_.toArray)
          ) ++ chainSyncSeeds(),
      (s, r, b) => ChainSync.decodePrefix(s, r, b, anyItem, ChainSync.Limits.NodeToClient),
      (s, r, m) => ChainSync.encode(s, r, m, anyItem, ChainSync.Limits.NodeToClient)
    ),
    roundTrip(
      "chainsync-engine-opaque",
      ChainSync.State.values.toSeq,
      ChainSync.Role.values.toSeq,
      chainSyncEngineSeeds,
      (s, r, b) =>
        ChainSyncEngine.decodePrefix(s, r, b, opaquePoint, opaqueTip, opaqueChainPayload),
      (s, r, m) => ChainSyncEngine.encode(s, r, m, opaquePoint, opaqueTip, opaqueChainPayload)
    ),
    roundTrip(
      "blockfetch",
      BlockFetch.State.values.toSeq,
      BlockFetch.Role.values.toSeq,
      blockFetchSeeds,
      (s, r, b) => BlockFetch.decodePrefix(s, r, b, CardanoBlockFetch.payloadCodec()),
      (s, r, m) => BlockFetch.encode(s, r, m, CardanoBlockFetch.payloadCodec())
    ),
    roundTrip(
      "blockfetch-engine-opaque",
      BlockFetchEngine.State.values.toSeq,
      BlockFetchEngine.Role.values.toSeq,
      blockFetchEngineSeeds,
      (s, r, b) => BlockFetchEngine.decodePrefix(s, r, b, opaqueFetchPoint, opaqueFetchPayload),
      (s, r, m) => BlockFetchEngine.encode(s, r, m, opaqueFetchPoint, opaqueFetchPayload)
    ),
    roundTrip(
      "txsubmission2",
      txSubStates,
      ChainSync.Role.values.toSeq,
      txSubmissionSeeds,
      TxSubmission2.decodePrefix,
      TxSubmission2.encode
    ),
    roundTrip(
      "keepalive",
      KeepAlive.State.values.toSeq,
      KeepAlive.Role.values.toSeq,
      netCorpus("keep-alive:keepAliveMessage"),
      (s, r, b) => KeepAlive.decodePrefix(s, r, b),
      (s, r, m) => KeepAlive.encode(s, r, m)
    ),
    roundTrip(
      "peersharing",
      PeerSharing.State.values.toSeq,
      PeerSharing.Role.values.toSeq,
      peerSharingSeeds,
      (s, r, b) => PeerSharing.decoder(s, r)(b),
      (s, r, m) => PeerSharing.encode(s, r, m)
    ),
    roundTrip(
      "leios-fetch",
      LeiosFetch.State.values.toSeq,
      Seq(()),
      leiosFetchSeeds,
      (s, _, b) => leiosResult(LeiosFetch.decodePrefix(s, b)),
      (_, _, m) => LeiosFetch.encode(m)
    ),
    roundTrip(
      "leios-notify",
      LeiosNotify.State.values.toSeq,
      Seq(()),
      leiosNotifySeeds,
      (s, _, b) => leiosResult(LeiosNotify.decodePrefix(s, b)),
      (_, _, m) => LeiosNotify.encode(m)
    ),
    roundTrip(
      "local-state-query",
      LocalStateQuery.State.values.toSeq,
      ChainSync.Role.values.toSeq,
      netCorpus("local-state-query:localStateQueryMessage"),
      LocalStateQuery.decodePrefix,
      LocalStateQuery.encode
    ),
    roundTrip(
      "local-tx-submission",
      LocalTxSubmission.State.values.toSeq,
      ChainSync.Role.values.toSeq,
      () =>
        Corpus.transactions
          .take(6)
          .flatMap(tx =>
            LocalTxSubmission
              .encode(
                LocalTxSubmission.State.Idle,
                ChainSync.Role.Client,
                LocalTxSubmission.Message.SubmitTx(Bytes.fromArray(tx))
              )
              .toOption
              .map(_.toArray)
          ) ++
          netCorpus("local-tx-submission:localTxSubmissionMessage")(),
      LocalTxSubmission.decodePrefix,
      LocalTxSubmission.encode
    ),
    roundTrip(
      "local-tx-monitor",
      monitorStates,
      ChainSync.Role.values.toSeq,
      netCorpus("local-tx-monitor:localTxMonitorMessage"),
      LocalTxMonitor.decodePrefix,
      LocalTxMonitor.encode
    )
  )

  // --- node: blocks, headers, transactions ---------------------------------------------------------

  private val shelleyEras: Vector[Era] = Era.values.toVector.filter(_ != Era.Byron)

  private def eraSeeds(
      fixtures: Era => Vector[Array[Byte]],
      rule: String
  )(era: Era): () => Vector[Array[Byte]] = () =>
    fixtures(era) ++ Corpus.sampled(s"${era.toString.toLowerCase}:$rule", 40) ++ Corpus.tricky

  private val node: Vector[Target] =
    Vector(
      Target(
        "block",
        () => Corpus.blocks ++ Corpus.sampledEras("block", "alonzo") ++ Corpus.tricky,
        probe("block") { b =>
          seen("block", Block.decode(b, Corpus.byronEpochSlots).isRight)
          Block.decodeShelley(b)
          xyz.kd5ujc.scarab.node.NodeRules.decodeShelley(b)
        }
      ),
      Target(
        "header-envelope",
        () =>
          Corpus.headerEnvelopes ++ Corpus.sampled("chainsync-header:header", 60) ++ Corpus.tricky,
        probe("header-envelope") { b =>
          seen("header-envelope", Header.fromEnvelope(b, Corpus.byronEpochSlots).isRight)
        }
      ),
      Target(
        "leios-header-envelope",
        () => Corpus.leiosEnvelopes ++ Corpus.headerEnvelopes.take(10) ++ Corpus.tricky,
        probe("leios-header-envelope") { b =>
          seen("leios-header-envelope", HfcHeader.decode(b).isRight)
          // The reference reads longer-than-shortest heads, so an input need not be its own
          // encoding; what decodes writes a canonical envelope that reads as the same header and
          // is its own encoding (HfcHeader.definite).
          HfcHeader.decode(b).foreach { h =>
            HfcHeader.encode(h) match
              case Right(e) =>
                val again = HfcHeader.decode(e)
                req(again == Right(h), "the canonical envelope does not decode to its header")
                req(
                  again.toOption.map(HfcHeader.encode) == Some(Right(e)),
                  "the canonical envelope is not stable"
                )
                if b.size == e.size then req(e == b, "a same-size envelope differs from its input")
              case Left(_) => req(false, "a decoded envelope does not encode")
          }
        }
      ),
      Target(
        "leios-header",
        () => Corpus.leiosHeaders ++ Corpus.tricky,
        probe("leios-header") { b =>
          seen("leios-header", LeiosHeader.decode(b).isRight)
          LeiosHeader.decode(b).foreach { h =>
            req(h.raw == b, "the header does not keep its original bytes")
            req(h.hash == Blake2b.hash256.hash(b), "the header hash is not over the input")
          }
        }
      ),
      Target(
        "leios-block",
        () => Corpus.leiosBlocks ++ Corpus.tricky,
        probe("leios-block") { b =>
          seen("leios-block", LeiosBlock.decode(b).isRight)
          LeiosBlock.decode(b).foreach(_.faults)
        }
      ),
      Target(
        "header-byron",
        () => Corpus.byronHeaders ++ Corpus.tricky,
        probe("header-byron") { b =>
          seen("header-byron", Header.byronFromRaw(b, true, Corpus.byronEpochSlots).isRight)
          seen("header-byron", Header.byronFromRaw(b, false, Corpus.byronEpochSlots).isRight)
        }
      ),
      Target(
        "byron-body",
        () => Corpus.byronBodies ++ Corpus.tricky,
        probe("byron-body") { b => seen("byron-body", ByronBody.decode(b).isRight) }
      ),
      Target(
        "byron-address",
        () => Corpus.byronAddresses ++ Corpus.tricky,
        probe("byron-address") { b => seen("byron-address", ByronAddress.decode(b).isRight) }
      ),
      Target(
        "tx-submitted",
        () => Corpus.transactions ++ Corpus.sampled("conway:transaction", 40) ++ Corpus.tricky,
        probe("tx-submitted") { b => seen("tx-submitted", MempoolState.parse(b).isRight) }
      ),
      Target(
        "tx-local-submission-unwrap",
        () =>
          Corpus.transactions.take(10).flatMap { t =>
            Vector(
              Array(0x82.toByte, 0x06.toByte) ++ t,
              Array(0x82.toByte, 0x06.toByte, 0xd8.toByte, 0x18.toByte) ++ Bytes
                .fromArray(Array(0x58.toByte, t.length.min(255).toByte))
                .toArray ++ t.take(255)
            )
          } ++ Corpus.tricky,
        probe("tx-local-submission-unwrap") { b =>
          seen("tx-local-submission-unwrap", LocalSubmission.unwrap(b).isRight)
          LocalMonitor.unwrapId(b)
        }
      ),
      Target(
        "ntc-query",
        () => Corpus.ntcQueries ++ Corpus.tricky,
        probe("ntc-query") { b => seen("ntc-query", NtcQuery.decode(b).isRight) }
      ),
      Target(
        "ntc-query-versioned",
        () => Corpus.ntcQueries ++ Corpus.tricky,
        in =>
          // Eight decoder calls retain the normal linear allocation allowance per call.
          Probe.run("ntc-query-versioned", in, perByte = 1000L * NtcVersion.All.size) {
            val b = raw(in)
            val accepted = NtcVersion.All.map(v => NtcQuery.decode(b, v).isRight)
            seen("ntc-query-versioned", accepted.exists(identity))
          }
      ),
      Target(
        "plutus-data",
        () => Corpus.plutusData ++ Corpus.tricky,
        probe("plutus-data") { b => seen("plutus-data", PlutusData.decode(b).isRight) }
      ),
      Target(
        "plutus-flat-scan",
        () => Corpus.plutusFlat ++ Corpus.tricky,
        probe("plutus-flat-scan") { b =>
          seen("plutus-flat-scan", xyz.kd5ujc.scarab.plutus.FlatScan.scan(b.toArray).isRight)
        }
      ),
      Target(
        "address-and-output",
        () => Corpus.addresses ++ Corpus.sampled("conway:transaction_output", 60) ++ Corpus.tricky,
        probe("address-and-output") { b =>
          seen("address-and-output", Address.decode(b).isRight)
          RewardAccount.decode(b)
          Address.Mode.values.foreach { mode =>
            Address.decode(b, mode)
            TxOut.fromBytes(b, mode)
          }
        }
      ),
      Target(
        "param-update",
        () =>
          Corpus.sampled("conway:protocol_param_update", 80) ++ Corpus
            .sampled("alonzo:protocol_param_update", 40) ++ Corpus.tricky,
        probe("param-update") { b =>
          Cbor.decode(b).foreach { n =>
            n.value match
              case xyz.kd5ujc.scarab.codec.Value.Map(kvs) =>
                val fields = kvs.collect {
                  case (k, v)
                      if k.value.isInstanceOf[xyz.kd5ujc.scarab.codec.Value.UInt] && k.value
                        .asInstanceOf[xyz.kd5ujc.scarab.codec.Value.UInt]
                        .value
                        .isValidInt =>
                    k.value
                      .asInstanceOf[xyz.kd5ujc.scarab.codec.Value.UInt]
                      .value
                      .toInt -> v.original
                }.toMap
                val update = ParamUpdate(fields)
                Vector(6, 8, 10).foreach { major =>
                  seen(
                    "param-update",
                    ProtocolParams.update(TestLedger.params(major), update).isRight
                  )
                }
              case _ => ()
          }
        }
      ),
      Target(
        "lite-ledger-effects",
        () =>
          Corpus.txBodies(Era.Alonzo) ++ Corpus.txBodies(Era.Shelley) ++ Corpus
            .sampled("shelley:transaction_body", 60) ++ Corpus.tricky,
        probe("lite-ledger-effects") { b =>
          seen("lite-ledger-effects", LiteLedger.effects(b, Era.Shelley).isRight)
          LiteLedger.effects(b, Era.Babbage)
        }
      ),
      Target(
        "voting-procedures",
        () => Corpus.sampled("conway:voting_procedures", 40) ++ Corpus.tricky,
        probe("voting-procedures") { b =>
          Cbor.decode(b).foreach(n => seen("voting-procedures", VotingProcedures.decode(n).isRight))
        }
      ),
      Target(
        "script",
        () =>
          Corpus.sampled("conway:native_script", 40) ++ Corpus
            .sampled("conway:script", 40) ++ Corpus.tricky,
        probe("script") { b =>
          xyz.kd5ujc.scarab.codec.Cbor.decode(b).foreach { n =>
            seen("script", Script.decode(n).isRight)
            Script.decodeWrapped(n)
            NativeScript.decode(n)
          }
        }
      )
    ) ++ shelleyEras.flatMap { era =>
      val tag = era.toString.toLowerCase
      Vector(
        Target(
          s"tx-body-$tag",
          eraSeeds(Corpus.txBodies, "transaction_body")(era),
          probe(s"tx-body-$tag") { b =>
            seen(s"tx-body-$tag", TxBody.decode(b, Address.Mode.of(era)).isRight)
          }
        ),
        Target(
          s"tx-witnesses-$tag",
          eraSeeds(Corpus.txWitnesses, "transaction_witness_set")(era),
          probe(s"tx-witnesses-$tag") { b =>
            seen(s"tx-witnesses-$tag", Witnesses.decode(b).isRight)
          }
        ),
        Target(
          s"tx-whole-$tag",
          () => Corpus.transactionsOf(era) ++ Corpus.tricky,
          probe(s"tx-whole-$tag") { b =>
            Corpus
              .asTransaction(b)
              .foreach(t => seen(s"tx-whole-$tag", Tx.fromBlock(t, era).isRight))
          }
        ),
        Target(
          s"block-header-$tag",
          () => Corpus.rawHeaders(era) ++ Corpus.sampled(s"$tag:header", 40) ++ Corpus.tricky,
          probe(s"block-header-$tag") { b =>
            seen(s"block-header-$tag", Header.fromRaw(era, b).isRight)
          }
        )
      )
    }

  /** The relays of a pool registration and the big ledger peer snapshot file (G1.3): both are read
    * from data the node does not write. The seeds are real: the relays of the mainnet registration
    * in `fixtures/mainnet/shelley-pool-network-block.hex` and the other shapes of `StakePoolRelay`,
    * and the snapshot file of the Leios testnet.
    */
  private val ledgerPeers: Vector[Target] =
    import xyz.kd5ujc.scarab.ledger.{Ipv4, Ipv6, PoolRelay, PoolRelays, RelayPort}
    def relayBytes(rs: PoolRelay*): Array[Byte] = PoolRelays.encode(rs.toVector).toArray
    val port = RelayPort.fromInt(3001)
    Vector(
      Target(
        "pool-relays",
        () =>
          Vector(
            relayBytes(PoolRelay.SingleHostAddr(port, Some(Ipv4(0x03652f85)), None)),
            relayBytes(PoolRelay.SingleHostAddr(port, None, Some(Ipv6(0x20010db800000000L, 1L)))),
            relayBytes(
              PoolRelay.SingleHostName(port, "relay.example"),
              PoolRelay.SingleHostName(None, "srv.example")
            ),
            relayBytes(PoolRelay.MultiHostName("srv.example")),
            relayBytes()
          ) ++ Corpus.tricky,
        probe("pool-relays") { b =>
          PoolRelays.decode(b).foreach { rs =>
            hit("pool-relays")
            req(
              PoolRelays.decode(PoolRelays.encode(rs)) == Right(rs),
              "relays do not decode as themselves after encoding"
            )
            PoolRelays.ledgerRelays(rs)
          }
        }
      ),
      Target(
        "peer-snapshot",
        () =>
          Vector(
            java.nio.file.Files.readAllBytes(
              java.nio.file.Path.of("fixtures/leios/testnet/config/peer-snapshot.json")
            )
          ) ++ Corpus.tricky,
        probe("peer-snapshot") { b =>
          val text = new String(b.toArray, java.nio.charset.StandardCharsets.UTF_8)
          seen("peer-snapshot", xyz.kd5ujc.scarab.node.PeerSnapshot.decode(text).isRight)
        }
      )
    )

  /** The MemPack `TxOut` of a LedgerDB `tables` file, in every era's grammar. The node imports and
    * exports this format; these seeds exercise externally produced snapshot values. The seeds are
    * the real outputs of the preprod snapshot in `fixtures/mempack`; an output that decodes must
    * pack and decode again to itself.
    */
  private val mempackTxOut: Vector[Target] =
    import scala.jdk.CollectionConverters.*
    import xyz.kd5ujc.scarab.ledger.mempack.TxOutMemPack
    import xyz.kd5ujc.scarab.ledger.state.{Alonzo, Conway, Mary, Shelley}
    def seeds: Vector[Array[Byte]] =
      java.nio.file.Files
        .readAllLines(java.nio.file.Path.of("fixtures/mempack/preprod-conway-sample.tsv"))
        .asScala
        .toVector
        .flatMap(line => Bytes.fromHex(line.split('\t')(2)).toOption.map(_.toArray))
    Vector(
      Target(
        "mempack-txout",
        () =>
          // build every seed's output once here: the first one pays for the class initialisation
          // of the hashing and script code, which the allocation bound of a probe is not for
          val all = seeds
          all.foreach(b =>
            TxOutMemPack.decodeParts[Conway](Bytes.fromArray(b)).foreach(TxOutMemPack.toTxOut)
          )
          all ++ Corpus.tricky
        ,
        probe("mempack-txout") { b =>
          TxOutMemPack.decodeParts[Conway](b).foreach { p =>
            hit("mempack-txout")
            req(
              TxOutMemPack
                .encodeParts[Conway](p)
                .flatMap(TxOutMemPack.decodeParts[Conway]) == Right(p),
              "a MemPack output does not decode as itself after packing"
            )
            TxOutMemPack.toTxOut(p)
          }
          // the other eras' grammars read the same bytes without an exception
          TxOutMemPack.decodeParts[Alonzo](b)
          TxOutMemPack.decodeParts[Mary](b)
          TxOutMemPack.decodeParts[Shelley](b)
          ()
        }
      )
    )

  private val snapshotMeta: Vector[Target] =
    import java.nio.charset.StandardCharsets.UTF_8
    import xyz.kd5ujc.scarab.storage.haskell.state.SnapshotMeta
    Vector(
      Target(
        "snapshot-meta",
        () =>
          val fixtures = Vector(
            "shelley-1000",
            "allegra-1800",
            "mary-2308",
            "alonzo-3013",
            "babbage-4000",
            "conway-4593"
          ).map(era =>
            java.nio.file.Files
              .readAllBytes(java.nio.file.Path.of("fixtures/haskell-state", era, "meta"))
          )
          fixtures ++ Vector(
            "{}",
            "[]",
            "{",
            """{"backend":"utxohd-mem","checksum":"1","tablesCodecVersion":1}"""
          )
            .map(_.getBytes(UTF_8)) ++ Corpus.tricky
        ,
        probe("snapshot-meta") { bytes =>
          SnapshotMeta.parse(new String(bytes.toArray, UTF_8)).foreach { meta =>
            hit("snapshot-meta")
            req(
              SnapshotMeta.encode(meta).flatMap(SnapshotMeta.parse) == Right(meta),
              "snapshot metadata round trip changes its fields"
            )
          }
        }
      )
    )

  private val snapshotTables: Vector[Target] =
    import xyz.kd5ujc.scarab.storage.haskell.state.SnapshotTables
    def seeds: Vector[Array[Byte]] =
      Vector(
        "shelley-1000",
        "allegra-1800",
        "mary-2308",
        "alonzo-3013",
        "babbage-4000",
        "conway-4593"
      )
        .map(era =>
          java.nio.file.Files
            .readAllBytes(java.nio.file.Path.of("fixtures/haskell-state", era, "tables"))
        )
    Vector(
      Target(
        "snapshot-tables",
        () =>
          val inputs = seeds
          inputs.foreach(b => SnapshotTables.decode(Bytes.fromArray(b)))
          inputs ++ Corpus.tricky
        ,
        probe("snapshot-tables") { bytes =>
          SnapshotTables.decode(bytes).foreach { (entries, summary) =>
            hit("snapshot-tables")
            val framed = for
              prefix <- SnapshotTables.prefix(summary.entries)
              encoded <- entries.traverse(SnapshotTables.encodeEntry)
            yield Bytes.wrap(
              Chunk
                .concat(
                  (prefix +: encoded :+ SnapshotTables.suffix(summary.entries))
                    .map(b => Chunk.array(b.unsafeArray, b.unsafeOffset, b.size))
                )
                .toArray
            )
            req(
              framed.flatMap(SnapshotTables.decode).map(_._1) == Right(entries),
              "tables reframing changes original keys or values"
            )
            val split = bytes.size / 2
            val incremental = for
              first <- SnapshotTables.decoder.feed(bytes.take(split))
              second <- first._1.feed(bytes.drop(split))
              complete <- second._1.finish
            yield (first._2 ++ second._2, complete.crc.value)
            req(
              incremental == Right((entries, summary.crc.value)),
              "tables chunk boundary changes decoding or original-byte CRC"
            )
          }
        }
      )
    )

  private val sclsFraming: Vector[Target] =
    import xyz.kd5ujc.scarab.storage.scls.{SclsFraming, SclsKey, SclsRecord, SclsValidation}
    // Prepare immutable codec definitions with the targets; every input decode remains in Probe.
    val records = SclsRecord
    val keys = SclsKey
    val container = SclsValidation
    val limit = SclsFraming.Limit
      .fromInt(16 * 1024 * 1024 + 4096)
      .fold(
        error => throw new Violation(s"invalid SCLS fuzz limit: $error"),
        identity
      )
    Vector(
      Target(
        "scls-framing",
        () =>
          Vector(
            java.nio.file.Files.readAllBytes(
              java.nio.file.Path.of("fixtures/scls/conway-4593/reference.scls")
            )
          ) ++ Corpus.tricky,
        probe("scls-framing") { bytes =>
          SclsFraming.decode(bytes, limit).foreach { frames =>
            hit("scls-framing")
            val encoded = frames.traverse(SclsFraming.encode(_, limit)).map { records =>
              Bytes.wrap(
                Chunk
                  .concat(
                    records.map(record =>
                      Chunk.array(record.unsafeArray, record.unsafeOffset, record.size)
                    )
                  )
                  .toArray
              )
            }
            req(
              encoded == Right(bytes),
              "SCLS framing does not preserve exact original record bytes"
            )
            val split = bytes.size / 2
            val incremental = for
              first <- SclsFraming.decoder(limit).feed(bytes.take(split))
              last <- first._1.feed(bytes.drop(split))
              _ <- last._1.finish
            yield first._2 ++ last._2
            req(incremental == Right(frames), "SCLS input boundary changes record decoding")
          }
        }
      ),
      Target(
        "scls-records",
        () =>
          Vector(
            java.nio.file.Files
              .readAllBytes(java.nio.file.Path.of("fixtures/scls/conway-4593/reference.scls"))
          ) ++ Corpus.tricky,
        probe("scls-records") { bytes =>
          SclsFraming.decode(bytes, limit).foreach { frames =>
            frames.foreach { frame =>
              records.decode(frame).foreach { record =>
                hit("scls-records")
                req(
                  records.encode(record) == Right(frame),
                  "SCLS typed record changes original bytes"
                )
                record match
                  case chunk: SclsRecord.Record.Chunk =>
                    records
                      .foldEntries(chunk, 0L)((count, _) => count + 1)
                      .foreach { (count, _) =>
                        req(count == chunk.count, "SCLS entry fold changes count")
                      }
                  case _ => ()
              }
            }
          }
        }
      ),
      Target(
        "scls-keys",
        () =>
          val fixture = java.nio.file.Files
            .readString(
              java.nio.file.Path.of("fixtures/scls/conway-4593/entries.tsv")
            )
            .linesIterator
            .map { row =>
              val fields = row.split("\t")
              val namespace = SclsRecord.Namespace.values
                .find(_.name == fields(0))
                .getOrElse(throw new Violation("unknown SCLS fixture namespace"))
              val key = Bytes
                .fromHex(fields(1))
                .fold(error => throw new Violation(error.message), identity)
              (Bytes(Vector(namespace.ordinal.toByte)) ++ key).toArray
            }
            .toVector
          val widths = SclsRecord.Namespace.values.toVector.map { namespace =>
            Array(namespace.ordinal.toByte) ++ Array.fill(namespace.keySize)(0.toByte)
          }
          fixture ++ widths ++ Corpus.tricky
        ,
        probe("scls-keys") { bytes =>
          if bytes.nonEmpty then
            SclsRecord.Namespace.values.lift(bytes(0) & 255).foreach { namespace =>
              val original = bytes.drop(1)
              keys.decode(namespace, original).foreach { key =>
                hit("scls-keys")
                req(key.namespace == namespace, "SCLS key changes its namespace")
                req(keys.encode(key) == Right(original), "SCLS key changes original bytes")
              }
            }
        }
      ),
      Target(
        "scls-container",
        () =>
          Vector(
            java.nio.file.Files
              .readAllBytes(java.nio.file.Path.of("fixtures/scls/conway-4593/reference.scls"))
          ) ++ Corpus.tricky,
        probe("scls-container") { bytes =>
          SclsFraming.decode(bytes, limit).foreach { frames =>
            container.validate(frames).foreach { verified =>
              hit("scls-container")
              req(verified.manifest.namespaces.size == 12, "SCLS profile namespace set changes")
            }
          }
        }
      )
    )

  /** Native snapshot payloads enter through storage as well as the recorded-data tools. Use
    * immutable current-format replacements from every era; their historical gaps are not rewritten
    * through the live writer (live provenance is separately tracked by #129).
    */
  private val nativeSnapshots: Vector[Target] =
    import cats.effect.{Sync, SyncIO}
    import cats.mtl.Ask
    import xyz.kd5ujc.scarab.consensus.state.ConsensusMirrorEnv
    import xyz.kd5ujc.scarab.node.NodeSetup
    import xyz.kd5ujc.scarab.storage.{
      Codec,
      FileLedgerSnapshots,
      SnapshotCheck,
      SnapshotSidecar,
      SnapshotV5
    }
    import scala.jdk.CollectionConverters.*
    lazy val env = NodeSetup
      .fromDirectory(java.nio.file.Path.of("fixtures/eras/genesis"))
      .fold(e => throw new Violation(e.message), _.mirrorEnv())
    lazy val format =
      given Ask[SyncIO, ConsensusMirrorEnv] = Ask.const(env)
      SnapshotV5.format[SyncIO](SnapshotCheck.Full)
    Vector(
      Target(
        "native-snapshot-v5",
        () =>
          // Keep decoder allocations visible to Probe's caller-thread counter.
          val caller = Thread.currentThread()
          req(
            Sync[SyncIO].blocking(Thread.currentThread()).unsafeRunSync() eq caller,
            "snapshot fuzz interpreter moved off the allocation-guard thread"
          )
          val listing = java.nio.file.Files.list(java.nio.file.Path.of("fixtures/snapshot-v5/eras"))
          val eraSeeds =
            try
              listing.iterator.asScala.toVector.sorted.map { file =>
                FileLedgerSnapshots
                  .verified(java.nio.file.Files.readAllBytes(file))
                  .fold(e => throw new Violation(e.message), identity)
              }
            finally listing.close()
          val seeds = eraSeeds :+ java.nio.file.Files.readAllBytes(
            java.nio.file.Path.of("node/src/test/resources/rewards-pending-v5.bin")
          )
          req(
            seeds.forall(b => format.decode(b).unsafeRunSync().isRight),
            "native snapshot seed does not decode"
          )
          seeds
        ,
        probe("native-snapshot-v5") { bytes =>
          format.decode(bytes.toArray).unsafeRunSync().foreach { snapshot =>
            hit("native-snapshot-v5")
            val sidecar = SnapshotV5
              .sidecarOf(bytes.toArray)
              .fold(e => throw new Violation(e.message), identity)
            req(
              snapshot.anchor == sidecar.anchor,
              "native snapshot anchor differs from its sidecar"
            )
            val encoded = Codec.encode(sidecar)(using SnapshotSidecar.codec)
            req(
              Codec.decode(encoded)(using SnapshotSidecar.codec) == Right(sidecar),
              "native sidecar round trip drops state or gap metadata"
            )
          }
        }
      )
    )

  private val ancillary: Vector[Target] =
    import xyz.kd5ujc.scarab.storage.haskell.state.AncillaryManifest
    Vector(
      Target(
        "ancillary-manifest",
        () =>
          val seeds = Vector(
            java.nio.file.Files.readAllBytes(
              java.nio.file.Path.of("fixtures/mempack/ancillary/preprod-manifest.json")
            )
          )
          seeds.foreach(b => AncillaryManifest.parse(new String(b, "UTF-8")).foreach(_.hash))
          seeds
        ,
        probe("ancillary-manifest") { bytes =>
          AncillaryManifest.parse(new String(bytes.toArray, "UTF-8")).foreach { manifest =>
            hit("ancillary-manifest")
            val again = AncillaryManifest.parse(AncillaryManifest.encode(manifest))
            req(again == Right(manifest), "manifest canonical JSON changes signed data")
            req(
              again.map(_.hash) == Right(manifest.hash),
              "manifest re-encoding changes the signed hash"
            )
            req(
              manifest.files.forall(f => AncillaryManifest.safePath(f.path)),
              "manifest admits an unsafe path"
            )
          }
        }
      ),
      Target(
        "ancillary-key",
        () =>
          val seeds = Vector(
            java.nio.file.Files
              .readAllBytes(java.nio.file.Path.of("fixtures/mempack/ancillary/preprod.vkey"))
          )
          seeds.foreach(b => AncillaryManifest.verificationKey(new String(b, "UTF-8")))
          seeds
        ,
        probe("ancillary-key") { bytes =>
          AncillaryManifest.verificationKey(new String(bytes.toArray, "UTF-8")).foreach { key =>
            hit("ancillary-key")
            req(key.bytes.size == 32, "ancillary key does not contain 32 octets")
          }
        }
      )
    )

  private val mithril: Vector[Target] =
    import xyz.kd5ujc.scarab.storage.mithril.MithrilClient
    val seed =
      """{"verified_db_directory":"/owned/db","certificate_network":"preview","certificate_hash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","certificate_epoch":1454,"certificate_merkle_root":"b84de351111675b2636e0f4b7a1dfadb2c55d89c8f7cb4137777f185e59fcba3"}"""
    Vector(
      Target(
        "mithril-receipt",
        () => Vector(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        probe("mithril-receipt") { bytes =>
          MithrilClient.ReceiptKind.values.foreach { kind =>
            MithrilClient.receipt(new String(bytes.toArray, "UTF-8"), kind).foreach { receipt =>
              hit("mithril-receipt")
              req(receipt.artifact.text.length == 64, "verified artifact is not a SHA256 identity")
              req(receipt.certificate.text.length == 64, "certificate is not a SHA256 identity")
            }
          }
        }
      )
    )

  private val networkIdentity: Vector[Target] =
    val rawHash = Bytes.fromArray(Array.tabulate[Byte](32)(_.toByte))
    val identity = NetworkIdentity(
      42L,
      Bytes.fromArray(Array.fill[Byte](32)(1)),
      Some(Bytes.fromArray(Array.fill[Byte](32)(2))),
      GenesisFileHash(rawHash),
      GenesisFileHash(Bytes.fromArray(Array.fill[Byte](32)(3)))
    )
    val legacyV1 = identity.legacy.renderAs(FormatVersion.V1)
    val legacyV2 = identity.legacy.renderAs(FormatVersion.V2)
    val current = identity.renderAs(FormatVersion.V6)
    val confirmedMissing = identity.renderAs(
      FormatVersion.V6,
      IdentityProvenance.OperatorConfirmedMissing
    )
    val confirmedLegacy = identity.renderAs(
      FormatVersion.V6,
      IdentityProvenance.OperatorConfirmedLegacy(FormatVersion.V2, rawHash)
    )
    val malformed = Vector(
      current.replace(
        "provenance fresh",
        "provenance operator-confirmed-legacy:v3:blake2b256=" + rawHash.hex
      ),
      current.replace("provenance fresh", "provenance operator-confirmed-legacy:v2"),
      current.replace("provenance fresh", "provenance operator-confirmed-legacy:v2:blake2b256=00")
    )
    val seeds = Vector(legacyV1, legacyV2, current, confirmedMissing, confirmedLegacy) ++ malformed
    Vector(
      Target(
        "network-identity-file",
        () => seeds.map(_.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        probe("network-identity-file") { bytes =>
          val text = new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
          NetworkIdentity.parseVersioned("fuzz identity", text) match
            case Right(parsed) =>
              req(!malformed.contains(text), "malformed identity provenance was accepted")
              hit("network-identity-file")
              val canonical = parsed._2 match
                case StoredNetworkIdentity.Legacy(_, value) => value.renderAs(parsed._1)
                case StoredNetworkIdentity.Complete(value, provenance) =>
                  value.renderAs(parsed._1, provenance)
              req(
                NetworkIdentity.parseVersioned("fuzz identity", canonical) == Right(parsed),
                "identity parse/render/parse changed its version or stored value"
              )
            case Left(_) => req(!seeds.take(5).contains(text), "valid identity seed was refused")
        }
      )
    )

  private val sclsValues: Target =
    import xyz.kd5ujc.scarab.storage.scls.{SclsRecord, SclsValue}
    val supported = Vector(
      SclsRecord.Namespace.Blocks,
      SclsRecord.Namespace.Utxo,
      SclsRecord.Namespace.Accounts,
      SclsRecord.Namespace.DReps,
      SclsRecord.Namespace.ProposalRoots,
      SclsRecord.Namespace.Proposals,
      SclsRecord.Namespace.CommitteeEntities,
      SclsRecord.Namespace.PParams,
      SclsRecord.Namespace.StakePools,
      SclsRecord.Namespace.StakePoolVrfHashes,
      SclsRecord.Namespace.Committee,
      SclsRecord.Namespace.Constitution
    )
    Target(
      "scls-values",
      () =>
        val fixture = java.nio.file.Files.readString(
          java.nio.file.Path.of("fixtures/scls/namespace-value-vectors/conway-f649.tsv")
        )
        val vectorSeeds = fixture.linesIterator
          .filter(_.nonEmpty)
          .map { line =>
            val hex = line.split("\\t", -1).last
            xyz.kd5ujc.scarab.codec.Bytes
              .fromHex(hex)
              .fold(e => throw new Violation(e.message), _.toArray)
          }
          .toVector
        val artifact = java.nio.file.Files
          .readString(java.nio.file.Path.of("fixtures/scls/conway-4593/entries.tsv"))
        val artifactSeeds = artifact.linesIterator.flatMap { line =>
          line.split("\\t", -1) match
            case Array(name, _, value) if supported.exists(_.name == name) =>
              Some(
                xyz.kd5ujc.scarab.codec.Bytes
                  .fromHex(value)
                  .fold(e => throw new Violation(e.message), _.toArray)
              )
            case _ => None
        }.toVector
        vectorSeeds ++ artifactSeeds
      ,
      probe("scls-values") { bytes =>
        supported.foreach { namespace =>
          SclsValue.decode(namespace, bytes).foreach { value =>
            hit("scls-values")
            val encoded = SclsValue.encode(value) match
              case Right(result) => result
              case Left(error) =>
                throw new Violation(s"decoded $namespace value does not encode: $error")
            req(
              SclsValue.decode(namespace, encoded) == Right(value),
              s"$namespace value changes after encoding"
            )
          }
        }
      }
    )

  val all: Vector[Target] =
    network ++ node ++ ledgerPeers ++ mempackTxOut ++ snapshotMeta ++ snapshotTables ++ sclsFraming ++
      Vector(sclsValues) ++ nativeSnapshots ++ ancillary ++ mithril ++ networkIdentity
