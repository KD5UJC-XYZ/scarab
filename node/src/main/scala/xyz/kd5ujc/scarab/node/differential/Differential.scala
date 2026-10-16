// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.differential

import cats.{Eq, Show}
import cats.derived.*
import java.nio.file.Path
import xyz.kd5ujc.scarab.consensus.{BlockRef, NodeState}
import xyz.kd5ujc.scarab.protocol.Era

/** When the harness converts the old state and compares it with the new side's whole (design D7: "a
  * full comparison at every epoch boundary and every 2,160 blocks"). Between two samples the new
  * side is not compared, and a side that is not ported is not even converted: a conversion of a
  * Preview state costs about a second (R7), an epoch has some twenty thousand blocks (R1).
  */
enum Sampling derives Eq:
  /** After every block: the eras fixture, whose states are small. */
  case EveryBlock

  /** After the first block the harness sees, the first block of every epoch and every era, and
    * every block whose number is a multiple of `blocks`.
    */
  case Boundaries(blocks: Long)

  /** Whether the tick of the block `at` is to be compared at a sampling point (the tick stage also
    * compares wherever the tick does something).
    */
  def dueTick(first: Boolean, at: BlockRef): Boolean = this match
    case EveryBlock    => true
    case Boundaries(n) => first || at.blockNo % n == 0

  /** Whether the state `after` the block `at`, which came from `before`, is to be compared. */
  def due(before: NodeState, after: NodeState, at: BlockRef, first: Boolean): Boolean = this match
    case EveryBlock => true
    case Boundaries(n) =>
      first ||
      after.ledger.epoch != before.ledger.epoch ||
      after.boot.era != before.boot.era ||
      at.blockNo % n == 0

object Sampling:
  /** D7's interval: the blocks of 2,160 (Preview's security parameter, `k`). */
  val Default: Sampling = Boundaries(2160)

/** What the harness does with a disagreement (design D7): stop the run (the devnet and the
  * fixtures), or adopt the old side's state, seed the new side from it and carry on (a long replay
  * finds every divergence in one run).
  */
enum Policy derives Eq:
  case Halt, Reseed

/** The time a sample cost: the conversion of the old state to the mirror, and the comparison with
  * the new side's.
  */
final case class Cost(toMirrorNanos: Long, diffNanos: Long)

/** What a compared tick cost, in nanoseconds: the old side's tick, the seed of the new side's
  * pre-state (the conversion of the old state), the new side's tick and the comparison with the old
  * side's ticked state (its conversion included).
  */
final case class TickCost(oldNanos: Long, seedNanos: Long, newNanos: Long, diffNanos: Long)

/** The time of the blocks one side applied, in nanoseconds: how many, their total and largest, and
  * a histogram by power of two (bucket `i` holds the blocks that took from 2^i to 2^(i+1) ns), for
  * the median and the 99th percentile of a run of any length in constant memory.
  */
final case class Timing(
    count: Long = 0,
    totalNanos: Long = 0,
    maxNanos: Long = 0,
    buckets: Vector[Long] = Vector.fill(40)(0L)
):
  def add(nanos: Long): Timing =
    val bucket =
      math.min(buckets.size - 1, 63 - java.lang.Long.numberOfLeadingZeros(math.max(1L, nanos)))
    Timing(
      count + 1,
      totalNanos + nanos,
      math.max(maxNanos, nanos),
      buckets.updated(bucket, buckets(bucket) + 1)
    )

  def meanNanos: Long = if count == 0 then 0L else totalNanos / count

  def merge(o: Timing): Timing =
    Timing(
      count + o.count,
      totalNanos + o.totalNanos,
      math.max(maxNanos, o.maxNanos),
      buckets.zip(o.buckets).map(_ + _)
    )

  /** The upper bound of the bucket the quantile `q` falls in. */
  def quantileNanos(q: Double): Long =
    val target = math.ceil(q * count).toLong
    val reached = buckets.scanLeft(0L)(_ + _).drop(1).indexWhere(_ >= target)
    if count == 0 || reached < 0 then 0L else 1L << (reached + 1)

