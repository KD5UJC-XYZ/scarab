// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.io.Source
import xyz.kd5ujc.scarab.codec.Bytes
import ChainSync.DecodeResult

/** Consumes actual generated codec messages exported from the pinned upstream QuickCheck tests. */
class UpstreamProtocolCodecCoverageSuite extends munit.FunSuite:
  private val prefix = "/upstream/ouroboros-network-protocol-codecs/"
  private def resource(name: String): InputStream =
    Option(getClass.getResourceAsStream(prefix + name)).getOrElse(fail(s"missing fixture $name"))

  private def rows(name: String, expectedSha256: String): Vector[Vector[String]] =
    val stream = resource(name)
    val raw =
      try stream.readAllBytes()
      finally stream.close()
    val digest = MessageDigest
      .getInstance("SHA-256")
      .digest(raw)
      .map(b => f"${b & 0xff}%02x")
      .mkString
    assertEquals(digest, expectedSha256, s"$name fixture SHA-256")
    Source
      .fromString(new String(raw, StandardCharsets.UTF_8))
      .getLines()
      .map(_.split("\\t", -1).toVector)
      .toVector

  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.toString), identity)
  private def cookie(value: String): KeepAlive.Cookie =
    KeepAlive.Cookie.from(value.toInt).fold(e => fail(e.toString), identity)

  private def cardanoVersionData(fields: Vector[String], start: Int): Handshake.Data =
    val diffusion = fields(start + 1) match
      case "InitiatorOnlyDiffusionMode"         => true
      case "InitiatorAndResponderDiffusionMode" => false
      case other                                => fail(s"unknown Cardano diffusion mode $other")
    val peers = fields(start + 2) match
      case "PeerSharingEnabled"  => true
      case "PeerSharingDisabled" => false
      case other                 => fail(s"unknown Cardano peer sharing value $other")
    val peras = fields(start + 4) match
      case "PerasSupported"   => true
      case "PerasUnsupported" => false
      case other              => fail(s"unknown Cardano Peras support value $other")
    Handshake.Data(
      magic = fields(start).toLong,
      initiatorOnly = diffusion,
      peerSharing = peers,
      query = fields(start + 3).toBoolean,
      peras = peras
    )

  test("consume all seeded KeepAlive codec cases at the typed Scarab boundary"):
    val input =
      rows("keepalive.tsv", "68bfde5d45f320586738c5d3fcc7c13b057b916697fbfc580f084dc72cdefb34")
    val matches = input.map { fields =>
      assertEquals(fields.size, 10)
      assertEquals(fields(0), "keepalive-codec-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      val size = fields(3).toInt
      assertEquals(seed, 490201 + ordinal)
      assertEquals(size, 30 + ordinal % 21)
      assertEquals(fields(9), "pass", s"upstream property seed $seed")
      val state = fields(4) match
        case "client" => KeepAlive.State.Client
        case "server" => KeepAlive.State.Server
        case other    => fail(s"unknown KeepAlive state $other")
      val sender = fields(5) match
        case "client" => KeepAlive.Role.Client
        case "server" => KeepAlive.Role.Server
        case other    => fail(s"unknown KeepAlive sender $other")
      val message = fields(6) match
        case "request"  => KeepAlive.Message.Request(cookie(fields(7)))
        case "response" => KeepAlive.Message.Response(cookie(fields(7)))
        case "done"     => KeepAlive.Message.Done
        case other      => fail(s"unknown KeepAlive message $other")
      val wire = bytes(fields(8))
      assertEquals(KeepAlive.decode(state, sender, wire), Right(message), s"seed $seed decode")
      assertEquals(KeepAlive.encode(state, sender, message), Right(wire), s"seed $seed wire")
      "match"
    }
    assertEquals(matches.size, 24)
    assertEquals(matches.count(_ == "match"), 24)

  test("retain and classify every seeded PeerSharing row, mapping only typed-compatible messages"):
    val input =
      rows("peersharing.tsv", "61fd52db28760ccfb88b3e0099e2f20e5f450ca0a690c495252243bed11df010")
    val statuses = input.map { fields =>
      assertEquals(fields.size, 11)
      assertEquals(fields(0), "peersharing-codec-int-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 490201 + ordinal)
      assertEquals(fields(3).toInt, 30 + ordinal % 21)
      assertEquals(fields(10), "pass", s"upstream property seed $seed")
      val wire = bytes(fields(9))
      fields(8) match
        case "candidate" =>
          val (state, sender, message) = fields(6) match
            case "request" =>
              val amount = PeerSharing.Values.Amount
                .from(fields(7).toInt)
                .getOrElse(fail(s"PeerSharing amount outside Word8 at seed $seed"))
              (
                PeerSharing.State.Idle,
                PeerSharing.Role.Client,
                PeerSharing.Message.ShareRequest(amount)
              )
            case "done" =>
              (PeerSharing.State.Idle, PeerSharing.Role.Client, PeerSharing.Message.Done)
            case other => fail(s"unexpected candidate PeerSharing message $other at seed $seed")
          assertEquals(
            PeerSharing.decode(state, sender, wire),
            Right(message),
            s"seed $seed decode"
          )
          assertEquals(PeerSharing.encode(state, sender, message), Right(wire), s"seed $seed wire")
          "match"
        case "unsupported-int-addresses" =>
          assertEquals(fields(4), "busy")
          assertEquals(fields(5), "server")
          assertEquals(fields(6), "peers")
          // The Haskell-generated Int list and its original CBOR are preserved in this row. There
          // is no honest conversion to Scarab's typed Address enum.
          assert(wire.size > 0, s"missing original peer-list bytes at seed $seed")
          "unsupported"
        case other => fail(s"unknown PeerSharing adapter status $other at seed $seed")
    }
    assertEquals(statuses.size, 24)
    assertEquals(statuses.count(_ == "match"), 18)
    assertEquals(statuses.count(_ == "unsupported"), 6)

  test("consume seeded Cardano NodeToNode negotiation cases at the typed Handshake boundary"):
    val input = rows(
      "cardano-handshake.tsv",
      "b8d1870c1e470c128afbcfc78ed204747aeef116315b457d6350fa6059e4b34c"
    )
    val outcomes = input.map { fields =>
      assertEquals(fields.size, 20)
      assertEquals(fields(0), "cardano-handshake-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 490801 + ordinal)
      assertEquals(fields(3).toInt, 40 + ordinal % 31)
      assert(fields(4) == "equal-network-magic" || fields(4) == "unconstrained")
      assertEquals(fields(5), "16")
      assertEquals(fields(6), "pass", s"unchanged upstream property at seed $seed")
      if fields(4) == "equal-network-magic" then assertEquals(fields(8), fields(13))

      val local = cardanoVersionData(fields, 8)
      val remote = cardanoVersionData(fields, 13)
      val localWire = bytes(fields(18))
      val remoteWire = bytes(fields(19))
      val decodedLocal = Handshake
        .decode(Handshake.Suite.NodeToNode, Handshake.State.Propose, localWire)
        .fold(e => fail(e.message), identity)
      val decodedRemote = Handshake
        .decode(Handshake.Suite.NodeToNode, Handshake.State.Propose, remoteWire)
        .fold(e => fail(e.message), identity)
      val expectedLocalMessage = Handshake
        .proposal(Handshake.Suite.NodeToNode, Vector(16 -> local))
        .fold(e => fail(e.toString), identity)
      val expectedRemoteMessage = Handshake
        .proposal(Handshake.Suite.NodeToNode, Vector(16 -> remote))
        .fold(e => fail(e.toString), identity)
      assertEquals(decodedLocal, expectedLocalMessage, s"seed $seed local proposal")
      assertEquals(decodedRemote, expectedRemoteMessage, s"seed $seed remote proposal")
      assertEquals(
        Handshake
          .encode(Handshake.Suite.NodeToNode, decodedLocal)
          .fold(e => fail(e.toString), identity),
        localWire,
        s"seed $seed original local wire"
      )
      assertEquals(
        Handshake
          .encode(Handshake.Suite.NodeToNode, decodedRemote)
          .fold(e => fail(e.toString), identity),
        remoteWire,
        s"seed $seed original remote wire"
      )

      val expected =
        if local.magic != remote.magic then
          Handshake.Result.Rejected(Handshake.Reason.Refused(16, "network magic mismatch"))
        else if local.query || remote.query then
          Handshake.Result.QueryResult(Vector(16 -> Right(remote)))
        else
          Handshake.Result.Negotiated(
            16,
            Handshake.Data(
              local.magic,
              initiatorOnly = local.initiatorOnly || remote.initiatorOnly,
              peerSharing = local.peerSharing && remote.peerSharing,
              query = local.query || remote.query,
              peras = local.peras && remote.peras
            )
          )
      val offers = Vector(16 -> local)
      val (fromRemote, resultFromRemote) = Handshake
        .responder(Handshake.Suite.NodeToNode, offers, decodedRemote)
        .fold(e => fail(e.toString), identity)
      assertEquals(resultFromRemote, expected, s"seed $seed remote to local")
      val reverseExpected = expected match
        case Handshake.Result.QueryResult(_) =>
          Handshake.Result.QueryResult(Vector(16 -> Right(local)))
        case Handshake.Result.Negotiated(_, data) => Handshake.Result.Negotiated(16, data)
        case rejected: Handshake.Result.Rejected  => rejected
      val (_, resultFromLocal) = Handshake
        .responder(Handshake.Suite.NodeToNode, Vector(16 -> remote), decodedLocal)
        .fold(e => fail(e.toString), identity)
      assertEquals(resultFromLocal, reverseExpected, s"seed $seed local to remote")
      assertEquals(fields(7), if local.magic == remote.magic then "accept" else "refuse")
      (resultFromRemote, fromRemote)
    }
    assertEquals(outcomes.size, 40)
    assertEquals(outcomes.count(_._1.isInstanceOf[Handshake.Result.Negotiated]), 1)
    assertEquals(outcomes.count(_._1.isInstanceOf[Handshake.Result.QueryResult]), 19)
    assertEquals(outcomes.count(_._1.isInstanceOf[Handshake.Result.Rejected]), 20)

  test(
    "consume all generated Cardano SockAddr PeerSharing CDDL messages without address projection"
  ):
    val input = rows(
      "cardano-peersharing-sockaddr.tsv",
      "d73da0b8a132ff46cfcea688775a8270ef360f77e9422b09a4f4483c041c6d4c"
    )
    val replayRows = Vector.newBuilder[String]
    val results = input.map { fields =>
      assertEquals(fields.size, 6)
      assertEquals(fields(0), "cardano-peersharing-sockaddr-cddl-v1")
      val version = fields(1).toInt
      val ordinal = fields(2).toInt
      assert(Set(14, 15, 16)(version))
      assert(ordinal >= 0 && ordinal < 100)
      assertEquals(fields(3), "pass", s"upstream CDDL case v$version/$ordinal")
      val original = bytes(fields(5))
      val raw = original.toArray
      assert(raw.length >= 2, s"truncated generated PeerSharing message v$version/$ordinal")
      val key = raw(1) & 0xff
      val (state, sender) = key match
        case 0     => (PeerSharing.State.Idle, PeerSharing.Role.Client)
        case 1     => (PeerSharing.State.Busy, PeerSharing.Role.Server)
        case 2     => (PeerSharing.State.Idle, PeerSharing.Role.Client)
        case other => fail(s"unknown upstream PeerSharing key $other at v$version/$ordinal")
      val message = PeerSharing
        .decode(state, sender, original)
        .fold(e => fail(s"v$version/$ordinal: $e"), identity)
      val scarabWire = PeerSharing
        .encode(state, sender, message)
        .fold(e => fail(s"v$version/$ordinal encode: $e"), identity)
      replayRows += s"$version\t$ordinal\t${fields(5)}\t${scarabWire.hex}"
      assertEquals(
        PeerSharing.decode(state, sender, scarabWire),
        Right(message),
        s"typed Cardano SockAddr message v$version/$ordinal"
      )
      message match
        case PeerSharing.Message.SharePeers(addresses) =>
          assert(addresses.forall {
            case PeerSharing.Address.V4(ip, port) =>
              PeerSharing.Word32
                .from(ip.value)
                .nonEmpty && PeerSharing.Port.from(port.value).nonEmpty
            case PeerSharing.Address.V6(a, b, c, d, port) =>
              Vector(a, b, c, d).forall(w => PeerSharing.Word32.from(w.value).nonEmpty) &&
              PeerSharing.Port.from(port.value).nonEmpty
          })
          "peers"
        case PeerSharing.Message.ShareRequest(_) => "request"
        case PeerSharing.Message.Done            => "done"
    }
    assertEquals(input.size, 300)
    for version <- Vector(14, 15, 16) do
      val ordinals = input.filter(_(1).toInt == version).map(_(2).toInt).sorted
      assertEquals(ordinals, (0 until 100).toVector, s"version $version CDDL ordinals")
    assertEquals(
      results.count(_ == "peers") + results.count(_ == "request") + results.count(_ == "done"),
      300
    )
    assertEquals(results.count(_ == "peers"), 100)
    assertEquals(results.count(_ == "request"), 103)
    assertEquals(results.count(_ == "done"), 97)
    sys.env.get("SCARAB_PEERSHARING_REPLAY_EXPORT").foreach { path =>
      Files.writeString(Path.of(path), replayRows.result().mkString("\n") + "\n")
    }

  test("classify generated pinned ChainSync Serialised-block rows at the typed boundary"):
    val input =
      rows("chainsync.tsv", "d2c840ebcb1f4b55b59055e8ef4b9ff55da80a0d0d428dc2b090c3c0c840224e")
    val payloadCodec = new ChainSync.PayloadCodec[Bytes]:
      def encode(value: Bytes): Either[ProtocolError, Bytes] = Right(value)
      def decode(value: Bytes): Either[ProtocolError, Bytes] = Right(value)

    assertEquals(input.size, 128)
    assertEquals(input.count(f => f.size == 10 && f(8) == "pass" && f(9) == "pass"), 128)
    val expectedConstructors = Map(
      "request-next" -> 14,
      "await-reply" -> 9,
      "roll-forward" -> 28,
      "roll-backward" -> 31,
      "find-intersect" -> 9,
      "intersect-found" -> 13,
      "intersect-not-found" -> 9,
      "done" -> 15
    )
    val expectedStates = Map(
      "idle" -> 38,
      "intersect" -> 22,
      "next-can-await" -> 37,
      "next-must-reply" -> 31
    )
    assertEquals(input.groupMapReduce(_(6))(_ => 1)(_ + _), expectedConstructors)
    assertEquals(input.groupMapReduce(_(4))(_ => 1)(_ + _), expectedStates)
    val mapped = input.map { fields =>
      assertEquals(fields.size, 10)
      assertEquals(fields(0), "chainsync-serialised-codec-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 491101 + ordinal)
      assertEquals(fields(3).toInt, 30 + ordinal % 21)
      val state = fields(4) match
        case "idle"            => ChainSync.State.Idle
        case "next-can-await"  => ChainSync.State.NextCanAwait
        case "next-must-reply" => ChainSync.State.NextMustReply
        case "intersect"       => ChainSync.State.Intersect
        case other             => fail(s"unknown upstream ChainSync state $other at seed $seed")
      val sender = fields(5) match
        case "client" => ChainSync.Role.Client
        case "server" => ChainSync.Role.Server
        case other    => fail(s"unknown upstream ChainSync sender $other at seed $seed")
      val wire = bytes(fields(7))
      val decoded = ChainSync.decodePrefix(state, sender, wire, payloadCodec) match
        case ChainSync.DecodeResult.Decoded(message, consumed) =>
          assertEquals(consumed, wire.size, s"seed $seed consumed full original wire")
          Right(message)
        case ChainSync.DecodeResult.Failed(error) => Left(error.toString)
        case ChainSync.DecodeResult.NeedMore      => Left("NeedMore")
      decoded match
        case Left(reason) => s"unsupported:${fields(6)}:$reason"
        case Right(message) =>
          val decodedTag = message match
            case ChainSync.Message.RequestNext          => "request-next"
            case ChainSync.Message.AwaitReply           => "await-reply"
            case ChainSync.Message.RollForward(_, _)    => "roll-forward"
            case ChainSync.Message.RollBackward(_, _)   => "roll-backward"
            case ChainSync.Message.FindIntersect(_)     => "find-intersect"
            case ChainSync.Message.IntersectFound(_, _) => "intersect-found"
            case ChainSync.Message.IntersectNotFound(_) => "intersect-not-found"
            case ChainSync.Message.Done                 => "done"
          assertEquals(decodedTag, fields(6), s"seed $seed typed constructor")
          val encoded = ChainSync
            .encode(state, sender, message, payloadCodec)
            .fold(e => fail(s"seed $seed Scarab typed encode: ${e.toString}"), identity)
          assertEquals(encoded, wire, s"seed $seed exact original wire roundtrip")
          "matched"
    }
    assertEquals(mapped.size, 128)
    assertEquals(
      mapped.groupMapReduce(identity)(_ => 1)(_ + _),
      Map(
        "matched" -> 49,
        "unsupported:roll-forward:ExpectedDefiniteByteString" -> 20,
        "unsupported:roll-backward:ExpectedDefiniteByteString" -> 29,
        "unsupported:intersect-not-found:ExpectedDefiniteByteString" -> 8,
        "unsupported:intersect-found:ExpectedDefiniteByteString" -> 13,
        "unsupported:find-intersect:ExpectedDefiniteByteString" -> 9
      )
    )

  test("classify generated pinned BlockFetch Serialised-block rows at the typed boundary"):
    val input =
      rows("blockfetch.tsv", "18c4e32a647e7287246b2ef2c6a874e82e565778e63352966645fb969e0e2e50")
    val payloadCodec = CardanoBlockFetch.payloadCodec()
    assertEquals(input.size, 24)
    assertEquals(input.count(f => f.size == 10 && f(8) == "pass" && f(9) == "pass"), 24)
    assertEquals(
      input.groupMapReduce(_(6))(_ => 1)(_ + _),
      Map(
        "block" -> 4,
        "client-done" -> 5,
        "no-blocks" -> 5,
        "request-range" -> 4,
        "start-batch" -> 6
      )
    )
    assertEquals(
      input.groupMapReduce(_(4))(_ => 1)(_ + _),
      Map("busy" -> 11, "idle" -> 9, "streaming" -> 4)
    )
    val outcomes = input.map { fields =>
      assertEquals(fields.size, 10)
      assertEquals(fields(0), "blockfetch-serialised-codec-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 490201 + ordinal)
      assertEquals(fields(3).toInt, 30 + ordinal % 21)
      val state = fields(4) match
        case "idle"      => BlockFetch.State.Idle
        case "busy"      => BlockFetch.State.Busy
        case "streaming" => BlockFetch.State.Streaming
        case other       => fail(s"unknown upstream BlockFetch state $other at seed $seed")
      val sender = fields(5) match
        case "client" => BlockFetch.Role.Client
        case "server" => BlockFetch.Role.Server
        case other    => fail(s"unknown upstream BlockFetch sender $other at seed $seed")
      val wire = bytes(fields(7))
      val decoded = BlockFetch.decodePrefix(state, sender, wire, payloadCodec) match
        case DecodeResult.Decoded(message, consumed) =>
          assertEquals(consumed, wire.size, s"seed $seed consumed original wire")
          Right(message)
        case DecodeResult.Failed(error) => Left(error.toString)
        case DecodeResult.NeedMore      => Left("NeedMore")
      decoded match
        case Left(reason) =>
          assertEquals(fields(6), "request-range", s"only mock Int hashes are unsupported at $seed")
          assertEquals(reason, "ExpectedDefiniteByteString", s"seed $seed typed reason")
          "unsupported:request-range:ExpectedDefiniteByteString"
        case Right(message) =>
          val decodedTag = message match
            case BlockFetch.Message.RequestRange(_, _) => "request-range"
            case BlockFetch.Message.ClientDone         => "client-done"
            case BlockFetch.Message.StartBatch         => "start-batch"
            case BlockFetch.Message.NoBlocks           => "no-blocks"
            case BlockFetch.Message.Block(_)           => "block"
            case BlockFetch.Message.BatchDone          => "batch-done"
          assertEquals(decodedTag, fields(6), s"seed $seed typed constructor")
          val encoded = BlockFetch
            .encode(state, sender, message, payloadCodec)
            .fold(e => fail(s"seed $seed Scarab typed encode: ${e.toString}"), identity)
          assertEquals(encoded, wire, s"seed $seed exact original wire roundtrip")
          "matched"
    }
    assertEquals(outcomes.size, 24)
    assertEquals(
      outcomes.groupMapReduce(identity)(_ => 1)(_ + _),
      Map("matched" -> 20, "unsupported:request-range:ExpectedDefiniteByteString" -> 4)
    )

  test("classify generated pinned LocalStateQuery codec rows at the typed boundary"):
    val input =
      rows("localsq.tsv", "09358cbf911717869def7225954a7b0148ad5128a48edcd3895f94d2b44ddd18")
    assertEquals(input.size, 24)
    assertEquals(input.count(f => f.size == 10 && f(8) == "pass" && f(9) == "pass"), 24)
    assertEquals(
      input.groupMapReduce(_(6))(_ => 1)(_ + _),
      Map(
        "acquire-specific-point" -> 1,
        "acquire-volatile-tip" -> 3,
        "acquired" -> 2,
        "done" -> 1,
        "failure-point-too-old" -> 1,
        "reacquire-immutable-tip" -> 3,
        "reacquire-specific-point" -> 5,
        "reacquire-volatile-tip" -> 5,
        "release" -> 1,
        "result" -> 2
      )
    )
    assertEquals(
      input.groupMapReduce(_(4))(_ => 1)(_ + _),
      Map("acquired" -> 14, "acquiring" -> 3, "idle" -> 5, "querying" -> 2)
    )
    val outcomes = input.map { fields =>
      assertEquals(fields.size, 10)
      assertEquals(fields(0), "localsq-codec-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 492001 + ordinal)
      assertEquals(fields(3).toInt, 30 + ordinal % 21)
      val state = fields(4) match
        case "idle"      => LocalStateQuery.State.Idle
        case "acquiring" => LocalStateQuery.State.Acquiring
        case "acquired"  => LocalStateQuery.State.Acquired
        case "querying"  => LocalStateQuery.State.Querying
        case other       => fail(s"unknown upstream LocalStateQuery state $other at seed $seed")
      val sender = fields(5) match
        case "client" => ChainSync.Role.Client
        case "server" => ChainSync.Role.Server
        case other    => fail(s"unknown upstream LocalStateQuery sender $other at seed $seed")
      val wire = bytes(fields(7))
      val decoded = LocalStateQuery.decodePrefix(state, sender, wire) match
        case DecodeResult.Decoded(message, consumed) =>
          assertEquals(consumed, wire.size, s"seed $seed consumed original wire")
          Right(message)
        case DecodeResult.Failed(error) => Left(error.toString)
        case DecodeResult.NeedMore      => Left("NeedMore")
      decoded match
        case Left(reason) =>
          assert(
            fields(6) == "acquire-specific-point" || fields(6) == "reacquire-specific-point",
            s"unexpected unsupported LocalStateQuery constructor ${fields(6)} at seed $seed"
          )
          assertEquals(reason, "ExpectedDefiniteByteString", s"seed $seed typed reason")
          s"unsupported:${fields(6)}:$reason"
        case Right(message) =>
          val decodedTag = message match
            case LocalStateQuery.Message.Acquire(LocalStateQuery.Target.Specific(_)) =>
              "acquire-specific-point"
            case LocalStateQuery.Message.Acquire(LocalStateQuery.Target.VolatileTip) =>
              "acquire-volatile-tip"
            case LocalStateQuery.Message.Acquire(LocalStateQuery.Target.ImmutableTip) =>
              "acquire-immutable-tip"
            case LocalStateQuery.Message.Acquired => "acquired"
            case LocalStateQuery.Message.Failed(LocalStateQuery.Failure.PointTooOld) =>
              "failure-point-too-old"
            case LocalStateQuery.Message.Failed(LocalStateQuery.Failure.PointNotOnChain) =>
              "failure-point-not-on-chain"
            case LocalStateQuery.Message.Query(_)  => "query"
            case LocalStateQuery.Message.Result(_) => "result"
            case LocalStateQuery.Message.Release   => "release"
            case LocalStateQuery.Message.ReAcquire(LocalStateQuery.Target.Specific(_)) =>
              "reacquire-specific-point"
            case LocalStateQuery.Message.ReAcquire(LocalStateQuery.Target.VolatileTip) =>
              "reacquire-volatile-tip"
            case LocalStateQuery.Message.ReAcquire(LocalStateQuery.Target.ImmutableTip) =>
              "reacquire-immutable-tip"
            case LocalStateQuery.Message.Done => "done"
          assertEquals(decodedTag, fields(6), s"seed $seed typed constructor")
          val encoded = LocalStateQuery
            .encode(state, sender, message)
            .fold(e => fail(s"seed $seed Scarab typed encode: ${e.toString}"), identity)
          assertEquals(encoded, wire, s"seed $seed exact original wire roundtrip")
          "matched"
    }
    assertEquals(outcomes.size, 24)
    assertEquals(
      outcomes.groupMapReduce(identity)(_ => 1)(_ + _),
      Map(
        "matched" -> 20,
        "unsupported:acquire-specific-point:ExpectedDefiniteByteString" -> 1,
        "unsupported:reacquire-specific-point:ExpectedDefiniteByteString" -> 3
      )
    )
