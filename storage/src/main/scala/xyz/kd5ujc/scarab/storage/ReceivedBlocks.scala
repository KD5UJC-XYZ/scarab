// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.Mutex
import cats.mtl.Raise
import cats.syntax.all.*
import fs2.io.file.Path
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.StorageError

/** Original received bodies, including forks and blocks whose selection LoE postpones.
  *
  * ChainDB writes to VolatileDB before selection (the pinned consensus ChainSel.hs `addBlockSync`).
  * The selected-chain log cannot serve this purpose: appending a postponed fork there would change
  * the chain recovered on restart. This separate log uses the existing VolatileStore record format
  * unchanged and never writes rollback records.
  */
trait ReceivedBlocks[F[_]]:
  def put(ref: BlockRef, raw: Bytes)(using Raise[F, StorageError]): F[Unit]
  def get(hash: Bytes): F[Option[(BlockRef, Bytes)]]
  def entries: F[Vector[(BlockRef, Bytes)]]
  def hashes: F[Set[Bytes]]

  /** A validation refusal suppresses this body's reuse until the next full validation on open. Like
    * the reference's in-memory invalid-block set, this is not a peer identity persisted across node
    * lifetimes.
    */
  def invalidate(hash: Bytes): F[Unit]

  /** Only call after the immutable store has made this boundary durable. Keep the cutoff slot:
    * Byron EBBs and regular blocks may share it (native VolatileDB/Impl/FileInfo.hs:94–97).
    */
  def collect(durableSlot: BigInt)(using Raise[F, StorageError]): F[Unit]

object ReceivedBlocks:
  def open[F[_]: Async](dir: Path, blocksPerFile: Int = 512)(using
      Raise[F, StorageError]
  ): Resource[F, ReceivedBlocks[F]] =
    for
      opened <- VolatileStore.open[F](dir, blocksPerFile)
      (log, recovered) = opened
      blocks <- Resource.eval(
        Ref.of[F, Map[Bytes, (BlockRef, Bytes)]](
          recovered.chain.map(block => block._1.hash -> block).toMap
        )
      )
      lock <- Resource.eval(Mutex[F])
      invalid <- Resource.eval(Ref.of[F, Set[Bytes]](Set.empty))
    yield new ReceivedBlocks[F]:
      def put(ref: BlockRef, raw: Bytes)(using Raise[F, StorageError]): F[Unit] =
        lock.lock.surround {
          blocks.get.flatMap { known =>
            Async[F].unlessA(known.contains(ref.hash)) {
              Async[F].uncancelable(_ =>
                log.append(ref, raw) *> blocks.update(_.updated(ref.hash, ref -> raw))
              )
            }
          }
        }

      def get(hash: Bytes): F[Option[(BlockRef, Bytes)]] =
        invalid.get.flatMap(refused =>
          if refused(hash) then Async[F].pure(None) else blocks.get.map(_.get(hash))
        )

      def entries: F[Vector[(BlockRef, Bytes)]] =
        (blocks.get, invalid.get).mapN { (known, refused) =>
          known.valuesIterator
            .filterNot(block => refused(block._1.hash))
            .toVector
            .sortBy { case (ref, _) => (ref.slot, ref.hash.hex) }
        }

      def hashes: F[Set[Bytes]] =
        (blocks.get, invalid.get).mapN((known, refused) => known.keySet -- refused)

      def invalidate(hash: Bytes): F[Unit] = invalid.update(_ + hash)

      def collect(durableSlot: BigInt)(using Raise[F, StorageError]): F[Unit] =
        lock.lock.surround {
          Async[F].uncancelable(_ =>
            log.gc(durableSlot - 1) *> blocks
              .updateAndGet(_.filter(_._2._1.slot >= durableSlot))
              .flatMap(remaining => invalid.update(_ intersect remaining.keySet))
          )
        }
