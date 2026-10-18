// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.nio.file.{Files, Path}
import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.codec.Bytes

/** The relays of a pool registration: `StakePoolRelay` of cardano-ledger-core
  * (`State/StakePool.hs:328-421`), read from the bytes a registration holds. The fixtures: the one
  * real registration with relays the repository holds (a mainnet Shelley block, provenance in
  * `fixtures/mainnet/README.md`), the registrations of the devnet (none has a relay), and bytes
  * written by hand from the reference's encoder.
  */
class PoolRelaysSuite extends ScalaCheckSuite:
  private def hex(s: String): Bytes =
    Bytes.fromHex(s.filterNot(_.isWhitespace)).fold(e => fail(e.toString), identity)

  private def port(n: Int): RelayPort = RelayPort.fromInt(n).getOrElse(fail(s"port $n"))

  private def decoded(s: String): Vector[PoolRelay] =
    PoolRelays.decode(hex(s)).fold(e => fail(s"$e"), identity)

  private def refused(s: String): PoolRelayError =
    PoolRelays.decode(hex(s)).fold(identity, r => fail(s"decoded $r"))

  private val v4 = Ipv4(0x03652f85)

  // ---------------------------------------------------------------------- by hand

  test("a registration without relays is the empty value or an empty array") {
    assertEquals(PoolRelays.decode(Bytes.empty), Right(Vector.empty))
    assertEquals(decoded("80"), Vector.empty)
    assertEquals(decoded("9fff"), Vector.empty, "the indefinite empty array")
  }

  test("a single host address: port, IPv4, no IPv6; the octets are in network order") {
    // [[0, 3001, h'03652f85', null]]
    assertEquals(
      decoded("81 84 00 190bb9 4403652f85 f6"),
      Vector(PoolRelay.SingleHostAddr(Some(port(3001)), Some(v4), None))
    )
    assertEquals(v4.render, "3.101.47.133")
    assertEquals(v4.octets, hex("03652f85"))
  }

  test("a single host address with an IPv6 address and no IPv4 address") {
    // the registration holds the four 32-bit words of the address, each little-endian:
    // 20010db8 00000000 00000000 00000001 is b80d0120 00000000 00000000 01000000
    val wire = "b80d012000000000" + "0000000001000000"
    val relay = decoded(s"81 84 00 19ffff f6 50$wire").head
    val v6 = Ipv6(0x20010db800000000L, 1L)
    assertEquals(relay, PoolRelay.SingleHostAddr(Some(port(65535)), None, Some(v6)))
    assertEquals(v6.render, "2001:db8::1")
    assertEquals(v6.octets, hex("20010db8000000000000000000000001"))
    assertEquals(v6.wire, hex(wire))
    assertEquals(Ipv6.fromWire(hex(wire)), Some(v6))
    assertEquals(Ipv6.fromWire(hex("2001")), None)
  }

  test("a single host name with and without a port; a multi host name") {
    val name = "7072652e6578616d706c65" // "pre.example"
    assertEquals(
      decoded(s"83 83 01 190bb9 6b$name 83 01 f6 6b$name 82 02 6b$name"),
      Vector(
        PoolRelay.SingleHostName(Some(port(3001)), "pre.example"),
        PoolRelay.SingleHostName(None, "pre.example"),
        PoolRelay.MultiHostName("pre.example")
      )
    )
  }

  test(
    "the consensus layer's mapping: IPv4 wins, name without port and SRV are SRV, no port is none"
  ) {
    val v6 = Ipv6(1L, 2L)
    val p = Some(port(3001))
    import PoolRelay.*
    assertEquals(
      SingleHostAddr(p, Some(v4), Some(v6)).ledgerRelay,
      Some(LedgerRelay.Address(v4, port(3001)))
    )
    assertEquals(
      SingleHostAddr(p, None, Some(v6)).ledgerRelay,
      Some(LedgerRelay.Address(v6, port(3001)))
    )
    assertEquals(SingleHostAddr(None, Some(v4), None).ledgerRelay, None)
    assertEquals(SingleHostAddr(p, None, None).ledgerRelay, None)
    assertEquals(
      SingleHostName(p, "a.example").ledgerRelay,
      Some(LedgerRelay.Domain("a.example", port(3001)))
    )
    assertEquals(SingleHostName(None, "a.example").ledgerRelay, Some(LedgerRelay.Srv("a.example")))
    assertEquals(MultiHostName("a.example").ledgerRelay, Some(LedgerRelay.Srv("a.example")))
    assert(LedgerRelay.Srv("a.example").skipped)
    assert(!LedgerRelay.Domain("a.example", port(1)).skipped)
  }

  test("peerRelays drops a pool with no usable relay and keeps SRV ones; connectable drops SRV") {
    val unusable = Vector(PoolRelay.SingleHostAddr(None, Some(v4), None))
    val srvOnly = Vector(PoolRelay.MultiHostName("srv.example"))
    assertEquals(PoolRelays.peerRelays(unusable), None)
    assertEquals(PoolRelays.peerRelays(srvOnly).map(_.length), Some(1))
    assertEquals(PoolRelays.connectable(srvOnly), Vector.empty)
  }

  test("IPv6 text elides the longest run of zero groups only when it has two or more") {
    assertEquals(Ipv6(0L, 0L).render, "::")
    assertEquals(Ipv6(0L, 1L).render, "::1")
    assertEquals(Ipv6(0x0001000000020003L, 0x0004000500060007L).render, "1:0:2:3:4:5:6:7")
    assertEquals(Ipv6(0x2001000000000000L, 0x0000000000000000L).render, "2001::")
  }

  // ---------------------------------------------------------------------- typed refusals

  test("what is not a sequence of relays is refused with the reason") {
    import PoolRelayError.*
    assertEquals(refused("01"), NotASequence)
    assertEquals(refused("a0"), NotASequence)
    assertEquals(refused("8101"), NotARelay(0))
    assertEquals(refused("8180"), NotARelay(0))
    assertEquals(refused("81 82 00 00"), WrongLength(0, 0, 2))
    assertEquals(refused("81 83 03 f6 f6"), UnknownTag(0, 3))
    assertEquals(refused("81 84 00 1a00010000 f6 f6"), BadPort(0))
    assertEquals(refused("81 84 00 20 f6 f6"), BadPort(0))
    assertEquals(refused("81 84 00 f6 4503652f8501 f6"), BadIpv4(0))
    assertEquals(refused("81 84 00 f6 f6 4403652f85"), BadIpv6(0))
    assertEquals(refused("81 83 01 f6 01"), BadDnsName(0))
    assertEquals(refused("82 82 02 61 61 82 02 01"), BadDnsName(1))
  }

  test("a DNS name longer than 128 bytes is refused, 128 bytes is read") {
    def named(n: Int): Bytes = PoolRelays.encode(Vector(PoolRelay.MultiHostName("a" * n)))
    assertEquals(PoolRelays.decode(named(128)).map(_.size), Right(1))
    assertEquals(PoolRelays.decode(named(129)), Left(PoolRelayError.DnsNameTooLong(0, 129)))
    // the bound is in bytes of UTF-8: 65 two-byte characters are 130 bytes
    assertEquals(
      PoolRelays.decode(PoolRelays.encode(Vector(PoolRelay.MultiHostName("é" * 65)))),
      Left(PoolRelayError.DnsNameTooLong(0, 130))
    )
  }

  test("bytes that are not CBOR are refused as such") {
    assert(PoolRelays.decode(hex("ff")).left.exists(_.isInstanceOf[PoolRelayError.NotCbor]))
    assert(PoolRelays.decode(hex("8184")).left.exists(_.isInstanceOf[PoolRelayError.NotCbor]))
  }

  // ---------------------------------------------------------------------- real registrations

  private val EpochSlots: BigInt = BigInt(21600)

  test(
    "the mainnet registration of the Shelley block at slot 4,495,760: one IPv4 relay, port 3001"
  ) {
    val text = Files.readString(Path.of("fixtures/mainnet/shelley-pool-network-block.hex")).trim
    val block = Block.decode(hex(text), EpochSlots) match
      case Right(b: Block.Shelley) => b
      case other                   => fail(s"unexpected $other")
    val registered = block.transactions
      .map(Tx.fromBlock(_, block.era).fold(e => fail(e), identity))
      .flatMap(_.body.certificates)
      .collect { case c: Certificate.RegisterPool => c.params }
    assertEquals(registered.size, 1)
    val relays = PoolRelays.decode(registered.head.relays).fold(e => fail(s"$e"), identity)
    assertEquals(relays, Vector(PoolRelay.SingleHostAddr(Some(port(3001)), Some(v4), None)))
    assertEquals(PoolRelays.ledgerRelays(relays), Vector(LedgerRelay.Address(v4, port(3001))))
    // the stored bytes are the registration's own, and the encoder writes them back
    assertEquals(PoolRelays.encode(relays), registered.head.relays)
  }

  test("the devnet registers its pools without relays; those are no ledger peers") {
    val genesis = Files.readString(Path.of("fixtures/devnet/lsq/genesis/shelley-genesis.json"))
    val relayLists = """"relays"\s*:\s*\[(\s*)\]""".r.findAllMatchIn(genesis).toVector
    assertEquals(relayLists.size, 3, "the three pools of the devnet")
    assertEquals(
      PoolRelays.peerRelays(PoolRelays.decode(Bytes.empty).fold(e => fail(s"$e"), identity)),
      None
    )
  }

  // ---------------------------------------------------------------------- properties

  private val ports: Gen[Option[RelayPort]] =
    Gen.option(Gen.choose(0, RelayPort.MaxValue)).map(_.flatMap(RelayPort.fromInt))
  private val ipv4: Gen[Ipv4] = Arbitrary.arbitrary[Int].map(Ipv4(_))
  private val ipv6: Gen[Ipv6] =
    Gen.zip(Arbitrary.arbitrary[Long], Arbitrary.arbitrary[Long]).map(Ipv6(_, _))
  private val names: Gen[String] =
    Gen.choose(1, 60).flatMap(n => Gen.stringOfN(n, Gen.alphaNumChar))
  private val relay: Gen[PoolRelay] = Gen.oneOf(
    Gen.zip(ports, Gen.option(ipv4), Gen.option(ipv6)).map(PoolRelay.SingleHostAddr(_, _, _)),
    Gen.zip(ports, names).map(PoolRelay.SingleHostName(_, _)),
    names.map(PoolRelay.MultiHostName(_))
  )
  private val relays: Gen[Vector[PoolRelay]] = Gen.listOf(relay).map(_.toVector)

  property("decode after encode is the identity") {
    forAll(relays)(rs => PoolRelays.decode(PoolRelays.encode(rs)) == Right(rs))
  }

  property("encode writes definite-length arrays that decode to the same ledger relays") {
    forAll(relays) { rs =>
      PoolRelays.decode(PoolRelays.encode(rs)).map(PoolRelays.ledgerRelays) ==
        Right(PoolRelays.ledgerRelays(rs))
    }
  }

  property(
    "an address with both families is the IPv4 one; a relay never gives an unusable access point"
  ) {
    forAll(relay) { r =>
      r.ledgerRelay match
        case Some(LedgerRelay.Address(ip, _)) =>
          r match
            case PoolRelay.SingleHostAddr(_, Some(a), _)    => ip == a
            case PoolRelay.SingleHostAddr(_, None, Some(b)) => ip == b
            case _                                          => false
        case Some(LedgerRelay.Domain(_, _)) => r.isInstanceOf[PoolRelay.SingleHostName]
        case Some(LedgerRelay.Srv(n)) =>
          r match
            case PoolRelay.SingleHostName(None, m) => n == m
            case PoolRelay.MultiHostName(m)        => n == m
            case _                                 => false
        case None =>
          r match
            case PoolRelay.SingleHostAddr(p, a, b) => p.isEmpty || (a.isEmpty && b.isEmpty)
            case _                                 => false
    }
  }

  property("an address is its octets: V4 and V6 round-trip through the bytes of the registration") {
    forAll(Gen.oneOf(ipv4, ipv6))(ip => RelayIp.fromOctets(ip.octets).contains(ip))
  }

  property("an IPv6 address round-trips through the four little-endian words of a registration") {
    forAll(ipv6)(ip => Ipv6.fromWire(ip.wire).contains(ip))
  }

  property("decoding arbitrary bytes returns a value or a typed error, never an exception") {
    forAll(Gen.listOf(Arbitrary.arbitrary[Byte])) { bs =>
      PoolRelays.decode(Bytes.fromArray(bs.toArray)).fold(_ => true, _ => true)
    }
  }

  property("decoding mutated registrations returns a value or a typed error") {
    forAll(relays, Gen.choose(0, 40), Arbitrary.arbitrary[Byte]) { (rs, at, b) =>
      val raw = PoolRelays.encode(rs).toArray
      if raw.isEmpty then true
      else
        raw(at % raw.length) = b
        PoolRelays.decode(Bytes.fromArray(raw)).fold(_ => true, _ => true)
    }
  }

  test("a sequence of 2,000 relays is read on a thread with a small stack") {
    val rs =
      Vector.tabulate(2000)(i => PoolRelay.SingleHostName(RelayPort.fromInt(i % 65536), "r" + i))
    val bytes = PoolRelays.encode(rs)
    var result: Option[Either[Throwable, Int]] = None
    val worker = new Thread(
      null,
      () =>
        result = Some(
          try
            Right(
              PoolRelays.decode(bytes).fold(e => throw new IllegalStateException(s"$e"), _.size)
            )
          catch case t: Throwable => Left(t)
        ),
      "small-stack",
      256L * 1024
    )
    worker.start()
    worker.join()
    assertEquals(result.map(_.left.map(_.toString)), Some(Right(2000)))
  }
