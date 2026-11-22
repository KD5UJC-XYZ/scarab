// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.OfferedPart
import cats.effect.{IO, Resource}
import cats.effect.std.AtomicCell
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import fs2.concurrent.SignallingRef
import fs2.io.file.Files
import java.nio.file.Path
import scala.collection.immutable.{Queue as Fifo, SortedMap}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.leios.{AnnouncingHeader, LeiosOutstanding, StdGen}
import xyz.kd5ujc.scarab.consensus.leios.IsBigLedgerPeer
import xyz.kd5ujc.scarab.consensus.leios.LeiosOrders.given
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, ProtocolState}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{LeiosHeader, PeerError}
import xyz.kd5ujc.scarab.ledger.state.{BlockIssuer, KeyHash, SlotNo, WithOrigin}
import xyz.kd5ujc.scarab.network.{
  Bearer,
  ChainSyncServer,
  FailsOnUncaught,
  HotBundle,
  LeiosFetchClient,
  LeiosNotifyClient,
  LeiosNotifyServer,
  Mux,
  NetworkError,
  NodeToNode,
  NodeToNodeServer,
  Peers,
  Protocols,
  Served,
  ServerHandlers,
  TxSubmissionServer
}
import xyz.kd5ujc.scarab.network.LeiosNotifyServer.{Reply, Requested}
import xyz.kd5ujc.scarab.protocol.{
  ChainSync,
  ChainSyncFixtures,
  EbPoint,
  Era,
  HfcHeader,
  LeiosFetch,
  LeiosNotify,
  ReferenceBuild,
  TxSubmission2Fixtures
}
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word32, Word64}
import xyz.kd5ujc.scarab.storage.{LeiosStore, LeiosStoreLocks, LeiosStoreSuite, RocksLeiosStore}

import Peers.*

/** The Leios build's LeiosNotify servers and their relay ([[LeiosNotifyRelay]], [[LeiosCentral]],
  * [[LeiosNotifyOutgoing]]) against `hLeiosNotifyServer` (`NodeToNode.hs` 769-936 at
  * ouroboros-consensus 2416493b), `onAnnouncementCentral` (`LeiosDemoLogic/Announcements.hs`
  * 338-414) and `leiosOfferRelayDecision` (`LeiosDemoLogic.hs` 2353-2363), on the public Leios
  * testnet's announcing header 64849 and the header 64850 after it, which announces the next
  * endorser block (`fixtures/leios/testnet/headers-notable.tsv`, provenance in its README).
  */
