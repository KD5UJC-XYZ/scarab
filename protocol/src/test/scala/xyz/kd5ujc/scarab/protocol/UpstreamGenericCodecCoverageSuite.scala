// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.{DecodeResult, Role}

/** Exact original mock-domain rows complement strict Cardano refusal assertions. Production engines
  * own agency, envelopes and prefix parsing; typed item codecs preserve the original CBOR values.
  */
class UpstreamGenericCodecCoverageSuite extends munit.FunSuite:
  private final case class Sample(
      seed: Int,
      state: String,
      sender: String,
      constructor: String,
      wire: Bytes
  )

  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.toString), identity)

  private def samples(name: String, digest: String, count: Int): Vector[Sample] =
    val path = s"/upstream/ouroboros-network-protocol-codecs/$name.tsv"
    val stream = Option(getClass.getResourceAsStream(path)).getOrElse(fail(s"missing $path"))
    val raw =
      try stream.readAllBytes()
      finally stream.close()
    assertEquals(
      MessageDigest.getInstance("SHA-256").digest(raw).map(b => f"${b & 255}%02x").mkString,
      digest,
      s"original $name fixture"
    )
    val rows = new String(raw, StandardCharsets.UTF_8).linesIterator.toVector
    assertEquals(rows.size, count)
    rows.map { line =>
      val fields = line.split("\t", -1).toVector
      val offset = if name.endsWith("-binary") then 1 else 0
      val wireIndex = if name == "peersharing" then 9 else 7 + offset
      assertEquals(fields.drop(wireIndex + 1).toSet, Set("pass"), s"original judgments for $name")
      Sample(
        fields(1).toInt,
        fields(4 + offset),
        fields(5 + offset),
        fields(6 + offset),
        bytes(fields(wireIndex))
      )
    }

  private def sender(sample: Sample): Role = sample.sender match
    case "client" => Role.Client
    case "server" => Role.Server
    case other    => fail(s"unexpected original sender $other")

  private val point = ChainSyncEngine.pointCodec[Bytes](Right(_), Right(_))
  private val tip = ChainSyncEngine.tipCodec[Bytes](Right(_), Right(_))
  private val header = ChainSyncEngine.payloadCodec[Bytes](Right(_), Right(_))
  private val fetchPoint = BlockFetchEngine.pointCodec[Bytes](Right(_), Right(_))
  private val block = new BlockFetchEngine.PayloadCodec[Bytes]:
    def encode(value: Bytes): Either[ProtocolError, Bytes] = Right(value)
    def decode(item: Bytes): Either[ProtocolError, Bytes] = Right(item)

  private val tx = TxSubmission2Engine
    .fromCodecs[Bytes, Bytes](
      maxBatch = 65535,
      maxOriginalBytes = 2500000,
      maxAdvertisedSize = 0xffffffffL,
      maxMessageBytes = _ => 2500000,
      encodeId = Right(_),
      decodeId = Right(_),
      encodeBody = Right(_),
      decodeBody = Right(_),
      checkOffer = _ => Right(()),
      advertisedSize = size => Right(size.toLong),
      bodyId = Right(_)
    )
    .fold(e => fail(e.toString), identity)

  private def verify[A](
      sample: Sample,
      partitions: Boolean,
      decode: Bytes => DecodeResult[A],
      encode: A => Either[ProtocolError, Bytes],
      constructor: A => String
  ): Unit =
    val complete = decode(sample.wire)
    val value = complete match
      case DecodeResult.Decoded(value, consumed) =>
        assertEquals(consumed, sample.wire.size, s"complete original row ${sample.seed}")
        value
      case other => fail(s"original mock-domain row ${sample.seed} must decode: $other")
    assertEquals(constructor(value), sample.constructor, s"original constructor ${sample.seed}")
    assertEquals(encode(value), Right(sample.wire), s"exact original bytes ${sample.seed}")
    assertEquals(decode(sample.wire ++ bytes("00")), complete, s"preserve suffix ${sample.seed}")
    assertEquals(decode(sample.wire.take(sample.wire.size - 1)), DecodeResult.NeedMore)
    if partitions then
      // Prefix decoding is stateless. Every accumulated prefix of any ordered two/three-way
      // partition is one of these byte offsets; evaluating each once covers all partitions.
      (0 to sample.wire.size).foreach { end =>
        val expected = if end < sample.wire.size then DecodeResult.NeedMore else complete
        assertEquals(
          decode(sample.wire.take(end)),
          expected,
          s"original prefix ${sample.seed}@$end"
        )
      }

  test(
    "all original serialised ChainSync rows, binary directions and split observations reach the production engine"
  ):
    val corpora = Vector(
      ("chainsync", "d2c840ebcb1f4b55b59055e8ef4b9ff55da80a0d0d428dc2b090c3c0c840224e", 128),
      ("chainsync-binary", "2bb4450b478eba153716ad5a65c3267ee63cedf2d1d0d6850768729ee932ec1c", 256),
      ("chainsync-splits", "2979a92446cb3bc062b4f9109f1dea3208bf4094e22c80116f341ac838b45c70", 128)
    )
    corpora.foreach { (name, hash, count) =>
      samples(name, hash, count).foreach { sample =>
        val state = sample.state match
          case "idle"            => ChainSync.State.Idle
          case "next-can-await"  => ChainSync.State.NextCanAwait
          case "next-must-reply" => ChainSync.State.NextMustReply
          case "intersect"       => ChainSync.State.Intersect
          case other             => fail(s"unknown original state $other")
        val role = sender(sample)
        verify[ChainSyncEngine.Message[Bytes, Bytes, Bytes]](
          sample,
          name.endsWith("-splits"),
          ChainSyncEngine.decodePrefix(state, role, _, point, tip, header),
          ChainSyncEngine.encode(state, role, _, point, tip, header),
          {
            case ChainSyncEngine.Message.RequestNext          => "request-next"
            case ChainSyncEngine.Message.AwaitReply           => "await-reply"
            case ChainSyncEngine.Message.RollForward(_, _)    => "roll-forward"
            case ChainSyncEngine.Message.RollBackward(_, _)   => "roll-backward"
            case ChainSyncEngine.Message.FindIntersect(_)     => "find-intersect"
            case ChainSyncEngine.Message.IntersectFound(_, _) => "intersect-found"
            case ChainSyncEngine.Message.IntersectNotFound(_) => "intersect-not-found"
            case ChainSyncEngine.Message.Done                 => "done"
          }
        )
      }
    }

  test(
    "all original serialised BlockFetch rows, binary directions and split observations reach the production engine"
  ):
    val corpora = Vector(
      ("blockfetch", "18c4e32a647e7287246b2ef2c6a874e82e565778e63352966645fb969e0e2e50", 24),
      ("blockfetch-binary", "fd28eb73baef1556606482c004d78e03b3296414d99b4a15800f171f636cbeeb", 48),
      ("blockfetch-splits", "7d1ff263248c4d206df352d7f7e6e0b8a1735c204db7d30f761f25fffa61cb2a", 24)
    )
    corpora.foreach { (name, hash, count) =>
      samples(name, hash, count).foreach { sample =>
        val state = sample.state match
          case "idle"      => BlockFetchEngine.State.Idle
          case "busy"      => BlockFetchEngine.State.Busy
          case "streaming" => BlockFetchEngine.State.Streaming
          case other       => fail(s"unknown original state $other")
        val role =
          if sender(sample) == Role.Client then BlockFetchEngine.Role.Client
          else BlockFetchEngine.Role.Server
        verify[BlockFetchEngine.Message[Bytes, Bytes]](
          sample,
          name.endsWith("-splits"),
          BlockFetchEngine.decodePrefix(state, role, _, fetchPoint, block),
          BlockFetchEngine.encode(state, role, _, fetchPoint, block),
          {
            case BlockFetchEngine.Message.RequestRange(_, _) => "request-range"
            case BlockFetchEngine.Message.ClientDone         => "client-done"
            case BlockFetchEngine.Message.StartBatch         => "start-batch"
            case BlockFetchEngine.Message.NoBlocks           => "no-blocks"
            case BlockFetchEngine.Message.Block(_)           => "block"
            case BlockFetchEngine.Message.BatchDone          => "batch-done"
          }
        )
      }
    }

  test("all original LocalStateQuery codec and split observations reach the production engine"):
    val corpora = Vector(
      ("localsq", "a9b0f1584c4330ebb2ce670d3dd422ecb168ed4a55b6aa469c824b12c33940cf", 24),
      ("localsq-splits", "411ff97764d6e81748ecc4772a5bed02fffdb91dc3e8d5451f9197e58f5bc7b0", 24)
    )
    corpora.foreach { (name, hash, count) =>
      samples(name, hash, count).foreach { sample =>
        val state = sample.state match
          case "idle"      => LocalStateQuery.State.Idle
          case "acquiring" => LocalStateQuery.State.Acquiring
          case "acquired"  => LocalStateQuery.State.Acquired
          case "querying"  => LocalStateQuery.State.Querying
          case other       => fail(s"unknown original state $other")
        val role = sender(sample)
        def target(t: LocalStateQueryEngine.Target[Bytes]): String = t match
          case LocalStateQueryEngine.Target.Specific(_)  => "specific-point"
          case LocalStateQueryEngine.Target.VolatileTip  => "volatile-tip"
          case LocalStateQueryEngine.Target.ImmutableTip => "immutable-tip"
        verify[LocalStateQueryEngine.Message[Bytes]](
          sample,
          name.endsWith("-splits"),
          LocalStateQueryEngine.decodePrefix(state, role, _, point),
          LocalStateQueryEngine.encode(state, role, _, point),
          {
            case LocalStateQueryEngine.Message.Acquire(t)   => s"acquire-${target(t)}"
            case LocalStateQueryEngine.Message.ReAcquire(t) => s"reacquire-${target(t)}"
            case LocalStateQueryEngine.Message.Acquired     => "acquired"
            case LocalStateQueryEngine.Message.Failed(LocalStateQuery.Failure.PointTooOld) =>
              "failure-point-too-old"
            case LocalStateQueryEngine.Message.Failed(LocalStateQuery.Failure.PointNotOnChain) =>
              "failure-point-not-on-chain"
            case LocalStateQueryEngine.Message.Query(_)  => "query"
            case LocalStateQueryEngine.Message.Result(_) => "result"
            case LocalStateQueryEngine.Message.Release   => "release"
            case LocalStateQueryEngine.Message.Done      => "done"
          }
        )
      }
    }

  test(
    "all original TxSubmission2 codec, annotated and split observations reach the production engine"
  ):
    val corpora = Vector(
      ("txsubmission2", "35a6489a5d78aa1e59895f6d4b37a4bc6bb95c974d713b032786f48c9fd396c6", 24),
      (
        "txsubmission2-annotated",
        "2dde0b6a37ed1d4682f8ed04bdc0f96d1441aa79c79d0bf2cfe68b2e695e9ecc",
        24
      ),
      (
        "txsubmission2-splits",
        "01e45d4a3b7e32b4d92e6cdb5007f933c04f78361a46d94ebd97b1f64f31f503",
        24
      )
    )
    corpora.foreach { (name, hash, count) =>
      samples(name, hash, count).foreach { sample =>
        val state = sample.state match
          case "init"            => TxSubmission2Engine.State.Init
          case "idle"            => TxSubmission2Engine.State.Idle
          case "ids-blocking"    => TxSubmission2Engine.State.Ids(true)
          case "ids-nonblocking" => TxSubmission2Engine.State.Ids(false)
          case "txs"             => TxSubmission2Engine.State.Txs
          case other             => fail(s"unknown original state $other")
        val role = sender(sample)
        verify[TxSubmission2Engine.Message[Bytes, Bytes]](
          sample,
          name.endsWith("-splits") || name.endsWith("-annotated"),
          tx.decodePrefix(state, role, _),
          tx.encode(state, role, _),
          {
            case TxSubmission2Engine.Message.Init                    => "init"
            case TxSubmission2Engine.Message.Done                    => "done"
            case TxSubmission2Engine.Message.RequestIds(true, _, _)  => "request-ids-blocking"
            case TxSubmission2Engine.Message.RequestIds(false, _, _) => "request-ids-nonblocking"
            case TxSubmission2Engine.Message.ReplyIds(_)             => "reply-ids"
            case TxSubmission2Engine.Message.RequestTxs(_)           => "request-txs"
            case TxSubmission2Engine.Message.ReplyTxs(_)             => "reply-txs"
          }
        )
      }
    }

  test("all original PeerSharing integer rows use the production wire engine"):
    val input = samples(
      "peersharing",
      "f68fcdbd0e087c87b57600303b0ab9e32877675b4c30397215354e87ecc65620",
      24
    )
    val peer = PeerSharingEngine.peerCodec[Bytes](Right(_), Right(_))
    input.foreach { sample =>
      val state = sample.state match
        case "idle" => PeerSharing.State.Idle
        case "busy" => PeerSharing.State.Busy
        case other  => fail(s"unknown original state $other")
      val role =
        if sender(sample) == Role.Client then PeerSharing.Role.Client else PeerSharing.Role.Server
      verify[PeerSharingEngine.Message[Bytes]](
        sample,
        true,
        PeerSharingEngine.decodePrefix(state, role, _, peer),
        PeerSharingEngine.encode(state, role, _, peer),
        {
          case PeerSharingEngine.Message.ShareRequest(_) => "request"
          case PeerSharingEngine.Message.SharePeers(_)   => "peers"
          case PeerSharingEngine.Message.Done            => "done"
        }
      )
    }
