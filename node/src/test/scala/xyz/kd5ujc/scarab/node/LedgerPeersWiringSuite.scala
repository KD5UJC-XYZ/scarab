// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.NonEmptyVector
import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import cats.mtl.Tell
import cats.syntax.all.*
import com.comcast.ip4s.{Host, SocketAddress, host, ip, port}
import munit.CatsEffectSuite
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{
  Ipv4,
  LedgerRelay,
  PoolRelay,
  PoolRelayError,
  PoolRelays,
  RelayPort
}
import xyz.kd5ujc.scarab.ledger.state.{
  CompactCoin,
  Conway,
  IndividualPoolStake,
  KeyHash,
  NewEpochState,
  PoolDistr,
  ShelleyBasedEra,
  Sorted,
  StakePool,
  StakePoolState,
  VRFVerKeyHash
}
import xyz.kd5ujc.scarab.ledger.state.EpochStateGen.given
import xyz.kd5ujc.scarab.ledger.state.LedgerStateGen.given
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.node.read.MirrorEraRead

/** The ledger peers from the ledger state to the roots the governor asks for: the pool relays of
  * `nesPd` read from a Conway state ([[LedgerPeersSource]]), the worker's cache and its source of
  * the stake maps ([[LedgerPeersWorker]]), and the roots ([[NodeRootProvider]]).
  */
