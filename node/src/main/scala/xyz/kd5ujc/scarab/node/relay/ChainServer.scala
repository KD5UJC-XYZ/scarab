// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.relay

import cats.effect.{Async, Ref, Resource}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.SignallingRef
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{Era, NetworkError, NtNHeader}
import xyz.kd5ujc.scarab.protocol.CardanoBlockFetch.RawNtNBlock
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, Tip}
import xyz.kd5ujc.scarab.network.{BlockFetchServer, ChainSyncServer}
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef}
import xyz.kd5ujc.scarab.ledger.{Block, ChainError, Header, StorageError}
import xyz.kd5ujc.scarab.node.{Fatal, StorageBoundary}
import xyz.kd5ujc.scarab.storage.{ChainPersistence, ImmutableStore, ReceivedBlocks}

/** The chain this node serves to its peers: the in-memory volatile chain ([[ServedChain]]) in front
  * of the [[ImmutableStore]]. ChainSync follows it alone: it is fed by the node's own chain updates
  * (it is a [[ChainPersistence]]), never by what a peer announced, so a client only ever sees
  * headers of the chain this node selected. BlockFetch serves the blocks the node holds besides
  * ([[ChainServer.Held]], [[HeldRange]]), as the reference's server streams from its ChainDB, whose
  * VolatileDB holds every block it received or forged until it is collected.
  *
  * Without a store only the volatile chain is served: a client that starts from Origin once the
  * chain is longer than `k` is told there is no intersection, and a client whose next block is no
  * longer volatile is told it is not kept, which ends its connection.
  */