class LeiosNotifyRelaySuite extends FailsOnUncaught:
  import LeiosStoreSuite.{ok, allowing, body, tx, word32}

  private val dirs = ResourceFunFixture(Files.forAsync[IO].tempDirectory)
  private val source = Path.of("fixtures/leios/testnet")
  private val setup = NodeSetup
    .fromConfigFile(ReferenceBuild.LeiosB004fe0c)(source.resolve("config/config.json"))
    .fold(e => fail(e.message), identity)

  /** The fixture's headers by name, with the envelope the reference sent each in. */
  private val captured: Map[String, (Bytes, LeiosHeader)] = java.nio.file.Files
    .readAllLines(source.resolve("headers-notable.tsv"))
    .asScala
    .filter(l => l.nonEmpty && !l.startsWith("#"))
    .map(_.split('\t'))
    .map { cols =>
      val envelope = Bytes.fromHex(cols(5)).fold(e => fail(e.message), identity)
      cols(0) -> (
        envelope,
        HfcHeader.decode(envelope) match
          case Right(HfcHeader.Dijkstra(raw)) =>
            LeiosHeader.decode(raw).fold(e => fail(e.message), identity)
          case other => fail(s"unexpected header: $other")
      )
    }
    .toMap
  private val notable: Map[String, LeiosHeader] = captured.view.mapValues(_._2).toMap
  private val announcing = notable("announcing")
  private val certifying = notable("certifying")
  private def anc(header: LeiosHeader): AnnouncingHeader =
    AnnouncingHeader.of(header).getOrElse(fail("the header announces nothing"))
  private val first = anc(announcing)
  private val next = anc(certifying)
  private def announcementOf(a: AnnouncingHeader): Reply =
    Reply.Announcement(HfcHeader.Dijkstra(a.header.raw))

  /** Another header of `a` 's election, announcing another endorser block: an equivocation. */
  private val rival: AnnouncingHeader =
    first.copy(
      header = first.header.copy(hash = Bytes(Vector.fill(32)(7.toByte))),
      fields = first.fields.copy(ebHash = next.fields.ebHash)
    )

  private def lead(n: Long): LeiosMinOfferLead = LeiosMinOfferLead(Word64.fromUnsigned(n))
  private def w64(n: BigInt): Word64 = Word64.clamped(n)
  private val one = LeiosNotifyOutgoing.empty.copy(credits = 1)

  // --- the offer lead ---

  test("an endorser block is offered from the lead past the immutable tip, the origin's 0") {
    assertEquals(lead(10).decision(None, w64(9)), ShouldRelay.DoNotRelay)
    assertEquals(lead(10).decision(None, w64(10)), ShouldRelay.DoRelay)
    assertEquals(lead(10).decision(Some(w64(100)), w64(109)), ShouldRelay.DoNotRelay)
    assertEquals(lead(10).decision(Some(w64(100)), w64(110)), ShouldRelay.DoRelay)
    assertEquals(lead(0).decision(Some(w64(100)), w64(100)), ShouldRelay.DoRelay)
  }

  test("the lead is added to the immutable tip's slot modulo 2^64, as the reference adds") {
    // `unSlotNo slot < unSlotNo immTipSlot + minLead` in Word64: the largest lead the node reads
    // (`LeiosSettingsSuite`) with a tip at 1 bounds nothing, and with a tip at 0 everything
    val largest = LeiosMinOfferLead(Word64.fromUnsigned(-1L))
    assertEquals(largest.decision(Some(w64(1)), w64(0)), ShouldRelay.DoRelay)
    assertEquals(largest.decision(None, w64(BigInt(2).pow(64) - 2)), ShouldRelay.DoNotRelay)
    assertEquals(largest.decision(Some(w64(2)), w64(1)), ShouldRelay.DoRelay)
    assertEquals(largest.decision(Some(w64(2)), w64(0)), ShouldRelay.DoNotRelay)
  }

  // --- one peer's queue ---

  test("a request is a credit, refused where the peer holds the depth's") {
    val full = LeiosNotifyOutgoing.empty.copy(credits = 2)
    assertEquals(LeiosNotifyOutgoing.empty.requested(2).map(_.credits), Some(1))
    assertEquals(full.requested(2), None)
  }

  test("an announcement takes a credit and is recorded; with none it is dropped unrecorded") {
    assertEquals(LeiosNotifyOutgoing.empty.announcing(first), None)
    assertEquals(
      one.announcing(first),
      Some(
        LeiosNotifyOutgoing(
          0,
          Fifo(announcementOf(first)),
          SortedMap(first.fields.point -> Set.empty[OfferedPart])
        )
      )
    )
  }

  test("a re-announcement keeps what was offered for the point") {
    val offered = LeiosNotifyOutgoing(
      1,
      Fifo.empty,
      SortedMap(first.fields.point -> Set[OfferedPart](OfferedPart.Body))
    )
    assertEquals(
      offered.announcing(first).map(_.announced),
      Some(SortedMap(first.fields.point -> Set[OfferedPart](OfferedPart.Body)))
    )
  }

  test("an offer goes out once per part, only for an announced point, to be relayed, on a credit") {
    val p = first.fields.point
    val offer = Reply.BlockOffer(p, word32(5))
    val announced = LeiosNotifyOutgoing(2, Fifo.empty, SortedMap(p -> Set.empty[OfferedPart]))
    val once = announced.offering(p, OfferedPart.Body, offer, ShouldRelay.DoRelay)
    assertEquals(
      once,
      LeiosNotifyOutgoing(1, Fifo(offer), SortedMap(p -> Set[OfferedPart](OfferedPart.Body)))
    )
    // the same part again, or the point not announced, or not to be relayed, or no credit
    assertEquals(once.offering(p, OfferedPart.Body, offer, ShouldRelay.DoRelay), once)
    assertEquals(
      LeiosNotifyOutgoing.empty
        .copy(credits = 1)
        .offering(p, OfferedPart.Body, offer, ShouldRelay.DoRelay),
      LeiosNotifyOutgoing.empty.copy(credits = 1)
    )
    assertEquals(announced.offering(p, OfferedPart.Body, offer, ShouldRelay.DoNotRelay), announced)
    val spent = announced.copy(credits = 0)
    assertEquals(spent.offering(p, OfferedPart.Body, offer, ShouldRelay.DoRelay), spent)
    // the closure is independent of the body
    assertEquals(
      once.offering(p, OfferedPart.Closure, Reply.BlockTxsOffer(p), ShouldRelay.DoRelay).announced,
      SortedMap(p -> Set[OfferedPart](OfferedPart.Body, OfferedPart.Closure))
    )
  }

  test("what a peer was announced is pruned below the immutable tip's slot") {
    val p = first.fields.point
    val held = LeiosNotifyOutgoing(0, Fifo.empty, SortedMap(p -> Set.empty[OfferedPart]))
    assertEquals(held.prunedTo(Some(p.slot)).announced.keySet, Set(p))
    assertEquals(held.prunedTo(Some(w64(p.slot.toBigInt + 1))).announced.isEmpty, true)
    assertEquals(held.prunedTo(None).announced.keySet, Set(p))
  }

  // --- the central relay ---

  private def queue(credits: Int): IO[SignallingRef[IO, LeiosNotifyOutgoing]] =
    SignallingRef.of[IO, LeiosNotifyOutgoing](LeiosNotifyOutgoing.empty.copy(credits = credits))

  test("an election's first announcement goes to every peer with a credit, its second to those") {
    for
      (a, b) <- (queue(2), queue(0)).tupled
      (ta, tb) <- (IO.unique, IO.unique).tupled
      central <- AtomicCell[IO].of(LeiosCentral.empty[IO].inserted(ta, a).inserted(tb, b))
      published <- SignallingRef.of[IO, Int](0)
      // the relay comes first, then what is published locally
      seen <- SignallingRef.of[IO, Vector[Reply]](Vector.empty)
      local = a.get.flatMap(o => seen.set(o.messages.toVector)) *> published.update(_ + 1)
      _ <- LeiosCentral.announced(central, ShouldRelay.DoRelay, first, local)
      // a peer with no credit missed the first; given one now, it is not sent the equivocation
      _ <- b.update(_.copy(credits = 1))
      _ <- LeiosCentral.announced(central, ShouldRelay.DoRelay, rival, local)
      // a repeat and a third change nothing, and publish nothing
      _ <- LeiosCentral.announced(central, ShouldRelay.DoRelay, first, local)
      third = rival.copy(header = rival.header.copy(hash = Bytes(Vector.fill(32)(8.toByte))))
      _ <- LeiosCentral.announced(central, ShouldRelay.DoRelay, third, local)
      (sa, sb, count, before, st) <- (a.get, b.get, published.get, seen.get, central.get).tupled
    yield
      assertEquals(sa.messages.toVector, Vector(announcementOf(first), announcementOf(rival)))
      assertEquals(sb.messages.toVector, Vector.empty)
      assertEquals(count, 2)
      assertEquals(before, Vector(announcementOf(first), announcementOf(rival)))
      assertEquals(st.gate, SortedMap(first.fields.election -> Set(ta)))
  }

  test("an announcement not to be relayed is counted and published, and sent to nobody") {
    for
      a <- queue(1)
      ta <- IO.unique
      central <- AtomicCell[IO].of(LeiosCentral.empty[IO].inserted(ta, a))
      published <- SignallingRef.of[IO, Int](0)
      _ <- LeiosCentral.announced(central, ShouldRelay.DoNotRelay, first, published.update(_ + 1))
      (sa, count, st) <- (a.get, published.get, central.get).tupled
    yield
      assertEquals(sa, LeiosNotifyOutgoing.empty.copy(credits = 1))
      assertEquals(count, 1)
      assertEquals(st.selfPeer.live.keySet, Set(first.fields.election))
      assertEquals(st.gate.isEmpty, true)
  }

  test("a peer leaves the central state and the gate; the immutable tip prunes the gate") {
    for
      a <- queue(1)
      ta <- IO.unique
      central <- AtomicCell[IO].of(LeiosCentral.empty[IO].inserted(ta, a))
      _ <- LeiosCentral.announced(central, ShouldRelay.DoRelay, first, IO.unit)
      st <- central.get
    yield
      assertEquals(st.deleted(ta).queues.keySet, Set.empty)
      assertEquals(st.deleted(ta).gate.isEmpty, true)
      assertEquals(st.prunedToImmTip(first.fields.election.slot).gate.keySet, st.gate.keySet)
      val past = w64(first.fields.election.slot.toBigInt + 1)
      assertEquals(st.prunedToImmTip(past).gate.isEmpty, true)
      assertEquals(st.prunedToImmTip(past).selfPeer.live.isEmpty, true)
  }

  // --- the servers over the store ---

  private def store(dir: fs2.io.file.Path): Resource[IO, RocksLeiosStore[IO]] =
    LeiosStoreLocks
      .resource[IO]
      .flatMap(locks => allowing(RocksLeiosStore.resource[IO](dir / "leios", locks)))

  /** A peer's server over `central`, the notifying `store` 's notifications, the immutable tip at
    * `tip` and the default lead, its pump running for the resource's life.
    */
  private def served(
      central: LeiosChainSync.Central[IO],
      acquired: LeiosAcquired[IO],
      tip: Option[Word64]
  ): Resource[IO, LeiosNotifyServer.Replies[IO]] =
    LeiosNotifyRelay
      .server(
        central,
        acquired.notifications.subscribe,
        LeiosVotesSuite.noVotes,
        IO.pure(tip),
        LeiosMinOfferLead.Default
      )
      .flatTap(replies => replies.pump.background)

  /** The header state after `header`, as the gate leaves it ([[LeiosChainSyncSuite]]'s). */
  private def after(header: LeiosHeader): NodeHeaderState =
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
          xyz.kd5ujc.scarab.consensus.Praos2.State(
            praos.copy(lastSlot = WithOrigin.At(SlotNo.fromUnsigned(header.slot.toLong))),
            header.leios.announcement.map(xyz.kd5ujc.scarab.consensus.Praos2.AnnouncedBy(issuer, _))
          )
        )
      ),
      None,
      None,
      None,
      LeiosBound.Of(Word32.wrap(260000))
    )

  /** Peer A's ChainSync handling over `central` and `store`, as the node runs it. */
  private def chainSyncOfA(
      central: LeiosChainSync.Central[IO],
      store: LeiosStore[IO]
  ): IO[LeiosChainSync[IO]] =
    (
      LeiosFetchShared.create[IO, Int](
        LeiosOutstanding.empty[Int](StdGen.mk(4), Word64.clamped(0))
      ),
      LeiosPeerVars.create[IO](IsBigLedgerPeer.NotBigLedgerPeer)
    ).mapN((shared, vars) =>
      LeiosChainSync(
        shared,
        vars,
        central,
        store,
        _ => IO.pure(None),
        e => IO.raiseError(new AssertionError(e.toString))
      )
    )

  private def peer[A](run: Raise[IO, PeerError] ?=> IO[A]): IO[A] =
    Handle
      .allow[PeerError](run)
      .rescue(e => IO.raiseError(new AssertionError(e.toString)))

  private def credited(replies: LeiosNotifyServer.Replies[IO], n: Int): IO[Unit] =
    replies.requested
      .flatMap {
        case Requested.NotExcessive => IO.unit
        case other                  => IO.raiseError(new AssertionError(other.toString))
      }
      .replicateA_(n)

  dirs.test(
    "an endorser block announced over A's ChainSync and then acquired is relayed and offered to B"
  ) { dir =>
    val p = first.fields.point
    val eb = body(2)
    (store(dir), Resource.eval(LeiosAcquired.create[IO])).tupled.use { (rocks, acquired) =>
      val notifying = acquired.notifying(rocks)
      for
        central <- AtomicCell[IO].of(LeiosCentral.empty[IO])
        a <- chainSyncOfA(central, notifying)
        replies <- served(central, acquired, None).use { b =>
          for
            // B's server runs and holds requests: the announcement A's roll-forward brings is
            // relayed to it, then the body and the closure that arrive are offered
            _ <- credited(b, 4)
            _ <- peer(a.rolledForward(announcing.dijkstraView, after(certifying)))
            _ <- ok(notifying.writeEbBody(p, eb, Vector.empty))
            _ <- ok(notifying.writeTxs(p, Vector(0 -> tx(0), 1 -> tx(1))))
            replies <- b.next.replicateA(3)
          yield replies
        }
      yield assertEquals(
        replies,
        List(
          announcementOf(first),
          Reply.BlockOffer(p, word32(eb.encoded.size.toLong)),
          Reply.BlockTxsOffer(p)
        )
      )
    }
  }

  dirs.test("an endorser block inside the offer lead is not offered; one past it is") { dir =>
    val inside = first.fields.point
    val past = next.fields.point
    // the bound is the second endorser block's slot, which the first is older than
    val tip = Some(w64(past.slot.toBigInt - LeiosMinOfferLead.Default.slots.toBigInt))
    (store(dir), Resource.eval(LeiosAcquired.create[IO])).tupled.use { (rocks, acquired) =>
      val notifying = acquired.notifying(rocks)
      for
        central <- AtomicCell[IO].of(LeiosCentral.empty[IO])
        a <- chainSyncOfA(central, notifying)
        replies <- served(central, acquired, tip).use { b =>
          for
            _ <- credited(b, 4)
            _ <- peer(a.rolledForward(announcing.dijkstraView, after(certifying)))
            _ <- peer(a.rolledForward(certifying.dijkstraView, after(announcing)))
            _ <- ok(notifying.writeEbBody(inside, body(1), Vector.empty))
            _ <- ok(notifying.writeEbBody(past, body(2), Vector.empty))
            // the offers are taken in the order of the notifications, so the first offer queued
            // being the second endorser block's shows the first one's was dropped
            replies <- b.next.replicateA(3)
          yield replies
        }
      yield
        assert(inside.slot.toBigInt < past.slot.toBigInt)
        assertEquals(
          replies,
          List(
            announcementOf(first),
            announcementOf(next),
            Reply.BlockOffer(past, word32(body(2).encoded.size.toLong))
          )
        )
    }
  }

  dirs.test("a body already stored is offered for a point announced after it") { dir =>
    val p = first.fields.point
    val earlier = EbPoint(w64(p.slot.toBigInt - 1), p.hash)
    (store(dir), Resource.eval(LeiosAcquired.create[IO])).tupled.use { (rocks, acquired) =>
      val notifying = acquired.notifying(rocks)
      for
        central <- AtomicCell[IO].of(LeiosCentral.empty[IO])
        _ <- ok(
          notifying.writeEbPoint(earlier, word32(1)) *>
            notifying.writeEbBody(earlier, body(1), Vector.empty)
        )
        a <- chainSyncOfA(central, notifying)
        replies <- served(central, acquired, None).use { b =>
          credited(b, 2) *> peer(a.rolledForward(announcing.dijkstraView, after(certifying))) *>
            b.next.replicateA(2)
        }
      yield assertEquals(
        replies,
        List(announcementOf(first), Reply.BlockOffer(p, word32(body(1).encoded.size.toLong)))
      )
    }
  }

  dirs.test("the pump takes every offer and every vote, neither lost to the other") { dir =>
    val p = first.fields.point
    val eb = body(2)
    val keys = Vector.tabulate(2)(i => xyz.kd5ujc.scarab.crypto.bls.BlsFixtures.key(960 + i))
    val votes = keys.zipWithIndex.map { case ((sk, _), i) =>
      xyz.kd5ujc.scarab.crypto.bls.BlsFixtures.right(
        xyz.kd5ujc.scarab.crypto.bls.Vote.sign(
          sk,
          xyz.kd5ujc.scarab.crypto.bls.BlsFixtures.right(
            xyz.kd5ujc.scarab.crypto.bls.SeatId.fromInt(i)
          ),
          xyz.kd5ujc.scarab.crypto.bls.BlsFixtures.rbHash(5)
        )
      )
    }
    (store(dir), Resource.eval(LeiosAcquired.create[IO])).tupled.use { (rocks, acquired) =>
      val notifying = acquired.notifying(rocks)
      for
        central <- AtomicCell[IO].of(LeiosCentral.empty[IO])
        voted <- cats.effect.std.Queue.unbounded[IO, xyz.kd5ujc.scarab.crypto.bls.Vote]
        a <- chainSyncOfA(central, notifying)
        replies <- LeiosNotifyRelay
          .server(
            central,
            acquired.notifications.subscribe,
            Resource.pure(voted),
            IO.pure(None),
            LeiosMinOfferLead.Default
          )
          .use { b =>
            for
              _ <- credited(b, 5)
              _ <- peer(a.rolledForward(announcing.dijkstraView, after(certifying)))
              // both sources hold what to send before the pump takes any
              _ <- votes.traverse_(voted.offer)
              _ <- ok(notifying.writeEbBody(p, eb, Vector.empty))
              _ <- ok(notifying.writeTxs(p, Vector(0 -> tx(0), 1 -> tx(1))))
              replies <- b.pump.background.surround(b.next.replicateA(5))
            yield replies
          }
      yield
        assertEquals(replies.head, announcementOf(first))
        assertEquals(
          replies.tail.toSet,
          Set(
            Reply.BlockOffer(p, word32(eb.encoded.size.toLong)),
            Reply.BlockTxsOffer(p),
            Reply.Votes(Vector(votes(0))),
            Reply.Votes(Vector(votes(1)))
          )
        )
    }
  }

  dirs.test("a server's run registers it with the central state for the run's life") { dir =>
    (store(dir), Resource.eval(LeiosAcquired.create[IO])).tupled.use { (_, acquired) =>
      for
        central <- AtomicCell[IO].of(LeiosCentral.empty[IO])
        during <- served(central, acquired, None).use(_ => central.get.map(_.queues.size))
        afterwards <- central.get.map(_.queues.size)
      yield assertEquals((during, afterwards), (1, 0))
    }
  }

  // --- a round trip with the node's LeiosNotify and LeiosFetch clients ---

  private val magic = 42L
  private val chainSource = new ChainSyncServer.Source[IO, Bytes]:
    def intersect(points: Vector[ChainSync.Point]) = IO.pure(Left(ChainSync.Tip.Origin))
    def poll = IO.pure(None)
    def await = IO.never[ChainSyncServer.Update[Bytes]]

  dirs.test(
    "the LeiosNotify client is told of an announcement, the body and the closure, and fetches them"
  ) { dir =>
    val p = first.fields.point
    val eb = body(2)
    val txs = Vector(tx(0), tx(1))
    given LeiosNotify.AnnouncementReader = AnnouncedHeaders(BigInt(21600))
    (store(dir), Resource.eval(LeiosAcquired.create[IO])).tupled.use { (rocks, acquired) =>
      val notifying = acquired.notifying(rocks)
      for
        central <- AtomicCell[IO].of(LeiosCentral.empty[IO])
        requests <- SignallingRef.of[IO, Int](0)
        a <- chainSyncOfA(central, notifying)
        // the replies of each run, the requests they count observed
        notifier = LeiosNotifyRelay
          .server(
            central,
            acquired.notifications.subscribe,
            LeiosVotesSuite.noVotes,
            IO.pure(None),
            LeiosMinOfferLead.Default
          )
          .map(r =>
            new LeiosNotifyServer.Replies[IO]:
              def requested = r.requested <* requests.update(_ + 1)
              def next = r.next
              def pump = r.pump
          )
        handlers = ServerHandlers[IO, Bytes](
          ChainSyncFixtures.rawItem,
          Resource.pure(chainSource),
          Peers.blocksOf((_, _) => IO.pure(Peers.unknown)),
          new TxSubmissionServer.Sink[IO]:
            def reader = TxSubmission2Fixtures.structural
            def known(id: Bytes) = IO.pure(false)
            def submit(originals: Vector[xyz.kd5ujc.scarab.protocol.TxSubmission2.EraTx])(using
                Raise[IO, NetworkError]
            ) = IO.unit
          ,
          leiosFetch = Some(LeiosFetchStoreHandler(notifying)),
          leiosNotify = Some(notifier)
        )
        (x, y) <- Bearer.inMemory[IO](64)
        result <- typed(
          Served
            .accept(y, magic, handlers, NodeToNodeServer.Settings(bundle = HotBundle.Leios))
            .background
            .use(_ =>
              NodeToNode
                .warmOverWithResults[IO, Bytes, Unit](
                  x,
                  magic,
                  ChainSyncFixtures.rawItem,
                  (),
                  NodeToNode.Settings(bundle = HotBundle.Leios)
                )
                .use { warm =>
                  for
                    notify <- warm.mux.channel(Protocols.LeiosNotify, Mux.Role.Initiator)
                    fetch <- warm.mux.channel(Protocols.LeiosFetch, Mux.Role.Initiator)
                    client = LeiosNotifyClient[IO](notify)
                    // each request is held before what answers it happens
                    announced <- client.requestNext.start
                    _ <- requests.waitUntil(_ >= 1)
                    _ <- peer(a.rolledForward(announcing.dijkstraView, after(certifying)))
                    announcement <- announced.joinWithNever
                    offeredBody <- client.requestNext.start
                    _ <- requests.waitUntil(_ >= 2)
                    _ <- ok(notifying.writeEbBody(p, eb, Vector.empty))
                    bodyOffer <- offeredBody.joinWithNever
                    offeredClosure <- client.requestNext.start
                    _ <- requests.waitUntil(_ >= 3)
                    _ <- ok(notifying.writeTxs(p, txs.zipWithIndex.map((t, i) => i -> t)))
                    closureOffer <- offeredClosure.joinWithNever
                    _ <- client.quit
                    fetched <- LeiosFetchClient[IO](fetch).block(p)
                  yield (
                    announcement.message,
                    bodyOffer.message,
                    closureOffer.message,
                    fetched.message
                  )
                }
            )
        )
      yield
        // the envelope the reference sent the header in, byte for byte
        val envelope = captured("announcing")._1
        assertEquals(
          result,
          Right(
            (
              LeiosNotify.Message.BlockAnnouncement(envelope),
              LeiosNotify.Message.BlockOffer(p, word32(eb.encoded.size.toLong)),
              LeiosNotify.Message.BlockTxsOffer(p),
              LeiosFetch.Message.Block(eb.transactions.toVector)
            )
          )
        )
    }
  }
