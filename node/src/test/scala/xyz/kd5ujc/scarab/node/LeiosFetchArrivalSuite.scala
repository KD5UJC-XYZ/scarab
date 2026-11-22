// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.NonEmptyVector
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.{Queue, Semaphore}
import cats.effect.testkit.TestControl
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.leios.{
  AnnouncementFields,
  BytesSize,
  EbFetchState,
  ElId,
  IsBigLedgerPeer,
  LeiosFetchLogic,
  LeiosFetchRequest,
  LeiosFetchStaticEnv,
  LeiosOutstanding,
  PeerOffer,
  StdGen,
  WhetherTxsClosureOffered
}
import xyz.kd5ujc.scarab.consensus.leios.LeiosOrders.given
import xyz.kd5ujc.scarab.network.{
  FailsOnUncaught,
  Fibers,
  LeiosFetchJob,
  LeiosFetchPipelined,
  LeiosFetchRequestHandler,
  LeiosFetchServer,
  NetworkError,
  Peers,
  Protocols
}
import xyz.kd5ujc.scarab.protocol.{EbPoint, LeiosFetch, LeiosFetchFault, LeiosTxHash, TxBitmaps}
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word32, Word64}
import xyz.kd5ujc.scarab.storage.{
  BodyWritten,
  LeiosEb,
  LeiosEbNotification,
  LeiosStore,
  LeiosStoreError,
  LeiosStoreLocks,
  LeiosStoreSuite,
  LocalFill,
  PointWritten,
  RocksLeiosStore
}

import Peers.*

/** What the node does with a peer's LeiosFetch answers (`processLeiosBlock` and
  * `processLeiosBlockTxs`, ouroboros-consensus 2416493b `LeiosDemoLogic.hs` 849-1172 and
  * 1395-1588): a body checked, accounted for, written and held with its closure's jobs; the
  * closure's transactions checked against their jobs, written and retired; each refusal of the peer
  * in the reference's order, with what the state and the store hold after it; and the store's
  * failure as the node's own.
  */
