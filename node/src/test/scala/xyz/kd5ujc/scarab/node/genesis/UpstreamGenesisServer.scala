// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.genesis

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.SignallingRef
import xyz.kd5ujc.scarab.network.{BlockFetchServer, ChainSyncServer}
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}
import UpstreamGenesisCases.NodeState
import UpstreamGenesisBlocks.{Block, Problem, Universe}

/** The server conditions of PeerSimulator/Handlers.hs, interpreted over unchanged native state
  * ticks. Silence before AwaitReply and delayed block availability remain separate observations.
  */
final class UpstreamGenesisServer private (
    universe: Universe,
    available: SignallingRef[IO, (Long, NodeState)],
    cursor: Ref[IO, ChainSync.Point],
    problem: Problem => IO[Nothing]
):
  import ChainSync.Point
  import ChainSyncServer.Update
  private enum Decision:
    case Reply(update: Update[NtNHeader], next: Point)
    case Await, Silent

  private def ancestor(a: Point, b: Point): Boolean = a match
    case Point.Origin => true
    case Point.Block(_, hash) =>
      b match
        case Point.Origin               => false
        case Point.Block(_, descendant) => descendant.value.takeRight(hash.size) == hash.value

  private def tip(point: Point): ChainSync.Tip = point match
    case Point.Origin => ChainSync.Tip.Origin
    case _ =>
      universe.native
        .get(point)
        .fold(ChainSync.Tip.Origin)(b =>
          ChainSync.Tip(b.header.point, ChainSync.UInt64.saturating(b.header.blockNo))
        )

  private def encoded(point: Point): IO[Point] =
    universe.encode(point).fold(problem, IO.pure)

  private def decide(at: Point, state: NodeState): IO[Decision] =
    val chain = Point.Origin +: universe.chain(state.header).map(_.native)
    if at == state.header then
      IO.pure(if ancestor(state.tip, state.header) then Decision.Await else Decision.Silent)
    else if ancestor(at, state.header) then
      chain.dropWhile(_ != at).drop(1).headOption.flatMap(universe.native.get) match
        case Some(block) =>
          IO.pure(Decision.Reply(Update.RollForward(block.wire, tip(state.tip)), block.native))
        case None => IO.pure(Decision.Silent)
    else if ancestor(state.header, at) then IO.pure(Decision.Silent)
    else
      val common = chain.reverseIterator.find(ancestor(_, at)).getOrElse(Point.Origin)
      encoded(common).map(point =>
        Decision.Reply(Update.RollBackward(point, tip(state.tip)), common)
      )

  private def changed(version: Long): IO[Unit] =
    available.discrete.dropWhile(_._1 == version).take(1).compile.drain

  private def reply(allowAwait: Boolean): IO[Option[Update[NtNHeader]]] =
    cats.Monad[IO].tailRecM(()) { _ =>
      (available.get, cursor.get).tupled.flatMap { case ((version, state), at) =>
        decide(at, state).flatMap {
          case Decision.Reply(update, next) => cursor.set(next).as(Right(Some(update)))
          case Decision.Await if allowAwait => IO.pure(Right(None))
          case _                            => changed(version).as(Left(()))
        }
      }
    }

  val chainSync: ChainSyncServer.Source[IO, NtNHeader] = new ChainSyncServer.Source[IO, NtNHeader]:
    def intersect(points: Vector[Point]) = available.get.flatMap { (_, state) =>
      val offered = points.flatMap(p => universe.decode(p).toOption)
      offered.find(ancestor(_, state.tip)) match
        case Some(point) => cursor.set(point) *> encoded(point).map(p => Right(p -> tip(state.tip)))
        case None        => IO.pure(Left(tip(state.tip)))
    }
    def poll = reply(true)
    def await = reply(false).flatMap(_.fold(IO.never[Update[NtNHeader]])(IO.pure))

  private def waitBlock(block: Block): IO[Unit] = cats.Monad[IO].tailRecM(()) { _ =>
    available.get.flatMap { (version, state) =>
      val point = block.native
      val present = ancestor(point, state.block) ||
        (!ancestor(point, state.header) && ancestor(state.block, state.header) && !ancestor(
          state.block,
          point
        ))
      if present then IO.pure(Right(())) else changed(version).as(Left(()))
    }
  }

  val blocks: BlockFetchServer.Blocks[IO] = new BlockFetchServer.Blocks[IO]:
    def range(from: Point, to: Point, maxBlocks: Int) =
      IO.pure {
        for
          nativeTo <- universe.decode(to).toOption
          source = universe.chain(nativeTo)
          first = source.indexWhere(_.header.point == from)
          last = source.indexWhere(_.header.point == to)
          if first >= 0 && last >= first && last - first + 1 <= maxBlocks
        yield Stream
          .emits(source.slice(first, last + 1))
          .covary[IO]
          .evalMap(b => waitBlock(b).as(b.body))
      }

  def restart: IO[UpstreamGenesisServer] =
    Ref
      .of[IO, ChainSync.Point](ChainSync.Point.Origin)
      .map(next => new UpstreamGenesisServer(universe, available, next, problem))

  def publish(state: NodeState): IO[Unit] = available.update((version, _) => (version + 1, state))

object UpstreamGenesisServer:
  def create(universe: Universe, problem: Problem => IO[Nothing]): IO[UpstreamGenesisServer] =
    (
      SignallingRef[IO, (Long, NodeState)](
        0L -> NodeState(ChainSync.Point.Origin, ChainSync.Point.Origin, ChainSync.Point.Origin)
      ),
      Ref.of[IO, ChainSync.Point](ChainSync.Point.Origin)
    ).mapN((available, cursor) => new UpstreamGenesisServer(universe, available, cursor, problem))
