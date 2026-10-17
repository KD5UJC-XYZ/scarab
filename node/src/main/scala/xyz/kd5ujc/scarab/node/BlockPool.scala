// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.selection.FetchMode

/** The blocks that peer sessions fetched ahead of the rules, by header hash, shared by the sessions
  * of all peers. A session claims the blocks of a range before it requests them, so a block is
  * fetched from one peer only (the reference's `filterNotAlreadyInFlightWithOtherPeers` in bulk
  * sync, ouroboros-network `Ouroboros.Network.BlockFetch.Decision`), and the session that applies a
  * block takes it whichever peer it came from, waiting if that peer has not delivered it yet.
  *
  * The fetch mode decides whether a block that another session holds a claim on can be claimed too.
  * In bulk sync it cannot. In deadline mode the reference does not filter the blocks in flight with
  * other peers (`Decision.hs` 659-662 of ouroboros-network main, 1.3.0.0), and bounds the number of
  * peers fetching at once by `maxConcurrencyDeadline` (`fetchRequestDecision`, 1100-1120); here the
  * same block can be claimed by up to [[BlockPool.FetchPolicy.deadlineConcurrency]] sessions, and
  * the first to deliver it wins (a later delivery is dropped).
  *
  * A claim that is not filled (the connection failed, the peer lacks the block) is released, and a
  * session waiting for it fetches the block itself. Blocks that nobody takes (a candidate that was
  * not preferred) are dropped, oldest first, past [[BlockPool.Retained]] entries.
  *
  * Claims are made through a [[BlockPool.Claims]] that belongs to one session ([[session]]). It is
  * a resource: when the session ends, however it ends (a failure, a cancellation), every claim it
  * still holds is released, so a claim cannot outlive the connection that was to fill it. Without
  * that a session waiting for such a claim waits for ever (issue 130).
  */
final class BlockPool[F[_]: Async] private (
    slots: Ref[F, BlockPool.Slots[F]],
    policy: BlockPool.FetchPolicy
):
  import BlockPool.*
  private val F = Async[F]

  /** The claims of one session, released when the resource is. */
  def session: Resource[F, Claims[F]] =
    Resource
      .make(slots.modify(s => (s.copy(owners = s.owners + 1), s.owners)))(releaseAll)
      .map(new Claims(this, _))

  /** Claim the hashes no session holds (in deadline mode also those held by fewer sessions than the
    * policy allows); the result says, per hash and in order, whether `owner` now holds it (and must
    * [[fill]] or [[release]] it).
    */
  private def claim(owner: Long, hashes: Vector[Bytes], mode: FetchMode): F[Vector[Boolean]] =
    hashes.traverse(_ => Deferred[F, Option[Bytes]]).flatMap { fresh =>
      slots.modify { s =>
        val (grown, owned) = hashes.zip(fresh).foldLeft((s, Vector.empty[Boolean])) {
          case ((acc, flags), (hash, done)) =>
            acc.entries.get(hash) match
              case None =>
                (
                  acc.copy(
                    entries = acc.entries.updated(hash, Slot.Pending(done, acc.next, Set(owner))),
                    next = acc.next + 1
                  ),
                  flags :+ true
                )
              case Some(Slot.Pending(waiting, seq, owners))
                  if mode == FetchMode.Deadline && !owners(owner) &&
                    owners.size < policy.deadlineConcurrency =>
                (
                  acc.copy(entries =
                    acc.entries.updated(hash, Slot.Pending(waiting, seq, owners + owner))
                  ),
                  flags :+ true
                )
              case Some(_) => (acc, flags :+ false)
        }
        (grown.trimmed, owned)
      }
    }

  /** The blocks `owner` claimed arrived: the first delivery of a block wins. */
  private def fill(owner: Long, blocks: Vector[(Bytes, Bytes)]): F[Unit] =
    slots
      .modify { s =>
        blocks.foldLeft((s, Vector.empty[(Deferred[F, Option[Bytes]], Bytes)])) {
          case ((acc, waiting), (hash, block)) =>
            acc.entries.get(hash) match
              case Some(Slot.Pending(done, seq, owners)) if owners(owner) =>
                (
                  acc.copy(entries = acc.entries.updated(hash, Slot.Done(block, seq))),
                  waiting :+ (done -> block)
                )
              case _ => (acc, waiting)
        }
      }
      .flatMap(_.traverse_((done, block) => done.complete(Some(block))))

  /** Give up the claims of `owner` that are still open (those already filled stay). A block that
    * another session also claimed stays pending for it; one nobody holds any more is released to
    * whoever waits for it.
    */
  private def release(owner: Long, hashes: Vector[Bytes]): F[Unit] =
    slots.modify(release1(_, owner, hashes)).flatMap(_.traverse_(_.complete(None)))

  /** Give up every claim `owner` still holds. */
  private def releaseAll(owner: Long): F[Unit] =
    slots
      .modify { s =>
        val mine = s.entries.collect {
          case (hash, Slot.Pending(_, _, owners)) if owners(owner) => hash
        }
        release1(s, owner, mine.toVector)
      }
      .flatMap(_.traverse_(_.complete(None)))

  /** The state without `owner` on the claims `hashes`, and the waits nobody can fill any more. */
  private def release1(
      s: Slots[F],
      owner: Long,
      hashes: Vector[Bytes]
  ): (Slots[F], Vector[Deferred[F, Option[Bytes]]]) =
    hashes.foldLeft((s, Vector.empty[Deferred[F, Option[Bytes]]])) { case ((acc, open), hash) =>
      acc.entries.get(hash) match
        case Some(Slot.Pending(done, seq, owners)) if owners(owner) =>
          if owners.size > 1 then
            (
              acc.copy(entries =
                acc.entries.updated(hash, Slot.Pending(done, seq, owners - owner))
              ),
              open
            )
          else (acc.copy(entries = acc.entries - hash), open :+ done)
        case _ => (acc, open)
    }

  /** The block, once some session has fetched it; none when no session holds a claim on it (or the
    * claim was released), and the caller fetches it itself.
    */
  def take(hash: Bytes): F[Option[Bytes]] =
    slots.modify { s =>
      s.entries.get(hash) match
        case Some(Slot.Done(block, _)) =>
          (s.copy(entries = s.entries - hash), F.pure(Option(block)))
        case Some(Slot.Pending(done, _, _)) =>
          (s, done.get.flatTap(_ => slots.update(a => a.copy(entries = a.entries - hash))))
        case None => (s, F.pure(Option.empty[Bytes]))
    }.flatten

  /** Entries held (tests). */
  def size: F[Int] = slots.get.map(_.entries.size)

