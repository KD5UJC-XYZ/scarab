// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.NonEmptyVector
import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.Files
import scala.collection.immutable.SortedMap
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.leios.{
  AnnouncementFields,
  BytesSize,
  EbFetchState,
  ElId,
  IsBigLedgerPeer,
  LeiosFetchRequest,
  LeiosFetchStaticEnv,
  LeiosJobPool,
  LeiosOutstanding,
  PeerOffer,
  StdGen,
  WhetherTxsClosureOffered
}
import xyz.kd5ujc.scarab.consensus.leios.LeiosOrders.given
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64
import xyz.kd5ujc.scarab.storage.{LeiosEb, LeiosStoreLocks, LeiosStoreSuite, RocksLeiosStore}

/** The node's Leios fetch logic (ouroboros-consensus 2416493b `NodeKernel.hs` 543-650, 715-735 and
  * 925-955): one decision queues each live peer's requests and drops its dead offers, the loop
  * decides once per wake at most every half second, the state and the peers' offers and claims are
  * pruned at the immutable tip, and the state starts from the store's complete closures.
  */
class LeiosFetchKernelSuite extends FailsOnUncaught:
  import LeiosStoreSuite.{ok, allowing, word32, slot, hash}

  private val env = LeiosFetchStaticEnv.demo
  private val issuer = Bytes(Vector.fill(28)(5.toByte))
  private val offered = BytesSize.wrap(1000)
  private val noForecast: IO[Word64 => Either[Unit, BytesSize]] = IO.pure(_ => Left(()))

  private def offer(closure: WhetherTxsClosureOffered) =
    PeerOffer(Some(BytesSize.wrap(100000)), Some(offered), closure)

  /** Endorser block `i` announced at slot `s` and focused by its election. */
  private def focused(o: LeiosOutstanding[Int], s: Long, i: Int): LeiosOutstanding[Int] =
    o.announced(AnnouncementFields(ElId(slot(s), issuer), hash(i), offered), None)._1

  private def empty: LeiosOutstanding[Int] = LeiosOutstanding.empty[Int](StdGen.mk(4), slot(0))

  private def withPeers(
      initial: LeiosOutstanding[Int],
      offers: Map[Int, Map[EbPoint, PeerOffer]]
  ): IO[LeiosFetchShared[IO, Int]] =
    LeiosFetchShared.create[IO, Int](initial).flatTap { shared =>
      offers.toVector.traverse_ { (p, os) =>
        LeiosPeerVars
          .create[IO](IsBigLedgerPeer.NotBigLedgerPeer)
          .flatTap(_.offerings.set(SortedMap.from(os)))
          .flatMap(vars => shared.peers.update(_.updated(p, vars)))
      }
    }

  /** The requests queued for peer `p`, taken. */
  private def queued(shared: LeiosFetchShared[IO, Int], p: Int): IO[Vector[LeiosFetchRequest]] =
    shared.peers.get.flatMap(
      _.get(p).fold(IO.pure(Vector.empty[LeiosFetchRequest]))(vars =>
        Vector
          .empty[LeiosFetchRequest]
          .tailRecM(taken =>
            vars.requestsToSend.tryTake.map {
              case Some(LeiosRequestsToSend.Taken.Request(r))  => Left(taken :+ r)
              case Some(LeiosRequestsToSend.Taken.Stop) | None => Right(taken)
            }
          )
      )
    )

  test("a decision queues a live peer's requests and drops the offers it finds dead") {
    val fresh = EbPoint(slot(10), hash(1))
    val held = EbPoint(slot(11), hash(2))
    val initial = focused(focused(empty, 10, 1), 11, 2).acquireEbBody(held.hash, LeiosJobPool.empty)
    for
      shared <- withPeers(
        initial,
        Map(
          1 -> Map(fresh -> offer(WhetherTxsClosureOffered.Offered)),
          2 -> Map(held -> offer(WhetherTxsClosureOffered.Offered))
        )
      )
      _ <- LeiosFetchKernel.step(shared, env, IO.pure(Some(slot(11))), noForecast)
      first <- queued(shared, 1)
      second <- queued(shared, 2)
      offers <- shared.peers.get.flatMap(
        _.toVector.traverse((p, v) => v.offerings.get.map(p -> _.keySet))
      )
      after <- shared.outstanding.get
    yield
      assertEquals(
        first,
        Vector(LeiosFetchRequest.Block(fresh, offered, BytesSize.wrap(100000)))
      )
      assertEquals(second, Vector.empty)
      // the offer of a body already held with nothing left to fetch is dead; the other is kept
      assertEquals(offers.toMap, Map(1 -> Set(fresh), 2 -> Set.empty[EbPoint]))
      assertEquals(after.requestedEbPeers.get(fresh.hash).map(_.toSortedSet.toSet), Some(Set(1)))
      assertEquals(after.requestedBytesSizePerPeer.get(1), Some(offered))
  }

  test("the loop decides once per wake, at most once every half second") {
    TestControl.executeEmbed {
      for
        shared <- withPeers(empty, Map.empty)
        times <- Ref.of[IO, Vector[FiniteDuration]](Vector.empty)
        first <- Deferred[IO, Unit]
        second <- Deferred[IO, Unit]
        slotRead = IO.monotonic
          .flatMap(now =>
            times
              .updateAndGet(_ :+ now)
              .flatMap(ts =>
                first
                  .complete(())
                  .whenA(ts.sizeIs == 1) *> second.complete(()).whenA(ts.sizeIs == 2)
              )
          )
          .as(Option(slot(1)))
        result <- LeiosFetchKernel
          .run(shared, env, slotRead, noForecast, Stream.empty, IO.raiseError)
          .surround(
            IO.monotonic.flatMap(start =>
              // woken again as soon as the first decision is made: the second waits out the pace
              shared.wake *> first.get *> shared.wake *> second.get *> times.get
                .map(_.map(_ - start))
            )
          )
      yield assertEquals(result, Vector(Duration.Zero, LeiosFetchKernel.LoopInterval))
    }
  }

  test("an exception in the decision loop goes to the node's sink, which stops the node") {
    val boom = new IllegalStateException("the forecast failed")
    for
      shared <- withPeers(empty, Map.empty)
      stopped <- Deferred[IO, Throwable]
      seen <- LeiosFetchKernel
        .run[IO, Int, Unit](
          shared,
          env,
          IO.pure(None),
          IO.raiseError(boom),
          Stream.empty,
          stopped.complete(_).void
        )
        .surround(shared.wake *> stopped.get)
    yield assertEquals(seen, boom)
  }

  test("the state, the offers and the claims are pruned below the immutable tip") {
    val old = EbPoint(slot(5), hash(1))
    val kept = EbPoint(slot(20), hash(2))
    for
      shared <- withPeers(
        focused(focused(empty, 5, 1), 20, 2),
        Map(
          1 -> Map(
            old -> offer(WhetherTxsClosureOffered.Offered),
            kept -> offer(WhetherTxsClosureOffered.Offered)
          )
        )
      )
      vars <- shared.peers.get.map(_(1))
      _ <- vars.certificationClaims.set(
        SortedMap(ElId(slot(5), issuer) -> old.hash, ElId(slot(20), issuer) -> kept.hash)
      )
      pruned <- Deferred[IO, Unit]
      _ <- LeiosFetchKernel
        .run(
          shared,
          env,
          IO.pure(None),
          noForecast,
          Stream.emit(BigInt(10)) ++ Stream.exec(pruned.complete(()).void),
          IO.raiseError
        )
        .surround(pruned.get)
      after <- shared.outstanding.get
      offers <- vars.offerings.get
      claims <- vars.certificationClaims.get
    yield
      assertEquals(after.ebState.keySet, Set(kept.hash))
      assertEquals(after.outstandingPrunedSlot, slot(10))
      assertEquals(offers.keySet, Set(kept))
      assertEquals(claims.keySet, Set(ElId(slot(20), issuer)))
  }

  private val dirs = ResourceFunFixture(Files.forAsync[IO].tempDirectory)

  dirs.test("the state starts from the store's complete closures at or after the immutable tip") {
    dir =>
      val txs = Vector.tabulate(2)(LeiosStoreSuite.tx)
      val eb = LeiosStoreSuite.eb(
        NonEmptyVector.fromVectorUnsafe(txs.map(t => LeiosEb.hashTx(t) -> word32(t.size.toLong)))
      )
      val h: EbHash = LeiosEb.hashOf(eb.transactions.toVector)
      val points = Vector(EbPoint(slot(3), h), EbPoint(slot(12), h))
      LeiosStoreLocks
        .resource[IO]
        .flatMap(locks => allowing(RocksLeiosStore.resource[IO](dir / "leios", locks)))
        .use { store =>
          for
            _ <- ok(points.traverse_ { p =>
              store.writeEbPoint(p, word32(eb.encoded.size.toLong)) *>
                store.writeEbBody(p, eb, Vector.empty) *>
                store.writeTxs(p, txs.zipWithIndex.map((t, i) => i -> t))
            })
            o <- ok(LeiosFetchKernel.initialized[IO, Int](store, StdGen.mk(4), slot(10)))
          yield
            assertEquals(o.outstandingPrunedSlot, slot(10))
            assertEquals(o.ebState.get(h).map(_.slot), Some(slot(12)))
            assertEquals(
              o.ebState.get(h).map(_.fetch),
              Some(EbFetchState.BodyAcquired(LeiosJobPool.empty))
            )
        }
  }
