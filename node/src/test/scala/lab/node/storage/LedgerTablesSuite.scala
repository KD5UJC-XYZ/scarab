// SPDX-License-Identifier: Apache-2.0
package lab.node.storage

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.io.file.Path
import lab.cbor.Bytes
import lab.node.*
import lab.node.ledger.*
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*

/** The ledger tables (accounts, pools, DReps, the three stake snapshots) behind a store: the
  * in-memory and the RocksDB interpreters against plain `Map` models under random sequences of
  * blocks (point writes, bulk edits that scan, snapshot rotations), rollbacks, flushes and
  * checkpoint restores (roadmap D1). Every block is applied through `LedgerStore.completingF`, so
  * the rows a step reads are fetched on demand exactly as the node does it.
  */
class LedgerTablesSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(40)

  // -------------------------------------------------------------------------------------------
  // The model: what the tables must hold

  private final case class Model(
      accounts: Map[Credential, Account],
      pools: Map[Bytes, PoolState],
      dreps: Map[Credential, DRepState],
      snaps: Vector[(Long, Map[Credential, BigInt], Map[Credential, Bytes])] // mark, set, go
  )

  private val emptyModel =
    Model(Map.empty, Map.empty, Map.empty, Vector.fill(3)((0L, Map.empty, Map.empty)))

  private def be(n: Long, size: Int): Array[Byte] =
    Array.tabulate(size)(i => ((n >>> (8 * (size - 1 - i))) & 0xff).toByte)
  private def cred(i: Int): Credential = Credential.Key(Bytes.wrap(be(i.toLong, 28)))
  private def poolId(i: Int): Bytes = Bytes.wrap(be(1000L + i, 28))

  private def poolParams(i: Int): PoolParams =
    PoolParams(
      poolId(i),
      Bytes.wrap(be(2000L + i, 32)),
      i,
      340,
      Rational.zero,
      RewardAccount(0, cred(i)),
      Set.empty,
      Bytes.empty,
      None
    )

  // -------------------------------------------------------------------------------------------
  // Operations of a block

  private enum Op:
    case PutAccount(k: Int, balance: Long, deposit: Long, pool: Option[Int])
    case DelAccount(k: Int)
    case PutPool(k: Int, deposit: Long)
    case DelPool(k: Int)
    case PutDRep(k: Int, deposit: Long, expiry: Long, delegs: Set[Int])
    case DelDRep(k: Int)

    /** A bulk edit that scans the table, as the epoch boundary does. */
    case BumpAccounts(by: Long)
    case Rotate(stake: Map[Int, Long], delegations: Map[Int, Int])

  private val keys = Gen.choose(0, 12)

  private val genOp: Gen[Op] = Gen.frequency(
    6 -> (for
      k <- keys; b <- Gen.choose(0L, 1000L); d <- Gen.choose(0L, 50L);
      p <- Gen.option(Gen.choose(0, 3))
    yield Op.PutAccount(k, b, d, p)),
    2 -> keys.map(Op.DelAccount(_)),
    2 -> (for k <- Gen.choose(0, 3); d <- Gen.choose(0L, 500L) yield Op.PutPool(k, d)),
    1 -> Gen.choose(0, 3).map(Op.DelPool(_)),
    3 -> (for
      k <- keys; d <- Gen.choose(0L, 500L); e <- Gen.choose(0L, 100L)
      delegs <- Gen.listOf(keys).map(_.toSet.take(4))
    yield Op.PutDRep(k, d, e, delegs)),
    1 -> keys.map(Op.DelDRep(_)),
    1 -> Gen.choose(1L, 9L).map(Op.BumpAccounts(_)),
    2 -> (for
      stake <- Gen.mapOf(Gen.zip(keys, Gen.choose(1L, 5000L)))
      deleg <- Gen.mapOf(Gen.zip(keys, Gen.choose(0, 3)))
    yield Op.Rotate(stake, deleg))
  )

  private enum Action:
    case Block(ops: Vector[Op])
    case Rollback(depth: Int)

  private val genAction: Gen[Action] = Gen.frequency(
    8 -> Gen.choose(1, 5).flatMap(Gen.listOfN(_, genOp)).map(ops => Action.Block(ops.toVector)),
    2 -> Gen.choose(1, 4).map(Action.Rollback(_))
  )

  private def account(b: Long, d: Long, p: Option[Int]) =
    Account(b, d, p.map(poolId), None)

  private def applyModel(m: Model, op: Op): Model = op match
    case Op.PutAccount(k, b, d, p) =>
      m.copy(accounts = m.accounts.updated(cred(k), account(b, d, p)))
    case Op.DelAccount(k) => m.copy(accounts = m.accounts - cred(k))
    case Op.PutPool(k, d) =>
      m.copy(pools = m.pools.updated(poolId(k), PoolState(poolParams(k), d)))
    case Op.DelPool(k) => m.copy(pools = m.pools - poolId(k))
    case Op.PutDRep(k, d, e, delegs) =>
      m.copy(dreps = m.dreps.updated(cred(k), DRepState(d, e, None, delegs.map(cred))))
    case Op.DelDRep(k) => m.copy(dreps = m.dreps - cred(k))
    case Op.BumpAccounts(by) =>
      m.copy(accounts = m.accounts.view.mapValues(a => a.copy(balance = a.balance + by)).toMap)
    case Op.Rotate(stake, deleg) =>
      val gen = m.snaps.head._1 + 1
      m.copy(snaps =
        Vector(
          (
            gen,
            stake.map((k, v) => cred(k) -> BigInt(v)),
            deleg.map((k, p) => cred(k) -> poolId(p))
          ),
          m.snaps(0),
          m.snaps(1)
        )
      )

  private def applyLedger(l: LedgerState, op: Op): LedgerState = op match
    case Op.PutAccount(k, b, d, p) =>
      l.copy(accounts = l.accounts.updated(cred(k), account(b, d, p)))
    case Op.DelAccount(k) => l.copy(accounts = l.accounts - cred(k))
    case Op.PutPool(k, d) =>
      l.copy(pools = l.pools.updated(poolId(k), PoolState(poolParams(k), d)))
    case Op.DelPool(k) => l.copy(pools = l.pools - poolId(k))
    case Op.PutDRep(k, d, e, delegs) =>
      l.copy(dreps = l.dreps.updated(cred(k), DRepState(d, e, None, delegs.map(cred))))
    case Op.DelDRep(k) => l.copy(dreps = l.dreps - cred(k))
    case Op.BumpAccounts(by) =>
      l.copy(accounts = l.accounts.mapRows((_, a) => a.copy(balance = a.balance + by)))
    case Op.Rotate(stake, deleg) =>
      val mark = l.snapshots.mark
      val fresh = Snapshot.of(
        mark.gen + 1,
        stake.map((k, v) => cred(k) -> BigInt(v)),
        deleg.map((k, p) => cred(k) -> poolId(p)),
        Map.empty,
        l.accounts.stamp
      )
      l.copy(snapshots = l.snapshots.copy(mark = fresh, set = mark, go = l.snapshots.set))

  // -------------------------------------------------------------------------------------------
  // The chain: the follower's window of states over a store

  private final case class Chain(
      states: Vector[LedgerState], // tip last; the window
      anchor: LedgerState,
      models: Vector[Model],
      anchorModel: Model,
      blockNo: Long
  )

  private val k = 3

  private def given_[A](program: Handle[IO, StorageError] ?=> IO[A]): A =
    Handle
      .allow[StorageError](program)
      .rescue(e => IO.raiseError(new RuntimeException(e.message)))
      .unsafeRunSync()

  private def start: Chain =
    Chain(Vector.empty, TestLedger.state(8, Vector.empty), Vector.empty, emptyModel, 0)

  private def run(store: LedgerStore[IO], actions: List[Action])(using
      Handle[IO, StorageError]
  ): IO[(Chain, Boolean)] = runFrom(store, start, actions)

  private def runFrom(store: LedgerStore[IO], from: Chain, actions: List[Action])(using
      Handle[IO, StorageError]
  ): IO[(Chain, Boolean)] =
    actions.foldLeft(IO.pure((from, true))) { (acc, action) =>
      acc.flatMap { case (chain, ok) =>
        step(store, chain, action).flatMap { next =>
          check(store, next).map(good => next -> (ok && good))
        }
      }
    }

  private def step(store: LedgerStore[IO], chain: Chain, action: Action)(using
      Handle[IO, StorageError]
  ): IO[Chain] = action match
    case Action.Rollback(depth) =>
      val d = math.min(depth, chain.states.size)
      IO.pure(
        chain.copy(
          states = chain.states.dropRight(d),
          models = chain.models.dropRight(d),
          blockNo = chain.blockNo - d
        )
      )
    case Action.Block(ops) =>
      val tip = chain.states.lastOption.getOrElse(chain.anchor)
      val model = chain.models.lastOption.getOrElse(chain.anchorModel)
      val blockNo = chain.blockNo + 1
      for
        done <- LedgerStore.completingF(Some(store), tip.at(blockNo)) { l =>
          IO.pure(Table.guarded(ops.foldLeft(l)(applyLedger)))
        }
        flushed <- store.flushed
        // every few blocks the way a snapshot does it: the whole diff the store holds is dropped
        settled = done._2.settled(flushed, force = blockNo % 5 == 0)
        window = chain.states :+ settled
        models = chain.models :+ ops.foldLeft(model)(applyModel)
        next <-
          if window.size > k then
            LedgerStore
              .advance(store, window.head)
              .as(
                chain.copy(
                  states = window.tail,
                  anchor = window.head,
                  models = models.tail,
                  anchorModel = models.head,
                  blockNo = blockNo
                )
              )
          else IO.pure(chain.copy(states = window, models = models, blockNo = blockNo))
      yield next

  private def snapshots(l: LedgerState) = Vector(l.snapshots.mark, l.snapshots.set, l.snapshots.go)

  /** `l` with everything fetched whole equals `m`; the counters equal a recomputation. */
  private def matches(store: LedgerStore[IO], l: LedgerState, m: Model, what: String)(using
      Handle[IO, StorageError]
  ): IO[Boolean] =
    LedgerStore.materialize(store, l).map { full =>
      assertEquals(full.accounts.toMap, m.accounts, s"$what: accounts")
      assertEquals(full.pools.toMap, m.pools, s"$what: pools")
      assertEquals(full.dreps.toMap, m.dreps, s"$what: dreps")
      assertEquals(l.accounts.count, m.accounts.size.toLong, s"$what: account count")
      assertEquals(l.pools.count, m.pools.size.toLong, s"$what: pool count")
      assertEquals(l.dreps.count, m.dreps.size.toLong, s"$what: drep count")
      assertEquals(
        l.accounts.sumA,
        m.accounts.valuesIterator.map(_.balance).sum,
        s"$what: balances"
      )
      assertEquals(
        l.accounts.sumB,
        m.accounts.valuesIterator.map(_.deposit).sum,
        s"$what: deposits"
      )
      assertEquals(l.pools.sumB, m.pools.valuesIterator.map(_.deposit).sum, s"$what: pool deposits")
      assertEquals(l.dreps.sumB, m.dreps.valuesIterator.map(_.deposit).sum, s"$what: drep deposits")
      snapshots(full).zip(m.snaps).zipWithIndex.foreach { case ((sn, (gen, stake, deleg)), i) =>
        assertEquals(sn.gen, gen, s"$what: snapshot $i generation")
        assertEquals(sn.stake.toMap, stake, s"$what: snapshot $i stake")
        assertEquals(sn.delegations.toMap, deleg, s"$what: snapshot $i delegations")
        assertEquals(sn.stake.count, stake.size.toLong, s"$what: snapshot $i stake count")
      }
      true
    }

  /** Every retained state, completed from the store, equals its model; and the store alone equals
    * the anchor's (the rows of snapshot generations nothing refers to are gone).
    */
  private def check(store: LedgerStore[IO], chain: Chain)(using
      Handle[IO, StorageError]
  ): IO[Boolean] =
    for
      window <- chain.states.zip(chain.models).traverse((s, m) => matches(store, s, m, "window"))
      anchor <- matches(store, chain.anchor, chain.anchorModel, "anchor")
      live = snapshots(chain.anchor).map(_.gen)
      stale <- (1L until live.max).toVector
        .filter(_ < live.min)
        .flatTraverse(g =>
          Vector(Snapshot.StakeTable, Snapshot.DelegTable)
            .traverse(t => store.scanRows(Table.Space(t, g)))
        )
    yield window.forall(identity) && anchor && stale.forall(_.isEmpty)

  // -------------------------------------------------------------------------------------------

  private def tempDir(): java.nio.file.Path =
    java.nio.file.Files.createTempDirectory("ledger-tables-suite")

  private def rocks[A](use: LedgerStore[IO] => IO[A])(using Handle[IO, StorageError]): IO[A] =
    val dir = tempDir()
    RocksLedgerStore
      .resource[IO](Path.fromNioPath(dir) / "live", 8L * 1024 * 1024)
      .use(use)
      .guarantee(IO.blocking(RocksLedgerStore.deleteTree(dir)))

  private val genActions: Gen[List[Action]] = Gen.choose(1, 40).flatMap(Gen.listOfN(_, genAction))

  property("memory store: apply/rollback/flush matches the model; counters and sums are exact") {
    forAll(genActions) { actions =>
      given_(MemoryLedgerStore[IO].flatMap(run(_, actions)).map(_._2))
    }
  }

  property("rocksdb store: apply/rollback/flush matches the model; counters and sums are exact") {
    forAll(genActions) { actions =>
      given_(rocks(run(_, actions).map(_._2)))
    }
  }

  property("rocksdb and memory interpreters agree on every row after the same sequence") {
    forAll(genActions) { actions =>
      given_(
        MemoryLedgerStore[IO].flatMap(mem =>
          rocks { disk =>
            for
              a <- run(mem, actions)
              b <- run(disk, actions)
              rows <- LedgerStore.tableNames.toVector.traverse { t =>
                // both interpreters, every generation a model state can hold
                (0L to 12L).toVector.traverse { g =>
                  (mem.scanRows(Table.Space(t, g)), disk.scanRows(Table.Space(t, g))).mapN {
                    (x, y) =>
                      x.map((k, v) => k.toSeq -> v.toSeq).toMap == y
                        .map((k, v) => k.toSeq -> v.toSeq)
                        .toMap
                  }
                }
              }
              stamps <- (mem.flushed, disk.flushed).tupled
            yield a._2 && b._2 && rows.flatten.forall(identity) && stamps._1 == stamps._2
          }
        )
      )
    }
  }

  private def checkpointRoundTrip(
      open: (LedgerStore[IO] => IO[Boolean]) => IO[Boolean],
      actions: List[Action],
      more: List[Action]
  )(using Handle[IO, StorageError]) =
    open { store =>
      val dir = tempDir()
      val cp = Path.fromNioPath(dir) / "cp"
      (for
        // Drive the first part, snapshot the store with the anchor state paired with it (settled,
        // as a snapshot file holds it), keep going, then restore the checkpoint.
        first <- run(store, actions)
        (chain, ok) = first
        at <- store.flushed
        paired = chain.anchor.settled(at, force = true)
        _ <- store.checkpoint(cp)
        _ <- runFrom(store, chain, more)
        restored <- store.restore(cp)
        after <- matches(store, paired, chain.anchorModel, "restored")
        stamp <- store.flushed
        missing <- store.restore(Path.fromNioPath(dir) / "nothing")
      yield ok && restored && !missing && after && stamp == at)
        .guarantee(IO.blocking(RocksLedgerStore.deleteTree(dir)))
    }

  property("checkpoint then restore returns the tables to the checkpointed state (memory)") {
    forAll(genActions, genActions) { (actions, more) =>
      given_(checkpointRoundTrip(f => MemoryLedgerStore[IO].flatMap(f), actions, more))
    }
  }

  property("checkpoint then restore returns the tables to the checkpointed state (rocksdb)") {
    forAll(genActions, genActions) { (actions, more) =>
      given_(checkpointRoundTrip(f => rocks(f), actions, more))
    }
  }

  test("a row read for a key the table never had is known absent, not fetched again") {
    given_ {
      MemoryLedgerStore[IO].flatMap { store =>
        val l0 = TestLedger.state(8, Vector.empty)
        val l1 = l0.copy(accounts = l0.accounts.updated(cred(1), account(5, 1, None)))
        for
          _ <- LedgerStore.advance(store, l1.at(1))
          l2 = l1.settled(1, force = true)
          done <- LedgerStore.completingF(Some(store), l2)(l =>
            IO.pure(Table.guarded((l.accounts.contains(cred(1)), l.accounts.contains(cred(2)))))
          )
        yield
          assertEquals(done._2, (true, false))
          // the second answer came from the fetched read set: asking again needs no more fetches
          assertEquals(done._1.accounts.get(cred(2)), None)
          assertEquals(done._1.accounts.read.keySet, Set(cred(1), cred(2)))
      }
    }
  }

  test("a read the state cannot answer is reported, never answered from an incomplete view") {
    val l0 = TestLedger.state(8, Vector.empty)
    val settled =
      l0.copy(accounts = l0.accounts.updated(cred(1), account(5, 1, None))).settled(0, force = true)
    // (a control signal is not NonFatal, so `intercept` would not see it)
    val miss = Table.guarded(settled.accounts.get(cred(1))).swap.getOrElse(fail("answered"))
    assertEquals(miss.keys, Set[Any](cred(1)))
    val scan = Table.guarded(settled.accounts.toMap).swap.getOrElse(fail("answered"))
    assert(scan.all)
  }