class LedgerPeersWiringSuite extends CatsEffectSuite:
  private def key(n: Int): KeyHash[StakePool] =
    KeyHash
      .fromBytes[StakePool](Bytes.fromArray(Array.fill(28)(n.toByte)))
      .fold(e => fail(e.toString), identity)

  private def compact(n: BigInt): CompactCoin =
    CompactCoin.fromBigInt(n).fold(e => fail(e.toString), identity)

  private def port(n: Int): RelayPort = RelayPort.fromInt(n).getOrElse(fail("port"))

  private def v4(a: Int, b: Int, c: Int, d: Int): Ipv4 =
    Ipv4((a << 24) | (b << 16) | (c << 8) | d)

  private def addr(ip: Ipv4, p: Int): PoolRelay =
    PoolRelay.SingleHostAddr(Some(port(p)), Some(ip), None)

  private def relays(rs: PoolRelay*): Bytes = PoolRelays.encode(rs.toVector)

  // ------------------------------------------------------------------ a Conway state with pools

  private lazy val base: NewEpochState[Conway] =
    summon[org.scalacheck.Arbitrary[NewEpochState[Conway]]].arbitrary
      .apply(Gen.Parameters.default.withSize(10), Seed(7L))
      .getOrElse(fail("no state"))

  private def pool(n: Int, current: Option[Bytes]): Option[(KeyHash[StakePool], StakePoolState)] =
    current.map { rs =>
      val sample = summon[org.scalacheck.Arbitrary[StakePoolState]].arbitrary
        .apply(Gen.Parameters.default.withSize(10), Seed(n.toLong))
        .getOrElse(fail("no pool"))
      key(n) -> sample.copy(relays = rs)
    }

  private def share(n: Int, of: Int): IndividualPoolStake =
    IndividualPoolStake(
      Rational(n.toLong, of.toLong),
      compact(n.toLong),
      VRFVerKeyHash
        .fromBytes(Bytes.fromArray(Array.fill(32)(1.toByte)))
        .fold(e => fail(e.toString), identity)
    )

  /** A state whose pool distribution and registered pools are as given. */
  private def state(
      distribution: Seq[(Int, IndividualPoolStake)],
      registered: Seq[(Int, Bytes)],
      future: Seq[(Int, Bytes)]
  ): MirrorEraRead[Conway] =
    given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway
    val cert = base.es.ls.cert
    val pstate = cert.pState.copy(
      stakePools = Sorted.map(registered.flatMap((n, rs) => pool(n, Some(rs))).toVector),
      future = Sorted.map(future.map { (n, rs) =>
        key(n) -> xyz.kd5ujc.scarab.ledger.PoolParams(
          key(n).bytes,
          Bytes.fromArray(Array.fill(32)(1.toByte)),
          BigInt(0),
          BigInt(0),
          Rational.zero,
          xyz.kd5ujc.scarab.ledger
            .RewardAccount(0, xyz.kd5ujc.scarab.ledger.Credential.Key(key(n).bytes)),
          Set.empty,
          rs,
          None
        )
      }.toVector)
    )
    val nes = base.copy[Conway](
      pd = PoolDistr(
        Sorted.map(distribution.map((n, s) => key(n) -> s).toVector),
        base.pd.totalActiveStake
      ),
      es = base.es.copy[Conway](ls = base.es.ls.copy[Conway](cert = cert.copy(pState = pstate)))
    )
    new MirrorEraRead[Conway](nes)

  private val srvOnly = relays(PoolRelay.MultiHostName("srv.example"))
  private val named = relays(PoolRelay.SingleHostName(Some(port(3001)), "b.example"))

  test("the ledger peers are the pool distribution's pools with relays, largest stake first") {
    val read = state(
      distribution = Seq(
        1 -> share(50, 100),
        2 -> share(25, 100),
        3 -> share(25, 100),
        4 -> share(10, 100),
        6 -> share(5, 100),
        7 -> share(5, 100)
      ),
      registered = Seq(
        1 -> relays(
          addr(v4(3, 101, 47, 133), 3001),
          PoolRelay.SingleHostName(Some(port(3001)), "a.example")
        ),
        2 -> srvOnly,
        3 -> named,
        4 -> Bytes.fromArray(Array(0x01.toByte)),
        5 -> relays(addr(v4(9, 9, 9, 9), 1)),
        7 -> Bytes.empty
      ),
      future = Seq(1 -> relays(addr(v4(1, 1, 1, 1), 3002)))
    )
    val got = LedgerPeersSource.of(read).fold(e => fail(e.toString), identity)
    // pool 1 first (its registered relays, then its pending ones), the tie of 2 and 3 in the order
    // of their ids, pool 5 is not in the distribution, 6 is not registered, 7 has no relay
    assertEquals(
      got.pools.map(p => p.stake.share -> p.relays.toVector),
      Vector(
        Rational(1, 2) -> Vector(
          LedgerRelay.Address(v4(3, 101, 47, 133), port(3001)),
          LedgerRelay.Domain("a.example", port(3001)),
          LedgerRelay.Address(v4(1, 1, 1, 1), port(3002))
        ),
        Rational(1, 4) -> Vector(LedgerRelay.Srv("srv.example")),
        Rational(1, 4) -> Vector(LedgerRelay.Domain("b.example", port(3001)))
      )
    )
    // a pool whose relays do not decode is left out and reported, the others are not affected
    assertEquals(got.skipped, Vector(SkippedPool(key(4).bytes, PoolRelayError.NotASequence)))
  }

  test("a pool that registers again keeps its old relays until the new ones take effect") {
    val read = state(
      distribution = Seq(1 -> share(1, 1)),
      registered = Seq(1 -> relays(addr(v4(5, 5, 5, 5), 1))),
      future = Seq(1 -> relays(addr(v4(6, 6, 6, 6), 2)))
    )
    val got = LedgerPeersSource.of(read).fold(e => fail(e.toString), identity)
    assertEquals(
      got.pools.flatMap(_.relays.toVector.toList),
      Vector(
        LedgerRelay.Address(v4(5, 5, 5, 5), port(1)),
        LedgerRelay.Address(v4(6, 6, 6, 6), port(2))
      )
    )
  }

  // ------------------------------------------------------------------ the worker

  private val relayA = LedgerRelay.Address(v4(10, 0, 0, 1), port(3001))
  private val relayB = LedgerRelay.Domain("b.example", port(3001))
  private def pools(stakes: (Rational, LedgerRelay)*): LedgerPools =
    LedgerPools(
      stakes.toVector.map((s, r) => LedgerPool(PoolStake(s), NonEmptyVector.one(r))),
      Vector.empty
    )

  /** A source that counts the reads of the ledger. */
  private final class Counting(slot: WithOrigin[BigInt], ledger: LedgerPools, reads: Ref[IO, Int])
      extends LedgerPeersSource[IO]:
    def latestSlot = IO.pure(slot)
    def pools = reads.update(_ + 1).as(Right(ledger))

  private def worker(
      use: UseLedgerPeers,
      slot: WithOrigin[BigInt],
      ledger: LedgerPools,
      snapshot: Option[LedgerPeerSnapshot[LedgerRelay]] = None
  ): IO[(LedgerPeersWorker[IO], Ref[IO, Int], Ref[IO, Vector[LedgerPeersTrace]])] =
    for
      reads <- Ref.of[IO, Int](0)
      traces <- Ref.of[IO, Vector[LedgerPeersTrace]](Vector.empty)
      given Tell[IO, LedgerPeersTrace] = new Tell[IO, LedgerPeersTrace]:
        def functor = cats.Functor[IO]
        def tell(l: LedgerPeersTrace) = traces.update(_ :+ l)
      w <- LedgerPeersWorker.create[IO](use, new Counting(slot, ledger, reads), snapshot, Rng(5L))
    yield (w, reads, traces)

  private val two = pools(Rational(3, 4) -> relayA, Rational(1, 4) -> relayB)

  test("with ledger peers off the worker offers nothing and does not read the ledger") {
    worker(UseLedgerPeers.Disabled, WithOrigin.At(BigInt(1000)), two).flatMap { (w, reads, _) =>
      (w.request[LedgerKind.All](5), reads.get).tupled.map { (answer, n) =>
        assertEquals(answer, LedgerAnswer.FallBackToPublicRoots[LedgerRelay]())
        assertEquals(n, 0)
      }
    }
  }

  test("before the useLedgerAfterSlot slot the ledger gives nothing; after it, its pools") {
    val after = UseLedgerPeers.Use(AfterSlot.After(BigInt(500)))
    for
      before <- worker(after, WithOrigin.At(BigInt(499)), two)
      a1 <- before._1.request[LedgerKind.All](3)
      reads1 <- before._2.get
      later <- worker(after, WithOrigin.At(BigInt(500)), two)
      a2 <- later._1.request[LedgerKind.All](4)
      reads2 <- later._2.get
      empty <- worker(after, WithOrigin.Origin, two)
      a3 <- empty._1.request[LedgerKind.All](4)
    yield
      assertEquals(a1, LedgerAnswer.FallBackToPublicRoots[LedgerRelay]())
      assertEquals(reads1, 0)
      a2 match
        case LedgerAnswer.Picked(relays, notEnough) =>
          assertEquals(relays.size, 4)
          assert(relays.forall(r => r == relayA || r == relayB))
          // two pools, four wanted
          assert(notEnough)
        case other => fail(s"unexpected $other")
      assertEquals(reads2, 1)
      assertEquals(a3, LedgerAnswer.FallBackToPublicRoots[LedgerRelay]())
  }

  test("the cache lives 1847 seconds, 30 seconds while it is empty and ledger peers are on") {
    val use = UseLedgerPeers.Use(AfterSlot.Always)
    val program =
      for
        full <- worker(use, WithOrigin.At(BigInt(10)), two)
        _ <- full._1.request[LedgerKind.All](1)
        _ <- IO.sleep(1800.seconds)
        _ <- full._1.request[LedgerKind.All](1)
        fullReads <- full._2.get
        _ <- IO.sleep(100.seconds)
        _ <- full._1.request[LedgerKind.All](1)
        fullReadsLater <- full._2.get
        none <- worker(use, WithOrigin.At(BigInt(10)), LedgerPools.empty)
        _ <- none._1.request[LedgerKind.All](1)
        _ <- IO.sleep(29.seconds)
        _ <- none._1.request[LedgerKind.All](1)
        noneReads <- none._2.get
        _ <- IO.sleep(2.seconds)
        _ <- none._1.request[LedgerKind.All](1)
        noneReadsLater <- none._2.get
      yield (fullReads, fullReadsLater, noneReads, noneReadsLater)
    TestControl.executeEmbed(program).map { case (a, b, c, d) =>
      assertEquals((a, b), (1, 2))
      assertEquals((c, d), (1, 2))
    }
  }

  test("the peer snapshot is the source of the maps when it is not older than the ledger") {
    val snapshotPool = SnapshotPool(
      AccPoolStake(Rational(1)),
      PoolStake(Rational(1)),
      NonEmptyVector.one[LedgerRelay](LedgerRelay.Domain("snap.example.", port(3001)))
    )
    def snapshot(slot: BigInt) =
      LedgerPeerSnapshot.V23(
        SnapshotPoint.Block(slot, Bytes.fromArray(Array.fill(32)(1.toByte))),
        2L,
        Vector(snapshotPool)
      )
    val use = UseLedgerPeers.Use(AfterSlot.Always)
    for
      newer <- worker(use, WithOrigin.At(BigInt(100)), two, Some(snapshot(150)))
      fromSnapshot <- newer._1.request[LedgerKind.Big](3)
      older <- worker(use, WithOrigin.At(BigInt(100)), two, Some(snapshot(90)))
      fromLedger <- older._1.request[LedgerKind.Big](3)
      traces <- newer._3.get
    yield
      fromSnapshot match
        case LedgerAnswer.Picked(relays, _) =>
          assert(relays.forall(_ == LedgerRelay.Domain("snap.example.", port(3001))))
        case other => fail(s"unexpected $other")
      fromLedger match
        case LedgerAnswer.Picked(relays, _) =>
          assert(relays.forall(r => r == relayA || r == relayB))
        case other => fail(s"unexpected $other")
      assert(
        traces.exists {
          case LedgerPeersTrace.Refreshed(StakeSource.Snapshot, _, _, _) => true; case _ => false
        },
        traces.toString
      )
  }

  // ------------------------------------------------------------------ the roots

  private type Addr = SocketAddress[Host]
  private val configured: Addr = SocketAddress[Host](host"pub.example", port"3001")
  private val boot: Addr = SocketAddress[Host](host"boot.example", port"3001")

  test(
    "a ledger relay is a peer address; an SRV relay, a bad name and the unspecified address are none"
  ) {
    assertEquals(
      NodeRootProvider.address(relayA),
      Some(SocketAddress[Host](ip"10.0.0.1", port"3001"))
    )
    assertEquals(
      NodeRootProvider.address(LedgerRelay.Domain("b.example.", port(3001))),
      Some(SocketAddress[Host](host"b.example", port"3001"))
    )
    assertEquals(NodeRootProvider.address(LedgerRelay.Srv("s.example")), None)
    assertEquals(NodeRootProvider.address(LedgerRelay.Address(v4(0, 0, 0, 0), port(3001))), None)
    assertEquals(NodeRootProvider.address(LedgerRelay.Domain("not a host", port(3001))), None)
    val v6 = xyz.kd5ujc.scarab.ledger.Ipv6(0x2a02c20622422138L, 1L)
    assertEquals(
      NodeRootProvider.address(LedgerRelay.Address(v6, port(3001))).map(_.host.toString),
      Some("2a02:c206:2242:2138::1")
    )
  }

  private def provider(use: UseLedgerPeers, ledger: LedgerPools) =
    worker(use, WithOrigin.At(BigInt(10)), ledger).map((w, _, _) =>
      NodeRootProvider[IO](Set(configured), Vector(boot), w)
    )

  test(
    "the sensitive state gets the bootstrap peers; otherwise the ledger, else the public roots"
  ) {
    for
      on <- provider(UseLedgerPeers.Use(AfterSlot.Always), two)
      sensitive <- on.request(5, true)
      ledger <- on.request(5, false)
      big <- on.requestBigLedger(5)
      emptyBig <- on.requestBigLedger(0)
      off <- provider(UseLedgerPeers.Disabled, two)
      fallback <- off.request(5, false)
      noBig <- off.requestBigLedger(5)
    yield
      assertEquals(sensitive.map(_._1), Some(PublicRoots(Set.empty[Addr], Set(boot))))
      ledger match
        case Some((roots, ttl)) =>
          assertEquals(roots.ordinary, Set.empty[Addr])
          assertEquals(roots.bootstrap, Set.empty[Addr])
          assert(
            roots.ledger.nonEmpty && roots.ledger.subsetOf(
              Set(
                SocketAddress[Host](ip"10.0.0.1", port"3001"),
                SocketAddress[Host](host"b.example", port"3001")
              )
            )
          )
          assertEquals(ttl, LedgerPeerStake.ReRequestInterval)
        case None => fail("no answer")
      assert(big.exists {
        case (BigLedgerRoots.Ledger(peers), _) => peers.nonEmpty; case _ => false
      })
      assertEquals(
        emptyBig,
        Some(BigLedgerRoots.Ledger(Set.empty[Addr]) -> LedgerPeerStake.ReRequestInterval)
      )
      assertEquals(fallback.map(_._1), Some(PublicRoots(Set(configured), Set.empty[Addr])))
      assertEquals(
        noBig,
        Some(
          BigLedgerRoots.Fallback(
            PublicRoots(Set(configured), Set.empty[Addr])
          ) -> 60.seconds
        )
      )
  }

  test("the dynamic provider preserves ordinary fallback classification and advertisement") {
    val literal: Addr = SocketAddress[Host](ip"192.0.2.41", port"3001")
    for
      built <- worker(UseLedgerPeers.Disabled, WithOrigin.At(BigInt(10)), two)
      (w, _, _) = built
      canceled <- Deferred[IO, Unit]
      runtime <- Ref.of[IO, PeerNetwork.Runtime[IO]](
        PeerNetwork.Runtime(
          generation = BigInt(0),
          roots = PeerNetwork.Roots(
            Nil,
            Set(literal),
            None,
            UseLedgerPeers.Disabled,
            advertise = Map(literal -> true)
          ),
          resolvedLocalRoots = Nil,
          localDnsCache = LocalRootDnsCache.empty[NodeRootProvider.LocalDnsKey, Addr],
          worker = w,
          association = AssociationMode.Unrestricted,
          churn = ChurnLoop.Config(Targets.relay, Regime.kind(_, false)),
          snapshotCheckStarted = false,
          snapshotCheckCanceled = canceled
        )
      )
      rng <- Ref.of[IO, Rng](Rng(1L))
      dns = new PeerDns[IO]:
        def addresses(name: DnsName) =
          IO.raiseError(new AssertionError("literal roots must not resolve DNS"))
        def services(name: DnsName) = IO.raiseError(new AssertionError("no SRV roots configured"))
      result <- NodeRootProvider.dynamic(runtime, dns, rng).requestBigLedger(5)
    yield assertEquals(
      result,
      Some(
        BigLedgerRoots.Fallback(
          PublicRoots(Set(literal), Set.empty[Addr], advertise = Map(literal -> true))
        ) -> 60.seconds
      )
    )
  }
