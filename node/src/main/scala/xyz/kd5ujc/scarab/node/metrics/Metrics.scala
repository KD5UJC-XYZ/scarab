// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

import cats.{Applicative, Monad}
import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import scala.collection.immutable.SortedMap

/** One metric as the registry holds it: its definition and a value for every label combination
  * seen, keyed by the rendered label values (in the order of [[labelNames]]).
  */
sealed trait Family:
  def name: MetricName
  def help: String
  def labelNames: List[LabelName]

object Family:
  final case class Counter(
      name: MetricName,
      help: String,
      labelNames: List[LabelName],
      series: Map[List[String], Double]
  ) extends Family

  final case class Gauge(
      name: MetricName,
      help: String,
      labelNames: List[LabelName],
      series: Map[List[String], Double]
  ) extends Family

  /** `counts(i)` is the number of observations in bucket `i` alone (the last one is `+Inf`), so
    * `counts.sum == count`.
    */
  final case class Observations(counts: Vector[Long], sum: Double, count: Long)

  final case class Histogram(
      name: MetricName,
      help: String,
      labelNames: List[LabelName],
      buckets: Buckets,
      series: Map[List[String], Observations]
  ) extends Family

/** Everything the registry holds at one instant, by metric name. */
final case class Snapshot(families: SortedMap[MetricName, Family])

/** The node's measurements as a capability: what to count and what to set are passed as typed
  * definitions ([[Catalogue]]), with the labels as a value of the definition's label type. The
  * interpreter keeps the state; [[Exposition.render]] writes a [[Snapshot]] in the Prometheus text
  * format.
  */
trait Metrics[F[_]]:
  /** Add `by` to a counter (a value that is not above zero adds nothing). */
  def inc[L](counter: CounterDef[L], labels: L, by: Double = 1.0): F[Unit]

  /** Move a counter whose total is known elsewhere up to `to`; never down, so it stays monotone and
    * calling it again with the same total changes nothing.
    */
  def advance[L](counter: CounterDef[L], labels: L, to: Double): F[Unit]

  def set[L](gauge: GaugeDef[L], labels: L, value: Double): F[Unit]

  /** Count a value into a histogram (a value that is not finite is dropped). */
  def observe[L](histogram: HistogramDef[L], labels: L, value: Double): F[Unit]

  def snapshot: F[Snapshot]

