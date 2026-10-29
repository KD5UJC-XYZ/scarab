// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import cats.mtl.{Raise, Stateful}
import cats.mtl.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.ledger.{Header, NodeError, PeerError, ValidationError}
import xyz.kd5ujc.scarab.protocol.ChainSync

/** Sessions as the node runs them: a session follows the headers its peer announces, the bodies
  * reach the node's block pool as the session's BlockFetch client delivers them, and the node's
  * selection worker adopts them there (issue 578). The follower suites that script a peer without a
  * network use these.
  */
private[scarab] object WorkerTesting extends munit.Assertions:

  /** A stop of the node that the test does not expect. */
  def unexpected(fault: Fatal): IO[Unit] =
    IO.raiseError(new AssertionError(s"the node was told to stop: $fault"))

  /** A block pool whose decisions read `follower`'s committed selection, as `Main` creates it. */
  def pool[S](follower: Follower[IO, S]): IO[BlockPool[IO]] =
    BlockPool.create[IO](BlockPool.FetchPolicy(), follower.fetchSelection)

  /** The node's selection worker for `follower` over `pool`, as `Main` installs it before the
    * network starts: the follower reads the pool's fetch coordinator and invalid-block registry,
    * and every block a session delivers is adopted by the worker, not in the session. The
    * predecessor of the chain's first block is the chain's own origin, as `Main` passes the
    * setup's. Without `fatal`, a stop of the node fails the worker's fiber, which a
    * `FailsOnUncaught` suite reports as a failed test.
    */
  def selection[S](
      follower: Follower[IO, S],
      pool: BlockPool[IO],
      fatal: Fatal => IO[Unit] = unexpected
  )(using chain: Stateful[IO, VolatileChain[S]], raise: Raise[IO, NodeError]): Resource[IO, Unit] =
    given BlockSelection.Before[IO] = BlockSelection.Before.noop[IO]
    Resource
      .eval(
        follower.installFetchCoordinator(pool.fetchCoordinator) *>
          follower.installInvalidBlocks(pool.invalidBlocks) *>
          (SelectionWork.create[IO](pool.fetchCoordinator.refreshGenesis), chain.get).tupled
      )
      .flatMap((work, current) => follower.blockSelection(pool, work, fatal, current.origin))

  /** A scripted peer as its session sees it, delivering through a pool session's claims. */
  final class Fed private[WorkerTesting] (
      val peer: Peer[IO],
      candidate: Ref[IO, Vector[Header]],
      claims: BlockPool.Claims[IO]
  ):
    /** The end the session is due by what the worker already decided: a refusal that blamed the
      * peer, or an invalid block in its candidate before the tip.
      */
    def due: IO[Option[ValidationError]] =
      claims.blamedYet.flatMap(
        _.fold(
          (candidate.get, claims.invalidBlocks.snapshot).mapN((headers, known) =>
            Fed.rejected(headers, known.entries)
          )
        )(refusal => IO.pure(Some(refusal)))
      )

  object Fed:
    /** The newest invalid block of `headers` but its tip, which diffusion pipelining tolerates. */
    def rejected(
        headers: Vector[Header],
        invalid: Map[xyz.kd5ujc.scarab.codec.Bytes, ValidationError]
    ): Option[ValidationError] =
      headers.dropRight(1).reverseIterator.flatMap(h => invalid.get(h.hash)).nextOption()

  /** `peer` with the pool's registries, delivering the body of each header it announces through
    * `claims` before the session sees the header, checked as `Peer.over` checks a fetched body
    * (`BlockPool.Delivered.checked`). As there, with diffusion pipelining on: a body that does not
    * match its header is the peer's protocol failure; a refusal that punishes the peer ends its
    * session (`pull` races `claims.blamed`); and so does an invalid block in its candidate before
    * the tip (`invalidBlockRejector`, consensus 82ecba329 ChainSync/Client.hs:2082–2156) or as the
    * predecessor of a header it announces (`checkKnownInvalid`, Client.hs:1642–1694).
    */
  def delivering(peer: Peer[IO], claims: BlockPool.Claims[IO]): Resource[IO, Fed] =
    Resource.eval(Ref.of[IO, Vector[Header]](Vector.empty)).map { candidate =>
      def invalid(refusal: Option[ValidationError])(using Raise[IO, PeerError]): IO[Unit] =
        refusal.traverse_(PeerError.InvalidBlock(_).raise[IO, Unit])

      def rejector(using Raise[IO, PeerError]): IO[Nothing] =
        BigInt(-1).tailRecM[IO, Nothing] { revision =>
          claims.invalidBlocks.awaitChange(revision).flatMap { known =>
            candidate.get
              .flatMap(headers => invalid(Fed.rejected(headers, known.entries)))
              .as(Left(known.revision))
          }
        }

      def deliver(header: Header)(using Raise[IO, PeerError]): IO[Unit] =
        peer.fetch(header).flatMap { raw =>
          BlockPool.Delivered
            .checked(header, raw)
            .fold(
              problem => PeerError.Protocol(problem).raise[IO, Unit],
              delivered =>
                claims.claim(Vector(header.hash)) *> claims.fillDelivered(Vector(delivered))
            )
        }

      val fed = new Peer[IO]:
        override def invalidBlocks = Some(claims.invalidBlocks)
        override def noVerdictBlocks = Some(claims.noVerdictBlocks)
        def intersect(candidates: Vector[ChainSync.Point])(using Raise[IO, PeerError]) =
          candidate.set(Vector.empty) *> peer.intersect(candidates)
        def next(using Raise[IO, PeerError]) =
          IO.race(IO.race(claims.blamed, rejector), peer.next).flatMap {
            case Left(Left(refusal)) => PeerError.InvalidBlock(refusal).raise[IO, SyncEvent]
            case Left(Right(never))  => never
            case Right(event @ SyncEvent.RollForward(header, _)) =>
              header.prevHash.flatTraverse(claims.invalidBlocks.lookup).flatMap(invalid) *>
                candidate.update(_ :+ header) *> deliver(header).as(event)
            case Right(event @ SyncEvent.RollBackward(point, _)) =>
              candidate
                .update(headers => headers.take(headers.indexWhere(_.point == point) + 1))
                .as(event)
          }
        def fetch(header: Header)(using Raise[IO, PeerError]) = peer.fetch(header)
      new Fed(fed, candidate, claims)
    }

  /** `follower` 's session over the scripted `peer` with the worker installed over `blocks`, until
    * `done` and the worker has decided every block the peer delivered (the session still running
    * then, `None`), or until the worker tells the node to stop (that fault). The session's own end
    * is raised as it is, once the worker, which outlives every session, has decided what the
    * session delivered before it ended; an end the worker's decisions make due is waited for.
    */
  def run[S](follower: Follower[IO, S], blocks: BlockPool[IO], peer: Peer[IO], done: IO[Unit])(using
      Stateful[IO, VolatileChain[S]],
      Raise[IO, NodeError]
  ): IO[Option[Fatal]] =
    Deferred[IO, Fatal].flatMap { stopped =>
      selection(follower, blocks, stopped.complete(_).void).surround {
        val following = (blocks.session >>= (delivering(peer, _)))
          .use(fed =>
            IO.race(
              follower.session(fed.peer),
              done *> settled(blocks) *> fed.due.flatMap(_.fold(IO.unit)(_ => IO.never))
            )
          )
          .guarantee(IO.race(stopped.get, settled(blocks)).void)
        IO.race(stopped.get, following).map(_.left.toOption)
      }
    }

  /** As [[run]], for a node that must not be told to stop. */
  def follow[S](follower: Follower[IO, S], blocks: BlockPool[IO], peer: Peer[IO], done: IO[Unit])(
      using
      Stateful[IO, VolatileChain[S]],
      Raise[IO, NodeError]
  ): IO[Unit] =
    run(follower, blocks, peer, done).flatMap(
      _.traverse_(fault => IO(fail(s"the node was told to stop: $fault")))
    )

  /** Until the worker has decided every block delivered before: its own reprocessing, queued behind
    * them, has run (`BlockAdmissions.reprocess`, first in, first out).
    */
  def settled(pool: BlockPool[IO]): IO[Unit] =
    pool.admissions.reprocess.timeout(30.seconds)
