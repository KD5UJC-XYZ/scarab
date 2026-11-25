// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.NonEmptyVector
import cats.effect.{IO, Resource}
import cats.effect.std.Supervisor
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.leios.{
  AnnouncingHeader,
  BytesSize,
  EbFetchState,
  LeiosFetchStaticEnv,
  LeiosJobPool,
  ProtVerParity,
  StdGen
}
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.LeiosBlock
import xyz.kd5ujc.scarab.network.{FailsOnUncaught, LeiosNotifyServer, NetworkError}
import xyz.kd5ujc.scarab.node.forge.ForgedLeiosEb
import xyz.kd5ujc.scarab.protocol.{HfcHeader, TxBitmaps}
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word16, Word32, Word64}
import xyz.kd5ujc.scarab.storage.{LeiosStoreLocks, LeiosStoreSuite, RocksLeiosStore}

/** An endorser block the node forged, handed to its fetch's handlers (`onForgedLeiosEb`,
  * ouroboros-consensus 2416493b `LeiosDemoLogic.hs` 2389-2460, [[LeiosFetchNode.onForged]]): the
  * store holds its point, body and closure afterwards, the closure's completion reaches chain
  * selection's set, the fetch logic holds the body and never fetches it, and the LeiosFetch server
  * answers for it from the store as it answers for one fetched from a peer
  * ([[LeiosFetchStoreHandler]]); a downstream peer's LeiosNotify server is told of its
  * announcement, then offered its body and its closure ([[LeiosNotifyRelay]]).
  *
  * The endorser block is forged of the 40 transactions of the testnet's block 64,853
  * (`fixtures/leios/testnet/blocks-rc-2026w44.tsv`), announced by that block's header; the
  * reference's own endorser block is `DijkstraEndorserBlockSuite` 's.
  */
