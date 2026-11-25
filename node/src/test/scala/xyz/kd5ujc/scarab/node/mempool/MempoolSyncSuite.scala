// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.Applicative
import cats.effect.{Deferred, Fiber, IO, Ref, Resource}
import cats.effect.std.Mutex
import cats.effect.testkit.TestControl
import cats.mtl.{Ask, Handle, Raise, Stateful}
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.{Signal, SignallingRef}
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, BlockRules, ChainSelection, Scheduling, VolatileChain}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.{ChainError, NodeError, StorageError}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.selection.Gsm
import xyz.kd5ujc.scarab.node.{Fatal, Follower, Main, RefStateful}
import xyz.kd5ujc.scarab.node.assertions.Assertions
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.node.read.ConwayView
import xyz.kd5ujc.scarab.plutus.ScarabEvaluator
import xyz.kd5ujc.scarab.protocol.ChainSync

/** The mempool's watcher on the tip ([[MempoolSync]]): it looks at the published chain once at
  * start and then whenever its tip changed, and the pool reads the chain again once its lock is
  * held ([[Mempool.synchronise]]), as the reference's `forkSyncStateOnTipPointChange` and
  * `implSyncWithLedger` do, so that a transaction being judged under the pool's lock holds back no
  * chain selection, and a revalidation that waited behind it applies the chain as it is then.
  */
