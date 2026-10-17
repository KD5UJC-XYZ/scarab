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
          HfcHeader.decode(b).foreach { h =>
            req(HfcHeader.encode(h) == Right(b), "an envelope does not re-encode to itself")
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
        "plutus-data",
        () => Corpus.plutusData ++ Corpus.tricky,
        probe("plutus-data") { b => seen("plutus-data", PlutusData.decode(b).isRight) }
      ),
      Target(
        "address-and-output",
        () => Corpus.addresses ++ Corpus.sampled("conway:transaction_output", 60) ++ Corpus.tricky,
        probe("address-and-output") { b =>
          seen("address-and-output", Address.decode(b).isRight)
          Address.decode(b, Address.Mode.Backwards)
          RewardAccount.decode(b)
          TxOut.fromBytes(b)
          TxOut.fromBytes(b, Address.Mode.Backwards)
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

  val all: Vector[Target] = network ++ node
