// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Deferred, Resource}
import cats.effect.syntax.all.*
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.ChainSelection
import xyz.kd5ujc.scarab.ledger.{BlockPoolProblem, Header, NodeError, PeerError, ValidationError}
import xyz.kd5ujc.scarab.network.Fibers
import xyz.kd5ujc.scarab.protocol.ChainSync

/** The single ChainDB add-block runner (consensus 82ecba329, Background.hs:658–708). Its forest
  * contains processed bodies only: later queued receipts cannot change an earlier adding cause.
  */
private[node] final class BlockSelection[F[_]: Async] private (
    pool: BlockPool[F],
    work: SelectionWork.Session[F],
    application: BlockSelection.Application[F],
    originPredecessor: Option[Bytes],
    before: BlockSelection.Before[F]
):
  private val F = Async[F]
  import BlockSelection.Phase

  private def reader(
      candidate: ChainSelection.Candidate,
      bodies: Map[Bytes, BlockPool.Admission]
  )(using Raise[F, NodeError]): Peer[F] = new Peer[F]:
    override def invalidBlocks = Some(pool.invalidBlocks)
    def intersect(@scala.annotation.unused points: Vector[ChainSync.Point])(using
        Raise[F, PeerError]
    ) =
      F.pure(Some(candidate.base))
    def next(using Raise[F, PeerError]) = F.never[SyncEvent]
    def fetch(header: Header)(using Raise[F, PeerError]) = bodies
      .get(header.hash)
      .fold(BlockPoolProblem.MissingDeliveredHeader(header.point).raise[F, Bytes])(a =>
        F.pure(a.taken.block)
      )
    override def validation(
        @scala.annotation.unused header: Header
    )(using cats.Applicative[F], Raise[F, PeerError]) =
      F.pure(Right(Peer.Validation.none[F]): Either[BlockPoolProblem, Peer.Validation[F]])
    override def invalidated(header: Header, error: ValidationError)(using cats.Applicative[F]) =
      bodies
        .get(header.hash)
        .fold(
          F.pure[Either[BlockPoolProblem, Unit]](
            Left(BlockPoolProblem.MissingDeliveredHeader(header.point))
          )
        )(a => pool.invalidated(a.taken, header, error))

  private def select(adding: Option[BlockPool.Admission])(using Raise[F, NodeError]): F[Unit] =
    application.locked(selectLocked(adding))

  private def selectLocked(
      adding: Option[BlockPool.Admission],
      phase: Phase = Phase.Admission
  )(using Raise[F, NodeError]): F[Unit] =
    for
      current <- application.local
      bodies <- pool.processedSnapshot
      known <- pool.invalidBlocks.snapshot
      original <- AvailableBlocks
        .candidates(
          if phase == Phase.Startup then current.copy(blocks = Vector.empty) else current,
          bodies.view.mapValues(_.delivered.header).toMap,
          known.entries.keySet,
          adding.map(_.delivered.header.hash),
          originPredecessor
        )
        .leftMap {
          case AvailableBlocks.Error.HashMismatch(key, actual) =>
            BlockPoolProblem.AvailableHashMismatch(key, actual)
          case AvailableBlocks.Error.Cycle(hash) => BlockPoolProblem.AvailableCycle(hash)
        }
        .fold(_.raise[F, Vector[ChainSelection.Candidate]], F.pure)
      _ <- F.tailRecM(()) { _ =>
        for
          before <- pool.invalidBlocks.snapshot
          // ChainSel.hs:1145–1186 truncates and reinserts the original candidate pool. An unrelated
          // branch cannot become attributed to this adding client during an invalid-prefix retry.
          truncated = original.map(candidate =>
            candidate.copy(headers =
              candidate.headers.takeWhile(h => !before.entries.contains(h.hash))
            )
          )
          ordered <- application.project(truncated, phase)
          _ <- ordered.headOption.traverse_(candidate =>
            application.apply(
              reader(candidate, bodies),
              candidate,
              adding.fold(Peer.Validation.none[F])(Peer.Validation.admission(pool, _)),
              phase
            )
          )
          after <- pool.invalidBlocks.snapshot
        yield if after.revision != before.revision then Left(()) else Right(())
      }
    yield ()

  private def consume(using Raise[F, NodeError]): F[Unit] =
    F.foreverM(pool.admissions.next.flatMap {
      case BlockAdmissions.Work.Add(admission) =>
        val header = admission.delivered.header
        pool.admissions.processing("add", "waitingForApplication")(
          work
            .processing(header.hash)
            .surround(application.locked {
              pool.admissions.stage("applying") *> application.admits(header).flatMap {
                case false => F.unit
                case true =>
                  pool.processedSnapshot.flatMap { processed =>
                    if processed.contains(header.hash) then F.unit
                    else
                      before.prepare(header, admission.taken.block) *> pool
                        .processAdmission(admission) *>
                        selectLocked(Some(admission))
                  }
              }
            }) *> pool.completeAdmission(header.hash)
        )
      case BlockAdmissions.Work.Reprocess(done) =>
        pool.admissions.processing("reprocess", "selecting")(
          select(None).guarantee(done.complete(()).void)
        )
    })

  private def reselect: F[Unit] = application.governor.flatMap(_.fold(F.never[Unit]) { governor =>
    F.tailRecM[Long, Unit](-1L)(previous =>
      governor
        .selectionChanged(previous)
        .flatMap(revision => pool.admissions.reprocess.as(Left(revision)))
    )
  })

  def run(fatal: Fatal => F[Unit], ready: Deferred[F, Either[Fatal, Unit]]): F[Unit] =
    Fibers
      .scoped[F, Fatal, Unit](Fatal.Defect(_)) {
        val scope = summon[Handle[F, Fatal]]
        given Raise[F, NodeError] = Peer.contramapped(scope)(error =>
          SessionEnd.of(error) match
            case SessionEnd.Storage(cause)     => Fatal.Storage(cause)
            case SessionEnd.Unavailable(cause) => Fatal.Unavailable(cause)
            case _                             => Fatal.Internal(error)
        )
        application
          .locked(selectLocked(None, Phase.Startup)) *> ready.complete(Right(())).void *>
          F.both(consume, reselect).void
      }
      .guarantee(pool.admissions.close)
      .flatMap {
        case Left(error) => ready.complete(Left(error)).void *> fatal(error)
        case Right(_)    => F.unit
      }

