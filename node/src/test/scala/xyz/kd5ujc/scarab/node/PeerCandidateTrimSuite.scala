// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.concurrent.SignallingRef
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.consensus.{ChainSelection, HeaderChain}
import xyz.kd5ujc.scarab.ledger.{Header, PeerError}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.genesis.{LeakyBucket, LimitOnPatience}
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}
import scala.concurrent.duration.*

/** A session's candidate follows the header gate's history as it is trimmed to our chain's anchor
  * (issue 810): while our anchor advances over many windows of headers, the session takes every
  * header it is asked for and its candidate ends past the first window, so publication never
  * starves. Before, the candidate grew to the window and its oldest block, long ours and never
  * fetched again, held the session's ChainSync still.
  *
  * A trim never passes what the session published to its consumer, so the candidate can still reach
  * the window for a moment while the consumer lags. How long depends on how the fibers are
  * scheduled, so the cases with a free-running session assert only what holds on every schedule;
  * the cases that pin a schedule bring every fiber to rest first.
  */
class PeerCandidateTrimSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val magic = 42L
  private val retained = 8
  // how far our anchor is behind what the consumer took, and how far past it the gate lets the
  // peer's headers run (the forecast range of the real gate)
  private val anchorLag = 2
  private val forecast = 3
  private val pipelineWindow =
    ChainSyncPipelineDecision.Window.checked(2, 4).fold(error => fail(error.toString), identity)

  private val forged: IO[ForgedChain] = ForgedChain.forge(5000 until 5180)

  /** A gate that accepts every header within the forecast range of what the consumer took, waiting
    * for the consumer otherwise (none: it waits for nothing), and whose history starts at our
    * chain's anchor. `accepted` is told of each header it accepts.
    */
  private def gate(
      taken: Option[SignallingRef[IO, Int]],
      anchor: Ref[IO, ChainSync.Point],
      accepted: Header => IO[Unit] = _ => IO.unit
  ): HeaderCheck[IO] = new HeaderCheck[IO]:
    def withLeios(leios: LeiosChainSync[IO]): HeaderCheck[IO] = this
    def start(point: ChainSync.Point) = IO.pure(new HeaderGate[IO]:
      def counts = IO.pure(HeaderGate.Counts(0, 0, 0))
      def accept(batch: Vector[SyncEvent])(using Raise[IO, PeerError]) =
        batch.traverse_ {
          case SyncEvent.RollForward(header, _) =>
            taken.traverse_(_.waitUntil(n => header.blockNo <= BigInt(n + forecast))) *>
              accepted(header)
          case SyncEvent.RollBackward(_, _) => IO.unit
        }
      override def snapshot(using cats.Applicative[IO]) =
        anchor.get.map(point => Some(HeaderChain.at[NodeHeaderState](point, None))))

  /** A patience bucket that only says when ChainSync first waits for local capacity. */
  private def capacityWatch(full: Deferred[IO, Unit]): LimitOnPatience[IO] =
    new LimitOnPatience[IO]:
      def setPaused(reason: LimitOnPatience.PauseReason, paused: Boolean): IO[Unit] =
        full.complete(()).void.whenA(reason == LimitOnPatience.PauseReason.LocalCapacity && paused)
      def validated(blockNo: BigInt): IO[Unit] = IO.unit
      def jumpedTo(blockNo: BigInt): IO[Unit] = IO.unit
      def configure(config: LeakyBucket.Config): IO[Unit] = IO.unit
      def snapshot: IO[LeakyBucket.State] =
        IO.pure(LeakyBucket.State(Rational.zero, 0, false, 0, LeakyBucket.Config.disabled))

  /** What [[advancing]] saw: the headers the consumer took, of how many it pulled for, the
    * candidate's base, as a position in the chain (-1 for the origin), and size at the end, and how
    * the session ended if it did not take them all.
    */
  private final case class Advance(
      took: Int,
      pulls: Int,
      base: Int,
      size: Int,
      ended: Option[Throwable]
  )

  /** The consumer pulls every header the peer has but its last few, our chain adopting each with
    * its anchor `anchorLag` behind, while no block is ever fetched.
    */
  private def advancing(chain: ForgedChain): IO[Advance] =
    // every header the forged chain has but the last few, which the peer serves at its tip
    val pulls = chain.headers.size - forecast - 2
    def position(point: ChainSync.Point) = chain.headers.indexWhere(_.point == point)
    FetchCoordinator
      .create[IO]()
      .flatMap { coordinator =>
        // this coordinator decides nothing: no block is ever fetched, as no window's oldest is
        coordinator.session(11L).use { session =>
          TestControl.executeEmbed {
            for
              taken <- SignallingRef.of[IO, Int](0)
              anchor <- Ref.of[IO, ChainSync.Point](ChainSync.Point.Origin)
              took <- Ref.of[IO, Int](0)
              last <- Ref.of[IO, Option[ChainSelection.Candidate]](None)
              // a session that stalls ends with the error of the connection's end; what it took
              // and its candidate then say why
              ended <- connected(chain, gate(Some(taken), anchor), session) { peer =>
                val candidate =
                  peer.headerCandidate.getOrElse(fail("a streaming session has a candidate"))
                val pull = peer.next.flatMap {
                  case SyncEvent.RollForward(header, _) =>
                    val index = position(header.point)
                    // our chain adopts what the consumer takes, its anchor `anchorLag` behind
                    chain.headers.lift(index - anchorLag).traverse_(h => anchor.set(h.point)) *>
                      taken.set(header.blockNo.toInt) *> took.update(_ + 1)
                  case SyncEvent.RollBackward(_, _) => IO.unit
                }
                peer.intersect(Vector(ChainSync.Point.Origin)) *>
                  (1 to pulls).toList
                    .traverse_(_ => pull.guarantee(candidate.get.flatMap(c => last.set(Some(c)))))
              }.attempt
              count <- took.get
              seen <- last.get
            yield Advance(
              count,
              pulls,
              seen.fold(-1)(c => position(c.base)),
              seen.fold(0)(_.headers.size),
              ended.left.toOption
            )
          }
        }
      }

  test("a candidate trimmed to our advancing anchor lets the session take every header") {
    forged.flatMap { chain =>
      advancing(chain).map { seen =>
        assert(
          seen.pulls > retained * 4,
          s"${chain.headers.size} headers are too few to pass the window"
        )
        val context =
          s"the session took ${seen.took} of ${seen.pulls} headers, its candidate ${seen.size} " +
            s"headers from position ${seen.base} (a window of $retained)"
        // whatever ended the session fails the case, with its cause; fewer headers taken than
        // pulled for is a starved publication
        seen.ended.foreach(cause =>
          if seen.took < seen.pulls then fail(s"publication starved: $context", cause)
          else fail(s"the session failed after its last pull: $context", cause)
        )
        assert(seen.took == seen.pulls, s"publication starved: $context")
        // the candidate followed our anchor: it ends past the first window
        assert(seen.base >= retained, s"the candidate is still inside the first window: $context")
      }
    }
  }

  test("a trim the publication held back is taken once publication passes our anchor again") {
    forged.flatMap { chain =>
      val first = 4
      val pulls = chain.headers.size - 2
      FetchCoordinator
        .create[IO]()
        .flatMap { coordinator =>
          // nothing is ever fetched: a full window waits for the trim alone
          coordinator.session(12L).use { session =>
            TestControl.executeEmbed {
              for
                anchor <- Ref.of[IO, ChainSync.Point](ChainSync.Point.Origin)
                full <- Deferred[IO, Unit]
                // our chain follows another peer: its anchor two headers behind what this session's
                // gate accepted, however far behind this session's consumer is
                follows = (header: Header) =>
                  chain.headers
                    .lift(chain.headers.indexWhere(_.point == header.point) - 2)
                    .traverse_(h => anchor.set(h.point))
                _ <- connected(
                  chain,
                  gate(None, anchor, follows),
                  session,
                  Some(capacityWatch(full))
                ) { peer =>
                  peer.intersect(Vector(ChainSync.Point.Origin)) *>
                    (1 to first).toList.traverse_(_ => peer.next) *>
                    // the consumer stops until the window is full: the trim waits for publication
                    full.get *> (first until pulls).toList.traverse_(_ => peer.next)
                }
              yield ()
            }
          }
        }
    }
  }

  /** A gate that, like the node's, moves its history's base to our chain's anchor only when it
    * records a header or follows our chain, and tells of our chain's changes. `meanwhile`, when
    * armed, runs once inside a follow, after it read our anchor.
    */
  private def following(
      ours: SignallingRef[IO, ChainSync.Point],
      base: Ref[IO, ChainSync.Point],
      meanwhile: Option[Ref[IO, IO[Unit]]] = None
  ): HeaderCheck[IO] = new HeaderCheck[IO]:
    def withLeios(leios: LeiosChainSync[IO]): HeaderCheck[IO] = this
    def start(point: ChainSync.Point) = IO.pure(new HeaderGate[IO]:
      def counts = IO.pure(HeaderGate.Counts(0, 0, 0))
      def accept(batch: Vector[SyncEvent])(using Raise[IO, PeerError]) =
        ours.get.flatMap(base.set)
      override def snapshot(using cats.Applicative[IO]) =
        base.get.map(point => Some(HeaderChain.at[NodeHeaderState](point, None)))
      override def ourChainChanged(using cats.Applicative[IO]) =
        ours.get.map(seen => Some(ours.discrete.dropWhile(_ == seen).head.compile.drain))
      override def followOurChain(using cats.Applicative[IO]) =
        (base.get, ours.get).flatMapN((from, anchor) =>
          meanwhile.traverse_(hook => hook.getAndSet(IO.unit).flatten) *>
            (if from == anchor then IO.pure(HeaderGate.Following.Unchanged)
             else base.set(anchor).as(HeaderGate.Following.Trimmed))
        ))

  test("a full window is trimmed when our chain moves on while ChainSync waits for capacity") {
    forged.flatMap { chain =>
      FetchCoordinator
        .create[IO]()
        .flatMap { coordinator =>
          // nothing is ever fetched: only a trim makes room in a full window
          coordinator.session(13L).use { session =>
            (Deferred[IO, Unit], Ref.of[IO, Boolean](false)).flatMapN { (release, parked) =>
              val program =
                for
                  ours <- SignallingRef.of[IO, ChainSync.Point](ChainSync.Point.Origin)
                  base <- Ref.of[IO, ChainSync.Point](ChainSync.Point.Origin)
                  full <- Deferred[IO, Unit]
                  _ <- connected(chain, following(ours, base), session, Some(capacityWatch(full))) {
                    peer =>
                      // up to the window's last header: everything the session published
                      val last = chain.headers(retained - 1).point
                      def drain: IO[Unit] = peer.next.flatMap {
                        case SyncEvent.RollForward(header, _) if header.point == last => IO.unit
                        case _                                                        => drain
                      }
                      // the session's pipeline starts with its first pull
                      peer.intersect(Vector(ChainSync.Point.Origin)) *> peer.next *> full.get *>
                        drain *> parked.set(true) *> release.get *>
                        // our chain moves on only now, with ChainSync waiting for capacity and
                        // every other fiber idle: no header is recorded to move the gate's
                        // history, no block arrives and nothing is published
                        ours.set(chain.headers(retained - 3).point) *>
                        // the window ended at its eighth header: the next three come only once
                        // the wait followed our chain and the candidate was trimmed
                        (1 to 3).toList.traverse_(_ => peer.next)
                  }
                yield ()
              TestControl.execute(program).flatMap { control =>
                for
                  // every runnable fiber runs to its wait, at the same instant: the publication is
                  // done and the capacity wait is parked on its signals
                  _ <- control.tick
                  waiting <- parked.get
                  _ <- IO(assert(waiting, "the session did not reach its full window"))
                  _ <- release.complete(())
                  _ <- control.tick
                  result <- control.results
                yield assertEquals(result, Some(cats.effect.kernel.Outcome.succeeded(cats.Id(()))))
              }
            }
          }
        }
    }
  }

  test("our chain moving on between the wait's follow and its wait still wakes the wait") {
    forged.flatMap { chain =>
      FetchCoordinator
        .create[IO]()
        .flatMap { coordinator =>
          coordinator.session(14L).use { session =>
            (Deferred[IO, Unit], Ref.of[IO, Boolean](false)).flatMapN { (release, parked) =>
              val program =
                for
                  ours <- SignallingRef.of[IO, ChainSync.Point](ChainSync.Point.Origin)
                  base <- Ref.of[IO, ChainSync.Point](ChainSync.Point.Origin)
                  meanwhile <- Ref.of[IO, IO[Unit]](IO.unit)
                  full <- Deferred[IO, Unit]
                  check = following(ours, base, Some(meanwhile))
                  _ <- connected(chain, check, session, Some(capacityWatch(full))) { peer =>
                    val last = chain.headers(retained - 1).point
                    def drain: IO[Unit] = peer.next.flatMap {
                      case SyncEvent.RollForward(header, _) if header.point == last => IO.unit
                      case _                                                        => drain
                    }
                    peer.intersect(Vector(ChainSync.Point.Origin)) *> peer.next *> full.get *>
                      drain *> parked.set(true) *> release.get *>
                      // the next follow reads our old anchor, and our chain moves on right after
                      meanwhile.set(ours.set(chain.headers(retained - 3).point)) *>
                      // a block arrives elsewhere: the wait follows our chain once more
                      coordinator.refreshGenesis *>
                      (1 to 3).toList.traverse_(_ => peer.next)
                  }
                yield ()
              TestControl.execute(program).flatMap { control =>
                for
                  _ <- control.tick
                  waiting <- parked.get
                  _ <- IO(assert(waiting, "the session did not reach its full window"))
                  _ <- release.complete(())
                  _ <- control.tick
                  result <- control.results
                yield assertEquals(result, Some(cats.effect.kernel.Outcome.succeeded(cats.Id(()))))
              }
            }
          }
        }
    }
  }

  test("a peer's chain that no longer passes through our anchor restarts the waiting session") {
    forged.flatMap { chain =>
      FetchCoordinator
        .create[IO]()
        .flatMap { coordinator =>
          coordinator.session(15L).use { session =>
            (Deferred[IO, Unit], Ref.of[IO, Boolean](false)).flatMapN { (release, parked) =>
              val program =
                for
                  lost <- Ref.of[IO, Boolean](false)
                  accepted <- Ref.of[IO, Vector[(Int, ChainSync.Point)]](Vector.empty)
                  starts <- Ref.of[IO, Int](0)
                  restarted <- Deferred[IO, Unit]
                  full <- Deferred[IO, Unit]
                  // each session start is a gate of its own; the first follow after `lost` finds
                  // that the history no longer passes through our anchor
                  check = new HeaderCheck[IO]:
                    def withLeios(leios: LeiosChainSync[IO]): HeaderCheck[IO] = this
                    def start(point: ChainSync.Point) =
                      starts
                        .updateAndGet(_ + 1)
                        .flatTap(n => restarted.complete(()).whenA(n > 1))
                        .map { started =>
                          new HeaderGate[IO]:
                            def counts = IO.pure(HeaderGate.Counts(0, 0, 0))
                            def accept(batch: Vector[SyncEvent])(using Raise[IO, PeerError]) =
                              accepted.update(_ ++ batch.collect {
                                case SyncEvent.RollForward(header, _) => started -> header.point
                              })
                            override def snapshot(using cats.Applicative[IO]) =
                              IO.pure(
                                Some(HeaderChain.at[NodeHeaderState](ChainSync.Point.Origin, None))
                              )
                            override def followOurChain(using cats.Applicative[IO]) =
                              lost.get.map(gone =>
                                if gone then HeaderGate.Following.NoLongerIntersects(point)
                                else HeaderGate.Following.Unchanged
                              )
                        }
                  _ <- connected(chain, check, session, Some(capacityWatch(full))) { peer =>
                    val last = chain.headers(retained - 1).point
                    def drain: IO[Unit] = peer.next.flatMap {
                      case SyncEvent.RollForward(header, _) if header.point == last => IO.unit
                      case _                                                        => drain
                    }
                    peer.intersect(Vector(ChainSync.Point.Origin)) *> peer.next *> full.get *>
                      drain *> parked.set(true) *> release.get *>
                      // our chain leaves the peer's while the session waits; a block arrives
                      lost.set(true) *> coordinator.refreshGenesis *>
                      // the session drains and finds a new intersection, without the header it
                      // was holding
                      restarted.get
                  }
                  taken <- accepted.get
                yield taken.collect { case (1, point) => point }
              TestControl.execute(program).flatMap { control =>
                for
                  _ <- control.tick
                  waiting <- parked.get
                  _ <- IO(assert(waiting, "the session did not reach its full window"))
                  _ <- release.complete(())
                  _ <- control.tick
                  result <- control.results
                yield assertEquals(
                  result,
                  Some(
                    cats.effect.kernel.Outcome.succeeded(
                      cats.Id(chain.headers.take(retained).map(_.point))
                    )
                  )
                )
              }
            }
          }
        }
    }
  }

  /** A gate that accepts the headers up to the position `allowed` holds, waiting for it otherwise,
    * and whose history starts at our chain's anchor. Armed, `hold` keeps the next read of that
    * history waiting until it is completed, and says so in `holding`; the read gives our anchor as
    * it is when the wait ends, or, `early`, as it was when the read began.
    */
  private def holding(
      chain: ForgedChain,
      allowed: SignallingRef[IO, Int],
      anchor: Ref[IO, ChainSync.Point],
      hold: Ref[IO, Option[Deferred[IO, Unit]]],
      holding: Ref[IO, Boolean],
      early: Boolean
  ): HeaderCheck[IO] = new HeaderCheck[IO]:
    def withLeios(leios: LeiosChainSync[IO]): HeaderCheck[IO] = this
    def start(point: ChainSync.Point) = IO.pure(new HeaderGate[IO]:
      def counts = IO.pure(HeaderGate.Counts(0, 0, 0))
      def accept(batch: Vector[SyncEvent])(using Raise[IO, PeerError]) =
        batch.traverse_ {
          case SyncEvent.RollForward(header, _) =>
            val at = chain.headers.indexWhere(_.point == header.point)
            allowed.waitUntil(_ >= at)
          case SyncEvent.RollBackward(_, _) => IO.unit
        }
      override def snapshot(using cats.Applicative[IO]) =
        val waited =
          hold.getAndSet(None).flatMap(_.traverse_(release => holding.set(true) *> release.get))
        (if early then anchor.get <* waited else waited *> anchor.get)
          .map(point => Some(HeaderChain.at[NodeHeaderState](point, None))))

  /** How [[trimmedAtRest]] moves the session while the publisher's step waits in its read of our
    * anchor, the third header for the first two:
    *   - `Resting`: nothing moves;
    *   - `Replaced`: ChainSync takes one more header, replacing the candidate;
    *   - `Overtaken`: our anchor moves to the fifth header, past the publisher's cursor, and
    *     ChainSync takes one more header, after the step read our old anchor, the origin.
    */
  private enum Meanwhile:
    case Resting, Replaced, Overtaken

  /** What [[trimmedAtRest]] saw once every fiber came to rest after the publisher's step: the
    * session's candidate, as the position of its base in the chain and its size, the size of the
    * candidate the coordinator was last announced, and whether the schedule went as planned.
    */
  private final case class Trimmed(
      base: Int,
      size: Int,
      announced: Vector[Option[Int]],
      scheduled: Vector[Boolean]
  )

  /** ChainSync takes the headers up to `first`, the consumer takes one and holds the rest, and the
    * publisher's next step, which publishes the fifth header, waits in its read of our anchor while
    * the session moves as `meanwhile` says. Every fiber is then at rest: ChainSync waits for the
    * gate, the publisher for the consumer and the consumer for the case.
    */
  private def trimmedAtRest(chain: ForgedChain, meanwhile: Meanwhile): IO[Trimmed] =
    val first = 5
    def position(point: ChainSync.Point) = chain.headers.indexWhere(_.point == point)
    FetchCoordinator
      .create[IO]()
      .flatMap { coordinator =>
        // nothing is ever fetched: only a trim drops a header
        coordinator.session(16L).use { session =>
          (
            SignallingRef.of[IO, Int](first),
            Ref.of[IO, ChainSync.Point](ChainSync.Point.Origin),
            Ref.of[IO, Option[Deferred[IO, Unit]]](None),
            Ref.of[IO, Boolean](false),
            Deferred[IO, Unit],
            Deferred[IO, Unit],
            Deferred[IO, Unit],
            Ref.of[IO, Int](0)
          ).flatMapN { (allowed, anchor, hold, held, trim, resume, finish, parked) =>
            val early = meanwhile == Meanwhile.Overtaken
            val program = connected(
              chain,
              holding(chain, allowed, anchor, hold, held, early),
              session,
              readAhead = 1
            ) { peer =>
              val candidate =
                peer.headerCandidate.getOrElse(fail("a streaming session has a candidate"))
              peer.intersect(Vector(ChainSync.Point.Origin)) *> peer.next *> parked.set(1) *>
                resume.get *> hold.set(Some(trim)) *> peer.next *> parked.set(2) *> finish.get *>
                candidate.get
            }
            TestControl.execute(program).flatMap { control =>
              for
                // ChainSync waits at header `first + 1`; the publisher has published three headers
                // past the one the consumer took and waits for the consumer
                _ <- control.tick
                one <- parked.get.map(_ == 1)
                _ <- resume.complete(())
                _ <- control.tick
                waiting <- held.get
                two <- parked.get.map(_ == 2)
                _ <- meanwhile match
                  case Meanwhile.Resting => anchor.set(chain.headers(2).point)
                  case Meanwhile.Replaced =>
                    allowed.set(first + 1) *> control.tick *> anchor.set(chain.headers(2).point)
                  case Meanwhile.Overtaken =>
                    anchor.set(chain.headers(4).point) *> allowed.set(first + 1) *> control.tick
                _ <- trim.complete(())
                _ <- control.tick
                sessions <- coordinator.diagnostics
                _ <- finish.complete(())
                _ <- control.tick
                result <- control.results
              yield result match
                case Some(cats.effect.kernel.Outcome.Succeeded(seen)) =>
                  Trimmed(
                    position(seen.base),
                    seen.headers.size,
                    sessions.map(_.candidateHeaders),
                    Vector(one, waiting, two)
                  )
                case other => fail(s"the session did not end with its candidate: $other")
            }
          }
        }
      }

  /** The schedule went as [[trimmedAtRest]] plans it: the consumer took a header, the publisher's
    * step waited in its read of our anchor, and the consumer took another.
    */
  private def scheduled(seen: Trimmed): Unit =
    assertEquals(seen.scheduled, Vector(true, true, true), "the schedule did not go as planned")

  test("a candidate ChainSync replaces while the publisher trims is trimmed all the same") {
    forged.flatMap(trimmedAtRest(_, Meanwhile.Replaced)).map { seen =>
      scheduled(seen)
      // the trim goes to our anchor, the third header, and keeps the four after it, the newest of
      // them the one ChainSync took while the publisher's step was under way
      assertEquals((seen.base, seen.size), (2, 4), "the trim was lost")
      assertEquals(seen.announced, Vector(Some(4)), "the coordinator holds another candidate")
    }
  }

  test("a trimmed candidate is announced to the coordinator while its peer sends nothing") {
    forged.flatMap(trimmedAtRest(_, Meanwhile.Resting)).map { seen =>
      scheduled(seen)
      assertEquals((seen.base, seen.size), (2, 3), "the candidate was not trimmed")
      // no batch follows the trim: the coordinator has it only from the trim's own announcement
      assertEquals(seen.announced, Vector(Some(3)), "the coordinator holds the untrimmed candidate")
    }
  }

  test("a trim our anchor asks for past the cursor is taken by the step that moves the cursor") {
    forged.flatMap(trimmedAtRest(_, Meanwhile.Overtaken)).map { seen =>
      scheduled(seen)
      // ChainSync's batch trimmed only to the cursor, the fourth header; the step that publishes
      // the fifth, our anchor, trims to it, though it read our old anchor
      assertEquals((seen.base, seen.size), (4, 2), "the trim waits for another step")
      assertEquals(seen.announced, Vector(Some(2)))
    }
  }

  private def connected[A](
      forged: ForgedChain,
      check: HeaderCheck[IO],
      fetchSession: FetchCoordinator.Session[IO],
      patience: Option[LimitOnPatience[IO]] = None,
      readAhead: Int = 2
  )(
      body: Peer.Controlled[IO] => Raise[IO, PeerError] ?=> IO[A]
  ): IO[A] =
    for
      honest <- ServedChain(forged)
      handlers = new ServerHandlers[IO, NtNHeader](
        honest.codec,
        honest.chainSync,
        honest.blocks,
        honest.transactions
      )
      pair <- Bearer.inMemory[IO](256)
      (theirs, ours) = pair
      result <- Handle
        .allow[NetworkError](Served.accept(theirs, magic, handlers).void)
        .rescue(_ => IO.unit)
        .background
        .use { _ =>
          Handle
            .allow[NetworkError] {
              NodeToNode
                .over[IO, NtNHeader](
                  ours,
                  magic,
                  NtNHeader.codec,
                  NodeToNode.Settings().pipelining(pipelineWindow.high)
                )
                .use { connection =>
                  Peer
                    .over[IO](
                      connection,
                      Peer.Settings(
                        SocketAddress(host"127.0.0.1", port"3001"),
                        magic,
                        readAhead = readAhead,
                        byronEpochSlots = ForgedChain.geometry.byron.epochSlots,
                        pipelineWindow = pipelineWindow
                      ),
                      heightSnapshot = ChainSyncHeights.origin,
                      chain = Peer.Chain.Praos(Some(check)),
                      patience = patience,
                      retainedHeaders = retained,
                      fetchSession = Some(fetchSession)
                    )
                    .use(peer =>
                      Handle.allow[PeerError](body(peer)).rescue(e => IO(fail(e.message)))
                    )
                }
            }
            .rescue(e => IO(fail(e.message)))
        }
    yield result