final class ChainServer[F[_]: Async] private (
    state: Ref[F, ServedChain],
    version: SignallingRef[F, Long],
    store: Option[ImmutableStore[F]],
    held: ChainServer.Held[F],
    headerOf: ChainServer.HeaderOf,
    fatal: Fatal => F[Unit]
):
  private val F = Async[F]

  def tip: F[Tip] = state.get.map(_.tip)

  /** The bytes of the volatile block `hash`, which this server holds until the block is immutable:
    * the mempool's watcher reads the blocks the pool has not seen from here.
    */
  def volatileBlock(hash: Bytes): F[Option[Bytes]] = state.get.map(_.raw(hash))

  private[relay] def activeFollowers: F[Int] = state.get.map(_.followers.size)

  /** The node's chain updates, to be composed with the storage persistence. The block must be
    * stored (by the persistence that runs before this one) before it leaves the volatile chain.
    */
  def persistence[S]: ChainPersistence[F, S] =
    new ChainPersistence[F, S]:
      def extended(ref: BlockRef, raw: Bytes, immutable: Vector[BlockRef], anchor: Anchor[S])(using
          Raise[F, StorageError],
          Raise[F, ChainError]
      ) =
        for
          header <- headerOf(raw).fold(_.raise[F, NtNHeader], F.pure)
          _ <- state.update(_.extend(Served(ref, header, raw), immutable.size, anchor.block))
          _ <- version.update(_ + 1)
        yield ()

      def rolledBack(to: Point)(using Raise[F, StorageError], Raise[F, ChainError]) =
        state
          .modify { chain =>
            chain.rollback(to).fold((chain, false))(c => (c, true))
          }
          .flatMap(changed => version.update(_ + 1).whenA(changed))

  /** Whether `point` is on our chain, and if it is, follower `id` moved to roll back to it: the
    * reference's `checkIfPointOnChain` (ouroboros-consensus 82ecba32,
    * `Storage/ChainDB/Impl/Follower.hs` 457-525). Origin, the anchor and a volatile point are
    * checked against the chain as it is and taken in one update, so a fork switch that drops the
    * point cannot come between the check and the move (465-472). A point older than the volatile
    * chain is looked up in the store's index outside the update, the block not read, as the
    * reference opens an ImmutableDB iterator at the point outside the transaction (510-522), which
    * finds it in the chunk's indexes and reads no block (`streamAfterPoint`, `ImmutableDB/API.hs`
    * 542-576; `checkLowerBound` and `getSlotInfo`, `ImmutableDB/Impl/Iterator.hs` 226-243 and
    * 281-352); the store never drops a block, so the follower moved after the lookup stands on our
    * chain however the volatile chain changed meanwhile.
    */
  private def checkIfPointOnChain(id: Long, point: Point)(using
      Raise[F, StorageError]
  ): F[Boolean] =
    state.modify(ChainServer.standing(id, point, store)).flatMap {
      case ChainServer.Standing.Taken  => F.pure(true)
      case ChainServer.Standing.Absent => F.pure(false)
      case ChainServer.Standing.Older(older, stored) =>
        stored.refOf(older).flatMap {
          case Some(_) => state.update(_.move(id, Cursor.RollbackTo(older))).as(true)
          case None    => F.pure(false)
        }
    }

  /** One cursor per connected node-to-node ChainSync client: headers. */
  def source: Resource[F, ChainSyncServer.Source[F, NtNHeader]] =
    cursor(s => Some(s.header), raw => headerOf(raw).toOption, fatal)

  /** One cursor per connected node-to-client ChainSync client: whole blocks, as stored. */
  def blockSource: Resource[F, ChainSyncServer.Source[F, RawNtNBlock]] = blockSource(fatal)

  /** The same, with a storage failure under the cursor given to `failed` instead of the node's
    * fatal sink (a node-to-client connection ends alone).
    */
  def blockSource(failed: Fatal => F[Unit]): Resource[F, ChainSyncServer.Source[F, RawNtNBlock]] =
    cursor(
      served => RawNtNBlock.from(served.raw).toOption,
      raw => RawNtNBlock.from(raw).toOption,
      failed
    )

  /** One cursor per connected ChainSync client, closed with the connection. `ofServed` and
    * `ofStored` give what the client is sent for a volatile and an immutable block (a block that
    * cannot be sent is skipped as if absent, which cannot happen for a validated chain).
    */
  private def cursor[P](
      ofServed: Served => Option[P],
      ofStored: Bytes => Option[P],
      fatal: Fatal => F[Unit]
  ): Resource[F, ChainSyncServer.Source[F, P]] =
    Resource
      .make(state.modify(_.open()))(id => state.update(_.close(id)))
      .map { id =>
        new ChainSyncServer.Source[F, P]:
          def intersect(points: Vector[Point]) = StorageBoundary.run(fatal) {
            // as the reference's `handleFindIntersect` (`MiniProtocol/ChainSync/Server.hs`
            // 157-166): the first offered point on our chain moves the follower, whose first
            // instruction is then a roll-back to it, and the tip is read after the move
            points
              .findM(checkIfPointOnChain(id, _))
              .flatMap(found => tip.map(t => found.map(_ -> t).toRight(t)))
          }

          def poll: F[Option[ChainSyncServer.Update[P]]] = StorageBoundary.run(fatal) {
            state
              .modify { chain =>
                chain.followers.get(id).map(chain.read) match
                  case Some(Read.Forward(b)) =>
                    chain.move(id, Cursor.At(b.ref.point)) -> Read.Forward(b)
                  case Some(Read.Backward(p)) =>
                    chain.move(id, Cursor.At(p)) -> Read.Backward(p)
                  case Some(other) => chain -> other
                  case None        => chain -> Read.AtTip
              }
              .flatMap {
                case Read.Forward(b) =>
                  tip.map(t => ofServed(b).map(ChainSyncServer.Update.RollForward(_, t)))
                case Read.Backward(p) =>
                  tip.map(t => Some(ChainSyncServer.Update.RollBackward(p, t)))
                case Read.AtTip     => F.pure(None)
                case Read.Behind(p) => immutableNext(id, p, ofStored)
              }

          }

          def await: F[ChainSyncServer.Update[P]] =
            version.get.flatMap { seen =>
              poll.flatMap {
                case Some(update) => F.pure(update)
                case None         => version.waitUntil(_ > seen) *> await
              }
            }
      }

  /** The block after `point` in the immutable store, for a client that has not caught up. Without a
    * store that block is not kept, and the client is told so rather than left waiting (#924).
    */
  private def immutableNext[P](
      id: Long,
      point: Point,
      ofStored: Bytes => Option[P]
  )(using Raise[F, StorageError]): F[Option[ChainSyncServer.Update[P]]] =
    store match
      case None => F.pure(Some(ChainSyncServer.Update.NotKept(point)))
      case Some(s) =>
        s.stream(point).take(1).compile.last.flatMap {
          case None => F.pure(None)
          case Some(stored) =>
            for
              chain <- state.get
              _ <- state.update(_.move(id, Cursor.At(stored.ref.point)))
            yield ofStored(stored.raw).map(ChainSyncServer.Update.RollForward(_, chain.tip))
        }

  /** Blocks for BlockFetch, as the reference's server streams them from its ChainDB
    * ([[HeldRange]]): a range on any fork the node holds, the selected chain's or another's, joined
    * to the immutable chain where it reaches it. The length of a range is not bounded, as in the
    * reference's `blockFetchServer'` (ouroboros-consensus 82ecba32,
    * `MiniProtocol/BlockFetch/Server.hs` 87-146). Its ends are found in the held blocks or the
    * store's index, no block read, as the reference's iterator checks its bounds in the
    * VolatileDB's index and the ImmutableDB's (`checkUpperBound` and `checkLowerBound`,
    * `ImmutableDB/Impl/Iterator.hs` 141-148 and 203-243); each block is read when it is sent.
    */
  def blocks: BlockFetchServer.Blocks[F] = (from, to) =>
    (from, to) match
      case (first: Point.Block, last: Point.Block) =>
        HeldRange.answer(reads, heldNow, first, last)
      // the server refuses an Origin end before it asks (`BlockFetchServer.run`)
      case _ =>
        F.pure(BlockFetchServer.Answer.Refused(NetworkError.RangeRefusal.NoGenesisBlock))

  /** The blocks held at one instant, by hash: the selected volatile chain's, each after the block
    * before it, and the received log's, each after the predecessor its header names, a Byron
    * chain's first block after Origin. The reference walks one snapshot of its VolatileDB's index
    * (`computePathVolatileDB`, `Storage/ChainDB/Impl/Iterator.hs` 494-504): the volatile chain is
    * read on both sides of the read of the received log's index and the reads are taken again until
    * its blocks did not change between them, so the two are as they were when the index was read.
    */
  private def heldNow: F[Bytes => Option[HeldRange.Held]] =
    def selected(chain: ServedChain)(hash: Bytes) =
      chain.predecessorOf(hash).map(HeldRange.Held(_, _))
    held.received.fold(state.get.map(selected)) { received =>
      F.tailRecM(()) { _ =>
        (state.get, received.links, state.get).mapN { (before, links, after) =>
          def logged(hash: Bytes) = links(hash).map { linked =>
            HeldRange.Held(
              linked.ref,
              linked.predecessor match
                case ReceivedBlocks.Predecessor.Genesis => None
                case ReceivedBlocks.Predecessor.Block(previous) =>
                  Option.unless(held.origin.contains(previous))(previous)
            )
          }
          Either.cond(
            before.generation == after.generation,
            (hash: Bytes) => selected(before)(hash).orElse(logged(hash)),
            ()
          )
        }
      }
    }

  /** What a range reads: the store, and the held blocks' originals, the volatile chain's from
    * memory and the received log's from its cache or file (bypassing the blocks held invalid, as
    * the reference's VolatileDB reads a block without consulting the invalid-block set). Without a
    * store the immutable chain ends at the volatile chain's anchor and no block of it is read.
    */
  private val reads: HeldRange.Source[F] = new HeldRange.Source[F]:
    def tip: F[Option[BlockRef]] =
      store.fold(state.get.map(_.anchor))(s => StorageBoundary.run(fatal)(s.tip))

    def refOf(point: Point.Block): F[Option[BlockRef]] =
      store.flatTraverse(s => StorageBoundary.run(fatal)(s.refOf(point)))

    def stored(
        from: HeldRange.Lower,
        first: BlockRef,
        last: BlockRef
    ): Stream[F, ImmutableStore.Stored] =
      store.fold(Stream.empty.covaryAll[F, ImmutableStore.Stored])(s =>
        from match
          case HeldRange.Lower.Inclusive(_) =>
            ChainServer.this.stored(s, first, last)
          case HeldRange.Lower.Exclusive(_) => after(s, first, last)
      )

    def body(hash: Bytes): F[Option[Bytes]] =
      state.get.map(_.raw(hash)).flatMap {
        case Some(raw) => F.pure(Some(raw))
        case None =>
          held.received
            .flatTraverse(r => StorageBoundary.run(fatal)(r.original(hash).map(_.map(_._2))))
      }

  /** The stored blocks from `first` to `last`, each read when it is pulled. `last` is stored and in
    * order after `first`, so the stream reaches it, and ends there: no block after it is read, as
    * the reference's iterator takes the index entries up to its upper bound's hash and no further
    * (`iteratorStateForChunk`, `ImmutableDB/Impl/Iterator.hs` 578-585).
    */
  private def stored(
      s: ImmutableStore[F],
      first: BlockRef,
      last: BlockRef
  ): Stream[F, ImmutableStore.Stored] =
    val head = Stream
      .eval(StorageBoundary.run(fatal)(s.get(first.point)))
      .unNone
      .map(ImmutableStore.Stored(first, _))
    if first.point == last.point then head else head ++ after(s, first, last)

  /** The stored blocks after `first` through `last`, as [[stored]]. */
  private def after(
      s: ImmutableStore[F],
      first: BlockRef,
      last: BlockRef
  ): Stream[F, ImmutableStore.Stored] =
    StorageBoundary.stream(fatal)(s.stream(first.point).takeThrough(_.ref.point != last.point))

