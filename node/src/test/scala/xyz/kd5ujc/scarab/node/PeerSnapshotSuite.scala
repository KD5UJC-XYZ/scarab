// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.Path
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{Ipv4, Ipv6, LedgerRelay, RelayPort}
import xyz.kd5ujc.scarab.network.selection.*

/** The `peerSnapshotFile` of the topology, read as `FromJSON (LedgerPeerSnapshot BigLedgerPeers)`
  * reads it (ouroboros-network 1.2.0.0, `Type.hs:254-299`). The one real file is the snapshot of
  * the public Leios testnet (`fixtures/leios/testnet/config/peer-snapshot.json`, unchanged,
  * provenance in `fixtures/leios/README.md`); the others are written by hand in the format of the
  * reference's `ToJSON` (`Type.hs:197-231`).
  */
class PeerSnapshotSuite extends FunSuite:
  private def port(n: Int): RelayPort = RelayPort.fromInt(n).getOrElse(fail(s"port $n"))

  private def ok(text: String): LedgerPeerSnapshot[LedgerRelay] =
    PeerSnapshot.decode(text).fold(e => fail(s"$e"), identity)

  private def refused(text: String): PeerSnapshotError =
    PeerSnapshot.decode(text).fold(identity, s => fail(s"read $s"))

  private def pool(relays: String, acc: String = "1", rel: String = "1") =
    s"""{"accumulatedStake": $acc, "relativeStake": $rel, "relays": $relays}"""

  private def v2(body: String, slot: String = "100", version: Int = 2) =
    s"""{"version": $version, "slotNo": $slot, "bigLedgerPools": [$body]}"""

  private val host = """[{"address": "relay.example", "port": 3001}]"""

  test("the Leios testnet file: version 23, the point, the magic, three big pools") {
    val snapshot = PeerSnapshot
      .read(Path.of("fixtures/leios/testnet/config/peer-snapshot.json"))
      .fold(e => fail(s"$e"), identity)
    snapshot match
      case LedgerPeerSnapshot.V23(point, magic, pools) =>
        assertEquals(magic, 164L)
        point match
          case SnapshotPoint.Block(slot, hash) =>
            assertEquals(slot, BigInt(280825))
            assertEquals(
              hash.hex,
              "85f25eb3a3a6eed5ca6e18aa47701992febc6a277cc7481f20a32c8bc1f91d69"
            )
          case other => fail(s"$other")
        assertEquals(pools.size, 3)
        // the stakes are the exact decimals of the file, not doubles
        assertEquals(
          pools.head.accumulated.share,
          Rational.fromDecimal(BigDecimal("0.35901138711667363"))
        )
        assertEquals(pools.head.stake.share, pools.head.accumulated.share)
        assertEquals(pools.last.accumulated.share, Rational.one)
        // a domain gets the trailing dot of the reference's FromJSON
        assertEquals(
          pools.head.relays.toVector,
          Vector(LedgerRelay.Domain("leios-node.play.dev.cardano.org.", port(3001)))
        )
      case other => fail(s"$other")
    assertEquals(snapshot.slot, WithOrigin.At(BigInt(280825)))
  }

  test("the snapshot of that file beats a ledger behind it and gives the big peers of the file") {
    val snapshot = PeerSnapshot
      .read(Path.of("fixtures/leios/testnet/config/peer-snapshot.json"))
      .fold(e => fail(s"$e"), identity)
    val chosen = LedgerPeerStake.stakeMapWithSlotOverSource[LedgerRelay](
      WithOrigin.At(280000),
      LedgerSupply.BeforeSlot(),
      Some(snapshot),
      LedgerPeersCache.empty,
      AfterSlot.After(151200)
    )
    assertEquals(chosen.source, StakeSource.Snapshot)
    assertEquals(chosen.maps.big.size, 3)
    assertEquals(chosen.maps.all.size, 3)
    val picked = LedgerPeerStake
      .answer[LedgerKind.Big, LedgerRelay](
        LedgerPeersCache(chosen.maps, chosen.cachedSlot, None),
        5,
        Rng(1L)
      )
      ._2
    picked match
      case LedgerAnswer.Picked(relays, _) => assertEquals(relays.size, 5)
      case other                          => fail(s"$other")
  }

  test("versions 1 and 2 give the slot, a number or \"origin\"; the key is version") {
    val one = ok(v2(pool(host), version = 1))
    val two = ok(v2(pool(host), slot = "\"origin\""))
    assertEquals(one.slot, WithOrigin.At(BigInt(100)))
    assertEquals(two.slot, WithOrigin.Origin)
    assert(one.isInstanceOf[LedgerPeerSnapshot.V2[?]])
    // both give the same pools
    assertEquals(one.pools, two.pools)
  }

  test("version 23 under NodeToClientVersion; the point is the origin or a block") {
    val atOrigin = ok(
      s"""{"NodeToClientVersion": 23, "NetworkMagic": 2, "Point": "origin", "bigLedgerPools": [${pool(
          host
        )}]}"""
    )
    assertEquals(atOrigin.slot, WithOrigin.Origin)
    val atBlock = ok(
      s"""{"NodeToClientVersion": 23, "NetworkMagic": 2, "bigLedgerPools": [${pool(host)}],
         |"Point": {"blockPointSlot": 7, "blockPointHash": "ab"}}""".stripMargin
    )
    atBlock match
      case LedgerPeerSnapshot.V23(SnapshotPoint.Block(slot, hash), magic, _) =>
        assertEquals((slot, hash, magic), (BigInt(7), Bytes.fromArray(Array(0xab.toByte)), 2L))
      case other => fail(s"$other")
  }

  test("a relay: an IP address is an address, a name a domain with a dot, no port is SRV") {
    val relays =
      """[{"address": "192.0.2.1", "port": 3001},
        | {"address": "2001:db8::1", "port": 3002},
        | {"address": "a.example", "port": 3003},
        | {"address": "b.example.", "port": 3004},
        | {"address": "srv.example"},
        | {"address": "c.example", "port": "3005"},
        | {"address": "d.example", "port": 30.5}]""".stripMargin
    val got = ok(v2(pool(relays))).pools.head.relays.toVector
    assertEquals(
      got,
      Vector(
        LedgerRelay.Address(Ipv4(0xc0000201), port(3001)),
        LedgerRelay.Address(Ipv6(0x20010db800000000L, 1L), port(3002)),
        LedgerRelay.Domain("a.example.", port(3003)),
        LedgerRelay.Domain("b.example.", port(3004)),
        LedgerRelay.Srv("srv.example."),
        LedgerRelay.Srv("c.example."),
        LedgerRelay.Srv("d.example.")
      )
    )
  }

  test("the stakes are exact decimals, integers and exponents included") {
    val p = ok(v2(pool(host, acc = "0.1", rel = "1e-1"))).pools.head
    assertEquals(p.accumulated.share, Rational(1, 10))
    assertEquals(p.stake.share, Rational(1, 10))
  }

  test("what is wrong is named") {
    import PeerSnapshotError.*
    assert(refused("{").isInstanceOf[NotJson])
    assertEquals(refused("{}"), Missing("version"))
    assertEquals(refused("""{"version": "2"}"""), Malformed("version"))
    assertEquals(refused("""{"version": 3}"""), UnsupportedVersion(3))
    assertEquals(refused("""{"version": 2, "bigLedgerPools": []}"""), Missing("slotNo"))
    assertEquals(
      refused("""{"version": 2, "slotNo": -1, "bigLedgerPools": []}"""),
      Malformed("slotNo")
    )
    assertEquals(refused("""{"version": 2, "slotNo": 1}"""), Missing("bigLedgerPools"))
    assertEquals(refused(v2(pool("[]"))), NoRelays(0))
    assertEquals(refused(v2(pool(host) + "," + pool("[]"))), NoRelays(1))
    assertEquals(
      refused(v2("""{"relativeStake": 1, "relays": []}""")),
      Missing("bigLedgerPools[0].accumulatedStake")
    )
    assertEquals(
      refused(v2(pool(host, acc = "\"1\""))),
      Malformed("bigLedgerPools[0].accumulatedStake")
    )
    assertEquals(
      refused(v2(pool(host, rel = "-0.5"))),
      Malformed("bigLedgerPools[0].relativeStake")
    )
    assertEquals(
      refused(v2(pool("""[{"address": "a.example", "port": 70000}]"""))),
      Malformed("bigLedgerPools[0].relays[0].port")
    )
    assertEquals(refused(v2(pool("""[{"port": 1}]"""))), Missing("address"))
    assertEquals(
      refused("""{"version": 23, "NetworkMagic": 1, "bigLedgerPools": []}"""),
      Missing("Point")
    )
    assertEquals(
      refused(
        """{"version": 23, "Point": "origin", "NetworkMagic": 4294967296, "bigLedgerPools": []}"""
      ),
      Malformed("NetworkMagic")
    )
  }

  test("a file that is not there is a typed error") {
    assert(
      PeerSnapshot
        .read(Path.of("fixtures/leios/testnet/config/no-such-snapshot.json"))
        .left
        .exists(_.isInstanceOf[PeerSnapshotError.Unreadable])
    )
  }
