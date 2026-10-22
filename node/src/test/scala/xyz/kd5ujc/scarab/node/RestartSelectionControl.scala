// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Resource}
import cats.mtl.Raise
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Header, NodeError}

/** Exercise the production selector after ChainStorage.resume, from storage integration tests. */
object RestartSelectionControl:
  def resource[S](follower: Follower[IO, S], recovered: Vector[(Header, Bytes)])(using
      Raise[IO, NodeError]
  ): Resource[IO, Unit] =
    for
      pool <- Resource.eval(BlockPool.create[IO])
      delivered <- Resource.eval(recovered.traverse { (header, raw) =>
        BlockPool.Delivered
          .checked(header, raw)
          .fold(
            error => IO.raiseError(new AssertionError(error.message)),
            IO.pure
          )
      })
      _ <- Resource.eval(pool.seedRecoveredHeaders(delivered))
      _ <- Resource.eval(follower.installInvalidBlocks(pool.invalidBlocks))
      work <- Resource.eval(SelectionWork.create[IO](IO.unit))
      given BlockSelection.Before[IO] = BlockSelection.Before.noop[IO]
      _ <- follower.blockSelection(pool, work, _ => IO.unit)
    yield ()
