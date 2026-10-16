// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

/** The upper bounds of a histogram's buckets: finite, strictly increasing, and never empty (the
  * `+Inf` bucket is implicit). The constructors cannot build anything else.
  */
final class Buckets private (val bounds: Vector[Double]):
  /** The bucket a value falls in: the first bound at or above it, else the `+Inf` bucket. */
  def indexOf(value: Double): Int =
    val i = bounds.indexWhere(value <= _)
    if i < 0 then bounds.size else i

  override def equals(other: Any): Boolean = other match
    case b: Buckets => bounds == b.bounds
    case _          => false
  override def hashCode: Int = bounds.hashCode

object Buckets:
  /** The given bounds, sorted, without the non-finite ones and the repeated ones; one bound at
    * least, so `first` is separate.
    */
  def of(first: Double, rest: Double*): Buckets =
    val cleaned = (first +: rest.toVector).filter(_.isFinite).distinct.sorted
    new Buckets(if cleaned.isEmpty then Vector(0.0) else cleaned)

  /** `count` bounds from `start`, each `factor` times the one before (`factor` above 1). */
  def exponential(start: Double, factor: Double, count: Int): Buckets =
    val bounds = Vector.iterate(start, count.max(1))(_ * factor.max(1.000001))
    of(bounds.head, bounds.tail*)

/** What a metric is called, what it means, and which labels it carries. The three kinds are three
  * types, so `set` on a counter or `observe` on a gauge does not compile; the label type `L` is the
  * type of the value the labels are taken from.
  */
sealed trait MetricDef:
  def name: MetricName
  def help: String
  def labelNames: List[LabelName]

/** A value that only goes up (it restarts from zero with the process). */
final case class CounterDef[L](name: MetricName, help: String, labels: Labels[L]) extends MetricDef:
  def labelNames: List[LabelName] = labels.names

/** A value that can go up and down. */
final case class GaugeDef[L](name: MetricName, help: String, labels: Labels[L]) extends MetricDef:
  def labelNames: List[LabelName] = labels.names

/** Observations counted into buckets, with their sum and count. */
final case class HistogramDef[L](
    name: MetricName,
    help: String,
    labels: Labels[L],
    buckets: Buckets
) extends MetricDef:
  def labelNames: List[LabelName] = labels.names

object CounterDef:
  def apply(name: MetricName, help: String): CounterDef[Unit] =
    CounterDef(name, help, Labels.none)

object GaugeDef:
  def apply(name: MetricName, help: String): GaugeDef[Unit] = GaugeDef(name, help, Labels.none)
