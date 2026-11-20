// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.relay

import cats.Monad
import cats.data.NonEmptyVector
import cats.syntax.all.*
import fs2.{Pull, Stream}
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.network.BlockFetchServer
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}
import xyz.kd5ujc.scarab.protocol.NetworkError
import xyz.kd5ujc.scarab.storage.ImmutableStore

/** A BlockFetch range over the blocks the node holds: the selected chain, the blocks of other forks
  * it holds beside it, and the immutable chain. The reference's ChainDB iterator, which the
  * BlockFetch server streams from (ouroboros-consensus 82ecba32, `Storage/ChainDB/Impl/Iterator.hs`
  * and `Storage/ChainDB/Impl/Paths.hs`, the same at 808e40054; `MiniProtocol/BlockFetch/Server.hs`
  * 114-146): a range whose upper bound is newer than the immutable tip is a path walked back from
  * it through the held blocks, which need not be on the selected chain, joined to the immutable
  * chain only at its tip; a range no newer than the tip is the immutable chain's. The blocks are
  * read one at a time as the batch is sent, and a held block that was collected meanwhile is looked
  * for in the immutable chain, its point checked against the path.
  *
  * Memory: the path's references for a request, never its bodies.
  */
private[relay] object HeldRange:
  /** A held block and the block before it, none for the first block of the chain. */
  final case class Held(ref: BlockRef, predecessor: Option[Bytes])

  /** A lower bound of a stream from the immutable chain (`StreamFrom`). */
  enum Lower:
    def point: Point.Block

    case Inclusive(point: Point.Block)
    case Exclusive(point: Point.Block)

  /** The path back from an upper bound through the held blocks (`Path`, `Paths.hs` 220-255). */
  enum Path:
    /** The upper bound is not held (`NotInVolatileDB`). */
    case NotHeld

    /** The held blocks from the lower bound to the upper, oldest first (`CompletelyInVolatileDB`).
      */
    case Complete(points: NonEmptyVector[BlockRef])

    /** The held blocks back from the upper bound, oldest first, until one whose predecessor
      * `predecessor` is not held (`PartiallyInVolatileDB`).
      */
    case Partial(predecessor: Bytes, points: NonEmptyVector[BlockRef])

  /** What a range reads: the immutable chain and the held blocks' bodies. */
  trait Source[F[_]]:
    /** The immutable chain's newest block, none while it is empty. */
    def tip: F[Option[BlockRef]]

    /** The immutable block at `point`, found in the index, none if it is not stored. */
    def refOf(point: Point.Block): F[Option[BlockRef]]

    /** The stored blocks from `from`, stored as `first`, through `last`, each read when it is
      * pulled.
      */
    def stored(from: Lower, first: BlockRef, last: BlockRef): Stream[F, ImmutableStore.Stored]

    /** The original of the held block `hash`, none once it is no longer held. */
    def body(hash: Bytes): F[Option[Bytes]]

  /** Whether `from` to `to` are bounds of a range at all, by their slots: the reference's
    * `validBounds` (`Storage/Common.hs` 126-137), with no Origin bound.
    */
  def validBounds(from: Lower, to: Point.Block): Boolean = from match
    case Lower.Inclusive(p) => p.slot.value <= to.slot.value
    case Lower.Exclusive(p) => p.hash != to.hash && p.slot.value <= to.slot.value

  /** The path from `from` to `to`, `held` giving each held block by its hash: the reference's
    * `computePath` for an inclusive lower bound (`Paths.hs` 148-218, `computeReversePath` 311-342).
    * The upper bound is found by its hash, the lower by its point. None when the walk back reaches
    * the start of the chain without meeting the lower bound: the range is invalid (`Nothing`, which
    * the iterator raises as `InvalidIteratorRange`, `Iterator.hs` 494-504).
    */
  def path(held: Bytes => Option[Held], from: Point.Block, to: Point.Block): Option[Path] =
    held(to.hash).fold(Some(Path.NotHeld))(end =>
      walk(held, from, end, Vector.empty, Set(end.ref.hash))
    )

  /** The walk follows each held block's predecessor by its hash whatever its slot, as the
    * reference's index does: a block is held before selection judges it. `seen` are the hashes on
    * the path. A predecessor already on it closes a cycle, which only a collision of block hashes
    * could make: the reference's walk would not end (`computeReversePath` has no such check), and
    * this one ends there, the predecessor taken as one it does not hold.
    */
  @tailrec private def walk(
      held: Bytes => Option[Held],
      from: Point.Block,
      current: Held,
      newer: Vector[BlockRef],
      seen: Set[Bytes]
  ): Option[Path] =
    val points = NonEmptyVector(current.ref, newer)
    if current.ref.point == from then Some(Path.Complete(points))
    else
      current.predecessor match
        case None => None
        case Some(hash) =>
          held(hash).filterNot(_ => seen(hash)) match
            case None           => Some(Path.Partial(hash, points))
            case Some(previous) => walk(held, from, previous, points.toVector, seen + hash)

  /** Where the immutable chain stands for a stream from `from` through `to`: the reference's
    * ImmutableDB `stream` (`Storage/ImmutableDB/Impl/Iterator.hs` 134-159), which raises for bounds
    * that are not a range (`InvalidIteratorRangeError`, by their slots first and, both found, by
    * the order of a boundary block and the block of its slot) and answers `MissingBlock` when an
    * end is not stored, the upper first.
    */
  private enum Immutable[F[_]]:
    case Refused(refusal: NetworkError.RangeRefusal)
    case Missing()
    case Opened(blocks: Stream[F, ImmutableStore.Stored])

  private def immutable[F[_]: Monad](
      source: Source[F],
      from: Lower,
      to: Point.Block
  ): F[Immutable[F]] =
    val invalid =
      Immutable.Refused[F](NetworkError.RangeRefusal.InvalidIteratorRangeError(from.point, to))
    if !validBounds(from, to) then invalid.pure[F]
    else
      source.refOf(to).flatMap {
        case None => Immutable.Missing[F]().pure[F]
        case Some(last) =>
          source.refOf(from.point).map {
            case None                                             => Immutable.Missing[F]()
            case Some(first) if !ChainServer.ordered(first, last) => invalid
            case Some(first) => Immutable.Opened(source.stored(from, first, last))
          }
      }

  /** The answer for the range `from` to `to`: the reference's `newIterator` for inclusive bounds
    * (`Iterator.hs` 254-451). `held` reads the held blocks as they are, after the immutable tip, as
    * the reference reads the VolatileDB's index after the ImmutableDB's tip.
    */
  def answer[F[_]: Monad](
      source: Source[F],
      held: F[Bytes => Option[Held]],
      from: Point.Block,
      to: Point.Block
  ): F[BlockFetchServer.Answer[F]] =
    type Answer = BlockFetchServer.Answer[F]
    val lower = Lower.Inclusive(from)
    val invalid: Answer =
      BlockFetchServer.Answer.Refused(NetworkError.RangeRefusal.InvalidIteratorRange(from, to))
    def opened(state: Immutable[F], next: Option[NonEmptyVector[BlockRef]]): Answer = state match
      case Immutable.Refused(refusal) => BlockFetchServer.Answer.Refused(refusal)
      case Immutable.Missing()        => BlockFetchServer.Answer.NoBlocks()
      case Immutable.Opened(blocks)   => batch(source, State.FromImmutable(lower, blocks, next))
    // the range is the immutable chain's (`streamFromImmutableDB`)
    def fromImmutable(last: Point.Block): F[Answer] =
      immutable(source, lower, last).map(opened(_, None))
    // the path's oldest blocks are the immutable chain's, through its tip (`streamFromBoth`)
    def fromBoth(predecessor: Bytes, points: NonEmptyVector[BlockRef]): F[Answer] =
      source.tip.flatMap {
        case None => BlockFetchServer.Answer.NoBlocks[F]().pure[F]
        case Some(tip) if tip.hash == predecessor =>
          immutable(source, lower, block(tip)).map(opened(_, Some(points)))
        case Some(tip) =>
          // blocks of the path may have reached the immutable chain since it was walked
          NonEmptyVector.fromVector(points.toVector.dropWhile(_.point != tip.point)) match
            case None => BlockFetchServer.Answer.NoBlocks[F]().pure[F]
            case Some(fromTip) =>
              NonEmptyVector
                .fromVector(fromTip.tail)
                .fold(fromImmutable(block(tip)))(rest =>
                  immutable(source, lower, block(tip)).map(opened(_, Some(rest)))
                )
      }
    def fromHeld: F[Answer] = held.flatMap(path(_, from, to) match
      case None                        => invalid.pure[F]
      case Some(Path.NotHeld)          => BlockFetchServer.Answer.NoBlocks[F]().pure[F]
      case Some(Path.Complete(points)) => batch(source, State.FromHeld(lower, points)).pure[F]
      case Some(Path.Partial(predecessor, points)) => fromBoth(predecessor, points))
    if !validBounds(lower, to) then invalid.pure[F]
    else
      source.tip.flatMap {
        case None => fromHeld
        case Some(tip) =>
          val endSlot = to.slot.value
          if endSlot < tip.slot || (endSlot == tip.slot && to.hash == tip.hash) then
            fromImmutable(to)
          // the boundary block of the tip's slot is older than the tip: an upper bound in that slot
          // is the immutable chain's, or a fork too old (cases 1-3, `Iterator.hs` 287-333)
          else if endSlot == tip.slot && !tip.boundary then fromImmutable(to)
          else fromHeld
      }

  private def block(ref: BlockRef): Point.Block =
    Point.Block(UInt64.saturating(ref.slot), ref.hash)

  /** Where the stream of a range stands (`IteratorState`, `Iterator.hs` 522-593), with `from` the
    * lower bound a stream from the immutable chain would continue at.
    */
  private enum State[F[_]]:
    /** Streaming the immutable chain, then the held path `next` if any (`InImmutableDB`). */
    case FromImmutable(
        from: Lower,
        blocks: Stream[F, ImmutableStore.Stored],
        next: Option[NonEmptyVector[BlockRef]]
    )

    /** Reading the held path (`InVolatileDB`). */
    case FromHeld(from: Lower, points: NonEmptyVector[BlockRef])

    /** Reading from the immutable chain the blocks of the path that left the held blocks, each
      * checked against the path (`InImmutableDBRetry`); `from` is none until one is found.
      */
    case Recovering(
        from: Option[Lower],
        blocks: Stream[F, ImmutableStore.Stored],
        points: NonEmptyVector[BlockRef]
    )

  private type Sent = Either[NetworkError.RangeRefusal, Bytes]

  private def batch[F[_]: Monad](source: Source[F], start: State[F]): BlockFetchServer.Answer[F] =
    BlockFetchServer.Answer.Batch(stream(source, start).stream)

  private def refuse[F[_]](refusal: NetworkError.RangeRefusal): Pull[F, Sent, Unit] =
    Pull.output1(Left(refusal))

  /** The blocks of a range from `state` on (`implIteratorNext`, `Iterator.hs` 624-792). */
  private def stream[F[_]: Monad](source: Source[F], state: State[F]): Pull[F, Sent, Unit] =
    state match
      case State.FromImmutable(from, blocks, next) =>
        blocks.pull.uncons1.flatMap {
          case Some((stored, rest)) =>
            val after = Lower.Exclusive(block(stored.ref))
            Pull.output1(Right(stored.raw)) >>
              stream(source, State.FromImmutable(after, rest, next))
          case None => next.fold(Pull.done)(points => stream(source, State.FromHeld(from, points)))
        }
      case State.FromHeld(from, points) =>
        val expected = points.head
        Pull.eval(source.body(expected.hash)).flatMap {
          case Some(raw) =>
            Pull.output1(Right(raw)) >> NonEmptyVector
              .fromVector(points.tail)
              .fold(Pull.done)(rest =>
                stream(source, State.FromHeld(Lower.Exclusive(block(expected)), rest))
              )
          // no longer held: collected, and in the immutable chain if it was on the selected chain
          // (`nextInVolatileDB`, 654-695)
          case None =>
            Pull.eval(source.tip).flatMap {
              case None => refuse(NetworkError.RangeRefusal.BlockGCed(block(expected)))
              case Some(tip) =>
                Pull.eval(immutable(source, from, block(tip))).flatMap {
                  case Immutable.Refused(refusal) => refuse(refusal)
                  case Immutable.Missing() =>
                    refuse(NetworkError.RangeRefusal.BlockGCed(block(expected)))
                  case Immutable.Opened(blocks) =>
                    stream(source, State.Recovering(None, blocks, points))
                }
            }
        }
      case State.Recovering(from, blocks, points) =>
        val expected = points.head
        blocks.pull.uncons1.flatMap {
          case Some((stored, rest)) if stored.ref.point == expected.point =>
            val after = Lower.Exclusive(block(expected))
            Pull.output1(Right(stored.raw)) >> NonEmptyVector
              .fromVector(points.tail)
              .fold(Pull.done)(next => stream(source, State.Recovering(Some(after), rest, next)))
          // another block, or none: before any was found the block is gone; after, the rest of
          // the path may still be held (`nextInImmutableDBRetry`, 740-792)
          case _ =>
            from.fold(refuse(NetworkError.RangeRefusal.BlockGCed(block(expected))))(continue =>
              stream(source, State.FromHeld(continue, points))
            )
        }
