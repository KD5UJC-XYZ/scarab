// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import cats.effect.{IO, Ref}
import cats.mtl.Handle
import cats.syntax.all.*
import com.comcast.ip4s.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

/** The governor's decisions run against a connection manager with an opt-in outbound limit: the
  * jobs are carried out on the manager, and their outcomes given back to the governor. Local roots
  * are left out of the limit as the governor classifies the peer: a dial reads the classification
  * when it reserves its connection (`GovernorState.dialOf`), and a peer that becomes a local root
  * while its connection is open has that connection left out (`Job.Exempt`). So the governor's own
  * dials never meet a place that a local root holds, and a local root is never refused.
  */
class LocalRootExemptionSuite extends FailsOnUncaught:
  private type S = GovernorState[Int]
  private val t0: Time = Duration.Zero

  // the targets the start-up check reads: one ordinary and two big ledger peers, so 3
  private val targets = Targets.validated(0, 10, 1, 0, 4, 2, 0).toOption.get
  private val threshold = OutboundLimit.Needed(targets.established, targets.establishedBig).total

  private def address(peer: Int): SocketAddress[IpAddress] =
    SocketAddress(IpAddress.fromString(s"10.1.0.$peer").get, port"3001")

  private def atMost(n: Int): OutboundLimit =
    OutboundLimit.AtMost(OutboundLimit.Connections.from(n).toOption.get)

  private val rootConfig = RootConfig(
    advertise = false,
    DiffusionMode.InitiatorAndResponder,
    Provenance.Outbound,
    trustable = false
  )

  private def roots(members: Int*): LocalRoots[Int] =
    LocalRoots.fromGroups(
      List(RootGroupSpec(members.size, members.size, members.toList.map(_ -> rootConfig)))
    )

  private def initial(peers: (Int, Origin)*): S =
    val st = GovernorState
      .initial[Int](3L, LocalRoots.empty[Int], targets, Sensitivity.initial(false))
      .copy(targets = targets)
    st.copy(peers = st.peers ++ peers.map((p, origin) => p -> PeerRecord.cold(origin)))

  /** How a dial is classified: as the governor's state says when it reserves, or as it said when
    * the governor decided the dial.
    */
  private enum Classified:
    case AtReservation, AtDecision

  /** The connection manager, the governor's state, and what the jobs left: the connection of each
    * peer and its release, the peers whose dial the limit refused, and the answer to a big ledger
    * request.
    */
  private final class World(
      cm: ConnectionManager[IO],
      val state: Ref[IO, S],
      held: Ref[IO, Map[Int, (Connection[IO], IO[Unit])]],
      val refused: Ref[IO, Vector[Int]],
      bigLedger: Ref[IO, Set[Int]],
      classified: Classified,
      honourExempt: Boolean
  ):
    def places: IO[Int] = cm.snapshot.map(_.dials)

    def setBigLedger(peers: Set[Int]): IO[Unit] = bigLedger.set(peers)

    /** A job, with the classification of its dial when the governor decided it. */
    final case class Pending(job: Job[Int], decided: OutboundLimit.Dial)

    def pending(st: S, jobs: List[Job[Int]]): List[Pending] =
      jobs.map {
        case job @ Job.Connect(p, _, _, _) => Pending(job, st.dialOf(p))
        case job                           => Pending(job, OutboundLimit.Dial.Counted)
      }

    private def dial(peer: Int, decided: OutboundLimit.Dial, generation: BigInt) =
      val kind = classified match
        case Classified.AtReservation => state.get.map(_.dialOf(peer))
        case Classified.AtDecision    => IO.pure(decided)
      Handle
        .allow[ConnectionError](
          cm.acquire(address(peer), Provenance.Outbound, kind)
            .allocated
            .map(_.asRight[ConnectionError])
        )
        .rescue(e => IO.pure(Left(e)))
        .flatMap {
          case Right((Acquired.Reserved(c), release)) =>
            held.update(_.updated(peer, (c, release))).as(Outcome.Connected[Int](peer, generation))
          case Right((Acquired.Reused(c, _), _)) =>
            held.update(_.updated(peer, (c, IO.unit))).as(Outcome.Connected[Int](peer, generation))
          case Left(ConnectionError.OutboundLimit(_, _)) =>
            refused
              .update(_ :+ peer)
              .as(Outcome.ConnectFailed(peer, FailureClass.Transient, generation))
          case Left(_) => IO.pure(Outcome.ConnectFailed(peer, FailureClass.Transient, generation))
        }

    /** Carry out a job, and give its outcome, if it has one. */
    def run(pending: Pending): IO[Option[Outcome[Int]]] = pending.job match
      case Job.Connect(p, _, _, generation) => dial(p, pending.decided, generation).map(Some(_))
      case Job.Activate(p, _)               => IO.pure(Some(Outcome.Activated(p)))
      case Job.Deactivate(p)                => IO.pure(Some(Outcome.Deactivated(p)))
      case Job.Close(p, _) =>
        held
          .modify(m => (m - p, m.get(p)))
          .flatMap(_.traverse_((c, release) => c.releaseOutbound *> release))
          .as(Some(Outcome.Closed(p)))
      case Job.RequestRoots(_, _) => IO.pure(Some(Outcome.RootsFailed()))
      case Job.RequestBigLedger(_) =>
        bigLedger.get.map(found =>
          Some(
            if found.isEmpty then Outcome.BigLedgerFailed()
            else Outcome.BigLedgerFound(BigLedgerRoots.Ledger(found), 1.hour)
          )
        )
      case Job.Share(id, _, peers) =>
        IO.pure(Some(Outcome.Shared(id, peers.map(_ -> PeerShare.Result.NotRegistered()))))
      case Job.Exempt(p) =>
        held.get
          .flatMap(_.get(p).traverse_(_._1.exempt))
          .whenA(honourExempt)
          .as(Option.empty[Outcome[Int]])

    private def settle(st0: S): (S, List[Job[Int]]) =
      @scala.annotation.tailrec
      def go(st: S, jobs: List[Job[Int]], fuel: Int): (S, List[Job[Int]]) =
        require(fuel > 0, "the governor did not settle")
        PeerSelection.decide(st, t0) match
          case Step.Act(d)     => go(d.state, jobs ++ d.jobs, fuel - 1)
          case Step.Idle(s, _) => (s, jobs)
      go(st0, Nil, 100)

    /** Apply `event`, run `jobs` and the jobs the event and the outcomes make, until the governor
      * has nothing left to do but the jobs `hold` keeps back, which are returned with `kept` to be
      * run later.
      */
    def drive(
        event: Option[Event[Int]],
        jobs: List[Pending],
        kept: List[Pending],
        hold: Job[Int] => Boolean
    ): IO[List[Pending]] =
      def apply(e: Event[Int]): IO[List[Pending]] =
        state.modify { st =>
          val d = PeerSelection.apply(st, e, t0)
          (d.state, pending(d.state, d.jobs))
        }
      event.fold(IO.pure(List.empty[Pending]))(apply).flatMap { first =>
        (jobs ++ first, kept).tailRecM { (jobs, kept) =>
          jobs match
            case Nil =>
              state
                .modify { st =>
                  val (settled, more) = settle(st)
                  (settled, pending(settled, more))
                }
                .map(more => if more.isEmpty then Right(kept) else Left((more, kept)))
            case p :: rest if hold(p.job) => IO.pure(Left((rest, kept :+ p)))
            case p :: rest =>
              run(p).flatMap {
                case None => IO.pure(Left((rest, kept)))
                case Some(outcome) =>
                  apply(Event.Completed(outcome)).map(m => Left((rest ++ m, kept)))
              }
        }
      }

    /** Run held jobs now, and what follows from them. */
    def release(jobs: List[Pending]): IO[List[Pending]] = drive(None, jobs, Nil, _ => false)

  private def world(st0: S, classified: Classified, honourExempt: Boolean): IO[World] =
    (
      ConnectionManager.create[IO](ConnectionManager.Limits(outbound = atMost(threshold))),
      Ref.of[IO, S](st0),
      Ref.of[IO, Map[Int, (Connection[IO], IO[Unit])]](Map.empty),
      Ref.of[IO, Vector[Int]](Vector.empty),
      Ref.of[IO, Set[Int]](Set.empty)
    ).mapN((cm, state, held, refused, big) =>
      World(cm, state, held, refused, big, classified, honourExempt)
    )

  private def reload(members: Int*): Event[Int] =
    Event.RootsConfigured(roots(members*), false, Set.empty, Some(BigInt(1)))

  /** Two big ledger peers dialled and counted, then made the local roots of a reload, whose big
    * ledger replacements are dialled: the places held at the end, the peers refused, and the
    * statuses of the replacements.
    */
  private def reclassified(honourExempt: Boolean): IO[(Int, Vector[Int], List[Status])] =
    for
      w <- world(
        initial(1 -> Origin.BigLedger, 2 -> Origin.BigLedger),
        Classified.AtReservation,
        honourExempt
      )
      _ <- w.drive(None, Nil, Nil, _ => false)
      connected <- w.state.get
      _ <- IO(assertEquals(List(1, 2).map(connected.peers(_).status.established), List(true, true)))
      // the reload makes both local roots; the big ledger request, due again (a reload keeps
      // its retry time, issue 810), then finds two others
      _ <- w.state.update(_.copy(bigLedgerRetryAt = t0))
      _ <- w.setBigLedger(Set(3, 4))
      _ <- w.drive(Some(reload(1, 2)), Nil, Nil, _ => false)
      after <- w.state.get
      places <- w.places
      refused <- w.refused.get
    yield (places, refused, List(3, 4).flatMap(after.peers.get).map(_.status))

  test(
    "big ledger peers made local roots while connected hold no place: their replacements are not refused at the limit"
  ) {
    reclassified(honourExempt = true).map { (places, refused, replacements) =>
      assertEquals(refused, Vector.empty, "no dial refused")
      assertEquals(places, 2, "only the two big ledger replacements hold places")
      assertEquals(replacements.map(_.established), List(true, true), replacements.toString)
    }
  }

  // the same reload with the governor's step ignored: the former big ledger peers keep the places
  // their dials took, and one of the two replacements is refused, for as long as they stay
  // connected
  test("without the step that leaves them out, a replacement is refused at the limit") {
    reclassified(honourExempt = false).map { (places, refused, _) =>
      assertEquals(places, 3)
      assertEquals(refused.size, 1, refused.toString)
    }
  }

  /** An ordinary peer A and a big ledger peer Q connected, while the dial of another big ledger
    * peer P is held back before it reserves; a reload makes P a local root, and a replacement big
    * ledger peer R takes the last place; then P's dial reserves. The places held, the peers
    * refused, and P's status.
    */
  private def pendingAtReload(classified: Classified): IO[(Int, Vector[Int], Status)] =
    val (a, q, p, r) = (10, 1, 2, 3)
    for
      w <- world(
        initial(a -> Origin.Public, q -> Origin.BigLedger, p -> Origin.BigLedger),
        classified,
        honourExempt = true
      )
      heldP <- w.drive(None, Nil, Nil, { case Job.Connect(`p`, _, _, _) => true; case _ => false })
      _ <- IO(
        assertEquals(
          heldP.map(_.job),
          List(Job.Connect(p, DiffusionMode.InitiatorAndResponder, Provenance.Outbound))
        )
      )
      // the reload: P, still connecting, becomes a local root; the big ledger request, due
      // again (a reload keeps its retry time, issue 810), finds R
      _ <- w.state.update(_.copy(bigLedgerRetryAt = t0))
      _ <- w.setBigLedger(Set(r))
      closes <- w.drive(
        Some(reload(p)),
        Nil,
        heldP,
        {
          case Job.Connect(`p`, _, _, _) | Job.Close(_, _) => true
          case _                                           => false
        }
      )
      full <- w.places
      // P's dial reserves now, with every place taken, and the closes the reload asked for run
      _ <- w.release(closes)
      places <- w.places
      refused <- w.refused.get
      after <- w.state.get
    yield
      assertEquals(full, threshold, "A, Q and R take every place before P reserves")
      (places, refused, after.peers(p).status)

  test("a peer made a local root while its connect is pending is never refused at the limit") {
    pendingAtReload(Classified.AtReservation).map { (_, refused, status) =>
      assertEquals(refused, Vector.empty, s"the local root is not refused: $refused")
      assert(status.established, s"the local root is connected: $status")
    }
  }

  // with the classification the governor had when it decided the dial, P is counted, and refused
  test("classified when the governor decided the dial, the same local root is refused") {
    pendingAtReload(Classified.AtDecision).map { (_, refused, _) =>
      assertEquals(refused, Vector(2), s"refused: $refused")
    }
  }
