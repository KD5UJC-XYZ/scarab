// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.data.EitherT
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.kernel.MonadCancel
import cats.effect.std.Mutex
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import cats.arrow.FunctionK
import cats.mtl.Raise
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.consensus.{Anchor, VolatileChain}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.PreprodDeepTx.onSmallStack
import xyz.kd5ujc.scarab.ledger.{Block, StorageError, TxIn}
import xyz.kd5ujc.scarab.node.{MirrorSteps, NodeSetup}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.ChainSync.{DecodeResult, Point, Role}
import xyz.kd5ujc.scarab.protocol.{Era, LocalStateQuery}
import xyz.kd5ujc.scarab.network.LocalStateQueryServer.Refusal
import xyz.kd5ujc.scarab.network.LocalStateQueryServer
import xyz.kd5ujc.scarab.storage.{MemoryUtxoStore, RocksUtxoStore, UtxoStore}
import fs2.io.file.Path as Fs2Path

/** GetCBOR is recursively valid in the reference (Shelley/Ledger/Query.hs:944,1017 at
  * ouroboros-consensus 82ecba3). Its interpretation must not depend on the JVM worker stack.
  */
class DeepQuerySuite extends FunSuite:
  private type StorageF[A] = EitherT[IO, StorageError, A]
  private val depth = 20000
  private val setup = NodeSetup
    .fromDirectory(Path.of("fixtures/devnet/lsq/genesis"))
    .fold(e => fail(e.message), identity)
  private val env = QueryEnv(
    setup.geometry.systemStart,
    setup.geometry,
    setup.praos.forks,
    setup.mirrorEnv().byron,
    setup.globals.maxSupply,
    setup.genesis.raw,
    setup.mirrorEnv().ledger,
    networkMagic = setup.genesis.networkMagic.toLong
  )
  private val view = QueryView(Point.Origin, None, setup.mirrorStart)

  private def nested(tag: Int, layers: Int = depth): Bytes = CborOut.build { w =>
    w.array(2).uint(0).array(2).uint(0).array(2).uint(view.era.index)
    (0 until layers).foreach(_ => w.array(2).uint(9))
    w.array(1).uint(tag)
  }

  private def decoded(raw: Bytes): NtcQuery =
    NtcQuery.decode(raw).fold(e => fail(e.message), identity)

  private def result(raw: Bytes): Bytes =
    Cbor.decode(raw).fold(e => fail(e.message), identity).value match
      case Value.Arr(Vector(inner)) => inner.original
      case _                        => fail("expected singleton era answer")

  private def unwrap(raw: Bytes, layers: Int): Bytes =
    (0 until layers).foldLeft(raw) { (current, _) =>
      Cbor.decode(current).fold(e => fail(e.message), identity).value match
        case Value.Tag(t, inner) if t == 24 =>
          inner.value match
            case Value.ByteString(bytes) => bytes
            case _                       => fail("expected GetCBOR bytes")
        case _ => fail("expected tag 24")
    }

  test("a typed pinned-view acquisition failure releases the StateQuerySource resource") {
    val releases = new AtomicInteger(0)
    val storageFailure = StorageError.Io("snapshot", RuntimeException("test"))
    val program = (for
      chain <- Ref.of[StorageF, VolatileChain[MirrorState]](
        VolatileChain(5, Anchor(None, setup.mirrorStart), Vector.empty)
      )
      backing <- MemoryUtxoStore[StorageF]
      store = new xyz.kd5ujc.scarab.storage.UtxoStore[StorageF]:
        export backing.{
          read,
          write,
          flushed,
          stamp,
          all,
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
        override def readView: Resource[
          StorageF,
          Either[StorageError, xyz.kd5ujc.scarab.storage.UtxoPinnedReadView[StorageF]]
        ] =
          Resource.make(
            EitherT.pure[IO, StorageError](
              Left(storageFailure)
            )
          )(_ => EitherT.liftF(IO(releases.incrementAndGet()).void))
      gate <- cats.effect.std.Mutex[StorageF]
      source = StateQuerySource(chain, EitherT.rightT[IO, StorageError](env), store, gate)
      acquired <- source
        .acquire(LocalStateQuery.Target.VolatileTip)
        .use(result => EitherT.rightT[IO, StorageError](result))
    yield acquired).value
    program
      .map { acquired =>
        assertEquals(
          acquired,
          Right(
            Left(
              LocalStateQueryServer.AcquireFailure.Backend(storageFailure)
            )
          )
        )
        assertEquals(releases.get(), 1)
      }
      .unsafeRunSync()
  }

  test("semantic decode, UTxO planning, diagnostic and answer walks survive 20000 wrappers") {
    onSmallStack {
      val query = decoded(nested(7))
      assertEquals(QueryAnswers.utxoNeeds(view, query), QueryAnswers.UtxoNeeds.Whole)
      assertEquals(
        NtcQuery.describe(query),
        s"IfCurrent(${view.era.index},GetCBOR[$depth](GetUTxOWhole))"
      )
      val answer = QueryAnswers
        .answerOrWhy(env, view, Map.empty, query)
        .fold(e => fail(e.toString), identity)
      val leaf = unwrap(result(answer), depth)
      val plain = QueryAnswers
        .answerOrWhy(
          env,
          view,
          Map.empty,
          NtcQuery.IfCurrent(view.era.index, LedgerQuery.GetUTxOWhole)
        )
        .fold(e => fail(e.toString), identity)
      assertEquals(leaf, result(plain))
    }
  }

  test("unsupported and malformed deep queries return typed failures on a small stack") {
    onSmallStack {
      val query = decoded(nested(14))
      assertEquals(QueryAnswers.utxoNeeds(view, query), QueryAnswers.UtxoNeeds.Nothing)
      assertEquals(
        QueryAnswers.answerOrWhy(env, view, Map.empty, query),
        Left(Unanswered.Unsupported)
      )
      assert(NtcQuery.decode(nested(99)).left.exists {
        case NtcQuery.Error.UnknownTag(_, 99) => true
        case _                                => false
      })
    }
  }

  test("pre-Conway era gate peels wrappers for both accepted and refused query kinds") {
    val beforeConway = NodeSetup
      .fromDirectory(Path.of("fixtures/eras/genesis"))
      .fold(e => fail(e.message), identity)
    // The public era capture has 50 Byron blocks, followed by its first Shelley block.
    val state = Files
      .readAllLines(Path.of("fixtures/eras/blocks.hex"))
      .asScala
      .filter(_.nonEmpty)
      .take(51)
      .foldLeft(beforeConway.mirrorStart) { (current, line) =>
        val raw = Bytes.fromHex(line).fold(e => fail(e.message), identity)
        val block = Block
          .decode(raw, beforeConway.geometry.byron.epochSlots)
          .fold(e => fail(e.message), identity)
        MirrorSteps
          .block(beforeConway, current, block.header, raw)
          .fold(e => fail(e.toString), identity)
      }
    val early = QueryView(Point.Origin, None, state)
    assertEquals(early.era, Era.Shelley)
    onSmallStack {
      def wrapped(leaf: LedgerQuery): NtcQuery = NtcQuery.IfCurrent(
        early.era.index,
        (0 until depth).foldLeft(leaf)((q, _) => LedgerQuery.GetCBOR(q))
      )
      assertEquals(
        QueryAnswers.answerOrWhy(env, early, Map.empty, wrapped(LedgerQuery.GetEpochNo)),
        Left(Unanswered.Unsupported)
      )
      val answer = QueryAnswers
        .answerOrWhy(env, early, Map.empty, wrapped(LedgerQuery.DebugChainDepState))
        .fold(e => fail(e.toString), identity)
      val plain = QueryAnswers
        .answerOrWhy(
          env,
          early,
          Map.empty,
          NtcQuery.IfCurrent(early.era.index, LedgerQuery.DebugChainDepState)
        )
        .fold(e => fail(e.toString), identity)
      assertEquals(unwrap(result(answer), depth), result(plain))
    }
  }

  test("the transport accepts 20000 wrappers and safely refuses input over its depth bound") {
    onSmallStack {
      val legal = nested(7)
      val wire = LocalStateQuery
        .encode(LocalStateQuery.State.Acquired, Role.Client, LocalStateQuery.Message.Query(legal))
        .fold(e => fail(e.message), identity)
      assertEquals(
        LocalStateQuery.decodePrefix(LocalStateQuery.State.Acquired, Role.Client, wire),
        DecodeResult.Decoded(LocalStateQuery.Message.Query(legal), wire.size)
      )
      val tooDeep = CborOut.build(_.array(2).uint(3).raw(nested(7, 40000)))
      assert(
        LocalStateQuery.decodePrefix(LocalStateQuery.State.Acquired, Role.Client, tooDeep) match
          case DecodeResult.Failed(_) => true
          case _                      => false
      )
    }
  }

  test("an acquired session answers and logs a refusal at 20000 query layers on a small stack") {
    onSmallStack {
      val program = for
        chain <- Ref.of[IO, VolatileChain[MirrorState]](
          VolatileChain(5, Anchor(None, setup.mirrorStart), Vector.empty)
        )
        store <- MemoryUtxoStore[IO]
        gate <- cats.effect.std.Mutex[IO]
        source = StateQuerySource(chain, IO.pure(env), store, gate)
        results <- source.acquire(LocalStateQuery.Target.VolatileTip).use {
          _.fold(
            {
              case LocalStateQueryServer.AcquireFailure.Rejected(e) => IO.delay(fail(e.toString))
              case e                                                => IO.delay(fail(e.toString))
            },
            session =>
              for
                answered <- session.answer(nested(7))
                refused <- session.answer(nested(14))
              yield answered -> refused
          )
        }
      yield
        val (answered, refused) = results
        assert(answered.isRight)
        assertEquals(
          refused,
          Right(
            Left(
              Refusal.Unsupported(
                s"IfCurrent(${view.era.index},GetCBOR[$depth](GetRewardProvenance))"
              )
            )
          )
        )
      program.unsafeRunSync()
    }
  }

  private def pinnedReadExercise(store: UtxoStore[IO])(using
      Raise[IO, StorageError]
  ): IO[Unit] =
    val first = TxIn(Bytes(Vector.fill(32)(0x71.toByte)), 0)
    val later = TxIn(Bytes(Vector.fill(32)(0x72.toByte)), 0)
    val addressBytes = Bytes(Vector(0x61.toByte) ++ Vector.fill(28)(0x33.toByte))
    val address = xyz.kd5ujc.scarab.ledger.Address(
      addressBytes,
      xyz.kd5ujc.scarab.ledger.Address.Kind.Shelley(
        1,
        xyz.kd5ujc.scarab.ledger.Credential.Key(Bytes(Vector.fill(28)(0x33.toByte))),
        None,
        None
      )
    )
    val original = xyz.kd5ujc.scarab.ledger.TxOut(
      Bytes.empty,
      address,
      xyz.kd5ujc.scarab.ledger.Value.coin(10),
      xyz.kd5ujc.scarab.ledger.Datum.NoDatum,
      None,
      0
    )
    val changed = original.copy(value = xyz.kd5ujc.scarab.ledger.Value.coin(20))
    def query(args: CborOut => Unit): Bytes = CborOut.build { w =>
      w.array(2).uint(0).array(2).uint(0).array(2).uint(view.era.index)
      args(w)
    }
    val whole = query(_.array(1).uint(7))
    val byAddress = query(w => w.array(2).uint(6).array(1).bytes(addressBytes))
    val byInput =
      query(w => w.array(2).uint(15).array(1).array(2).bytes(first.id).uint(first.index))
    onSmallStack {
      val program = for
        chain <- Ref.of[IO, VolatileChain[MirrorState]](
          VolatileChain(
            5,
            Anchor(None, setup.mirrorStart),
            Vector(
              xyz.kd5ujc.scarab.consensus.BlockRef(1, Bytes(Vector.fill(32)(1.toByte)), 1) ->
                setup.mirrorStart
            )
          )
        )
        _ <- store.write(Vector(first -> Some(original)), 1L)
        gate <- cats.effect.std.Mutex[IO]
        source = StateQuerySource(chain, IO.pure(env), store, gate)
        answers <- source.acquire(LocalStateQuery.Target.VolatileTip).use {
          case Right(session) =>
            for
              initial <- Vector(whole, byAddress, byInput).traverse(session.answer)
              _ <- store.write(Vector(first -> Some(changed), later -> Some(original)), 10L)
              anchor = xyz.kd5ujc.scarab.consensus
                .BlockRef(10, Bytes(Vector.fill(32)(10.toByte)), 10)
              rolledForward = VolatileChain(
                5,
                Anchor(Some(anchor), setup.mirrorStart),
                (11 to 15).toVector.map(n =>
                  xyz.kd5ujc.scarab.consensus.BlockRef(
                    n,
                    Bytes(Vector.fill(32)(n.toByte)),
                    n
                  ) -> setup.mirrorStart
                )
              )
              _ <- chain.set(rolledForward)
              rolledBack <- chain.get.map(
                _.rollback(anchor.point).fold(e => fail(e.toString), identity)
              )
              _ <- chain.set(rolledBack)
              retained <- Vector(whole, byAddress, byInput).traverse(session.answer)
            yield initial -> retained
          case Left(error) => IO.raiseError(new RuntimeException(error.toString))
        }
      yield assertEquals(answers._2, answers._1)
      program
    }

  test("a Conway LSQ session pins whole, address and input reads with memory and Rocks views") {
    given Raise[IO, StorageError] with
      def functor = cats.Functor[IO]
      def raise[E <: StorageError, A](error: E): IO[A] =
        IO.raiseError(new RuntimeException(error.message))
    val dir = Files.createTempDirectory("deep-query-rocks")
    val rocks = RocksUtxoStore
      .resource[IO](Fs2Path.fromNioPath(dir) / "live", 8L * 1024 * 1024)
      .use(pinnedReadExercise)
      .guarantee(
        IO.blocking(Files.walk(dir))
          .bracket { paths =>
            IO.blocking(paths.iterator.asScala.toVector)
              .flatMap(
                _.sortBy(_.getNameCount).reverse
                  .traverse_(path => IO.blocking(Files.deleteIfExists(path)).void)
              )
          }(paths => IO.blocking(paths.close()))
      )
    (MemoryUtxoStore[IO].flatMap(pinnedReadExercise) *> rocks).unsafeRunSync()
  }

  test("LSQ capture waits for the shared gate between backing write and chain publication") {
    given Raise[IO, StorageError] with
      def functor = cats.Functor[IO]
      def raise[E <: StorageError, A](error: E): IO[A] =
        IO.raiseError(new RuntimeException(error.message))
    onSmallStack {
      val program = for
        chain <- Ref.of[IO, VolatileChain[MirrorState]](
          VolatileChain(5, Anchor(None, setup.mirrorStart), Vector.empty)
        )
        store <- MemoryUtxoStore[IO]
        gate <- cats.effect.std.Mutex[IO]
        source = StateQuerySource(chain, IO.pure(env), store, gate)
        advanced <- Deferred[IO, Unit]
        publish <- Deferred[IO, Unit]
        acquired <- Deferred[IO, Either[
          LocalStateQueryServer.AcquireFailure[StorageError],
          LocalStateQueryServer.Session[IO, StorageError]
        ]]
        began <- Deferred[IO, Unit]
        ref = xyz.kd5ujc.scarab.consensus.BlockRef(1, Bytes(Vector.fill(32)(1.toByte)), 1)
        after = VolatileChain(5, Anchor(None, setup.mirrorStart), Vector(ref -> setup.mirrorStart))
        writer <- gate.lock
          .surround(
            store.write(Vector.empty, 1L) *> advanced.complete(()) *> publish.get *> chain.set(
              after
            )
          )
          .start
        _ <- advanced.get
        reader <- (began.complete(()) *> source.acquire(LocalStateQuery.Target.VolatileTip).use {
          result =>
            acquired.complete(result)
        }).start
        _ <- began.get *> IO.sleep(20.millis)
        beforePublish <- acquired.tryGet
        _ <- publish.complete(())
        _ <- writer.joinWithNever
        result <- acquired.get
        _ <- reader.joinWithNever
      yield
        assertEquals(beforePublish, None)
        assert(result.isRight, result.toString)
      program.unsafeRunSync()
    }
  }

  test("canceling LSQ acquisition while waiting for the publication gate completes promptly") {
    val program = for
      chain <- Ref.of[IO, VolatileChain[MirrorState]](
        VolatileChain(5, Anchor(None, setup.mirrorStart), Vector.empty)
      )
      store <- MemoryUtxoStore[IO]
      backing <- Mutex[IO]
      waiting <- Deferred[IO, Unit]
      calls <- Ref.of[IO, Int](0)
      gate = new Mutex[IO]:
        def lock: Resource[IO, Unit] =
          Resource.eval {
            calls.update(_ + 1) *> calls.get.flatMap { count =>
              if count == 2 then waiting.complete(()).void else IO.unit
            }
          } *> backing.lock
        override def mapK[G[_]](f: FunctionK[IO, G])(using
            G0: MonadCancel[G, ?]
        ): Mutex[G] =
          backing.mapK(f)(using G0)
      source = StateQuerySource(chain, IO.pure(env), store, gate)
      cancellation <- gate.lock.use { _ =>
        source
          .acquire(LocalStateQuery.Target.VolatileTip)
          .use(_ => IO.unit)
          .start
          .flatMap { fiber =>
            waiting.get
              .as(true)
              .timeoutTo(2.seconds, IO.pure(false))
              .flatMap { observed =>
                Deferred[IO, Unit].flatMap { cancelled =>
                  (fiber.cancel *> cancelled.complete(())).start.flatMap { canceller =>
                    (IO.sleep(250.millis) *> cancelled.tryGet).map { completedBeforeUnlock =>
                      (observed, completedBeforeUnlock.nonEmpty, fiber, canceller)
                    }
                  }
                }
              }
          }
      }
      readerDone <- cancellation._3.join
        .as(true)
        .timeoutTo(2.seconds, IO.pure(false))
      cancellerDone <- cancellation._4.join
        .as(true)
        .timeoutTo(2.seconds, IO.pure(false))
    yield
      assert(cancellation._1, "the reader must reach the contended gate")
      assert(cancellation._2, "cancel should finish before the gate is released")
      assert(readerDone, "the reader must terminate after gate cleanup")
      assert(cancellerDone, "the cancellation fiber must terminate after gate cleanup")
    TestControl.executeEmbed(program).unsafeRunSync()
  }
