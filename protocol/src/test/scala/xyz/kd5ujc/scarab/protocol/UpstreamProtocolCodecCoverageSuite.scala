// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.io.Source
import xyz.kd5ujc.scarab.codec.Bytes

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
