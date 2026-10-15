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
end LeiosTestnetHandshakeSuite