object Metrics:
  def apply[F[_]](using metrics: Metrics[F]): Metrics[F] = metrics

  /** A scrape: run the collectors that read the sources of the node, then render the registry. */
  extension [F[_]](metrics: Metrics[F])
    def scrape(collectors: List[F[Unit]])(using F: Monad[F]): F[String] =
      collectors.sequence_ *> metrics.snapshot.map(Exposition.render)

  /** The definitions of a metric without labels, without writing the empty label value. */
  extension [F[_]](metrics: Metrics[F])
    def inc(counter: CounterDef[Unit]): F[Unit] = metrics.inc(counter, (), 1.0)
    def advance(counter: CounterDef[Unit], to: Double): F[Unit] = metrics.advance(counter, (), to)
    def set(gauge: GaugeDef[Unit], value: Double): F[Unit] = metrics.set(gauge, (), value)
    def observe(histogram: HistogramDef[Unit], value: Double): F[Unit] =
      metrics.observe(histogram, (), value)

  /** Records nothing: for the code that has no registry (tests of other parts, a node without the
    * endpoint).
    */
  def none[F[_]: Applicative]: Metrics[F] = new Metrics[F]:
    def inc[L](counter: CounterDef[L], labels: L, by: Double) = Applicative[F].unit
    def advance[L](counter: CounterDef[L], labels: L, to: Double) = Applicative[F].unit
    def set[L](gauge: GaugeDef[L], labels: L, value: Double) = Applicative[F].unit
    def observe[L](histogram: HistogramDef[L], labels: L, value: Double) = Applicative[F].unit
    def snapshot = Applicative[F].pure(Snapshot(SortedMap.empty))

  /** A registry in a `Ref`. The counters and histograms in `registered` exist from the start at
    * zero (a series that is not there yet is not the same as one that is zero); a gauge appears
    * when it is first set. A definition used without being registered is registered by its first
    * use.
    */
  def create[F[_]: Sync](registered: Iterable[MetricDef]): F[Metrics[F]] =
    Ref.of[F, Snapshot](initial(registered)).map(new RefMetrics(_))

  private def initial(registered: Iterable[MetricDef]): Snapshot =
    Snapshot(SortedMap.from(registered.iterator.map {
      case c: CounterDef[?] =>
        c.name -> Family.Counter(
          c.name,
          c.help,
          c.labelNames,
          if c.labelNames.isEmpty then Map(Nil -> 0.0) else Map.empty
        )
      case g: GaugeDef[?] => g.name -> Family.Gauge(g.name, g.help, g.labelNames, Map.empty)
      case h: HistogramDef[?] =>
        h.name -> Family.Histogram(
          h.name,
          h.help,
          h.labelNames,
          h.buckets,
          if h.labelNames.isEmpty then Map(Nil -> empty(h.buckets)) else Map.empty
        )
    }))

  private def empty(buckets: Buckets): Family.Observations =
    Family.Observations(Vector.fill(buckets.bounds.size + 1)(0L), 0.0, 0L)

  private final class RefMetrics[F[_]: Sync](state: Ref[F, Snapshot]) extends Metrics[F]:
    private def counter(snapshot: Snapshot, c: CounterDef[?]): Family.Counter =
      snapshot.families.get(c.name) match
        case Some(f: Family.Counter) => f
        case _                       => Family.Counter(c.name, c.help, c.labelNames, Map.empty)

    private def gauge(snapshot: Snapshot, g: GaugeDef[?]): Family.Gauge =
      snapshot.families.get(g.name) match
        case Some(f: Family.Gauge) => f
        case _                     => Family.Gauge(g.name, g.help, g.labelNames, Map.empty)

    private def histogram(snapshot: Snapshot, h: HistogramDef[?]): Family.Histogram =
      snapshot.families.get(h.name) match
        case Some(f: Family.Histogram) => f
        case _ => Family.Histogram(h.name, h.help, h.labelNames, h.buckets, Map.empty)

    def inc[L](c: CounterDef[L], labels: L, by: Double) =
      val key = c.labels.values(labels)
      state.update { s =>
        val f = counter(s, c)
        val next = f.series.getOrElse(key, 0.0) + (if by > 0 then by else 0.0)
        Snapshot(s.families.updated(c.name, f.copy(series = f.series.updated(key, next))))
      }

    def advance[L](c: CounterDef[L], labels: L, to: Double) =
      val key = c.labels.values(labels)
      state.update { s =>
        val f = counter(s, c)
        val next = f.series.getOrElse(key, 0.0).max(to)
        Snapshot(s.families.updated(c.name, f.copy(series = f.series.updated(key, next))))
      }

    def set[L](g: GaugeDef[L], labels: L, value: Double) =
      val key = g.labels.values(labels)
      state.update { s =>
        val f = gauge(s, g)
        Snapshot(s.families.updated(g.name, f.copy(series = f.series.updated(key, value))))
      }

    def observe[L](h: HistogramDef[L], labels: L, value: Double) =
      val key = h.labels.values(labels)
      if !value.isFinite then Sync[F].unit
      else
        state.update { s =>
          val f = histogram(s, h)
          val seen = f.series.getOrElse(key, empty(f.buckets))
          val at = f.buckets.indexOf(value)
          val next = Family.Observations(
            seen.counts.updated(at, seen.counts(at) + 1),
            seen.sum + value,
            seen.count + 1
          )
          Snapshot(s.families.updated(h.name, f.copy(series = f.series.updated(key, next))))
        }

    def snapshot = state.get
