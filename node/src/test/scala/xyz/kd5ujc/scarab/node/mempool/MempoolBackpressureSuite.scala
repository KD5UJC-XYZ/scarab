// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.effect.{IO, Ref}
import cats.effect.std.Queue
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.*

import TestLedger.txIn
import TxKit.*

/** `implAddTx` of the reference: a transaction that finds no room waits for the pool to change
  * instead of being refused, in a line that serves local clients before queued peers, and it holds
  * no lock while it waits. Each test says what it waits for: the `Waiting` event of a transaction
  * that found no room, the lines of [[Mempool.lines]] for submissions queued behind it, or the
  * answer of an add. The failsafe timeouts bound a test that would otherwise hang; nothing is
  * decided by elapsed time.
  *
  * The pool holds one block of `capacity` bytes. `small` transactions have one output, `large` ones
  * ten, so the sizes below are chosen for each test and asserted, not assumed.
  */
class MempoolBackpressureSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  private given cats.mtl.Raise[IO, StorageError] with
    def functor = cats.Functor[IO]
    def raise[E <: StorageError, A](error: E): IO[A] = IO(fail(error.message))

  private val failsafe = 20.seconds

  /** Alice pays from `txIn(i)` to `outputs` outputs of bob. */
  private def spend(i: Int, outputs: Int = 1): (Bytes, Bytes) =
    val fee = ada
    val each = 2 * ada
    val rest = 100 * ada - fee - each * (outputs - 1)
    tx(
      Vector(txIn(i)),
      Vector.fill(outputs - 1)(bob.address() -> each) :+ (bob.address() -> rest),
      fee,
      Vector(alice)
    )

  private def cost(raw: Bytes): Long =
    MempoolState.parse(raw).fold(r => fail(r.message), _._2.size) + Capacity.PerTxOverhead

  private final class Probe(
      val mempool: Mempool[IO],
      val base: MempoolLedger,
      val log: Ref[IO, Vector[MempoolEvent]],
      events: Queue[IO, MempoolEvent]
  ):
    /** The next event, in order, that satisfies `wanted`. */
    def next(wanted: MempoolEvent => Boolean): IO[MempoolEvent] =
      events.take.iterateUntil(wanted).timeout(failsafe)

    def waiting(id: Bytes): IO[Unit] = next {
      case MempoolEvent.Waiting(Some(i), _, _) => i == id
      case _                                   => false
    }.void

    def added(id: Bytes): IO[Unit] = next {
      case MempoolEvent.Added(i, _, _, _) => i == id
      case _                              => false
    }.void

    def lines(expected: Mempool.Lines): IO[Unit] =
      (IO.cede *> mempool.lines).iterateUntil(_ == expected).timeout(failsafe).void

    def indexOf(wanted: MempoolEvent => Boolean): IO[Int] = log.get.map(_.indexWhere(wanted))

    def addedIds: IO[Vector[Bytes]] =
      log.get.map(_.collect { case MempoolEvent.Added(id, _, _, _) => id })

    def waitingIds: IO[Vector[Option[Bytes]]] =
      log.get.map(_.collect { case MempoolEvent.Waiting(id, _, _) => id })

    /** The ledger after `raws` were adopted in a block, for a `sync`. */
    def after(raws: Bytes*): MempoolLedger =
      raws.foldLeft(base)((l, raw) =>
        l.applied(MempoolState.parse(raw).fold(r => fail(r.message), _._2))
      )

  private def probe(capacity: Long, inputs: Int = 6): IO[Probe] =
    for
      log <- Ref.of[IO, Vector[MempoolEvent]](Vector.empty)
      events <- Queue.unbounded[IO, MempoolEvent]
      base = ledger(inputs).withParams(_.copy(maxBlockBodySize = 1024 + capacity))
      mempool <- Mempool.create[IO](
        Right(base),
        1000,
        env,
        Some(1),
        (e: MempoolEvent) => log.update(_ :+ e) *> events.offer(e)
      )
    yield Probe(mempool, base, log, events)

  /** `held` fits the pool, `wide` fits an empty pool but not beside `held`, and `narrow` fits
    * beside `held` but not beside `wide`: with `capacity` bytes.
    */
  private final case class Sizes(held: Bytes, wide: Bytes, narrow: Bytes, capacity: Long)

  private def sizes: Sizes =
    val held = spend(1)._1
    val wide = spend(2, outputs = 3)._1
    val narrow = spend(3)._1
    val capacity = (cost(held) + cost(narrow)).max(cost(wide)) + 1
    assert(cost(held) + cost(wide) > capacity, "wide must not fit beside held")
    assert(cost(held) + cost(narrow) <= capacity, "narrow must fit beside held")
    assert(cost(wide) + cost(narrow) > capacity, "narrow must not fit beside wide")
    Sizes(held, wide, narrow, capacity)

  private def idOf(raw: Bytes): Bytes = MempoolState.parse(raw).fold(r => fail(r.message), _._1)

  test("a full pool blocks add, and sync, awaitChange and the readers run while it waits") {
    val s = sizes
    for
      p <- probe(s.capacity)
      _ <- p.mempool.add(s.held, Source.Local)
      blocked <- p.mempool.add(s.wide, Source.Local).start
      _ <- p.waiting(idOf(s.wide))
      // the state lock is free: none of these may wait for the adder
      seen <- p.mempool.snapshot
      contained <- p.mempool.contains(idOf(s.held))
      changed <- p.mempool.awaitChange(_ => false).timeout(failsafe)
      // a revalidation that frees nothing is not a reason to stop waiting, and a retry does not
      // announce the wait again
      _ <- p.mempool.sync(Right(p.base), 1001)
      freed <- p.mempool.sync(Right(p.after(s.held)), 1002, Set(idOf(s.held)))
      result <- blocked.joinWithNever.timeout(failsafe)
      held <- p.mempool.snapshot
      waits <- p.waitingIds
      addedAt <- p.indexOf {
        case MempoolEvent.Added(id, _, _, _) => id == idOf(s.wide); case _ => false
      }
      adoptedAt <- p.indexOf(_ == MempoolEvent.Adopted(idOf(s.held)))
    yield
      // it was added after the revalidation that freed the room, not before
      assert(adoptedAt >= 0 && addedAt > adoptedAt, s"added at $addedAt, room freed at $adoptedAt")
      assertEquals(seen.entries.map(_.id), Vector(idOf(s.held)))
      assert(contained)
      assertEquals(changed.entries.size, 1)
      assertEquals(freed, Vector.empty)
      assertEquals(result, Right(idOf(s.wide)))
      assertEquals(held.entries.map(_.id), Vector(idOf(s.wide)))
      assertEquals(waits, Vector(Some(idOf(s.wide))))
  }

  test("an add that waits completes after removeTxsEvenIfValid frees room") {
    val s = sizes
    for
      p <- probe(s.capacity)
      _ <- p.mempool.add(s.held, Source.Remote)
      blocked <- p.mempool.add(s.wide, Source.Remote).start
      _ <- p.waiting(idOf(s.wide))
      removed <- p.mempool.removeTxsEvenIfValid(Set(idOf(s.held)))
      result <- blocked.joinWithNever.timeout(failsafe)
      removedAt <- p.indexOf(_ == MempoolEvent.Removed(idOf(s.held)))
      addedAt <- p.indexOf {
        case MempoolEvent.Added(id, _, _, _) => id == idOf(s.wide)
        case _                               => false
      }
    yield
      assert(removedAt >= 0 && addedAt > removedAt, s"added at $addedAt, removed at $removedAt")
      assertEquals(removed, Vector.empty)
      assertEquals(result, Right(idOf(s.wide)))
  }

  test("a new tip with a larger capacity lets a waiting add in: the retry measures again") {
    val s = sizes
    for
      p <- probe(s.capacity)
      _ <- p.mempool.add(s.held, Source.Local)
      blocked <- p.mempool.add(s.wide, Source.Local).start
      _ <- p.waiting(idOf(s.wide))
      grown = p.base.withParams(_.copy(maxBlockBodySize = 1024 + 3 * s.capacity))
      dropped <- p.mempool.sync(Right(grown), 1001)
      result <- blocked.joinWithNever.timeout(failsafe)
      held <- p.mempool.snapshot
    yield
      assertEquals(dropped, Vector.empty)
      assertEquals(result, Right(idOf(s.wide)))
      assertEquals(held.entries.map(_.id), Vector(idOf(s.held), idOf(s.wide)))
  }

  test("a local add overtakes the peers that queue behind a waiting peer") {
    // `held` takes most of the pool: a peer's `first` waits for it to leave, and while it waits a
    // second peer and a local client queue; all three fit once `held` has gone
    val held = spend(1, outputs = 10)._1
    val first = spend(2)._1
    val second = spend(3)._1
    val local = spend(4)._1
    val capacity = cost(first) + cost(second) + cost(local)
    assert(cost(held) <= capacity && cost(held) + cost(first) > capacity)
    for
      p <- probe(capacity)
      _ <- p.mempool.add(held, Source.Local)
      head <- p.mempool.add(first, Source.Remote).start
      _ <- p.waiting(idOf(first))
      peer <- p.mempool.add(second, Source.Remote).start
      client <- p.mempool.add(local, Source.Local).start
      _ <- p.lines(Mempool.Lines(remote = 1, common = 1))
      _ <- p.mempool.removeTxsEvenIfValid(Set(idOf(held)))
      results <- List(head, peer, client).traverse(_.joinWithNever.timeout(failsafe))
      order <- p.addedIds
    yield
      assertEquals(results, List(first, second, local).map(r => Right(idOf(r))))
      assertEquals(order, Vector(held, first, local, second).map(idOf))
  }

  test("only the head of the line waits for room: a smaller transaction does not pass it") {
    val s = sizes
    for
      p <- probe(s.capacity)
      _ <- p.mempool.add(s.held, Source.Local)
      head <- p.mempool.add(s.wide, Source.Remote).start
      _ <- p.waiting(idOf(s.wide))
      small <- p.mempool.add(s.narrow, Source.Local).start
      // the small one fits beside `held` and is nevertheless in line
      _ <- p.lines(Mempool.Lines(remote = 0, common = 1))
      _ <- p.mempool.removeTxsEvenIfValid(Set(idOf(s.held)))
      first <- head.joinWithNever.timeout(failsafe)
      // behind `wide` there is no room for it: it is the head now, and waits
      _ <- p.waiting(idOf(s.narrow))
      _ <- p.mempool.removeTxsEvenIfValid(Set(idOf(s.wide)))
      second <- small.joinWithNever.timeout(failsafe)
      order <- p.addedIds
    yield
      assertEquals(first, Right(idOf(s.wide)))
      assertEquals(second, Right(idOf(s.narrow)))
      assertEquals(order, Vector(s.held, s.wide, s.narrow).map(idOf))
  }

  test("cancelling a waiting add releases its place in both lines") {
    val s = sizes
    for
      p <- probe(s.capacity)
      _ <- p.mempool.add(s.held, Source.Local)
      peer <- p.mempool.add(s.wide, Source.Remote).start
      _ <- p.waiting(idOf(s.wide))
      later <- p.mempool.add(s.narrow, Source.Remote).start
      _ <- p.lines(Mempool.Lines(remote = 1, common = 0))
      _ <- peer.cancel
      outcome <- peer.join
      served <- later.joinWithNever.timeout(failsafe)
      // a local client that waits is cancelable too, and frees the common line
      client <- p.mempool.add(s.wide, Source.Local).start
      _ <- p.waiting(idOf(s.wide))
      _ <- client.cancel
      _ <- p.lines(Mempool.Lines(0, 0))
      _ <- p.mempool.removeTxsEvenIfValid(Set(idOf(s.held), idOf(s.narrow)))
      again <- p.mempool.add(s.wide, Source.Local).timeout(failsafe)
    yield
      assert(outcome.isCanceled, s"the cancelled add ended as $outcome")
      assertEquals(served, Right(idOf(s.narrow)))
      assertEquals(again, Right(idOf(s.wide)))
  }

  test("a duplicate is answered at once by a pool without room") {
    val held = spend(1)._1
    for
      p <- probe(cost(held) + 1)
      _ <- p.mempool.add(held, Source.Local)
      local <- p.mempool.add(held, Source.Local).timeout(failsafe)
      remote <- p.mempool.add(held, Source.Remote).timeout(failsafe)
      waits <- p.waitingIds
    yield
      assertEquals(local, Left(Rejection.AlreadyHeld(idOf(held))))
      assertEquals(remote, Left(Rejection.AlreadyHeld(idOf(held))))
      assertEquals(waits, Vector.empty)
  }

  test("a transaction that cannot fit even an empty pool is refused at once") {
    val s = sizes
    val oversized = spend(4, outputs = 10)._1
    assert(cost(oversized) > s.capacity)
    for
      p <- probe(s.capacity)
      empty <- p.mempool.add(oversized, Source.Local).timeout(failsafe)
      _ <- p.mempool.add(s.held, Source.Local)
      beside <- p.mempool.add(oversized, Source.Remote).timeout(failsafe)
      waits <- p.waitingIds
      log <- p.log.get
    yield
      List(empty, beside).foreach {
        case Left(full: Rejection.Full) =>
          assertEquals(full.needed.bytes, cost(oversized))
          assertEquals(full.dimension, CapacityDimension.Bytes)
          assert(LocalSubmission.reasonItem(full).isRight)
        case other => fail(s"expected the capacity refusal, received $other")
      }
      assertEquals(waits, Vector.empty)
      assertEquals(
        log.collect { case MempoolEvent.Rejected(Some(id), _: Rejection.Full) => id },
        Vector(idOf(oversized), idOf(oversized))
      )
  }

  test("a peer's submit waits for room in its fiber and the local handler waits as well") {
    val s = sizes
    for
      p <- probe(s.capacity)
      _ <- p.mempool.add(s.held, Source.Local)
      handler = LocalSubmission.handler[IO](p.mempool)(fatal =>
        IO(fail(s"unexpected fatal: $fatal"))
      )
      sink = p.mempool.sink(fatal => IO(fail(s"unexpected fatal: $fatal")))
      peer <- sink.submit(Vector(s.wide)).start
      _ <- p.waiting(idOf(s.wide))
      known <- sink.known(idOf(s.wide))
      _ <- p.mempool.removeTxsEvenIfValid(Set(idOf(s.held)))
      _ <- peer.joinWithNever.timeout(failsafe)
      handled <- p.mempool.snapshot
      removedAt <- p.indexOf(_ == MempoolEvent.Removed(idOf(s.held)))
      addedAt <- p.indexOf {
        case MempoolEvent.Added(id, _, _, _) => id == idOf(s.wide)
        case _                               => false
      }
      // the local handler goes through the same add: it waits and answers when there is room
      local <- handler(wrapped(s.narrow)).start
      _ <- p.waiting(idOf(s.narrow))
      _ <- p.mempool.removeTxsEvenIfValid(Set(idOf(s.wide)))
      response <- local.joinWithNever.timeout(failsafe)
    yield
      assert(removedAt >= 0 && addedAt > removedAt, s"added at $addedAt, removed at $removedAt")
      assert(!known, "a transaction that waits is not held yet")
      assertEquals(handled.entries.map(_.id), Vector(idOf(s.wide)))
      assertEquals(response, xyz.kd5ujc.scarab.network.LocalTxSubmissionServer.Response.Accepted)
  }

  /** The hard-fork-combinator wrapping of a Conway transaction as `cardano-cli` sends it. */
  private def wrapped(raw: Bytes): Bytes =
    Bytes(Vector(0x82.toByte, 0x06.toByte) ++ raw.value)
