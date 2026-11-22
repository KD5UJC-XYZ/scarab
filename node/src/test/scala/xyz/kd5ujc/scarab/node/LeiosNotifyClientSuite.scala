// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Supervisor
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.SignallingRef
import fs2.io.file.{Files, Path}
import java.time.Instant
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.consensus.HeaderRules
import xyz.kd5ujc.scarab.consensus.Praos2
import xyz.kd5ujc.scarab.consensus.leios.{
  AnnouncingHeader,
  BytesSize,
  IsBigLedgerPeer,
  LeiosFetchRequest,
  LeiosNotifyPeerState,
  PeerOffer,
  StdGen,
  TipCommittee,
  WhetherTxsClosureOffered
}
import xyz.kd5ujc.scarab.crypto.bls.{Committee, Seat, SeatId, Vote, Weight}
import xyz.kd5ujc.scarab.crypto.bls.BlsFixtures.{key, rbHash, right}
import xyz.kd5ujc.scarab.ledger.{
  AnnouncementError,
  AnnouncementInvalidity,
  InvalidOffer,
  LeiosHeader,
  LeiosNotifyFault,
  PeerError
}
import xyz.kd5ujc.scarab.network.{
  Bearer,
  ChainSyncServer,
  ChainSyncTimeouts,
  FailsOnUncaught,
  HotBundle,
  LeiosNotification,
  LeiosNotifyServer,
  NetworkError,
  NodeToNode,
  NodeToNodeServer,
  Peers,
  Protocols,
  ProtocolResults,
  Served,
  ServerHandlers,
  TxSubmissionServer
}
import xyz.kd5ujc.scarab.network.LeiosNotifyServer.Reply
import xyz.kd5ujc.scarab.protocol.{ChainSyncFixtures, EbPoint, HfcHeader, TxSubmission2Fixtures}
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word32, Word64}
import xyz.kd5ujc.scarab.storage.{LeiosStoreLocks, LeiosStoreSuite, RocksLeiosStore}

import Peers.*

/** The Leios build's LeiosNotify client ([[LeiosNotifyHandling]],
  * [[LeiosFetchNode.notifyInitiator]]) against `hLeiosNotifyClient`
  * (`Ouroboros/Consensus/Network/NodeToNode.hs` 615-768 at ouroboros-consensus 2416493b) and
  * `announcementValidity` (`LeiosDemoLogic.hs` 2048-2093): a peer's announcement counted and
  * relayed through the central state and recorded in the fetch state, its age held to
  * `maxAnnouncementAgeSend` and `maxAnnouncementAgeRecv`; its offers checked and recorded, so that
  * the decision fetches; its votes counted to the quorum; and the client run by a hot activation
  * against the node's own server. The announcing header is the public Leios testnet's 64849, and
  * the header after it, which announces the next endorser block
  * (`fixtures/leios/testnet/headers-notable.tsv`, provenance in its README). The check of an
  * announcing header against the immutable tip is [[Praos2ReferenceSuite]]'s; here it is given.
  */