/** What the harness reports as it goes. */
enum Event:
  /** The tick of `block` was compared (`Stage.Tick`): it crossed `epochsCrossed` epoch boundaries.
    */
  case TickCompared(block: BlockRef, epochsCrossed: Int, cost: TickCost)

  /** A sample was taken after `block`: the conversion and comparison cost, and whether the new side
    * was seeded from the old one (its rules are not ported).
    */
  case Sampled(block: BlockRef, epoch: BigInt, era: Era, cost: Cost, seeded: Boolean)

  /** The sides disagree (or cannot be compared); `bundle` is where the repro was kept. */
  case Diverged(divergence: Divergence, bundle: Option[Path])

/** The harness' account of a run: for the coverage report (what is left to port) and the cost of
  * the conversion.
  */
final case class Stats(
    blocks: Long = 0,
    samples: Vector[Cost] = Vector.empty,
    notPorted: Map[(Era, Stage), Long] = Map.empty,
    failures: Long = 0,
    ticks: Vector[TickCost] = Vector.empty,
    oldBlocks: Timing = Timing(),
    newBlocks: Timing = Timing()
):
  def sampled: Long = samples.size.toLong
  def ticked(c: TickCost): Stats = copy(ticks = ticks :+ c)

  /** A block both sides applied: what each took (the old side's whole block, the new side's block
    * and its read set).
    */
  def blockTimed(oldNanos: Long, newNanos: Long): Stats =
    copy(oldBlocks = oldBlocks.add(oldNanos), newBlocks = newBlocks.add(newNanos))

  def block: Stats = copy(blocks = blocks + 1)
  def sample(c: Cost): Stats = copy(samples = samples :+ c)
  def notPortedIn(era: Era, stage: Stage): Stats =
    copy(notPorted = notPorted.updatedWith((era, stage))(n => Some(n.getOrElse(0L) + 1)))
  def failed: Stats = copy(failures = failures + 1)

object Stats:
  /** The sorted values' quantile `q` (0 to 1), nearest rank; zero for none. */
  def quantile(sorted: Vector[Long], q: Double): Long =
    if sorted.isEmpty then 0L else sorted(math.min(sorted.size - 1, (q * sorted.size).toInt))

  /** One line a person reads: blocks, samples, what each conversion cost, what is not ported. */
  given Show[Stats] = Show.show { s =>
    def ms(xs: Vector[Long]): String =
      val sorted = xs.sorted
      f"median ${quantile(sorted, 0.5) / 1e6}%.1f, p99 ${quantile(sorted, 0.99) / 1e6}%.1f, max ${sorted.lastOption
          .getOrElse(0L) / 1e6}%.1f"
    s"differential: ${s.blocks} blocks, ${s.sampled} sampled, ${s.ticks.size} ticks compared " +
      s"(old ms ${ms(s.ticks.map(_.oldNanos))}; new ms ${ms(s.ticks.map(_.newNanos))}), " +
      s"${s.failures} failures; " +
      f"per block (${s.newBlocks.count} compared) old ms mean ${s.oldBlocks.meanNanos / 1e6}%.3f, p50 <${s.oldBlocks
          .quantileNanos(0.5) / 1e6}%.3f, p99 <${s.oldBlocks.quantileNanos(0.99) / 1e6}%.3f, max ${s.oldBlocks.maxNanos / 1e6}%.1f; " +
      f"new ms mean ${s.newBlocks.meanNanos / 1e6}%.3f, p50 <${s.newBlocks.quantileNanos(0.5) / 1e6}%.3f, p99 <${s.newBlocks
          .quantileNanos(0.99) / 1e6}%.3f, max ${s.newBlocks.maxNanos / 1e6}%.1f; " +
      s"toMirror ms ${ms(s.samples.map(_.toMirrorNanos))}; diff ms ${ms(s.samples.map(_.diffNanos))}; " +
      s"not ported ${s.notPorted.toList.sortBy(_._1._1.ordinal).map { case ((e, st), n) => s"$e/$st=$n" }.mkString(", ")}"
  }
