// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.relay

import cats.Monad
import cats.mtl.Raise
import xyz.kd5ujc.scarab.ledger.{StorageError, ChainError}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef}
import xyz.kd5ujc.scarab.storage.ChainPersistence

object Persistence:
  /** Both observers see every update, `first` before `second`: the store must hold a block before
    * the served chain stops keeping it in memory.
    */
  def both[F[_]: Monad, S](
      first: ChainPersistence[F, S],
      second: ChainPersistence[F, S]
  ): ChainPersistence[F, S] = new ChainPersistence[F, S]:
    def extended(ref: BlockRef, raw: Bytes, immutable: Vector[BlockRef], anchor: Anchor[S])(using
        Raise[F, StorageError],
        Raise[F, ChainError]
    ) =
      first.extended(ref, raw, immutable, anchor) *> second.extended(ref, raw, immutable, anchor)
    def rolledBack(to: ChainSync.Point)(using Raise[F, StorageError], Raise[F, ChainError]) =
      first.rolledBack(to) *> second.rolledBack(to)