object ChainServer:
  /** Header of a stored block, as ChainSync carries it. */
  type HeaderOf = Bytes => Either[ChainError, NtNHeader]

  /** Where a point offered for an intersection stands on the served chain as it is now. */
  private enum Standing[+S]:
    /** On the chain: the follower was moved to roll back to it. */
    case Taken

    /** Not on the chain. */
    case Absent

    /** Older than the volatile chain: on the chain if `store` holds it. */
    case Older(point: Point.Block, store: S)

  /** The blocks BlockFetch serves beside the selected chain: the received log, which holds the
    * blocks the node received or forged, held invalid too, until their file is collected (the
    * reference's VolatileDB); and `origin`, the hash a Byron chain's first block names as its
    * predecessor (the genesis data's), which stands for Origin.
    */
  final case class Held[F[_]](received: Option[ReceivedBlocks[F]], origin: Option[Bytes])

  object Held:
    /** The selected chain alone. */
    def none[F[_]]: Held[F] = Held(None, None)

  /** Whether a range from `from` to `to`, two stored blocks, is in order: by slot, and within one
    * slot the boundary block before the main block, as the reference's ImmutableDB orders a
    * boundary block and the block of its slot (by their chunk slots, `ImmutableDB/Impl/Iterator.hs`
    * 151-159). A block is in order with itself. A Byron boundary block repeats the number of the
    * block before it, so the block numbers cannot order a range.
    */
  private[relay] def ordered(from: BlockRef, to: BlockRef): Boolean =
    from.slot < to.slot || (from.slot == to.slot && (from.boundary || !to.boundary))

  /** The chain with follower `id` moved to roll back to `point` if `point` is volatile, the anchor,
    * or Origin while the whole chain is volatile or a store serves it from the start (the
    * reference's Genesis is always in its ImmutableDB, `Follower.hs` 506-509); otherwise the chain
    * unchanged.
    */
  private def standing[S](id: Long, point: Point, store: Option[S])(
      chain: ServedChain
  ): (ServedChain, Standing[S]) =
    (point, store) match
      case _ if chain.containsVolatile(point) =>
        (chain.move(id, Cursor.RollbackTo(point)), Standing.Taken)
      case (Point.Origin, Some(_)) => (chain.move(id, Cursor.RollbackTo(point)), Standing.Taken)
      case (block: Point.Block, Some(s)) => (chain, Standing.Older(block, s))
      case _                             => (chain, Standing.Absent)

  /** The header of a whole block, its body's contents not decoded ([[Block.decodeHeader]]). For
    * Byron the size hint is the block's byte size.
    */
  def headerOfBlock(byronEpochSlots: BigInt): HeaderOf =
    raw =>
      Block.decodeHeader(raw, byronEpochSlots).map {
        case h: Header.Byron =>
          NtNHeader(Era.Byron, h.raw, Some(NtNHeader.ByronInfo(h.boundary, h.blockSizeHint.value)))
        case h => NtNHeader(h.era, h.raw, None)
      }

  def open[F[_]: Async](
      store: Option[ImmutableStore[F]],
      anchor: Option[BlockRef],
      headerOf: HeaderOf,
      fatal: Fatal => F[Unit],
      held: Held[F]
  ): F[ChainServer[F]] =
    for
      state <- Ref.of[F, ServedChain](ServedChain.empty(anchor))
      version <- SignallingRef.of[F, Long](0L)
    yield new ChainServer[F](state, version, store, held, headerOf, fatal)
