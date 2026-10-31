// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.ChainSelection
import xyz.kd5ujc.scarab.consensus.SelectView
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** The processed forest keeps what the reference's VolatileDB keeps in memory of a block, its index
  * entry, and reads the body back from the received log (#416). Without a log the body stays in
  * memory.
  */
class ProcessedBodiesSuite extends FailsOnUncaught:
  private val local =
    ChainSelection.Local(Point.Origin, SelectView(-1, 0, None, 0, None), Vector.empty)

  private def blocks(n: Int): IO[Vector[BlockPool.Delivered]] =
    ForgedChain
      .forge(5000 until 5100)
      .flatMap(chain =>
        chain.headers
          .zip(chain.raws)
          .take(n)
          .traverse((h, raw) =>
            BlockPool.Delivered.checked(h, raw).fold(e => IO(fail(e.message)), IO.pure)
          )
      )
      .flatTap(found => IO(assertEquals(found.size, n, "forged blocks")))

  /** A received log over `stored`: `get` hides a refused block, `original` does not. */
  private def journal(
      stored: Ref[IO, Map[Bytes, Bytes]],
      refused: Ref[IO, Set[Bytes]]
  ): BlockPool.Recovered[IO] =
    BlockPool.Recovered(
      stored.get.map(_.keySet),
      h => (stored.get, refused.get).mapN((s, r) => s.get(h).filterNot(_ => r(h))),
      h => stored.get.map(_.get(h)),
      h => refused.update(_ + h)
    )

  /** `delivered` admitted through `pool`'s admission queue and processed, as selection does. */
  private def processAll(pool: BlockPool[IO], delivered: Vector[BlockPool.Delivered]): IO[Unit] =
    pool.admissions.resource(_ => IO.unit).use { _ =>
      pool.session.use { sender =>
        sender.claim(delivered.map(_.header.hash)) *> sender.fillDelivered(delivered) *>
          delivered.traverse_(_ =>
            pool.admissions.next.flatMap {
              case BlockAdmissions.Work.Add(admission) =>
                pool.processAdmission(admission) *>
                  pool.completeAdmission(admission.delivered.header.hash)
              case other => IO(fail(s"not an admission: $other"))
            }
          )
      }
    }

  test("with a received log a processed block keeps no body and reads it back, refused or not") {
    for
      delivered <- blocks(2)
      stored <- Ref.of[IO, Map[Bytes, Bytes]](delivered.map(d => d.header.hash -> d.block).toMap)
      refused <- Ref.of[IO, Set[Bytes]](Set.empty)
      pool <- BlockPool
        .create[IO](BlockPool.FetchPolicy(), IO.pure(local), journal(stored, refused))
      _ <- processAll(pool, delivered)
      processed <- pool.processedSnapshot
      _ <- IO(assertEquals(processed.keySet, delivered.map(_.header.hash).toSet))
      _ <- IO(assert(processed.values.forall(_.held.isEmpty), "the forest holds no body"))
      first = delivered.head
      read <- pool.body(first.header.hash, processed(first.header.hash))
      _ <- IO(assertEquals(read, Some(first.block)))
      _ <- refused.update(_ + first.header.hash)
      afterRefusal <- pool.body(first.header.hash, processed(first.header.hash))
      _ <- IO(assertEquals(afterRefusal, Some(first.block), "a refused original stays readable"))
      taken <- pool.takeFilled(delivered(1).header.hash)
    yield
      assertEquals(taken.map(_.block), Some(delivered(1).block), "a claim is filled from the log")
      assertEquals(taken.flatMap(_.metadata).map(_.header.hash), Some(delivered(1).header.hash))
  }

  test("without a received log a processed block keeps its body in memory") {
    for
      delivered <- blocks(2)
      pool <- BlockPool.create[IO]
      _ <- processAll(pool, delivered)
      processed <- pool.processedSnapshot
      bodies <- delivered.traverse(d => pool.body(d.header.hash, processed(d.header.hash)))
    yield
      assertEquals(processed.values.map(_.held.isDefined).toSet, Set(true))
      assertEquals(bodies, delivered.map(d => Some(d.block)))
  }

  test("blocks seeded from the received log are kept as metadata and read back from it") {
    for
      delivered <- blocks(3)
      stored <- Ref.of[IO, Map[Bytes, Bytes]](delivered.map(d => d.header.hash -> d.block).toMap)
      refused <- Ref.of[IO, Set[Bytes]](Set.empty)
      pool <- BlockPool
        .create[IO](BlockPool.FetchPolicy(), IO.pure(local), journal(stored, refused))
      _ <- pool.seedRecoveredMetadata(delivered.map(_.metadata))
      processed <- pool.processedSnapshot
      bodies <- delivered.traverse(d => pool.body(d.header.hash, processed(d.header.hash)))
    yield
      assert(processed.values.forall(p => p.held.isEmpty && p.origin == BlockPool.Source.Recovered))
      assertEquals(bodies, delivered.map(d => Some(d.block)))
  }
