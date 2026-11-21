// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.{NonEmptyMap, NonEmptySet, NonEmptyVector}
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
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
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint, LeiosFetchRefusal, LeiosTxHash, TxBitmaps}
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
  private def body(n: Int): LeiosEb =
    LeiosStoreSuite.eb(
      NonEmptyVector.fromVectorUnsafe(
        Vector.tabulate(n)(i => LeiosEb.hashTx(tx(i)) -> word32(tx(i).size.toLong))
      )
    )

  /** A store holding `bodies` whole: each body, every transaction of its closure. */
  private def filled(
      dir: Path,
      bodies: Vector[(EbPoint, LeiosEb)]
  ): Resource[IO, RocksLeiosStore[IO]] =
    LeiosStoreLocks
      .resource[IO]
      .flatMap(locks => allowing(RocksLeiosStore.resource[IO](dir, locks)))
      .evalTap { s =>
        ok(bodies.traverse_ { (p, eb) =>
          s.writeEbPoint(p, word32(eb.encoded.size.toLong)) *> s.writeEbBody(p, eb, Vector.empty) *>
            s.writeTxs(p, Vector.tabulate(eb.size)(i => i -> tx(i)))
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

  test("the ingress limits are the reference's, with the margin") {
    val limits = IngressLimits.nodeToNode()
    assertEquals(limits(Protocols.LeiosNotify), (1L << 20) + (1L << 20) / 10)
    assertEquals(limits(Protocols.LeiosFetch), 60L * (1L << 20) + 6L * (1L << 20))
  }