class LeiosForgedEbSuite extends FailsOnUncaught:
  import LeiosStoreSuite.{ok, allowing, slot}

  private val dirs = ResourceFunFixture(Files.forAsync[IO].tempDirectory)

  private lazy val block: LeiosBlock =
    val raw = java.nio.file.Files
      .readAllLines(java.nio.file.Path.of("fixtures", "leios", "testnet", "blocks-rc-2026w44.tsv"))
      .asScala
      .map(_.split('\t'))
      .collectFirst {
        case r if r(0) == "announcing-with-txs" =>
          Bytes.fromHex(r(5)).fold(e => fail(e.message), identity)
      }
      .getOrElse(fail("no block 64,853"))
    LeiosBlock.decode(raw).fold(e => fail(e.message), identity)

  private lazy val forged: ForgedLeiosEb =
    ForgedLeiosEb.forge(
      block.header.slot,
      ProtVerParity.Even,
      NonEmptyVector
        .fromVector(block.transactions)
        .getOrElse(fail("no transactions"))
    )

  private lazy val announcing: AnnouncingHeader =
    AnnouncingHeader.forged(
      block.header,
      forged.point.hash,
      BytesSize.wrap(forged.body.encodedSize)
    )

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

  private def served[A](
      run: cats.mtl.Raise[IO, NetworkError] ?=> IO[A]
  ): IO[Either[NetworkError, A]] =
    Handle.allow[NetworkError](run.map(_.asRight[NetworkError])).rescue(e => IO.pure(e.asLeft[A]))

  /** The node's fetch over a fresh store, its completions offered to chain selection's set. */
  private def rig(
      dir: Path
  ): Resource[IO, (LeiosFetchNode[IO], RocksLeiosStore[IO], LeiosAcquired[IO])] =
    (
      LeiosStoreLocks
        .resource[IO]
        .flatMap(locks => allowing(RocksLeiosStore.resource[IO](dir, locks))),
      Supervisor[IO](await = false),
      Resource.eval(LeiosAcquired.create[IO])
    ).tupled.evalMap { (store, settling, acquired) =>
      ok(
        LeiosTxCache
          .create[IO]
          .flatMap(txCache =>
            LeiosFetchNode.create[IO](
              acquired.notifying(store),
              StdGen.mk(4),
              slot(0),
              settling,
              e => IO.raiseError(new AssertionError(e.toString)),
              IO.raiseError,
              _ => IO.pure(None),
              acquired.notifications.subscribe,
              IO.pure(None),
              LeiosMinOfferLead.Default,
              LeiosMempoolPull.none[IO],
              txCache
            )
          )
      ).map(node => (node, store, acquired))
    }

  dirs.test("the store holds a forged endorser block and its closure, and serves them by hash") {
    dir =>
      rig(dir / "leios").use { (node, store, acquired) =>
        val point = forged.point
        val txs = forged.closure.toVector
        for
          _ <- ok(node.onForged.forged(announcing, forged))
          body <- ok(store.lookupEbBody(point.hash))
          closure <- ok(store.lookupEbClosure(point.hash))
          points <- ok(store.scanEbPoints)
          complete <- ok(store.scanCompleteEbClosuresNotOlderThanSlot(slot(0)))
          // the write that completed the closure offered the point to chain selection's set
          completed <- acquired.next
          state <- node.shared.outstanding.get
          answered <- served(LeiosFetchStoreHandler(store).block(point))
          some <- served(LeiosFetchStoreHandler(store).blockTxs(point, bitmaps(0, 7, 39)))
          all <- served(LeiosFetchStoreHandler(store).blockTxs(point, bitmaps(txs.indices*)))
        yield
          assertEquals(body, Some(forged.body.encoded))
          assertEquals(closure, Some(txs))
          assertEquals(points, Vector(point))
          assertEquals(complete, Vector(point))
          assertEquals(completed, point)
          // held, so its body is never asked of a peer; its pool lists every transaction, as the
          // reference's does: the forge pulls none from the pool (`noMempoolPull`) and the write
          // of its closure retires no job (`ForgedTxs`)
          assertEquals(
            state.ebState.get(point.hash).map(_.fetch).collect {
              case EbFetchState.BodyAcquired(jobs, _) => jobs
            },
            Some(
              LeiosJobPool.of(
                LeiosFetchStaticEnv.demo.maxJobBytesSize,
                LeiosFetchStaticEnv.demo.maxJobTxCount,
                SortedMap.from(forged.body.transactions.toVector.zipWithIndex.map {
                  case ((hash, size), offset) => offset -> (hash, BytesSize.wrap(size.toLong))
                })
              )
            )
          )
          assertEquals(answered, Right(forged.body.transactions.toVector))
          assertEquals(some, Right(Vector(0, 7, 39).map(txs(_)._2)))
          assertEquals(all, Right(txs.map(_._2)))
      }
  }

  dirs.test(
    "the forged closure's transactions are recorded in the transaction cache as applied under the forge's parity"
  ) { dir =>
    rig(dir / "leios").use { (node, _, _) =>
      val hashes = forged.closure.toVector.map(_._1)
      for
        before <- node.shared.txCache.lookup
        _ <- ok(node.onForged.forged(announcing, forged))
        after <- node.shared.txCache.lookup
      yield
        assertEquals(hashes.map(before), hashes.map(_ => None))
        // `insertApplied` of `ForgedTxs` (`LeiosDemoLogic.hs` 1565-1566 at 2416493b): the closure
        // check of the node's own endorser block then reapplies every one of them
        assertEquals(hashes.map(after), hashes.map(_ => Some(Right(forged.txsProtVerParity))))
    }
  }

  dirs.test("the announcement's point is recorded at the size of the forged body") { dir =>
    rig(dir / "leios").use { (node, store, _) =>
      for
        _ <- ok(node.onForged.forged(announcing, forged))
        // the same block again: the announcement is a repeat, the body is held, the closure was
        // complete, and nothing changes
        _ <- ok(node.onForged.forged(announcing, forged))
        points <- ok(store.scanEbPoints)
        body <- ok(store.lookupEbBody(forged.point.hash))
        closure <- ok(store.lookupEbClosure(forged.point.hash))
        counted <- node.shared.outstanding.get
      yield
        assertEquals(announcing.fields.ebBodySize, BytesSize.wrap(forged.body.encoded.size.toLong))
        assertEquals(announcing.fields.point, forged.point)
        assertEquals(points, Vector(forged.point))
        assertEquals(body, Some(forged.body.encoded))
        assertEquals(closure, Some(forged.closure.toVector))
        assert(counted.ebState.get(forged.point.hash).exists(_.hasBody))
    }
  }

  dirs.test(
    "a forged endorser block is announced to a downstream peer with credits, then its body and closure offered"
  ) { dir =>
    rig(dir / "leios").use { (node, _, _) =>
      // the production forge callback, with a downstream server running and holding three requests
      node.notifier(LeiosVotesSuite.noVotes).flatTap(_.pump.background).use { downstream =>
        for
          held <- downstream.requested.replicateA(3)
          _ <- ok(node.onForged.forged(announcing, forged))
          replies <- downstream.next.replicateA(3)
        yield
          assertEquals(held, List.fill(3)(LeiosNotifyServer.Requested.NotExcessive))
          assertEquals(
            replies,
            List(
              LeiosNotifyServer.Reply.Announcement(HfcHeader.Dijkstra(block.header.raw)),
              LeiosNotifyServer.Reply.BlockOffer(
                forged.point,
                Word32.wrap(forged.body.encoded.size.toLong)
              ),
              LeiosNotifyServer.Reply.BlockTxsOffer(forged.point)
            )
          )
      }
    }
  }
