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
  Address,
  Block,
  Header,
  NodeError,
  StorageError,
  TxIn,
  TxOut,
  Utxo,
  Value
}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import xyz.kd5ujc.scarab.storage.{MemoryUtxoStore, RocksUtxoStore, UtxoPinnedReadView, UtxoStore}

/** The read-ahead store ([[ReadAheadStore]], #1484): the block path's read set is the store's,
  * whatever was read ahead; an entry read ahead and then spent, flushed and trimmed is not served;
  * the buffer stays within its bounds; a reset forgets what was read before it, also a read in
  * flight across it; and the era-walking chain applied over a RocksDB store gives the same states
  * with read-ahead as without.
  */
class ReadAheadStoreSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught with ScalaCheckSuite:
  import ReadAheadStore.{Bounds, Buffer}

  private val address: Address =
    Address
      .decode(Bytes.fromHex("61" + "05" * 28).fold(e => fail(e.toString), identity))
      .fold(e => fail(e.toString), identity)

  private def in(n: Int): TxIn = TxIn(Bytes(Vector.fill(32)((n & 0xff).toByte)), n)
  private def out(n: Int): TxOut = TxOut(address, Value.coin(BigInt(1000000 + n)))

  /** `body` with a typed storage error failing the test. */
  private def ok[A](body: Raise[IO, StorageError] ?=> IO[A]): IO[A] =
    Handle
      .allow[StorageError](body.map(_.asRight[StorageError]))
      .rescue(e => IO.pure(e.asLeft[A]))
      .flatMap(_.fold(e => IO(fail(s"storage error: $e")), IO.pure))

  /** A state's set whose diff is empty and whose trim point is `flushed`: every key is the store's.
    */
  private def trimmedAt(flushed: Long): Utxo = Utxo.empty.copy(flushed = flushed, stamp = flushed)

  // ------------------------------------------------------------------ the buffer

  private val ops: Gen[Vector[Buffer => Buffer]] =
    val put = for
      gen <- Gen.choose(0L, 2L)
      at <- Gen.choose(-1L, 20L)
      keys <- Gen.listOf(Gen.choose(0, 63))
    yield (b: Buffer) => b.put(gen, at, keys.map(k => in(k) -> out(k)).toMap)
    val take = for
      flushed <- Gen.choose(-1L, 20L)
      keys <- Gen.listOf(Gen.choose(0, 63))
    yield (b: Buffer) => b.take(keys.map(in), flushed)._1
    Gen
      .listOf(Gen.frequency(6 -> put, 3 -> take, 1 -> Gen.const((b: Buffer) => b.cleared)))
      .map(_.toVector)

  property("the buffer never exceeds its bounds, and its bytes are those of the outputs it holds") {
    Prop.forAll(ops, Gen.choose(0, 40), Gen.choose(0L, 2000L)) { (steps, entries, bytes) =>
      val bounds = Bounds(entries, bytes)
      steps.scanLeft(Buffer.empty(bounds))((b, step) => step(b)).forall { b =>
        val (_, all) = b.take((0 until 64).map(in), Long.MinValue)
        b.size <= entries && b.bytes <= bytes && all.size == b.size &&
        b.bytes == all.valuesIterator.map(_.raw.size.toLong).sum
      }
    }
  }

  property("within its bounds the buffer gives exactly what was read at or after the trim point") {
    val model = for
      reads <- Gen.listOf(Gen.zip(Gen.choose(-1L, 20L), Gen.listOf(Gen.choose(0, 63))))
      flushed <- Gen.choose(-1L, 20L)
      keys <- Gen.listOf(Gen.choose(0, 63))
    yield (reads, flushed, keys)
    Prop.forAll(model) { (reads, flushed, keys) =>
      val filled = reads.foldLeft(Buffer.empty(Bounds.Default)) { case (b, (at, ks)) =>
        b.put(0, at, ks.map(k => in(k) -> out(k)).toMap)
      }
      // the newest read of each key wins
      val newest = reads.foldLeft(Map.empty[Int, Long]) { case (m, (at, ks)) =>
        ks.foldLeft(m)((n, k) => n.updated(k, at))
      }
      val expected =
        keys.distinct.collect { case k if newest.get(k).exists(_ >= flushed) => in(k) -> out(k) }
      val (after, served) = filled.take(keys.map(in), flushed)
      served == expected.toMap && keys.forall(k => !after.holds(in(k)))
    }
  }

  test("a read put in a generation the buffer has left is not kept") {
    val b = Buffer.empty(Bounds.Default).cleared
    assertEquals(b.put(0, 5, Map(in(1) -> out(1))).size, 0)
    assertEquals(b.put(1, 5, Map(in(1) -> out(1))).size, 1)
  }

  // ------------------------------------------------------------------ the store

  test("an entry read ahead, then spent, flushed and trimmed before use, is not served") {
    ok {
      for
        memory <- MemoryUtxoStore[IO]
        store <- ReadAheadStore.idle[IO](memory)
        _ <- memory.write(Vector(in(1) -> Some(out(1)), in(2) -> Some(out(2))), 10)
        _ <- store.prefetch(Set(in(1), in(2)))
        // both spent by block 20, which is flushed, and the state trimmed to it
        _ <- memory.write(Vector(in(1) -> None, in(2) -> None), 20)
        stale <- store.readFor(trimmedAt(20), SortedSet(in(1)))
        direct <- memory.read(Vector(in(1)))
        // a state trimmed no further than the read: its diff holds the spend, and the key is not
        // asked for; asked for all the same, the value read ahead is the store's as of then
        served <- store.readFor(trimmedAt(10), SortedSet(in(2)))
        buffer <- store.buffered.get
      yield
        assertEquals(stale, Map.empty[TxIn, TxOut])
        assertEquals(stale, direct)
        assertEquals(served, Map(in(2) -> out(2)))
        assertEquals((buffer.served, buffer.stale, buffer.size), (1L, 1L, 0))
    }
  }

  test("an input absent when read ahead and created later is read from the store") {
    ok {
      for
        memory <- MemoryUtxoStore[IO]
        store <- ReadAheadStore.idle[IO](memory)
        _ <- store.prefetch(Set(in(3)))
        _ <- memory.write(Vector(in(3) -> Some(out(3))), 30)
        read <- store.readFor(trimmedAt(30), SortedSet(in(3)))
        held <- store.buffered.get
      yield
        assertEquals(read, Map(in(3) -> out(3)))
        assertEquals(held.size, 0)
    }
  }

  test("a reset forgets what was read ahead") {
    ok {
      for
        memory <- MemoryUtxoStore[IO]
        store <- ReadAheadStore.idle[IO](memory)
        _ <- memory.write(Vector(in(4) -> Some(out(4))), 40)
        _ <- store.prefetch(Set(in(4)))
        _ <- store.reset
        read <- store.readFor(trimmedAt(-1), SortedSet(in(4)))
      yield assertEquals(read, Map.empty[TxIn, TxOut])
    }
  }

  test("a read in flight across a reset is not kept") {
    ok {
      for
        memory <- MemoryUtxoStore[IO]
        _ <- memory.write(Vector(in(5) -> Some(out(5))), 50)
        reading <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        store <- ReadAheadStore.idle[IO](Gated(memory, reading, release))
        prefetch <- store.prefetch(Set(in(5))).start
        _ <- reading.get
        _ <- store.reset
        _ <- release.complete(())
        _ <- prefetch.joinWithNever
        held <- store.buffered.get
      yield assertEquals(held.size, 0)
    }
  }

  test("a read asked for ahead is read by a reader") {
    ok {
      MemoryUtxoStore[IO]
        .flatMap { memory =>
          memory.write(Vector(in(6) -> Some(out(6))), 60) *>
            ReadAheadStore.resource[IO](memory).use { store =>
              store.ahead(IO.pure(Set(in(6)))) *>
                store.buffered.discrete.find(_.holds(in(6))).compile.drain *>
                store.readFor(trimmedAt(60), SortedSet(in(6))).product(store.buffered.get)
            }
        }
        .map { (read, held) =>
          assertEquals(read, Map(in(6) -> out(6)))
          assertEquals(held.served, 1L)
        }
    }
  }

  /** Random interleavings of blocks (spending live and spent entries, creating new ones), flushes,
    * trims, reads ahead and block reads: each block read set from the read-ahead store is the
    * store's.
    */
  private enum Op:
    case Apply(spend: List[Int], create: Int)
    case Flush, Trim
    case Ahead(keys: List[Int])
    case Use(keys: List[Int])

  test("under any interleaving the block path's read set is the store's") {
    val op: Gen[Op] = Gen.frequency(
      4 -> Gen.zip(Gen.listOf(Gen.choose(0, 40)), Gen.choose(1, 4)).map(Op.Apply.apply),
      2 -> Gen.const(Op.Flush),
      2 -> Gen.const(Op.Trim),
      3 -> Gen.listOf(Gen.choose(0, 40)).map(Op.Ahead.apply),
      3 -> Gen.listOf(Gen.choose(0, 40)).map(Op.Use.apply)
    )
    val cases =
      Gen.listOfN(200, Gen.listOf(op)).apply(Gen.Parameters.default, Seed(1484L)).toList.flatten
    ok {
      cases.traverse { ops =>
        for
          memory <- MemoryUtxoStore[IO]
          store <- ReadAheadStore.idle[IO](memory)
          // the chain's set and the next key and block number
          end <- ops.foldLeftM((Utxo.empty, 0, 1L, 0)) {
            case ((utxo, next, block, mismatches), o) =>
              def blockRead(keys: List[Int]): IO[(Map[TxIn, TxOut], Boolean)] =
                val wanted = utxo.missing(keys.map(in))
                (store.readFor(utxo, wanted), memory.read(wanted)).mapN((a, b) => (a, a == b))
              o match
                case Op.Apply(spend, create) =>
                  blockRead(spend).map { (read, same) =>
                    val applied = (utxo.at(block).withResident(read) -- spend.map(in)) ++
                      (next until next + create).map(k => in(k) -> out(k))
                    (
                      applied.copy(resident = Map.empty),
                      next + create,
                      block + 1,
                      mismatches + (if same then 0 else 1)
                    )
                  }
                case Op.Flush =>
                  UtxoStore.advance(memory, utxo).as((utxo, next, block, mismatches))
                case Op.Trim =>
                  memory.flushed.map(at =>
                    (utxo.settled(at, force = true), next, block, mismatches)
                  )
                case Op.Ahead(keys) =>
                  store.prefetch(keys.map(in).toSet).as((utxo, next, block, mismatches))
                case Op.Use(keys) =>
                  blockRead(keys).map((_, same) =>
                    (utxo, next, block, mismatches + (if same then 0 else 1))
                  )
          }
          buffer <- store.buffered.get
        yield (end._4, buffer.served, buffer.stale)
      }
    }.map { results =>
      assertEquals(results.map(_._1).sum, 0)
      // not vacuous: entries were both served and refused as stale
      assert(results.map(_._2).sum > 0, "no entry was served")
      assert(results.map(_._3).sum > 0, "no entry was found stale")
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

  test("the era-walking chain over a RocksDB store reaches the same states with read-ahead") {
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
        ahead <- Resource.eval(ReadAheadStore.idle[IO](rocks))
      yield (plain, ahead)
    Handle
      .allow[NodeError] {
        stores
          .use { (plain, ahead) =>
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
              aheadAll <- ahead.all
              buffer <- ahead.buffered.get
            yield (end._3, plainAll == aheadAll, end._1.last == end._2.last, buffer)
          }
          .map(_.asRight[NodeError])
      }
      .rescue(e => IO.pure(e.asLeft))
      .map { outcome =>
        val (differing, sameStore, sameLast, buffer) = outcome.fold(e => fail(e.message), identity)
        assertEquals(differing, 0)
        assert(sameStore, "the stores differ")
        assert(sameLast, "the final states differ")
        assert(buffer.served > 0, s"nothing was served from the read-ahead ($buffer)")
      }
  }

  private def storage(store: UtxoStore[IO], s: MirrorState)(using Raise[IO, StorageError]) =
    xyz.kd5ujc.scarab.consensus.state.MirrorStep.utxoOf(s).traverse_(UtxoStore.advance(store, _))

  /** `store`, whose reads say when they have read and wait for `release` before they return. */
  private final class Gated(
      store: UtxoStore[IO],
      reading: Deferred[IO, Unit],
      release: Deferred[IO, Unit]
  ) extends UtxoStore[IO]:
    def read(keys: Iterable[TxIn])(using Raise[IO, StorageError]) =
      store.read(keys).flatTap(_ => reading.complete(()) *> release.get)
    def stamp(using Raise[IO, StorageError]) = store.stamp
    def all(using Raise[IO, StorageError]) = store.all
    def select(keep: TxOut => Boolean)(using Raise[IO, StorageError]) = store.select(keep)
    def selectAddresses(addresses: Set[Bytes])(using Raise[IO, StorageError]) =
      store.selectAddresses(addresses)
    def write(changes: Iterable[(TxIn, Option[TxOut])], stamp: Long)(using
        Raise[IO, StorageError]
    ) = store.write(changes, stamp)
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
