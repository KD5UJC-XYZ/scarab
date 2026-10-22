// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import io.circe.{Decoder, Json}
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import com.comcast.ip4s.{IpAddress, SocketAddress, Port}
import xyz.kd5ujc.scarab.network.ConnectionId
import xyz.kd5ujc.scarab.protocol.PeerSharing

/** Every original native sorting result and every pairwise comparison is retained, including
  * duplicate keys, IPv4 byte ordering, IPv6 flow and scope, and real IPv6 TCP endpoints.
  */
class UpstreamConnectionIdSuite extends FunSuite:
  private def field[A: Decoder](json: Json, name: String): A =
    json.hcursor.get[A](name).fold(error => fail(error.toString), identity)
  private def word(value: Long): PeerSharing.Word32 =
    PeerSharing.Word32.from(value).fold(fail(s"Word32 fixture: $value"))(identity)
  private def endpoint(json: Json): ConnectionId.Endpoint =
    val port = PeerSharing.Port.from(field[Int](json, "port")).fold(fail("fixture port"))(identity)
    field[String](json, "tag") match
      case "ipv4" => ConnectionId.Endpoint.Inet4(port, word(field[Long](json, "hostWord")))
      case "ipv6" =>
        field[Vector[Long]](json, "words") match
          case Vector(a, b, c, d) =>
            ConnectionId.Endpoint.Inet6(
              port,
              word(field[Long](json, "flowInfo")),
              word(a),
              word(b),
              word(c),
              word(d),
              word(field[Long](json, "scopeId"))
            )
          case _ => fail("fixture IPv6 does not contain four words")
      case other => fail(s"fixture address tag: $other")
  private val rows = Files
    .readString(Path.of("fixtures/reference/genesis-connection-order/ordering.jsonl"))
    .linesIterator
    .filter(_.nonEmpty)
    .map(line => parse(line).fold(error => fail(error.message), identity))
    .toVector

  test("the full native corpus has eight original cases") { assertEquals(rows.size, 8) }

  rows.foreach { row =>
    val label = field[String](row, "label")
    test(s"native $label preserves complete sorting and all pairwise comparisons") {
      val inputs = field[Vector[Json]](row, "input").map { value =>
        field[Int](value, "id") -> ConnectionId(
          endpoint(field[Json](value, "local")),
          endpoint(field[Json](value, "remote"))
        )
      }
      val byId = inputs.toMap
      assertEquals(inputs.sortBy(_._2).map(_._1), field[Vector[Int]](row, "sortedIds"))
      field[Vector[Json]](row, "comparisons").foreach { comparison =>
        val left = field[Int](comparison, "left")
        val right = field[Int](comparison, "right")
        val expected = field[String](comparison, "ordering") match
          case "LT"  => -1
          case "EQ"  => 0
          case "GT"  => 1
          case other => fail(s"fixture ordering: $other")
        assertEquals(
          Integer.signum(summon[Ordering[ConnectionId]].compare(byId(left), byId(right))),
          expected
        )
      }
    }
    if label == "known-address-byte-roundtrips" || label == "actual-ipv6-loopback-tcp-pair" then
      test(s"native $label retains socket byte conversion and actual TCP metadata fields") {
        field[Vector[Json]](row, "input").foreach { input =>
          Vector("local", "remote").foreach { side =>
            val value = field[Json](input, side)
            val bytes = field[String](value, "tag") match
              case "ipv4" => field[Vector[Int]](value, "octets").map(_.toByte)
              case "ipv6" =>
                field[Vector[Int]](value, "segments").flatMap(n =>
                  Vector((n >>> 8).toByte, n.toByte)
                )
              case other => fail(other)
            val ip = IpAddress.fromBytes(bytes.toArray).fold(fail("fixture socket bytes"))(identity)
            val port =
              Port.fromInt(field[Int](value, "port")).fold(fail("fixture socket port"))(identity)
            assertEquals(
              ConnectionId.Endpoint.fromSocketAddress(SocketAddress(ip, port)),
              Right(endpoint(value))
            )
          }
        }
      }
  }
