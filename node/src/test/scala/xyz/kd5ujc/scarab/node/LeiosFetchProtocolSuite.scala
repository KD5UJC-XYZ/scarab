// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.{NonEmptyMap, NonEmptySet, NonEmptyVector}
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.leios.{BytesSize, JobRootHash, LeiosFetchRequest, LeiosJob}
import xyz.kd5ujc.scarab.network.{
  FailsOnUncaught,
  Fibers,
  IngressLimits,
  LeiosFetchJob,
  LeiosFetchPipelined,
  LeiosFetchServer,
  MuxChannel,
  NetworkError,
  Peers,
  Protocols
}
import xyz.kd5ujc.scarab.protocol.{
  EbHash,
  EbPoint,
  LeiosFetch,
  LeiosFetchRefusal,
  LeiosTxHash,
  TxBitmaps
}
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word16, Word32, Word64}
import xyz.kd5ujc.scarab.storage.{LeiosEb, LeiosStoreLocks, LeiosStoreSuite, RocksLeiosStore}

import Peers.*

/** The LeiosFetch client against the server over an in-memory multiplexer, the server answering
  * from a real store, as the reference's `leiosFetchClientPeerPipelined` and `leiosFetchServerPeer`
  * run with `leiosFetchHandler` (ouroboros-consensus 2416493b `LeiosDemoOnlyTestFetch.hs` 545-683,
  * `LeiosDemoLogic.hs` 246-307): a body and its whole closure, more requests than the pipeline's
  * depth in their order, the server's refusals of what it does not hold, and the decision's
  * requests as the wire's.
  */