class MempoolSyncSuite extends FailsOnUncaught with ScalaCheckSuite:
  private given Assertions[IO] = Assertions.noop[IO]
  import TxKit.{env, ledger, pay}

  /** A chain of the watcher's own tests: its tip, and where a pool revalidates at it. */
  private final case class Chain(tip: ChainSync.Point, at: Mempool.Target)

  private def hashOf(id: Int): Bytes = Bytes(Vector(7.toByte, id.toByte))

  private def chainAt(
      id: Int,
      slot: BigInt,
      ledger: Either[ConwayView.Unavailable, PoolLedger] = Left(ConwayView.Unavailable.InByron),
      adopted: Set[Bytes] = Set.empty
  ): Chain =
    Chain(
      BlockRef(slot, hashOf(id), BigInt(id)).point,
      Mempool.Target(ledger, slot, adopted, Some(hashOf(id)))
    )

  private val origin: Chain =
    Chain(
      ChainSync.Point.Origin,
      Mempool.Target(Left(ConwayView.Unavailable.InByron), BigInt(-1), Set.empty, None)
    )

  /** As the node's target: none when the pool is at the chain's tip; `looked` records each chain
    * read, and `held` what is done with it before it is answered.
    */
  private def targetOf(
      looked: SignallingRef[IO, Vector[ChainSync.Point]],
      held: Chain => IO[Unit] = _ => IO.unit
  ): MempoolSync.Target[IO, Chain] = new MempoolSync.Target[IO, Chain]:
    def apply(pool: MempoolState, chain: Chain)(using Raise[IO, NodeError]) =
      looked.update(_ :+ chain.tip) *> held(chain).as(
        Option.unless(pool.tipHash == chain.at.tipHash && pool.tipSlot == chain.at.tipSlot)(
          chain.at
        )
      )

  /** A pool with no ledger at Origin, which takes every revalidation. */
  private def emptyPool: IO[Mempool[IO]] =
    Mempool.create[IO](Left(ConwayView.Unavailable.InByron), BigInt(-1), env, None, _ => IO.unit)

  test("the watcher looks once at start, then at the chain as it is once it has the pool's lock") {
    val (b1, b2, b3) = (chainAt(1, 1), chainAt(2, 2), chainAt(3, 3))
    for
      mempool <- emptyPool
      chains <- SignallingRef.of[IO, Chain](origin)
      looked <- SignallingRef.of[IO, Vector[ChainSync.Point]](Vector.empty)
      busy <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      reported <- Deferred[IO, Fatal]
      // the revalidation at b1 holds the watcher, with the pool's lock, until it is released
      target = targetOf(
        looked,
        chain => (busy.complete(()) *> release.get).whenA(chain.tip == b1.tip)
      )
      synced <- MempoolSync
        .resource[IO, Chain](mempool, chains, _.tip, target, f => reported.complete(f).void)
        .use(_ =>
          looked.waitUntil(_.nonEmpty) *> chains.set(b1) *> busy.get *> chains.set(b2) *>
            chains.set(b3) *> release.complete(()) *>
            mempool.awaitChange(_.tipHash != b3.at.tipHash)
        )
      seen <- looked.get
      failed <- reported.tryGet
    yield
      assertEquals(seen.headOption, Some(ChainSync.Point.Origin), "one look at start")
      assertEquals(
        seen.drop(1).take(2),
        Vector(b1.tip, b3.tip),
        "then one at b1, and one at b3, the latest, for b2 and b3 published while it ran"
      )
      assertEquals(synced.tipHash, b3.at.tipHash, "the pool is at the latest tip")
      assertEquals(failed, None, "nothing stopped the node")
  }

  /** What stops the node when the revalidation at the first commit fails with `failing`. */
  private def stoppedBy(failing: Raise[IO, NodeError] ?=> IO[Unit]): IO[Fatal] =
    for
      mempool <- emptyPool
      chains <- SignallingRef.of[IO, Chain](origin)
      reported <- Deferred[IO, Fatal]
      target = new MempoolSync.Target[IO, Chain]:
        def apply(pool: MempoolState, chain: Chain)(using Raise[IO, NodeError]) =
          if chain.tip == ChainSync.Point.Origin then IO.pure(None) else failing.as(None)
      fatal <- MempoolSync
        .resource[IO, Chain](mempool, chains, _.tip, target, f => reported.complete(f).void)
        .use(_ => chains.set(chainAt(1, 1)) *> reported.get)
    yield fatal

  test("a revalidation that fails stops the node with its typed cause") {
    val storage = StorageError.Io("utxo", new java.io.IOException("read failed"))
    val bug = new IllegalStateException("a bug in the revalidation")
    for
      byStorage <- stoppedBy(Raise[IO, NodeError].raise[NodeError, Unit](storage))
      byChain <- stoppedBy(Raise[IO, NodeError].raise[NodeError, Unit](ChainError.NoIntersection))
      byException <- stoppedBy(IO.raiseError(bug))
    yield
      assertEquals(byStorage, Fatal.Storage(storage), "a storage failure stops the node as one")
      assertEquals(
        byChain,
        Fatal.Internal(ChainError.NoIntersection),
        "any other failure of the node's own"
      )
      byException match
        case Fatal.Defect(cause) => assert(cause eq bug, s"the exception itself, got $cause")
        case other               => fail(s"an exception is a defect of the node, got $other")
  }

  /** An environment whose next reading, once armed, waits: the reading a transaction's admission
    * makes under the pool's lock, held for as long as the test wants.
    */
  private def holding(
      armed: Ref[IO, Option[(Deferred[IO, Unit], Deferred[IO, Unit])]],
      env: MempoolEnv
  ): Ask[IO, MempoolEnv] = new Ask[IO, MempoolEnv]:
    val applicative = Applicative[IO]
    def ask[E >: MempoolEnv]: IO[E] =
      armed
        .getAndSet(None)
        .flatMap(_.traverse_((entered, release) => entered.complete(()) *> release.get))
        .as(env)

  /** `admit` running in a fiber of its own for `use`. On every way out, the success path's
    * included, its gate `release` is completed and the fiber cancelled (a no-op once joined), so
    * that no admission is left holding the pool's lock (TST-5).
    */
  private def admitting[A, B](admit: IO[A], release: Deferred[IO, Unit])(
      use: Fiber[IO, Throwable, A] => IO[B]
  ): IO[B] =
    Resource.make(admit.start)(fiber => release.complete(()).void *> fiber.cancel).use(use)

  /** `chains` with what the watcher took of it recorded in `seen`, as it takes it. */
  private def observed[A](
      chains: Signal[IO, A],
      seen: SignallingRef[IO, Vector[A]]
  ): Signal[IO, A] =
    new Signal[IO, A]:
      def discrete: Stream[IO, A] = chains.discrete.evalTap(a => seen.update(_ :+ a))
      def continuous: Stream[IO, A] = chains.continuous
      def get: IO[A] = chains.get

  test(
    "a revalidation that waited behind an admission applies the chain as it is then, not a tip " +
      "the chain has left: the admitted transaction stays"
  ) {
    import TxKit.applied
    val base = ledger(2)
    def parsed(raw: Bytes) = MempoolState.parse(raw).fold(r => fail(r.message), identity)
    // the transaction admitted, one block's that spends its input, and one of a block beside it
    val (t, _) = pay(1)
    val (conflict, _) = pay(1, fee = 2 * TxKit.ada)
    val (other, _) = pay(2)
    val a = chainAt(10, 1000, Right(base))
    val b = chainAt(11, 1001, Right(base.applied(conflict)), Set(parsed(conflict)._1))
    val c = chainAt(12, 1001, Right(base.applied(other)), Set(parsed(other)._1))
    for
      log <- Ref.of[IO, Vector[MempoolEvent]](Vector.empty)
      armed <- Ref.of[IO, Option[(Deferred[IO, Unit], Deferred[IO, Unit])]](None)
      mempool <- Mempool.create[IO](
        Right(base),
        BigInt(1000),
        env,
        None,
        (e: MempoolEvent) => log.update(_ :+ e),
        environment = Some(holding(armed, env)),
        tipHash = a.at.tipHash
      )
      chains <- SignallingRef.of[IO, Chain](a)
      taken <- SignallingRef.of[IO, Vector[Chain]](Vector.empty)
      looked <- SignallingRef.of[IO, Vector[ChainSync.Point]](Vector.empty)
      reported <- Deferred[IO, Fatal]
      outcome <- MempoolSync
        .resource[IO, Chain](
          mempool,
          observed(chains, taken),
          _.tip,
          targetOf(looked),
          f => reported.complete(f).void
        )
        .use(_ =>
          Handle
            .allow[NodeError] {
              for
                // the look at start is over: the pool is at a
                _ <- looked.waitUntil(_.nonEmpty)
                entered <- Deferred[IO, Unit]
                release <- Deferred[IO, Unit]
                _ <- armed.set(Some(entered -> release))
                // a peer's transaction, judged against a, holding the pool's lock
                outcome <- admitting(mempool.add(t, Source.Remote), release) { admission =>
                  for
                    _ <- entered.get
                    // b is adopted and the watcher takes it, then b is replaced by c
                    _ <- chains.set(b)
                    _ <- taken.waitUntil(_.contains(b))
                    _ <- chains.set(c)
                    _ <- release.complete(())
                    admitted <- admission.joinWithNever
                    synced <- IO.race(
                      reported.get,
                      mempool.awaitChange(_.tipHash != c.at.tipHash)
                    )
                  yield (admitted, synced)
                }
              yield outcome
            }
            .rescue(e => IO.raiseError(new AssertionError(e.message)))
        )
      events <- log.get
      seen <- looked.get
    yield
      val (admitted, synced) = outcome
      val id = parsed(t)._1
      assertEquals(admitted, Right(id), "the transaction is admitted against a")
      synced match
        case Left(fatal) => fail(s"the watcher stopped the node: ${fatal.message}")
        case Right(state) =>
          assertEquals(state.tipHash, c.at.tipHash, "the pool is at c")
          assertEquals(
            state.entries.map(_.id),
            Vector(id),
            "the transaction stays: c leaves it valid"
          )
      assert(!seen.contains(b.tip), s"the pool never revalidated at b, which the chain left: $seen")
      assertEquals(
        events.collect { case MempoolEvent.Added(added, _, _, _) => added },
        Vector(id),
        "added once"
      )
      assertEquals(
        events.collect { case MempoolEvent.Dropped(dropped, _, _) => dropped },
        Vector.empty,
        "never dropped"
      )
      assertEquals(
        events.collect { case MempoolEvent.Adopted(adopted) => adopted },
        Vector.empty,
        "never taken for adopted"
      )
  }

  test("a revalidation that fails stops the node after the commit: the chain stays published") {
    val failure = StorageError.Io("utxo", new java.io.IOException("read failed"))
    for
      forged <- ForgedChain.forge(5000 until 5010)
      ref <- Ref.of[IO, VolatileChain[BigInt]](
        VolatileChain.genesis(ForgedChain.geometry.k.toInt, BigInt(-1))
      )
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(ref)
      follower <- Follower.create[IO, BigInt](
        BlockRules.from((_, header, _) => IO.pure(header.blockNo)),
        _ => IO.unit,
        ForgedChain.geometry
      )
      mempool <- emptyPool
      reported <- Deferred[IO, Fatal]
      target = new MempoolSync.Target[IO, VolatileChain[BigInt]]:
        def apply(pool: MempoolState, chain: VolatileChain[BigInt])(using Raise[IO, NodeError]) =
          if chain.tip == ChainSync.Point.Origin then IO.pure(None)
          else Raise[IO, NodeError].raise[NodeError, Option[Mempool.Target]](failure)
      adopted <- MempoolSync
        .resource[IO, VolatileChain[BigInt]](
          mempool,
          follower.committedChain,
          _.tip,
          target,
          f => reported.complete(f).void
        )
        .use(_ =>
          Handle
            .allow[NodeError](follower.adoptForged(forged.headers(0), forged.raws(0)))
            .rescue(e => IO.raiseError(new AssertionError(e.message)))
            .flatMap(adoption => reported.get.map(adoption -> _))
        )
      chain <- ref.get
    yield
      val header = forged.headers(0)
      val ref0 = BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary)
      assertEquals(adopted._1, Follower.Adoption.Adopted(ref0), "the commit is not undone")
      assertEquals(adopted._2, Fatal.Storage(failure), "the node stops with the failure")
      assertEquals(chain.tip, header.point, "the block is on the published chain")
  }

  /** What the node did while a peer's transaction held the pool's lock, and once it let go. */
  private final case class Observed(
      first: Follower.Adoption,
      second: Follower.Adoption,
      versus: Gsm.Candidate,
      gated: ChainSync.Point,
      whileHeld: MempoolState,
      refused: Boolean,
      synced: Either[Fatal, MempoolState],
      tip: ChainSync.Point,
      looked: Vector[ChainSync.Point]
  )

  /** The real follower over a forged chain, the real pool and the real watcher with the node's
    * target (`Main.mempoolTarget`), the pool's lock held by a peer's transaction whose reading of
    * the environment waits.
    */
  private def whileAdmitting(forged: ForgedChain): IO[Observed] =
    val env = MempoolEnv(
      ForgedChain.globals,
      ForgedChain.geometry,
      ScarabEvaluator.default,
      ScarabEvaluator.defaultDecoder
    )
    val start = VolatileChain.genesis(ForgedChain.geometry.k.toInt, forged.genesis)
    val transition = ForgedChain.config.forks.transition(ForgedChain.geometry.k)
    val bytes = forged.headers.map(_.hash).zip(forged.raws).toMap
    for
      ref <- Ref.of[IO, VolatileChain[MirrorState]](start)
      given Stateful[IO, VolatileChain[MirrorState]] = RefStateful(ref)
      // the follower's lock, which a state query's acquire also takes (`StateQuerySource`)
      gate <- Mutex[IO]
      follower <- Follower.create[IO, MirrorState](
        ForgedChain.rules,
        _ => IO.unit,
        ForgedChain.geometry,
        chainGate = Some(gate)
      )
      armed <- Ref.of[IO, Option[(Deferred[IO, Unit], Deferred[IO, Unit])]](None)
      mempool <- Mempool.create[IO](
        Left(ConwayView.Unavailable.InByron),
        BigInt(-1),
        env,
        None,
        MempoolEvents.none[IO],
        environment = Some(holding(armed, env))
      )
      scheduling <- Scheduling.inline[IO]
      synced <- Ref.of[IO, Vector[Option[Bytes]]](Vector(None))
      looked <- SignallingRef.of[IO, Vector[ChainSync.Point]](Vector.empty)
      reported <- Deferred[IO, Fatal]
      node = Main.mempoolTarget(
        scheduling,
        ForgedChain.geometry.byron.epochSlots,
        transition,
        hash => IO.pure(bytes.get(hash)),
        synced
      )(using Ask.const[IO, MempoolEnv](env))
      target = new MempoolSync.Target[IO, VolatileChain[MirrorState]]:
        def apply(pool: MempoolState, chain: VolatileChain[MirrorState])(using
            Raise[IO, NodeError]
        ) = looked.update(_ :+ chain.tip) *> node(pool, chain)
      observed <- MempoolSync
        .resource[IO, VolatileChain[MirrorState]](
          mempool,
          follower.committedChain,
          _.tip,
          target,
          f => reported.complete(f).void
        )
        .use(_ =>
          Handle
            .allow[NodeError] {
              for
                // the look at start is over: the pool is at the chain's tip
                _ <- looked.waitUntil(_.nonEmpty)
                entered <- Deferred[IO, Unit]
                release <- Deferred[IO, Unit]
                _ <- armed.set(Some(entered -> release))
                // a peer's transaction, refused once its turn comes, holding the lock meanwhile
                observed <- admitting(
                  mempool.add(Bytes(Vector(0x80.toByte)), Source.Remote),
                  release
                ) { admission =>
                  for
                    _ <- entered.get
                    first <- follower.adoptForged(forged.headers(0), forged.raws(0))
                    second <- follower.adoptForged(forged.headers(1), forged.raws(1))
                    versus <- follower.versus(
                      ChainSelection.Candidate(ChainSync.Point.Origin, forged.headers.take(3))
                    )
                    gated <- gate.lock.surround(ref.get.map(_.tip))
                    whileHeld <- mempool.snapshot
                    _ <- release.complete(())
                    refused <- admission.joinWithNever
                    synced <- IO.race(
                      reported.get,
                      mempool.awaitChange(_.tipHash != Some(forged.headers(1).hash))
                    )
                    chain <- ref.get
                    seen <- looked.get
                  yield Observed(
                    first,
                    second,
                    versus,
                    gated,
                    whileHeld,
                    refused.isLeft,
                    synced,
                    chain.tip,
                    seen
                  )
                }
              yield observed
            }
            .rescue(e => IO.raiseError(new AssertionError(e.message)))
        )
    yield observed

  test(
    "a transaction holding the pool's lock holds back no commit, adoption, GSM comparison or " +
      "state-query acquire, and the pool catches up at the chain's tip once it lets go"
  ) {
    ForgedChain.forge(5000 until 5020).flatMap { forged =>
      val (h0, h1) = (forged.headers(0), forged.headers(1))
      // on a schedule of its own: a wait for the pool's lock would leave nothing to run
      TestControl
        .executeEmbed(whileAdmitting(forged))
        .adaptError { case _: TestControl.NonTerminationException =>
          new AssertionError(
            "chain selection waited for the transaction holding the mempool's lock"
          )
        }
        .map { o =>
          assertEquals(
            o.first,
            Follower.Adoption.Adopted(BlockRef(h0.slot, h0.hash, h0.blockNo, h0.isBoundary)),
            "the first block is committed while the transaction holds the lock"
          )
          assertEquals(
            o.second,
            Follower.Adoption.Adopted(BlockRef(h1.slot, h1.hash, h1.blockNo, h1.isBoundary)),
            "and the next"
          )
          assertEquals(o.versus, Gsm.Candidate.Better(true), "the GSM's comparison answers")
          assertEquals(o.gated, h1.point, "a state query's acquire gets the chain")
          assertEquals(
            o.whileHeld.tipHash,
            None,
            "the pool did not move while the transaction held its lock"
          )
          assert(o.refused, "the transaction is answered once its turn comes")
          o.synced match
            case Left(fatal) => fail(s"the watcher stopped the node: ${fatal.message}")
            case Right(state) =>
              assertEquals(state.tipHash, Some(h1.hash), "the pool caught up with the tip")
              assertEquals(state.tipSlot, h1.slot, "at the tip's slot")
          assertEquals(o.tip, h1.point, "the chain is at the second block")
          assertEquals(o.looked.headOption, Some(ChainSync.Point.Origin), "one look at start")
          assert(
            !o.looked.contains(h0.point),
            s"the chain is read once the lock is held, when it is at the second block: ${o.looked}"
          )
          assert(o.looked.contains(h1.point), s"and revalidated there: ${o.looked}")
        }
    }
  }

  /** A model of chains for [[MempoolSync.unseen]]: blocks named by distinct numbers, a window of
    * the last `k` blocks after an anchor (none at Origin), and a fork of at most `k` blocks.
    */
  private def window(chain: Vector[Int], k: Int): Vector[Option[Bytes]] =
    val identities = chain.map(i => Option(Bytes.fromArray(BigInt(i).toByteArray)))
    if chain.sizeIs > k then identities.takeRight(k + 1) else None +: identities

  private val forks: Gen[(Int, Vector[Int], Vector[Int])] =
    for
      k <- Gen.choose(1, 6)
      shared <- Gen.choose(0, 12)
      left <- Gen.choose(0, k)
      right <- Gen.choose(0, k)
    yield (
      k,
      Vector.tabulate(shared)(identity) ++ Vector.tabulate(left)(i => 100 + i),
      Vector.tabulate(shared)(identity) ++ Vector.tabulate(right)(i => 200 + i)
    )

  property("unseen: the blocks of the chain's window the pool was not revalidated past") {
    Prop.forAll(forks) { (k, previous, current) =>
      val synced = window(previous, k)
      val count = MempoolSync.unseen(synced, synced.last, window(current, k))
      // the blocks the chain keeps that the chain the pool was revalidated at did not hold
      val expected = current.takeRight(k).count(b => !previous.contains(b))
      Prop(count == expected)
        .label(s"k=$k previous=$previous current=$current: $count, expected $expected")
    }
  }

  property("unseen: without the chain it was revalidated at, the blocks after the pool's tip") {
    Prop.forAll(forks, Gen.choose(0, 40)) { case ((k, _, current), pick) =>
      val identities = window(current, k)
      val tip = identities.lift(pick).flatten.orElse(Some(Bytes(Vector(9, 9))))
      val count = MempoolSync.unseen(Vector(Some(Bytes(Vector(8, 8)))), tip, identities)
      val expected = identities.lastIndexOf(tip) match
        case -1    => 0
        case index => identities.size - 1 - index
      Prop(count == expected).label(s"k=$k current=$current tip=$tip: $count, expected $expected")
    }
  }
