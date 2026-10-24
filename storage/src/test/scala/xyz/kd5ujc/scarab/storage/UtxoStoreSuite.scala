// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.io.file.Path
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.ledger.*
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*

/** The UTxO set behind a store: the in-memory and the RocksDB interpreters against a plain `Map`
  * model under random sequences of blocks, rollbacks and flushes, with the incrementally kept
  * stake, total and count against a recomputation.
  */
class UtxoStoreSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(40)

  // -------------------------------------------------------------------------------------------
  // Generators

  private def be(n: Long, size: Int): Array[Byte] =
    Array.tabulate(size)(i => ((n >>> (8 * (size - 1 - i))) & 0xff).toByte)

  private def credBytes(seed: Int): Array[Byte] = be(seed.toLong, 28)

  /** An output with the original CBOR of a real one (enterprise, base or pointer address). */
  private def output(kind: Int, pay: Int, stake: Int, coin: Long): TxOut =
    val address: Array[Byte] = kind match
      case 0 => 0x61.toByte +: credBytes(pay)
      case 1 => (0x01.toByte +: credBytes(pay)) ++ credBytes(stake)
      case 2 => (0x41.toByte +: credBytes(pay)) ++ Array[Byte](1, 2, (stake % 3).toByte)
      case _ => (0x71.toByte +: credBytes(pay)) // enterprise, script
    val cbor =
      Array[Byte](0x82.toByte, 0x58.toByte, address.length.toByte) ++ address ++
        Array[Byte](0x1a.toByte) ++ be(coin, 4)
    TxOut.fromBytes(Bytes.wrap(cbor)).fold(e => throw new AssertionError(e), identity)

  /** Same, but built by hand with no original encoding, as genesis and test outputs are. */
  private def synthetic(pay: Int, stake: Int, coin: Long): TxOut =
    TxOut(
      Address(
        Bytes.wrap(0x01.toByte +: (credBytes(pay) ++ credBytes(stake))),
        Address.Kind.Shelley(
          0,
          Credential.Key(Bytes.wrap(credBytes(pay))),
          Some(Credential.Key(Bytes.wrap(credBytes(stake)))),
          None
        )
      ),
      Value.coin(coin)
    )

  private val genOut: Gen[TxOut] =
    for
      kind <- Gen.choose(0, 3)
      pay <- Gen.choose(0, 5)
      stake <- Gen.choose(0, 4)
      coin <- Gen.choose(1L, 1000000L)
      synth <- Gen.frequency(9 -> Gen.const(false), 1 -> Gen.const(true))
    yield if synth then synthetic(pay, stake, coin) else output(kind, pay, stake, coin)

  private enum Action:
    case Block(spends: Int, creates: Vector[TxOut], pick: Long)
    case Rollback(depth: Int)

  private val genAction: Gen[Action] = Gen.frequency(
    8 -> (for
      spends <- Gen.choose(0, 4)
      creates <- Gen.listOfN(3, genOut).flatMap(xs => Gen.choose(0, 3).map(xs.take))
      pick <- Gen.choose(0L, 1000L)
    yield Action.Block(spends, creates.toVector, pick)),
    2 -> Gen.choose(1, 4).map(Action.Rollback(_))
  )

  // -------------------------------------------------------------------------------------------
  // The chain: the follower's window of states over a store

  /** @param utxos
    *   tip last; the window
    * @param models
    *   the expected set after each window state
    */
  private final case class Chain(
      utxos: Vector[Utxo],
      anchor: Utxo,
      models: Vector[Map[TxIn, TxOut]],
      anchorModel: Map[TxIn, TxOut],
      blockNo: Long,
      txCounter: Long
  )

  private val k = 3

  private def run(store: UtxoStore[IO], actions: List[Action])(using
      cats.mtl.Raise[cats.effect.IO, xyz.kd5ujc.scarab.ledger.StorageError]
  ): IO[Boolean] =
    val start = Chain(Vector.empty, Utxo.empty, Vector.empty, Map.empty, 0, 0)
    actions
      .foldLeft(IO.pure((start, true))) { (acc, action) =>
        acc.flatMap { case (chain, ok) =>
          step(store, chain, action).flatMap { next =>
            check(store, next).map(good => next -> (ok && good))
          }
        }
      }
      .map(_._2)

  private def step(store: UtxoStore[IO], chain: Chain, action: Action)(using
      cats.mtl.Raise[cats.effect.IO, xyz.kd5ujc.scarab.ledger.StorageError]
  ): IO[Chain] = action match
    case Action.Rollback(depth) =>
      val d = math.min(depth, chain.utxos.size)
      val kept = chain.utxos.dropRight(d)
      IO.pure(
        chain.copy(
          utxos = kept,
          models = chain.models.dropRight(d),
          blockNo = chain.blockNo - d
        )
      )
    case Action.Block(spends, creates, pick) =>
      val tip = chain.utxos.lastOption.getOrElse(chain.anchor)
      val model = chain.models.lastOption.getOrElse(chain.anchorModel)
      val keys = model.keys.toVector.sortBy(_.render)
      val spent =
        if keys.isEmpty then Vector.empty
        else
          (0 until math.min(spends, keys.size))
            .map(i => keys(((pick + 7 * i) % keys.size).toInt))
            .distinct
            .toVector
      val blockNo = chain.blockNo + 1
      val txId = Bytes.wrap(be(chain.txCounter + 1, 32))
      val made = creates.zipWithIndex.map((o, i) => TxIn(txId, i) -> o)
      for
        fetched <- store.read(tip.missing(spent))
        applied = tip.at(blockNo).withResident(fetched) -- spent ++ made
        flushed <- StorageTestKit.ok(store.flushed)
        settled = applied.settled(flushed)
        window = chain.utxos :+ settled
        models = chain.models :+ ((model -- spent) ++ made)
        next <-
          if window.size > k then
            val newAnchor = window.head
            StorageTestKit
              .ok(UtxoStore.advance(store, newAnchor))
              .as(
                chain.copy(
                  utxos = window.tail,
                  anchor = newAnchor,
                  models = models.tail,
                  anchorModel = models.head,
                  blockNo = blockNo,
                  txCounter = chain.txCounter + 1
                )
              )
          else
            IO.pure(
              chain.copy(
                utxos = window,
                models = models,
                blockNo = blockNo,
                txCounter = chain.txCounter + 1
              )
            )
      yield next

  private def recomputed(
      model: Map[TxIn, TxOut]
  ): (BigInt, Long, Map[Credential, BigInt], Map[Ptr, BigInt]) =
    val stake = model.valuesIterator
      .collect { case TxOut(_, Address(_, Address.Kind.Shelley(_, _, Some(c), _)), v, _, _, _) =>
        c -> v.coin
      }
      .toVector
      .groupMapReduce(_._1)(_._2)(_ + _)
      .filter(_._2 != 0)
    val ptrs = model.valuesIterator
      .collect { case TxOut(_, Address(_, Address.Kind.Shelley(_, _, None, Some(p))), v, _, _, _) =>
        p -> v.coin
      }
      .toVector
      .groupMapReduce(_._1)(_._2)(_ + _)
      .filter(_._2 != 0)
    (model.valuesIterator.map(_.value.coin).sum, model.size.toLong, stake, ptrs)

  /** Every retained state, completed from the store, equals its model; the incremental stake, total
    * and count equal a recomputation; and the store alone equals the anchor.
    */
  private def check(store: UtxoStore[IO], chain: Chain)(using
      cats.mtl.Raise[cats.effect.IO, xyz.kd5ujc.scarab.ledger.StorageError]
  ): IO[Boolean] =
    for
      stored <- store.all
      completed <- chain.utxos.traverse(u => UtxoStore.materialize(store, u))
      // a query for some addresses reads only the outputs it keeps: the same as filtering the whole
      keep0 = (a: Bytes) => a.hex.hashCode % 2 == 0
      keep = (out: TxOut) => keep0(out.address.raw)
      kept <- chain.utxos.traverse(u => UtxoStore.materializeWhere(store, u, keep))
      // the same through the address path, which passes over the other outputs undecoded
      addresses = chain.models.flatMap(_.valuesIterator.map(_.address.raw)).filter(keep0).toSet
      byAddress <- chain.utxos.traverse(u => UtxoStore.materializeAddresses(store, u, addresses))
    yield
      val states = chain.utxos.zip(chain.models).zip(completed.zip(kept)).forall {
        case ((u, model), (full, some)) =>

          val (coin, count, stake, ptrs) = recomputed(model)
          some == model.filter((_, out) => keep(out)) &&
          full == model && u.coin == coin && u.count == count && u.stake == stake && u.ptrStake == ptrs
      }
      val anchor = chain.anchor
      val (coin, count, stake, ptrs) = recomputed(chain.anchorModel)
      val paths = byAddress.zip(chain.models).forall { case (got, model) =>
        got == model.filter((_, out) => addresses(out.address.raw))
      }
      states && paths && stored == chain.anchorModel && anchor.coin == coin && anchor.count == count &&
      anchor.stake == stake && anchor.ptrStake == ptrs

  test("the address of an encoded output is found from the head of its CBOR, or not at all") {
    def bytes(xs: Int*): Array[Byte] = xs.map(_.toByte).toArray
    val addr = Array.tabulate[Int](57)(i => if i == 0 then 0x01 else i)
    // tag 1, array(2), bytes(57) as 0x58 0x39, then a coin
    val array = bytes((Seq(1, 0x82, 0x58, 57) ++ addr :+ 0x05)*)
    // tag 1, map(2), key 0, bytes(57), key 1, a coin
    val map = bytes((Seq(1, 0xa2, 0x00, 0x58, 57) ++ addr ++ Seq(0x01, 0x05))*)
    // a map whose first key is not 0, an indefinite array and an output in the codec form
    val reordered = bytes((Seq(1, 0xa2, 0x01, 0x05, 0x00, 0x58, 57) ++ addr)*)
    val indefinite = bytes((Seq(1, 0x9f, 0x58, 57) ++ addr :+ 0xff)*)
    val codec = bytes(0, 1, 2, 3)
    assertEquals(UtxoStore.Coding.addressSpan(array), Some((4, 57)))
    assertEquals(UtxoStore.Coding.addressSpan(map), Some((5, 57)))
    assertEquals(UtxoStore.Coding.addressSpan(reordered), None)
    assertEquals(UtxoStore.Coding.addressSpan(indefinite), None)
    assertEquals(UtxoStore.Coding.addressSpan(codec), None)
    val wanted = Array(addr.map(_.toByte))
    val other = Array(Array.fill[Byte](57)(9))
    assert(UtxoStore.Coding.mayPayTo(array, wanted))
    assert(UtxoStore.Coding.mayPayTo(map, wanted))
    assert(!UtxoStore.Coding.mayPayTo(array, other))
    assert(!UtxoStore.Coding.mayPayTo(map, other))
    // not located: decoded, never skipped
    assert(UtxoStore.Coding.mayPayTo(reordered, other))
    assert(UtxoStore.Coding.mayPayTo(codec, other))
    // a pointer address (type 4) is a candidate whatever the wanted set holds
    val pointer = bytes((Seq(1, 0x82, 0x58, 57, 0x40) ++ addr.tail :+ 0x05)*)
    assert(UtxoStore.Coding.mayPayTo(pointer, other))
  }

  // -------------------------------------------------------------------------------------------

  private def orFail[A](program: Handle[IO, StorageError] ?=> IO[A]): A =
    Handle
      .allow[StorageError](program)
      .rescue(e => IO.raiseError(new RuntimeException(e.message)))
      .unsafeRunSync()

  private def tempDir(): java.nio.file.Path =
    java.nio.file.Files.createTempDirectory("utxo-store-suite")

  private def rocks[A](use: UtxoStore[IO] => IO[A])(using Handle[IO, StorageError]): IO[A] =
    val dir = tempDir()
    RocksUtxoStore
      .resource[IO](Path.fromNioPath(dir) / "live", 8L * 1024 * 1024)
      .use(use)
      .guarantee(IO.blocking(RocksUtxoStore.deleteTree(dir)))

  test("a Rocks read view keeps its stamp and entries across later batches") {
    val first = TxIn(Bytes.wrap(be(1, 32)), 0)
    val second = TxIn(Bytes.wrap(be(2, 32)), 0)
    val original = output(0, 1, 1, 10)
    val later = output(0, 2, 2, 20)
    orFail {
      rocks { store =>
        for
          _ <- store.write(Vector(first -> Some(original)), 10L)
          result <- store.readView.use { pinned =>
            pinned match
              case Left(error) => summon[Handle[IO, StorageError]].raise(error)
              case Right(view) =>
                for
                  before <- view.all.flatMap(
                    _.fold(summon[Handle[IO, StorageError]].raise, IO.pure)
                  )
                  _ <- store.write(Vector(first -> None, second -> Some(later)), 11L)
                  after <- view.all.flatMap(_.fold(summon[Handle[IO, StorageError]].raise, IO.pure))
                  oldInput <- view
                    .read(Vector(first, second))
                    .flatMap(_.fold(summon[Handle[IO, StorageError]].raise, IO.pure))
                  current <- store.all
                yield (before, after, oldInput, current, view.stamp)
          }
        yield
          val (before, after, oldInput, current, pinnedStamp) = result
          assertEquals(pinnedStamp, 10L)
          assertEquals(before, Map(first -> original))
          assertEquals(after, before)
          assertEquals(oldInput, before)
          assertEquals(current, Map(second -> later))
      }
    }
  }

  test("canceling a Rocks read view releases its native snapshot") {
    val input = TxIn(Bytes.wrap(be(3, 32)), 0)
    val out = output(0, 3, 3, 30)
    orFail {
      rocks { store =>
        for
          before <- store.memory
          started <- Deferred[IO, Unit]
          reader <- store.readView.use(_ => started.complete(()) *> IO.never[Unit]).start
          _ <- started.get
          held <- store.memory
          _ <- reader.cancel
          after <- store.memory
          _ <- store.write(Vector(input -> Some(out)), 12L)
          readBack <- store.read(Vector(input))
        yield
          assertEquals(before.get("num-snapshots"), Some(0L))
          assertEquals(held.get("num-snapshots"), Some(1L))
          assertEquals(after.get("num-snapshots"), Some(0L))
          assertEquals(readBack, Map(input -> out))
      }
    }
  }

  private val genActions: Gen[List[Action]] = Gen.choose(1, 40).flatMap(Gen.listOfN(_, genAction))

  property("memory store: apply/rollback/flush matches the model; stake, total, count are exact") {
    forAll(genActions) { actions =>
      orFail(MemoryUtxoStore[IO].flatMap(run(_, actions)))
    }
  }

  property("rocksdb store: apply/rollback/flush matches the model; stake, total, count are exact") {
    forAll(genActions) { actions =>
      orFail(rocks(run(_, actions)))
    }
  }

  property("rocksdb and memory interpreters agree on every read after the same sequence") {
    forAll(genActions) { actions =>
      orFail(
        MemoryUtxoStore[IO].flatMap(mem =>
          rocks { disk =>
            for
              a <- run(mem, actions)
              b <- run(disk, actions)
              all <- (mem.all, disk.all).tupled
              stamps <- (mem.flushed, disk.flushed).tupled
              sizes <- (mem.size, disk.size).tupled
              probe <- disk.read(all._1.keys)
            yield a && b && all._1 == all._2 && stamps._1 == stamps._2 && sizes._1 == sizes._2 &&
              probe == all._1
          }
        )
      )
    }
  }

  private def checkpointRoundTrip(
      open: (UtxoStore[IO] => IO[Boolean]) => IO[Boolean],
      actions: List[Action],
      more: List[Action]
  )(using cats.mtl.Raise[cats.effect.IO, xyz.kd5ujc.scarab.ledger.StorageError]) =
    open { store =>
      val dir = tempDir()
      val cp = Path.fromNioPath(dir) / "cp"
      (for
        // Drive the first part, snapshot the store, keep going, then restore the snapshot.
        _ <- run(store, actions)
        before <- store.all
        stampBefore <- StorageTestKit.ok(store.flushed)
        _ <- StorageTestKit.ok(store.checkpoint(cp))
        _ <- run(store, more)
        restored <- store.restore(cp)
        after <- store.all
        stampAfter <- StorageTestKit.ok(store.flushed)
        missing <- store.restore(Path.fromNioPath(dir) / "nothing")
      yield restored && !missing && after == before && stampAfter == stampBefore)
        .guarantee(IO.blocking(RocksUtxoStore.deleteTree(dir)))
    }

  property("checkpoint then restore returns the store to the checkpointed set (memory)") {
    forAll(genActions, genActions) { (actions, more) =>
      orFail(checkpointRoundTrip(f => MemoryUtxoStore[IO].flatMap(f), actions, more))
    }
  }

  property("checkpoint then restore returns the store to the checkpointed set (rocksdb)") {
    forAll(genActions, genActions) { (actions, more) =>
      orFail(checkpointRoundTrip(f => rocks(f), actions, more))
    }
  }

  test("a memory checkpoint replaces its file atomically and leaves no temporary file") {
    val dir = tempDir()
    val cp = Path.fromNioPath(dir) / "cp"
    val file = dir.resolve("cp").resolve("utxo.bin")
    val tmp = dir.resolve("cp").resolve("utxo.bin.tmp")
    val a = TxIn(Bytes.wrap(be(1, 32)), 0)
    val b = TxIn(Bytes.wrap(be(2, 32)), 0)
    try
      orFail {
        MemoryUtxoStore[IO].flatMap { store =>
          for
            _ <- store.write(Vector(a -> Some(output(0, 1, 1, 10))), 1)
            _ <- StorageTestKit.ok(store.checkpoint(cp))
            first <- IO.blocking(java.nio.file.Files.readAllBytes(file))
            // a crash during an earlier attempt left a torn temporary file: it is no checkpoint,
            // restore ignores it and the next checkpoint overwrites it
            _ <- IO.blocking(java.nio.file.Files.write(tmp, Array[Byte](1, 2, 3)))
            _ <- store.write(Vector(b -> Some(output(0, 2, 1, 20))), 2)
            keptOld <- store.restore(cp)
            afterRestore <- store.all
            _ <- store.write(Vector(b -> Some(output(0, 2, 1, 20))), 2)
            _ <- StorageTestKit.ok(store.checkpoint(cp))
            second <- IO.blocking(java.nio.file.Files.readAllBytes(file))
            _ <- store.reset
            restored <- store.restore(cp)
            all <- store.all
          yield
            assert(keptOld)
            assertEquals(afterRestore.keySet, Set(a))
            assert(!java.nio.file.Files.exists(tmp))
            assert(!java.util.Arrays.equals(first, second))
            assert(restored)
            assertEquals(all.keySet, Set(a, b))
        }
      }
    finally RocksUtxoStore.deleteTree(dir)
  }

  test("a restored rocksdb checkpoint is independent of the live database") {
    val dir = tempDir()
    orFail {
      rocks { store =>
        val cp = Path.fromNioPath(dir) / "cp"
        val a = TxIn(Bytes.wrap(be(1, 32)), 0)
        val b = TxIn(Bytes.wrap(be(2, 32)), 0)
        for
          _ <- store.write(Vector(a -> Some(output(0, 1, 1, 10))), 1)
          _ <- StorageTestKit.ok(store.checkpoint(cp))
          _ <- store.write(Vector(b -> Some(output(0, 2, 1, 20)), a -> None), 2)
          _ <- store.restore(cp)
          restored <- store.all
          _ <- store.write(Vector(b -> Some(output(0, 3, 1, 30))), 5)
          again <- store.restore(cp)
          second <- store.all
        yield
          assertEquals(restored.keySet, Set(a))
          assert(again)
          assertEquals(second.keySet, Set(a))
      }.guarantee(IO.blocking(RocksUtxoStore.deleteTree(dir)))
    }
  }

  test("the key is the Haskell node's: the 32-byte txid then the index as a big-endian u16") {
    val id = Bytes.wrap(be(7, 32))
    val key = UtxoStore.Coding.encodeKey(TxIn(id, 0x1234)).get
    assertEquals(key.length, 34)
    assertEquals(key.toSeq.drop(32), Seq(0x12.toByte, 0x34.toByte))
    assertEquals(UtxoStore.Coding.decodeKey(key), TxIn(id, 0x1234))
    assertEquals(UtxoStore.Coding.encodeKey(TxIn(id, 0x10000)), None)
  }
