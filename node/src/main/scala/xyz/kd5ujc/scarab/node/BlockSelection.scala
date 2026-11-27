// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.effect.syntax.all.*
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import cats.data.NonEmptyVector
import xyz.kd5ujc.scarab.consensus.{BlockRef, ChainSelection, LeiosCertGate, LeiosForestBlock}
import xyz.kd5ujc.scarab.storage.ReceivedBlocks
import xyz.kd5ujc.scarab.protocol.EbHash
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
  /** `last` keeps the block fetched most recently: a refusal is reported for the block whose rules
    * just ran on it ([[Follower]]'s `switchTo`), inside an uncancelable section, so the refused
    * original is taken from there and never read again where a wait could not be cancelled.
    */
  private def reader(
      bodies: ProcessedForest[BlockPool.Processed],
      last: Ref[F, Option[(BlockPool.Processed, Bytes)]]
  ): BlockSelection.Bodies[F] =
    new BlockSelection.Bodies[F]:
      def forged(header: Header) =
        bodies.get(header.hash).exists(_.origin == BlockPool.Source.Forged)
      def fetch(header: Header)(using Raise[F, NodeError]) =
        bodies
          .get(header.hash)
          .flatTraverse(p => pool.body(header.hash, p).map(_.map(p -> _)))
          .flatMap(
            _.fold(BlockPoolProblem.MissingDeliveredHeader(header.point).raise[F, Bytes]) {
              (p, raw) => last.set(Some(p -> raw)).as(raw)
            }
          )
      def invalidated(header: Header, error: ValidationError) =
        last.get.flatMap {
          case Some((p, raw)) if p.delivered.header.hash == header.hash =>
            pool.invalidated(
              BlockPool.Taken(raw, false, p.origin, Some(p.delivered)),
              header,
              error
            )
          case _ =>
            F.pure[Either[BlockPoolProblem, Unit]](
              Left(BlockPoolProblem.MissingDeliveredHeader(header.point))
            )
        }

  /** Whom a refusal in a selection for `cause` may blame: the sender of an admitted block, nobody
    * else (`Peer.Validation`).
    */
  private def validation(cause: BlockSelection.Cause): Peer.Validation[F] = cause match
    case BlockSelection.Cause.Admitted(admission) => Peer.Validation.admission(pool, admission)
    case BlockSelection.Cause.Reselection | BlockSelection.Cause.Forged(_) |
        BlockSelection.Cause.LeiosEbAcquired(_, _) =>
      Peer.Validation.none[F]

  private def selectLocked(
      cause: BlockSelection.Cause,
      phase: Phase = Phase.Admission
  )(using Raise[F, NodeError]): F[Unit] =
    for
      // The time is read once, for the release: a released block an attempt has changed is
      // excluded for the rest of this selection, so its end does not depend on the clock (#693).
      now <- F.monotonic
      current <- application.local
      bodies <- pool.processedForest
      // only a reselection judges a withheld block again, the one due first with those of its
      // chain, never an admission: a delivery cannot bring an attempt forward, and a refusal on it
      // blames no session
      released <- cause match
        case BlockSelection.Cause.Reselection =>
          pool.noVerdictBlocks
            .release(now, hash => bodies.get(hash).flatMap(_.delivered.header.predecessor))
        case BlockSelection.Cause.Admitted(_) | BlockSelection.Cause.Forged(_) |
            BlockSelection.Cause.LeiosEbAcquired(_, _) =>
          F.pure(None)
      known <- pool.invalidBlocks.snapshot
      // a block the node has no verdict on is not judged again before its time (issues 567, 693)
      withheld <- pool.noVerdictBlocks.excluded(released)
      // a ranking block certifying an endorser block whose closure is not acquired is parked: hidden
      // as a tip and as a successor until it is (`ignoreUnacquiredCertRB`,
      // `ignoreUnacquiredCertRBSuc`, `ChainSel.hs:1046-1064` at ouroboros-consensus 2416493b),
      // asked of each block the walks reach, as the reference wraps the forest's functions
      acquired <- pool.leios.snapshot
      parked = LeiosCertGate.unacquired(acquired, BlockSelection.leiosBlock(bodies))
      original = AvailableBlocks.candidates(
        if phase == Phase.Startup then current.copy(blocks = Vector.empty) else current,
        bodies,
        hash => known.entries.contains(hash) || withheld(hash) || parked(hash),
        cause.containing,
        originPredecessor
      )
      _ <- F.tailRecM(()) { _ =>
        for
          before <- pool.invalidBlocks.snapshot
          // ChainSel.hs:1145–1186 (ouroboros-consensus 82ecba32) truncates and reinserts the
          // original candidate pool. An unrelated branch cannot become attributed to this adding
          // client during an invalid-prefix retry.
          excluded <- pool.noVerdictBlocks.excluded(released)
          truncated = original.map(candidate =>
            candidate.copy(headers =
              candidate.headers.takeWhile(h =>
                !before.entries.contains(h.hash) && !excluded.contains(h.hash)
              )
            )
          )
          ordered <- application.project(truncated, phase)
          last <- Ref.of[F, Option[(BlockPool.Processed, Bytes)]](None)
          _ <- ordered.headOption.traverse_(candidate =>
            application.apply(
              reader(bodies, last),
              candidate,
              validation(cause),
              phase
            )
          )
          after <- pool.invalidBlocks.snapshot
          withheldAfter <- pool.noVerdictBlocks.excluded(released)
        // select again when a block was refused, or withheld for want of a verdict
        yield
          if after.revision != before.revision || withheldAfter != excluded then Left(())
          else Right(())
      }
      // a released block no preferred candidate reached waits its delay again, uncounted
      _ <- released.traverse_(pool.noVerdictBlocks.unreached)
    yield ()

  /** Chain selection for a block this node forged, which is in the processed forest: as for a
    * delivered block, only the candidates through it, and nobody to punish for a refusal. The
    * caller holds the application lock ([[Application.locked]]) and gives its own error scope.
    */
  private[node] val selecting: BlockSelection.Selecting[F] = new BlockSelection.Selecting[F]:
    def forged(hash: Bytes)(using Raise[F, NodeError]): F[Unit] =
      selectLocked(BlockSelection.Cause.Forged(hash))

  private def consume(using Raise[F, NodeError]): F[Unit] =
    F.foreverM(pool.admissions.next.flatMap {
      case BlockAdmissions.Work.Add(admission) =>
        val header = admission.delivered.header
        pool.admissions.processing(WorkKind.Add, AdmissionStage.WaitingForApplication)(
          work
            .processing(header.blockPoint)
            .surround(application.locked {
              pool.admissions.stage(AdmissionStage.Applying) *> application.admits(header).flatMap {
                case false => before.passed(header)
                case true =>
                  pool.processedForest.flatMap { processed =>
                    if processed.contains(header.hash) then before.passed(header)
                    else
                      before.prepare(header, admission.taken.block) *> pool
                        .processAdmission(admission)
                        .flatMap(_.fold(_.raise[F, Boolean], F.pure)) *>
                        selectLocked(BlockSelection.Cause.Admitted(admission))
                  }
              }
            }) *> pool.completeAdmission(header.hash)
        )
      case BlockAdmissions.Work.Reprocess(done) =>
        pool.admissions.processing(WorkKind.Reprocess, AdmissionStage.WaitingForApplication)(
          application
            .locked(
              pool.admissions.stage(AdmissionStage.Selecting) *>
                selectLocked(BlockSelection.Cause.Reselection)
            )
            .guarantee(done.complete(()).void)
        )
      case BlockAdmissions.Work.ReprocessLeiosEb(eb) =>
        pool.admissions.processing(
          WorkKind.ReprocessLeiosEb,
          AdmissionStage.WaitingForApplication
        )(
          application.locked(
            pool.admissions.stage(AdmissionStage.Selecting) *> reprocessLeiosEb(eb)
          )
        )
    })

  /** `chainSelReprocessLeiosEb` (ouroboros-consensus 2416493b `ChainSel.hs:636-685`): the ranking
    * blocks parked on `eb`, whose closure is now acquired, are the certifying successors of the
    * blocks that announce it; the candidates through any of them are considered together, and
    * nothing is done when none is parked.
    */
  private def reprocessLeiosEb(eb: EbHash)(using Raise[F, NodeError]): F[Unit] =
    (pool.processedForest, pool.invalidBlocks.snapshot).flatMapN { (bodies, known) =>
      NonEmptyVector
        .fromVector(
          LeiosCertGate
            .parkedOn(eb, BlockSelection.forest(bodies), known.entries.contains)
            .toVector
        )
        .traverse_(parked => selectLocked(BlockSelection.Cause.LeiosEbAcquired(eb, parked)))
    }

  /** `leiosAcquiredEbsRunner` (ouroboros-consensus 2416493b `ChainDB/Impl/Background.hs:170-200`):
    * each completion of the store joins the acquired set, before the reprocessing is queued, and a
    * novel one queues the reprocessing of the ranking blocks parked on it.
    */
  private def acquiring: F[Unit] =
    F.foreverM(
      pool.leios.next.flatMap(point =>
        pool.leios.acquired(point).flatMap(pool.admissions.reprocessLeiosEb(point.hash).whenA)
      )
    )

  /** Judge again the withheld blocks whose cause can pass (issue 693): when one is due, a
    * reselection is queued behind the admissions already waiting, and the next only once it is
    * done, so at most one is in flight in the node and admissions are not starved.
    */
  private def retries: F[Unit] =
    F.foreverM(pool.noVerdictBlocks.awaitDue *> pool.admissions.reprocess)

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
        given Raise[F, NodeError] = Peer.contramapped(summon[Handle[F, Fatal]])(Fatal.of)
        application
          .locked(selectLocked(BlockSelection.Cause.Reselection, Phase.Startup)) *>
          ready.complete(Right(())).void *>
          F.both(consume, F.both(reselect, F.both(retries, acquiring))).void
      }
      .guarantee(pool.admissions.close)
      .flatMap {
        case Left(error) => ready.complete(Left(error)).void *> fatal(error)
        case Right(_)    => F.unit
      }

