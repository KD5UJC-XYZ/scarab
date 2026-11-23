// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.NonEmptyVector
import cats.effect.{IO, Ref, Resource}
import cats.effect.std.{AtomicCell, Semaphore}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.{Files, Path}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.leios.{
  AnnouncementFields,
  BytesSize,
  ElId,
  IsBigLedgerPeer,
  LeiosFetchRequest,
  LeiosFetchStaticEnv,
  LeiosOutstanding,
  LeiosRollForward,
  PeerOffer,
  StdGen,
  WhetherTxsClosureOffered
}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.Peers.typed
import xyz.kd5ujc.scarab.protocol.EbPoint
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64
import xyz.kd5ujc.scarab.storage.{
  LeiosEb,
  LeiosEbNotification,
  LeiosStore,
  LeiosStoreLocks,
  LeiosStoreSuite,
  RocksLeiosStore
}

/** How long an offered endorser block's closure takes to be acquired, on the clock of the decision
  * loop (ouroboros-consensus 2416493b `NodeKernel.hs` 543-653, `LeiosDemoLogic.hs` 470-545 and
  * 1026-1172): the loop decides at once when an offer wakes it and at most once every half second;
  * a body is asked for in one round and its closure's jobs in the next, once the body is held. A
  * closure the mempool holds is written with its body, in the first round, and no transaction is
  * asked for; one fetched from the peer takes the second round.
  */
