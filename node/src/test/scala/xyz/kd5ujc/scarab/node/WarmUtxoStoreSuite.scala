// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Resource}
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.{Files as FsFiles, Path as FsPath}
import java.nio.file.{Files, Path}
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.rng.Seed
import scala.collection.immutable.SortedSet
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.{
  Block,
  ByteStrings,
  Header,
  NodeError,
  StorageError,
  TxIn,
  TxOut,
  Utxo
}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import xyz.kd5ujc.scarab.storage.{MemoryUtxoStore, RocksUtxoStore, UtxoPinnedReadView, UtxoStore}

/** The warm tier in front of the UTxO store ([[WarmUtxoStore]], #1484): every read it gives is the
  * store's, and every entry it holds is the store's current value, under any interleaving of
  * writes, reads, reads ahead and resets; a fill read before a write that deletes its key, or read
  * while a write is under way, is not kept; the tier stays within its budget, and holds nothing on
  * a budget of 0; and the era-walking chain applied over a RocksDB store gives the same states with
  * the tier as without.
  */
class WarmUtxoStoreSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught with ScalaCheckSuite:
  import WarmUtxoStore.{Budget, EntryBytes, Key, Kind, Tier}

  private def in(n: Int): TxIn = TxIn(Bytes(Vector.fill(32)((n & 0xff).toByte)), n)
  private def key(n: Int): Key = Key.of(in(n)).getOrElse(fail(s"no key for $n"))

  /** An output decoded from its encoding, `[address, coin]`, so it carries its bytes (`raw`), of a
    * size that grows with `n` (the coin's head).
    */
  private def out(n: Int): TxOut =
    val coin = n match
      case _ if n < 24  => f"${n}%02x"
      case _ if n < 256 => f"18${n}%02x"
      case _            => f"19${n & 0xffff}%04x"
    val raw = Bytes.fromHex("82581d61" + "05" * 28 + coin).fold(e => fail(e.toString), identity)
    TxOut.fromBytes(raw)(using ByteStrings.Chunked).fold(e => fail(e.toString), identity)

  /** An output built rather than decoded: its encoding is empty, and the store's is the codec's. */
  private def built(n: Int): TxOut = TxOut(out(n).address, out(n).value)

  /** `body` with a typed storage error failing the test. */
  private def ok[A](body: Raise[IO, StorageError] ?=> IO[A]): IO[A] =
    Handle
      .allow[StorageError](body.map(_.asRight[StorageError]))
      .rescue(e => IO.pure(e.asLeft[A]))
      .flatMap(_.fold(e => IO(fail(s"storage error: $e")), IO.pure))

  /** A state's set whose diff is empty and whose trim point is `flushed`: every key is the store's.
    */
  private def trimmedAt(flushed: Long): Utxo = Utxo.empty.copy(flushed = flushed, stamp = flushed)

  /** The keys of the tier whose value is not the store's: none, if the tier is coherent. */
  private def incoherent(tier: Tier, store: Map[TxIn, TxOut], keys: Range): List[Int] =
    val byKey = keys.map(k => key(k) -> k).toMap
    tier.contents.toList.collect {
      case (k, (value, _))
          if value.isEmpty || value != byKey.get(k).flatMap(n => store.get(in(n))) =>
        byKey.getOrElse(k, -1)
    }

  // ------------------------------------------------------------------ the tier

  private val tierOps: Gen[Vector[Tier => Tier]] =
    val write = for
      touched <- Gen.listOf(Gen.choose(0, 63))
      created <- Gen.listOf(Gen.choose(0, 63))
    yield (t: Tier) =>
      t.moving(WarmUtxoStore.Touched.Keys((touched ++ created).map(key).toVector))
        .moved(created.map(k => key(k) -> Tier.encoded(out(k * 37))))
    val lookup = Gen.listOf(Gen.choose(0, 63)).map(ks => (t: Tier) => t.lookup(ks.map(key))._1)
    val fill = Gen.listOf(Gen.choose(0, 63)).map { ks => (t: Tier) =>
      val (looked, (_, seen)) = t.lookup(Nil)
      looked.filled(seen, ks.map(k => key(k) -> Tier.encoded(out(k * 37))))._1
    }
    val clear = Gen.const((t: Tier) => t.moving(WarmUtxoStore.Touched.Everything).quiet)
    Gen.listOf(Gen.frequency(4 -> write, 3 -> lookup, 3 -> fill, 1 -> clear)).map(_.toVector)

  property("the tier never exceeds its budget, nor its read share, and its bytes are its charges") {
    Prop.forAll(tierOps, Gen.choose(0L, 12000L)) { (steps, bytes) =>
      val budget = Budget.bytes(bytes)
      steps.scanLeft(Tier.empty(budget))((t, step) => step(t)).forall { t =>
        t.bytes <= budget.bytes && t.readBytes <= budget.readShare &&
        t.charged == (t.youngBytes, t.readBytes) && t.stats.entries == t.size
      }
    }
  }

  test("with a budget of 0 the tier holds nothing") {
    val t = Tier
      .empty(Budget.bytes(0))
      .moving(WarmUtxoStore.Touched.Keys(Vector(key(1))))
      .moved(Vector(key(1) -> Tier.encoded(out(1))))
    val (filled, _) = t.lookup(Nil) match
      case (looked, (_, seen)) => looked.filled(seen, Vector(key(2) -> Tier.encoded(out(2))))
    assertEquals((t.size, t.bytes, filled.size, filled.bytes), (0, 0L, 0, 0L))
  }

  test("the young leave oldest first when the budget is full, and a read entry is kept apart") {
    val charge = Tier.encoded(out(300)).length + EntryBytes
    // room for eight entries, of which the read population may take one
    val budget = Budget.bytes(8 * charge + 8)
    def written(t: Tier, k: Int) =
      t.moving(WarmUtxoStore.Touched.Keys(Vector(key(k))))
        .moved(Vector(key(k) -> Tier.encoded(out(k))))
    val full = (300 until 308).foldLeft(Tier.empty(budget))(written)
    // 300, the oldest, is read: it moves to the read population, and a ninth write pushes out 301,
    // the oldest of the young
    val next = written(full.lookup(List(key(300)))._1, 308)
    assertEquals(
      (300 to 308).map(k => next.kindOf(key(k))),
      Some(Kind.Read) +: None +: Vector.fill(7)(Some(Kind.Young))
    )
    assertEquals((next.stats.evictedYoung, next.stats.served), (1L, 1L))
  }

  test("a fill whose lookup found the tier moving, or that comes after a move, is refused") {
    val quiet = Tier.empty(Budget.Default)
    val (_, (_, before)) = quiet.lookup(Nil)
    val moving = quiet.moving(WarmUtxoStore.Touched.Keys(Vector(key(1))))
    val (_, (_, during)) = moving.lookup(Nil)
    val fill = Vector(key(2) -> Tier.encoded(out(2)))
    val (afterMove, landed) = moving.quiet.filled(before, fill)
    val (inMove, landedInMove) = moving.filled(during, fill)
    assertEquals(
      (afterMove.holds(key(2)), landed, inMove.holds(key(2)), landedInMove),
      (false, WarmUtxoStore.Landing.Moved, false, WarmUtxoStore.Landing.Current)
    )
    assertEquals(afterMove.stats.refused + inMove.stats.refused, 2L)
  }

  // ------------------------------------------------------------------ the store

  /** Random interleavings of writes (creations and deletions, a key repeated in one batch, decoded
    * and built outputs), reads, reads ahead and the block path's reads through the read-ahead
    * store, and resets, applied to a warm store over a memory store and, in lockstep, to a plain
    * one: every read is the plain store's, and after every step every entry the tier holds is the
    * plain store's value.
    */
  private enum Op:
    case Write(changes: List[(Int, Option[Int])])
    case Read(keys: List[Int])
    case Ahead(keys: List[Int])
    case Use(keys: List[Int])
    case Reset

  test("under any interleaving every read is the store's and every entry held is the store's") {
    val change = Gen.zip(
      Gen.choose(0, 40),
      Gen.frequency(2 -> Gen.const(None), 3 -> Gen.choose(0, 600).map(Some(_)))
    )
    val op: Gen[Op] = Gen.frequency(
      5 -> Gen.listOf(change).map(Op.Write.apply),
      4 -> Gen.listOf(Gen.choose(0, 40)).map(Op.Read.apply),
      2 -> Gen.listOf(Gen.choose(0, 40)).map(Op.Ahead.apply),
      2 -> Gen.listOf(Gen.choose(0, 40)).map(Op.Use.apply),
      1 -> Gen.const(Op.Reset)
    )
    val cases = Gen
      .listOfN(200, Gen.zip(Gen.choose(0L, 6000L), Gen.listOf(op)))
      .apply(Gen.Parameters.default, Seed(1484L))
      .toList
      .flatten
    def output(n: Int): TxOut = if n % 5 == 0 then built(n) else out(n)
    ok {
      cases.traverse { (bytes, ops) =>
        for
          plain <- MemoryUtxoStore[IO]
          backing <- MemoryUtxoStore[IO]
          warm <- WarmUtxoStore[IO](backing, Budget.bytes(bytes))
          ahead <- ReadAheadStore.idle[IO](warm)
          end <- ops.zipWithIndex.foldLeftM((0, 0)) { case ((mismatches, incoherence), (o, i)) =>
            def same(a: IO[Map[TxIn, TxOut]], b: IO[Map[TxIn, TxOut]]) =
              (a, b).mapN((x, y) => if x == y then 0 else 1)
            val step = o match
              case Op.Write(changes) =>
                val batch = changes.map((k, v) => in(k) -> v.map(output))
                (ahead.write(batch, i.toLong) *> plain.write(batch, i.toLong)).as(0)
              case Op.Read(keys)  => same(ahead.read(keys.map(in)), plain.read(keys.map(in)))
              case Op.Ahead(keys) => ahead.prefetch(keys.map(in).toSet).as(0)
              case Op.Use(keys) =>
                plain.flushed.flatMap(at =>
                  same(
                    ahead.readFor(trimmedAt(at), SortedSet.from(keys.map(in))),
                    plain.read(keys.map(in))
                  )
                )
              case Op.Reset => (ahead.reset *> plain.reset).as(0)
            for
              m <- step
              tier <- warm.held
              all <- plain.all
            yield (mismatches + m, incoherence + incoherent(tier, all, 0 to 40).size)
          }
          stats <- warm.stats
        yield (end, stats)
      }
    }.map { results =>
      assertEquals(results.map(_._1._1).sum, 0, "a read differed from the store's")
      assertEquals(results.map(_._1._2).sum, 0, "the tier held a value that is not the store's")
      val stats = results.map(_._2)
      // not vacuous: the tier served entries, and both populations were cut by the budget
      assert(stats.map(_.served).sum > 0, "nothing was served")
      assert(stats.map(_.evictedYoung).sum > 0, "no young entry was evicted")
      assert(stats.map(_.evictedRead).sum > 0, "no read entry was evicted")
    }
  }

  test("a fill read before a write that deletes its key is not kept, and the read is the store's") {
    ok {
      for
        memory <- MemoryUtxoStore[IO]
        _ <- memory.write(Vector(in(1) -> Some(out(1))), 10)
        reading <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        warm <- WarmUtxoStore[IO](Gated.reads(memory, reading, release), Budget.Default)
        // the read in flight is owned by a resource, and released on any failure
        read <- warm
          .read(Vector(in(1)))
          .background
          .use(landed =>
            reading.get *> warm.write(Vector(in(1) -> None), 20) *> release.complete(()) *>
              landed.flatMap(_.embedNever)
          )
          .guarantee(release.complete(()).void)
        tier <- warm.held
        after <- warm.read(Vector(in(1)))
      yield
        // the read saw the store before the delete; the write moved the tier, so it was read
        // again, after it
        assertEquals(read, Map.empty[TxIn, TxOut])
        assertEquals((tier.holds(key(1)), tier.stats.refused), (false, 1L))
        assertEquals(after, Map.empty[TxIn, TxOut])
    }
  }

  test("a fill read while a write that deletes its key is under way is not kept") {
    ok {
      for
        memory <- MemoryUtxoStore[IO]
        _ <- memory.write(Vector(in(2) -> Some(out(2)), in(3) -> Some(out(3))), 10)
        writing <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        warm <- WarmUtxoStore[IO](Gated.writes(memory, writing, release), Budget.Default)
        // the write is held before the store changes: the reads see the store as it was
        during <- warm
          .write(Vector(in(2) -> None), 20)
          .background
          .use(written =>
            writing.get *> warm
              .read(Vector(in(2), in(3)))
              .flatTap(_ => release.complete(()) *> written.flatMap(_.embedNever))
          )
          .guarantee(release.complete(()).void)
        tier <- warm.held
        after <- warm.read(Vector(in(2), in(3)))
      yield
        assertEquals(during, Map(in(2) -> out(2), in(3) -> out(3)))
        assertEquals(
          (tier.holds(key(2)), tier.holds(key(3)), tier.stats.refused),
          (false, false, 2L)
        )
        assertEquals(after, Map(in(3) -> out(3)))
    }
  }

  test("a write that fails leaves the tier quiet, without its keys or its creations") {
    ok {
      for
        memory <- MemoryUtxoStore[IO]
        warm <- WarmUtxoStore[IO](Gated.failing(memory), Budget.Default)
        _ <- warm.write(Vector(in(4) -> Some(out(4))), 10).attempt
        moved <- warm.held
        _ <- memory.write(Vector(in(5) -> Some(out(5))), 11)
        _ <- warm.read(Vector(in(5)))
        filled <- warm.held
      yield
        assertEquals((moved.holds(key(4)), moved.phase), (false, WarmUtxoStore.Phase.Quiet))
        // quiet again: a later read fills
        assert(filled.holds(key(5)))
    }
  }

  test("a reset and a restore empty the tier") {
    ok {
      FsFiles[IO].tempDirectory.use { dir =>
        for
          memory <- MemoryUtxoStore[IO]
          warm <- WarmUtxoStore[IO](memory, Budget.Default)
          _ <- warm.write(Vector(in(6) -> Some(out(6))), 10)
          _ <- warm.checkpoint(dir / "c")
          _ <- warm.write(Vector(in(7) -> Some(out(7))), 11)
          written <- warm.held
          _ <- warm.restore(dir / "c")
          restored <- warm.held
          read <- warm.read(Vector(in(6), in(7)))
          _ <- warm.reset
          reset <- warm.held
          after <- warm.read(Vector(in(6)))
        yield
          assertEquals(written.size, 2)
          assertEquals(restored.size, 0)
          assertEquals(read, Map(in(6) -> out(6)))
          assertEquals((reset.size, after), (0, Map.empty[TxIn, TxOut]))
      }
    }
  }

  test("an entry is served in the store's own decoding, a built output among them") {
    ok {
      for
        memory <- MemoryUtxoStore[IO]
        warm <- WarmUtxoStore[IO](memory, Budget.Default)
        _ <- warm.write(Vector(in(8) -> Some(out(8)), in(9) -> Some(built(9))), 10)
        tier <- warm.held
        read <- warm.read(Vector(in(8), in(9)))
        direct <- memory.read(Vector(in(8), in(9)))
        stats <- warm.stats
      yield
        assertEquals(
          (tier.kindOf(key(8)), tier.kindOf(key(9))),
          (Some(Kind.Young), Some(Kind.Young))
        )
        assertEquals(read, direct)
        assertEquals((stats.served, stats.missed), (2L, 0L))
    }
  }

  // ------------------------------------------------------------------ the era-walking chain

  private val fixtures = Path.of("fixtures/eras")
  private lazy val setup: NodeSetup =
    NodeSetup
      .fromDirectory(ReferenceBuild.Default)(fixtures.resolve("genesis"))
      .fold(e => fail(e.message), identity)
  private lazy val raws: Vector[Bytes] =
    Files
      .readAllLines(fixtures.resolve("blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e.message), identity))
  private lazy val headers: Vector[Header] =
    raws.map(
      Block.decode(_, setup.geometry.byron.epochSlots).fold(e => fail(e.message), identity).header
    )

  test("the era-walking chain over a RocksDB store reaches the same states with the warm tier") {
    val window = 10
    val lag = 5
    def rules(store: UtxoStore[IO]) = MirrorRules[IO](
      setup.praos,
      setup.globals,
      setup.byronRules,
      setup.handOver,
      setup.byronHistory,
      xyz.kd5ujc.scarab.plutus.PlutusBackend.default,
      utxoStore = Some(store)
    )
    def stores(using Raise[IO, StorageError]) =
      for
        a <- FsFiles[IO].tempDirectory
        b <- FsFiles[IO].tempDirectory
        plain <- RocksUtxoStore.resource[IO](a / "live")
        rocks <- RocksUtxoStore.resource[IO](b / "live")
        // the chain's store holds few entries, and few of its reads reach the store: the budget
        // is not what this test is about (the interleaving property cuts both populations)
        warm <- Resource.eval(WarmUtxoStore[IO](rocks, Budget.Default))
        ahead <- Resource.eval(ReadAheadStore.idle[IO](warm))
      yield (plain, warm, ahead)
    Handle
      .allow[NodeError] {
        stores
          .use { (plain, warm, ahead) =>
            val (withOut, withIn) = (rules(plain), rules(ahead))
            // the blocks the queue holds are read ahead from the state at the time they are queued
            def queue(i: Int, s: MirrorState): IO[Unit] =
              raws.lift(i).traverse_(raw => ahead.prefetch(ReadAheadStore.inputs(raw, s)))
            for
              _ <- (0 until window - 1).toList.traverse_(queue(_, setup.mirrorStart))
              end <- raws.indices.toList.foldLeftM(
                (Vector(setup.mirrorStart), Vector(setup.mirrorStart), 0)
              ) { case ((as, bs, differing), i) =>
                for
                  _ <- queue(i + window - 1, bs.last)
                  a <- withOut(as.last, headers(i), raws(i))
                  b <- withIn(bs.last, headers(i), raws(i))
                  // the store follows `lag` blocks behind, as it follows the immutable tip
                  _ <- (i - lag).some.filter(_ >= 0 && i % 8 == 0).traverse_ { j =>
                    storage(plain, as(j + 1)) *> storage(ahead, bs(j + 1))
                  }
                yield (as :+ a, bs :+ b, differing + (if a == b then 0 else 1))
              }
              plainAll <- plain.all
              warmAll <- ahead.all
              tier <- warm.held
            yield (end._3, plainAll == warmAll, end._1.last == end._2.last, tier, plainAll)
          }
          .map(_.asRight[NodeError])
      }
      .rescue(e => IO.pure(e.asLeft))
      .map { outcome =>
        val (differing, sameStore, sameLast, tier, all) =
          outcome.fold(e => fail(e.message), identity)
        assertEquals(differing, 0)
        assert(sameStore, "the stores differ")
        assert(sameLast, "the final states differ")
        val stats = tier.stats
        assert(stats.served > 0, s"nothing was served from the tier ($stats)")
        // every entry the tier holds at the end is the store's
        val byKey = all.keys.flatMap(i => Key.of(i).map(_ -> i)).toMap
        assert(
          tier.contents.forall((k, v) => byKey.get(k).flatMap(all.get) == v._1),
          "the tier holds a value that is not the store's"
        )
      }
  }

  private def storage(store: UtxoStore[IO], s: MirrorState)(using Raise[IO, StorageError]) =
    xyz.kd5ujc.scarab.consensus.state.MirrorStep.utxoOf(s).traverse_(UtxoStore.advance(store, _))

  /** `store` with its reads or its writes held at a gate, or its writes failing. */
  private final class Gated(
      store: UtxoStore[IO],
      beforeRead: IO[Unit],
      beforeWrite: IO[Unit]
  ) extends UtxoStore[IO]:
    def read(keys: Iterable[TxIn])(using Raise[IO, StorageError]) =
      store.read(keys).flatTap(_ => beforeRead)
    def stamp(using Raise[IO, StorageError]) = store.stamp
    def all(using Raise[IO, StorageError]) = store.all
    def select(keep: TxOut => Boolean)(using Raise[IO, StorageError]) = store.select(keep)
    def selectAddresses(addresses: Set[Bytes])(using Raise[IO, StorageError]) =
      store.selectAddresses(addresses)
    def write(changes: Iterable[(TxIn, Option[TxOut])], stamp: Long)(using
        Raise[IO, StorageError]
    ) = beforeWrite *> store.write(changes, stamp)
    def flushed(using Raise[IO, StorageError]) = store.flushed
    def readView: Resource[IO, Either[StorageError, UtxoPinnedReadView[IO]]] = store.readView
    def scan[A](zero: A)(step: (A, TxIn, TxOut) => A)(using Raise[IO, StorageError]) =
      store.scan(zero)(step)
    def entries(using Raise[IO, StorageError]): Stream[IO, (TxIn, TxOut)] = store.entries
    def size(using Raise[IO, StorageError]) = store.size
    def checkpoint(dir: FsPath)(using Raise[IO, StorageError]) = store.checkpoint(dir)
    def restore(dir: FsPath)(using Raise[IO, StorageError]) = store.restore(dir)
    def reset(using Raise[IO, StorageError]) = store.reset
    def memory(using Raise[IO, StorageError]) = store.memory

  private object Gated:
    /** Each read, once it has read, says so and waits for `release` before it returns. */
    def reads(store: UtxoStore[IO], reading: Deferred[IO, Unit], release: Deferred[IO, Unit]) =
      Gated(store, reading.complete(()).void *> release.get, IO.unit)

    /** Each write, before it changes the store, says so and waits for `release`. */
    def writes(store: UtxoStore[IO], writing: Deferred[IO, Unit], release: Deferred[IO, Unit]) =
      Gated(store, IO.unit, writing.complete(()).void *> release.get)

    /** Each write fails before it changes the store. */
    def failing(store: UtxoStore[IO]) =
      Gated(store, IO.unit, IO.raiseError(new RuntimeException("write refused")))
