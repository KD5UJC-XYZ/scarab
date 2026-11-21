// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.NonEmptyVector
import cats.effect.{Deferred, IO, Resource}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.leios.{
  AnnouncementFields,
  BytesSize,
  EbFetchState,
  ElId,
  LeiosFetchLogic,
  LeiosFetchRequest,
  LeiosFetchStaticEnv,
  LeiosOutstanding,
  PeerOffer,
  StdGen,
  WhetherTxsClosureOffered
}
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
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word32
import xyz.kd5ujc.scarab.storage.{
  BodyWritten,
  LeiosEb,
  LeiosStore,
  LeiosStoreError,
  LeiosStoreLocks,
  LeiosStoreSuite,
  LocalFill,
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

  /** The state that has asked the peer for `eb` 's body, offered at `offered` and bounded by
    * `bound`, as the decision asks: an announcement focuses it and the peer offers it.
    */
  private def asked(
      eb: LeiosEb,
      offered: BytesSize,
      bound: BytesSize
  ): (LeiosOutstanding[Int], LeiosFetchRequest.Block) =
    val p = pointOf(eb, 10)
    val (announced, _) = LeiosOutstanding
      .empty[Int](StdGen.mk(4), slot(0))
      .announced(AnnouncementFields(ElId(p.slot, issuer), p.hash, offered), None)
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
      fatal: Deferred[IO, LeiosStoreError]
  )

  private def rig(store: LeiosStore[IO], initial: LeiosOutstanding[Int]): IO[Rig] =
    for
      shared <- LeiosFetchShared.create[IO, Int](initial)
      acquired <- LeiosAcquired.create[IO]
      fatal <- Deferred[IO, LeiosStoreError]
    yield Rig(
      LeiosFetchArrival(shared, acquired.notifying(store), env, e => fatal.complete(e).void),
      shared,
      acquired,
      fatal
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
          afterBody <- r.shared.outstanding.get
          body <- ok(store.lookupEbBody(p.hash))
          (deciding, requests) = closureRequests(afterBody, p)
          _ <- r.shared.outstanding.set(deciding)
          txsResults <- requests.traverse(req =>
            typed(r.arrival.blockTxs(peer, req, offsetsOf(req).map(txs)))
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
            case Some(EbFetchState.BodyAcquired(pool)) => assert(!pool.isEmpty)
            case other                                 => fail(s"not acquired: $other")
          assert(requests.nonEmpty)
          assertEquals(txsResults, requests.map(_ => Right(())))
          held(after, p) match
            case Some(EbFetchState.BodyAcquired(pool)) => assert(pool.isEmpty)
            case other                                 => fail(s"not acquired: $other")
          assert(after.requestedJobsPerPeer.isEmpty)
          assert(!after.requestedBytesSizePerPeer.contains(peer))
          assertEquals(completed, p)
          assertEquals(whole.map(_.map(_._2)), Some(txs))
          assertEquals(woken, Some(()))
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
          case Some(EbFetchState.BodyAcquired(pool)) => assert(pool.isEmpty)
          case other                                 => fail(s"not held: $other")
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
    typed(r.arrival.block(peer, request, eb.transactions.toVector)).flatMap { result =>
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
          _ <- typed(r.arrival.block(peer, request, eb.transactions.toVector))
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
            typed(r.arrival.blockTxs(peer, t, offsetsOf(t).map(txs)))
          )
          between <- r.shared.outstanding.get
          again <- reqs(second).traverse(t =>
            typed(r.arrival.blockTxs(second, t, offsetsOf(t).map(txs)))
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

  dirs.test("the store's failure stops the node and is never the peer's fault") { dir =>
    val eb = body(Vector.tabulate(3)(tx))
    val (asking, request) = asked(eb, size(eb), closure(eb))
    stored(dir / "leios").use { store =>
      val failing = new Failing(store, LeiosStoreError.Closed)
      for
        r <- rig(failing, asking)
        // the arrival waits for the node to stop; what reaches the node's sink is the store's error
        result <- IO.race(
          typed(r.arrival.block(peer, request, eb.transactions.toVector)),
          r.fatal.get
        )
        after <- r.shared.outstanding.get
      yield
        assertEquals(result, Right(LeiosStoreError.Closed))
        assertEquals(held(after, request.point), Some(EbFetchState.NoBody))
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
                jobs <- cats.effect.std.Queue.unbounded[IO, LeiosFetchJob[IO]]
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
