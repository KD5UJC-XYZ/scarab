// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import xyz.kd5ujc.scarab.codec.Bytes

/** Time and allocation of [[HaskellLedgerFile.decode]] on a real Haskell `state` file: the read of
  * a Mithril or Haskell snapshot import, where the collections of the state (sets and maps of a
  * million entries on mainnet) are decoded by [[Wire.setOf]], [[Wire.mapOf]] and [[Wire.seqOf]].
  * Not part of `check`; run it in a forked JVM with a few gigabytes of heap:
  *
  * `java -Xmx6g -cp <storage test classpath> xyz.kd5ujc.scarab.storage.haskell.state.HaskellStateDecodeBench <state file> [warm-ups] [samples]`
  *
  * Each decode is checked by writing the state again and comparing the bytes with the file, outside
  * the timing; the SHA-256 of the file is printed so that two runs can be matched to their input.
  */
object HaskellStateDecodeBench:
  private val bean = ManagementFactory.getThreadMXBean match
    case b: com.sun.management.ThreadMXBean => b
    case _                                  => sys.error("allocation counter unavailable")

  def main(args: Array[String]): Unit =
    val usage = "usage: HaskellStateDecodeBench <state file> [warm-ups] [samples]"
    val (file, warmups, samples) = args.toList match
      case path :: rest =>
        (
          Path.of(path),
          rest.headOption.flatMap(_.toIntOption).getOrElse(5),
          rest.drop(1).headOption.flatMap(_.toIntOption).getOrElse(9)
        )
      case Nil => sys.error(usage)
    val raw = Files.readAllBytes(file)
    val data = Bytes.fromArray(raw)
    val sha = MessageDigest.getInstance("SHA-256").digest(raw).map("%02x".format(_)).mkString
    println(s"BENCH input: ${raw.length} bytes, sha256 $sha")
    def decoded(): HaskellLedgerFile =
      HaskellLedgerFile.decode(data).fold(e => sys.error(s"decode failed: ${e.render}"), identity)
    def verify(state: HaskellLedgerFile): Unit =
      HaskellLedgerFile.encode(state) match
        case Right(again) if again == data => ()
        case Right(_)  => sys.error("the state is not written again byte for byte")
        case Left(why) => sys.error(s"not writable: ${why.violation.render}")
    (1 to warmups).foreach(_ => verify(decoded()))
    val rows = (1 to samples).map { sample =>
      System.gc()
      val allocation = bean.getCurrentThreadAllocatedBytes
      val start = System.nanoTime()
      val state = decoded()
      val elapsed = System.nanoTime() - start
      val bytes = bean.getCurrentThreadAllocatedBytes - allocation
      verify(state)
      println(s"SAMPLE decode sample=$sample ns=$elapsed allocated=$bytes")
      (elapsed, bytes)
    }
    val ns = rows.map(_._1).sorted
    val bytes = rows.map(_._2).sorted
    println(
      s"METRIC decode median_ns=${ns(ns.size / 2)} min_ns=${ns.head} max_ns=${ns.last} " +
        s"allocated=${bytes(bytes.size / 2)}"
    )