private[node] object BlockSelection:
  enum Phase:
    case Startup, Admission

  /** What a selection runs for: the worker's reselection (the LoE moved, the node caught up, a
    * withheld block is due, or the start), whose candidates are every path the forest has, and
    * which judges again the withheld block due first; a delivered block's admission, whose
    * candidates go through it and whose refusals may blame its sender; or a block this node forged
    * ([[Follower.submitForged]]), whose candidates go through it and whose refusals blame nobody.
    * The reference runs `ChainSelReprocessLoEBlocks` for the first and `chainSelectionForBlock` for
    * the other two (ouroboros-consensus 82ecba329 `Storage/ChainDB/Impl/ChainSel.hs:372,452`).
    */
  enum Cause:
    case Reselection
    case Admitted(admission: BlockPool.Admission)
    case Forged(hash: Bytes)

    /** The closure of `eb` is acquired: the candidates through the ranking blocks `parked` on it
      * (`ChainSelReprocessLeiosEb`, ouroboros-consensus 2416493b `ChainSel.hs:405-412`).
      */
    case LeiosEbAcquired(eb: EbHash, parked: NonEmptyVector[Bytes])

    /** The blocks a candidate goes through one of: none for a reselection, which takes them all. */
    def containing: Option[Set[Bytes]] = this match
      case Reselection             => None
      case Admitted(admission)     => Some(Set(admission.delivered.header.hash))
      case Forged(hash)            => Some(Set(hash))
      case LeiosEbAcquired(_, set) => Some(set.toVector.toSet)

  /** What the acquired-endorser-block gate reads of the processed forest: each block's predecessor
    * and Leios fields, as the reference reads them from the VolatileDB's `BlockInfo`.
    */
  def forest(bodies: ProcessedForest[BlockPool.Processed]): Map[Bytes, LeiosForestBlock] =
    bodies.byHash.view
      .mapValues(p => LeiosForestBlock(p.delivered.header.prevHash, p.delivered.leios))
      .toMap

  /** The same, of one block: what the gate reads of a block a selection's walk reaches. */
  def leiosBlock(bodies: ProcessedForest[BlockPool.Processed])(
      hash: Bytes
  ): Option[LeiosForestBlock] =
    bodies.get(hash).map(p => LeiosForestBlock(p.delivered.header.prevHash, p.delivered.leios))

  /** The worker's chain selection for a block this node forged, run by the follower under the
    * application lock it holds.
    */
  trait Selecting[F[_]]:
    def forged(hash: Bytes)(using Raise[F, NodeError]): F[Unit]

  /** What is done with a block before it joins the processed forest: a delivered block is written
    * to the received log and its verification cache warmed (`prepare`); a block this node forged is
    * written to the received log only (`store`), since its rules ran or run when selection reaches
    * it ([[Follower.submitForged]]). When a delivered block enters the add-block queue, its store
    * reads and its signature checks are asked for ahead of the worker (`ahead`,
    * [[ReadAheadStore.ahead]], [[VerifyAhead.ahead]]), which must not wait: it runs under the
    * queue's publication. A delivered block the worker takes from the queue and does not prepare
    * (older than the immutable tip, or already processed) is `passed` by.
    */
  trait Before[F[_]]:
    def prepare(header: Header, raw: Bytes)(using Raise[F, NodeError]): F[Unit]
    def store(header: Header, raw: Bytes)(using Raise[F, NodeError]): F[Unit]
    def ahead(header: Header, raw: Bytes): F[Unit]
    def passed(header: Header): F[Unit]

  object Before:
    /** The node's: a delivered block is written to the received log (`received`, when the node
      * keeps one) and its signature checks asked for (`preverify`), unless the verification cache
      * holds every verdict of them already (`holds`), as it does for a block whose checks were all
      * made ahead of the worker ([[VerifyAhead]], #1499); when a Shelley-based block enters the
      * queue its store reads (`reads`, #1484) and its signature checks are asked for ahead of the
      * worker; one passed by is the verifier's no longer.
      */
    def delivered[F[_]: cats.Monad](
        received: Option[ReceivedBlocks[F]],
        preverify: Peer.Prepare[F],
        holds: Bytes => F[Boolean],
        verifyAhead: VerifyAhead[F],
        reads: (Header, Bytes) => F[Unit]
    ): Before[F] = new Before[F]:
      def prepare(header: Header, raw: Bytes)(using Raise[F, NodeError]): F[Unit] =
        store(header, raw) *> (header match
          case _: Header.Shelley =>
            verifyAhead.reached(header.hash) *>
              holds(header.hash).ifM(verifyAhead.skipped, preverify(Vector(raw)))
          case _ => preverify(Vector(raw)))
      def store(header: Header, raw: Bytes)(using Raise[F, NodeError]): F[Unit] =
        received.traverse_(
          _.put(BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary), raw)
        )
      def ahead(header: Header, raw: Bytes): F[Unit] = header match
        case _: Header.Shelley => reads(header, raw) *> verifyAhead.ahead(header, raw)
        case _                 => cats.Monad[F].unit
      def passed(header: Header): F[Unit] = verifyAhead.passed(header.hash)

    def noop[F[_]: cats.Applicative]: Before[F] = new Before[F]:
      def prepare(@scala.annotation.unused header: Header, @scala.annotation.unused raw: Bytes)(
          using Raise[F, NodeError]
      ): F[Unit] = cats.Applicative[F].unit
      def store(@scala.annotation.unused header: Header, @scala.annotation.unused raw: Bytes)(using
          Raise[F, NodeError]
      ): F[Unit] = cats.Applicative[F].unit
      def ahead(
          @scala.annotation.unused header: Header,
          @scala.annotation.unused raw: Bytes
      ): F[Unit] = cats.Applicative[F].unit
      def passed(@scala.annotation.unused header: Header): F[Unit] = cats.Applicative[F].unit

  /** What the worker applies a candidate from: the bodies the pool admitted, and where a refusal of
    * one is recorded against its original (issue 599). A peer session has no part in it.
    */
  trait Bodies[F[_]]:
    def fetch(header: Header)(using Raise[F, NodeError]): F[Bytes]

    /** Whether the block is one this node forged (`BlockPool.Source.Forged`): with no verdict on
      * it, it is our own block without a verdict, which is not judged again (R3 of the #567
      * decision).
      */
    def forged(header: Header): Boolean
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
  )(using before: Before[F], owner: Raise[F, NodeError]): Resource[F, Selecting[F]] =
    work.session.flatMap { session =>
      pool.invalidBlocks.selectionResource *> pool.admissions.resource(a =>
        session.ready(Vector(a.delivered.header.hash)) *>
          before.ahead(a.delivered.header, a.taken.block)
      ) *>
        (for
          ready <- Resource.eval(Deferred[F, Either[Fatal, Unit]])
          selection = new BlockSelection(pool, session, application, originPredecessor, before)
          _ <- Resource.make(selection.run(fatal, ready).start)(_.cancel)
          result <- Resource.eval(ready.get)
          _ <- Resource.eval(result match
            case Right(_)                        => Async[F].unit
            case Left(Fatal.Storage(cause))      => owner.raise[NodeError, Unit](cause)
            case Left(Fatal.Unavailable(cause))  => owner.raise[NodeError, Unit](cause)
            case Left(Fatal.Internal(cause))     => owner.raise[NodeError, Unit](cause)
            case Left(Fatal.Unverifiable(cause)) => owner.raise[NodeError, Unit](cause)
            case Left(Fatal.Defect(cause))       => Async[F].raiseError[Unit](cause)
            // the selection raises none; the node's sink stops the node on it all the same
            case Left(voting @ Fatal.LeiosVoting(_)) => fatal(voting))
        yield selection.selecting)
    }
