// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import scala.util.Random
import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.DecodeResult

/** A message fed to a decoder as the multiplexer feeds it when the peer paces its SDUs, so that the
  * ingress queue runs dry after every one: each input is the prefix held so far, and the decoder is
  * resumed with the cursor the last input left.
  */
object Paced:
  /** The bytes every reader of the protocols refuses as a head: major type 0 with the reserved
    * additional information 28.
    */
  val Reserved: Byte = 0x1c

  /** `decoder` on the prefixes of `message` that end at `cuts` (increasing), resumed each time from
    * the last cursor until a verdict other than `NeedMore`: the verdict on each prefix.
    */
  def resumed[A](
      decoder: IncrementalDecoder[A],
      message: Bytes,
      cuts: Vector[Int]
  ): Vector[DecodeResult[A]] = resumedOn(decoder, cuts.map(message.take))

  /** As [[resumed]], on the inputs given. */
  def resumedOn[A](decoder: IncrementalDecoder[A], inputs: Vector[Bytes]): Vector[DecodeResult[A]] =
    inputs
      .foldLeft((decoder.initial, Vector.empty[DecodeResult[A]], false)) {
        case ((cursor, seen, true), _) => (cursor, seen, true)
        case ((cursor, seen, false), input) =>
          val (next, result) = decoder.resume(cursor, input)
          (next, seen :+ result, result != DecodeResult.NeedMore)
      }
      ._2

  /** `decoder` started afresh on each prefix of `message` that ends at `cuts`, until a verdict
    * other than `NeedMore`.
    */
  def afresh[A](
      decoder: IncrementalDecoder[A],
      message: Bytes,
      cuts: Vector[Int]
  ): Vector[DecodeResult[A]] =
    val all = cuts.map(at => decoder.decodePrefix(message.take(at)))
    all.indexWhere(_ != DecodeResult.NeedMore) match
      case -1 => all
      case at => all.take(at + 1)

  /** `head` bytes at once, then one byte at a time to the end of `message`. */
  def dribbled(message: Bytes, head: Int): Vector[Int] = (head to message.size).toVector

  /** The inputs of [[dribbled]], those after the first and shorter than `whole` with the bytes of
    * `[from, until)` overwritten by [[Reserved]]: the decoder read their heads on the first input,
    * and one that resumes reads none of them again while the item they are in arrives, so it
    * answers on each input as on the true prefix, while one that reads each input from its start
    * reads them and refuses. From `whole` on, where that item has arrived whole and its bytes are
    * read once more as one item, the inputs are true.
    */
  def poisoned(message: Bytes, head: Int, from: Int, until: Int, whole: Int): Vector[Bytes] =
    val poison = message.toArray
    (from until until).foreach(i => poison(i) = Reserved)
    val spoiled = Bytes.wrap(poison)
    dribbled(message, head).map(at =>
      if at == head || at >= whole then message.take(at) else spoiled.take(at)
    )

  /** The decoder that reads each prefix from its start, as the multiplexer's receive read every
    * message before it kept a decoder's cursor.
    */
  def restarting[A](decoder: IncrementalDecoder[A]): IncrementalDecoder[A] =
    new IncrementalDecoder[A]:
      type Cursor = Unit
      def initial: Cursor = ()
      def resume(cursor: Cursor, input: Bytes): (Cursor, DecodeResult[A]) =
        ((), decoder.decodePrefix(input))

  /** The bytes the calling thread allocates while `body` runs, from its own allocation counter: a
    * measure of the bytes a decoder copies that does not depend on the machine's load.
    */
  def allocated(body: => Unit): Long =
    val bean = java.lang.management.ManagementFactory.getThreadMXBean match
      case sun: com.sun.management.ThreadMXBean => sun
      case other => throw new AssertionError(s"no allocation counter in $other")
    val before = bean.getCurrentThreadAllocatedBytes
    body
    bean.getCurrentThreadAllocatedBytes - before

  /** How the allocation of `run` grows from size `n` to `4 * n`: about 4 where the work is linear,
    * about 16 where it is quadratic. Each size is run twice and the second run measured.
    */
  def growth(n: Int)(run: Int => Unit): Double =
    def measured(size: Int): Long =
      allocated(run(size))
      allocated(run(size))
    val small = measured(n)
    val large = measured(4 * n)
    large.toDouble / small.toDouble

  /** Up to `count` increasing cut points of a message of `size` bytes, the last at `size`. */
  def cuts(size: Int, count: Int, random: Random): Vector[Int] =
    (Vector.fill(count)(random.nextInt(size + 1)) :+ size).distinct.sorted

  /** `message` with `flips` of its bytes changed at random: a message the decoder may refuse
    * anywhere.
    */
  def flipped(message: Bytes, flips: Int, random: Random): Bytes =
    val bytes = message.toArray
    (0 until flips).foreach { _ =>
      val at = random.nextInt(bytes.length)
      bytes(at) = (bytes(at) ^ (1 << random.nextInt(8))).toByte
    }
    Bytes.wrap(bytes)

  /** The verdicts of `decoder` resumed on random cuts of `message` and of copies of it with bytes
    * flipped, each against the verdict of the decoder started afresh on the same prefix: the pairs
    * that differ.
    */
  def differences[A](
      decoder: IncrementalDecoder[A],
      messages: Vector[Bytes],
      seed: Long
  ): Vector[String] =
    val random = new Random(seed)
    messages.zipWithIndex.flatMap { (message, i) =>
      (0 until 8).flatMap { round =>
        val input = if round == 0 then message else flipped(message, 1 + round % 3, random)
        val at = cuts(input.size, 1 + random.nextInt(24), random)
        val found = resumed(decoder, input, at)
        val expected = afresh(decoder, input, at)
        Option.when(found != expected)(
          s"message $i round $round cuts ${at.mkString(",")}: resumed $found, afresh $expected"
        )
      }
    }
