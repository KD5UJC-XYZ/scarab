// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.io.Source
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Mux.*

/** A bounded consumer for cases exported from the pinned upstream `prop_sdu_codec`; see the
  * adjacent fixture manifest for generator and source provenance.
  */
class MuxUpstreamConformanceSuite extends munit.FunSuite:
  private val fixtureSha256 = "49befe17b1a319bc3723fcce332517976203aa3296494dc74555354d29c87105"

  private def resource(path: String): InputStream =
    Option(getClass.getResourceAsStream(path)).getOrElse(fail(s"missing classpath fixture $path"))

  private def fixture: Vector[String] =
    val stream = resource("/upstream/network-mux-sdu/cases.tsv")
    try
      val raw = stream.readAllBytes()
      val digest =
        MessageDigest.getInstance("SHA-256").digest(raw).map(b => f"${b & 0xff}%02x").mkString
      assertEquals(digest, fixtureSha256)
      Source.fromString(new String(raw, StandardCharsets.UTF_8)).getLines().toVector
    finally stream.close()

  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.toString), identity)

  test("consume the exact seeded upstream mux SDU codec cases"):
    val rows = fixture.map { line =>
      val fields = line.split("\t", -1)
      assertEquals(fields.length, 11)
      assertEquals(fields(0), "sdu-codec-v1")
      assertEquals(fields(10), "pass")
      val ordinal = fields(2).toInt
      val timestamp = fields(4).toLong
      val protocol = fields(5).toInt
      val direction = fields(6) match
        case "initiator" => Direction.Initiator
        case "responder" => Direction.Responder
        case other       => fail(s"unknown upstream direction $other")
      val length = fields(7).toInt
      val upstreamBytes = bytes(fields(8))
      val independentReferenceBytes = bytes(fields(9))
      val payload = Bytes(Vector.fill(length)(0xa5.toByte))
      val sdu = Sdu(timestamp, protocol, direction, payload)

      assertEquals(fields(1).toInt, 490049 + ordinal)
      assertEquals(fields(3).toInt, 30 + ordinal % 21)
      assertEquals(upstreamBytes, independentReferenceBytes, s"upstream wire bytes at row $ordinal")
      assertEquals(upstreamBytes.size, HeaderBytes + length, s"wire size at row $ordinal")

      if length == 0 then
        // The upstream property accepts encode/reference equality and expects its decoder to
        // reject the zero-size SDU. Scarab's public encoder rejects zero before emitting bytes.
        assertEquals(
          encode(sdu),
          Left(Error.PayloadLengthOutOfRange(0, WireMaxPayloadBytes)),
          s"documented zero-length policy divergence at row $ordinal"
        )
        "divergence"
      else
        assertEquals(encode(sdu), Right(upstreamBytes), s"original wire bytes at row $ordinal")
        assertEquals(decode(upstreamBytes), Right(sdu), s"typed decode at row $ordinal")
        "match"
    }

    assertEquals(rows.size, 40)
    assertEquals(rows.count(_ == "match"), 34)
    assertEquals(rows.count(_ == "divergence"), 6)
