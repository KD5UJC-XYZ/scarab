// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref, Resource}
import cats.mtl.{Handle, Stateful}
import cats.syntax.all.*
import fs2.io.file.Files
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRules, LeiosBlockInfo, LeiosCert, VolatileChain}
import xyz.kd5ujc.scarab.ledger.{NodeError, StorageError}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.node.assertions.Assertions
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64
import xyz.kd5ujc.scarab.storage.{LeiosStore, LeiosStoreLocks, LeiosStoreSuite, RocksLeiosStore}

/** The acquired-endorser-block gate of the node's chain selection (ouroboros-consensus 2416493b
  * `Storage/ChainDB/Impl/ChainSel.hs:1778-1844`, `chainSelReprocessLeiosEb` 636-685,
  * `leiosAcquiredEbsRunner` in `ChainDB/Impl/Background.hs:170-200`, the seed at the ChainDB's open
  * in `ChainDB/Impl.hs:236-247`): a ranking block certifying an endorser block whose closure the
  * store has not completed is parked, and selected once it completes; a closure completed before
  * the open is acquired from the start.
  *
  * A Dijkstra chain with an announcement and its certificate cannot be forged here, so the blocks
  * are forged Conway blocks given the Leios fields of an announcing and a certifying block
  * ([[BlockPool.Delivered.withLeios]]); the rules are a stub, as the node has none for Dijkstra.
  */
class LeiosEbGateSuite extends FailsOnUncaught:
  private final case class Node(
      pool: BlockPool[IO],
      follower: Follower[IO, BigInt],
      state: Ref[IO, VolatileChain[BigInt]]
  )

  private val eb: EbHash = EbHash.fromBytes(Bytes(Vector.fill(32)(7.toByte)))

  private def node(recovered: Vector[BlockPool.Delivered], before: BlockPool[IO] => IO[Unit])(
      use: Node => IO[Unit]
  ): IO[Unit] =
    given Assertions[IO] = Assertions.noop[IO]
    for
      state <- Ref.of[IO, VolatileChain[BigInt]](VolatileChain.genesis(5, -1))
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(state)
      pool <- BlockPool.create[IO]
      _ <- pool.seedRecoveredHeaders(recovered)
      _ <- before(pool)
      follower <- Follower.create[IO, BigInt](
        BlockRules.from((_, h, _) => IO.pure(h.blockNo)),
        _ => IO.unit,
        ForgedChain.geometry
      )
      _ <- follower.installInvalidBlocks(pool.invalidBlocks)
      work <- SelectionWork.create[IO](IO.unit)
      given BlockSelection.Before[IO] = BlockSelection.Before.noop[IO]
      _ <- Handle
        .allow[NodeError] {
          follower
            .blockSelection(pool, work, e => IO(fail(s"stopped: $e")))
            .use(_ => use(Node(pool, follower, state)))
            .timeoutTo(5.seconds, IO(fail("block selection did not finish within 5 seconds")))
        }
        .rescue(error => IO(fail(error.message)))
    yield ()

  /** An announcing block and the block certifying its endorser block. */
  private def announcingAndCertifying: IO[(BlockPool.Delivered, BlockPool.Delivered)] =
    ForgedChain.forge(5000 until 5060).flatMap { chain =>
      chain.headers
        .zip(chain.raws)
        .take(2)
        .traverse((h, raw) =>
          BlockPool.Delivered.checked(h, raw).fold(e => IO(fail(e.message)), IO.pure)
        )
        .map(blocks =>
          (
            blocks(0).withLeios(LeiosBlockInfo(LeiosCert.Absent, Some(eb))),
            blocks(1).withLeios(LeiosBlockInfo(LeiosCert.Carried, None))
          )
        )
    }

  private def store: Resource[IO, LeiosStore[IO]] =
    Files
      .forAsync[IO]
      .tempDirectory
      .flatMap(dir =>
        LeiosStoreLocks
          .resource[IO]
          .flatMap(locks =>
            LeiosStoreSuite.allowing(RocksLeiosStore.resource[IO](dir / "leios", locks))
          )
      )

  /** The endorser block's point (at the announcing block's slot), body and transactions: the last
    * write completes its closure.
    */
  private def acquire(announcing: BlockPool.Delivered, s: LeiosStore[IO]): IO[Vector[EbPoint]] =
    val point = EbPoint(
      Word64.from(announcing.header.slot).fold(e => throw new AssertionError(e.toString), identity),
      eb
    )
    LeiosStoreSuite.ok(
      s.writeEbPoint(point, LeiosStoreSuite.word32(100)) *>
        s.writeEbBody(point, LeiosStoreSuite.body(1), Vector.empty) *>
        s.writeTxs(point, Vector(0 -> LeiosStoreSuite.tx(0)))
    )

  private def tip(n: Node): IO[Option[Bytes]] = n.state.get.map(_.tipRef.map(_.hash))

  test(
    "a block certifying an unacquired endorser block is parked, and selected once it is acquired"
  ) {
    announcingAndCertifying.flatMap { (announcing, certifying) =>
      store.use { raw =>
        node(Vector.empty, _ => IO.unit) { n =>
          val notified = n.pool.leios.notifying(raw)
          n.pool.session.use { sender =>
            for
              _ <- sender.claim(Vector(announcing, certifying).map(_.header.hash))
              _ <- sender.fillDelivered(Vector(announcing, certifying))
              _ <- n.pool.admissions.reprocess
              parked <- tip(n)
              revision <- n.follower.headerChanges.revision
              completed <- acquire(announcing, notified)
              // the store's completion reaches the runner, which queues the reprocessing
              _ <- n.follower.headerChanges.changed(revision)
              selected <- tip(n)
            yield
              assertEquals(completed.map(_.hash), Vector(eb))
              assertEquals(parked, Some(announcing.header.hash))
              assertEquals(selected, Some(certifying.header.hash))
          }
        }
      }
    }
  }

  test("a closure the store completed before the open is acquired from the start") {
    announcingAndCertifying.flatMap { (announcing, certifying) =>
      store.use { s =>
        acquire(announcing, s) *>
          node(
            Vector(announcing, certifying),
            pool =>
              Handle
                .allow[StorageError](pool.leios.seeded(s, None))
                .rescue(e => IO(fail(e.message)))
          ) { n =>
            tip(n).map(selected => assertEquals(selected, Some(certifying.header.hash)))
          }
      }
    }
  }

  test("without the seed, the startup selection parks the certifying block") {
    announcingAndCertifying.flatMap { (announcing, certifying) =>
      node(Vector(announcing, certifying), _ => IO.unit) { n =>
        tip(n).map(selected => assertEquals(selected, Some(announcing.header.hash)))
      }
    }
  }

  test("the acquired set is pruned below the immutable tip with the collection") {
    LeiosAcquired.create[IO].flatMap { acquired =>
      for
        _ <- acquired.acquired(EbPoint(LeiosStoreSuite.slot(3), eb))
        _ <- acquired.pruned(BigInt(3))
        kept <- acquired.snapshot.map(_.contains(eb))
        _ <- acquired.pruned(BigInt(4))
        gone <- acquired.snapshot.map(_.contains(eb))
      yield assertEquals((kept, gone), (true, false))
    }
  }
