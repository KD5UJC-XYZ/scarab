// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.lang.management.ManagementFactory
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.state.{FutureGenDeleg, Genesis, KeyHash, SlotNo}
import xyz.kd5ujc.scarab.models.runtime.Codec as ModelCodec
import xyz.kd5ujc.scarab.storage.haskell.state.HaskellStateCodecs

/** Development tool for #205: the derived codecs, encode and decode, timed and with the thread's
  * allocation counted on the old (list-erased) and the new (typed chain) derivation.
  *
  * Two derivations are measured, because #205 changes both:
  *   - `storage.Codec` (`Codec.auto`) on a nested synthetic record — the general codec;
  *   - `models.runtime.Codec` built by `Record.auto` on `FutureGenDeleg`, a real snapshot record
  *     (`[slot, genesis key hash]`) — the derivation the Haskell state files go through.
  *
  * `storage/Test/runMain xyz.kd5ujc.scarab.storage.BenchCodecMain [iterations]`
  *
  * The digests cover the complete encoded bytes and the decoded value, so equal digests mean equal
  * outputs and the comparison is of the derivation, not of the answer.
  */
object BenchCodecMain:
  final case class Inner(a: Long, b: Bytes, c: Option[String])
  final case class Row(i: Long, s: String, in: Inner, xs: Vector[Int], more: Vector[Inner])

  given Codec[Inner] = Codec.auto
  given Codec[Row] = Codec.auto

  import HaskellStateCodecs.given

  private val genesis =
    KeyHash
      .fromBytes[Genesis](Bytes.fromArray(Array.fill[Byte](28)(5)))
      .fold(e => sys.error(e.toString), identity)
  private val futureGenDeleg = FutureGenDeleg(SlotNo.fromUnsigned(1234L), genesis)
  private val recordCodec: ModelCodec[FutureGenDeleg] = summon[ModelCodec[FutureGenDeleg]]

  private val bean = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
  private def allocated: Long = bean.getCurrentThreadAllocatedBytes

  private val row = Row(
    7L,
    "x" * 32,
    Inner(42L, Bytes.fromArray(Array.fill[Byte](28)(9)), Some("y")),
    Vector.tabulate(16)(identity),
    Vector.tabulate(8)(i => Inner(i.toLong, Bytes.fromArray(Array.fill[Byte](28)(i.toByte)), None))
  )

  private def digest(bytes: Array[Byte]): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.update(bytes)
    md.digest().take(8).map(b => f"$b%02x").mkString

  private def digest(bytes: Bytes): String = digest(bytes.toArray)

  private def sha256(text: String): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.update(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    md.digest().take(8).map(b => f"$b%02x").mkString

  private def measure(name: String, iterations: Int, warmup: Int = 3, reps: Int = 5)(
      body: Int => Long
  ): Unit =
    var i = 0
    while i < warmup do
      body(iterations)
      i += 1
    val samples = (1 to reps).map { _ =>
      val a0 = allocated
      val t0 = System.nanoTime
      val checksum = body(iterations)
      val ns = System.nanoTime - t0
      val a1 = allocated
      if checksum == Long.MinValue then println("impossible checksum")
      (ns / 1e6, a1 - a0)
    }
    val ms = samples.map(_._1).sorted
    val bytes = samples.map(_._2).sorted
    println(
      f"METRIC $name%-22s median=${ms(ms.size / 2)}%10.3f ms min=${ms.head}%10.3f max=${ms.last}%10.3f alloc=${bytes(bytes.size / 2)}%14d B  (${iterations} iterations)"
    )

  def main(args: Array[String]): Unit =
    val iterations = args.headOption.map(_.toInt).getOrElse(20000)
    println(s"bench=codec iterations=$iterations java=${System.getProperty("java.version")}")
    val encoded = Codec.encode(row)
    measure("codec.encode", iterations) { n =>
      var h = 0
      var i = 0
      while i < n do
        h ^= java.util.Arrays.hashCode(Codec.encode(row))
        i += 1
      h.toLong
    }
    measure("codec.decode", iterations) { n =>
      var h = 0
      var i = 0
      while i < n do
        h ^= Codec.decode[Row](encoded).fold(e => sys.error(e.toString), _.hashCode())
        i += 1
      h.toLong
    }
    val rowDecoded = Codec.decode[Row](encoded).fold(e => sys.error(e.toString), identity)
    println(
      s"DIGEST codec bytes=${digest(encoded)} length=${encoded.length} value=${sha256(rowDecoded.toString)}"
    )

    val encodedRecord = recordCodec.encodeUnchecked(futureGenDeleg)
    measure("codec.record.encode", iterations) { n =>
      var h = 0
      var i = 0
      while i < n do
        h ^= java.util.Arrays.hashCode(recordCodec.encodeUnchecked(futureGenDeleg).toArray)
        i += 1
      h.toLong
    }
    measure("codec.record.decode", iterations) { n =>
      var h = 0
      var i = 0
      while i < n do
        h ^= recordCodec.decodeBytes(encodedRecord).fold(e => sys.error(e.toString), _.hashCode())
        i += 1
      h.toLong
    }
    val recordDecoded =
      recordCodec.decodeBytes(encodedRecord).fold(e => sys.error(e.toString), identity)
    println(
      s"DIGEST codec.record bytes=${digest(encodedRecord)} length=${encodedRecord.size} value=${sha256(recordDecoded.toString)}"
    )
