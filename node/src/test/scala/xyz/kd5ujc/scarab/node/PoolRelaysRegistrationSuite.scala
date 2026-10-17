// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{LedgerRelay, PoolRelay, PoolRelays}

/** The relays of real pool registrations that carry an IPv6 address: the registration transactions
  * of two mainnet pools and one Preview pool, fetched from the Koios API (`pool_updates`, which
  * gives the address as db-sync decoded it, and `tx_cbor`, the transaction as the chain holds it;
  * `fixtures/mainnet/pool-relays-ipv6.json`, `fixtures/preview/pool-relays-ipv6.json`). The decoder
  * gives the address the explorer gives, which pins the byte order: the four 32-bit words of the
  * address, each little-endian (cardano-ledger-binary `ipv6ToBytes`).
  */
class PoolRelaysRegistrationSuite extends FunSuite:
  private final case class Registration(
      tx: String,
      relays: Bytes,
      ipv4: Option[String],
      ipv6: String,
      port: Int
  )

  private def load(file: String): List[Registration] =
    val text = Files.readString(Path.of(file))
    val json = parser.parse(text).fold(e => fail(e.message), identity)
    json.hcursor
      .downField("registrations")
      .as[List[Json]]
      .fold(e => fail(e.message), identity)
      .map { j =>
        val c = j.hcursor
        def str(name: String): String = c.get[String](name).fold(e => fail(e.message), identity)
        Registration(
          str("txHash"),
          Bytes.fromHex(str("relaysHex")).fold(e => fail(e.toString), identity),
          c.get[Option[String]]("ipv4").fold(e => fail(e.message), identity),
          str("ipv6"),
          c.get[Int]("port").fold(e => fail(e.message), identity)
        )
      }

  private val mainnet = load("fixtures/mainnet/pool-relays-ipv6.json")
  private val preview = load("fixtures/preview/pool-relays-ipv6.json")

  test("the fixtures hold the registrations they say") {
    assertEquals(mainnet.size, 2)
    assertEquals(preview.size, 1)
  }

  for r <- mainnet ++ preview do
    test(s"registration ${r.tx.take(12)}: the IPv6 address is the one the explorer shows") {
      val decoded = PoolRelays.decode(r.relays).fold(e => fail(e.toString), identity)
      decoded match
        case Vector(PoolRelay.SingleHostAddr(Some(port), v4, Some(v6))) =>
          assertEquals(port.toInt, r.port)
          assertEquals(v6.render, r.ipv6)
          assertEquals(v4.map(_.render), r.ipv4)
          // the encoder writes the bytes back
          assertEquals(PoolRelays.encode(decoded), r.relays)
        case other => fail(s"unexpected relays $other")
    }

  test("a registration with both families offers the IPv4 address; one without IPv4 the IPv6 one") {
    val both = PoolRelays.decode(preview.head.relays).fold(e => fail(e.toString), identity)
    PoolRelays.peerRelays(both).map(_.head) match
      case Some(LedgerRelay.Address(ip, port)) =>
        assertEquals(ip.render, "127.0.0.1")
        assertEquals(port.toInt, 6000)
      case other => fail(s"unexpected $other")
    val onlyV6 = PoolRelays.decode(mainnet.head.relays).fold(e => fail(e.toString), identity)
    PoolRelays.peerRelays(onlyV6).map(_.head) match
      case Some(LedgerRelay.Address(ip, _)) => assertEquals(ip.render, mainnet.head.ipv6)
      case other                            => fail(s"unexpected $other")
  }