class LeiosFetchLatencySuite extends FailsOnUncaught:
  import LeiosStoreSuite.{allowing, slot, tx, word32}

  private val dirs = ResourceFunFixture(Files.forAsync[IO].tempDirectory)
  private val env = LeiosFetchStaticEnv.demo
  private val peer = 1
  private val issuer = Bytes(Vector.fill(28)(5.toByte))
  private val txs = Vector.tabulate(40)(tx)
  private val eb: LeiosEb =
    LeiosStoreSuite.eb(
      NonEmptyVector.fromVectorUnsafe(txs.map(t => LeiosEb.hashTx(t) -> word32(t.size.toLong)))
    )
  private val point = EbPoint(slot(10), LeiosEb.hashOf(eb.transactions.toVector))
  private val bodySize = BytesSize.wrap(eb.encoded.size.toLong)
  private val bound = BytesSize.wrap(eb.transactions.toVector.map(_._2.toLong).sum)

  /** What the peer was asked for, and when. */
  private enum Asked derives CanEqual:
    case Body(at: FiniteDuration)
    case Txs(at: FiniteDuration, offsets: Vector[Int])

  /** What one offer led to: the requests and when the closure's acquisition was published. */
  private final case class Run(asked: Vector[Asked], acquiredAt: FiniteDuration)

  private def stored(dir: Path): Resource[IO, RocksLeiosStore[IO]] =
    LeiosStoreLocks
      .resource[IO]
      .flatMap(locks => allowing(RocksLeiosStore.resource[IO](dir, locks)))

  private def offsetsOf(req: LeiosFetchRequest.BlockTxs): Vector[Int] =
    req.jobs.toSortedMap.values.flatMap(_.offsets.toSortedSet).toVector.sorted

  /** The endorser block announced, so its election fetches it, and nothing offered yet. */
  private def announced: LeiosOutstanding[Int] =
    LeiosOutstanding
      .empty[Int](StdGen.mk(4), slot(0))
      .announced(AnnouncementFields(ElId(point.slot, issuer), point.hash, bodySize), None)
      ._1

  /** The loop, the arrival over `store` and a client that answers each request as soon as it is
    * taken, with the mempool `pull`; the peer offers the body and the closure at the start, as
    * LeiosNotify offers them (`recordEbBodyOffer`, `recordEbClosureOffer`), and the run ends when
    * the store publishes the closure's acquisition, which the voting thread takes.
    */
  private def offered(store: LeiosStore[IO], pull: LeiosMempoolPull[IO]): IO[Run] =
    for
      shared <- LeiosFetchShared.create[IO, Int](announced)
      vars <- LeiosPeerVars.create[IO](IsBigLedgerPeer.NotBigLedgerPeer)
      _ <- shared.peers.update(_.updated(peer, vars))
      slots <- Semaphore[IO](LeiosFetchArrival.WriterQueueDepth)
      acquired <- LeiosAcquired.create[IO]
      central <- AtomicCell[IO].of(LeiosCentral.empty[IO])
      asked <- Ref.of[IO, Vector[Asked]](Vector.empty)
      arrival = LeiosFetchArrival(
        shared,
        acquired.notifying(store),
        env,
        e => IO.raiseError(new AssertionError(e.toString)),
        _.start.void,
        slots,
        pull
      )
      start <- IO.monotonic
      since = IO.monotonic.map(_ - start)
      answered = (step: IO[Either[xyz.kd5ujc.scarab.network.NetworkError, Unit]]) =>
        step.flatMap(r => IO(assertEquals(r, Right(()))))
      client = vars.requestsToSend.take.flatMap {
        case LeiosRequestsToSend.Taken.Request(b: LeiosFetchRequest.Block) =>
          since.flatMap(at => asked.update(_ :+ Asked.Body(at))) *>
            answered(typed(arrival.block(peer, b, eb.transactions.toVector)))
        case LeiosRequestsToSend.Taken.Request(t: LeiosFetchRequest.BlockTxs) =>
          since.flatMap(at => asked.update(_ :+ Asked.Txs(at, offsetsOf(t)))) *>
            answered(typed(arrival.blockTxs(peer, t, offsetsOf(t).map(txs))))
        case LeiosRequestsToSend.Taken.Stop => IO.unit
      }
      loop = LeiosFetchKernel.run[IO, Int, Unit](
        shared,
        central,
        env,
        IO.pure(Some(point.slot)),
        IO.pure((_: Word64) => Right(bound)),
        Stream.empty,
        IO.raiseError
      )
      record = (offer: PeerOffer) =>
        vars.offerings.update(LeiosRollForward.recordOffer(_, point, offer)) *> shared.wake
      run <- acquired.notifications.subscribe.use(published =>
        (loop, client.foreverM.background).tupled.surround(
          record(PeerOffer(None, Some(bodySize), WhetherTxsClosureOffered.NotOffered)) *>
            record(PeerOffer(None, None, WhetherTxsClosureOffered.Offered)) *>
            published.take.iterateUntil(_ == LeiosEbNotification.AcquiredEbTxs(point)) *>
            (asked.get, since).mapN(Run(_, _))
        )
      )
    yield run

  dirs.test("a closure the mempool holds is acquired in the offer's round, and none is fetched") {
    dir =>
      stored(dir / "leios").use { store =>
        TestControl
          .executeEmbed(
            offered(
              store,
              LeiosMempoolPull.of(
                IO.pure(
                  xyz.kd5ujc.scarab.node.mempool.LeiosTxIndex(
                    txs.map(t => LeiosEb.hashTx(t) -> t).toMap,
                    Some(xyz.kd5ujc.scarab.consensus.leios.ProtVerParity.Even)
                  )
                )
              )
            )
          )
          .map(run =>
            // the offer wakes the decision at once; the body's arrival completes the closure
            assertEquals(run, Run(Vector(Asked.Body(Duration.Zero)), Duration.Zero))
          )
      }
  }

  dirs.test(
    "a closure fetched from the peer takes the decision's next round, one loop interval on"
  ) { dir =>
    stored(dir / "leios").use { store =>
      TestControl
        .executeEmbed(offered(store, LeiosMempoolPull.none[IO]))
        .map(run =>
          assertEquals(
            run,
            Run(
              Vector(
                Asked.Body(Duration.Zero),
                Asked.Txs(LeiosFetchKernel.LoopInterval, txs.indices.toVector)
              ),
              LeiosFetchKernel.LoopInterval
            )
          )
        )
    }
  }

  dirs.test("a closure the mempool holds in part fetches only the rest, in the next round") { dir =>
    val (inPool, missed) = txs.indices.toVector.partition(_ % 2 == 0)
    stored(dir / "leios").use { store =>
      TestControl
        .executeEmbed(
          offered(
            store,
            LeiosMempoolPull.of(
              IO.pure(
                xyz.kd5ujc.scarab.node.mempool.LeiosTxIndex(
                  inPool.map(txs).map(t => LeiosEb.hashTx(t) -> t).toMap,
                  Some(xyz.kd5ujc.scarab.consensus.leios.ProtVerParity.Even)
                )
              )
            )
          )
        )
        .map(run =>
          assertEquals(
            run,
            Run(
              Vector(Asked.Body(Duration.Zero), Asked.Txs(LeiosFetchKernel.LoopInterval, missed)),
              LeiosFetchKernel.LoopInterval
            )
          )
        )
    }
  }
