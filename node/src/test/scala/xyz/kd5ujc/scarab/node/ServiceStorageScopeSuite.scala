// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Mutex
import cats.mtl.Raise
import java.io.IOException
import java.nio.file.Path
import org.http4s.{Method, Request, Uri}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{Anchor, VolatileChain}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.{StorageError, TxIn, TxOut, Utxo}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.node.mempool.{LocalSubmission, Mempool, MempoolEvents, TxKit}
import xyz.kd5ujc.scarab.node.ntc.{CborOut, QueryEnv, QueryView, StateQuerySource}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.protocol.LocalStateQuery.Target
import xyz.kd5ujc.scarab.storage.{MemoryUtxoStore, SnapshotFailure, UtxoPinnedReadView, UtxoStore}

/** Serving callbacks are invoked after their construction scope has ended, in a different fiber. */
class ServiceStorageScopeSuite extends FailsOnUncaught:
  private val error = StorageError.Io("UTxO read", new IOException("disk unavailable"))
  private val setup = NodeSetup
    .fromDirectory(Path.of("fixtures/devnet/lsq/genesis"))
    .fold(e => fail(e.message), identity)

  private def broken(
      store: UtxoStore[IO],
      enabled: Ref[IO, Boolean],
      pinnedClosed: Option[Ref[IO, Boolean]] = None
  ): UtxoStore[IO] =
    new UtxoStore[IO]:
      export store.{
        write,
        flushed,
        stamp,
        select,
        selectAddresses,
        scan,
        entries,
        size,
        checkpoint,
        restore,
        reset,
        memory
      }
      def read(keys: Iterable[TxIn])(using caller: Raise[IO, StorageError]): IO[Map[TxIn, TxOut]] =
        enabled.get.ifM(caller.raise(error), store.read(keys))
      def all(using caller: Raise[IO, StorageError]): IO[Map[TxIn, TxOut]] =
        enabled.get.ifM(caller.raise(error), store.all)

      def readView = store.readView
        .map(_.map { view =>
          new UtxoPinnedReadView[IO]:
            export view.{stamp, select, selectAddresses}
            def read(keys: Iterable[TxIn]) =
              enabled.get.ifM(IO.pure(Left(error)), view.read(keys))
            def all = enabled.get.ifM(IO.pure(Left(error)), view.all)
        })
        .onFinalize(pinnedClosed.fold(IO.unit)(_.set(true)))

  private def opened[A](body: Raise[IO, StorageError] ?=> IO[A]): IO[A] =
    SnapshotFailure.capture(body).map(_.fold(e => fail(s"opening failed: $e"), identity))

  private def pendingFatal[A](run: (Fatal => IO[Unit]) => IO[A]): IO[Unit] =
    for
      observed <- Deferred[IO, Fatal]
      released <- Deferred[IO, Unit]
      _ <- run(f => observed.complete(f).void)
        .guarantee(released.complete(()).void)
        .background
        .use { _ =>
          for
            result <- observed.get.timeout(3.seconds)
            finished <- released.tryGet
          yield
            assertEquals(result, Fatal.Storage(error))
            assertEquals(finished, None)
        }
      _ <- released.get.timeout(3.seconds)
    yield ()

  test("mempool reads use the current caller scope and release the lock after failure") {
    val full = TxKit.ledger(2)
    val (raw, id) = TxKit.pay(1)
    for
      enabled <- Ref.of[IO, Boolean](true)
      pool <- opened {
        for
          store <- MemoryUtxoStore[IO]
          _ <- store.write(full.utxo.toVector.map((i, o) => i -> Some(o)), 0L)
          pool <- Mempool.create[IO](
            Right(full.withUtxo(Utxo.empty)),
            1000,
            TxKit.env,
            100000,
            MempoolEvents.none[IO],
            utxoStore = Some(broken(store, enabled))
          )
        yield pool
      }
      failed <- SnapshotFailure.capture(pool.add(raw)).start.flatMap(_.joinWithNever)
      _ <- enabled.set(false)
      retried <- opened(pool.add(raw))
    yield
      assertEquals(failed, Left(SnapshotFailure.Storage(error)))
      assertEquals(retried, Right(id))
  }

  test("local submission reports storage failure without emitting a ledger refusal") {
    val (raw, _) = TxKit.pay(1)
    for
      enabled <- Ref.of[IO, Boolean](true)
      pool <- opened {
        MemoryUtxoStore[IO].flatMap(store =>
          Mempool.create[IO](
            Right(TxKit.ledger(2).withUtxo(Utxo.empty)),
            1000,
            TxKit.env,
            100000,
            MempoolEvents.none[IO],
            utxoStore = Some(broken(store, enabled))
          )
        )
      }
      _ <- pendingFatal(report =>
        LocalSubmission.handler(pool)(report).apply(Bytes(Vector(0x82.toByte, 0x06.toByte)) ++ raw)
      )
    yield ()
  }

  test("HTTP materialization reports the typed storage cause and remains cancelable") {
    for
      enabled <- Ref.of[IO, Boolean](true)
      store <- opened(MemoryUtxoStore[IO])
      chain <- Ref.of[IO, VolatileChain[MirrorState]](
        VolatileChain(5, Anchor(None, setup.mirrorStart), Vector.empty)
      )
      _ <- pendingFatal(report =>
        QueryServer
          .routes(ChainView.fromRef(chain), Some(broken(store, enabled)))(report)
          .orNotFound
          .run(Request[IO](Method.GET, Uri.unsafeFromString("/state")))
      )
    yield ()
  }

  test("an acquired state query returns its typed backend error after releasing the pinned view") {
    val env = QueryEnv(
      setup.geometry.systemStart,
      setup.geometry,
      setup.praos.forks,
      setup.mirrorEnv().byron,
      setup.globals.maxSupply,
      setup.genesis.raw,
      setup.mirrorEnv().ledger,
      networkMagic = setup.genesis.networkMagic.toLong
    )
    val era = QueryView(Point.Origin, None, setup.mirrorStart).era.index
    val query =
      CborOut.build(_.array(2).uint(0).array(2).uint(0).array(2).uint(era).array(1).uint(7))
    for
      enabled <- Ref.of[IO, Boolean](true)
      store <- opened(MemoryUtxoStore[IO])
      released <- Ref.of[IO, Boolean](false)
      gate <- Mutex[IO]
      chain <- Ref.of[IO, VolatileChain[MirrorState]](
        VolatileChain(5, Anchor(None, setup.mirrorStart), Vector.empty)
      )
      result <- {
        val source =
          StateQuerySource(chain, IO.pure(env), broken(store, enabled, Some(released)), gate)
        source.acquire(Target.VolatileTip).use {
          _.fold(e => IO(fail(s"acquisition refused: $e")), _.answer(query))
        }
      }
      closed <- released.get
    yield
      assertEquals(result, Left(error))
      assert(closed)
  }
