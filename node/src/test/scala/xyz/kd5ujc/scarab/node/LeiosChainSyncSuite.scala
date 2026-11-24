// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Resource}
import cats.effect.std.AtomicCell
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.io.file.Files
import java.nio.file.Path
import scala.collection.immutable.SortedMap
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.Praos2
import xyz.kd5ujc.scarab.consensus.leios.{
  BytesSize,
  ElId,
  IsBigLedgerPeer,
  LeiosOutstanding,
  PeerOffer,
  StdGen,
  WhetherTxsClosureOffered
}
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, ProtocolState}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{EbAnnouncement, LeiosHeader, PeerError}
import xyz.kd5ujc.scarab.ledger.state.{BlockIssuer, KeyHash, SlotNo, WithOrigin}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.protocol.{ChainSync, EbHash, EbPoint, Era, HfcHeader, ReferenceBuild}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64
import xyz.kd5ujc.scarab.storage.{LeiosStoreLocks, LeiosStoreSuite, RocksLeiosStore}

/** The Leios build's handling of a ChainSync client's accepted headers ([[LeiosChainSync]]),
  * against `checkMsgRollForwardForLeiosOffers`, `noteCertificationClaim`,
  * `processAnnouncementCentrally` and the jump callback (`LeiosDemoLogic.hs` 1628-1849 and
  * 2097-2138, `NodeToNode.hs` 484-526 at ouroboros-consensus 2416493b), on the public Leios
  * testnet's announcing header 64849 and the header 64850 that certifies its endorser block
  * (`fixtures/leios/testnet/headers-notable.tsv`, provenance in its README).
  */
