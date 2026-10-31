// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import java.io.FileInputStream
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import scala.annotation.tailrec
import scala.util.Using
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.mempack.TxOutMemPack
import xyz.kd5ujc.scarab.ledger.state.Conway

/** Time and allocation of reading a real Haskell `tables` file, the UTxO set of a snapshot import:
  * the [[SnapshotTables]] decoder with its CRC over the first megabytes of the file, then
  * [[TxOutMemPack]]'s decode of every output read and its encode of each again. Not part of
  * `check`; run it in a forked JVM with a few gigabytes of heap:
  *
  * `java -Xmx6g -cp <storage test classpath> xyz.kd5ujc.scarab.storage.haskell.state.SnapshotTablesBench <tables file> [megabytes] [warm-ups] [samples]`
  *
  * The totals it prints (entries, bytes, outputs that do not decode, bytes encoded) are the same
  * for two builds that read the file alike.
  */
object SnapshotTablesBench:
  private val bean = ManagementFactory.getThreadMXBean match
    case b: com.sun.management.ThreadMXBean => b
    case _                                  => sys.error("allocation counter unavailable")

  private val Chunk = 65536

  def main(args: Array[String]): Unit =
    val usage = "usage: SnapshotTablesBench <tables file> [megabytes] [warm-ups] [samples]"
    val (file, megabytes, warmups, samples) = args.toList match
      case path :: rest =>
        val numbers = rest.flatMap(_.toIntOption)
        (
          path,
          numbers.headOption.getOrElse(256),
          numbers.lift(1).getOrElse(3),
          numbers.lift(2).getOrElse(7)
        )
      case Nil => sys.error(usage)
    val raw = Using.resource(new FileInputStream(file))(_.readNBytes(megabytes << 20))
    val sha = MessageDigest.getInstance("SHA-256").digest(raw).map("%02x".format(_)).mkString
    println(s"BENCH input: the first ${raw.length} bytes, sha256 $sha")

    // the entries of the prefix, folded as they are read
    def tables[A](z: A)(f: (A, SnapshotTables.Entry) => A): A =
      @tailrec def go(decoder: SnapshotTables.Decoder, at: Int, acc: A): A =
        if at >= raw.length then acc
        else
          decoder.feed(Bytes.wrap(raw, at, Chunk)) match
            case Left(e)           => sys.error(s"tables: $e")
            case Right((next, es)) => go(next, at + Chunk, es.foldLeft(acc)(f))
      go(SnapshotTables.decoder, 0, z)

    val values = tables(Vector.empty[Bytes])((vs, e) => vs :+ e.value)
    val outputs = values.flatMap(v => TxOutMemPack.decode[Conway](v).toOption)
    println(
      s"BENCH totals: entries=${values.size} bytes=${values.map(_.size.toLong).sum} " +
        s"undecoded=${values.size - outputs.size} " +
        s"encoded=${outputs.flatMap(o => TxOutMemPack.encode[Conway](o).toOption).map(_.size.toLong).sum}"
    )

    val workloads = Vector[(String, () => Long)](
      "tables" -> (() => tables(0L)((n, e) => n + e.key.size + e.value.size)),
      "decode" -> (() =>
        values.foldLeft(0L)((n, v) => n + TxOutMemPack.decode[Conway](v).fold(_ => 0L, _ => 1L))
      ),
      "encode" -> (() =>
        outputs.foldLeft(0L)((n, o) =>
          n + TxOutMemPack.encode[Conway](o).fold(_ => 0L, _.size.toLong)
        )
      )
    )
    workloads.foreach { (name, work) =>
      (1 to warmups).foreach(_ => work())
      val rows = (1 to samples).map { sample =>
        System.gc()
        val allocation = bean.getCurrentThreadAllocatedBytes
        val start = System.nanoTime()
        val result = work()
        val elapsed = System.nanoTime() - start
        val bytes = bean.getCurrentThreadAllocatedBytes - allocation
        println(s"SAMPLE $name sample=$sample ns=$elapsed allocated=$bytes result=$result")
        (elapsed, bytes)
      }
      val ns = rows.map(_._1).sorted
      val bytes = rows.map(_._2).sorted
      println(
        s"METRIC $name median_ns=${ns(ns.size / 2)} min_ns=${ns.head} max_ns=${ns.last} " +
          s"allocated=${bytes(bytes.size / 2)}"
      )
    }
