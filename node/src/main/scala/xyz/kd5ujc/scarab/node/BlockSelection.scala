// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Ref, Resource}
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
    available: Ref[F, Map[Bytes, BlockPool.Admission]],
    application: BlockSelection.Application[F],
    originPredecessor: Option[Bytes]
):
  private val F = Async[F]

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
    application.locked(F.tailRecM(true) { restricted =>
      for
        current <- application.local
        bodies <- available.get
        known <- pool.invalidBlocks.snapshot
        paths <- AvailableBlocks
          .candidates(
            current,
            bodies.view.mapValues(_.delivered.header).toMap,
            known.entries.keySet,
            adding.filter(_ => restricted).map(_.delivered.header.hash),
            originPredecessor
          )
          .leftMap {
            case AvailableBlocks.Error.HashMismatch(key, actual) =>
              BlockPoolProblem.AvailableHashMismatch(key, actual)
            case AvailableBlocks.Error.Cycle(hash) => BlockPoolProblem.AvailableCycle(hash)
          }
          .fold(_.raise[F, Vector[ChainSelection.Candidate]], F.pure)
        // AvailableBlocks emits only nonempty paths, in native unsigned-hash DFS order. Stable
        // ties retain that order; preference belongs to the selected era's consensus protocol.
        best = paths.reduceOption((left, right) =>
          if application.prefers(left.headers.last, right.headers.last) then right else left
        )
        _ <- best.traverse_(candidate =>
          application.apply(
            reader(candidate, bodies),
            candidate,
            adding.fold(Peer.Validation.none[F])(Peer.Validation.admission(pool, _))
          )
        )
        after <- pool.invalidBlocks.snapshot
      yield if after.revision != known.revision then Left(false) else Right(())
    })

  private def consume(using Raise[F, NodeError]): F[Unit] =
    F.foreverM(pool.admissions.next.flatMap {
      case BlockAdmissions.Work.Add(admission, prepare) =>
        val header = admission.delivered.header
        prepare *> available.update(_.updated(header.hash, admission)) *>
          work.processing(header.hash).surround(select(Some(admission))) *>
          pool.completeAdmission(header.hash) *>
          application.anchorSlot.flatMap(cutoff =>
            available.update(_.filter((_, body) => cutoff.forall(body.delivered.header.slot >= _)))
          )
      case BlockAdmissions.Work.Reprocess(done) => select(None).guarantee(done.complete(()).void)
    })

  private def reselect: F[Unit] = application.governor.flatMap(_.fold(F.never[Unit]) { governor =>
    F.tailRecM[Long, Unit](-1L)(previous =>
      governor
        .selectionChanged(previous)
        .flatMap(revision => pool.admissions.reprocess.as(Left(revision)))
    )
  })

  def run(fatal: Fatal => F[Unit]): F[Unit] =
    Fibers
      .scoped[F, Fatal, Unit](Fatal.Defect(_)) {
        val scope = summon[Handle[F, Fatal]]
        given Raise[F, NodeError] = Peer.contramapped(scope)(error =>
          SessionEnd.of(error) match
            case SessionEnd.Storage(cause)     => Fatal.Storage(cause)
            case SessionEnd.Unavailable(cause) => Fatal.Unavailable(cause)
            case _                             => Fatal.Internal(error)
        )
        select(None) *> F.both(consume, reselect).void
      }
      .guarantee(pool.admissions.close)
      .flatMap(_.fold(fatal, _ => F.unit))

private[node] object BlockSelection:
  trait Application[F[_]]:
    def local: F[ChainSelection.Local]
    def anchorSlot: F[Option[BigInt]]
    def governor: F[Option[GenesisGovernor[F]]]
    def locked[A](body: F[A]): F[A]
    def prefers(left: Header, right: Header): Boolean
    def apply(peer: Peer[F], candidate: ChainSelection.Candidate, validation: Peer.Validation[F])(
        using Raise[F, NodeError]
    ): F[Unit]

  def resource[F[_]: Async](
      pool: BlockPool[F],
      work: SelectionWork[F],
      fatal: Fatal => F[Unit],
      application: Application[F],
      originPredecessor: Option[Bytes]
  ): Resource[F, Unit] =
    work.session.flatMap { session =>
      pool.invalidBlocks.selectionResource *> pool.admissions.resource(a =>
        session.ready(Vector(a.delivered.header.hash))
      ) *>
        Resource
          .eval(pool.selectionSnapshot.flatMap(Ref.of[F, Map[Bytes, BlockPool.Admission]](_)))
          .flatMap(available =>
            Resource
              .make(
                new BlockSelection(pool, session, available, application, originPredecessor)
                  .run(fatal)
                  .start
              )(_.cancel)
              .void
          )
    }
