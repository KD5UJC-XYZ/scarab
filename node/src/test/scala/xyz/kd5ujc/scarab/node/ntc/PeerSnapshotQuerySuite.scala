// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.data.NonEmptyVector
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{Ipv4, Ipv6, LedgerRelay, RelayPort}
import xyz.kd5ujc.scarab.network.selection.{LedgerPool, PoolStake}
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}

class PeerSnapshotQuerySuite extends FunSuite:
  private val expected = Files
    .readAllLines(Path.of("fixtures/reference/wallet-queries/peer-snapshots.hex"))
    .asScala
    .toVector
  private val port = RelayPort.fromInt(3001).getOrElse(fail("port is out of range"))
  private val mixed = NonEmptyVector
    .of[LedgerRelay](LedgerRelay.Domain("relay.example", port), LedgerRelay.Srv("srv.example"))
  private val srv = NonEmptyVector.one[LedgerRelay](LedgerRelay.Srv("only.example"))
  private val pools = Vector(
    LedgerPool(PoolStake(Rational(3, 5)), mixed),
    LedgerPool(PoolStake(Rational(3, 10)), srv),
    LedgerPool(PoolStake(Rational(1, 10)), mixed)
  )
  private val point = Point.Block(UInt64.saturating(42), Bytes(Vector.fill(32)(1.toByte)))
  private val forms = Vector(
    ("legacy before SRV", None, false),
    ("legacy with SRV", None, true),
    ("modern big", Some(LedgerPeerKind.Big), true),
    ("modern all", Some(LedgerPeerKind.All), true)
  )
  private val ipPools = Vector(
    LedgerPool(
      PoolStake(Rational.one),
      NonEmptyVector.of[LedgerRelay](
        LedgerRelay.Address(Ipv4(0xc0000201), port),
        LedgerRelay.Address(
          Ipv6(0x20010db800000000L, 1L),
          RelayPort.fromInt(3002).getOrElse(fail("port"))
        )
      )
    )
  )
  private val crossing = Vector(
    pools(0),
    pools(1).copy(stake = PoolStake(Rational(31, 100))),
    pools(2).copy(stake = PoolStake(Rational(9, 100)))
  )
  private val extra = Vector(
    ("origin legacy empty", Vector.empty[LedgerPool[LedgerRelay]], Point.Origin, None, false),
    (
      "origin modern big empty",
      Vector.empty[LedgerPool[LedgerRelay]],
      Point.Origin,
      Some(LedgerPeerKind.Big),
      true
    ),
    (
      "origin modern all empty",
      Vector.empty[LedgerPool[LedgerRelay]],
      Point.Origin,
      Some(LedgerPeerKind.All),
      true
    ),
    ("IPv4 and IPv6 all", ipPools, point, Some(LedgerPeerKind.All), true),
    ("IPv4 and IPv6 big", ipPools, point, Some(LedgerPeerKind.Big), true),
    ("quota crossing all", crossing, point, Some(LedgerPeerKind.All), true),
    ("quota crossing big", crossing, point, Some(LedgerPeerKind.Big), true)
  )
  extra.zipWithIndex.foreach { case ((name, entries, at, kind, srv), i) =>
    test(s"$name: exact released native wire bytes") {
      assertEquals(
        PeerSnapshotQueries.encode(entries, at, 42L, kind, srv).hex,
        expected(i + forms.size)
      )
    }
  }
  forms.zipWithIndex.foreach { case ((name, kind, supportsSrv), i) =>
    test(s"$name: exact bytes from the released native snapshot encoder") {
      assertEquals(
        PeerSnapshotQueries.encode(pools, point, 42L, kind, supportsSrv).hex,
        expected(i)
      )
    }
  }

  test("public acquired queries keep negotiated SRV and GetCBOR result-version semantics") {
    import xyz.kd5ujc.scarab.ledger.{PoolRelays, PoolRelay}
    import xyz.kd5ujc.scarab.ledger.state.*
    import xyz.kd5ujc.scarab.node.read.ChainRead.given
    import xyz.kd5ujc.scarab.protocol.NtcVersion
    val base = WalletQueryCases.cases.headOption.fold(fail("no wallet state"))(_._2)
    val registered = base.es.ls.cert.pState.stakePools.toVector.take(3)
    val mixedRelay = Vector(
      PoolRelay.SingleHostName(Some(port), "relay.example"),
      PoolRelay.MultiHostName("srv.example")
    )
    val encoded = Vector(
      PoolRelays.encode(mixedRelay),
      PoolRelays.encode(Vector(PoolRelay.MultiHostName("only.example"))),
      PoolRelays.encode(mixedRelay)
    )
    val changed =
      registered.zip(encoded).map { case ((id, pool), relays) => id -> pool.copy(relays = relays) }
    val distribution = Sorted.map(registered.zip(pools).map { case ((id, pool), p) =>
      id -> IndividualPoolStake(p.stake.share, CompactCoin.fromUnsigned(1L), pool.vrf)
    })
    val poolState = base.es.ls.cert.pState.copy(stakePools = Sorted.map(changed))
    val nes = base.copy[Conway](
      pd = PoolDistr.empty.copy(distribution = distribution),
      es = base.es
        .copy[Conway](ls = base.es.ls.copy[Conway](cert = base.es.ls.cert.copy(pState = poolState)))
    )
    val view = QueryView(point, Some(BigInt(1)), WalletQueryCases.mirror(nes))
    val env = NtcGolden.queryEnv.copy(networkMagic = 42L)
    def ask(q: LedgerQuery, version: NtcVersion): Bytes =
      QueryAnswers
        .answerOrWhy(env, view, Map.empty, NtcQuery.IfCurrent(6, q), version)
        .fold(e => fail(e.toString), identity)
    def result(index: Int): Bytes = CborOut.build(
      _.array(1).raw(Bytes.fromHex(expected(index)).fold(e => fail(e.message), identity))
    )
    assertEquals(ask(LedgerQuery.GetLedgerPeerSnapshotLegacy, NtcVersion.V21), result(0))
    assertEquals(ask(LedgerQuery.GetLedgerPeerSnapshotLegacy, NtcVersion.V22), result(1))
    assertEquals(ask(LedgerQuery.GetLedgerPeerSnapshotLegacy, NtcVersion.V23), result(1))
    assertEquals(
      ask(LedgerQuery.GetLedgerPeerSnapshot(LedgerPeerKind.Big), NtcVersion.V23),
      result(2)
    )
    assertEquals(
      ask(LedgerQuery.GetLedgerPeerSnapshot(LedgerPeerKind.All), NtcVersion.V23),
      result(3)
    )
    val latest = Bytes.fromHex(expected(1)).fold(e => fail(e.message), identity)
    assertEquals(
      ask(LedgerQuery.GetCBOR(LedgerQuery.GetLedgerPeerSnapshotLegacy), NtcVersion.V21),
      CborOut.build(_.array(1).tag(24).bytes(latest))
    )
  }

  test("invalid modern network magic refuses before the native Word32 encoder") {
    import xyz.kd5ujc.scarab.node.read.ChainRead.given
    val base = WalletQueryCases.cases.headOption.fold(fail("no wallet state"))(_._2)
    val view = QueryView(point, Some(BigInt(1)), WalletQueryCases.mirror(base))
    Vector(-1L, 0x100000000L).foreach { magic =>
      Vector(LedgerPeerKind.All, LedgerPeerKind.Big).foreach { kind =>
        assertEquals(
          QueryAnswers.answerOrWhy(
            NtcGolden.queryEnv.copy(networkMagic = magic),
            view,
            Map.empty,
            NtcQuery.IfCurrent(6, LedgerQuery.GetLedgerPeerSnapshot(kind))
          ),
          Left(Unanswered.Unwritable)
        )
      }
    }
  }