class LeiosChainSyncSuite extends FailsOnUncaught:
  import LeiosStoreSuite.{ok, allowing}

  private val dirs = ResourceFunFixture(Files.forAsync[IO].tempDirectory)
  private val source = Path.of("fixtures/leios/testnet")
  private val setup = NodeSetup
    .fromConfigFile(ReferenceBuild.Leios)(source.resolve("config/config.json"))
    .fold(e => fail(e.message), identity)

  private val notable: Map[String, LeiosHeader] = java.nio.file.Files
    .readAllLines(source.resolve("headers-notable.tsv"))
    .asScala
    .filter(l => l.nonEmpty && !l.startsWith("#"))
    .map(_.split('\t'))
    .map { cols =>
      val envelope = Bytes.fromHex(cols(5)).fold(e => fail(e.message), identity)
      cols(0) -> (HfcHeader.decode(envelope) match
        case Right(HfcHeader.Dijkstra(raw)) =>
          LeiosHeader.decode(raw).fold(e => fail(e.message), identity)
        case other => fail(s"unexpected header: $other"))
    }
    .toMap
  private val announcing = notable("announcing")
  private val certifying = notable("certifying")
  private val announcement: EbAnnouncement =
    announcing.leios.announcement.getOrElse(fail("the announcing header announces nothing"))
  private val bound = BytesSize.wrap(260000)

  /** The header state after `header`, as the gate leaves it: Praos2's state with the header's slot
    * last and its announcement, if it has one, by the hash of its issuer's key (`Praos2.reupdate`),
    * and the view it was validated against.
    */
  private def after(
      header: LeiosHeader,
      bound: LeiosBound = LeiosBound.Of(xyz.kd5ujc.scarab.protocol.LeiosWord.Word32.wrap(260000))
  ): NodeHeaderState =
    val initial = ProtocolState.initial(Era.Conway, setup.identity.shelleyGenesis)
    val praos = initial.dep match
      case ChainDepState.Praos(p) => p
      case other                  => fail(s"unexpected initial protocol: $other")
    val issuer = KeyHash
      .fromBytes[BlockIssuer](Blake2b.hash224.hash(header.issuerVkey))
      .fold(e => fail(e.toString), identity)
    NodeHeaderState.Shelley(
      initial.copy(dep =
        ChainDepState.Praos2(
          Praos2.State(
            praos.copy(lastSlot = WithOrigin.At(SlotNo.fromUnsigned(header.slot.toLong))),
            header.leios.announcement.map(Praos2.AnnouncedBy(issuer, _))
          )
        )
      ),
      None,
      None,
      None,
      bound
    )

  private val election =
    ElId(Word64.clamped(announcing.slot), Blake2b.hash224.hash(announcing.issuerVkey))
  private val point = EbPoint(Word64.clamped(announcing.slot), announcement.hash)

  private final case class Fixture(
      shared: LeiosFetchShared[IO, Int],
      vars: LeiosPeerVars[IO],
      central: LeiosChainSync.Central[IO],
      handling: LeiosChainSync[IO],
      store: RocksLeiosStore[IO]
  )

  private def fixture(dir: fs2.io.file.Path): Resource[IO, Fixture] =
    LeiosStoreLocks
      .resource[IO]
      .flatMap(locks => allowing(RocksLeiosStore.resource[IO](dir / "leios", locks)))
      .evalMap { store =>
        for
          shared <- LeiosFetchShared.create[IO, Int](
            LeiosOutstanding.empty[Int](StdGen.mk(4), Word64.clamped(0))
          )
          vars <- LeiosPeerVars.create[IO](IsBigLedgerPeer.NotBigLedgerPeer)
          central <- AtomicCell[IO].of(LeiosCentral.empty[IO])
          handling = LeiosChainSync(
            shared,
            vars,
            central,
            store,
            _ => IO.pure(None),
            e => IO.raiseError(new AssertionError(e.toString))
          )
        yield Fixture(shared, vars, central, handling, store)
      }

  private def peer[A](run: cats.mtl.Raise[IO, PeerError] ?=> IO[A]): IO[Either[PeerError, A]] =
    Handle.allow[PeerError](run.map(_.asRight[PeerError])).rescue(e => IO.pure(e.asLeft[A]))

  private def woken(shared: LeiosFetchShared[IO, Int]): IO[Boolean] =
    shared.ready.tryTake.map(_.nonEmpty)

  test("the notable headers are an announcement and the header that certifies it") {
    assert(!announcing.leios.blockBodyContainsLeiosCert)
    assert(certifying.leios.blockBodyContainsLeiosCert)
    assertEquals(certifying.prevHash, Some(announcing.hash))
  }

  dirs.test("a certifying roll-forward claims, offers the body and closure at the view's bound") {
    dir =>
      fixture(dir).use { f =>
        for
          result <- peer(f.handling.rolledForward(certifying.dijkstraView, after(announcing)))
          offers <- f.vars.offerings.get
          claims <- f.vars.certificationClaims.get
          wake <- woken(f.shared)
          outstanding <- f.shared.outstanding.get
        yield
          assertEquals(result, Right(()))
          assertEquals(
            offers,
            SortedMap(
              point -> PeerOffer(
                Some(bound),
                Some(BytesSize.wrap(announcement.size)),
                WhetherTxsClosureOffered.Offered
              )
            )(using xyz.kd5ujc.scarab.consensus.leios.LeiosOrders.ebPointOrdering)
          )
          assertEquals(claims, SortedMap(election -> announcement.hash))
          assert(wake)
          // an offer never makes the fetch logic track an endorser block: what it tracks is the
          // certifying header's own announcement, of the next endorser block
          assert(!outstanding.ebState.contains(announcement.hash))
          assertEquals(
            outstanding.ebState.keySet,
            certifying.leios.announcement.map(_.hash).toSet
          )
      }
  }

  dirs.test("a bound the node could not forecast counts the claim and offers nothing") { dir =>
    fixture(dir).use { f =>
      for
        result <- peer(
          f.handling.rolledForward(certifying.dijkstraView, after(announcing, LeiosBound.Unknown))
        )
        offers <- f.vars.offerings.get
        claims <- f.vars.certificationClaims.get
      yield
        assertEquals(result, Right(()))
        assertEquals(offers.size, 0)
        assertEquals(claims, SortedMap(election -> announcement.hash))
    }
  }

  dirs.test("a second endorser block claimed certified for the election costs the connection") {
    dir =>
      fixture(dir).use { f =>
        val rival = EbHash.fromBytes(Bytes(Vector.fill(32)(9.toByte)))
        for
          _ <- f.vars.certificationClaims.set(SortedMap(election -> rival))
          result <- peer(f.handling.rolledForward(certifying.dijkstraView, after(announcing)))
          offers <- f.vars.offerings.get
        yield
          assertEquals(
            result,
            Left(
              PeerError.LeiosTwoCertificationClaims(
                election.slot,
                election.pool,
                rival,
                announcement.hash
              )
            )
          )
          assertEquals(offers.size, 0)
      }
  }

  dirs.test("an announcing roll-forward focuses its endorser block once, and records its point") {
    dir =>
      fixture(dir).use { f =>
        for
          first <- peer(f.handling.rolledForward(announcing.dijkstraView, after(certifying)))
          wakeFirst <- woken(f.shared)
          again <- peer(f.handling.rolledForward(announcing.dijkstraView, after(certifying)))
          wakeAgain <- woken(f.shared)
          outstanding <- f.shared.outstanding.get
          points <- ok(f.store.scanEbPoints)
          counted <- f.central.get
          offers <- f.vars.offerings.get
        yield
          assertEquals((first, again), (Right(()), Right(())))
          assert(wakeFirst)
          assert(!wakeAgain)
          assertEquals(outstanding.elFocus.get(election), Some(announcement.hash))
          assert(outstanding.isFocused(announcement.hash))
          assertEquals(points, Vector(point))
          assertEquals(counted.selfPeer.live.keySet, Set(election))
          // an announcement is not this peer's offer: the roll-forward is not LeiosNotify
          assertEquals(offers.size, 0)
      }
  }

  /** A point at `slot` of a chain the notable headers are not on. */
  private def elsewhere(slot: BigInt): ChainSync.Point =
    ChainSync.Point.Block(
      ChainSync.UInt64.saturating(slot),
      Bytes.wrap(Array.fill(32)(0x5a.toByte))
    )

  private val jumpHeaders = Vector(
    announcing.dijkstraView -> Some(after(certifying)),
    certifying.dijkstraView -> Some(after(announcing))
  )
  private val jumpAnchor = elsewhere(announcing.slot - 1)

  // `past` of `headersWithPredecessorStates` (`NodeToNode.hs` 2128-2144 at 2416493b): split after
  // the anchor or the newest header at the accepted slot, or not at all where neither is there
  test("a jump's fragment is split at the accepted slot, after its anchor, or kept whole") {
    def past(through: Option[BigInt]) =
      LeiosChainSync
        .pastAccepted(through.map(Word64.clamped), jumpAnchor, jumpHeaders)
        .map((h, _) => h.slot)
    val all = Vector(announcing.slot, certifying.slot)
    assertEquals(past(None), all)
    assertEquals(past(Some(announcing.slot - 1)), all)
    assertEquals(past(Some(announcing.slot)), Vector(certifying.slot))
    assertEquals(past(Some(certifying.slot)), Vector.empty)
    // no header and not the anchor at the accepted slot: another chain than the last jump's
    assertEquals(past(Some(certifying.slot + 1)), all)
    assertEquals(past(Some(announcing.slot - 2)), all)
  }

  dirs.test("a jump onto a fork without the last jump's tip slot offers its older headers") { dir =>
    fixture(dir).use { f =>
      for
        // a first jump, accepted through a slot past the certifying header's on another chain
        _ <- peer(f.handling.jumped(elsewhere(certifying.slot + 1), Vector.empty))
        first <- f.vars.maxAcceptedJumpSlot.get
        // then a jump onto a fork that has no header at that slot
        second <- peer(f.handling.jumped(jumpAnchor, jumpHeaders))
        offers <- f.vars.offerings.get
        through <- f.vars.maxAcceptedJumpSlot.get
      yield
        assertEquals(first, Some(Word64.clamped(certifying.slot + 1)))
        assertEquals(second, Right(()))
        // the whole fragment is walked: the certifying header's offer is recorded
        assertEquals(offers.keySet, Set(point))
        // the accepted slot never decreases
        assertEquals(through, first)
    }
  }

  dirs.test("an accepted jump offers what its headers past the last jump would have, once") { dir =>
    fixture(dir).use { f =>
      val headers = jumpHeaders
      for
        _ <- peer(f.handling.jumped(jumpAnchor, headers))
        offers <- f.vars.offerings.get
        through <- f.vars.maxAcceptedJumpSlot.get
        _ <- f.vars.offerings.set(SortedMap.empty(using LeiosOrdersPoint))
        _ <- peer(f.handling.jumped(jumpAnchor, headers))
        again <- f.vars.offerings.get
        outstanding <- f.shared.outstanding.get
      yield
        assertEquals(offers.keySet, Set(point))
        assertEquals(through, Some(Word64.clamped(certifying.slot)))
        // the jump announces nothing, and its headers are not offered twice
        assertEquals(outstanding.ebState, Map.empty)
        assertEquals(again.size, 0)
    }
  }

  private val LeiosOrdersPoint = xyz.kd5ujc.scarab.consensus.leios.LeiosOrders.ebPointOrdering
