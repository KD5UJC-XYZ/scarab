// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Deferred, Resource}
import cats.effect.syntax.all.*
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.ChainSelection
import xyz.kd5ujc.scarab.ledger.{BlockPoolProblem, Header, NodeError, ValidationError}
import xyz.kd5ujc.scarab.network.Fibers
import xyz.kd5ujc.scarab.node.BlockAdmissions.{AdmissionStage, WorkKind}

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

  /** The bodies of `candidate` as the pool admitted them, and where a refusal of one is recorded
    * against its original.
    */
  private def reader(bodies: Map[Bytes, BlockPool.Admission]): BlockSelection.Bodies[F] =
    new BlockSelection.Bodies[F]:
      def fetch(header: Header)(using Raise[F, NodeError]) = bodies
        .get(header.hash)
        .fold(BlockPoolProblem.MissingDeliveredHeader(header.point).raise[F, Bytes])(a =>
          F.pure(a.taken.block)
        )
      def invalidated(header: Header, error: ValidationError) =
        bodies
          .get(header.hash)
          .fold(
            F.pure[Either[BlockPoolProblem, Unit]](
              Left(BlockPoolProblem.MissingDeliveredHeader(header.point))
            )
          )(a => pool.invalidated(a.taken, header, error))

  private def selectLocked(
      adding: Option[BlockPool.Admission],
      phase: Phase = Phase.Admission
  )(using Raise[F, NodeError]): F[Unit] =
    for
      current <- application.local
      bodies <- pool.processedSnapshot
      known <- pool.invalidBlocks.snapshot
      // a block the node has no verdict on is not judged again (issue 567)
      withheld <- pool.noVerdictBlocks.hashes
      original <- AvailableBlocks
        .candidates(
          if phase == Phase.Startup then current.copy(blocks = Vector.empty) else current,
          bodies.view.mapValues(_.delivered.header).toMap,
          known.entries.keySet ++ withheld,
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
          excluded <- pool.noVerdictBlocks.hashes
          truncated = original.map(candidate =>
            candidate.copy(headers =
              candidate.headers.takeWhile(h =>
                !before.entries.contains(h.hash) && !excluded.contains(h.hash)
              )
            )
          )
          ordered <- application.project(truncated, phase)
          _ <- ordered.headOption.traverse_(candidate =>
            application.apply(
              reader(bodies),
              candidate,
              adding.fold(Peer.Validation.none[F])(Peer.Validation.admission(pool, _)),
              phase
            )
          )
          after <- pool.invalidBlocks.snapshot
          withheldAfter <- pool.noVerdictBlocks.hashes
        // select again when a block was refused, or withheld for want of a verdict
        yield
          if after.revision != before.revision || withheldAfter != excluded then Left(())
          else Right(())
      }
    yield ()

  private def consume(using Raise[F, NodeError]): F[Unit] =
    F.foreverM(pool.admissions.next.flatMap {
      case BlockAdmissions.Work.Add(admission) =>
        val header = admission.delivered.header
        pool.admissions.processing(WorkKind.Add, AdmissionStage.WaitingForApplication)(
          work
            .processing(header.hash)
            .surround(application.locked {
              pool.admissions.stage(AdmissionStage.Applying) *> application.admits(header).flatMap {
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
        pool.admissions.processing(WorkKind.Reprocess, AdmissionStage.WaitingForApplication)(
          application
            .locked(pool.admissions.stage(AdmissionStage.Selecting) *> selectLocked(None))
            .guarantee(done.complete(()).void)
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
            // a crypto provider that fails on a block stops the node, as the exception ends the
            // reference's linked addBlockRunner thread (consensus 82ecba32
            // Storage/ChainDB/Impl/Background.hs:107-108,142)
            case SessionEnd.Unverifiable(cause) => Fatal.Unverifiable(cause)
            case SessionEnd.Defect(cause)       => Fatal.Defect(cause)
            case _                              => Fatal.Internal(error)
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

  /** What the worker applies a candidate from: the bodies the pool admitted, and where a refusal of
    * one is recorded against its original (issue 599). A peer session has no part in it.
    */
  trait Bodies[F[_]]:
    def fetch(header: Header)(using Raise[F, NodeError]): F[Bytes]
    def invalidated(header: Header, error: ValidationError): F[Either[BlockPoolProblem, Unit]]

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
        bodies: Bodies[F],
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
            case Right(_)                        => Async[F].unit
            case Left(Fatal.Storage(cause))      => owner.raise[NodeError, Unit](cause)
            case Left(Fatal.Unavailable(cause))  => owner.raise[NodeError, Unit](cause)
            case Left(Fatal.Internal(cause))     => owner.raise[NodeError, Unit](cause)
            case Left(Fatal.Unverifiable(cause)) => owner.raise[NodeError, Unit](cause)
            case Left(Fatal.Defect(cause))       => Async[F].raiseError[Unit](cause))
        yield ())
    }
