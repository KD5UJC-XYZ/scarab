// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.lang.management.ManagementFactory
import xyz.kd5ujc.scarab.codec.Bytes

/** Time and allocation of [[Utxo.changesSince]], the walk of the diff that the store makes each
  * time it follows the immutable tip (issue 650). Not part of `check`:
  *
  * `java -cp <ledger test classpath> xyz.kd5ujc.scarab.ledger.UtxoChangesBench [changes] [samples]`
  *
  * The diff holds `changes` entries stamped 0 to 999 and the walk asks for those after 499, so half
  * are taken. The result printed (the number taken and the newest stamp) is the same for two builds
  * that walk alike.
  */
object UtxoChangesBench:
  private val bean = ManagementFactory.getThreadMXBean match
    case b: com.sun.management.ThreadMXBean => b
    case _                                  => sys.error("allocation counter unavailable")

  def main(args: Array[String]): Unit =
    val numbers = args.toList.flatMap(_.toIntOption)
    val (changes, samples) = (numbers.headOption.getOrElse(400000), numbers.lift(1).getOrElse(30))
    val diff = (0 until changes).map { i =>
      val id = Bytes.wrap(Array.tabulate[Byte](32)(k => (i >>> (8 * (k % 4)) ^ k).toByte))
      TxIn(id, BigInt(i % 3)) -> Utxo.Change(None, (i % 1000).toLong)
    }.toMap
    val utxo = Utxo.empty.copy(diff = diff)
    def walk(): (Int, Long) =
      val (taken, newest) = utxo.changesSince(499L)
      (taken.size, newest)
    (1 to 20).foreach(_ => walk())
    val rows = (1 to samples).map { _ =>
      val allocation = bean.getCurrentThreadAllocatedBytes
      val start = System.nanoTime()
      val result = walk()
      val elapsed = System.nanoTime() - start
      (elapsed, bean.getCurrentThreadAllocatedBytes - allocation, result)
    }
    val ns = rows.map(_._1).sorted
    val bytes = rows.map(_._2).sorted
    println(
      s"METRIC changesSince median_ns=${ns(ns.size / 2)} min_ns=${ns.head} max_ns=${ns.last} " +
        s"allocated=${bytes(bytes.size / 2)} result=${rows.map(_._3).distinct.mkString(",")}"
    )
