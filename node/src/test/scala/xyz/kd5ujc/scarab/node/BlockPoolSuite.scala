// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.selection.FetchMode
import scala.concurrent.duration.*

/** A block is claimed by one session, fetched by it, and taken by whichever session applies it. */
class BlockPoolSuite extends CatsEffectSuite:
  override def munitIOTimeout: FiniteDuration = 30.seconds

  private def hash(n: Int): Bytes = Bytes.wrap(Array.fill(32)(n.toByte))
  private def block(n: Int): Bytes = Bytes.wrap(Array.fill(10)((n + 100).toByte))

  test("Genesis handover retries a stalled claim and retains the winning source") {
    for
      pool <- BlockPool.create[IO]
      result <- (pool.session, pool.session).tupled.use { (slow, replacement) =>
        for
          first <- slow.claim(Vector(hash(1)))
          duplicate <- replacement.claim(Vector(hash(1)), allowOverlap = true)
          _ <- replacement.fill(Vector(hash(1) -> block(1)))
          _ <- slow.fill(Vector(hash(1) -> block(2)))
          taken <- replacement.take(hash(1))
        yield (first, duplicate, taken)
      }
    yield
      assertEquals(result._1, Vector(true))
      assertEquals(result._2, Vector(true))
      assertEquals(result._3.map(t => (t.block, t.own)), Some((block(1), true)))
  }

  test("a second claim on the same hashes owns nothing, and the rest is split") {
    for
      pool <- BlockPool.create[IO]
      claims <- pool.session.allocated.map(_._1)
      other <- pool.session.allocated.map(_._1)
      first <- claims.claim(Vector(hash(1), hash(2)))
      second <- other.claim(Vector(hash(1), hash(2), hash(3)))
    yield
      assertEquals(first, Vector(true, true))
      assertEquals(second, Vector(false, false, true))
  }

  test("a received body remains readable, a block nobody claimed is not there") {
    for
      pool <- BlockPool.create[IO]
      claims <- pool.session.allocated.map(_._1)
      _ <- claims.claim(Vector(hash(1)))
      _ <- claims.fill(Vector(hash(1) -> block(1)))
      got <- pool.take(hash(1))
      again <- pool.take(hash(1))
      never <- pool.take(hash(9))
      held <- pool.size
    yield
      assertEquals(got, Some(block(1)))
      assertEquals(again, Some(block(1)))
      assertEquals(never, None)
      assertEquals(held, 1)
  }

  test("a session waiting for a block that another one is fetching gets it") {
    for
      pool <- BlockPool.create[IO]
      claims <- pool.session.allocated.map(_._1)
      _ <- claims.claim(Vector(hash(1)))
      waiting <- pool.take(hash(1)).start
      _ <- IO.sleep(50.millis)
      _ <- claims.fill(Vector(hash(1) -> block(1)))
      got <- waiting.joinWithNever
    yield assertEquals(got, Some(block(1)))
  }

  test("a released claim sends the waiter to fetch the block itself, and it can be claimed again") {
    for
      pool <- BlockPool.create[IO]
      claims <- pool.session.allocated.map(_._1)
      _ <- claims.claim(Vector(hash(1)))
      waiting <- pool.take(hash(1)).start
      _ <- IO.sleep(50.millis)
      _ <- claims.release(Vector(hash(1)))
      got <- waiting.joinWithNever
      again <- claims.claim(Vector(hash(1)))
    yield
      assertEquals(got, None)
      assertEquals(again, Vector(true))
  }

  test("releasing a filled block leaves it") {
    for
      pool <- BlockPool.create[IO]
      claims <- pool.session.allocated.map(_._1)
      _ <- claims.claim(Vector(hash(1)))
      _ <- claims.fill(Vector(hash(1) -> block(1)))
      _ <- claims.release(Vector(hash(1)))
      got <- pool.take(hash(1))
    yield assertEquals(got, Some(block(1)))
  }

  test("blocks nobody takes are dropped, oldest first, past the retained count") {
    val n = 2 * BlockPool.Retained + 10
    def key(i: Int): Bytes = Bytes.wrap(java.nio.ByteBuffer.allocate(32).putInt(i).array)
    for
      pool <- BlockPool.create[IO]
      claims <- pool.session.allocated.map(_._1)
      hashes = Vector.range(0, n).map(key)
      _ <- hashes.grouped(100).toVector.traverse_ { g =>
        claims.claim(g) *> claims.fill(g.map(h => h -> block(1)))
      }
      held <- pool.size
      oldest <- pool.take(key(0))
      newest <- pool.take(key(n - 1))
    yield
      assert(held <= 2 * BlockPool.Retained + 100, s"held $held")
      assertEquals(oldest, None)
      assertEquals(newest, Some(block(1)))
  }

  test("a session that ends gives up its open claims, and the session waiting for one fetches it") {
    for
      pool <- BlockPool.create[IO]
      ended <- pool.session.allocated
      (claims, end) = ended
      _ <- claims.claim(Vector(hash(1), hash(2)))
      _ <- claims.fill(Vector(hash(2) -> block(2)))
      waiting <- pool.take(hash(1)).start
      _ <- IO.sleep(50.millis)
      _ <- end
      cachedCount <- pool.size
      got <- waiting.joinWithNever
      filled <- pool.take(hash(2))
      held <- pool.size
    yield
      assertEquals(got, None)
      // size counts cached blocks too: a released claim does not imply an empty live pool.
      assertEquals(cachedCount, 1)
      // what the session had filled stays for whoever applies it
      assertEquals(filled, Some(block(2)))
      assertEquals(held, 1)
  }

  test("a session that is cancelled while it holds claims gives them up") {
    for
      pool <- BlockPool.create[IO]
      claimed <- cats.effect.Deferred[IO, Unit]
      holder <- pool.session
        .use(claims => claims.claim(Vector(hash(1))) *> claimed.complete(()) *> IO.never)
        .start
      _ <- claimed.get
      waiting <- pool.take(hash(1)).start
      _ <- IO.sleep(50.millis)
      _ <- holder.cancel
      got <- waiting.joinWithNever
    yield assertEquals(got, None)
  }

  test("a session cannot fill or release what another session claimed") {
    for
      pool <- BlockPool.create[IO]
      mine <- pool.session.allocated.map(_._1)
      theirs <- pool.session.allocated.map(_._1)
      _ <- mine.claim(Vector(hash(1)))
      _ <- theirs.fill(Vector(hash(1) -> block(1)))
      _ <- theirs.release(Vector(hash(1)))
      stillOurs <- theirs.claim(Vector(hash(1)))
      _ <- mine.fill(Vector(hash(1) -> block(1)))
      got <- pool.take(hash(1))
    yield
      assertEquals(stillOurs, Vector(false))
      assertEquals(got, Some(block(1)))
  }

  // ------------------------------------------------------------------ the fetch mode

  test("bulk sync: a block another session holds is not claimed, whatever the policy allows") {
    for
      pool <- BlockPool.create[IO](BlockPool.FetchPolicy(deadlineConcurrency = 3))
      first <- pool.session.allocated.map(_._1)
      second <- pool.session.allocated.map(_._1)
      a <- first.claim(Vector(hash(1)), FetchMode.BulkSync)
      b <- second.claim(Vector(hash(1)), FetchMode.BulkSync)
    yield assertEquals((a, b), (Vector(true), Vector(false)))
  }

  test("deadline claims permit preferred-peer handover; the coordinator controls admission") {
    for
      pool <- BlockPool.create[IO]
      first <- pool.session.allocated.map(_._1)
      second <- pool.session.allocated.map(_._1)
      a <- first.claim(Vector(hash(1)), FetchMode.Deadline)
      b <- second.claim(Vector(hash(1)), FetchMode.Deadline)
    yield assertEquals((a, b), (Vector(true), Vector(true)))
  }

  test(
    "deadline claims retain first delivery across all admitted sessions"
  ) {
    for
      pool <- BlockPool.create[IO](BlockPool.FetchPolicy(deadlineConcurrency = 2))
      first <- pool.session.allocated.map(_._1)
      second <- pool.session.allocated.map(_._1)
      third <- pool.session.allocated.map(_._1)
      a <- first.claim(Vector(hash(1)), FetchMode.Deadline)
      again <- first.claim(Vector(hash(1)), FetchMode.Deadline)
      b <- second.claim(Vector(hash(1)), FetchMode.Deadline)
      c <- third.claim(Vector(hash(1)), FetchMode.Deadline)
      waiting <- pool.take(hash(1)).start
      _ <- IO.sleep(20.millis)
      _ <- second.fill(Vector(hash(1) -> block(2)))
      got <- waiting.joinWithNever
      // the slower delivery cannot replace the retained original body
      _ <- first.fill(Vector(hash(1) -> block(1)))
      held <- pool.size
    yield
      assertEquals((a, again, b, c), (Vector(true), Vector(false), Vector(true), Vector(true)))
      assertEquals(got, Some(block(2)))
      assertEquals(held, 1)
  }

  test(
    "a duplicate claim released by one session leaves the block to the other; both released frees it"
  ) {
    for
      pool <- BlockPool.create[IO](BlockPool.FetchPolicy(deadlineConcurrency = 2))
      first <- pool.session.allocated.map(_._1)
      second <- pool.session.allocated.map(_._1)
      _ <- first.claim(Vector(hash(1)), FetchMode.Deadline)
      _ <- second.claim(Vector(hash(1)), FetchMode.Deadline)
      waiting <- pool.take(hash(1)).start
      _ <- IO.sleep(20.millis)
      _ <- first.release(Vector(hash(1)))
      stillWaiting <- IO.race(waiting.join, IO.sleep(50.millis)).map(_.isRight)
      _ <- second.fill(Vector(hash(1) -> block(1)))
      got <- waiting.joinWithNever
      _ <- first.claim(Vector(hash(2)), FetchMode.Deadline)
      _ <- first.release(Vector(hash(2)))
      freed <- second.claim(Vector(hash(2)), FetchMode.Deadline)
    yield
      assert(stillWaiting, "the waiter is released only when nobody holds the claim")
      assertEquals(got, Some(block(1)))
      assertEquals(freed, Vector(true))
  }

  test(
    "a block says which session delivered it; a refusal drops that session's other blocks and tells it"
  ) {
    import xyz.kd5ujc.scarab.ledger.{BlockFailure, ValidationError}
    import xyz.kd5ujc.scarab.protocol.ChainSync
    val refusal = ValidationError(
      ChainSync.Point.Block(ChainSync.UInt64.from(1).toOption.get, hash(1)),
      BlockFailure.HeaderDiffers
    )
    for
      pool <- BlockPool.create[IO]
      deliverer <- pool.session.allocated.map(_._1)
      taker <- pool.session.allocated.map(_._1)
      _ <- deliverer.claim(Vector(hash(1), hash(2), hash(4)))
      _ <- deliverer.fill(Vector(hash(1) -> block(1), hash(2) -> block(2), hash(4) -> block(4)))
      own <- deliverer.take(hash(2))
      first <- taker.take(hash(1))
      // the rules refused the block the taker took: it is not the taker's peer that sent it
      _ <- taker.distrust(first.get.origin, refusal)
      blamed <- deliverer.blamed.timeout(1.second)
      dropped <- taker.take(hash(4))
      held <- pool.size
    yield
      assertEquals(own.map(t => (t.block, t.own)), Some((block(2), true)))
      assertEquals(first.map(t => (t.block, t.own)), Some((block(1), false)))
      assertEquals(blamed, refusal)
      assertEquals(dropped, None)
      assertEquals(held, 0)
  }

  test("recovered original bytes remain readable and invalidation has no live peer attribution") {
    import cats.effect.Ref
    import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
    import xyz.kd5ujc.scarab.ledger.{BlockFailure, ValidationError}
    import xyz.kd5ujc.scarab.protocol.ChainSync.Point
    val local = ChainSelection.Local(Point.Origin, SelectView(-1, 0, None, 0, None), Vector.empty)
    val refusal = ValidationError(
      Point.Block(xyz.kd5ujc.scarab.protocol.ChainSync.UInt64.saturating(1), hash(1)),
      BlockFailure.HeaderDiffers
    )
    for
      persisted <- Ref.of[IO, Map[Bytes, Bytes]](Map(hash(1) -> block(1), hash(2) -> block(2)))
      pool <- BlockPool.create[IO](
        BlockPool.FetchPolicy(),
        IO.pure(local),
        BlockPool.Recovered(
          persisted.get.map(_.keySet),
          h => persisted.get.map(_.get(h)),
          h => persisted.update(_ - h)
        )
      )
      _ <- pool.session.use { session =>
        for
          first <- session.take(hash(1))
          again <- session.take(hash(1))
          _ <- IO(assertEquals(first.map(_.origin), Some(BlockPool.Source.Recovered)))
          _ <- IO(assertEquals(first.map(_.block), again.map(_.block)))
          _ <- session.distrust(BlockPool.Source.Recovered, refusal)
          missing <- session.take(hash(1))
          other <- session.take(hash(2))
          blame <- IO.race(session.blamed, IO.sleep(1.millis))
          _ <- IO(assertEquals(missing, None))
          _ <- IO(assertEquals(other.map(_.block), Some(block(2))))
          _ <- IO(assertEquals(blame, Right(())))
        yield ()
      }
    yield ()
  }

  test("a rejected own-source body is invalidated after reads and reported only once") {
    import cats.effect.Ref
    import xyz.kd5ujc.scarab.ledger.{BlockFailure, ValidationError}
    import xyz.kd5ujc.scarab.protocol.ChainSync
    val refusal = ValidationError(
      ChainSync.Point.Block(ChainSync.UInt64.saturating(1), hash(1)),
      BlockFailure.HeaderDiffers
    )
    for
      reports <- Ref.of[IO, Vector[ValidationError]](Vector.empty)
      pool <- BlockPool.create[IO]
      _ <- pool.session(Some(e => reports.update(_ :+ e))).use { session =>
        for
          _ <- session.claim(Vector(hash(1))) *> session.fill(Vector(hash(1) -> block(1)))
          taken <- session.take(hash(1))
          origin <- taken.fold(IO(fail("original body absent")))(IO.pure)
          _ <- IO(assert(origin.own))
          _ <- session.distrust(origin.origin, refusal) *> session.distrust(origin.origin, refusal)
          missing <- session.take(hash(1))
          retained <- session.retained(origin.origin, hash(1))
          _ <- IO(assertEquals(missing, None))
          _ <- IO(assertEquals(retained, false))
        yield ()
      }
      observed <- reports.get
    yield assertEquals(observed, Vector(refusal))
  }

  test("fetch decisions use actual readable recovered bodies and invalidate the refused hash") {
    import cats.effect.Ref
    import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
    import xyz.kd5ujc.scarab.ledger.{BlockFailure, Fixtures, ValidationError}
    import xyz.kd5ujc.scarab.network.selection.{FetchGsv, GenesisBlockFetch, Gsm}
    import xyz.kd5ujc.scarab.protocol.ChainSync.Point
    val headers = Fixtures.chain(1)
    val h = headers.head
    val local = ChainSelection.Local(Point.Origin, SelectView(-1, 0, None, 0, None), Vector.empty)
    val candidate = ChainSelection.Candidate(Point.Origin, headers)
    for
      durable <- Ref.of[IO, Map[Bytes, Bytes]](Map(h.hash -> block(1)))
      pool <- BlockPool.create[IO](
        BlockPool.FetchPolicy(),
        IO.pure(local),
        BlockPool.Recovered(
          durable.get.map(_.keySet),
          h => durable.get.map(_.get(h)),
          h => durable.update(_ - h)
        )
      )
      _ <- pool.fetchCoordinator.installGenesis(
        FetchCoordinator.GenesisControl(
          IO.pure(
            FetchCoordinator
              .GenesisContext(Gsm.Phase.Syncing, 0, GenesisBlockFetch.Starvation.Ongoing)
          ),
          _ => IO.unit
        )
      )
      _ <- (pool.session, pool.fetchCoordinator.session(0)).tupled.use { (claims, fetcher) =>
        for
          _ <- fetcher.announce(Some(h), FetchGsv.default, candidate = Some(candidate))
          _ <- pool.fetchCoordinator.step
          recovered <- fetcher.tryAcquire
          _ <- IO(assertEquals(recovered, None))
          first <- claims.take(h.hash)
          _ <- IO(assertEquals(first.map(_.origin), Some(BlockPool.Source.Recovered)))
          _ <- claims.distrust(
            BlockPool.Source.Recovered,
            ValidationError(h.point, BlockFailure.HeaderDiffers)
          )
          _ <- pool.fetchCoordinator.step
          refetched <- fetcher.tryAcquire
          _ <- IO(assertEquals(refetched.map(_.headers), Some(headers)))
        yield ()
      }
    yield ()
  }
