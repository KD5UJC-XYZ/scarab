// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes

import Handshake.*

/** Handshake replies recorded from the public Leios testnet relay (provenance in
  * fixtures/leios/README.md): our decoder must read them and the negotiation must come out as
  * recorded.
  */
class LeiosTestnetHandshakeSuite extends munit.FunSuite:
  private val rows: Vector[(String, String, String)] =
    Files
      .readString(Path.of("fixtures", "leios", "testnet", "handshake-replies.tsv"))
      .linesIterator
      .filterNot(l => l.startsWith("#") || l.isEmpty)
      .map { l =>
        val p = l.split("\t", -1)
        (p(0), p(1), p(2))
      }
      .toVector

  private def read(hex: String): Message =
    val bytes = Bytes.fromHex(hex).fold(e => fail(e), identity)
    decode(Suite.NodeToNode, State.Confirm, bytes).fold(e => fail(e.message), identity)

  test("the recorded replies decode") {
    assertEquals(rows.size, 4)
    rows.foreach((_, hex, _) => read(hex))
  }

  test("a version query lists exactly 14, 15 and 16, magic 164, peer sharing on, Peras off") {
    val (_, hex, _) = rows.find(_._1 == "query").get
    read(hex) match
      case Message.QueryReply(versions) => assertEquals(versions.map(_._1), Vector(14, 15, 16))
      case other                        => fail(other.toString)
  }

  test("each offered version is accepted as offered, initiator only") {
    rows.filter(_._1.startsWith("accept")).foreach { (label, hex, _) =>
      val version = label.stripPrefix("accept-").toInt
      val client = clientStart(Suite.NodeToNode, Vector(version -> Data(164, initiatorOnly = true)))
        .fold(e => fail(e), identity)
        ._1
      client.receive(read(hex)).fold(e => fail(e), identity)._2 match
        case Result.Negotiated(v, d) =>
          assertEquals(v, version)
          assertEquals(d.magic, 164L)
        case other => fail(other.toString)
    }
  }

  private def hexOf(m: Message): String =
    encode(Suite.NodeToNode, m).fold(e => fail(e), identity).hex

  private val relayOffers: Vector[(Int, Data)] =
    Vector(14, 15, 16).map(_ -> Data(164, initiatorOnly = false, peerSharing = true))

  private val ours: Vector[(Int, Data)] =
    nodeToNodeOffers(NtnVersion.V16, Data(164, initiatorOnly = true))

  test(
    "our proposal of 14 to 16 for the testnet is the bytes below (the relay's data per version)"
  ) {
    val proposal = clientStart(Suite.NodeToNode, ours).fold(e => fail(e), identity)._2
    assertEquals(
      hexOf(proposal),
      "8200a3" + "0e8418a4f500f4" + "0f8418a4f500f4" + "108518a4f500f4f4"
    )
  }

  test(
    "the relay's query reply carries the data the relay offers: duplex, peer sharing, no Peras"
  ) {
    val (_, hex, _) = rows.find(_._1 == "query").get
    read(hex) match
      case Message.QueryReply(versions) =>
        versions.foreach { (v, term) =>
          assertEquals(
            decodeData(Suite.NodeToNode, v, term),
            Right(Data(164, initiatorOnly = false, peerSharing = true))
          )
        }
      case other => fail(other.toString)
  }

  test("a relay offering 14 to 16 answers our proposal with version 16, as the recorded bytes") {
    val proposal = clientStart(Suite.NodeToNode, ours).fold(e => fail(e), identity)._2
    val (answer, result) =
      responder(Suite.NodeToNode, relayOffers, proposal).fold(e => fail(e), identity)
    // the agreed data: initiator only (the smaller diffusion mode), no peer sharing (we do not
    // offer it), no Peras; byte for byte what the relay sent for version 16
    assertEquals(hexOf(answer), rows.find(_._1 == "accept-16").get._2)
    assertEquals(result, Result.Negotiated(16, Data(164, initiatorOnly = true)))
  }

  test("a relay that accepts only 14 and 15 (the released maximum) answers with 15") {
    val proposal = clientStart(Suite.NodeToNode, ours).fold(e => fail(e), identity)._2
    val released = relayOffers.filter(_._1 <= NtnVersion.LatestReleased.number)
    val (answer, _) = responder(Suite.NodeToNode, released, proposal).fold(e => fail(e), identity)
    assertEquals(hexOf(answer), rows.find(_._1 == "accept-15").get._2)
  }
end LeiosTestnetHandshakeSuite
