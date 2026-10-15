// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.fuzz

import java.lang.management.ManagementFactory
import xyz.kd5ujc.scarab.codec.Bytes
import scala.annotation.nowarn
import org.scalacheck.{Gen, Prop, Shrink, Test}
import org.scalacheck.rng.Seed
import org.scalacheck.util.Pretty

/** Run configuration, read from system properties (`build.sbt` sets them from the `fuzz` command).
  */
object FuzzConfig:
  private def long(name: String, default: Long): Long =
    sys.props.get(name).flatMap(_.toLongOption).getOrElse(default)
  val cases: Int = long("fuzz.cases", 60).toInt
  val firstSeed: Long = long("fuzz.seed", 1)
  val seeds: Int = long("fuzz.seeds", 1).toInt

  /** One decode may take this long before the property fails (typical cases take microseconds). */
  val timeLimitMs: Long = 2000

  /** A decode stuck for this long kills the JVM after printing its input. */
  val hangLimitMs: Long = 30000

/** A violated property: the decoder threw, ran too long, or allocated too much. */
final class Violation(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)

/** One fuzz input. Its `toString` is the whole input in hex so a failure can be pasted into a test.
  */
final class Input(val bytes: Array[Byte]):
  def size: Int = bytes.length
  def hex: String = Bytes.fromArray(bytes).hex
  override def toString: String = s"Input(${bytes.length} bytes, hex $hex)"

/** The guards around one decoder call. */
object Probe:
  private val threads = ManagementFactory.getThreadMXBean match
    case t: com.sun.management.ThreadMXBean => t
    case other => throw new IllegalStateException(s"no allocation counters in $other")

  @volatile private var armedAt = 0L
  @volatile private var armedFor: Option[(String, Input)] = None

  private val watchdog = new Thread("fuzz-watchdog"):
    override def run(): Unit =
      while true do
        Thread.sleep(500)
        armedFor.foreach { case (name, input) =>
          val ms = (System.nanoTime() - armedAt) / 1000000
          if ms > FuzzConfig.hangLimitMs then
            System.err.println(s"HANG in $name after $ms ms\n$input")
            Runtime.getRuntime.halt(3)
        }
  watchdog.setDaemon(true)
  watchdog.start()

  /** Allocation allowed for an input: a fixed share (class loading, caches, a block's worth of
    * nodes) plus a multiple of the input size, so that a declared length cannot buy memory. The
    * multiple is 1000 bytes since the decoders accept any nesting (B17): an input of `81` bytes is
    * one node tree level a byte (a node, its value, window, vector and frame, about 200 bytes) in
    * each of the decode, encode, decode and encode a target runs, where 2048 levels once capped it.
    */
  def allocLimit(inputSize: Int, perByte: Long = 1000): Long =
    24L * 1024 * 1024 + perByte * inputSize

  /** Run `body` on `input`: any exception, a run over the time limit or allocation over `limit` is
    * a `Violation`.
    */
  def run(name: String, input: Input, limit: Long = -1, perByte: Long = 1000)(body: => Unit): Unit =
    val bound = if limit >= 0 then limit else allocLimit(input.size, perByte)
    val id = Thread.currentThread().threadId()
    val before = threads.getThreadAllocatedBytes(id)
    armedAt = System.nanoTime()
    armedFor = Some(name -> input)
    val outcome =
      try
        body
        None
      catch case t: Throwable => Some(t)
      finally armedFor = None
    val ms = (System.nanoTime() - armedAt) / 1000000
    val allocated = threads.getThreadAllocatedBytes(id) - before
    outcome.foreach {
      case v: Violation => throw v
      case t =>
        val where = t.getStackTrace.take(8).mkString("\n  at ", "\n  at ", "")
        throw new Violation(s"$name threw $t$where", t)
    }
    if ms > FuzzConfig.timeLimitMs then throw new Violation(s"$name took $ms ms")
    if allocated > bound then
      throw new Violation(
        s"$name allocated $allocated bytes (limit $bound) for ${input.size} bytes"
      )

/** ScalaCheck driver: deterministic seeds, shrinking by chunk removal, failures print the seed. */
object Runner:
  /** Smaller inputs that are likely to fail the same way: chunks removed (halves, quarters, ...
    * single bytes), then bytes zeroed.
    */
  @nowarn("cat=deprecation")
  given shrinkInput: Shrink[Input] = Shrink { in =>
    val a = in.bytes
    val chunks =
      Iterator.iterate(a.length / 2)(_ / 2).takeWhile(_ >= 1).distinct.flatMap { size =>
        (0 until a.length by size).iterator.map(at => new Input(a.patch(at, Nil, size)))
      }
    val zeros = (0 until math.min(a.length, 64)).iterator
      .filter(i => a(i) != 0)
      .map(i => new Input(a.updated(i, 0.toByte)))
    (chunks ++ zeros).take(400).toStream
  }

  /** Run `check` over `cases` generated inputs for each seed; the first failure fails the target.
    */
  def run(name: String, gen: Gen[Input])(check: Input => Unit): Unit =
    val prop = Prop.forAll(gen) { (in: Input) =>
      check(in)
      true
    }
    (0 until FuzzConfig.seeds).foreach { i =>
      val seed = FuzzConfig.firstSeed + i
      val params = Test.Parameters.default
        .withMinSuccessfulTests(FuzzConfig.cases)
        .withWorkers(1)
        .withInitialSeed(Seed(seed * 1000003L + name.hashCode))
        .withMaxSize(100)
      val result = Test.check(params, prop)
      if !result.passed then
        throw new AssertionError(
          s"fuzz target $name failed with seed $seed (reproduce: fuzz ${FuzzConfig.cases} $seed 1 $name)\n" +
            Pretty.pretty(result, Pretty.Params(0))
        )
    }