private[node] object BlockSelection:
  enum Phase:
    case Startup, Admission

  trait Before[F[_]]:
    def prepare(header: Header, raw: Bytes)(using Raise[F, NodeError]): F[Unit]

  object Before:
    def noop[F[_]: cats.Applicative]: Before[F] = new Before[F]:
      def prepare(@scala.annotation.unused header: Header, @scala.annotation.unused raw: Bytes)(
          using Raise[F, NodeError]
      ): F[Unit] = cats.Applicative[F].unit

  trait Application[F[_]]:
    def local: F[ChainSelection.Local]
    def admits(header: Header): F[Boolean]
    def governor: F[Option[GenesisGovernor[F]]]
    def locked[A](body: F[A]): F[A]
    def project(
        candidates: Vector[ChainSelection.Candidate],
        phase: Phase
    ): F[Vector[ChainSelection.Candidate]]
    def apply(
        peer: Peer[F],
        candidate: ChainSelection.Candidate,
        validation: Peer.Validation[F],
        phase: Phase
    )(using
        Raise[F, NodeError]
    ): F[Unit]

  def resource[F[_]: Async](
      pool: BlockPool[F],
      work: SelectionWork[F],
      fatal: Fatal => F[Unit],
      application: Application[F],
      originPredecessor: Option[Bytes]
  )(using before: Before[F], owner: Raise[F, NodeError]): Resource[F, Unit] =
    work.session.flatMap { session =>
      pool.invalidBlocks.selectionResource *> pool.admissions.resource(a =>
        session.ready(Vector(a.delivered.header.hash))
      ) *>
        (for
          ready <- Resource.eval(Deferred[F, Either[Fatal, Unit]])
          _ <- Resource.make(
            new BlockSelection(pool, session, application, originPredecessor, before)
              .run(fatal, ready)
              .start
          )(_.cancel)
          result <- Resource.eval(ready.get)
          _ <- Resource.eval(result match
            case Right(_)                       => Async[F].unit
            case Left(Fatal.Storage(cause))     => owner.raise[NodeError, Unit](cause)
            case Left(Fatal.Unavailable(cause)) => owner.raise[NodeError, Unit](cause)
            case Left(Fatal.Internal(cause))    => owner.raise[NodeError, Unit](cause)
            case Left(Fatal.Defect(cause))      => Async[F].raiseError[Unit](cause))
        yield ())
    }
