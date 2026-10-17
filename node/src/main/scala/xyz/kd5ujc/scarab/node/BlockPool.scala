// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes

/** The blocks that peer sessions fetched ahead of the rules, by header hash, shared by the sessions
  * of all peers. A session claims the blocks of a range before it requests them, so a block is
  * fetched from one peer only (the reference's `filterNotAlreadyInFlightWithOtherPeers` in bulk
  * sync, ouroboros-network `Ouroboros.Network.BlockFetch.Decision`), and the session that applies a
  * block takes it whichever peer it came from, waiting if that peer has not delivered it yet.
  *
  * A claim that is not filled (the connection failed, the peer lacks the block) is released, and a
  * session waiting for it fetches the block itself. Blocks that nobody takes (a candidate that was
  * not preferred) are dropped, oldest first, past [[BlockPool.Retained]] entries.
  *
  * Claims are made through a [[BlockPool.Claims]] that belongs to one session ([[session]]). It is a
  * resource: when the session ends, however it ends (a failure, a cancellation), every claim it
  * still holds is released, so a claim cannot outlive the connection that was to fill it. Without
  * that a session waiting for such a claim waits for ever (issue 130).
  */
final class BlockPool[F[_]: Async] private (slots: Ref[F, BlockPool.Slots[F]]):
  import BlockPool.*
  private val F = Async[F]

  /** The claims of one session, released when the resource is. */
  def session: Resource[F, Claims[F]] =
    Resource
      .make(slots.modify(s => (s.copy(owners = s.owners + 1), s.owners)))(releaseAll)
      .map(new Claims(this, _))

  /** Claim the hashes no session holds; the result says, per hash and in order, whether `owner` now
    * holds it (and must [[fill]] or [[release]] it).
    */
  private def claim(owner: Long, hashes: Vector[Bytes]): F[Vector[Boolean]] =
    hashes.traverse(_ => Deferred[F, Option[Bytes]]).flatMap { fresh =>
      slots.modify { s =>
        val (grown, owned) = hashes.zip(fresh).foldLeft((s, Vector.empty[Boolean])) {
          case ((acc, flags), (hash, done)) =>
            if acc.entries.contains(hash) then (acc, flags :+ false)
            else
              (
                acc.copy(entries = acc.entries.updated(hash, Slot.Pending(done, acc.next, owner)), next = acc.next + 1),
                flags :+ true
              )
        }
        (grown.trimmed, owned)
      }
    }

  /** The blocks `owner` claimed arrived. */
  private def fill(owner: Long, blocks: Vector[(Bytes, Bytes)]): F[Unit] =
    slots
      .modify { s =>
        blocks.foldLeft((s, Vector.empty[(Deferred[F, Option[Bytes]], Bytes)])) {
          case ((acc, waiting), (hash, block)) =>
            acc.entries.get(hash) match
              case Some(Slot.Pending(done, seq, o)) if o == owner =>
                (
                  acc.copy(entries = acc.entries.updated(hash, Slot.Done(block, seq))),
                  waiting :+ (done -> block)
                )
              case _ => (acc, waiting)
        }
      }
      .flatMap(_.traverse_((done, block) => done.complete(Some(block))))

  /** Give up the claims of `owner` that are still open (those already filled stay). */
  private def release(owner: Long, hashes: Vector[Bytes]): F[Unit] =
    slots
      .modify { s =>
        hashes.foldLeft((s, Vector.empty[Deferred[F, Option[Bytes]]])) { case ((acc, open), hash) =>
          acc.entries.get(hash) match
            case Some(Slot.Pending(done, _, o)) if o == owner =>
              (acc.copy(entries = acc.entries - hash), open :+ done)
            case _ => (acc, open)
        }
      }
      .flatMap(_.traverse_(_.complete(None)))

  /** Give up every claim `owner` still holds. */
  private def releaseAll(owner: Long): F[Unit] =
    slots
      .modify { s =>
        val open = s.entries.filter {
          case (_, Slot.Pending(_, _, o)) => o == owner
          case _                          => false
        }
        (
          s.copy(entries = s.entries -- open.keys),
          open.valuesIterator.collect { case Slot.Pending(done, _, _) => done }.toVector
        )
      }
      .flatMap(_.traverse_(_.complete(None)))

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
  /** What one session may do with the pool: claim blocks to fetch, fill what it fetched, release
    * what it could not. Obtained from [[BlockPool.session]].
    */
  final class Claims[F[_]] private[BlockPool] (pool: BlockPool[F], owner: Long):
    def claim(hashes: Vector[Bytes]): F[Vector[Boolean]] = pool.claim(owner, hashes)
    def fill(blocks: Vector[(Bytes, Bytes)]): F[Unit] = pool.fill(owner, blocks)
    def release(hashes: Vector[Bytes]): F[Unit] = pool.release(owner, hashes)

  /** Entries kept before the oldest finished ones are dropped. */
  val Retained = 8192

  def create[F[_]: Async]: F[BlockPool[F]] =
    Ref.of[F, Slots[F]](Slots(Map.empty, 0L, 0L)).map(new BlockPool(_))

  private enum Slot[F[_]]:
    case Pending(done: Deferred[F, Option[Bytes]], seq: Long, owner: Long)
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
