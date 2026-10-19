// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.io.Source
import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.DecodeResult

private enum ChainSyncSplitCoverage:
  case Matched(twoPartitions: Int, threePartitions: Int)
  case Unsupported(error: ProtocolError)

private enum BinaryCodecDirection:
  case WrappedToSerialised, SerialisedToWrapped

private enum BinaryCodecCoverage:
  case Matched(direction: BinaryCodecDirection)
  case UnsupportedMockHash(direction: BinaryCodecDirection, error: ProtocolError)

private enum HandshakeRejectionCoverage:
  case VersionMismatch
  case DecodeError
  case Refused

private enum HandshakeOutcomeCoverage:
  case Negotiated(version: Int, data: Handshake.Data)
  case QueryReply
  case Rejected(reason: HandshakeRejectionCoverage)

private enum QueryVersionCoverage:
  case QueryReply
  case VersionMismatch
  case DecodeError
  case Refused
  case PreconditionFilteredEmptyMap

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

  private def binaryDirection(value: String): BinaryCodecDirection = value match
    case "codec/codecSerialised" => BinaryCodecDirection.WrappedToSerialised
    case "codecSerialised/codec" => BinaryCodecDirection.SerialisedToWrapped
    case other                   => fail(s"unknown binary compatibility direction $other")

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
      rows("keepalive.tsv", "eacd61861245f5c5061fa66230dc152585915b91c7ffac2fe7d7239ca2f7b0bf")
    val matches = input.map { fields =>
      assertEquals(fields.size, 11)
      assertEquals(fields(0), "keepalive-codec-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      val size = fields(3).toInt
      assertEquals(seed, 490201 + ordinal)
      assertEquals(size, 30 + ordinal % 21)
      assertEquals(fields(9), "pass", s"upstream property seed $seed")
      assertEquals(fields(10), "pass", s"upstream valid-CBOR property seed $seed")
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
    val partitions = input.map { fields =>
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
      val wire = bytes(fields(8))
      assert(wire.size >= 2, s"unexpected short KeepAlive wire at seed $seed")
      assertEquals(KeepAlive.encode(state, sender, message), Right(wire), s"seed $seed wire")

      val counts = verifyAllPartitions(
        wire,
        DecodeResult.Decoded(message, wire.size),
        prefix => KeepAlive.decodePrefix(state, sender, prefix),
        s"seed $seed"
      )

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
      counts
    }
    assertEquals(input.size, 24)
    assertEquals(partitions.map(_._1).sum, input.map(fields => bytes(fields(8)).size + 1).sum)
    assertEquals(
      partitions.map(_._2).sum,
      input.map { fields =>
        val n = bytes(fields(8)).size
        (n + 1) * (n + 2) / 2
      }.sum
    )
    assertEquals(
      input.groupMapReduce(_(6))(_ => 1)(_ + _),
      Map("request" -> 7, "response" -> 10, "done" -> 7)
    )

  private def verifyAllPartitions[A](
      wire: Bytes,
      expected: DecodeResult[A],
      decode: Bytes => DecodeResult[A],
      label: String
  ): (Int, Int) =
    val raw = wire.value.toVector
    def verify(parts: Vector[Vector[Byte]], partition: String): Unit =
      val retainedPrefixes = parts.scanLeft(Vector.empty[Byte])(_ ++ _).tail
      retainedPrefixes.zipWithIndex.foreach { (retained, index) =>
        val result = decode(Bytes(retained))
        if retained.size < raw.size then
          assertEquals(result, DecodeResult.NeedMore, s"$label $partition prefix $index incomplete")
        else assertEquals(result, expected, s"$label $partition prefix $index complete")
      }
      assertEquals(
        retainedPrefixes.lastOption.getOrElse(Vector.empty),
        raw,
        s"$label $partition retained wire"
      )

    val splits2 = (0 to raw.size).map { first =>
      verify(Vector(raw.take(first), raw.drop(first)), s"2-split@$first")
      1
    }.sum
    val splits3 = (for
      first <- 0 to raw.size
      second <- first to raw.size
    yield
      verify(
        Vector(raw.take(first), raw.slice(first, second), raw.drop(second)),
        s"3-split@$first/$second"
      )
      1
    ).sum
    val truncated = decode(Bytes(raw.dropRight(1)))
    assertNotEquals(truncated, expected, s"$label truncated-wire negative control")
    (splits2, splits3)

  private def verifyChainSyncPartitions[A](
      wire: Bytes,
      expected: ChainSync.DecodeResult[A],
      decode: Bytes => ChainSync.DecodeResult[A],
      label: String
  ): (Int, Int) =
    val raw = wire.value.toVector
    def verify(parts: Vector[Vector[Byte]], partition: String): Unit =
      val retainedPrefixes = parts.scanLeft(Vector.empty[Byte])(_ ++ _).tail
      retainedPrefixes.zipWithIndex.foreach { (retained, index) =>
        val result = decode(Bytes(retained))
        if retained.size < raw.size then
          assertEquals(result, ChainSync.DecodeResult.NeedMore, s"$label $partition prefix $index")
        else assertEquals(result, expected, s"$label $partition complete prefix $index")
      }
      assertEquals(retainedPrefixes.lastOption.getOrElse(Vector.empty), raw, s"$label wire")

    val twoPartitions = (0 to raw.size).map { first =>
      verify(Vector(raw.take(first), raw.drop(first)), s"2-split@$first")
      1
    }.sum
    val threePartitions = (for
      first <- 0 to raw.size
      second <- first to raw.size
    yield
      verify(
        Vector(raw.take(first), raw.slice(first, second), raw.drop(second)),
        s"3-split@$first/$second"
      )
      1
    ).sum
    assertNotEquals(
      decode(Bytes(raw.dropRight(1))),
      expected,
      s"$label truncated-wire negative control"
    )
    (twoPartitions, threePartitions)

  test("consume upstream LocalTxSubmission split properties through Scarab's prefix decoder"):
    val input = rows(
      "localtxsubmission-splits.tsv",
      "0a2ce345a9102aafbb5041353dcac1dc3a0410b54ab685ccfa4bd0b292f4b97d"
    )
    val partitionCounts = input.map { fields =>
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
      counts
    }
    assertEquals(input.size, 24)
    assertEquals(partitionCounts.map(_._1).sum, input.map(fields => bytes(fields(7)).size + 1).sum)
    assertEquals(
      partitionCounts.map(_._2).sum,
      input.map { fields =>
        val n = bytes(fields(7)).size
        (n + 1) * (n + 2) / 2
      }.sum
    )
    assertEquals(
      input.groupMapReduce(_(6))(_ => 1)(_ + _),
      Map("accept-tx" -> 4, "done" -> 7, "reject-tx" -> 7, "submit-tx" -> 6)
    )

  test("consume upstream LocalTxMonitor split properties through Scarab's prefix decoder"):
    val input = rows(
      "localtxmonitor-splits.tsv",
      "774026794eabf01ad8eaa73015b6c63a2057bfbd20c64204171483bb86e018b5"
    )
    val partitionCounts = input.map { fields =>
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
      assertEquals(LocalTxMonitor.encode(state, sender, message), Right(wire), s"seed $seed wire")
      val expected = DecodeResult.Decoded(message, wire.size)
      val counts = verifyAllPartitions(
        wire,
        expected,
        prefix => LocalTxMonitor.decodePrefix(state, sender, prefix),
        s"seed $seed"
      )
      counts
    }
    assertEquals(input.size, 24)
    assertEquals(partitionCounts.map(_._1).sum, input.map(fields => bytes(fields(7)).size + 1).sum)
    assertEquals(
      partitionCounts.map(_._2).sum,
      input.map { fields =>
        val n = bytes(fields(7)).size
        (n + 1) * (n + 2) / 2
      }.sum
    )
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

  test("retain and classify every seeded PeerSharing row, mapping only typed-compatible messages"):
    val input =
      rows("peersharing.tsv", "f68fcdbd0e087c87b57600303b0ab9e32877675b4c30397215354e87ecc65620")
    assertEquals(
      input.groupMapReduce(_(8))(_ => 1)(_ + _),
      Map("candidate" -> 18, "unsupported-int-addresses" -> 6)
    )
    val outcomes = input.map { fields =>
      assertEquals(fields.size, 15)
      assertEquals(fields(0), "peersharing-codec-int-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 490201 + ordinal)
      assertEquals(fields(3).toInt, 30 + ordinal % 21)
      assertEquals(fields(10), "pass", s"upstream codec property seed $seed")
      assertEquals(fields(11), "pass", s"upstream CBOR roundtrip property seed $seed")
      assertEquals(fields(12), "pass", s"upstream valid-CBOR property seed $seed")
      assertEquals(fields(13), "pass", s"upstream split-2 property seed $seed")
      assertEquals(fields(14), "pass", s"upstream split-3 property seed $seed")
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
          assertEquals(PeerSharing.encode(state, sender, message), Right(wire), s"seed $seed wire")
          val expected = DecodeResult.Decoded(message, wire.size)
          assertEquals(PeerSharing.decoder(state, sender)(wire), expected, s"seed $seed decode")
          val counts = verifyAllPartitions(
            wire,
            expected,
            prefix => PeerSharing.decoder(state, sender)(prefix),
            s"PeerSharing seed $seed"
          )
          val midpoint = wire.size / 2
          val reordered = Bytes(wire.value.drop(midpoint) ++ wire.value.take(midpoint))
          assert(wire.value.take(midpoint).nonEmpty && wire.value.drop(midpoint).nonEmpty)
          PeerSharing.decoder(state, sender)(reordered) match
            case DecodeResult.Decoded(_, _) => fail(s"seed $seed reordered wire decoded")
            case DecodeResult.NeedMore | DecodeResult.Failed(_) => ()
          ("match", Some(counts))
        case "unsupported-int-addresses" =>
          assertEquals(fields(4), "busy")
          assertEquals(fields(5), "server")
          assertEquals(fields(6), "peers")
          // The Haskell-generated Int list and its original CBOR are preserved in this row. There
          // is no honest conversion to Scarab's typed Address enum.
          assert(wire.size > 0, s"missing original peer-list bytes at seed $seed")
          PeerSharing.decoder(PeerSharing.State.Busy, PeerSharing.Role.Server)(wire) match
            case DecodeResult.Failed(
                  ProtocolError.PeerSharing(
                    PeerSharing.Error.Unexpected(PeerSharing.Expectation.DefiniteArray, _)
                  )
                ) =>
              ()
            case other => fail(s"seed $seed expected typed Int-address error, found $other")
          ("unsupported", Option.empty[(Int, Int)])
        case other => fail(s"unknown PeerSharing adapter status $other at seed $seed")
    }
    assertEquals(outcomes.size, 24)
    assertEquals(
      outcomes.groupMapReduce(_._1)(_ => 1)(_ + _),
      Map("match" -> 18, "unsupported" -> 6)
    )
    val mappedPartitions = outcomes.flatMap(_._2)
    assertEquals(
      mappedPartitions.map(_._1).sum,
      input.filter(_(8) == "candidate").map(fields => bytes(fields(9)).size + 1).sum
    )
    assertEquals(
      mappedPartitions.map(_._2).sum,
      input
        .filter(_(8) == "candidate")
        .map { fields =>
          val size = bytes(fields(9)).size
          (size + 1) * (size + 2) / 2
        }
        .sum
    )

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

  test("replay Cardano query-version ST cases through the typed Handshake client and responder"):
    val input = rows(
      "cardano-handshake-query.tsv",
      "c7d73ff87683b26013acece7b1eea01dfec83bd39a7860f466ec9a20f6520de0"
    )
    def offers(message: Handshake.Message): Vector[(Int, Handshake.Data)] =
      val entries = message match
        case Handshake.Message.Propose(vs)    => vs
        case Handshake.Message.QueryReply(vs) => vs
        case other                            => fail(s"expected a version map, got $other")
      entries.map { case (version, data) =>
        version -> Handshake
          .decodeData(Handshake.Suite.NodeToNode, version, data)
          .fold(e => fail(e.message), identity)
      }

    val replay = input.map { fields =>
      assertEquals(fields.size, 8)
      assertEquals(fields(0), "cardano-handshake-query-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 498101 + ordinal)
      assertEquals(fields(3).toInt, 60 + ordinal % 31)
      assertEquals(fields(4), "pass", s"unchanged upstream query-version ST property at seed $seed")
      val originalClientWire = bytes(fields(5))
      val queryClientWire = bytes(fields(6))
      val serverReplyWire = bytes(fields(7))
      val originalClient = Handshake
        .decode(Handshake.Suite.NodeToNode, Handshake.State.Propose, originalClientWire)
        .fold(e => fail(e.message), identity)
      val queryClient = Handshake
        .decode(Handshake.Suite.NodeToNode, Handshake.State.Propose, queryClientWire)
        .fold(e => fail(e.message), identity)
      val serverProposal = Handshake
        .decode(Handshake.Suite.NodeToNode, Handshake.State.Propose, serverReplyWire)
        .fold(e => fail(e.message), identity)
      val originalOffers = offers(originalClient)
      val clientOffers = offers(queryClient)
      val serverOffers = offers(serverProposal)
      assertEquals(
        clientOffers,
        originalOffers.map { case (version, data) =>
          version -> data.copy(query = true, peerSharing = true)
        },
        s"exact upstream query transform at seed $seed"
      )
      val (_, proposal) = Handshake
        .clientStart(Handshake.Suite.NodeToNode, clientOffers)
        .fold(e => fail(e.toString), identity)
      assertEquals(
        Handshake
          .encode(Handshake.Suite.NodeToNode, proposal)
          .fold(e => fail(e.toString), identity),
        queryClientWire,
        s"Cardano client proposal wire at seed $seed"
      )
      val (answer, serverResult) = Handshake
        .responder(Handshake.Suite.NodeToNode, serverOffers, proposal)
        .fold(e => fail(e.toString), identity)
      serverResult match
        case Handshake.Result.QueryResult(_) =>
          QueryVersionCoverage.QueryReply
        case Handshake.Result.Rejected(Handshake.Reason.VersionMismatch(_, _)) =>
          assert(answer.isInstanceOf[Handshake.Message.Refuse])
          QueryVersionCoverage.VersionMismatch
        case Handshake.Result.Rejected(Handshake.Reason.Refused(_, _)) =>
          assert(answer.isInstanceOf[Handshake.Message.Refuse])
          QueryVersionCoverage.Refused
        case Handshake.Result.Rejected(Handshake.Reason.DecodeError(_, _)) =>
          assert(answer.isInstanceOf[Handshake.Message.Refuse])
          QueryVersionCoverage.DecodeError
        case other =>
          fail(s"query-enabled proposal had unexpected responder result $other at seed $seed")
    }
    assertEquals(replay.size, 64)
    assertEquals(
      replay.count(_ == QueryVersionCoverage.QueryReply),
      0,
      "the independent-pair property sample has no QueryReply cases"
    )
    assertEquals(replay.count(_ == QueryVersionCoverage.Refused), 62)
    assertEquals(replay.count(_ == QueryVersionCoverage.VersionMismatch), 2)

  test("replay the separately labeled correlated Cardano query-version ST slice through Scarab"):
    val input = rows(
      "cardano-handshake-query-correlated.tsv",
      "824c4d2b96e0b0a49770dd47b931778690e429df2ff2cb4cf27004074b494ee5"
    )
    def typedOffers(message: Handshake.Message): Vector[(Int, Handshake.Data)] =
      val entries = message match
        case Handshake.Message.Propose(vs)    => vs
        case Handshake.Message.QueryReply(vs) => vs
        case other                            => fail(s"expected a version map, got $other")
      entries.map { case (version, data) =>
        version -> Handshake
          .decodeData(Handshake.Suite.NodeToNode, version, data)
          .fold(e => fail(e.message), identity)
      }
    val outcomes = input.map { fields =>
      assertEquals(fields.size, 8)
      assertEquals(fields(0), "cardano-handshake-query-correlated-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 499001 + ordinal)
      assertEquals(fields(3).toInt, 60 + ordinal % 31)
      val status = fields(4)
      val originalWire = bytes(fields(5))
      val queryWire = bytes(fields(6))
      val serverReplyWire = bytes(fields(7))
      val originalMessage = Handshake
        .decode(
          Handshake.Suite.NodeToNode,
          Handshake.State.Propose,
          originalWire
        )
        .fold(e => fail(e.message), identity)
      val queryMessage = Handshake
        .decode(
          Handshake.Suite.NodeToNode,
          Handshake.State.Propose,
          queryWire
        )
        .fold(e => fail(e.message), identity)
      val originalOffers = typedOffers(originalMessage)
      val clientOffers = typedOffers(queryMessage)
      if status == "empty-version-map" then
        assertEquals(originalOffers, Vector.empty)
        QueryVersionCoverage.PreconditionFilteredEmptyMap
      else
        assertEquals(status, "pass", s"unchanged query ST property at seed $seed")
        val serverReply = Handshake
          .decode(
            Handshake.Suite.NodeToNode,
            Handshake.State.Confirm,
            serverReplyWire
          )
          .fold(e => fail(e.message), identity)
        val serverOffers = typedOffers(serverReply)
        assertEquals(
          serverOffers,
          originalOffers,
          s"same generated map used on both sides at seed $seed"
        )
        assertEquals(
          clientOffers,
          originalOffers.map { case (version, data) =>
            version -> data.copy(query = true, peerSharing = true)
          },
          s"original Cardano query transform at seed $seed"
        )
        val (client, proposal) = Handshake
          .clientStart(Handshake.Suite.NodeToNode, clientOffers)
          .fold(e => fail(e.toString), identity)
        assertEquals(
          Handshake
            .encode(Handshake.Suite.NodeToNode, proposal)
            .fold(e => fail(e.toString), identity),
          queryWire,
          s"exact generated client proposal at seed $seed"
        )
        val (answer, responderResult) = Handshake
          .responder(Handshake.Suite.NodeToNode, serverOffers, proposal)
          .fold(e => fail(e.toString), identity)
        assert(
          responderResult.isInstanceOf[Handshake.Result.QueryResult],
          s"expected QueryReply at seed $seed"
        )
        assertEquals(answer, serverReply, s"full server version map in QueryReply at seed $seed")
        assertEquals(
          Handshake
            .encode(Handshake.Suite.NodeToNode, answer)
            .fold(e => fail(e.toString), identity),
          serverReplyWire,
          s"QueryReply wire at seed $seed"
        )
        val (completedClient, clientResult) = client
          .receive(serverReply)
          .fold(e => fail(e.toString), identity)
        assertEquals(completedClient.state, Handshake.State.Done)
        assertEquals(
          clientResult,
          Handshake.Result.QueryResult(serverOffers.map((version, data) => version -> Right(data))),
          s"client query result preserves full server version data at seed $seed"
        )
        QueryVersionCoverage.QueryReply
    }
    assertEquals(outcomes.count(_ == QueryVersionCoverage.QueryReply), 63)
    assertEquals(outcomes.count(_ == QueryVersionCoverage.PreconditionFilteredEmptyMap), 1)

  test(
    "replay Cardano accept-or-refuse property cases through Scarab's typed client and responder"
  ):
    val input = rows(
      "cardano-handshake-accept-refuse.tsv",
      "3859981bf743643c9300858bc69dee0e991a1e828fc7dd66f958f83f5ca4db00"
    )
    def offers(message: Handshake.Message): Vector[(Int, Handshake.Data)] = message match
      case Handshake.Message.Propose(vs) =>
        vs.map { case (version, data) =>
          version -> Handshake
            .decodeData(Handshake.Suite.NodeToNode, version, data)
            .fold(e => fail(e.message), identity)
        }
      case other => fail(s"expected generated Cardano proposal, got $other")
    def outcome(result: Handshake.Result): HandshakeOutcomeCoverage = result match
      case Handshake.Result.Negotiated(version, data) =>
        HandshakeOutcomeCoverage.Negotiated(version, data)
      case Handshake.Result.QueryResult(_) => HandshakeOutcomeCoverage.QueryReply
      case Handshake.Result.Rejected(Handshake.Reason.VersionMismatch(_, _)) =>
        HandshakeOutcomeCoverage.Rejected(HandshakeRejectionCoverage.VersionMismatch)
      case Handshake.Result.Rejected(Handshake.Reason.DecodeError(_, _)) =>
        HandshakeOutcomeCoverage.Rejected(HandshakeRejectionCoverage.DecodeError)
      case Handshake.Result.Rejected(Handshake.Reason.Refused(_, _)) =>
        HandshakeOutcomeCoverage.Rejected(HandshakeRejectionCoverage.Refused)
    def direction(
        initiatorOffers: Vector[(Int, Handshake.Data)],
        responderOffers: Vector[(Int, Handshake.Data)],
        originalInitiatorWire: Bytes,
        seed: Int
    ): HandshakeOutcomeCoverage =
      val (client, proposal) = Handshake
        .clientStart(Handshake.Suite.NodeToNode, initiatorOffers)
        .fold(e => fail(e.toString), identity)
      assertEquals(
        Handshake
          .encode(Handshake.Suite.NodeToNode, proposal)
          .fold(e => fail(e.toString), identity),
        originalInitiatorWire,
        s"generated proposal wire at seed $seed"
      )
      val (reply, responderResult) = Handshake
        .responder(Handshake.Suite.NodeToNode, responderOffers, proposal)
        .fold(e => fail(e.toString), identity)
      val (_, clientResult) = client.receive(reply).fold(e => fail(e.toString), identity)
      val serverOutcome = outcome(responderResult)
      val clientOutcome = outcome(clientResult)
      assertEquals(clientOutcome, serverOutcome, s"client and responder result at seed $seed")
      serverOutcome

    val results = input.map { fields =>
      assertEquals(fields.size, 7)
      assertEquals(fields(0), "cardano-handshake-accept-refuse-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 500001 + ordinal)
      assertEquals(fields(3).toInt, 60 + ordinal % 31)
      assertEquals(fields(4), "pass", s"unchanged upstream property at seed $seed")
      val clientWire = bytes(fields(5))
      val serverWire = bytes(fields(6))
      val clientMessage = Handshake
        .decode(
          Handshake.Suite.NodeToNode,
          Handshake.State.Propose,
          clientWire
        )
        .fold(e => fail(e.message), identity)
      val serverMessage = Handshake
        .decode(
          Handshake.Suite.NodeToNode,
          Handshake.State.Propose,
          serverWire
        )
        .fold(e => fail(e.message), identity)
      val clientOffers = offers(clientMessage)
      val serverOffers = offers(serverMessage)
      val clientOutcome = direction(clientOffers, serverOffers, clientWire, seed)
      val serverOutcome = direction(serverOffers, clientOffers, serverWire, seed)
      assertEquals(clientOutcome, serverOutcome, s"symmetric Cardano decision at seed $seed")
      clientOutcome
    }
    assertEquals(results.size, 64)
    assertEquals(
      results.count(_ == HandshakeOutcomeCoverage.Rejected(HandshakeRejectionCoverage.Refused)),
      62
    )
    assertEquals(
      results.count(
        _ == HandshakeOutcomeCoverage.Rejected(HandshakeRejectionCoverage.VersionMismatch)
      ),
      2
    )
    assertEquals(
      results.count {
        case HandshakeOutcomeCoverage.Negotiated(_, _) => true
        case _                                         => false
      },
      0
    )

  test(
    "replay correlated Cardano accept-or-refuse cases with one unchanged generated map per side"
  ):
    val input = rows(
      "cardano-handshake-accept-refuse-correlated.tsv",
      "ac8b54e2ec6fffe372e1b2638c1edbca256cf389c10e030d32fb4bd7fc9792f2"
    )
    def offers(message: Handshake.Message): Vector[(Int, Handshake.Data)] = message match
      case Handshake.Message.Propose(vs) =>
        vs.map { case (version, data) =>
          version -> Handshake
            .decodeData(Handshake.Suite.NodeToNode, version, data)
            .fold(e => fail(e.message), identity)
        }
      case other => fail(s"expected generated Cardano proposal, got $other")
    val outcomes = input.map { fields =>
      assertEquals(fields.size, 6)
      assertEquals(fields(0), "cardano-handshake-accept-refuse-correlated-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 501001 + ordinal)
      assertEquals(fields(3).toInt, 60 + ordinal % 31)
      val message = Handshake
        .decode(
          Handshake.Suite.NodeToNode,
          Handshake.State.Propose,
          bytes(fields(5))
        )
        .fold(e => fail(e.message), identity)
      val typed = offers(message)
      if fields(4) == "empty-version-map" then
        assertEquals(typed, Vector.empty)
        HandshakeOutcomeCoverage.Rejected(HandshakeRejectionCoverage.VersionMismatch)
      else
        assertEquals(
          fields(4),
          "pass",
          s"unchanged upstream accept-or-refuse property at seed $seed"
        )
        assert(typed.nonEmpty)
        val (client, proposal) = Handshake
          .clientStart(Handshake.Suite.NodeToNode, typed)
          .fold(e => fail(e.toString), identity)
        assertEquals(
          Handshake
            .encode(Handshake.Suite.NodeToNode, proposal)
            .fold(e => fail(e.toString), identity),
          bytes(fields(5)),
          s"same generated map proposal at seed $seed"
        )
        val (reply, responderResult) = Handshake
          .responder(Handshake.Suite.NodeToNode, typed, proposal)
          .fold(e => fail(e.toString), identity)
        val (_, clientResult) = client.receive(reply).fold(e => fail(e.toString), identity)
        val serverOutcome = responderResult match
          case Handshake.Result.Negotiated(version, data) =>
            HandshakeOutcomeCoverage.Negotiated(version, data)
          case Handshake.Result.QueryResult(_) => HandshakeOutcomeCoverage.QueryReply
          case Handshake.Result.Rejected(Handshake.Reason.VersionMismatch(_, _)) =>
            HandshakeOutcomeCoverage.Rejected(HandshakeRejectionCoverage.VersionMismatch)
          case Handshake.Result.Rejected(Handshake.Reason.DecodeError(_, _)) =>
            HandshakeOutcomeCoverage.Rejected(HandshakeRejectionCoverage.DecodeError)
          case Handshake.Result.Rejected(Handshake.Reason.Refused(_, _)) =>
            HandshakeOutcomeCoverage.Rejected(HandshakeRejectionCoverage.Refused)
        val clientOutcome = clientResult match
          case Handshake.Result.Negotiated(version, data) =>
            HandshakeOutcomeCoverage.Negotiated(version, data)
          case Handshake.Result.QueryResult(_) => HandshakeOutcomeCoverage.QueryReply
          case Handshake.Result.Rejected(Handshake.Reason.VersionMismatch(_, _)) =>
            HandshakeOutcomeCoverage.Rejected(HandshakeRejectionCoverage.VersionMismatch)
          case Handshake.Result.Rejected(Handshake.Reason.DecodeError(_, _)) =>
            HandshakeOutcomeCoverage.Rejected(HandshakeRejectionCoverage.DecodeError)
          case Handshake.Result.Rejected(Handshake.Reason.Refused(_, _)) =>
            HandshakeOutcomeCoverage.Rejected(HandshakeRejectionCoverage.Refused)
        assertEquals(clientOutcome, serverOutcome, s"same-map handshake outcome at seed $seed")
        assert(serverOutcome match
          case HandshakeOutcomeCoverage.Rejected(_) => false
          case _                                    => true)
        serverOutcome
    }
    assertEquals(
      outcomes.count {
        case HandshakeOutcomeCoverage.Rejected(_) => false
        case _                                    => true
      },
      63
    )
    assertEquals(
      outcomes.count(
        _ == HandshakeOutcomeCoverage.Rejected(HandshakeRejectionCoverage.VersionMismatch)
      ),
      1
    )
    assertEquals(
      outcomes.count {
        case HandshakeOutcomeCoverage.Negotiated(_, _) => true
        case _                                         => false
      },
      32
    )
    assertEquals(outcomes.count(_ == HandshakeOutcomeCoverage.QueryReply), 31)

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
      ChainSync.decodePrefix(state, sender, wire, payloadCodec) match
        case ChainSync.DecodeResult.Failed(ProtocolError.ExpectedDefiniteByteString) =>
          s"unsupported:${fields(6)}"
        case ChainSync.DecodeResult.Failed(error) =>
          fail(s"seed $seed unexpected typed ChainSync decode error: $error")
        case ChainSync.DecodeResult.NeedMore =>
          fail(s"seed $seed complete ChainSync wire unexpectedly needs more bytes")
        case ChainSync.DecodeResult.Decoded(message, consumed) =>
          assertEquals(consumed, wire.size, s"seed $seed consumed full original wire")
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
            .fold(e => fail(s"seed $seed Scarab typed encode: $e"), identity)
          assertEquals(encoded, wire, s"seed $seed exact original wire roundtrip")
          "matched"
    }
    assertEquals(mapped.size, 128)
    assertEquals(
      mapped.groupMapReduce(identity)(_ => 1)(_ + _),
      Map(
        "matched" -> 49,
        "unsupported:roll-forward" -> 20,
        "unsupported:roll-backward" -> 29,
        "unsupported:intersect-not-found" -> 8,
        "unsupported:intersect-found" -> 13,
        "unsupported:find-intersect" -> 9
      )
    )

  test("consume original ChainSync wrapped/Serialised binary compatibility properties"):
    val input = rows(
      "chainsync-binary.tsv",
      "2bb4450b478eba153716ad5a65c3267ee63cedf2d1d0d6850768729ee932ec1c"
    )
    val payloadCodec = new ChainSync.PayloadCodec[Bytes]:
      def encode(value: Bytes): Either[ProtocolError, Bytes] = Right(value)
      def decode(value: Bytes): Either[ProtocolError, Bytes] = Right(value)
    val byDirection = input.groupBy(fields => binaryDirection(fields(4)))
    assertEquals(
      byDirection.view.mapValues(_.size).toMap,
      Map(
        BinaryCodecDirection.WrappedToSerialised -> 128,
        BinaryCodecDirection.SerialisedToWrapped -> 128
      )
    )
    assertEquals(
      input.groupMapReduce(_(7))(_ => 1)(_ + _),
      Map(
        "await-reply" -> 18,
        "done" -> 30,
        "find-intersect" -> 18,
        "intersect-found" -> 26,
        "intersect-not-found" -> 18,
        "request-next" -> 28,
        "roll-backward" -> 62,
        "roll-forward" -> 56
      )
    )
    val matched = input.map { fields =>
      assertEquals(fields.size, 10)
      assertEquals(fields(0), "chainsync-binary-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 491101 + ordinal)
      assertEquals(fields(3).toInt, 30 + ordinal % 21)
      assertEquals(fields(9), "pass", s"upstream binary compatibility seed $seed")
      val direction = binaryDirection(fields(4))
      val state = fields(5) match
        case "idle"            => ChainSync.State.Idle
        case "next-can-await"  => ChainSync.State.NextCanAwait
        case "next-must-reply" => ChainSync.State.NextMustReply
        case "intersect"       => ChainSync.State.Intersect
        case other             => fail(s"unknown ChainSync binary state $other at seed $seed")
      val sender = fields(6) match
        case "client" => ChainSync.Role.Client
        case "server" => ChainSync.Role.Server
        case other    => fail(s"unknown ChainSync binary agency $other at seed $seed")
      val tag = fields(7)
      val wire = bytes(fields(8))
      ChainSync.decodePrefix(state, sender, wire, payloadCodec) match
        case ChainSync.DecodeResult.Failed(error @ ProtocolError.ExpectedDefiniteByteString) =>
          assert(
            Set(
              "roll-forward",
              "roll-backward",
              "find-intersect",
              "intersect-found",
              "intersect-not-found"
            )
              .contains(tag),
            s"only the upstream mock hash representation is unsupported at seed $seed"
          )
          BinaryCodecCoverage.UnsupportedMockHash(direction, error)
        case ChainSync.DecodeResult.Failed(error) =>
          fail(s"seed $seed unexpected typed ChainSync binary decode error: $error")
        case ChainSync.DecodeResult.NeedMore =>
          fail(s"seed $seed complete ChainSync binary wire unexpectedly needs more bytes")
        case ChainSync.DecodeResult.Decoded(message, consumed) =>
          assertEquals(consumed, wire.size, s"seed $seed consumed full binary wire")
          val decodedTag = message match
            case ChainSync.Message.RequestNext          => "request-next"
            case ChainSync.Message.AwaitReply           => "await-reply"
            case ChainSync.Message.RollForward(_, _)    => "roll-forward"
            case ChainSync.Message.RollBackward(_, _)   => "roll-backward"
            case ChainSync.Message.FindIntersect(_)     => "find-intersect"
            case ChainSync.Message.IntersectFound(_, _) => "intersect-found"
            case ChainSync.Message.IntersectNotFound(_) => "intersect-not-found"
            case ChainSync.Message.Done                 => "done"
          assertEquals(decodedTag, tag, s"seed $seed typed binary constructor")
          assertEquals(
            ChainSync.encode(state, sender, message, payloadCodec),
            Right(wire),
            s"seed $seed Scarab preserves original binary-compatible wire"
          )
          BinaryCodecCoverage.Matched(direction)
    }
    assertEquals(matched.collect { case BinaryCodecCoverage.Matched(_) => 1 }.size, 98)
    assertEquals(
      matched.collect { case BinaryCodecCoverage.UnsupportedMockHash(_, _) => 1 }.size,
      158
    )
    assertEquals(
      matched.collect { case BinaryCodecCoverage.UnsupportedMockHash(_, error) => error }.distinct,
      Vector(ProtocolError.ExpectedDefiniteByteString)
    )
    assertEquals(
      input
        .zip(matched)
        .groupBy((fields, _) => binaryDirection(fields(4)))
        .view
        .mapValues { rows =>
          rows.count { case (_, BinaryCodecCoverage.Matched(_)) => true; case _ => false } ->
            rows.count {
              case (_, BinaryCodecCoverage.UnsupportedMockHash(_, _)) => true; case _ => false
            }
        }
        .toMap,
      Map(
        BinaryCodecDirection.WrappedToSerialised -> (49 -> 79),
        BinaryCodecDirection.SerialisedToWrapped -> (49 -> 79)
      )
    )
    assertEquals(
      input
        .groupBy(fields => (fields(2), fields(7)))
        .view
        .mapValues(rows => rows.map(_(4)).toSet)
        .toMap
        .values
        .forall(_ == Set("codec/codecSerialised", "codecSerialised/codec")),
      true,
      "both original generator domains retain paired constructor rows"
    )
    assertEquals(
      input
        .groupBy(fields => (fields(2), fields(7)))
        .values
        .forall(rows => rows.map(_(8)).distinct.size == 1),
      true,
      "the pinned original binary compatibility properties retain identical paired wire bytes"
    )

  test("consume upstream ChainSync serialized split properties through Scarab's prefix decoder"):
    val splitInput = rows(
      "chainsync-splits.tsv",
      "2979a92446cb3bc062b4f9109f1dea3208bf4094e22c80116f341ac838b45c70"
    )
    val codecInput = rows(
      "chainsync.tsv",
      "d2c840ebcb1f4b55b59055e8ef4b9ff55da80a0d0d428dc2b090c3c0c840224e"
    )
    val payloadCodec = new ChainSync.PayloadCodec[Bytes]:
      def encode(value: Bytes): Either[ProtocolError, Bytes] = Right(value)
      def decode(value: Bytes): Either[ProtocolError, Bytes] = Right(value)

    assertEquals(splitInput.size, 128)
    assertEquals(codecInput.size, splitInput.size)
    assertEquals(splitInput.count(f => f.size == 10 && f(8) == "pass" && f(9) == "pass"), 128)
    val outcomes = splitInput.zip(codecInput).map { (fields, codecFields) =>
      assertEquals(fields.size, 10)
      assertEquals(fields(0), "chainsync-splits-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 491101 + ordinal)
      assertEquals(fields(3).toInt, 30 + ordinal % 21)
      assertEquals(fields(4), codecFields(4), s"seed $seed original state")
      assertEquals(fields(5), codecFields(5), s"seed $seed sender/agency")
      assertEquals(fields(6), codecFields(6), s"seed $seed constructor")
      assertEquals(fields(7), codecFields(7), s"seed $seed original wire")
      assertEquals(codecFields(8), "pass", s"seed $seed original codec verdict")
      assertEquals(codecFields(9), "pass", s"seed $seed original CBOR verdict")
      assertEquals(fields(8), "pass", s"seed $seed upstream splits2 verdict")
      assertEquals(fields(9), "pass", s"seed $seed upstream splits3 verdict")
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
      ChainSync.decodePrefix(state, sender, wire, payloadCodec) match
        case ChainSync.DecodeResult.Failed(ProtocolError.ExpectedDefiniteByteString) =>
          assert(
            Set(
              "roll-forward",
              "roll-backward",
              "find-intersect",
              "intersect-found",
              "intersect-not-found"
            )
              .contains(fields(6)),
            s"only mock Int hash rows are unsupported at seed $seed"
          )
          ChainSyncSplitCoverage.Unsupported(ProtocolError.ExpectedDefiniteByteString)
        case ChainSync.DecodeResult.Failed(error) =>
          fail(s"seed $seed unexpected typed ChainSync decode error: $error")
        case ChainSync.DecodeResult.NeedMore =>
          fail(s"seed $seed complete ChainSync wire unexpectedly needs more bytes")
        case decoded @ ChainSync.DecodeResult.Decoded(message, consumed) =>
          assertEquals(consumed, wire.size, s"seed $seed consumed complete original wire")
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
            .fold(e => fail(s"seed $seed Scarab typed encode: $e"), identity)
          assertEquals(encoded, wire, s"seed $seed exact original wire roundtrip")
          val (twoPartitions, threePartitions) = verifyChainSyncPartitions(
            wire,
            decoded,
            prefix => ChainSync.decodePrefix(state, sender, prefix, payloadCodec),
            s"seed $seed"
          )
          ChainSyncSplitCoverage.Matched(twoPartitions, threePartitions)
    }
    assertEquals(
      outcomes.collect { case ChainSyncSplitCoverage.Matched(_, _) => 1 }.size,
      49
    )
    assertEquals(
      outcomes.collect {
        case ChainSyncSplitCoverage.Unsupported(ProtocolError.ExpectedDefiniteByteString) => 1
      }.size,
      79
    )
    assertEquals(
      splitInput
        .zip(outcomes)
        .collect { case (fields, ChainSyncSplitCoverage.Unsupported(_)) => fields(6) }
        .groupMapReduce(identity)(_ => 1)(_ + _),
      Map(
        "roll-forward" -> 20,
        "roll-backward" -> 29,
        "intersect-not-found" -> 8,
        "intersect-found" -> 13,
        "find-intersect" -> 9
      )
    )
    val mapped = splitInput.zip(outcomes).collect {
      case (fields, ChainSyncSplitCoverage.Matched(twoPartitions, threePartitions)) =>
        (bytes(fields(7)).size, twoPartitions, threePartitions)
    }
    assertEquals(
      mapped.map(_._2).sum,
      mapped.map(_._1 + 1).sum,
      "all split2 partitions replayed for typed-compatible rows"
    )
    assertEquals(
      mapped.map(_._3).sum,
      mapped.map { case (size, _, _) => (size + 1) * (size + 2) / 2 }.sum,
      "all split3 partitions replayed for typed-compatible rows"
    )

  test("classify generated pinned BlockFetch Serialised-block rows at the typed boundary"):
    val input =
      rows("blockfetch.tsv", "18c4e32a647e7287246b2ef2c6a874e82e565778e63352966645fb969e0e2e50")
    val payloadCodec = CardanoBlockFetch.payloadCodec()
    assertEquals(input.size, 24)
    assertEquals(
      input.count(f => f.size == 10 && f(8) == "pass" && f(9) == "pass"),
      24
    )
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
      BlockFetch.decodePrefix(state, sender, wire, payloadCodec) match
        case DecodeResult.Failed(ProtocolError.ExpectedDefiniteByteString) =>
          assertEquals(fields(6), "request-range", s"only mock Int hashes are unsupported at $seed")
          "unsupported-mock-point"
        case DecodeResult.Failed(error) =>
          fail(s"seed $seed unexpected typed BlockFetch decode error: $error")
        case DecodeResult.NeedMore =>
          fail(s"seed $seed complete BlockFetch wire unexpectedly needs more bytes")
        case DecodeResult.Decoded(message, consumed) =>
          assertEquals(consumed, wire.size, s"seed $seed consumed original wire")
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
            .fold(e => fail(s"seed $seed Scarab typed encode: $e"), identity)
          assertEquals(encoded, wire, s"seed $seed exact original wire roundtrip")
          "matched"
    }
    assertEquals(outcomes.size, 24)
    assertEquals(
      outcomes.groupMapReduce(identity)(_ => 1)(_ + _),
      Map("matched" -> 20, "unsupported-mock-point" -> 4)
    )

  test("consume original BlockFetch wrapped/Serialised binary compatibility properties"):
    val input = rows(
      "blockfetch-binary.tsv",
      "fd28eb73baef1556606482c004d78e03b3296414d99b4a15800f171f636cbeeb"
    )
    val payloadCodec = CardanoBlockFetch.payloadCodec()
    val byDirection = input.groupBy(fields => binaryDirection(fields(4)))
    assertEquals(
      byDirection.view.mapValues(_.size).toMap,
      Map(
        BinaryCodecDirection.WrappedToSerialised -> 24,
        BinaryCodecDirection.SerialisedToWrapped -> 24
      )
    )
    assertEquals(
      input.groupMapReduce(_(7))(_ => 1)(_ + _),
      Map(
        "block" -> 8,
        "client-done" -> 10,
        "no-blocks" -> 10,
        "request-range" -> 8,
        "start-batch" -> 12
      )
    )
    val outcomes = input.map { fields =>
      assertEquals(fields.size, 10)
      assertEquals(fields(0), "blockfetch-binary-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 490201 + ordinal)
      assertEquals(fields(3).toInt, 30 + ordinal % 21)
      assertEquals(fields(9), "pass", s"upstream binary compatibility seed $seed")
      val direction = binaryDirection(fields(4))
      val state = fields(5) match
        case "idle"      => BlockFetch.State.Idle
        case "busy"      => BlockFetch.State.Busy
        case "streaming" => BlockFetch.State.Streaming
        case other       => fail(s"unknown BlockFetch binary state $other at seed $seed")
      val sender = fields(6) match
        case "client" => BlockFetch.Role.Client
        case "server" => BlockFetch.Role.Server
        case other    => fail(s"unknown BlockFetch binary agency $other at seed $seed")
      val tag = fields(7)
      val wire = bytes(fields(8))
      BlockFetch.decodePrefix(state, sender, wire, payloadCodec) match
        case DecodeResult.Failed(error @ ProtocolError.ExpectedDefiniteByteString) =>
          assertEquals(tag, "request-range", s"only mock Int points are unsupported at seed $seed")
          BinaryCodecCoverage.UnsupportedMockHash(direction, error)
        case DecodeResult.Failed(error) =>
          fail(s"seed $seed unexpected typed BlockFetch binary decode error: $error")
        case DecodeResult.NeedMore =>
          fail(s"seed $seed complete BlockFetch binary wire unexpectedly needs more bytes")
        case DecodeResult.Decoded(message, consumed) =>
          assertEquals(consumed, wire.size, s"seed $seed consumed full binary wire")
          val decodedTag = message match
            case BlockFetch.Message.RequestRange(_, _) => "request-range"
            case BlockFetch.Message.ClientDone         => "client-done"
            case BlockFetch.Message.StartBatch         => "start-batch"
            case BlockFetch.Message.NoBlocks           => "no-blocks"
            case BlockFetch.Message.Block(_)           => "block"
            case BlockFetch.Message.BatchDone          => "batch-done"
          assertEquals(decodedTag, tag, s"seed $seed typed binary constructor")
          assertEquals(
            BlockFetch.encode(state, sender, message, payloadCodec),
            Right(wire),
            s"seed $seed Scarab preserves original binary-compatible wire"
          )
          BinaryCodecCoverage.Matched(direction)
    }
    assertEquals(outcomes.collect { case BinaryCodecCoverage.Matched(_) => 1 }.size, 40)
    assertEquals(
      outcomes.collect { case BinaryCodecCoverage.UnsupportedMockHash(_, _) => 1 }.size,
      8
    )
    assertEquals(
      outcomes.collect { case BinaryCodecCoverage.UnsupportedMockHash(_, error) => error }.distinct,
      Vector(ProtocolError.ExpectedDefiniteByteString)
    )
    assertEquals(
      input
        .zip(outcomes)
        .groupBy((fields, _) => binaryDirection(fields(4)))
        .view
        .mapValues { rows =>
          rows.count { case (_, BinaryCodecCoverage.Matched(_)) => true; case _ => false } ->
            rows.count {
              case (_, BinaryCodecCoverage.UnsupportedMockHash(_, _)) => true; case _ => false
            }
        }
        .toMap,
      Map(
        BinaryCodecDirection.WrappedToSerialised -> (20 -> 4),
        BinaryCodecDirection.SerialisedToWrapped -> (20 -> 4)
      )
    )

  test("consume upstream BlockFetch serialized split properties through Scarab's prefix decoder"):
    val input = rows(
      "blockfetch-splits.tsv",
      "7d1ff263248c4d206df352d7f7e6e0b8a1735c204db7d30f761f25fffa61cb2a"
    )
    val completeWire = rows(
      "blockfetch.tsv",
      "18c4e32a647e7287246b2ef2c6a874e82e565778e63352966645fb969e0e2e50"
    )
    val payloadCodec = CardanoBlockFetch.payloadCodec()
    assertEquals(input.size, 24)
    assertEquals(completeWire.size, 24)
    assertEquals(
      input.count(f => f.size == 10 && f(8) == "pass" && f(9) == "pass"),
      24
    )
    val results = input.zip(completeWire).map { (fields, baseFields) =>
      assertEquals(fields.size, 10)
      assertEquals(fields(0), "blockfetch-splits-v1")
      assertEquals(baseFields(0), "blockfetch-serialised-codec-v1")
      assertEquals(
        fields.slice(1, 8),
        baseFields.slice(1, 8),
        "split observer preserves the original generated row"
      )
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 490201 + ordinal)
      assertEquals(fields(3).toInt, 30 + ordinal % 21)
      assertEquals(
        fields(8),
        "pass",
        s"upstream prop_codec_splits2_BlockFetchSerialised seed $seed"
      )
      assertEquals(
        fields(9),
        "pass",
        s"upstream prop_codec_splits3_BlockFetchSerialised seed $seed"
      )
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
      BlockFetch.decodePrefix(state, sender, wire, payloadCodec) match
        case DecodeResult.Failed(ProtocolError.ExpectedDefiniteByteString) =>
          assertEquals(
            fields(6),
            "request-range",
            s"only mock Int hashes are unsupported at seed $seed"
          )
          ("unsupported-mock-point", Option.empty[(Int, Int)])
        case DecodeResult.Failed(error) =>
          fail(s"seed $seed unexpected typed BlockFetch error: $error")
        case DecodeResult.NeedMore =>
          fail(s"seed $seed complete BlockFetch wire unexpectedly needs more bytes")
        case DecodeResult.Decoded(message, consumed) =>
          assertEquals(consumed, wire.size, s"seed $seed consumed original wire")
          val tag = message match
            case BlockFetch.Message.RequestRange(_, _) => "request-range"
            case BlockFetch.Message.ClientDone         => "client-done"
            case BlockFetch.Message.StartBatch         => "start-batch"
            case BlockFetch.Message.NoBlocks           => "no-blocks"
            case BlockFetch.Message.Block(_)           => "block"
            case BlockFetch.Message.BatchDone          => "batch-done"
          assertEquals(tag, fields(6), s"seed $seed typed constructor")
          assertEquals(
            BlockFetch.encode(state, sender, message, payloadCodec),
            Right(wire),
            s"seed $seed exact original wire roundtrip"
          )
          val counts = verifyAllPartitions(
            wire,
            DecodeResult.Decoded(message, wire.size),
            prefix => BlockFetch.decodePrefix(state, sender, prefix, payloadCodec),
            s"seed $seed"
          )
          ("matched", Some(counts))
    }
    assertEquals(
      results.groupMapReduce(_._1)(_ => 1)(_ + _),
      Map("matched" -> 20, "unsupported-mock-point" -> 4)
    )
    val supportedRows = results.zip(input).collect { case (("matched", _), fields) => fields }
    val partitionCounts = results.flatMap(_._2)
    assertEquals(supportedRows.size, 20)
    assertEquals(
      partitionCounts.map(_._1).sum,
      supportedRows.map(fields => bytes(fields(7)).size + 1).sum
    )
    assertEquals(
      partitionCounts.map(_._2).sum,
      supportedRows.map { fields =>
        val n = bytes(fields(7)).size
        (n + 1) * (n + 2) / 2
      }.sum
    )
    assertEquals(
      supportedRows.groupMapReduce(_(6))(_ => 1)(_ + _),
      Map("block" -> 4, "client-done" -> 5, "no-blocks" -> 5, "start-batch" -> 6)
    )

  test("classify generated pinned LocalStateQuery codec rows at the typed boundary"):
    val input =
      rows("localsq.tsv", "a9b0f1584c4330ebb2ce670d3dd422ecb168ed4a55b6aa469c824b12c33940cf")
    assertEquals(input.size, 24)
    assertEquals(
      input.count(f => f.size == 11 && f(8) == "pass" && f(9) == "pass" && f(10) == "pass"),
      24
    )
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
      assertEquals(fields.size, 11)
      assertEquals(fields(0), "localsq-codec-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 492001 + ordinal)
      assertEquals(fields(3).toInt, 30 + ordinal % 21)
      assertEquals(fields(10), "pass", s"upstream valid-CBOR seed $seed")
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
      LocalStateQuery.decodePrefix(state, sender, wire) match
        case DecodeResult.Failed(ProtocolError.ExpectedDefiniteByteString) =>
          assert(
            fields(6) == "acquire-specific-point" || fields(6) == "reacquire-specific-point",
            s"unexpected unsupported LocalStateQuery constructor ${fields(6)} at seed $seed"
          )
          s"unsupported:${fields(6)}"
        case DecodeResult.Failed(error) =>
          fail(s"seed $seed unexpected typed LocalStateQuery decode error: $error")
        case DecodeResult.NeedMore =>
          fail(s"seed $seed complete LocalStateQuery wire unexpectedly needs more bytes")
        case DecodeResult.Decoded(message, consumed) =>
          assertEquals(consumed, wire.size, s"seed $seed consumed original wire")
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
            .fold(e => fail(s"seed $seed Scarab typed encode: $e"), identity)
          assertEquals(encoded, wire, s"seed $seed exact original wire roundtrip")
          "matched"
    }
    assertEquals(outcomes.size, 24)
    assertEquals(
      outcomes.groupMapReduce(identity)(_ => 1)(_ + _),
      Map(
        "matched" -> 20,
        "unsupported:acquire-specific-point" -> 1,
        "unsupported:reacquire-specific-point" -> 3
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
      "a9b0f1584c4330ebb2ce670d3dd422ecb168ed4a55b6aa469c824b12c33940cf"
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
    val outcomes = input.zip(completeWire).map { (fields, baseFields) =>
      assertEquals(fields.size, 10)
      assertEquals(fields(0), "localsq-splits-v1")
      assertEquals(baseFields(0), "localsq-codec-v1")
      assertEquals(baseFields.size, 11)
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
      assertEquals(baseFields(10), "pass", s"upstream valid-CBOR seed $seed")
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
      LocalStateQuery.decodePrefix(state, sender, wire) match
        case DecodeResult.Failed(ProtocolError.ExpectedDefiniteByteString) =>
          assert(
            fields(6) == "acquire-specific-point" || fields(6) == "reacquire-specific-point",
            s"unexpected unsupported LocalStateQuery constructor ${fields(6)} at seed $seed"
          )
          (s"unsupported:${fields(6)}", Option.empty[(Int, Int)])
        case DecodeResult.Failed(error) =>
          fail(s"seed $seed unexpected typed LocalStateQuery error: $error")
        case DecodeResult.NeedMore =>
          fail(s"seed $seed complete LocalStateQuery wire unexpectedly needs more bytes")
        case DecodeResult.Decoded(message, consumed) =>
          assertEquals(consumed, wire.size, s"seed $seed consumed original wire")
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
          assertEquals(
            LocalStateQuery.encode(state, sender, message),
            Right(wire),
            s"seed $seed exact original wire roundtrip"
          )
          val counts = verifyAllPartitions(
            wire,
            DecodeResult.Decoded(message, wire.size),
            prefix => LocalStateQuery.decodePrefix(state, sender, prefix),
            s"seed $seed"
          )
          ("matched", Some(counts))
    }
    assertEquals(
      outcomes.map(_._1).groupMapReduce(identity)(_ => 1)(_ + _),
      Map(
        "matched" -> 20,
        "unsupported:acquire-specific-point" -> 1,
        "unsupported:reacquire-specific-point" -> 3
      )
    )
    val supportedRows = outcomes.zip(input).collect { case (("matched", _), fields) => fields }
    val partitionCounts = outcomes.flatMap(_._2)
    assertEquals(supportedRows.size, 20)
    assertEquals(
      partitionCounts.map(_._1).sum,
      supportedRows.map(fields => bytes(fields(7)).size + 1).sum
    )
    assertEquals(
      partitionCounts.map(_._2).sum,
      supportedRows.map { fields =>
        val n = bytes(fields(7)).size
        (n + 1) * (n + 2) / 2
      }.sum
    )
    assertEquals(
      supportedRows.groupMapReduce(_(6))(_ => 1)(_ + _),
      Map(
        "acquire-volatile-tip" -> 3,
        "acquired" -> 2,
        "done" -> 1,
        "failure-point-too-old" -> 1,
        "reacquire-immutable-tip" -> 3,
        "reacquire-specific-point" -> 2,
        "reacquire-volatile-tip" -> 5,
        "release" -> 1,
        "result" -> 2
      )
    )

  test("consume actual generated LocalTxSubmission codec cases at the typed boundary"):
    val input = rows(
      "localtxsubmission.tsv",
      "f1fd489b8ff996952090ef21fce3496cac9ad36f4655bb6209387d41e78ecdba"
    )
    assertEquals(input.size, 24)
    assertEquals(
      input.count(f => f.size == 11 && f(8) == "pass" && f(9) == "pass" && f(10) == "pass"),
      24
    )
    assertEquals(
      input.groupMapReduce(_(6))(_ => 1)(_ + _),
      Map("accept-tx" -> 4, "done" -> 7, "reject-tx" -> 7, "submit-tx" -> 6)
    )
    assertEquals(input.groupMapReduce(_(4))(_ => 1)(_ + _), Map("busy" -> 11, "idle" -> 13))
    val outcomes = input.map { fields =>
      assertEquals(fields.size, 11)
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
            .fold(e => fail(s"seed $seed Scarab typed encode: $e"), identity)
          assertEquals(encoded, wire, s"seed $seed exact original wire roundtrip")
          "matched"
        case DecodeResult.Failed(error) =>
          fail(s"seed $seed typed decode failed: $error")
        case DecodeResult.NeedMore => fail(s"seed $seed incomplete original wire")
    }
    assertEquals(outcomes.size, 24)
    assertEquals(outcomes.count(_ == "matched"), 24)

  test("consume upstream LocalTxSubmission annotated codec and split properties"):
    val input = rows(
      "localtxsubmission-annotated.tsv",
      "fbb303143dd5c7bfe540a194d06da3a4316dab7b12d94a748fbec1e1278e5b8b"
    )
    assertEquals(input.size, 24)
    assertEquals(
      input.groupMapReduce(_(4))(_ => 1)(_ + _),
      Map("busy" -> 12, "idle" -> 12)
    )
    assertEquals(
      input.groupMapReduce(_(6))(_ => 1)(_ + _),
      Map("accept-tx" -> 5, "done" -> 8, "reject-tx" -> 7, "submit-tx" -> 4)
    )
    val partitionCounts = input.map { fields =>
      assertEquals(fields.size, 11)
      assertEquals(fields(0), "local-tx-submission-annotated-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 496001 + ordinal)
      assertEquals(fields(3).toInt, 30 + ordinal % 21)
      assertEquals(fields(8), "pass", s"prop_anncodec seed $seed")
      assertEquals(fields(9), "pass", s"prop_anncodec_splits2 seed $seed")
      assertEquals(fields(10), "pass", s"prop_anncodec_splits3 seed $seed")
      val state = fields(4) match
        case "idle" => LocalTxSubmission.State.Idle
        case "busy" => LocalTxSubmission.State.Busy
        case other  => fail(s"unknown LocalTxSubmission annotated state $other at seed $seed")
      val sender = fields(5) match
        case "client" => ChainSync.Role.Client
        case "server" => ChainSync.Role.Server
        case other    => fail(s"unknown LocalTxSubmission annotated sender $other at seed $seed")
      val wire = bytes(fields(7))
      LocalTxSubmission.decodePrefix(state, sender, wire) match
        case DecodeResult.Decoded(message, consumed) =>
          assertEquals(consumed, wire.size, s"seed $seed consumed annotated wire")
          val tag = message match
            case LocalTxSubmission.Message.SubmitTx(_) => "submit-tx"
            case LocalTxSubmission.Message.AcceptTx    => "accept-tx"
            case LocalTxSubmission.Message.RejectTx(_) => "reject-tx"
            case LocalTxSubmission.Message.Done        => "done"
          assertEquals(tag, fields(6), s"seed $seed annotated constructor")
          assertEquals(
            LocalTxSubmission.encode(state, sender, message),
            Right(wire),
            s"seed $seed annotated payload bytes survive Scarab decode/encode"
          )
          verifyAllPartitions(
            wire,
            DecodeResult.Decoded(message, wire.size),
            prefix => LocalTxSubmission.decodePrefix(state, sender, prefix),
            s"LocalTxSubmission annotated seed $seed"
          )
        case DecodeResult.Failed(error) => fail(s"seed $seed annotated decode failed: $error")
        case DecodeResult.NeedMore      => fail(s"seed $seed annotated wire is incomplete")
    }
    assertEquals(partitionCounts.size, 24, "all generated LocalTxSubmission annotated rows mapped")

  test("consume generated LocalTxMonitor messages at the typed Scarab boundary"):
    val input =
      rows("localtxmonitor.tsv", "476cc50040858da65ccd430654a71ccfa869a1a8f74bcd286b8647b2d1581074")
    assertEquals(input.size, 24)
    assertEquals(
      input.count(f =>
        f.size == 12 && f(8) == "pass" && f(9) == "pass" && f(10) == "pass" && f(11) == "pass"
      ),
      24
    )
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
      assertEquals(fields.size, 12)
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
            .fold(e => fail(s"seed $seed Scarab typed encode: $e"), identity)
          assertEquals(encoded, wire, s"seed $seed exact original wire roundtrip")
          "matched"
        case DecodeResult.Failed(error) =>
          fail(s"seed $seed typed decode failed: $error")
        case DecodeResult.NeedMore => fail(s"seed $seed incomplete original wire")
    }
    assertEquals(outcomes.size, 24)
    assertEquals(outcomes.count(_ == "matched"), 24)

  test("classify actual generated TxSubmission2 rows at the Conway typed boundary"):
    val input =
      rows("txsubmission2.tsv", "35a6489a5d78aa1e59895f6d4b37a4bc6bb95c974d713b032786f48c9fd396c6")
    assertEquals(input.size, 24)
    assertEquals(
      input.count(f =>
        f.size == 12 && f(8) == "pass" && f(9) == "pass" && f(10) == "pass" && f(11) == "pass"
      ),
      24
    )
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
      assertEquals(fields.size, 12)
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
            .fold(e => fail(s"seed $seed Scarab typed encode: $e"), identity)
          assertEquals(encoded, wire, s"seed $seed exact original wire roundtrip")
          "matched"
        case DecodeResult.Failed(ProtocolError.ExpectedDefiniteArray) =>
          s"unsupported:${fields(6)}"
        case DecodeResult.Failed(error) =>
          fail(s"seed $seed unexpected typed TxSubmission2 decode error: $error")
        case DecodeResult.NeedMore =>
          fail(s"seed $seed complete TxSubmission2 wire unexpectedly needs more bytes")
    }
    assertEquals(outcomes.size, 24)
    assertEquals(
      outcomes.groupMapReduce(identity)(_ => 1)(_ + _),
      Map(
        "matched" -> 13,
        "unsupported:reply-ids" -> 3,
        "unsupported:reply-txs" -> 4,
        "unsupported:request-txs" -> 4
      )
    )

  test("consume upstream TxSubmission2 annotated codec and split properties by typed domain"):
    val input = rows(
      "txsubmission2-annotated.tsv",
      "2dde0b6a37ed1d4682f8ed04bdc0f96d1441aa79c79d0bf2cfe68b2e695e9ecc"
    )
    assertEquals(input.size, 24)
    assertEquals(
      input.groupMapReduce(_(4))(_ => 1)(_ + _),
      Map("ids-blocking" -> 5, "ids-nonblocking" -> 1, "idle" -> 11, "init" -> 4, "txs" -> 3)
    )
    assertEquals(
      input.groupMapReduce(_(6))(_ => 1)(_ + _),
      Map(
        "done" -> 1,
        "init" -> 4,
        "reply-ids" -> 5,
        "reply-txs" -> 3,
        "request-ids-blocking" -> 3,
        "request-ids-nonblocking" -> 6,
        "request-txs" -> 2
      )
    )
    val outcomes = input.map { fields =>
      assertEquals(fields.size, 11)
      assertEquals(fields(0), "txsubmission2-annotated-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 497001 + ordinal)
      assertEquals(fields(3).toInt, 30 + ordinal % 21)
      assertEquals(fields(8), "pass", s"prop_anncodec seed $seed")
      assertEquals(fields(9), "pass", s"prop_anncodec_splits2 seed $seed")
      assertEquals(fields(10), "pass", s"prop_anncodec_splits3 seed $seed")
      val state = fields(4) match
        case "init"            => TxSubmission2.State.Init
        case "idle"            => TxSubmission2.State.Idle
        case "ids-blocking"    => TxSubmission2.State.Ids(blocking = true)
        case "ids-nonblocking" => TxSubmission2.State.Ids(blocking = false)
        case "txs"             => TxSubmission2.State.Txs
        case other => fail(s"unknown TxSubmission2 annotated state $other at seed $seed")
      val sender = fields(5) match
        case "client" => ChainSync.Role.Client
        case "server" => ChainSync.Role.Server
        case other    => fail(s"unknown TxSubmission2 annotated agency $other at seed $seed")
      val wire = bytes(fields(7))
      TxSubmission2.decodePrefix(state, sender, wire) match
        case DecodeResult.Decoded(message, consumed) =>
          assertEquals(consumed, wire.size, s"seed $seed consumed annotated wire")
          val tag = message match
            case TxSubmission2.Message.Init => "init"
            case TxSubmission2.Message.RequestIds(blocking, _, _) =>
              if blocking then "request-ids-blocking" else "request-ids-nonblocking"
            case TxSubmission2.Message.ReplyIds(_)   => "reply-ids"
            case TxSubmission2.Message.RequestTxs(_) => "request-txs"
            case TxSubmission2.Message.ReplyTxs(_)   => "reply-txs"
            case TxSubmission2.Message.Done          => "done"
          assertEquals(tag, fields(6), s"seed $seed annotated constructor")
          val encoded = TxSubmission2
            .encode(state, sender, message)
            .fold(error => fail(s"seed $seed Scarab annotated encode: $error"), identity)
          assertEquals(encoded, wire, s"seed $seed annotated bytes preserved")
          val counts = verifyAllPartitions(
            wire,
            DecodeResult.Decoded(message, wire.size),
            prefix => TxSubmission2.decodePrefix(state, sender, prefix),
            s"TxSubmission2 annotated seed $seed"
          )
          (fields(6), Option.empty[ProtocolError], Some(counts))
        case DecodeResult.Failed(error @ ProtocolError.ExpectedDefiniteArray)
            if Set("reply-ids", "request-txs", "reply-txs").contains(fields(6)) =>
          (fields(6), Some(error), Option.empty[(Int, Int)])
        case DecodeResult.Failed(error @ ProtocolError.ExpectedFourMemberConwayTransaction)
            if fields(6) == "reply-txs" =>
          (fields(6), Some(error), Option.empty[(Int, Int)])
        case DecodeResult.Failed(error @ ProtocolError.ExpectedConwayGenTx)
            if fields(6) == "reply-txs" =>
          (fields(6), Some(error), Option.empty[(Int, Int)])
        case DecodeResult.Failed(error @ ProtocolError.ExpectedConwayTransactionID)
            if Set("reply-ids", "request-txs").contains(fields(6)) =>
          (fields(6), Some(error), Option.empty[(Int, Int)])
        case DecodeResult.Failed(error) =>
          fail(s"seed $seed unsupported TxSubmission2 annotated mismatch: $error")
        case DecodeResult.NeedMore => fail(s"seed $seed annotated wire is incomplete")
    }
    assertEquals(outcomes.size, 24)
    val matched = outcomes.collect { case (tag, None, Some(partitions)) => (tag, partitions) }
    val unsupported = outcomes.collect { case (tag, Some(error), None) => (tag, error) }
    assertEquals(matched.size + unsupported.size, outcomes.size)
    assert(matched.nonEmpty, "no actual annotated row reached Scarab's typed decoder")
    assertEquals(
      unsupported
        .map(_._2)
        .toSet
        .subsetOf(
          Set(
            ProtocolError.ExpectedDefiniteArray,
            ProtocolError.ExpectedFourMemberConwayTransaction,
            ProtocolError.ExpectedConwayGenTx,
            ProtocolError.ExpectedConwayTransactionID
          )
        ),
      true,
      "unsupported generated mock payloads retain their typed Conway boundary error"
    )

  test("consume upstream TxSubmission2 split properties through Scarab's prefix decoder"):
    val splitInput = rows(
      "txsubmission2-splits.tsv",
      "01e45d4a3b7e32b4d92e6cdb5007f933c04f78361a46d94ebd97b1f64f31f503"
    )
    val codecInput = rows(
      "txsubmission2.tsv",
      "35a6489a5d78aa1e59895f6d4b37a4bc6bb95c974d713b032786f48c9fd396c6"
    )
    assertEquals(splitInput.size, 24)
    assertEquals(codecInput.size, splitInput.size)
    assertEquals(splitInput.count(f => f.size == 10 && f(8) == "pass" && f(9) == "pass"), 24)
    val outcomes = splitInput.zip(codecInput).map { (fields, codecFields) =>
      assertEquals(fields.size, 10)
      assertEquals(fields(0), "txsubmission2-splits-v1")
      val seed = fields(1).toInt
      val ordinal = fields(2).toInt
      assertEquals(seed, 495001 + ordinal)
      assertEquals(fields(3).toInt, 30 + ordinal % 21)
      assertEquals(fields(4), codecFields(4), s"seed $seed original state")
      assertEquals(fields(5), codecFields(5), s"seed $seed original agency")
      assertEquals(fields(6), codecFields(6), s"seed $seed original constructor")
      assertEquals(fields(7), codecFields(7), s"seed $seed original wire")
      assertEquals(codecFields(8), "pass", s"seed $seed original codec verdict")
      assertEquals(codecFields(9), "pass", s"seed $seed original CBOR verdict")
      assertEquals(codecFields(10), "pass", s"seed $seed original codec-ID verdict")
      assertEquals(codecFields(11), "pass", s"seed $seed original valid-CBOR verdict")
      assertEquals(fields(8), "pass", s"seed $seed upstream splits2 verdict")
      assertEquals(fields(9), "pass", s"seed $seed upstream splits3 verdict")
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
        case DecodeResult.Failed(ProtocolError.ExpectedDefiniteArray) =>
          assert(
            Set("reply-ids", "request-txs", "reply-txs").contains(fields(6)),
            s"only generic mock transaction payload rows are unsupported at seed $seed"
          )
          (fields(6), None)
        case DecodeResult.Failed(error) =>
          fail(s"seed $seed unexpected typed TxSubmission2 decode error: $error")
        case DecodeResult.NeedMore =>
          fail(s"seed $seed complete TxSubmission2 wire unexpectedly needs more bytes")
        case decoded @ DecodeResult.Decoded(message, consumed) =>
          assertEquals(consumed, wire.size, s"seed $seed consumed complete original wire")
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
            .fold(e => fail(s"seed $seed Scarab typed encode: $e"), identity)
          assertEquals(encoded, wire, s"seed $seed exact original wire roundtrip")
          val counts = verifyAllPartitions(
            wire,
            decoded,
            prefix => TxSubmission2.decodePrefix(state, sender, prefix),
            s"seed $seed"
          )
          (fields(6), Some(counts))
    }
    assertEquals(outcomes.count(_._2.nonEmpty), 13)
    assertEquals(outcomes.count(_._2.isEmpty), 11)
    assertEquals(
      outcomes.collect { case (tag, None) => tag }.groupMapReduce(identity)(_ => 1)(_ + _),
      Map("reply-ids" -> 3, "request-txs" -> 4, "reply-txs" -> 4)
    )
    val mapped = splitInput.zip(outcomes).collect {
      case (fields, (_, Some((twoPartitions, threePartitions)))) =>
        val size = bytes(fields(7)).size
        (size, twoPartitions, threePartitions)
    }
    assertEquals(mapped.map(_._2).sum, mapped.map(_._1 + 1).sum)
    assertEquals(
      mapped.map(_._3).sum,
      mapped.map { case (size, _, _) => (size + 1) * (size + 2) / 2 }.sum
    )