class LeiosFetchProtocolSuite extends FailsOnUncaught:
  import LeiosStoreSuite.{ok, allowing, word32, slot}

  private val dirs = ResourceFunFixture(Files.forAsync[IO].tempDirectory)

  private def tx(i: Int): Bytes = LeiosStoreSuite.tx(i)
  private def body(n: Int, transaction: Int => Bytes = tx): LeiosEb =
    LeiosStoreSuite.eb(
      NonEmptyVector.fromVectorUnsafe(
        Vector.tabulate(n)(i =>
          LeiosEb.hashTx(transaction(i)) -> word32(transaction(i).size.toLong)
        )
      )
    )

  /** A store holding `bodies` whole: each body, every transaction of its closure, transaction `i`
    * of each body being `transaction(i)`.
    */
  private def filled(
      dir: Path,
      bodies: Vector[(EbPoint, LeiosEb)],
      transaction: Int => Bytes = tx
  ): Resource[IO, RocksLeiosStore[IO]] =
    LeiosStoreLocks
      .resource[IO]
      .flatMap(locks => allowing(RocksLeiosStore.resource[IO](dir, locks)))
      .evalTap { s =>
        ok(bodies.traverse_ { (p, eb) =>
          s.writeEbPoint(p, word32(eb.encoded.size.toLong)) *> s.writeEbBody(p, eb, Vector.empty) *>
            s.writeTxs(p, Vector.tabulate(eb.size)(i => i -> transaction(i)))
        })
      }

  /** A client and a server joined by an in-memory multiplexer, the server answering from `store`:
    * the client's result, and the server's failure, which ends the connection.
    */
  private def connected[A](store: RocksLeiosStore[IO])(
      client: Net ?=> MuxChannel[IO] => IO[A]
  ): IO[(Either[NetworkError, A], Option[NetworkError])] =
    Deferred[IO, Option[NetworkError]].flatMap { served =>
      typed(
        linked(protocols = Set(Protocols.LeiosFetch)).use { (c, s) =>
          for
            cc <- c.channel(Protocols.LeiosFetch)
            sc <- s.channel(Protocols.LeiosFetch)
            server = Fibers
              .scoped[IO, NetworkError, Unit](NetworkError.Defect(_))(
                LeiosFetchServer.run(sc, LeiosFetchStoreHandler(store))
              )
              // the connection ends with the server's failure: the test ends the client's side with
              // it at once, rather than at the client's 60-second limit for an answer
              .flatMap(r =>
                served
                  .complete(r.left.toOption) *> r.fold(e => s.abort(e) *> c.abort(e), _ => IO.unit)
              )
            result <- server.background.surround(client(cc))
          yield result
        }
      ).flatMap(r => served.tryGet.map(r -> _.flatten))
    }

  /** The client run over `jobs` in turn, then stopped once every answer has been handled: with no
    * job left the client waits, so it collects every answer before it stops. A result given while
    * answers are outstanding would leave them unhandled, as the reference's drain does.
    */
  private def fetch(channel: MuxChannel[IO], jobs: Vector[LeiosFetchJob[IO]])(using
      Net
  ): IO[Unit] =
    Queue.unbounded[IO, LeiosFetchJob[IO]].flatMap { q =>
      jobs.traverse_(q.offer) *> LeiosFetchPipelined.run[IO, Unit](
        channel,
        q.tryTake.map(_.fold(Left(IO.pure(Left(()))))(j => Right(Right(j))))
      )
    }

  private def bitmaps(offsets: Int*): TxBitmaps =
    TxBitmaps(
      offsets
        .groupBy(_ / 64)
        .toVector
        .sortBy(_._1)
        .map((w, offs) =>
          (
            Word16.from(BigInt(w)).fold(e => fail(e.toString), identity),
            Word64
              .from(offs.foldLeft(BigInt(0))((m, o) => m.setBit(63 - o % 64)))
              .fold(e => fail(e.toString), identity)
          )
        )
    )

  dirs.test("a body and its whole closure are fetched from a server that holds them") { dir =>
    val eb = body(70)
    val p = EbPoint(slot(10), EbHash.fromBytes(LeiosEb.hashTx(eb.encoded).bytes))
    filled(dir / "leios", Vector(p -> eb)).use { store =>
      for
        got <- Ref.of[IO, Vector[Vector[Bytes]]](Vector.empty)
        listed <- Ref.of[IO, Vector[Vector[(LeiosTxHash, Word32)]]](Vector.empty)
        result <- connected(store)(ch =>
          fetch(
            ch,
            Vector(
              LeiosFetchJob.Block[IO](p, txs => listed.update(_ :+ txs)),
              LeiosFetchJob.BlockTxs[IO](p, bitmaps((0 until 70)*), txs => got.update(_ :+ txs)),
              LeiosFetchJob.BlockTxs[IO](p, bitmaps(1, 63, 64, 69), txs => got.update(_ :+ txs))
            )
          )
        )
        bodies <- listed.get
        answers <- got.get
      yield
        assertEquals(result, (Right(()), None))
        assertEquals(bodies, Vector(eb.transactions.toVector))
        assertEquals(answers, Vector(Vector.tabulate(70)(tx), Vector(1, 63, 64, 69).map(tx)))
    }
  }

  dirs.test("more requests than the pipeline's depth are answered, each in the order sent") { dir =>
    val eb = body(3)
    val p = EbPoint(slot(1), EbHash.fromBytes(Bytes(Vector.fill(32)(9))))
    val n = LeiosFetchPipelined.PipelineDepth * 2 + 7
    filled(dir / "leios", Vector(p -> eb)).use { store =>
      for
        order <- Ref.of[IO, Vector[Int]](Vector.empty)
        result <- connected(store)(ch =>
          fetch(
            ch,
            Vector.tabulate(n)(i =>
              LeiosFetchJob.BlockTxs[IO](
                p,
                bitmaps(i % 3),
                txs => order.update(_ :+ i) *> IO(assertEquals(txs, Vector(tx(i % 3))))
              )
            )
          )
        )
        seen <- order.get
      yield
        assertEquals(result, (Right(()), None))
        assertEquals(seen, Vector.range(0, n))
    }
  }

  dirs.test("the server refuses, and ends the connection over, what it does not hold") { dir =>
    val eb = body(3)
    val held = EbPoint(slot(1), EbHash.fromBytes(Bytes(Vector.fill(32)(9))))
    val unknown = EbPoint(slot(1), EbHash.fromBytes(Bytes(Vector.fill(32)(8))))
    val partial = EbPoint(slot(2), EbHash.fromBytes(Bytes(Vector.fill(32)(7))))
    filled(dir / "leios", Vector(held -> eb))
      .evalTap(s =>
        ok(
          s.writeEbPoint(partial, word32(1)) *> s.writeEbBody(partial, eb, Vector.empty) *> s
            .writeTxs(partial, Vector(0 -> tx(0)))
        )
      )
      .use { store =>
        def refusal(job: LeiosFetchJob[IO]) =
          connected(store)(ch => fetch(ch, Vector(job))).map {
            case (Left(_), Some(NetworkError.LeiosFetchRefused(r))) => Some(r)
            case _                                                  => None
          }
        for
          block <- refusal(LeiosFetchJob.Block[IO](unknown, _ => IO.unit))
          beyond <- refusal(LeiosFetchJob.BlockTxs[IO](held, bitmaps(0, 3), _ => IO.unit))
          unwritten <- refusal(LeiosFetchJob.BlockTxs[IO](partial, bitmaps(0, 1), _ => IO.unit))
        yield
          assertEquals(block, Some(LeiosFetchRefusal.UnknownBlockRequested(unknown)))
          assertEquals(beyond, Some(LeiosFetchRefusal.UnknownTxsRequested(held, Vector(0, 3))))
          assertEquals(unwritten, Some(LeiosFetchRefusal.UnknownTxsRequested(partial, Vector(1))))
      }
  }

  dirs.test("an answer of transactions over the multiplexer's message ceiling is sent whole") {
    dir =>
      // 200 transactions of 16,000 bytes: 3.2 MB, over the 2,500,000 bytes the multiplexer bounds the
      // messages of the other protocols with
      def large(i: Int): Bytes = Bytes(Vector.tabulate(16000)(j => ((i + j) % 251 + 1).toByte))
      val n = 200
      val eb = body(n, large)
      val p = EbPoint(slot(4), EbHash.fromBytes(Bytes(Vector.fill(32)(6))))
      def encoded(message: LeiosFetch.Message): Bytes =
        LeiosFetch.encode(message).fold(e => fail(e.toString), identity)
      val all = bitmaps((0 until n)*)
      val request = LeiosFetch.Message.BlockTxsRequest(p, all)
      filled(dir / "leios", Vector(p -> eb), large).use { store =>
        for
          // a client that receives with a bound of its own, above the answer's size
          served <- connected(store)(ch =>
            ch.send(encoded(request)) *>
              ch.recv(4000000)(LeiosFetch.incremental(LeiosFetch.State.BlockTxs))
                .flatTap(_ => ch.send(encoded(LeiosFetch.Message.Done)))
          )
          // the pipelined client keeps the limit of BlockTxs, 550,000 bytes, and refuses it
          pipelined <- connected(store)(ch =>
            fetch(ch, Vector(LeiosFetchJob.BlockTxs[IO](p, all, _ => IO.unit)))
          )
        yield
          assertEquals(
            served,
            (Right(LeiosFetch.Message.BlockTxs(Vector.tabulate(n)(large))), None)
          )
          pipelined._1 match
            case Left(NetworkError.IngressLimit(Protocols.LeiosFetch, bytes)) =>
              assert(bytes > LeiosFetch.BlockTxsByteLimit, bytes.toString)
            case other => fail(s"the pipelined client took the answer: $other")
      }
  }

  test("the decision's transactions request goes on the wire as its jobs' offsets") {
    def job(offsets: Int*): LeiosJob =
      LeiosJob(
        NonEmptySet.of(offsets.head, offsets.tail*),
        BytesSize.wrap(1),
        JobRootHash.of(Vector.empty)
      )
    val p = EbPoint(slot(1), EbHash.fromBytes(Bytes(Vector.fill(32)(1))))
    val request: LeiosFetchRequest.BlockTxs =
      LeiosFetchRequest.BlockTxs(p, NonEmptyMap.of(0 -> job(0, 63), 1 -> job(64, 130)))
    assertEquals(LeiosFetchJobs.bitmaps(request), Right(bitmaps(0, 63, 64, 130)))
    LeiosFetchJobs.of[IO](request, (_, _) => IO.unit, (_, _) => IO.unit) match
      case Right(LeiosFetchJob.BlockTxs(point, b, _)) =>
        assertEquals((point, b), (p, bitmaps(0, 63, 64, 130)))
      case other => fail(s"not a transactions job: $other")
  }

  private val blockRequest: LeiosFetchRequest =
    LeiosFetchRequest.Block(
      EbPoint(slot(3), EbHash.fromBytes(Bytes(Vector.fill(32)(5)))),
      BytesSize.wrap(1),
      BytesSize.wrap(1)
    )
  private def job(request: LeiosFetchRequest) =
    LeiosFetchJobs.of[IO](request, (_, _) => IO.unit, (_, _) => IO.unit)

  test("the stop is the client's next instruction, whatever was queued before or after it") {
    typed {
      for
        // polled: a request queued after the stop is not taken
        after <- LeiosRequestsToSend.create[IO]
        _ <- after.terminate *> after.offer(NonEmptyVector.one(blockRequest))
        polledAfter <- LeiosFetchJobs.next(after, job)
        // polled: nor is one queued before it
        before <- LeiosRequestsToSend.create[IO]
        _ <- before.offer(NonEmptyVector.one(blockRequest)) *> before.terminate
        polledBefore <- LeiosFetchJobs.next(before, job)
        // awaited: the wait the client was given ends with the stop, not with the request queued
        // after it
        awaited <- LeiosRequestsToSend.create[IO]
        instruction <- LeiosFetchJobs.next(awaited, job)
        waiting <- instruction.fold(IO.pure, ready => IO(fail(s"ready at once: $ready")))
        _ <- awaited.terminate *> awaited.offer(NonEmptyVector.one(blockRequest))
        woken <- waiting
        // without a stop the request is the job
        open <- LeiosRequestsToSend.create[IO]
        _ <- open.offer(NonEmptyVector.one(blockRequest))
        sent <- LeiosFetchJobs.next(open, job)
      yield
        assertEquals(polledAfter.map(_.left.toOption), Right(Some(())))
        assertEquals(polledBefore.map(_.left.toOption), Right(Some(())))
        assertEquals(woken.left.toOption, Some(()))
        sent match
          case Right(Right(LeiosFetchJob.Block(point, _))) =>
            assertEquals(point, EbPoint(slot(3), EbHash.fromBytes(Bytes(Vector.fill(32)(5)))))
          case other => fail(s"not the request's job: $other")
    }.map(r => assert(r.isRight, r.toString))
  }

  test("the requests to send are a queue that the stop ends, as a model of them says") {
    enum Op:
      case Offer(slots: NonEmptyVector[Int])
      case Terminate
      case Take
    val op: Gen[Op] = Gen.frequency(
      4 -> Gen
        .nonEmptyListOf(Gen.choose(0, 1000))
        .map(l => Op.Offer(NonEmptyVector.fromVectorUnsafe(l.take(4).toVector))),
      1 -> Gen.const(Op.Terminate),
      5 -> Gen.const(Op.Take)
    )
    def request(n: Int): LeiosFetchRequest =
      LeiosFetchRequest.Block(
        EbPoint(slot(n.toLong), EbHash.fromBytes(Bytes(Vector.fill(32)(1)))),
        BytesSize.wrap(1),
        BytesSize.wrap(1)
      )
    // the model: the requests queued, oldest first, until the stop, which every take then gives
    def model(ops: List[Op]): Vector[Option[LeiosRequestsToSend.Taken]] =
      ops
        .foldLeft((Option(Vector.empty[Int]), Vector.empty[Option[LeiosRequestsToSend.Taken]])) {
          case ((Some(queued), seen), Op.Offer(slots)) => (Some(queued ++ slots.toVector), seen)
          case ((None, seen), Op.Offer(_))             => (None, seen)
          case ((_, seen), Op.Terminate)               => (None, seen)
          case ((None, seen), Op.Take) => (None, seen :+ Some(LeiosRequestsToSend.Taken.Stop))
          case ((Some(queued), seen), Op.Take) =>
            (
              Some(queued.drop(1)),
              seen :+ queued.headOption.map(n => LeiosRequestsToSend.Taken.Request(request(n)))
            )
        }
        ._2
    def run(ops: List[Op]): IO[Vector[Option[LeiosRequestsToSend.Taken]]] =
      LeiosRequestsToSend.create[IO].flatMap { requests =>
        ops.foldLeftM(Vector.empty[Option[LeiosRequestsToSend.Taken]]) {
          case (seen, Op.Offer(slots)) => requests.offer(slots.map(request)).as(seen)
          case (seen, Op.Terminate)    => requests.terminate.as(seen)
          case (seen, Op.Take)         => requests.tryTake.map(seen :+ _)
        }
      }
    val cases = Gen.listOfN(300, Gen.listOf(op)).apply(Gen.Parameters.default, Seed(54L)).toList
    cases.flatten.traverse(ops => run(ops).map(ops -> _)).map { runs =>
      assert(runs.nonEmpty)
      runs.foreach((ops, seen) => assertEquals(seen, model(ops), ops.toString))
    }
  }

  test("a stop before a request queued after it puts MsgDone next on the wire") {
    typed(
      linked(protocols = Set(Protocols.LeiosFetch)).use { (c, s) =>
        for
          cc <- c.channel(Protocols.LeiosFetch)
          sc <- s.channel(Protocols.LeiosFetch)
          requests <- LeiosRequestsToSend.create[IO]
          waiting <- Deferred[IO, Unit]
          // the client's instruction, noting when the client is given the wait
          next = LeiosFetchJobs
            .next(requests, job)
            .flatTap(_.fold(_ => waiting.complete(()).void, _ => IO.unit))
          // the client's failure is a value of its own scope, and it is cancelled when the test
          // ends, whatever the first message was
          client = Fibers.scoped[IO, NetworkError, Unit](NetworkError.Defect(_))(
            LeiosFetchPipelined.run[IO, Unit](cc, next)
          )
          seen <- client.background.use { joined =>
            for
              _ <- waiting.get
              _ <- requests.terminate *> requests.offer(NonEmptyVector.one(blockRequest))
              first <- sc.recv(LeiosFetchServer.IdleByteLimit)(
                LeiosFetch.incremental(LeiosFetch.State.Idle)
              )
              // the client ends only once it has sent MsgDone: anything else is not waited for
              ended <- first match
                case LeiosFetch.Message.Done => joined.flatMap(_.embedNever).map(Some(_))
                case _                       => IO.pure(None)
            yield (first, ended)
          }
        yield seen
      }
    ).map(r => assertEquals(r, Right((LeiosFetch.Message.Done, Some(Right(()))))))
  }

  test("the ingress limits are the reference's, with the margin") {
    val limits = IngressLimits.nodeToNode()
    assertEquals(limits(Protocols.LeiosNotify), (1L << 20) + (1L << 20) / 10)
    assertEquals(limits(Protocols.LeiosFetch), 60L * (1L << 20) + 6L * (1L << 20))
  }
