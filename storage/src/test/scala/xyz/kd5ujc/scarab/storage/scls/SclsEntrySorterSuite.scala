// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import cats.effect.{Deferred, Fiber, IO, Ref}
import cats.mtl.Handle
import cats.syntax.all.*
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.TxIn
import xyz.kd5ujc.scarab.storage.StorageTestKit
import xyz.kd5ujc.scarab.storage.scls.SclsRecord.{Entry, Namespace}

class SclsEntrySorterSuite extends CatsEffectSuite:
  private def config(maxEntryBytes: Int = 4096): SclsEntrySorter.Config =
    SclsEntrySorter.Config.fromInt(maxEntryBytes).fold(error => fail(error.toString), identity)

  private def attempt[A](
      body: Handle[IO, SclsEntrySorter.Error] ?=> IO[A]
  ): IO[Either[SclsEntrySorter.Error, A]] =
    Handle
      .allow[SclsEntrySorter.Error](body.map(_.asRight[SclsEntrySorter.Error]))
      .rescue(error => IO.pure(error.asLeft[A]))

  private def ok[A](body: Handle[IO, SclsEntrySorter.Error] ?=> IO[A]): IO[A] =
    attempt(body).flatMap(
      _.fold(error => IO.raiseError(new AssertionError(error.toString)), IO.pure)
    )

  private val zeroUtxoKey = Bytes(Vector.fill(Namespace.Utxo.keySize)(0.toByte))
  private val zeroValue = Bytes.fromArray(Array[Byte](0x80.toByte))

  private def namespaceKey(namespace: Namespace): Bytes =
    if namespace == Namespace.PParams then
      Bytes("curr".getBytes(java.nio.charset.StandardCharsets.US_ASCII).toVector)
    else Bytes(Vector.fill(namespace.keySize)(0.toByte))

  private def sortBytes(namespace: Namespace, key: Bytes): Bytes =
    val prefix = namespace.name.getBytes(java.nio.charset.StandardCharsets.US_ASCII)
    val packed = new Array[Byte](prefix.length + 1 + key.size)
    System.arraycopy(prefix, 0, packed, 0, prefix.length)
    System.arraycopy(key.unsafeArray, key.unsafeOffset, packed, prefix.length + 1, key.size)
    Bytes.wrap(packed)

  test("configuration rejects an unbounded or empty entry budget") {
    assert(SclsEntrySorter.Config.fromInt(0) == Left(SclsEntrySorter.Error.InvalidEntryLimit(0)))
    assert(SclsEntrySorter.Config.fromInt(-1) == Left(SclsEntrySorter.Error.InvalidEntryLimit(-1)))
  }

  test("the native UTxO BE16 order is reordered into packed SCLS LE16 order") {
    StorageTestKit.tempDir.use { root =>
      val id = Bytes(Vector.tabulate(32)(i => (31 - i).toByte))
      val index1 =
        SclsKey.encode(SclsKey.Key.Utxo(TxIn(id, 1))).fold(e => fail(e.toString), identity)
      val index256 =
        SclsKey.encode(SclsKey.Key.Utxo(TxIn(id, 256))).fold(e => fail(e.toString), identity)
      val one = Entry(index1, Bytes.fromArray(Array[Byte](1)))
      val two56 = Entry(index256, Bytes.fromArray(Array[Byte](2)))
      ok {
        SclsEntrySorter.resource[IO](root, config()).use { sorter =>
          sorter.add(Namespace.Utxo, one) *> sorter.add(Namespace.Utxo, two56) *>
            sorter.entries.compile.toVector.map { entries =>
              assert(entries.map(_._2.key) == Vector(index256, index1))
              assert(entries.map(_._2.value) == Vector(two56.value, one.value))
            }
        }
      }
    }
  }

  test("all namespace pairs sort exactly as SclsOutput byte ordering, including the pool prefix") {
    StorageTestKit.tempDir.use { root =>
      val inserted =
        Namespace.values.toVector.reverse.map(ns => ns -> Entry(namespaceKey(ns), zeroValue))
      val expected = inserted.sortWith { case ((left, _), (right, _)) =>
        left.bytes.compareTo(right.bytes) < 0
      }
      val packedOrder = inserted.sortWith { case ((left, leftEntry), (right, rightEntry)) =>
        sortBytes(left, leftEntry.key).compareTo(sortBytes(right, rightEntry.key)) < 0
      }
      assert(expected.map(_._1) == packedOrder.map(_._1))
      Namespace.values.foreach { left =>
        Namespace.values.foreach { right =>
          assert(!left.name.contains('\u0000') && !right.name.contains('\u0000'))
          val expectedSign = Integer.signum(left.bytes.compareTo(right.bytes))
          val actualSign = Integer.signum(
            sortBytes(left, namespaceKey(left)).compareTo(sortBytes(right, namespaceKey(right)))
          )
          assert(actualSign == expectedSign)
        }
      }
      assert(
        sortBytes(Namespace.StakePools, namespaceKey(Namespace.StakePools)).compareTo(
          sortBytes(
            Namespace.StakePoolVrfHashes,
            namespaceKey(Namespace.StakePoolVrfHashes)
          )
        ) < 0
      )
      ok {
        SclsEntrySorter.resource[IO](root, config()).use { sorter =>
          inserted.traverse_ { case (ns, entry) => sorter.add(ns, entry) } *>
            sorter.entries.compile.toVector.map { actual =>
              assert(actual.map(_._1) == expected.map(_._1))
              assert(actual.map(_._2.key) == expected.map(_._2.key))
            }
        }
      }
    }
  }

  test("duplicates are rejected while identical raw keys in different namespaces are allowed") {
    StorageTestKit.tempDir.use { root =>
      val entry = Entry(Bytes(Vector(0.toByte)), zeroValue)
      ok {
        SclsEntrySorter.resource[IO](root, config()).use { sorter =>
          for
            _ <- sorter.add(Namespace.Committee, entry)
            _ <- sorter.add(Namespace.Constitution, entry)
            duplicate <- attempt(sorter.add(Namespace.Committee, entry))
            entries <- sorter.entries.compile.toVector
          yield
            duplicate match
              case Left(SclsEntrySorter.Error.DuplicateEntry(Namespace.Committee, found)) =>
                assert(found == entry.key)
              case Left(other) => fail(s"expected duplicate entry, found $other")
              case Right(_)    => fail("duplicate namespace/key was accepted")
            assert(
              entries.map(_._1) == Vector(Namespace.Committee, Namespace.Constitution)
                .sortWith((a, b) => a.bytes.compareTo(b.bytes) < 0)
            )
        }
      }
    }
  }

  test("entry byte limits include the namespace framing and reject before inserting") {
    StorageTestKit.tempDir.use { root =>
      val key = zeroUtxoKey
      val value = Bytes.fromArray(Array.fill[Byte](64)(0x7f.toByte))
      val result = for
        _ <- IO.unit
        observed <- attempt {
          SclsEntrySorter.resource[IO](root, config(maxEntryBytes = 40)).use { sorter =>
            sorter.add(Namespace.Utxo, Entry(key, value))
          }
        }
        children <- IO.blocking {
          val listing = java.nio.file.Files.list(root.toNioPath)
          try listing.count().toInt
          finally listing.close()
        }
      yield
        observed match
          case Left(SclsEntrySorter.Error.EntryTooLarge(Namespace.Utxo, actual, maximum)) =>
            assert(actual == 7L + 1L + Namespace.Utxo.keySize + value.size)
            assert(maximum == 40)
          case Left(other) => fail(s"expected bounded entry refusal, found $other")
          case Right(_)    => fail("oversized entry was accepted")
        assert(children == 0)
      result
    }
  }

  test("invalid profile keys are rejected before write and do not poison the sorter") {
    StorageTestKit.tempDir.use { root =>
      val invalid = Entry(Bytes(Vector.fill(Namespace.Utxo.keySize - 1)(0.toByte)), zeroValue)
      ok {
        SclsEntrySorter.resource[IO](root, config()).use { sorter =>
          for
            rejected <- attempt(sorter.add(Namespace.Utxo, invalid))
            _ <- sorter.add(Namespace.Utxo, Entry(zeroUtxoKey, zeroValue))
            entries <- sorter.entries.compile.toVector
          yield
            rejected match
              case Left(SclsEntrySorter.Error.Key(SclsKey.Error.Length(Namespace.Utxo, found))) =>
                assert(found == Namespace.Utxo.keySize - 1)
              case Left(other) => fail(s"expected key length refusal, found $other")
              case Right(_)    => fail("incorrect SCLS key was accepted")
            assert(entries.map(_._2.key) == Vector(zeroUtxoKey))
        }
      }
    }
  }

  test("one ordered pass streams many rows, and add or a second pass after sealing is typed") {
    StorageTestKit.tempDir.use { root =>
      val count = 12000
      ok {
        SclsEntrySorter.resource[IO](root, config()).use { sorter =>
          val inserted = (0 until count).reverse.toList.traverse_ { index =>
            val key = Array.fill[Byte](Namespace.Utxo.keySize)(0)
            key(0) = (index >>> 24).toByte
            key(1) = (index >>> 16).toByte
            key(2) = (index >>> 8).toByte
            key(3) = index.toByte
            sorter.add(Namespace.Utxo, Entry(Bytes.fromArray(key), zeroValue))
          }
          for
            _ <- inserted
            seen <- Ref.of[IO, Int](0)
            total <- sorter.entries.evalMap(_ => seen.update(_ + 1)).compile.drain *> seen.get
            addAfter <- attempt(sorter.add(Namespace.Utxo, Entry(zeroUtxoKey, zeroValue)))
            secondPass <- attempt(sorter.entries.compile.drain)
          yield
            assert(total == count)
            assert(addAfter == Left(SclsEntrySorter.Error.AddAfterSeal))
            assert(secondPass == Left(SclsEntrySorter.Error.EntriesAlreadyOpened))
        }
      }
    }
  }

  test("cancellation closes the iterator and resource release removes only its owned directory") {
    StorageTestKit.tempDir.use { root =>
      val rows = (0 until 2000).map { index =>
        val key = Array.fill[Byte](Namespace.Utxo.keySize)(0)
        key(0) = (index >>> 8).toByte
        key(1) = index.toByte
        Entry(Bytes.fromArray(key), zeroValue)
      }
      val run = for
        _ <- IO.blocking(
          java.nio.file.Files.writeString(root.toNioPath.resolve("keep.txt"), "owned by caller")
        )
        escaped <- Ref.of[IO, Option[SclsEntrySorter[IO]]](None)
        _ <- ok {
          SclsEntrySorter.resource[IO](root, config()).use { sorter =>
            for
              _ <- escaped.set(Some(sorter))
              _ <- rows.toList.traverse_(sorter.add(Namespace.Utxo, _))
              started <- Deferred[IO, Unit]
              fiber <- sorter.entries
                .evalTap(_ => started.complete(()))
                .evalMap(_ => IO.never[Unit])
                .compile
                .drain
                .start
              _ <- started.get
              _ <- fiber.cancel
              result <- attempt(sorter.add(Namespace.Utxo, Entry(zeroUtxoKey, zeroValue)))
              _ <- result match
                case Left(SclsEntrySorter.Error.AddAfterSeal) => IO.unit
                case other =>
                  IO.raiseError(new AssertionError(s"expected sealed sorter, found $other"))
            yield ()
          }
        }
        afterClose <- escaped.get.flatMap {
          case Some(sorter) => attempt(sorter.add(Namespace.Utxo, Entry(zeroUtxoKey, zeroValue)))
          case None => IO.raiseError(new AssertionError("sorter did not escape its resource scope"))
        }
        children <- IO.blocking {
          val listing = java.nio.file.Files.list(root.toNioPath)
          try listing.count().toInt
          finally listing.close()
        }
        sentinel <- IO.blocking(java.nio.file.Files.readString(root.toNioPath.resolve("keep.txt")))
      yield
        assert(afterClose == Left(SclsEntrySorter.Error.OperationAfterClose))
        assert(children == 1)
        assert(sentinel == "owned by caller")
      run
    }
  }

  test("parent release waits for an active iterator and cannot resurrect its phase") {
    StorageTestKit.tempDir.use { root =>
      val result = for
        iteratorStarted <- Deferred[IO, Unit]
        continueIterator <- Deferred[IO, Unit]
        releaseRequested <- Deferred[IO, Unit]
        sorterRef <- Ref.of[IO, Option[SclsEntrySorter[IO]]](None)
        iteratorRef <- Ref.of[IO, Option[Fiber[IO, Throwable, Unit]]](None)
        parent <- attempt {
          SclsEntrySorter.resource[IO](root, config()).use { sorter =>
            val entry = Entry(zeroUtxoKey, zeroValue)
            for
              _ <- sorterRef.set(Some(sorter))
              _ <- sorter.add(Namespace.Utxo, entry)
              iterator <- sorter.entries
                .evalTap(_ => iteratorStarted.complete(()).void)
                .evalMap(_ => continueIterator.get)
                .compile
                .drain
                .start
              _ <- iteratorRef.set(Some(iterator))
              _ <- iteratorStarted.get
              _ <- releaseRequested.complete(())
            yield ()
          }
        }.start
        _ <- releaseRequested.get
        beforeIteratorEnds <- IO.race(parent.join, IO.sleep(100.millis))
        _ <- continueIterator.complete(())
        parentResult <- parent.join.flatMap {
          case cats.effect.Outcome.Succeeded(value) => value
          case cats.effect.Outcome.Errored(error)   => IO.raiseError(error)
          case cats.effect.Outcome.Canceled() =>
            IO.raiseError(new AssertionError("parent canceled"))
        }
        _ <- iteratorRef.get.flatMap {
          case Some(iterator) => iterator.join.void
          case None           => IO.raiseError(new AssertionError("iterator was not started"))
        }
        closed <- sorterRef.get.flatMap {
          case Some(sorter) => attempt(sorter.add(Namespace.Utxo, Entry(zeroUtxoKey, zeroValue)))
          case None         => IO.raiseError(new AssertionError("sorter was not opened"))
        }
      yield
        assert(
          beforeIteratorEnds.isRight,
          s"parent released while iterator was active: $beforeIteratorEnds"
        )
        assertEquals(parentResult, Right(()))
        assertEquals(closed, Left(SclsEntrySorter.Error.OperationAfterClose))
      result
    }
  }

  test("a typed write failure cleans the temporary database and preserves other scratch files") {
    StorageTestKit.tempDir.use { root =>
      val key = Bytes(Vector(0.toByte))
      val entry = Entry(key, zeroValue)
      for
        _ <- IO.blocking(
          java.nio.file.Files.writeString(root.toNioPath.resolve("keep.txt"), "caller")
        )
        result <- attempt {
          SclsEntrySorter.resource[IO](root, config()).use { sorter =>
            sorter.add(Namespace.Committee, entry) *> sorter.add(Namespace.Committee, entry)
          }
        }
        children <- IO.blocking {
          val listing = java.nio.file.Files.list(root.toNioPath)
          try listing.count().toInt
          finally listing.close()
        }
        sentinel <- IO.blocking(java.nio.file.Files.readString(root.toNioPath.resolve("keep.txt")))
      yield
        result match
          case Left(SclsEntrySorter.Error.DuplicateEntry(Namespace.Committee, found)) =>
            assert(found == entry.key)
          case Left(other) => fail(s"expected duplicate write failure, found $other")
          case Right(_)    => fail("duplicate write did not fail")
        assert(children == 1)
        assert(sentinel == "caller")
    }
  }