object BlockPool:
  /** What the fetch mode changes about who may fetch what. */
  final case class FetchPolicy(
      /** How many sessions may hold a claim on the same block in deadline mode
        * (`bfcMaxConcurrencyDeadline`): the default of cardano-node is 1, which makes deadline mode
        * fetch each block from one peer, as bulk sync does (`Cardano/Network/Diffusion/
        * Configuration.hs` 89-100 of ouroboros-network main). The `MaxConcurrencyDeadline` key of
        * the configuration file sets it.
        */
      deadlineConcurrency: Int = 1
  )

  /** What one session may do with the pool: claim blocks to fetch, fill what it fetched, release
    * what it could not. Obtained from [[BlockPool.session]].
    */
  final class Claims[F[_]] private[BlockPool] (pool: BlockPool[F], owner: Long):
    /** Claim `hashes` for this session under the fetch `mode` (bulk sync, the exclusive claim,
      * unless a caller says otherwise).
      */
    def claim(hashes: Vector[Bytes], mode: FetchMode = FetchMode.BulkSync): F[Vector[Boolean]] =
      pool.claim(owner, hashes, mode)
    def fill(blocks: Vector[(Bytes, Bytes)]): F[Unit] = pool.fill(owner, blocks)
    def release(hashes: Vector[Bytes]): F[Unit] = pool.release(owner, hashes)

  /** Entries kept before the oldest finished ones are dropped. */
  val Retained = 8192

  def create[F[_]: Async]: F[BlockPool[F]] = create(FetchPolicy())

  def create[F[_]: Async](policy: FetchPolicy): F[BlockPool[F]] =
    Ref.of[F, Slots[F]](Slots(Map.empty, 0L, 0L)).map(new BlockPool(_, policy))

  private enum Slot[F[_]]:
    case Pending(done: Deferred[F, Option[Bytes]], seq: Long, owners: Set[Long])
    case Done(block: Bytes, seq: Long)

  private final case class Slots[F[_]](entries: Map[Bytes, Slot[F]], next: Long, owners: Long):
    /** Without the finished entries older than the newest `Retained` claims, once the pool holds
      * twice as many (a walk of the map, so not on every claim).
      */
    def trimmed: Slots[F] =
      if entries.size <= 2 * Retained then this
      else
        val floor = next - Retained
        copy(entries = entries.filter {
          case (_, Slot.Done(_, seq)) => seq >= floor
          case _                      => true
        })
