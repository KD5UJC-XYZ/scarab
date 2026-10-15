// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.relay

import cats.effect.{Async, Ref, Resource}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.SignallingRef
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{Era, NtNHeader}
import xyz.kd5ujc.scarab.protocol.CardanoBlockFetch.RawNtNBlock
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, Tip, UInt64}
import xyz.kd5ujc.scarab.network.{BlockFetchServer, ChainSyncServer}
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef}
import xyz.kd5ujc.scarab.ledger.{Block, ChainError, Header}
import xyz.kd5ujc.scarab.storage.{ChainPersistence, ImmutableStore}

/** The chain this node serves to its peers: the in-memory volatile chain ([[ServedChain]]) in front
  * of the [[ImmutableStore]]. It is fed by the node's own chain updates (it is a
  * [[ChainPersistence]]), never by what a peer announced, so a client only ever sees blocks this
  * node has validated.
  *
  * Without a store only the volatile chain is served, and a client that starts from Origin once the
  * chain is longer than `k` is told there is no intersection.
  */
final class ChainServer[F[_]: Async] private (
    state: Ref[F, ServedChain],
    version: SignallingRef[F, Long],
    store: Option[ImmutableStore[F]],
    headerOf: ChainServer.HeaderOf
):
  private val F = Async[F]

  def tip: F[Tip] = state.get.map(_.tip)

  /** The node's chain updates, to be composed with the storage persistence. The block must be
    * stored (by the persistence that runs before this one) before it leaves the volatile chain.
    */
  def persistence[S](using Raise[F, ChainError]): ChainPersistence[F, S] =
    new ChainPersistence[F, S]:
      def extended(ref: BlockRef, raw: Bytes, immutable: Vector[BlockRef], anchor: Anchor[S]) =
        for
          header <- headerOf(raw).fold(_.raise[F, ChainServer.Info], F.pure)
          _ <- state.update(_.extend(Served(ref, header.ntn, raw), immutable.size, anchor.block))
          _ <- version.update(_ + 1)
        yield ()

      def rolledBack(to: Point) =
        state
          .modify { chain =>
            chain.rollback(to).fold((chain, false))(c => (c, true))
          }
          .flatMap(changed => version.update(_ + 1).whenA(changed))

  /** Is `point` on our chain: Origin, the anchor, volatile, or stored. */
  private def onChain(chain: ServedChain, point: Point): F[Boolean] = point match
    case Point.Origin                   => F.pure(chain.anchor.isEmpty || store.isDefined)
    case p if chain.containsVolatile(p) => F.pure(true)
    case p                              => store.fold(F.pure(false))(_.get(p).map(_.isDefined))

  /** One cursor per connected node-to-node ChainSync client: headers. */
  def source: Resource[F, ChainSyncServer.Source[F, NtNHeader]] =
    cursor(s => Some(s.header), raw => headerOf(raw).toOption.map(_.ntn))

  /** One cursor per connected node-to-client ChainSync client: whole blocks, as stored. */
  def blockSource: Resource[F, ChainSyncServer.Source[F, RawNtNBlock]] =
    cursor(served => RawNtNBlock.from(served.raw).toOption, raw => RawNtNBlock.from(raw).toOption)

  /** One cursor per connected ChainSync client, closed with the connection. `ofServed` and
    * `ofStored` give what the client is sent for a volatile and an immutable block (a block that
    * cannot be sent is skipped as if absent, which cannot happen for a validated chain).
    */
  private def cursor[P](
      ofServed: Served => Option[P],
      ofStored: Bytes => Option[P]
  ): Resource[F, ChainSyncServer.Source[F, P]] =
    Resource
      .make(state.modify(_.open()))(id => state.update(_.close(id)))
      .map { id =>
        new ChainSyncServer.Source[F, P]:
          def intersect(points: Vector[Point]) =
            state.get.flatMap { chain =>
              points
                .findM(onChain(chain, _))
                .flatMap {
                  case Some(found) =>
                    // as the reference's follower: the first instruction after an intersection
                    // is a roll-back to the point found
                    state
                      .update(_.move(id, Cursor.RollbackTo(found)))
                      .as(Right(found -> chain.tip))
                  case None => F.pure(Left(chain.tip))
                }
            }

          def poll: F[Option[ChainSyncServer.Update[P]]] =
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

          def await: F[ChainSyncServer.Update[P]] =
            version.get.flatMap { seen =>
              poll.flatMap {
                case Some(update) => F.pure(update)
                case None         => version.waitUntil(_ > seen) *> await
              }
            }
      }

  /** The block after `point` in the immutable store, for a client that has not caught up. */
  private def immutableNext[P](
      id: Long,
      point: Point,
      ofStored: Bytes => Option[P]
  ): F[Option[ChainSyncServer.Update[P]]] =
    store match
      case None => F.pure(None)
      case Some(s) =>
        s.stream(point).take(1).compile.last.flatMap {
          case None => F.pure(None)
          case Some(stored) =>
            for
              chain <- state.get
              _ <- state.update(_.move(id, Cursor.At(stored.ref.point)))
            yield ofStored(stored.raw).map(ChainSyncServer.Update.RollForward(_, chain.tip))
        }

  /** Blocks for BlockFetch: the range must lie on our chain (immutable then volatile). */
  def blocks: BlockFetchServer.Blocks[F] = (from, to, max) =>
    state.get.flatMap { chain =>
      def volatile(point: Point): Option[Int] =
        chain.blocks.indexWhere(_.ref.point == point) match
          case -1 => None
          case i  => Some(i)
      def number(point: Point): F[Option[BigInt]] = volatile(point) match
        case Some(i) => F.pure(Some(chain.blocks(i).ref.blockNo))
        case None =>
          store.fold(F.pure(Option.empty[BigInt]))(
            _.get(point).map(_.flatMap(raw => headerOf(raw).toOption.map(_.blockNo)))
          )
      def emit(served: Vector[Served]) = Stream.emits(served.map(_.raw).toList).covary[F]
      (number(from), number(to)).mapN {
        case (Some(a), Some(b)) if a <= b && b - a < max =>
          (volatile(from), volatile(to), store) match
            case (Some(i), Some(j), _) => Some(emit(chain.blocks.slice(i, j + 1)))
            case (Some(_), None, _)    => None
            case (None, _, None)       => None
            case (None, inVolatile, Some(s)) =>
              val first = Stream.eval(s.get(from)).unNone
              if from == to then Some(first)
              else
                val stored = s.stream(from)
                inVolatile match
                  case Some(j) =>
                    Some(first ++ stored.map(_.raw) ++ emit(chain.blocks.take(j + 1)))
                  case None => Some(first ++ stored.takeThrough(_.ref.point != to).map(_.raw))
        case _ => None
      }
    }

object ChainServer:
  /** Header of a stored block, as ChainSync carries it. */
  type HeaderOf = Bytes => Either[ChainError, Info]

  final case class Info(ntn: NtNHeader, blockNo: BigInt)

  /** Decode the header of a full block. For Byron the size hint is the block's byte size. */
  def headerOfBlock(byronEpochSlots: BigInt): HeaderOf =
    raw =>
      Block.decode(raw, byronEpochSlots).map { block =>
        block.header match
          case h: Header.Byron =>
            Info(
              NtNHeader(Era.Byron, h.raw, Some(NtNHeader.ByronInfo(h.boundary, raw.size))),
              h.blockNo
            )
          case h => Info(NtNHeader(h.era, h.raw, None), h.blockNo)
      }

  def open[F[_]: Async](
      store: Option[ImmutableStore[F]],
      anchor: Option[BlockRef],
      headerOf: HeaderOf
  ): F[ChainServer[F]] =
    for
      state <- Ref.of[F, ServedChain](ServedChain.empty(anchor))
      version <- SignallingRef.of[F, Long](0L)
    yield new ChainServer[F](state, version, store, headerOf)
