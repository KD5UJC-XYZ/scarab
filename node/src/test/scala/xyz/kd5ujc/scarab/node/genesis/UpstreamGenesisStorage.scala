// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.genesis

import cats.effect.{IO, Resource}
import cats.mtl.Raise
import cats.syntax.all.*
import fs2.io.file.Path
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef, VolatileChain}
import xyz.kd5ujc.scarab.ledger.{ChainError, StorageError}
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.storage.{ChainPersistence, ReceivedBlocks, VolatileStore}

import UpstreamGenesisReplay.Ledger

/** Native MockFS restart semantics over the production versioned journals. Selected blocks and
  * received forks have separate lifetimes; recovering selection never fabricates a received body.
  */
object UpstreamGenesisStorage:
  enum Problem extends DomainError:
    case MissingHeader(point: ChainSync.Point)
    case BodyDiffers(point: ChainSync.Point)
    case Chain(cause: ChainError)
  final case class Opened(
      chain: VolatileChain[Ledger],
      received: ReceivedBlocks[IO],
      selected: ChainPersistence[IO, Ledger]
  )

  def open(dir: Path, k: Int, universe: UpstreamGenesisBlocks.Universe)(using
      Raise[IO, StorageError],
      Raise[IO, Problem]
  ): Resource[IO, Opened] =
    for
      received <- ReceivedBlocks.open[IO](dir / "received")
      selected <- VolatileStore.open[IO](dir / "selected")
      (log, recovered) = selected
      chain <- Resource.eval(
        recovered.chain
          .foldLeftM(VolatileChain.genesis(k, Ledger(ChainSync.Point.Origin, 0, -1))) {
            (chain, stored) =>
              val (ref, raw) = stored
              universe.encoded.get(ref.point) match
                case None => Raise[IO, Problem].raise(Problem.MissingHeader(ref.point))
                case Some(block) if block.body != raw =>
                  Raise[IO, Problem].raise(Problem.BodyDiffers(ref.point))
                case Some(block) =>
                  chain
                    .extend(block.header, Ledger(ref.point, ref.blockNo, ref.slot))
                    .map(_._1)
                    .leftMap(Problem.Chain(_))
                    .fold(Raise[IO, Problem].raise, IO.pure)
          }
      )
    yield Opened(
      chain,
      received,
      new ChainPersistence[IO, Ledger]:
        def extended(
            ref: BlockRef,
            raw: Bytes,
            immutable: Vector[BlockRef],
            anchor: Anchor[Ledger]
        )(using
            Raise[IO, StorageError],
            Raise[IO, ChainError]
        ): IO[Unit] = log.append(ref, raw)
        def rolledBack(to: ChainSync.Point)(using
            Raise[IO, StorageError],
            Raise[IO, ChainError]
        ): IO[Unit] = log.rollback(to)
    )
