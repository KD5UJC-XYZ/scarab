// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.mtl.Handle
import fs2.io.file.Path
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.StorageError

/** Time of `GetUTxOByAddress` on a real RocksDB UTxO store: the scan that decodes every output
  * (`select`, what the query used to do) against the one that decodes only candidates
  * (`selectAddresses`). Not part of `check`; run it on a copy of a store (it opens the directory in
  * place, which rewrites its manifest):
  *
  * `storage/Test/runMain xyz.kd5ujc.scarab.storage.UtxoScanBench <store dir> [rounds]`
  *
  * It takes the addresses of every 100000th output as the wanted set, so the two scans can be
  * compared entry for entry.
  */
object UtxoScanBench:
  def main(args: Array[String]): Unit =
    val dir = args.headOption.getOrElse(sys.error("usage: UtxoScanBench <store dir> [rounds]"))
    val rounds = args.lift(1).flatMap(_.toIntOption).getOrElse(3)
    Handle
      .allow[StorageError](program(Path(dir), rounds))
      .rescue(e => IO.raiseError(new RuntimeException(e.message)))
      .unsafeRunSync()

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

  private def sampled(store: UtxoStore[IO]): IO[Set[Bytes]] =
    val picked = Set.newBuilder[Bytes]
    var i = 0L
    store
      .scan { (_, out) =>
        if i % 100000 == 0 then picked += out.address.raw
        i += 1
      }
      .map(_ => picked.result())

  private def compare(store: UtxoStore[IO], wanted: Set[Bytes], round: Int): IO[Unit] =
    for
      old <- timed(s"round $round decode every output (select)")(
        store.select(out => wanted(out.address.raw))
      )
      now <- timed(s"round $round decode candidates only (selectAddresses)")(
        store.selectAddresses(wanted)
      )
      _ <- IO.println(s"BENCH round $round: ${old.size} entries, equal: ${old == now}")
    yield ()
