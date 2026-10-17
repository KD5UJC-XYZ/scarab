// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.fuzz

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

/** Every entry point that parses bytes from a peer, a client or a block. The property of each: no
  * exception, bounded time and allocation (`Probe`), and `decode . encode` is the identity on what
  * decodes.
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

  private def roundTrip[S, R, M](
      name: String,
      states: Seq[S],
      roles: Seq[R],
      corpus: () => Vector[Array[Byte]],
      decode: (S, R, Bytes) => DecodeResult[M],
      encode: (S, R, M) => Either[String, Bytes]
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
    case Left(e)                         => DecodeResult.Failed(e.toString)

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
    def encode(value: Bytes): Either[String, Bytes] = Right(value)
    def decode(bytes: Bytes): Either[String, Bytes] = Right(bytes)

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
      "blockfetch",
      BlockFetch.State.values.toSeq,
      BlockFetch.Role.values.toSeq,
      blockFetchSeeds,
      (s, r, b) => BlockFetch.decodePrefix(s, r, b, CardanoBlockFetch.payloadCodec()),
      (s, r, m) => BlockFetch.encode(s, r, m, CardanoBlockFetch.payloadCodec())
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
      (s, r, m) => PeerSharing.encode(s, r, m).left.map(_.message)
    ),
    roundTrip(
      "leios-fetch",
      LeiosFetch.State.values.toSeq,
      Seq(()),
      leiosFetchSeeds,
      (s, _, b) => leiosResult(LeiosFetch.decodePrefix(s, b)),
      (_, _, m) => LeiosFetch.encode(m).left.map(_.toString)
    ),
    roundTrip(
      "leios-notify",
      LeiosNotify.State.values.toSeq,
      Seq(()),
      leiosNotifySeeds,
      (s, _, b) => leiosResult(LeiosNotify.decodePrefix(s, b)),
      (_, _, m) => LeiosNotify.encode(m).left.map(_.toString)
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

  /** The MemPack `TxOut` of a LedgerDB `tables` file (Step D, S1): read from a file the node does
    * not write (a Mithril snapshot), in every era's grammar. The seeds are the real outputs of the
    * preprod snapshot in `fixtures/mempack`; an output that decodes must pack and decode again to
    * itself.
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

  val all: Vector[Target] = network ++ node ++ ledgerPeers ++ mempackTxOut