class LeiosFetchArrivalSuite extends FailsOnUncaught:
  import LeiosStoreSuite.{ok, allowing, word32, slot, tx}

  private val dirs = ResourceFunFixture(Files.forAsync[IO].tempDirectory)
  private val env = LeiosFetchStaticEnv.demo
  private val peer = 1
  private val issuer = Bytes(Vector.fill(28)(5.toByte))

  private def body(txs: Vector[Bytes]): LeiosEb =
    LeiosStoreSuite.eb(
      NonEmptyVector.fromVectorUnsafe(txs.map(t => LeiosEb.hashTx(t) -> word32(t.size.toLong)))
    )
  private def size(eb: LeiosEb): BytesSize = BytesSize.wrap(eb.encoded.size.toLong)
  private def closure(eb: LeiosEb): BytesSize =
    BytesSize.wrap(eb.transactions.toVector.map(_._2.toLong).sum)
  private def pointOf(eb: LeiosEb, at: Long): EbPoint =
    EbPoint(slot(at), LeiosEb.hashOf(eb.transactions.toVector))

  /** The state, from `from`, that has asked the peer for `eb` 's body announced at slot `at`,
    * offered at `offered` and bounded by `bound`, as the decision asks: an announcement focuses it
    * and the peer offers it.
    */
  private def asked(
      eb: LeiosEb,
      offered: BytesSize,
      bound: BytesSize,
      from: LeiosOutstanding[Int] = LeiosOutstanding.empty[Int](StdGen.mk(4), slot(0)),
      at: Long = 10
  ): (LeiosOutstanding[Int], LeiosFetchRequest.Block) =
    val p = pointOf(eb, at)
    val (announced, _) =
      from.announced(AnnouncementFields(ElId(p.slot, issuer), p.hash, offered), None)
    val decision = LeiosFetchLogic.iteration[Int, Unit](
      env,
      _ => Left(()),
      Some(p.slot),
      Map(
        peer -> Map(p -> PeerOffer(Some(bound), Some(offered), WhetherTxsClosureOffered.Offered))
      ),
      Map.empty,
      announced
    )
    decision.requests.get(peer).map(_.toVector) match
      case Some(Vector(block: LeiosFetchRequest.Block)) => (decision.outstanding, block)
      case other                                        => fail(s"not one body request: $other")

  /** The next decision's transactions requests to the peer, and the state after it. */
  private def closureRequests(
      o: LeiosOutstanding[Int],
      p: EbPoint
  ): (LeiosOutstanding[Int], Vector[LeiosFetchRequest.BlockTxs]) =
    val decision = LeiosFetchLogic.iteration[Int, Unit](
      env,
      _ => Left(()),
      Some(p.slot),
      Map(peer -> Map(p -> PeerOffer(None, None, WhetherTxsClosureOffered.Offered))),
      Map.empty,
      o
    )
    (
      decision.outstanding,
      decision.requests.get(peer).fold(Vector.empty)(_.toVector).collect {
        case r: LeiosFetchRequest.BlockTxs => r
      }
    )

  private def stored(dir: Path): Resource[IO, RocksLeiosStore[IO]] =
    LeiosStoreLocks
      .resource[IO]
      .flatMap(locks => allowing(RocksLeiosStore.resource[IO](dir, locks)))

  /** The arrival over `store` notifying its completions, from `initial`, with what reached the
    * node's fatal sink.
    */
  private final case class Rig(
      arrival: LeiosFetchArrival[IO, Int],
      shared: LeiosFetchShared[IO, Int],
      acquired: LeiosAcquired[IO],
      fatal: Deferred[IO, LeiosStoreError],
      settled: Queue[IO, Unit]
  )

  private def rig(
      store: LeiosStore[IO],
      initial: LeiosOutstanding[Int],
      depth: Long = LeiosFetchArrival.WriterQueueDepth,
      pull: LeiosMempoolPull[IO] = LeiosMempoolPull.none[IO],
      limits: LeiosFetchStaticEnv = env
  ): IO[Rig] =
    for
      shared <- LeiosFetchShared.create[IO, Int](initial)
      slots <- Semaphore[IO](depth)
      acquired <- LeiosAcquired.create[IO]
      fatal <- Deferred[IO, LeiosStoreError]
      // each write and its claim on a fiber of its own, as the node's supervisor runs it; its end
      // is what a test waits for
      settled <- Queue.unbounded[IO, Unit]
    yield Rig(
      LeiosFetchArrival(
        shared,
        acquired.notifying(store),
        limits,
        e => fatal.complete(e).void,
        settling => settling.guarantee(settled.offer(())).start.void,
        slots,
        pull
      ),
      shared,
      acquired,
      fatal,
      settled
    )

  private def fault(f: LeiosFetchFault): Either[NetworkError, Unit] =
    Left(NetworkError.LeiosFetchInvalidReply(f))

  private def held(o: LeiosOutstanding[Int], p: EbPoint): Option[EbFetchState] =
    o.ebState.get(p.hash).map(_.fetch)

  dirs.test("a body is written and held with its closure's jobs, then its closure completes") {
    dir =>
      val txs = Vector.tabulate(70)(tx)
      val eb = body(txs)
      val (asking, request) = asked(eb, size(eb), closure(eb))
      val p = request.point
      stored(dir / "leios").use { store =>
        for
          r <- rig(store, asking)
          bodyResult <- typed(r.arrival.block(peer, request, eb.transactions.toVector))
          _ <- r.settled.take
          afterBody <- r.shared.outstanding.get
          body <- ok(store.lookupEbBody(p.hash))
          (deciding, requests) = closureRequests(afterBody, p)
          _ <- r.shared.outstanding.set(deciding)
          txsResults <- requests.traverse(req =>
            typed(r.arrival.blockTxs(peer, req, offsetsOf(req).map(txs))) <* r.settled.take
          )
          after <- r.shared.outstanding.get
          completed <- r.acquired.next
          whole <- ok(store.lookupEbClosure(p.hash))
          woken <- r.shared.ready.tryTake
        yield
          assertEquals(bodyResult, Right(()))
          assertEquals(body, Some(eb.encoded))
          // the peer's bytes for the body are released, the body held with jobs over every offset
          assert(!afterBody.requestedBytesSizePerPeer.contains(peer))
          held(afterBody, p) match
            case Some(EbFetchState.BodyAcquired(pool, _)) => assert(!pool.isEmpty)
            case other                                    => fail(s"not acquired: $other")
          assert(requests.nonEmpty)
          assertEquals(txsResults, requests.map(_ => Right(())))
          held(after, p) match
            case Some(EbFetchState.BodyAcquired(pool, _)) => assert(pool.isEmpty)
            case other                                    => fail(s"not acquired: $other")
          assert(after.requestedJobsPerPeer.isEmpty)
          assert(!after.requestedBytesSizePerPeer.contains(peer))
          assertEquals(completed, p)
          assertEquals(whole.map(_.map(_._2)), Some(txs))
          assertEquals(woken, Some(()))
      }
  }

  /** The mempool's index of `txs`: each by the hash an endorser block lists it by. */
  private def indexOf(txs: Vector[Bytes]): Map[LeiosTxHash, Bytes] =
    txs.map(t => LeiosEb.hashTx(t) -> t).toMap

  /** A mempool's index of `txs`, validated under the even parity. */
  private def indexed(txs: Map[LeiosTxHash, Bytes]): xyz.kd5ujc.scarab.node.mempool.LeiosTxIndex =
    xyz.kd5ujc.scarab.node.mempool.LeiosTxIndex(
      txs,
      Some(xyz.kd5ujc.scarab.consensus.leios.ProtVerParity.Even)
    )

  private def mempoolOf(txs: Vector[Bytes]): LeiosMempoolPull[IO] =
    LeiosMempoolPull.of(IO.pure(indexed(indexOf(txs))))

  dirs.test("a body whose closure the mempool holds is acquired with it, and nothing is fetched") {
    dir =>
      val txs = Vector.tabulate(70)(tx)
      val eb = body(txs)
      val (asking, request) = asked(eb, size(eb), closure(eb))
      val p = request.point
      stored(dir / "leios").use { rocks =>
        for
          log <- Ref.of[IO, Vector[Written]](Vector.empty)
          r <- rig(new Recording(rocks, log), asking, pull = mempoolOf(txs))
          result <- r.acquired.notifications.subscribe.use(published =>
            typed(r.arrival.block(peer, request, eb.transactions.toVector)) <* r.settled.take
              <* published.take.iterateUntil(_ == LeiosEbNotification.AcquiredEbTxs(p))
          )
          after <- r.shared.outstanding.get
          completed <- r.acquired.next
          whole <- ok(rocks.lookupEbClosure(p.hash))
          writes <- log.get
        yield
          assertEquals(result, Right(()))
          // held with nothing to fetch: the next decision asks the peer for no transaction
          held(after, p) match
            case Some(EbFetchState.BodyAcquired(pool, _)) => assert(pool.isEmpty)
            case other                                    => fail(s"not acquired: $other")
          assertEquals(closureRequests(after, p)._2, Vector.empty)
          assertEquals(completed, p)
          assertEquals(whole.map(_.map(_._2)), Some(txs))
          // the reference's writer's order: the point, the body, then the mempool's transactions
          assertEquals(
            writes,
            Vector(Written.Point, Written.Body, Written.Txs(txs.indices.toVector))
          )
      }
  }

  dirs.test(
    "the mempool's transactions are in the transaction cache, applied under its parity, when the write that completes the closure is published"
  ) { dir =>
    val txs = Vector.tabulate(5)(tx)
    val eb = body(txs)
    val (asking, request) = asked(eb, size(eb), closure(eb))
    val p = request.point
    stored(dir / "leios").use { rocks =>
      for
        log <- Ref.of[IO, Vector[Written]](Vector.empty)
        r <- rig(new Recording(rocks, log), asking, pull = mempoolOf(txs))
        // the announcement, as the node takes it before the body (`recordAnnouncementInTxCache`)
        _ <- r.shared.txCache.insertAnnouncement(
          p.slot.toBigInt,
          xyz.kd5ujc.scarab.crypto.bls.BlsFixtures.rbHash(3),
          p.hash
        )
        // read as the completion is published, before the arrival settles: a vote that the
        // completion wakes finds them tagged (the reference's TODO, `LeiosDemoLogic.hs` 1560)
        found <- r.acquired.notifications.subscribe.use(published =>
          typed(r.arrival.block(peer, request, eb.transactions.toVector)).background.use(_ =>
            published.take.iterateUntil(_ == LeiosEbNotification.AcquiredEbTxs(p)) *>
              r.shared.txCache.lookup <* r.settled.take
          )
        )
        index <- r.shared.txCache.snapshot
      yield
        assertEquals(
          eb.transactions.toVector.map(e => found(e._1)),
          Vector.fill(5)(Some(Right(xyz.kd5ujc.scarab.consensus.leios.ProtVerParity.Even)))
        )
        assertEquals(index.lookupBody(p.hash), Some(eb))
    }
  }

  dirs.test(
    "only the transactions the mempool misses become jobs, and are fetched to complete it"
  ) { dir =>
    val txs = Vector.tabulate(70)(tx)
    val eb = body(txs)
    val (asking, request) = asked(eb, size(eb), closure(eb))
    val p = request.point
    val (inPool, missed) = txs.indices.toVector.partition(_ % 3 != 0)
    stored(dir / "leios").use { rocks =>
      for
        log <- Ref.of[IO, Vector[Written]](Vector.empty)
        r <- rig(new Recording(rocks, log), asking, pull = mempoolOf(inPool.map(txs)))
        bodyResult <- typed(r.arrival.block(peer, request, eb.transactions.toVector))
        _ <- r.settled.take
        afterBody <- r.shared.outstanding.get
        (deciding, requests) = closureRequests(afterBody, p)
        _ <- r.shared.outstanding.set(deciding)
        txsResults <- requests.traverse(req =>
          typed(r.arrival.blockTxs(peer, req, offsetsOf(req).map(txs))) <* r.settled.take
        )
        completed <- r.acquired.next
        whole <- ok(rocks.lookupEbClosure(p.hash))
        writes <- log.get
      yield
        assertEquals(bodyResult, Right(()))
        // the jobs cover the misses, and nothing the mempool gave
        assertEquals(requests.flatMap(offsetsOf).sorted, missed)
        assertEquals(txsResults, requests.map(_ => Right(())))
        assertEquals(completed, p)
        assertEquals(whole.map(_.map(_._2)), Some(txs))
        assertEquals(
          writes.take(3),
          Vector(Written.Point, Written.Body, Written.Txs(inPool))
        )
    }
  }

  dirs.test("a body that is not the one asked for is refused before anything is accounted") { dir =>
    val eb = body(Vector.tabulate(3)(tx))
    val other = body(Vector.tabulate(4)(tx))
    val (asking, request) = asked(eb, size(eb), closure(eb))
    stored(dir / "leios").use { store =>
      for
        r <- rig(store, asking)
        result <- typed(r.arrival.block(peer, request, other.transactions.toVector))
        after <- r.shared.outstanding.get
        body <- ok(store.lookupEbBody(request.point.hash))
      yield
        assertEquals(
          result,
          fault(
            LeiosFetchFault.BodyHashMismatch(
              request.point,
              LeiosEb.hashOf(other.transactions.toVector)
            )
          )
        )
        assertEquals(after, asking)
        assertEquals(body, None)
    }
  }

  dirs.test("a body that lists a transaction twice is refused before anything is accounted") {
    dir =>
      val eb = body(Vector(tx(0), tx(1), tx(0), tx(2), tx(1)))
      val (asking, request) = asked(eb, size(eb), closure(eb))
      stored(dir / "leios").use { store =>
        for
          r <- rig(store, asking)
          result <- typed(r.arrival.block(peer, request, eb.transactions.toVector))
          after <- r.shared.outstanding.get
          body <- ok(store.lookupEbBody(request.point.hash))
        yield
          val twice = Vector(tx(0), tx(1))
            .map(LeiosEb.hashTx)
            .sortWith((a, b) => a.bytes.compareTo(b.bytes) < 0)
          assertEquals(result, fault(LeiosFetchFault.DuplicateTxHashes(request.point, twice)))
          assertEquals(after, asking)
          assertEquals(body, None)
      }
  }

  dirs.test("a body of another size than offered is written and held, and then refused") { dir =>
    val eb = body(Vector.tabulate(3)(tx))
    val offered = size(eb) + BytesSize.wrap(1)
    val (asking, request) = asked(eb, offered, closure(eb))
    stored(dir / "leios").use { store =>
      for
        r <- rig(store, asking)
        result <- typed(r.arrival.block(peer, request, eb.transactions.toVector))
        _ <- r.settled.take
        after <- r.shared.outstanding.get
        body <- ok(store.lookupEbBody(request.point.hash))
      yield
        assertEquals(
          result,
          fault(
            LeiosFetchFault.BlockWrongSize(
              request.point,
              Word32.wrap(offered.toLong),
              Word32.wrap(size(eb).toLong)
            )
          )
        )
        assertEquals(body, Some(eb.encoded))
        assert(held(after, request.point).exists(_ != EbFetchState.NoBody))
        assert(!after.requestedBytesSizePerPeer.contains(peer))
    }
  }

  dirs.test("a body over its closure's bound is held with nothing to fetch, never written") { dir =>
    val eb = body(Vector.tabulate(3)(tx))
    val bound = closure(eb) - BytesSize.wrap(1)
    val (asking, request) = asked(eb, size(eb), bound)
    stored(dir / "leios").use { store =>
      for
        r <- rig(store, asking)
        result <- typed(r.arrival.block(peer, request, eb.transactions.toVector))
        after <- r.shared.outstanding.get
        body <- ok(store.lookupEbBody(request.point.hash))
      yield
        assertEquals(
          result,
          fault(
            LeiosFetchFault.ClosureTooBig(
              request.point,
              closure(eb).toLong,
              Word32.wrap(bound.toLong)
            )
          )
        )
        assertEquals(body, None)
        held(after, request.point) match
          case Some(EbFetchState.BodyAcquired(pool, _)) => assert(pool.isEmpty)
          case other                                    => fail(s"not held: $other")
    }
  }

  dirs.test("the wrong size is refused before the bound, even for a body already held") { dir =>
    val eb = body(Vector.tabulate(3)(tx))
    val offered = size(eb) + BytesSize.wrap(2)
    val (asking, request) = asked(eb, offered, closure(eb) - BytesSize.wrap(1))
    stored(dir / "leios").use { store =>
      for
        r <- rig(store, asking)
        first <- typed(r.arrival.block(peer, request, eb.transactions.toVector))
        again <- typed(r.arrival.block(peer, request, eb.transactions.toVector))
      yield
        val wrong = fault(
          LeiosFetchFault.BlockWrongSize(
            request.point,
            Word32.wrap(offered.toLong),
            Word32.wrap(size(eb).toLong)
          )
        )
        assertEquals(first, wrong)
        assertEquals(again, wrong)
    }
  }

  /** A body held with its jobs, and the decision's transactions requests for them. */
  private def acquired(
      r: Rig,
      eb: LeiosEb,
      request: LeiosFetchRequest.Block
  ): IO[Vector[LeiosFetchRequest.BlockTxs]] =
    (typed(r.arrival.block(peer, request, eb.transactions.toVector)) <* r.settled.take).flatMap {
      result =>
        assertEquals(result, Right(()))
        r.shared.outstanding.get.flatMap { o =>
          val (deciding, requests) = closureRequests(o, request.point)
          r.shared.outstanding.set(deciding).as(requests)
        }
    }

  private def offsetsOf(req: LeiosFetchRequest.BlockTxs): Vector[Int] =
    req.jobs.toSortedMap.values.flatMap(_.offsets.toSortedSet).toVector.sorted

  dirs.test("transactions that are not what the jobs commit to are refused, in the checks' order") {
    dir =>
      val txs = Vector.tabulate(5)(tx)
      val eb = body(txs)
      val (asking, request) = asked(eb, size(eb), closure(eb))
      stored(dir / "leios").use { store =>
        for
          r <- rig(store, asking)
          requests <- acquired(r, eb, request)
          req = requests.head
          sent = offsetsOf(req).map(txs)
          before <- r.shared.outstanding.get
          short <- typed(r.arrival.blockTxs(peer, req, sent.dropRight(1)))
          resized <- typed(r.arrival.blockTxs(peer, req, sent.updated(0, tx(7))))
          tampered <- typed(
            r.arrival.blockTxs(peer, req, sent.updated(0, LeiosStoreSuite.sameSize(sent(0))))
          )
          after <- r.shared.outstanding.get
          closure <- ok(store.batchRetrieveTxs(req.point.hash, offsetsOf(req)))
        yield
          val job = req.jobs.toSortedMap.keys.head
          assertEquals(
            short,
            fault(LeiosFetchFault.TxsCountMismatch(req.point, sent.size - 1, sent.size))
          )
          assertEquals(resized, fault(LeiosFetchFault.JobByteSizeMismatch(req.point, job)))
          assertEquals(tampered, fault(LeiosFetchFault.JobRootHashMismatch(req.point, job)))
          assertEquals(after, before)
          assert(closure.forall((_, t) => !LeiosEb.written(t)))
      }
  }

  dirs.test("transactions of jobs another peer fetched are taken without a write, and refunded") {
    dir =>
      val txs = Vector.tabulate(5)(tx)
      val eb = body(txs)
      val (asking, request) = asked(eb, size(eb), closure(eb))
      val second = 2
      stored(dir / "leios").use { store =>
        for
          r <- rig(store, asking)
          _ <- typed(r.arrival.block(peer, request, eb.transactions.toVector)) <* r.settled.take
          o <- r.shared.outstanding.get
          // both peers offer the closure: each is asked for the same jobs
          decision = LeiosFetchLogic.iteration[Int, Unit](
            env,
            _ => Left(()),
            Some(request.point.slot),
            Map(
              peer -> Map(request.point -> PeerOffer(None, None, WhetherTxsClosureOffered.Offered)),
              second -> Map(
                request.point -> PeerOffer(None, None, WhetherTxsClosureOffered.Offered)
              )
            ),
            Map.empty,
            o
          )
          _ <- r.shared.outstanding.set(decision.outstanding)
          reqs = (peerId: Int) =>
            decision.requests.get(peerId).fold(Vector.empty)(_.toVector).collect {
              case t: LeiosFetchRequest.BlockTxs => t
            }
          first <- reqs(peer).traverse(t =>
            typed(r.arrival.blockTxs(peer, t, offsetsOf(t).map(txs))) <* r.settled.take
          )
          between <- r.shared.outstanding.get
          again <- reqs(second).traverse(t =>
            typed(r.arrival.blockTxs(second, t, offsetsOf(t).map(txs))) <* r.settled.take
          )
          after <- r.shared.outstanding.get
        yield
          assert(reqs(second).nonEmpty)
          assertEquals(first, reqs(peer).map(_ => Right(())))
          assert(between.requestedJobsPerPeer.contains(second))
          assert(between.requestedBytesSizePerPeer.contains(second))
          assertEquals(again, reqs(second).map(_ => Right(())))
          assert(after.requestedJobsPerPeer.isEmpty)
          assert(after.requestedBytesSizePerPeer.isEmpty)
      }
  }

  /** Jobs of at most two transactions: a closure of four is two jobs, which one request covers. */
  private val pairs = env.copy(maxJobTxCount = 2)

  /** `eb` 's body, held by `r` from its announcement at slot 10, pruned with the immutable tip at
    * slot 11 and announced again at slot 20; `stock` fills the mempool, and the body arrives again.
    * Its new request is returned.
    */
  private def reacquired(r: Rig, eb: LeiosEb, stock: IO[Unit]): IO[LeiosFetchRequest.Block] =
    for
      _ <- r.shared.outstanding.update(_.prunedToImmTip(slot(11))._2)
      pruned <- r.shared.outstanding.get
      (reasking, again) = asked(eb, size(eb), closure(eb), pruned, 20)
      _ <- r.shared.outstanding.set(reasking)
      _ <- stock
      result <- typed(r.arrival.block(peer, again, eb.transactions.toVector)) <* r.settled.take
    yield
      assertEquals(result, Right(()))
      again

  dirs.test("a reply to a pool since replaced retires none of the new pool's jobs, left to fetch") {
    dir =>
      // the request for both jobs is in flight while the body is pruned and announced again; the
      // mempool now holds the first job's transactions, so the new pool's job 0 is the old job 1's
      val txs = Vector.tabulate(4)(tx)
      val eb = body(txs)
      val (asking, request) = asked(eb, size(eb), closure(eb))
      stored(dir / "leios").use { rocks =>
        TestControl.executeEmbed(
          for
            log <- Ref.of[IO, Vector[Written]](Vector.empty)
            index <- Ref.of[IO, Map[LeiosTxHash, Bytes]](Map.empty)
            r <- rig(
              new Recording(rocks, log),
              asking,
              pull = LeiosMempoolPull.of(index.get.map(indexed)),
              limits = pairs
            )
            stale <- acquired(r, eb, request)
            again <- reacquired(r, eb, index.set(indexOf(txs.take(2))))
            replaced <- r.shared.outstanding.get
            _ <- log.set(Vector.empty)
            late <- stale.traverse(req =>
              typed(r.arrival.blockTxs(peer, req, offsetsOf(req).map(txs))) <* r.settled.take
            )
            afterLate <- r.shared.outstanding.get
            lateWrites <- log.get
            (deciding, fresh) = closureRequests(afterLate, again.point)
            _ <- r.shared.outstanding.set(deciding)
            fetched <- fresh.traverse(req =>
              typed(r.arrival.blockTxs(peer, req, offsetsOf(req).map(txs))) <* r.settled.take
            )
            after <- r.shared.outstanding.get
            whole <- ok(rocks.lookupEbClosure(again.point.hash))
          yield
            assertEquals(stale.map(_.jobs.length), Vector(2), "one request covers both jobs")
            assertEquals(late, Vector(Right(())), "the peer answered what it was asked")
            assertEquals(lateWrites, Vector(Written.Txs(Vector.empty)), "nothing of it is ingested")
            this.held(replaced, again.point) match
              case Some(EbFetchState.BodyAcquired(pool, _)) => assert(!pool.isEmpty)
              case other                                    => fail(s"not acquired: $other")
            assertEquals(held(afterLate, again.point), held(replaced, again.point))
            assert(afterLate.requestedJobsPerPeer.isEmpty, "its jobs are no longer in flight")
            assert(!afterLate.requestedBytesSizePerPeer.contains(peer), "its bytes are refunded")
            assertEquals(fresh.flatMap(offsetsOf), Vector(2, 3))
            assertEquals(fetched, fresh.map(_ => Right(())))
            this.held(after, again.point) match
              case Some(EbFetchState.BodyAcquired(pool, _)) => assert(pool.isEmpty, "jobs retired")
              case other                                    => fail(s"not acquired: $other")
            assertEquals(whole.map(_.map(_._2)), Some(txs))
        )
      }
  }

  /** The requests the kernel queued for `vars`, taken. */
  private def queued(vars: LeiosPeerVars[IO]): IO[Vector[LeiosFetchRequest]] =
    Vector
      .empty[LeiosFetchRequest]
      .tailRecM(taken =>
        vars.requestsToSend.tryTake.map {
          case Some(LeiosRequestsToSend.Taken.Request(r))  => Left(taken :+ r)
          case Some(LeiosRequestsToSend.Taken.Stop) | None => Right(taken)
        }
      )

  dirs.test(
    "a peer that owes a replaced pool's jobs is asked at once for the new pool's same-numbered job"
  ) { dir =>
    // the peer still owes the old pool's jobs 0 and 1 and is the only one to offer the closure
    // again; the new pool's job 0 is another job, and the kernel's decision asks the peer for it
    // before the late reply comes, so no new offer is needed once the decision drops this one
    val txs = Vector.tabulate(4)(tx)
    val eb = body(txs)
    val (asking, request) = asked(eb, size(eb), closure(eb))
    stored(dir / "leios").use { rocks =>
      TestControl.executeEmbed(
        for
          index <- Ref.of[IO, Map[LeiosTxHash, Bytes]](Map.empty)
          r <- rig(
            rocks,
            asking,
            pull = LeiosMempoolPull.of(index.get.map(indexed)),
            limits = pairs
          )
          stale <- acquired(r, eb, request)
          again <- reacquired(r, eb, index.set(indexOf(txs.take(2))))
          vars <- LeiosPeerVars.create[IO](IsBigLedgerPeer.NotBigLedgerPeer)
          _ <- vars.offerings.set(
            SortedMap(again.point -> PeerOffer(None, None, WhetherTxsClosureOffered.Offered))
          )
          _ <- r.shared.peers.update(_.updated(peer, vars))
          _ <- LeiosFetchKernel.step(
            r.shared,
            pairs,
            IO.pure(Some(again.point.slot)),
            IO.pure((_: Word64) => Left(()))
          )
          fresh <- queued(vars).map(_.collect { case t: LeiosFetchRequest.BlockTxs => t })
          offersLeft <- vars.offerings.get
          late <- stale.traverse(req =>
            typed(r.arrival.blockTxs(peer, req, offsetsOf(req).map(txs))) <* r.settled.take
          )
          fetched <- fresh.traverse(req =>
            typed(r.arrival.blockTxs(peer, req, offsetsOf(req).map(txs))) <* r.settled.take
          )
          after <- r.shared.outstanding.get
          whole <- ok(rocks.lookupEbClosure(again.point.hash))
        yield
          assertEquals(fresh.flatMap(offsetsOf), Vector(2, 3), "the new pool's job is asked for")
          assertEquals(offersLeft, SortedMap.empty[EbPoint, PeerOffer], "its offer is spent")
          assertEquals(late, stale.map(_ => Right(())))
          assertEquals(fetched, fresh.map(_ => Right(())))
          this.held(after, again.point) match
            case Some(EbFetchState.BodyAcquired(pool, _)) => assert(pool.isEmpty, "jobs retired")
            case other                                    => fail(s"not acquired: $other")
          assert(after.requestedJobsPerPeer.isEmpty, "nothing is in flight")
          assertEquals(whole.map(_.map(_._2)), Some(txs), "the closure completes")
      )
    }
  }

  dirs.test("a reply whose write lands after its pool is replaced retires none of the new jobs") {
    dir =>
      // the reply is taken while its pool is current, both jobs pending; its write waits at the
      // gate while the body is pruned, announced again and held with a new pool
      val txs = Vector.tabulate(4)(tx)
      val eb = body(txs)
      val (asking, request) = asked(eb, size(eb), closure(eb))
      stored(dir / "leios").use { rocks =>
        TestControl.executeEmbed(
          for
            gate <- (Deferred[IO, Unit], Deferred[IO, Unit]).mapN(TxsGate.apply)
            first <- Ref.of[IO, Option[TxsGate]](Some(gate))
            index <- Ref.of[IO, Map[LeiosTxHash, Bytes]](Map.empty)
            r <- rig(
              new GatedTxs(rocks, first),
              asking,
              pull = LeiosMempoolPull.of(index.get.map(indexed)),
              limits = pairs
            )
            stale <- acquired(r, eb, request)
            replies <- stale.traverse(req =>
              typed(r.arrival.blockTxs(peer, req, offsetsOf(req).map(txs)))
            )
            _ <- gate.reached.get
            again <- reacquired(r, eb, index.set(indexOf(txs.take(2))))
            replaced <- r.shared.outstanding.get
            _ <- gate.open.complete(())
            _ <- r.settled.take
            landed <- r.shared.outstanding.get
            (deciding, fresh) = closureRequests(landed, again.point)
            _ <- r.shared.outstanding.set(deciding)
            fetched <- fresh.traverse(req =>
              typed(r.arrival.blockTxs(peer, req, offsetsOf(req).map(txs))) <* r.settled.take
            )
            after <- r.shared.outstanding.get
            whole <- ok(rocks.lookupEbClosure(again.point.hash))
          yield
            assertEquals(stale.map(_.jobs.length), Vector(2), "one request covers both jobs")
            assertEquals(replies, Vector(Right(())))
            this.held(replaced, again.point) match
              case Some(EbFetchState.BodyAcquired(pool, _)) => assert(!pool.isEmpty)
              case other                                    => fail(s"not acquired: $other")
            assertEquals(held(landed, again.point), held(replaced, again.point))
            assert(landed.requestedJobsPerPeer.isEmpty, "its jobs are no longer in flight")
            assert(!landed.requestedBytesSizePerPeer.contains(peer), "its bytes are refunded")
            assertEquals(fresh.flatMap(offsetsOf), Vector(2, 3))
            assertEquals(fetched, fresh.map(_ => Right(())))
            this.held(after, again.point) match
              case Some(EbFetchState.BodyAcquired(pool, _)) => assert(pool.isEmpty, "jobs retired")
              case other                                    => fail(s"not acquired: $other")
            assertEquals(whole.map(_.map(_._2)), Some(txs))
        )
      }
  }

  dirs.test("a job whose transactions the store drops is retired, its closure left incomplete") {
    dir =>
      // the body declares the first two sizes swapped, their sum the same, and lists a third
      // transaction whose bytes start with a zero byte: the job's size and root hash are the
      // body's, so the honest reply passes every check, and the store drops all three
      // (`sqlInsertTxs`, `Impl/SQLite/Insert.hs` 287-297 at 2416493b); the job is retired all the
      // same (`onDurable`, `LeiosDemoLogic.hs` 1532-1536 and 1575), as the reference retires it
      val txs = Vector(tx(0), tx(1), LeiosStoreSuite.zeroLed(tx(2)))
      val declared = Vector(txs(1).size, txs(0).size, txs(2).size)
      val eb = LeiosStoreSuite.eb(
        NonEmptyVector.fromVectorUnsafe(
          txs.zip(declared).map((t, n) => LeiosEb.hashTx(t) -> word32(n.toLong))
        )
      )
      val (asking, request) = asked(eb, size(eb), closure(eb))
      stored(dir / "leios").use { store =>
        for
          r <- rig(store, asking)
          requests <- acquired(r, eb, request)
          results <- requests.traverse(req =>
            typed(r.arrival.blockTxs(peer, req, offsetsOf(req).map(txs))) <* r.settled.take
          )
          after <- r.shared.outstanding.get
          held <- ok(store.batchRetrieveTxs(request.point.hash, txs.indices.toVector))
          whole <- ok(store.lookupEbClosure(request.point.hash))
        yield
          assert(requests.nonEmpty)
          assertEquals(results, requests.map(_ => Right(())), "the peer is not at fault")
          this.held(after, request.point) match
            case Some(EbFetchState.BodyAcquired(pool, _)) => assert(pool.isEmpty, "jobs retired")
            case other                                    => fail(s"not acquired: $other")
          assert(after.requestedJobsPerPeer.isEmpty)
          assert(!after.requestedBytesSizePerPeer.contains(peer))
          assert(held.forall((_, t) => !LeiosEb.written(t)), "nothing written")
          assertEquals(whole, None, "the closure is incomplete")
      }
  }

  dirs.test("the store's failure stops the node and is never the peer's fault") { dir =>
    val eb = body(Vector.tabulate(3)(tx))
    val (asking, request) = asked(eb, size(eb), closure(eb))
    stored(dir / "leios").use { store =>
      val failing = new Failing(store, LeiosStoreError.Closed)
      for
        r <- rig(failing, asking)
        // the answer is the peer's to give and it gave it; the write fails on the node's fiber,
        // and what reaches the node's sink is the store's error
        result <- typed(r.arrival.block(peer, request, eb.transactions.toVector))
        error <- r.fatal.get
        _ <- r.settled.take
        after <- r.shared.outstanding.get
      yield
        assertEquals(result, Right(()))
        assertEquals(error, LeiosStoreError.Closed)
        assertEquals(held(after, request.point), Some(EbFetchState.NoBody))
    }
  }

  dirs.test("a body is refused once its write is queued, and claimed once it lands") { dir =>
    val eb = body(Vector.tabulate(3)(tx))
    val offered = size(eb) + BytesSize.wrap(1)
    val (asking, request) = asked(eb, offered, closure(eb))
    stored(dir / "leios").use { store =>
      Deferred[IO, Unit].flatMap { gate =>
        val held = new Gated(store, gate)
        for
          r <- rig(held, asking)
          // the write waits at the gate: the peer answers for the size meanwhile
          result <- typed(r.arrival.block(peer, request, eb.transactions.toVector))
          before <- r.shared.outstanding.get
          _ <- gate.complete(())
          _ <- r.settled.take
          after <- r.shared.outstanding.get
          body <- ok(store.lookupEbBody(request.point.hash))
        yield
          assertEquals(
            result,
            fault(
              LeiosFetchFault.BlockWrongSize(
                request.point,
                Word32.wrap(offered.toLong),
                Word32.wrap(size(eb).toLong)
              )
            )
          )
          assertEquals(this.held(before, request.point), Some(EbFetchState.NoBody))
          assert(this.held(after, request.point).exists(_ != EbFetchState.NoBody))
          assertEquals(body, Some(eb.encoded))
      }
    }
  }

  dirs.test("a stalled write stops the arrival admitting answers until a slot is free") { dir =>
    // two slots: the first body takes both, its point's lands and frees one, and its body's waits
    // at the gate; a second body, which needs two, waits for a slot (`submitJob`'s backpressure)
    val eb = body(Vector.tabulate(3)(tx))
    val (asking, request) = asked(eb, size(eb), closure(eb))
    stored(dir / "leios").use { store =>
      for
        gate <- Deferred[IO, Unit]
        r <- rig(new Gated(store, gate), asking, depth = 2)
        returned <- Ref.of[IO, Vector[Either[NetworkError, Unit]]](Vector.empty)
        control <- TestControl.execute(
          typed(r.arrival.block(peer, request, eb.transactions.toVector))
            .flatMap(result => returned.update(_ :+ result))
            .replicateA_(2)
        )
        _ <- control.tick
        stalled <- returned.get
        waiting <- control.results
        _ <- gate.complete(())
        _ <- control.tick
        admitted <- returned.get
        ended <- control.results
        body <- ok(store.lookupEbBody(request.point.hash))
      yield
        assertEquals(stalled, Vector(Right(())), "the second answer waits for a slot")
        assertEquals(waiting, None)
        assertEquals(admitted, Vector(Right(()), Right(())), "admitted once the write lands")
        assert(ended.exists(_.isSuccess), s"the answers are taken: $ended")
        assertEquals(body, Some(eb.encoded))
    }
  }

  test("a transaction's bytes are the ones inside its tag 24, as the reference hashes them") {
    val inner = tx(3)
    val wire = LeiosFetch.encode(LeiosFetch.Message.BlockTxs(Vector(inner)))
    val decoded = wire.flatMap(LeiosFetch.decodePrefix(LeiosFetch.State.BlockTxs, _))
    // `[4, [24(bytes)]]`: the tag's content is the transaction's encoding
    val tagged = Vector(0xd8.toByte, 0x18.toByte, (0x40 + inner.size).toByte) ++ inner.toArray
    assert(wire.exists(_.toArray.toSeq.endsWith(tagged)))
    assertEquals(decoded.map(_._1), Right(LeiosFetch.Message.BlockTxs(Vector(inner))))
    // and its job's commitment is over the hash of those bytes (`hashLeiosTx = hashTxBytes . cbor`)
    decoded.map(_._1) match
      case Right(LeiosFetch.Message.BlockTxs(Vector(got))) =>
        assertEquals(LeiosEb.hashTx(got), LeiosEb.hashTx(inner))
      case other => fail(s"not decoded: $other")
  }

  dirs.test("a body refused in the client's receiver ends the client with the peer's fault") {
    dir =>
      val eb = body(Vector.tabulate(3)(tx))
      val other = body(Vector.tabulate(2)(tx))
      val (asking, request) = asked(eb, size(eb), closure(eb))
      val lying = new LeiosFetchRequestHandler[IO]:
        def block(point: EbPoint)(using Raise[IO, NetworkError]) =
          IO.pure(other.transactions.toVector)
        def blockTxs(point: EbPoint, bitmaps: TxBitmaps)(using Raise[IO, NetworkError]) =
          IO.pure(Vector.empty[Bytes])
      stored(dir / "leios").use { store =>
        rig(store, asking).flatMap { r =>
          typed(
            linked(protocols = Set(Protocols.LeiosFetch)).use { (c, s) =>
              for
                cc <- c.channel(Protocols.LeiosFetch)
                sc <- s.channel(Protocols.LeiosFetch)
                job = LeiosFetchJob
                  .Block[IO](request.point, txs => r.arrival.block(peer, request, txs))
                jobs <- Queue.unbounded[IO, LeiosFetchJob[IO]]
                _ <- jobs.offer(job)
                result <- Fibers
                  .scoped[IO, NetworkError, Unit](NetworkError.Defect(_))(
                    LeiosFetchServer.run(sc, lying)
                  )
                  .background
                  .surround(
                    typed(
                      LeiosFetchPipelined.run[IO, Unit](
                        cc,
                        jobs.tryTake.map(_.fold(Left(IO.never))(j => Right(Right(j))))
                      )
                    )
                  )
              yield result
            }
          ).map(result =>
            assertEquals(
              result,
              Right(
                fault(
                  LeiosFetchFault.BodyHashMismatch(
                    request.point,
                    LeiosEb.hashOf(other.transactions.toVector)
                  )
                )
              )
            )
          )
        }
      }
  }

  /** A store whose writes of a body fail with `error`. */
  private final class Failing(underlying: LeiosStore[IO], error: LeiosStoreError)
      extends LeiosStore[IO]:
    export underlying.{writeEbPoint, writeTxs, lookupEbBody, lookupEbClosure, batchRetrieveTxs}
    export underlying.{scanEbPoints, scanCompleteEbClosuresNotOlderThanSlot, garbageCollect}
    export underlying.promoteToImmutable
    def writeEbBody(point: EbPoint, body: LeiosEb, fills: Vector[LocalFill])(using
        Raise[IO, LeiosStoreError]
    ): IO[BodyWritten] = error.raise[IO, BodyWritten]

  /** A store whose writes of a body wait for `gate`. */
  private final class Gated(underlying: LeiosStore[IO], gate: Deferred[IO, Unit])
      extends LeiosStore[IO]:
    export underlying.{writeEbPoint, writeTxs, lookupEbBody, lookupEbClosure, batchRetrieveTxs}
    export underlying.{scanEbPoints, scanCompleteEbClosuresNotOlderThanSlot, garbageCollect}
    export underlying.promoteToImmutable
    def writeEbBody(point: EbPoint, body: LeiosEb, fills: Vector[LocalFill])(using
        Raise[IO, LeiosStoreError]
    ): IO[BodyWritten] = gate.get *> underlying.writeEbBody(point, body, fills)

  /** A gate of [[GatedTxs]]: completed when a write reaches it, and the write's wait. */
  private final case class TxsGate(reached: Deferred[IO, Unit], open: Deferred[IO, Unit])

  /** A store whose first write of transactions says it has reached the gate `first` holds, and
    * waits there until it opens.
    */
  private final class GatedTxs(underlying: LeiosStore[IO], first: Ref[IO, Option[TxsGate]])
      extends LeiosStore[IO]:
    export underlying.{writeEbPoint, writeEbBody, lookupEbBody, lookupEbClosure, batchRetrieveTxs}
    export underlying.{scanEbPoints, scanCompleteEbClosuresNotOlderThanSlot, garbageCollect}
    export underlying.promoteToImmutable
    def writeTxs(point: EbPoint, txs: Vector[(Int, Bytes)])(using
        Raise[IO, LeiosStoreError]
    ): IO[Vector[EbPoint]] =
      first.getAndSet(None).flatMap(_.traverse_(g => g.reached.complete(()) *> g.open.get)) *>
        underlying.writeTxs(point, txs)

  /** A write of [[Recording]]: a point, a body, or transactions at their offsets. */
  private enum Written derives CanEqual:
    case Point
    case Body
    case Txs(offsets: Vector[Int])

  /** A store that records its writes in the order they land. */
  private final class Recording(underlying: LeiosStore[IO], log: Ref[IO, Vector[Written]])
      extends LeiosStore[IO]:
    export underlying.{lookupEbBody, lookupEbClosure, batchRetrieveTxs}
    export underlying.{scanEbPoints, scanCompleteEbClosuresNotOlderThanSlot, garbageCollect}
    export underlying.promoteToImmutable
    def writeEbPoint(point: EbPoint, announced: Word32)(using
        Raise[IO, LeiosStoreError]
    ): IO[PointWritten] =
      underlying.writeEbPoint(point, announced) <* log.update(_ :+ Written.Point)
    def writeEbBody(point: EbPoint, body: LeiosEb, fills: Vector[LocalFill])(using
        Raise[IO, LeiosStoreError]
    ): IO[BodyWritten] = underlying.writeEbBody(point, body, fills) <* log.update(_ :+ Written.Body)
    def writeTxs(point: EbPoint, txs: Vector[(Int, Bytes)])(using
        Raise[IO, LeiosStoreError]
    ): IO[Vector[EbPoint]] =
      underlying.writeTxs(point, txs) <* log.update(_ :+ Written.Txs(txs.map(_._1)))
