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

  test("consume upstream KeepAlive 2- and 3-split properties with Scarab prefix decoding"):
    val input = rows(
      "keepalive-splits.tsv",
      "fe810270669b209b0311dbff7c12e6a6c7d509cb3a3ba0e200cacb7de864db24"
    )
    var split2Partitions = 0
    var split3Partitions = 0
    val constructors = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    input.foreach { fields =>
      assertEquals(fields.size, 11)
      assertEquals(fields(0), "keepalive-splits-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 490201 + ordinal)
      assertEquals(fields(3).toInt, 30 + ordinal % 21)
      assertEquals(fields(9), "pass", s"upstream prop_codec_v2_splits2 seed $seed")
      assertEquals(fields(10), "pass", s"upstream prop_codec_v2_splits3 seed $seed")
      val state = fields(4) match
        case "client" => KeepAlive.State.Client
        case "server" => KeepAlive.State.Server
        case other    => fail(s"unknown KeepAlive state $other at seed $seed")
      val sender = fields(5) match
        case "client" => KeepAlive.Role.Client
        case "server" => KeepAlive.Role.Server
        case other    => fail(s"unknown KeepAlive sender $other at seed $seed")
      val message = fields(6) match
        case "request"  => KeepAlive.Message.Request(cookie(fields(7)))
        case "response" => KeepAlive.Message.Response(cookie(fields(7)))
        case "done"     => KeepAlive.Message.Done
        case other      => fail(s"unknown KeepAlive message $other at seed $seed")
      constructors.update(fields(6), constructors(fields(6)) + 1)
      val wire = bytes(fields(8))
      assert(wire.size >= 2, s"unexpected short KeepAlive wire at seed $seed")
      assertEquals(KeepAlive.encode(state, sender, message), Right(wire), s"seed $seed wire")

      def verifyPartition(parts: Vector[Vector[Byte]], label: String): Unit =
        var retained = Vector.empty[Byte]
        parts.foreach { fragment =>
          retained = retained ++ fragment
          val decoded = KeepAlive.decodePrefix(state, sender, Bytes(retained))
          if retained.size < wire.size then
            assertEquals(decoded, DecodeResult.NeedMore, s"seed $seed $label partial prefix")
          else
            assertEquals(
              decoded,
              DecodeResult.Decoded(message, wire.size),
              s"seed $seed $label complete prefix"
            )
        }
        assertEquals(retained, wire.value, s"seed $seed $label retained bytes")

      (0 to wire.size).foreach { cut =>
        verifyPartition(Vector(wire.value.take(cut), wire.value.drop(cut)), s"2-split@$cut")
        split2Partitions += 1
      }
      for
        firstCut <- 0 to wire.size
        secondCut <- firstCut to wire.size
      do
        verifyPartition(
          Vector(
            wire.value.take(firstCut),
            wire.value.slice(firstCut, secondCut),
            wire.value.drop(secondCut)
          ),
          s"3-split@$firstCut/$secondCut"
        )
        split3Partitions += 1

      // A nonempty two-fragment reorder is a negative control: the decoder must not
      // accept the original message after bytes arrive in the wrong order.
      val midpoint = wire.size / 2
      val left = wire.value.take(midpoint)
      val right = wire.value.drop(midpoint)
      assert(left.nonEmpty && right.nonEmpty, s"seed $seed negative-control fragments")
      val reordered = Bytes(right ++ left)
      KeepAlive.decodePrefix(state, sender, reordered) match
        case DecodeResult.Decoded(_, _) =>
          fail(s"seed $seed reordered nonempty fragments unexpectedly decoded: $reordered")
        case DecodeResult.NeedMore | DecodeResult.Failed(_) => ()
    }
    assertEquals(input.size, 24)
    assertEquals(split2Partitions, input.map(fields => bytes(fields(8)).size + 1).sum)
    assertEquals(
      split3Partitions,
      input.map { fields =>
        val n = bytes(fields(8)).size
        (n + 1) * (n + 2) / 2
      }.sum
    )
    assertEquals(constructors.toMap, Map("request" -> 7, "response" -> 10, "done" -> 7))

  private def verifyAllPartitions[A](
      wire: Bytes,
      expected: DecodeResult[A],
      decode: Bytes => DecodeResult[A],
      label: String
  ): (Int, Int) =
    val raw = wire.value.toVector
    def verify(parts: Vector[Vector[Byte]], partition: String): Unit =
      var retained = Vector.empty[Byte]
      parts.foreach { fragment =>
        retained = retained ++ fragment
        val result = decode(Bytes(retained))
        if retained.size < raw.size then
          assertEquals(result, DecodeResult.NeedMore, s"$label $partition incomplete prefix")
        else assertEquals(result, expected, s"$label $partition complete prefix")
      }
      assertEquals(retained, raw, s"$label $partition retained wire")

    var splits2 = 0
    var splits3 = 0
    (0 to raw.size).foreach { first =>
      verify(Vector(raw.take(first), raw.drop(first)), s"2-split@$first")
      splits2 += 1
    }
    for
      first <- 0 to raw.size
      second <- first to raw.size
    do
      verify(
        Vector(raw.take(first), raw.slice(first, second), raw.drop(second)),
        s"3-split@$first/$second"
      )
      splits3 += 1
    val truncated = decode(Bytes(raw.dropRight(1)))
    assertNotEquals(truncated, expected, s"$label truncated-wire negative control")
    (splits2, splits3)

  test("consume upstream LocalTxSubmission split properties through Scarab's prefix decoder"):
    val input = rows(
      "localtxsubmission-splits.tsv",
      "0a2ce345a9102aafbb5041353dcac1dc3a0410b54ab685ccfa4bd0b292f4b97d"
    )
    val constructors = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    var split2Count = 0
    var split3Count = 0
    input.foreach { fields =>
      assertEquals(fields.size, 10)
      assertEquals(fields(0), "local-tx-submission-splits-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 493001 + ordinal)
      assertEquals(fields(3).toInt, 30 + ordinal % 21)
      assertEquals(fields(8), "pass", s"upstream prop_codec_splits2 seed $seed")
      assertEquals(fields(9), "pass", s"upstream prop_codec_splits3 seed $seed")
      val state = fields(4) match
        case "idle" => LocalTxSubmission.State.Idle
        case "busy" => LocalTxSubmission.State.Busy
        case other  => fail(s"unknown LocalTxSubmission state $other at seed $seed")
      val sender = fields(5) match
        case "client" => ChainSync.Role.Client
        case "server" => ChainSync.Role.Server
        case other    => fail(s"unknown LocalTxSubmission sender $other at seed $seed")
      val wire = bytes(fields(7))
      val message = LocalTxSubmission.decodePrefix(state, sender, wire) match
        case DecodeResult.Decoded(value, consumed) =>
          assertEquals(consumed, wire.size, s"seed $seed consumed length")
          value
        case other => fail(s"seed $seed full LocalTxSubmission wire did not decode: $other")
      val tag = message match
        case LocalTxSubmission.Message.SubmitTx(_) => "submit-tx"
        case LocalTxSubmission.Message.AcceptTx    => "accept-tx"
        case LocalTxSubmission.Message.RejectTx(_) => "reject-tx"
        case LocalTxSubmission.Message.Done        => "done"
      assertEquals(tag, fields(6), s"seed $seed constructor")
      constructors.update(tag, constructors(tag) + 1)
      assertEquals(
        LocalTxSubmission.encode(state, sender, message),
        Right(wire),
        s"seed $seed wire"
      )
      val expected = DecodeResult.Decoded(message, wire.size)
      val counts = verifyAllPartitions(
        wire,
        expected,
        prefix => LocalTxSubmission.decodePrefix(state, sender, prefix),
        s"seed $seed"
      )
      split2Count += counts._1
      split3Count += counts._2
    }
    assertEquals(input.size, 24)
    assertEquals(split2Count, input.map(fields => bytes(fields(7)).size + 1).sum)
    assertEquals(
      split3Count,
      input.map { fields =>
        val n = bytes(fields(7)).size
        (n + 1) * (n + 2) / 2
      }.sum
    )
    assertEquals(
      constructors.toMap,
      Map("accept-tx" -> 4, "done" -> 7, "reject-tx" -> 7, "submit-tx" -> 6)
    )

  test("consume upstream LocalTxMonitor split properties through Scarab's prefix decoder"):
    val input = rows(
      "localtxmonitor-splits.tsv",
      "774026794eabf01ad8eaa73015b6c63a2057bfbd20c64204171483bb86e018b5"
    )
    val constructors = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    var split2Count = 0
    var split3Count = 0
    input.foreach { fields =>
      assertEquals(fields.size, 10)
      assertEquals(fields(0), "local-tx-monitor-splits-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 494001 + ordinal)
      assertEquals(fields(3).toInt, 30 + ordinal % 21)
      assertEquals(
        fields(8),
        "pass",
        s"upstream prop_codec_splitsM_LocalTxMonitor splits2 seed $seed"
      )
      assertEquals(
        fields(9),
        "pass",
        s"upstream prop_codec_splitsM_LocalTxMonitor splits3 seed $seed"
      )
      val state = fields(4) match
        case "idle"              => LocalTxMonitor.State.Idle
        case "acquiring"         => LocalTxMonitor.State.Acquiring
        case "acquired"          => LocalTxMonitor.State.Acquired
        case "busy-next-tx"      => LocalTxMonitor.State.Busy(LocalTxMonitor.Kind.NextTx)
        case "busy-has-tx"       => LocalTxMonitor.State.Busy(LocalTxMonitor.Kind.HasTx)
        case "busy-get-sizes"    => LocalTxMonitor.State.Busy(LocalTxMonitor.Kind.GetSizes)
        case "busy-get-measures" => LocalTxMonitor.State.Busy(LocalTxMonitor.Kind.GetMeasures)
        case other               => fail(s"unknown LocalTxMonitor state $other at seed $seed")
      val sender = fields(5) match
        case "client" => ChainSync.Role.Client
        case "server" => ChainSync.Role.Server
        case other    => fail(s"unknown LocalTxMonitor sender $other at seed $seed")
      val wire = bytes(fields(7))
      val message = LocalTxMonitor.decodePrefix(state, sender, wire) match
        case DecodeResult.Decoded(value, consumed) =>
          assertEquals(consumed, wire.size, s"seed $seed consumed length")
          value
        case other => fail(s"seed $seed full LocalTxMonitor wire did not decode: $other")
      val tag = message match
        case LocalTxMonitor.Message.Acquire             => "acquire"
        case LocalTxMonitor.Message.Acquired(_)         => "acquired"
        case LocalTxMonitor.Message.AwaitAcquire        => "await-acquire"
        case LocalTxMonitor.Message.NextTx              => "next-tx"
        case LocalTxMonitor.Message.ReplyNextTx(_)      => "reply-next-tx"
        case LocalTxMonitor.Message.HasTx(_)            => "has-tx"
        case LocalTxMonitor.Message.ReplyHasTx(_)       => "reply-has-tx"
        case LocalTxMonitor.Message.GetSizes            => "get-sizes"
        case LocalTxMonitor.Message.ReplyGetSizes(_)    => "reply-get-sizes"
        case LocalTxMonitor.Message.GetMeasures         => "get-measures"
        case LocalTxMonitor.Message.ReplyGetMeasures(_) => "reply-get-measures"
        case LocalTxMonitor.Message.Release             => "release"
        case LocalTxMonitor.Message.Done                => "done"
      assertEquals(tag, fields(6), s"seed $seed constructor")
      constructors.update(tag, constructors(tag) + 1)
      assertEquals(LocalTxMonitor.encode(state, sender, message), Right(wire), s"seed $seed wire")
      val expected = DecodeResult.Decoded(message, wire.size)
      val counts = verifyAllPartitions(
        wire,
        expected,
        prefix => LocalTxMonitor.decodePrefix(state, sender, prefix),
        s"seed $seed"
      )
      split2Count += counts._1
      split3Count += counts._2
    }
    assertEquals(input.size, 24)
    assertEquals(split2Count, input.map(fields => bytes(fields(7)).size + 1).sum)
    assertEquals(
      split3Count,
      input.map { fields =>
        val n = bytes(fields(7)).size
        (n + 1) * (n + 2) / 2
      }.sum
    )
    assertEquals(
      constructors.toMap,
      Map(
        "acquire" -> 1,
        "acquired" -> 1,
        "await-acquire" -> 2,
        "get-measures" -> 1,
        "get-sizes" -> 5,
        "has-tx" -> 2,
        "next-tx" -> 1,
        "release" -> 1,
        "reply-get-measures" -> 2,
        "reply-get-sizes" -> 1,
        "reply-has-tx" -> 3,
        "reply-next-tx" -> 4
      )
    )

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

  test(
    "consume upstream LocalStateQuery split properties through Scarab's stateful prefix decoder"
  ):
    val input = rows(
      "localsq-splits.tsv",
      "411ff97764d6e81748ecc4772a5bed02fffdb91dc3e8d5451f9197e58f5bc7b0"
    )
    val completeWire = rows(
      "localsq.tsv",
      "09358cbf911717869def7225954a7b0148ad5128a48edcd3895f94d2b44ddd18"
    )
    assertEquals(input.size, 24)
    assertEquals(completeWire.size, 24)
    assertEquals(
      input.count(f => f.size == 10 && f(8) == "pass" && f(9) == "pass"),
      24
    )
    assertEquals(
      input.groupMapReduce(_(6))(_ => 1)(_ + _),
      completeWire.groupMapReduce(_(6))(_ => 1)(_ + _)
    )
    val constructors = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    var split2Count = 0
    var split3Count = 0
    val outcomes = input.zip(completeWire).map { (fields, baseFields) =>
      assertEquals(fields.size, 10)
      assertEquals(fields(0), "localsq-splits-v1")
      assertEquals(baseFields(0), "localsq-codec-v1")
      assertEquals(
        fields.slice(1, 8),
        baseFields.slice(1, 8),
        "split observer preserves the original generated row"
      )
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 492001 + ordinal)
      assertEquals(fields(3).toInt, 30 + ordinal % 21)
      assertEquals(fields(8), "pass", s"upstream prop_codec_splits2 seed $seed")
      assertEquals(fields(9), "pass", s"upstream prop_codec_splits3 seed $seed")
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
      val full = LocalStateQuery.decodePrefix(state, sender, wire) match
        case DecodeResult.Decoded(message, consumed) =>
          assertEquals(consumed, wire.size, s"seed $seed consumed original wire")
          Right(message)
        case DecodeResult.Failed(error) => Left(error.toString)
        case DecodeResult.NeedMore      => Left("NeedMore")
      full match
        case Left(reason) =>
          assert(
            fields(6) == "acquire-specific-point" || fields(6) == "reacquire-specific-point",
            s"unexpected unsupported LocalStateQuery constructor ${fields(6)} at seed $seed"
          )
          assertEquals(reason, "ExpectedDefiniteByteString", s"seed $seed typed reason")
          s"unsupported:${fields(6)}:$reason"
        case Right(message) =>
          val tag = message match
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
          assertEquals(tag, fields(6), s"seed $seed typed constructor")
          constructors.update(tag, constructors(tag) + 1)
          val encoded = LocalStateQuery
            .encode(state, sender, message)
            .fold(e => fail(s"seed $seed Scarab typed encode: ${e.toString}"), identity)
          assertEquals(encoded, wire, s"seed $seed exact original wire roundtrip")
          val counts = verifyAllPartitions(
            wire,
            DecodeResult.Decoded(message, wire.size),
            prefix => LocalStateQuery.decodePrefix(state, sender, prefix),
            s"seed $seed"
          )
          split2Count += counts._1
          split3Count += counts._2
          "matched"
    }
    assertEquals(
      outcomes.groupMapReduce(identity)(_ => 1)(_ + _),
      Map(
        "matched" -> 20,
        "unsupported:acquire-specific-point:ExpectedDefiniteByteString" -> 1,
        "unsupported:reacquire-specific-point:ExpectedDefiniteByteString" -> 3
      )
    )
    val supportedRows = outcomes.zip(input).collect { case ("matched", fields) => fields }
    assertEquals(supportedRows.size, 20)
    assertEquals(split2Count, supportedRows.map(fields => bytes(fields(7)).size + 1).sum)
    assertEquals(
      split3Count,
      supportedRows.map { fields =>
        val n = bytes(fields(7)).size
        (n + 1) * (n + 2) / 2
      }.sum
    )
    assertEquals(constructors.values.sum, 20)

  test("consume actual generated LocalTxSubmission codec cases at the typed boundary"):
    val input = rows(
      "localtxsubmission.tsv",
      "003b00977beb431d5e9b59712af67f026b10d9a684daa37a208fc1c4a4cf88d1"
    )
    assertEquals(input.size, 24)
    assertEquals(input.count(f => f.size == 10 && f(8) == "pass" && f(9) == "pass"), 24)
    assertEquals(
      input.groupMapReduce(_(6))(_ => 1)(_ + _),
      Map("accept-tx" -> 4, "done" -> 7, "reject-tx" -> 7, "submit-tx" -> 6)
    )
    assertEquals(input.groupMapReduce(_(4))(_ => 1)(_ + _), Map("busy" -> 11, "idle" -> 13))
    val outcomes = input.map { fields =>
      assertEquals(fields.size, 10)
      assertEquals(fields(0), "local-tx-submission-codec-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 493001 + ordinal)
      assertEquals(fields(3).toInt, 30 + ordinal % 21)
      val state = fields(4) match
        case "idle" => LocalTxSubmission.State.Idle
        case "busy" => LocalTxSubmission.State.Busy
        case other  => fail(s"unknown upstream LocalTxSubmission state $other at seed $seed")
      val sender = fields(5) match
        case "client" => ChainSync.Role.Client
        case "server" => ChainSync.Role.Server
        case other    => fail(s"unknown upstream LocalTxSubmission sender $other at seed $seed")
      val wire = bytes(fields(7))
      LocalTxSubmission.decodePrefix(state, sender, wire) match
        case DecodeResult.Decoded(message, consumed) =>
          assertEquals(consumed, wire.size, s"seed $seed consumed original wire")
          val tag = message match
            case LocalTxSubmission.Message.SubmitTx(_) => "submit-tx"
            case LocalTxSubmission.Message.AcceptTx    => "accept-tx"
            case LocalTxSubmission.Message.RejectTx(_) => "reject-tx"
            case LocalTxSubmission.Message.Done        => "done"
          assertEquals(tag, fields(6), s"seed $seed typed constructor")
          val encoded = LocalTxSubmission
            .encode(state, sender, message)
            .fold(e => fail(s"seed $seed Scarab typed encode: ${e.toString}"), identity)
          assertEquals(encoded, wire, s"seed $seed exact original wire roundtrip")
          "matched"
        case DecodeResult.Failed(error) =>
          fail(s"seed $seed typed decode failed: ${error.toString}")
        case DecodeResult.NeedMore => fail(s"seed $seed incomplete original wire")
    }
    assertEquals(outcomes.size, 24)
    assertEquals(outcomes.count(_ == "matched"), 24)

  test("consume generated LocalTxMonitor messages at the typed Scarab boundary"):
    val input =
      rows("localtxmonitor.tsv", "0baa7b0b184ffb0bf5ed502c784b10002f4f37d8a5318d96d809762944e5f58b")
    assertEquals(input.size, 24)
    assertEquals(input.count(f => f.size == 10 && f(8) == "pass" && f(9) == "pass"), 24)
    assertEquals(
      input.groupMapReduce(_(6))(_ => 1)(_ + _),
      Map(
        "acquire" -> 1,
        "acquired" -> 1,
        "await-acquire" -> 2,
        "get-measures" -> 1,
        "get-sizes" -> 5,
        "has-tx" -> 2,
        "next-tx" -> 1,
        "release" -> 1,
        "reply-get-measures" -> 2,
        "reply-get-sizes" -> 1,
        "reply-has-tx" -> 3,
        "reply-next-tx" -> 4
      )
    )
    val outcomes = input.map { fields =>
      assertEquals(fields.size, 10)
      assertEquals(fields(0), "local-tx-monitor-codec-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 494001 + ordinal)
      assertEquals(fields(3).toInt, 30 + ordinal % 21)
      val state = fields(4) match
        case "idle"              => LocalTxMonitor.State.Idle
        case "acquiring"         => LocalTxMonitor.State.Acquiring
        case "acquired"          => LocalTxMonitor.State.Acquired
        case "busy-next-tx"      => LocalTxMonitor.State.Busy(LocalTxMonitor.Kind.NextTx)
        case "busy-has-tx"       => LocalTxMonitor.State.Busy(LocalTxMonitor.Kind.HasTx)
        case "busy-get-sizes"    => LocalTxMonitor.State.Busy(LocalTxMonitor.Kind.GetSizes)
        case "busy-get-measures" => LocalTxMonitor.State.Busy(LocalTxMonitor.Kind.GetMeasures)
        case other => fail(s"unknown upstream LocalTxMonitor state $other at seed $seed")
      val sender = fields(5) match
        case "client" => ChainSync.Role.Client
        case "server" => ChainSync.Role.Server
        case other    => fail(s"unknown upstream LocalTxMonitor sender $other at seed $seed")
      val wire = bytes(fields(7))
      LocalTxMonitor.decodePrefix(state, sender, wire) match
        case DecodeResult.Decoded(message, consumed) =>
          assertEquals(consumed, wire.size, s"seed $seed consumed original wire")
          val tag = message match
            case LocalTxMonitor.Message.Acquire             => "acquire"
            case LocalTxMonitor.Message.Acquired(_)         => "acquired"
            case LocalTxMonitor.Message.AwaitAcquire        => "await-acquire"
            case LocalTxMonitor.Message.NextTx              => "next-tx"
            case LocalTxMonitor.Message.ReplyNextTx(_)      => "reply-next-tx"
            case LocalTxMonitor.Message.HasTx(_)            => "has-tx"
            case LocalTxMonitor.Message.ReplyHasTx(_)       => "reply-has-tx"
            case LocalTxMonitor.Message.GetSizes            => "get-sizes"
            case LocalTxMonitor.Message.ReplyGetSizes(_)    => "reply-get-sizes"
            case LocalTxMonitor.Message.GetMeasures         => "get-measures"
            case LocalTxMonitor.Message.ReplyGetMeasures(_) => "reply-get-measures"
            case LocalTxMonitor.Message.Release             => "release"
            case LocalTxMonitor.Message.Done                => "done"
          assertEquals(tag, fields(6), s"seed $seed typed constructor")
          val encoded = LocalTxMonitor
            .encode(state, sender, message)
            .fold(e => fail(s"seed $seed Scarab typed encode: ${e.toString}"), identity)
          assertEquals(encoded, wire, s"seed $seed exact original wire roundtrip")
          "matched"
        case DecodeResult.Failed(error) =>
          fail(s"seed $seed typed decode failed: ${error.toString}")
        case DecodeResult.NeedMore => fail(s"seed $seed incomplete original wire")
    }
    assertEquals(outcomes.size, 24)
    assertEquals(outcomes.count(_ == "matched"), 24)

  test("classify actual generated TxSubmission2 rows at the Conway typed boundary"):
    val input =
      rows("txsubmission2.tsv", "148ef49de27138d1b2bf5d46b02ea944e3fe9fde1a646baf6b42304f0f909e41")
    assertEquals(input.size, 24)
    assertEquals(input.count(f => f.size == 10 && f(8) == "pass" && f(9) == "pass"), 24)
    assertEquals(
      input.groupMapReduce(_(6))(_ => 1)(_ + _),
      Map(
        "done" -> 5,
        "init" -> 4,
        "reply-ids" -> 3,
        "reply-txs" -> 4,
        "request-ids-blocking" -> 2,
        "request-ids-nonblocking" -> 2,
        "request-txs" -> 4
      )
    )
    assertEquals(
      input.groupMapReduce(_(4))(_ => 1)(_ + _),
      Map("idle" -> 8, "ids-blocking" -> 8, "init" -> 4, "txs" -> 4)
    )
    val outcomes = input.map { fields =>
      assertEquals(fields.size, 10)
      assertEquals(fields(0), "txsubmission2-codec-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 495001 + ordinal)
      assertEquals(fields(3).toInt, 30 + ordinal % 21)
      val state = fields(4) match
        case "init"            => TxSubmission2.State.Init
        case "idle"            => TxSubmission2.State.Idle
        case "ids-blocking"    => TxSubmission2.State.Ids(blocking = true)
        case "ids-nonblocking" => TxSubmission2.State.Ids(blocking = false)
        case "txs"             => TxSubmission2.State.Txs
        case other             => fail(s"unknown upstream TxSubmission2 state $other at seed $seed")
      val sender = fields(5) match
        case "client" => ChainSync.Role.Client
        case "server" => ChainSync.Role.Server
        case other    => fail(s"unknown upstream TxSubmission2 sender $other at seed $seed")
      val wire = bytes(fields(7))
      TxSubmission2.decodePrefix(state, sender, wire) match
        case DecodeResult.Decoded(message, consumed) =>
          assertEquals(consumed, wire.size, s"seed $seed consumed original wire")
          val tag = message match
            case TxSubmission2.Message.Init => "init"
            case TxSubmission2.Message.RequestIds(blocking, _, _) =>
              if blocking then "request-ids-blocking" else "request-ids-nonblocking"
            case TxSubmission2.Message.ReplyIds(_)   => "reply-ids"
            case TxSubmission2.Message.RequestTxs(_) => "request-txs"
            case TxSubmission2.Message.ReplyTxs(_)   => "reply-txs"
            case TxSubmission2.Message.Done          => "done"
          assertEquals(tag, fields(6), s"seed $seed typed constructor")
          val encoded = TxSubmission2
            .encode(state, sender, message)
            .fold(e => fail(s"seed $seed Scarab typed encode: ${e.toString}"), identity)
          assertEquals(encoded, wire, s"seed $seed exact original wire roundtrip")
          "matched"
        case DecodeResult.Failed(error) => s"unsupported:${fields(6)}:${error.productPrefix}"
        case DecodeResult.NeedMore      => "unsupported:NeedMore"
    }
    assertEquals(outcomes.size, 24)
    assertEquals(
      outcomes.groupMapReduce(identity)(_ => 1)(_ + _),
      Map(
        "matched" -> 13,
        "unsupported:reply-ids:ExpectedDefiniteArray" -> 3,
        "unsupported:reply-txs:ExpectedDefiniteArray" -> 4,
        "unsupported:request-txs:ExpectedDefiniteArray" -> 4
      )
    )
