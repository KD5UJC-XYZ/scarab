// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.io.file.Path
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{StorageError, TxIn, TxOut}

/** Time of `GetUTxOByAddress` on a real RocksDB UTxO store: the scan that decodes every output
  * (`select`, what the query used to do) against the one that decodes only candidates
  * (`selectAddresses`). Not part of `check`; run it on a copy of a store (it opens the directory in
  * place, which rewrites its manifest):
  *
  * `storage/Test/runMain xyz.kd5ujc.scarab.storage.UtxoScanBench <store dir> [rounds]`
  *
  * A store of the mainnet scale can be synthesised first (an empty directory, the number of
  * outputs; about 100000 distinct addresses):
  *
  * `storage/Test/runMain xyz.kd5ujc.scarab.storage.UtxoScanBench generate <store dir> <count>`
  *
  * It takes the addresses of every 100000th output as the wanted set, so the two scans can be
  * compared entry for entry.
  */
object UtxoScanBench:
  def main(args: Array[String]): Unit =
    val usage = "usage: UtxoScanBench <store dir> [rounds] | generate <store dir> <count>"
    val run: Handle[IO, StorageError] ?=> IO[Unit] = args.toList match
      case "generate" :: dir :: count :: Nil =>
        generate(Path(dir), count.toIntOption.getOrElse(sys.error(usage)))
      case dir :: rest =>
        program(Path(dir), rest.headOption.flatMap(_.toIntOption).getOrElse(3))
      case Nil => sys.error(usage)
    Handle
      .allow[StorageError](run)
      .rescue(e => IO.raiseError(new RuntimeException(e.message)))
      .unsafeRunSync()

  /** `count` outputs of a plain address and a lovelace amount, written in batches of 100000. */
  private def generate(dir: Path, count: Int)(using Handle[IO, StorageError]): IO[Unit] =
    def be(n: Long, size: Int): Array[Byte] =
      Array.tabulate(size)(i => ((n >>> (8 * (size - 1 - i))) & 0xff).toByte)
    def entry(n: Int): (TxIn, Option[TxOut]) =
      val address = 0x61.toByte +: be((n % 100003).toLong, 28)
      val cbor =
        Array[Byte](0x82.toByte, 0x58.toByte, address.length.toByte) ++ address ++
          Array[Byte](0x1a.toByte) ++ be(1000L + n, 4)
      val out = TxOut.fromBytes(Bytes.wrap(cbor)).fold(e => sys.error(e.toString), identity)
      TxIn(Bytes.wrap(be(n.toLong, 32)), BigInt(n % 3)) -> Some(out)
    RocksUtxoStore.resource[IO](dir).use { store =>
      (0 until count by 100000).toList.traverse_ { from =>
        store.write((from until math.min(count, from + 100000)).map(entry), from.toLong)
      } *> store.size.flatMap(n => IO.println(s"BENCH generated: $n entries"))
    }

  private def timed[A](what: String)(fa: IO[A]): IO[A] =
    IO.monotonic.flatMap { t0 =>
      fa.flatTap(_ => IO.monotonic.map(t1 => println(s"BENCH $what: ${(t1 - t0).toMillis} ms")))
    }

  private def program(dir: Path, rounds: Int)(using Handle[IO, StorageError]): IO[Unit] =
    RocksUtxoStore.resource[IO](dir).use { store =>
      for
        n <- timed("count")(store.size)
        _ <- IO.println(s"BENCH entries: $n")
        wanted <- sampled(store)
        _ <- IO.println(s"BENCH wanted addresses: ${wanted.size}")
        _ <- (1 to rounds).toList.foldLeft(IO.unit) { (acc, r) =>
          acc *> compare(store, wanted, r)
        }
      yield ()
    }

  private def sampled(store: UtxoStore[IO])(using
      cats.mtl.Raise[cats.effect.IO, xyz.kd5ujc.scarab.ledger.StorageError]
  ): IO[Set[Bytes]] =
    val picked = Set.newBuilder[Bytes]
    var i = 0L
    store
      .scan { (_, out) =>
        if i % 100000 == 0 then picked += out.address.raw
        i += 1
      }
      .map(_ => picked.result())

  private def compare(store: UtxoStore[IO], wanted: Set[Bytes], round: Int)(using
      cats.mtl.Raise[cats.effect.IO, xyz.kd5ujc.scarab.ledger.StorageError]
  ): IO[Unit] =
    for
      old <- timed(s"round $round decode every output (select)")(
        store.select(out => wanted(out.address.raw))
      )
      now <- timed(s"round $round decode candidates only (selectAddresses)")(
        store.selectAddresses(wanted)
      )
      _ <- IO.println(s"BENCH round $round: ${old.size} entries, equal: ${old == now}")
    yield ()