class LeiosNotifyClientSuite extends FailsOnUncaught:
  import LeiosStoreSuite.{ok, allowing}

  private val dirs = ResourceFunFixture(Files.forAsync[IO].tempDirectory)
  private val connection = 7L
  private val magic = 42L

  private val captured: Map[String, (Bytes, LeiosHeader)] = java.nio.file.Files
    .readAllLines(java.nio.file.Path.of("fixtures/leios/testnet/headers-notable.tsv"))
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
  private def anc(name: String): AnnouncingHeader =
    AnnouncingHeader.of(captured(name)._2).getOrElse(fail("the header announces nothing"))
  private val first = anc("announcing")
  private val next = anc("certifying")
  private def announcement(name: String): LeiosNotification =
    LeiosNotification.Announcement(captured(name)._1)
  private def relayed(a: AnnouncingHeader): Reply =
    Reply.Announcement(HfcHeader.Dijkstra(a.header.raw))

  // --- the node's view, its clock and the votes ---

  private val now = Instant.parse("2026-11-21T12:00:00Z")
  private def picos(i: Instant): BigInt =
    BigInt(i.getEpochSecond) * BigInt(10).pow(12) + BigInt(i.getNano) * 1000
  private val clock = new SlotClock[IO]:
    def now = IO.pure(LeiosNotifyClientSuite.this.now)
    def geometry = IO.raiseError(new AssertionError("the clock's history is not read"))

  /** A view whose every announced slot began `age` before `now` and whose check of an announcing
    * header answers `check`, the immutable tip at `immTip`.
    */
  private def view(
      age: FiniteDuration,
      check: AnnouncementCheck = AnnouncementCheck.Checked(Right(Praos2.OcinStaleness.Fresh)),
      immTip: Option[Word64] = None
  ): LeiosNotifyView[IO] = new LeiosNotifyView[IO]:
    def immutableTip = IO.pure(immTip)
    def onset(header: LeiosHeader) =
      IO.pure(HeaderRules.TimeForecast.Known(picos(now) - BigInt(age.toNanos) * 1000))
    def validate(header: LeiosHeader) = IO.pure(check)
    def ready = IO.unit

  private lazy val keys = Vector.tabulate(4)(i => key(950 + i))
  private lazy val committee = TipCommittee(
    right(Committee.honoured(keys.map(k => Seat(Weight(Rational(1, 4)), Some(k._2))))),
    Weight(Rational(3, 4))
  )
  private val voted = rbHash(33)
  private def vote(i: Int): Vote = right(Vote.sign(keys(i)._1, right(SeatId.fromInt(i)), voted))

  private def store(dir: Path): Resource[IO, RocksLeiosStore[IO]] =
    LeiosStoreLocks
      .resource[IO]
      .flatMap(locks => allowing(RocksLeiosStore.resource[IO](dir, locks)))

  /** A node over a store of its own, with no offer lead. */
  private def node(dir: Path): Resource[IO, (LeiosFetchNode[IO], RocksLeiosStore[IO])] =
    (store(dir), Supervisor[IO](await = true), Resource.eval(LeiosAcquired.create[IO])).tupled
      .evalMap { (rocks, settling, acquired) =>
        ok(
          LeiosFetchNode.create[IO](
            acquired.notifying(rocks),
            StdGen.mk(4),
            Word64.clamped(0),
            settling,
            e => IO.raiseError(new AssertionError(e.toString)),
            IO.raiseError,
            _ => IO.pure(None),
            acquired.notifications.subscribe,
            IO.pure(None),
            LeiosMinOfferLead(Word64.fromUnsigned(0L))
          )
        ).map(_ -> rocks)
      }

  /** The handling of one peer's replies on `fetch`, its events in `events`, inside its bracket. */
  private def handling[A](
      fetch: LeiosFetchNode[IO],
      v: LeiosNotifyView[IO],
      votes: LeiosVotes[IO],
      events: Ref[IO, Vector[LeiosNotifyEvent]]
  )(use: (LeiosNotifyHandling[IO], LeiosPeerVars[IO]) => IO[A]): IO[A] =
    fetch.shared.bracketPeer(connection, IsBigLedgerPeer.NotBigLedgerPeer) { vars =>
      Ref.of[IO, LeiosNotifyPeerState](LeiosNotifyPeerState.empty).flatMap { st =>
        val context = LeiosNotifyContext[IO](
          votes,
          v,
          clock,
          e => events.update(_ :+ e),
          AnnouncedHeaders(BigInt(21600))
        )
        use(fetch.notifyHandling(connection, vars, context, st), vars)
      }
    }

  private def peer[A](run: Raise[IO, PeerError] ?=> IO[A]): IO[Either[PeerError, A]] =
    Handle.allow[PeerError](run.map(_.asRight[PeerError])).rescue(e => IO.pure(Left(e)))

  private val noCommittee: IO[LeiosVotes[IO]] = LeiosVotes.create[IO](IO.pure(Right(None)))

  /** `downstream`, a peer's server on `fetch`, given `n` credits. */
  private def credited(downstream: LeiosNotifyServer.Replies[IO], n: Int): IO[Unit] =
    downstream.requested.replicateA_(n)

  // --- announcements ---

  dirs.test("an announcement over LeiosNotify is counted, relayed and recorded for the fetch") {
    dir =>
      node(dir).use { (fetch, _) =>
        for
          votes <- noCommittee
          events <- Ref.of[IO, Vector[LeiosNotifyEvent]](Vector.empty)
          result <- fetch.notifier(LeiosVotesSuite.noVotes).flatTap(_.pump.background).use {
            downstream =>
              credited(downstream, 2) *>
                handling(fetch, view(1.second), votes, events)((h, _) =>
                  peer(h.handle(announcement("announcing")))
                ) *> downstream.next
          }
          outstanding <- fetch.shared.outstanding.get
          told <- events.get
        yield
          assertEquals(result, relayed(first))
          assertEquals(outstanding.elFocus.get(first.fields.election), Some(first.fields.ebHash))
          assertEquals(
            told,
            Vector(
              LeiosNotifyEvent.Announced(
                connection,
                first.fields.point,
                ShouldRelay.DoRelay,
                1.second
              )
            )
          )
      }
  }

  dirs.test("an announcement is relayed up to 300 s old, and recorded but not relayed past it") {
    dir =>
      node(dir).use { (fetch, _) =>
        for
          votes <- noCommittee
          events <- Ref.of[IO, Vector[LeiosNotifyEvent]](Vector.empty)
          result <- fetch.notifier(LeiosVotesSuite.noVotes).flatTap(_.pump.background).use {
            downstream =>
              for
                _ <- credited(downstream, 3)
                // past the sending bound: counted and recorded, not relayed
                late <- handling(fetch, view(300.seconds + 1.nano), votes, events)((h, _) =>
                  peer(h.handle(announcement("announcing")))
                )
                // at it: relayed, and the first reply, so the late one was not queued
                atBound <- handling(fetch, view(300.seconds), votes, events)((h, _) =>
                  peer(h.handle(announcement("certifying")))
                )
                reply <- downstream.next
              yield (late, atBound, reply)
          }
          outstanding <- fetch.shared.outstanding.get
          told <- events.get
        yield
          assertEquals(result, (Right(()), Right(()), relayed(next)))
          assert(outstanding.elFocus.contains(first.fields.election))
          assertEquals(
            told.collect { case LeiosNotifyEvent.Announced(_, p, relay, _) => p -> relay },
            Vector(
              first.fields.point -> ShouldRelay.DoNotRelay,
              next.fields.point -> ShouldRelay.DoRelay
            )
          )
      }
  }

  dirs.test("an announcement 600 s old is accepted, and one older disconnects the peer") { dir =>
    node(dir).use { (fetch, _) =>
      for
        votes <- noCommittee
        events <- Ref.of[IO, Vector[LeiosNotifyEvent]](Vector.empty)
        atBound <- handling(fetch, view(600.seconds), votes, events)((h, _) =>
          peer(h.handle(announcement("announcing")))
        )
        past <- handling(fetch, view(600.seconds + 1.nano), votes, events)((h, _) =>
          peer(h.handle(announcement("certifying")))
        )
        outstanding <- fetch.shared.outstanding.get
      yield
        assertEquals(atBound, Right(()))
        assertEquals(
          past,
          Left(
            PeerError.LeiosNotify(
              LeiosNotifyFault.Announcement(
                next.header.slot,
                next.headerHash,
                AnnouncementError.TooOld
              )
            )
          )
        )
        // the refused one is not recorded
        assert(!outstanding.elFocus.contains(next.fields.election))
    }
  }

  dirs.test(
    "a repeated announcement, one without an announcement, and an invalid one end the peer"
  ) { dir =>
    node(dir).use { (fetch, _) =>
      val invalid = AnnouncementCheck.Checked(Left(AnnouncementInvalidity.OutsideHorizon))
      // an envelope of an era the build does not know announces nothing
      val unknownEra =
        LeiosNotification.Announcement(Bytes(Vector(0x82, 0x09, 0x41, 0x00).map(_.toByte)))
      for
        votes <- noCommittee
        events <- Ref.of[IO, Vector[LeiosNotifyEvent]](Vector.empty)
        repeated <- handling(fetch, view(1.second), votes, events)((h, _) =>
          peer(h.handle(announcement("announcing"))) *> peer(h.handle(announcement("announcing")))
        )
        missing <- handling(fetch, view(1.second), votes, events)((h, _) =>
          peer(h.handle(unknownEra))
        )
        refused <- handling(fetch, view(1.second, invalid), votes, events)((h, _) =>
          peer(h.handle(announcement("certifying")))
        )
      yield
        assertEquals(
          repeated,
          Left(
            PeerError.LeiosNotify(
              LeiosNotifyFault.Announcement(
                first.header.slot,
                first.headerHash,
                AnnouncementError.Repeat
              )
            )
          )
        )
        assertEquals(missing, Left(PeerError.LeiosNotify(LeiosNotifyFault.AnnouncementMissing)))
        assertEquals(
          refused,
          Left(
            PeerError.LeiosNotify(
              LeiosNotifyFault.Announcement(
                next.header.slot,
                next.headerHash,
                AnnouncementError.Invalid(AnnouncementInvalidity.OutsideHorizon)
              )
            )
          )
        )
    }
  }

  dirs.test("a stale counter's announcement is counted from the peer, not processed or relayed") {
    dir =>
      node(dir).use { (fetch, _) =>
        val stale = AnnouncementCheck.Checked(Right(Praos2.OcinStaleness.Stale))
        for
          votes <- noCommittee
          events <- Ref.of[IO, Vector[LeiosNotifyEvent]](Vector.empty)
          again <- handling(fetch, view(1.second, stale), votes, events)((h, _) =>
            peer(h.handle(announcement("announcing"))) *> peer(h.handle(announcement("announcing")))
          )
          outstanding <- fetch.shared.outstanding.get
          told <- events.get
        yield
          assertEquals(
            again.left.toOption.collect {
              case PeerError.LeiosNotify(LeiosNotifyFault.Announcement(_, _, e)) => e
            },
            Some(AnnouncementError.Repeat)
          )
          assert(!outstanding.elFocus.contains(first.fields.election))
          assertEquals(told, Vector.empty)
      }
  }

  // --- offers ---

  dirs.test(
    "a body offer of an announced endorser block is recorded, and the decision fetches it"
  ) { dir =>
    node(dir).use { (fetch, _) =>
      val p = first.fields.point
      val size = BytesSize.wrap(2000L)
      for
        votes <- noCommittee
        events <- Ref.of[IO, Vector[LeiosNotifyEvent]](Vector.empty)
        result <- handling(fetch, view(1.second), votes, events) { (h, vars) =>
          for
            early <- peer(h.handle(LeiosNotification.BlockOffer(p, Word32.wrap(2000))))
            _ <- peer(h.handle(announcement("announcing")))
            offered <- peer(h.handle(LeiosNotification.BlockOffer(p, Word32.wrap(2000))))
            closure <- peer(h.handle(LeiosNotification.BlockTxsOffer(p)))
            again <- peer(h.handle(LeiosNotification.BlockTxsOffer(p)))
            offers <- vars.offerings.get
            // the decision, the closure's bound forecast, assigns the body to this peer
            taken <- fetch
              .kernel[Unit](
                IO.pure(Some(p.slot)),
                IO.pure(_ => Right(BytesSize.wrap(260000L))),
                Stream.empty,
                IO.raiseError
              )
              .surround(fetch.shared.wake *> vars.requestsToSend.take.timeout(30.seconds))
          yield (early, offered, closure, again, offers.get(p), taken)
        }
        told <- events.get
      yield
        val (early, offered, closure, again, offer, taken) = result
        assertEquals(
          early,
          Left(
            PeerError.LeiosNotify(
              LeiosNotifyFault.Offer(
                InvalidOffer.BlockOfferWithoutAnnouncement(p, Word32.wrap(2000))
              )
            )
          )
        )
        assertEquals((offered, closure), (Right(()), Right(())))
        assertEquals(
          again,
          Left(
            PeerError.LeiosNotify(
              LeiosNotifyFault.Offer(
                InvalidOffer.RepeatedOffer(p, xyz.kd5ujc.scarab.ledger.OfferedPart.Closure)
              )
            )
          )
        )
        assertEquals(offer, Some(PeerOffer(None, Some(size), WhetherTxsClosureOffered.Offered)))
        assertEquals(
          taken,
          LeiosRequestsToSend.Taken.Request(
            LeiosFetchRequest.Block(p, size, BytesSize.wrap(260000L))
          )
        )
        assert(told.contains(LeiosNotifyEvent.BlockOffered(connection, p, size)))
        assert(told.contains(LeiosNotifyEvent.BlockTxsOffered(connection, p)))
    }
  }

  dirs.test("a cancellation before the client quit ends the peer") { dir =>
    node(dir).use { (fetch, _) =>
      for
        votes <- noCommittee
        events <- Ref.of[IO, Vector[LeiosNotifyEvent]](Vector.empty)
        ended <- handling(fetch, view(1.second), votes, events)((h, _) =>
          peer(h.handle(LeiosNotification.Canceled))
        )
      yield assertEquals(ended, Left(PeerError.LeiosNotify(LeiosNotifyFault.UnexpectedCancel)))
    }
  }

  // --- votes ---

  dirs.test("a peer's votes reach the vote state, and the one that reaches the quorum certifies") {
    dir =>
      node(dir).use { (fetch, _) =>
        for
          votes <- LeiosVotes.create[IO](IO.pure(Right(Some(committee))))
          events <- Ref.of[IO, Vector[LeiosNotifyEvent]](Vector.empty)
          handled <- handling(fetch, view(1.second), votes, events)((h, _) =>
            peer(
              h.handle(LeiosNotification.Votes(Vector(vote(0), vote(1)))) *>
                h.handle(LeiosNotification.Votes(Vector(vote(1), vote(2), vote(3))))
            )
          )
          cert <- votes.certificate(voted)
          told <- events.get
        yield
          assertEquals(handled, Right(()))
          assert(cert.isDefined)
          // each vote counted once, the repeat dropped; the third completes the certificate
          assertEquals(
            told.collect { case LeiosNotifyEvent.VoteAcquired(_, v, _) => v.voter.index },
            Vector(0, 1, 2, 3)
          )
          // traced whenever the vote state surfaces the certificate, as `addVote`'s `Added` does at
          // and past the quorum
          assertEquals(told.count(_ == LeiosNotifyEvent.Certified(voted)), 2)
          assertEquals(
            told.indexOf(LeiosNotifyEvent.Certified(voted)),
            told.indexWhere {
              case LeiosNotifyEvent.VoteAcquired(_, v, _) => v.voter.index == 2
              case _                                      => false
            } + 1
          )
      }
  }

  // --- a round trip: the node's client against the node's server ---

  private val chainSource = new ChainSyncServer.Source[IO, Bytes]:
    def intersect(points: Vector[xyz.kd5ujc.scarab.protocol.ChainSync.Point]) =
      IO.pure(Left(xyz.kd5ujc.scarab.protocol.ChainSync.Tip.Origin))
    def poll = IO.pure(None)
    def await = IO.never[ChainSyncServer.Update[Bytes]]

  dirs.test(
    "a hot activation's LeiosNotify client receives the server's announcement and vote, then quits"
  ) { dir =>
    (node(dir / "a"), node(dir / "b")).tupled.use { case ((server, served), (client, _)) =>
      for
        serverVotes <- LeiosVotes.create[IO](IO.pure(Right(Some(committee))))
        clientVotes <- LeiosVotes.create[IO](IO.pure(Right(Some(committee))))
        events <- SignallingRef.of[IO, Vector[LeiosNotifyEvent]](Vector.empty)
        requests <- SignallingRef.of[IO, Int](0)
        notifier = server
          .notifier(serverVotes.subscribe)
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
          leiosFetch = Some(LeiosFetchStoreHandler(served)),
          leiosNotify = Some(notifier)
        )
        context = LeiosNotifyContext[IO](
          clientVotes,
          view(1.second),
          clock,
          e => events.update(_ :+ e),
          AnnouncedHeaders(BigInt(21600))
        )
        (a, b) <- Bearer.inMemory[IO](64)
        result <- typed(
          Served
            .accept(b, magic, handlers, NodeToNodeServer.Settings(bundle = HotBundle.Leios))
            .background
            .surround(
              NodeToNode
                .warmOverWithResults[IO, Bytes, Unit](
                  a,
                  magic,
                  ChainSyncFixtures.rawItem,
                  (),
                  NodeToNode.Settings(bundle = HotBundle.Leios)
                )
                .use { warm =>
                  (warm.applications.activateHot *>
                    warm.applications.startHot(false, leiosNotify = true)).flatMap { results =>
                    warm
                      .startHotProtocols(
                        results,
                        None,
                        None,
                        ChainSyncTimeouts.PeerTrust.Untrustable,
                        None,
                        leiosNotify = Some(client.notifyInitiator(connection, context))
                      )
                      .use(connection =>
                        // the client's requests are held before the announcement and the vote
                        requests.waitUntil(_ >= 2) *>
                          server.announcedCentrally(first, ShouldRelay.DoRelay, IO.unit) *>
                          Handle
                            .allow[LeiosVotesDefect](serverVotes.add(vote(0)).void)
                            .rescue(e => IO.raiseError(new AssertionError(e.toString))) *>
                          events.waitUntil(_.size >= 2) *>
                          // the clean end: the client quits, drains and has the server's MsgDone
                          connection.finishGracefully(false)
                      ) *> warm.applications.terminateHot *>
                      warm.applications.awaitAll(Set(ProtocolResults.Temperature.Hot))
                  }
                }
            )
        )
        told <- events.get
        counted <- clientVotes.voters(voted)
        left <- client.shared.peers.get
      yield
        assertEquals(
          result,
          Right(
            ProtocolResults.LastToFinish.AllSucceeded(
              Map(
                Protocols.ChainSync -> (),
                Protocols.BlockFetch -> (),
                Protocols.LeiosNotify -> ()
              )
            )
          )
        )
        assertEquals(
          told.collect { case LeiosNotifyEvent.Announced(_, p, _, _) =>
            p
          },
          Vector(first.fields.point)
        )
        assertEquals(counted.keySet.map(_.index), Set(0))
        assert(left.isEmpty)
    }
  }
