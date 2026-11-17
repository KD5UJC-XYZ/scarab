// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{StorageError, TxIn, TxOut, UtxoStoreClosure}
import xyz.kd5ujc.scarab.ledger.testByteStrings
import xyz.kd5ujc.scarab.network.FailsOnUncaught

/** The native objects of the RocksDB UTxO store that outlive one call, a pinned view's snapshot and
  * an enumeration's iterator, held while the store is reset, restored or released: none of those
  * closes the database under them. A reset leaves them reading what they pinned; a restore or the
  * release ends them with [[StorageError.UtxoStoreClosed]], and releasing them after is safe.
  */
class RocksUtxoLeaseSuite extends FailsOnUncaught:
  private val CacheBytes = 8L * 1024 * 1024

  private def be(n: Long, size: Int): Array[Byte] =
    Array.tabulate(size)(i => ((n >>> (8 * (size - 1 - i))) & 0xff).toByte)

  /** An output to an enterprise address of payment key `pay`, of `coin` lovelace. */
  private def output(pay: Int, coin: Long): TxOut =
    val address = 0x61.toByte +: be(pay.toLong, 28)
    val cbor = Array[Byte](0x82.toByte, 0x58.toByte, address.length.toByte) ++ address ++
      Array[Byte](0x1a.toByte) ++ be(coin, 4)
    TxOut.fromBytes(Bytes.wrap(cbor)).fold(e => fail(e.toString), identity)

  private def input(n: Int): TxIn = TxIn(Bytes.wrap(be(n.toLong, 32)), 0)

  private val a = input(1)
  private val b = input(2)
  private val c = input(3)
  private val first = output(1, 10)
  private val second = output(2, 20)
  private val third = output(3, 30)

  private def snapshots(memory: Map[String, Long]): Option[Long] = memory.get("num-snapshots")

  test("a view pinned before a reset keeps reading what it pinned, and is released after it") {
    StorageTestKit.tempDir.use { dir =>
      StorageTestKit.ok {
        RocksUtxoStore.resource[IO](dir / "live", CacheBytes).use { store =>
          for
            _ <- store.write(Vector(a -> Some(first), b -> Some(second)), 10L)
            pinned <- store.readView.allocated
            (opened, release) = pinned
            view <- IO.fromEither(opened.leftMap(e => new AssertionError(e.message)))
            _ <- store.reset
            // the view's snapshot is still the store's: the reset did not close the database
            held <- store.memory
            _ <- IO(assertEquals(snapshots(held), Some(1L), "the reset keeps the view's snapshot"))
            emptied <- (store.all, store.flushed).tupled
            _ <- store.write(Vector(c -> Some(third)), 1L)
            all <- view.all
            read <- view.read(Vector(a, b, c))
            paying <- view.selectAddresses(Set(second.address.raw))
            current <- (store.all, store.flushed).tupled
            _ <- release
            after <- store.memory
          yield
            assertEquals(emptied, (Map.empty[TxIn, TxOut], -1L), "the reset empties the store")
            assertEquals(view.stamp, 10L)
            assertEquals(all, Right(Map(a -> first, b -> second)))
            assertEquals(read, Right(Map(a -> first, b -> second)))
            assertEquals(paying, Right(Map(b -> second)))
            assertEquals(current, (Map(c -> third), 1L), "the store reads what was written after")
            assertEquals(snapshots(after), Some(0L), "the release frees the snapshot")
        }
      }
    }
  }

  test("a view still held when the store is released ends with the store's closed error") {
    StorageTestKit.tempDir.use { dir =>
      val live = dir / "live"
      val released = StorageError.UtxoStoreClosed(live.toString, UtxoStoreClosure.Released)
      StorageTestKit.ok {
        RocksUtxoStore.resource[IO](live, CacheBytes).allocated.flatMap { (store, releaseStore) =>
          for
            _ <- store.write(Vector(a -> Some(first)), 1L)
            pinned <- store.readView.allocated
            (opened, release) = pinned
            view <- IO.fromEither(opened.leftMap(e => new AssertionError(e.message)))
            _ <- releaseStore
            all <- view.all
            read <- view.read(Vector(a))
            paying <- view.selectAddresses(Set(first.address.raw))
            _ <- release
          yield
            assertEquals(all, Left(released))
            assertEquals(read, Left(released))
            assertEquals(paying, Left(released))
        }
      }
    }
  }

  test("an enumeration open when the store is released ends with the store's closed error") {
    StorageTestKit.tempDir.use { dir =>
      val live = dir / "live"
      val released = StorageError.UtxoStoreClosed(live.toString, UtxoStoreClosure.Released)
      StorageTestKit.ok {
        RocksUtxoStore.resource[IO](live, CacheBytes).allocated.flatMap { (store, releaseStore) =>
          for
            _ <- store.write(Vector(a -> Some(first), b -> Some(second), c -> Some(third)), 1L)
            // the store is released after the first entry, while the enumeration's iterator is open
            listed <- StorageTestKit.attempt(
              store.entries.zipWithIndex
                .evalTap((_, i) => releaseStore.whenA(i == 0))
                .map(_._1)
                .compile
                .toVector
            )
          yield assertEquals(listed, Left(released))
        }
      }
    }
  }

  test(
    "a view pinned before a restore ends with the store's closed error, and is released after it"
  ) {
    StorageTestKit.tempDir.use { dir =>
      val live = dir / "live"
      val replaced = StorageError.UtxoStoreClosed(live.toString, UtxoStoreClosure.Replaced)
      StorageTestKit.ok {
        RocksUtxoStore.resource[IO](live, CacheBytes).use { store =>
          for
            _ <- store.write(Vector(a -> Some(first)), 1L)
            _ <- StorageTestKit.ok(store.checkpoint(dir / "cp"))
            _ <- store.write(Vector(b -> Some(second)), 2L)
            pinned <- store.readView.allocated
            (opened, release) = pinned
            view <- IO.fromEither(opened.leftMap(e => new AssertionError(e.message)))
            restored <- store.restore(dir / "cp")
            all <- view.all
            read <- view.read(Vector(a, b))
            paying <- view.selectAddresses(Set(first.address.raw))
            _ <- release
            current <- (store.all, store.flushed).tupled
            after <- store.memory
          yield
            assert(restored)
            assertEquals(view.stamp, 2L)
            assertEquals(all, Left(replaced))
            assertEquals(read, Left(replaced))
            assertEquals(paying, Left(replaced))
            assertEquals(current, (Map(a -> first), 1L), "the store reads the checkpoint")
            assertEquals(snapshots(after), Some(0L), "the new database holds no snapshot")
        }
      }
    }
  }

  test(
    "an enumeration open across a reset lists what it started on, and one across a restore ends"
  ) {
    StorageTestKit.tempDir.use { dir =>
      val live = dir / "live"
      val replaced = StorageError.UtxoStoreClosed(live.toString, UtxoStoreClosure.Replaced)
      def interrupted(store: UtxoStore[IO], by: IO[Unit]) =
        StorageTestKit.attempt(
          store.entries.zipWithIndex
            .evalTap((_, i) => by.whenA(i == 0))
            .map(_._1)
            .compile
            .toVector
        )
      StorageTestKit.ok {
        RocksUtxoStore.resource[IO](live, CacheBytes).use { store =>
          for
            _ <- store.write(Vector(a -> Some(first), b -> Some(second)), 1L)
            _ <- StorageTestKit.ok(store.checkpoint(dir / "cp"))
            acrossReset <- interrupted(store, store.reset)
            emptied <- (store.size, store.flushed).tupled
            _ <- store.write(Vector(a -> Some(first), b -> Some(second), c -> Some(third)), 2L)
            acrossRestore <- interrupted(store, store.restore(dir / "cp").void)
            current <- store.all
          yield
            assertEquals(acrossReset, Right(Vector(a -> first, b -> second)))
            assertEquals(emptied, (0L, -1L))
            assertEquals(acrossRestore, Left(replaced))
            assertEquals(current, Map(a -> first, b -> second))
        }
      }
    }
  }

  test("a reset with nothing pinned leaves a fresh store, and a view pinned after it reads it") {
    StorageTestKit.tempDir.use { dir =>
      StorageTestKit.ok {
        RocksUtxoStore.resource[IO](dir / "live", CacheBytes).use { store =>
          for
            _ <- store.write(Vector(a -> Some(first)), 3L)
            _ <- store.reset
            emptied <- (store.all, store.flushed).tupled
            _ <- store.write(Vector(b -> Some(second)), 0L)
            seen <- store.readView.use(_.traverse(view => view.all.map(view.stamp -> _)))
          yield
            assertEquals(emptied, (Map.empty[TxIn, TxOut], -1L))
            assertEquals(seen, Right(0L -> Right(Map(b -> second))))
        }
      }
    }
  }
