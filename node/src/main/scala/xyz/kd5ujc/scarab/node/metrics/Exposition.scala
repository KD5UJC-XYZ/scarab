// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

import scala.collection.immutable.SortedMap

/** The Prometheus text exposition format, version 0.0.4
  * (https://prometheus.io/docs/instrumenting/exposition_formats/): for each metric a `# HELP` and a
  * `# TYPE` line, then its samples, one per line. A histogram is its cumulative `_bucket` samples
  * (`le` is the upper bound, the last one `+Inf`), `_sum` and `_count`. Pure: the same snapshot
  * always gives the same text, metrics by name and series by label values.
  */
object Exposition:
  /** The content type of the text format, for the HTTP response. */
  val ContentType: String = "text/plain; version=0.0.4; charset=utf-8"

  def render(snapshot: Snapshot): String =
    val out = new java.lang.StringBuilder
    snapshot.families.valuesIterator.foreach(family(out, _))
    out.toString

  private def family(out: java.lang.StringBuilder, f: Family): Unit =
    val kind = f match
      case _: Family.Counter   => "counter"
      case _: Family.Gauge     => "gauge"
      case _: Family.Histogram => "histogram"
    out.append("# HELP ").append(f.name.value).append(' ').append(escapeHelp(f.help)).append('\n')
    out.append("# TYPE ").append(f.name.value).append(' ').append(kind).append('\n')
    f match
      case c: Family.Counter =>
        ordered(c.series).foreach((values, n) =>
          sample(out, c.name.value, c.labelNames, values, number(n))
        )
      case g: Family.Gauge =>
        ordered(g.series).foreach((values, v) =>
          sample(out, g.name.value, g.labelNames, values, number(v))
        )
      case h: Family.Histogram =>
        ordered(h.series).foreach((values, seen) => histogram(out, h, values, seen))

  private def histogram(
      out: java.lang.StringBuilder,
      h: Family.Histogram,
      values: List[String],
      seen: Family.Observations
  ): Unit =
    val name = h.name.value
    val bounds = h.buckets.bounds.map(number) :+ "+Inf"
    val cumulative = seen.counts.scanLeft(0L)(_ + _).tail
    bounds
      .zip(cumulative)
      .foreach((le, n) =>
        sample(out, s"${name}_bucket", h.labelNames, values, n.toString, Some(le))
      )
    sample(out, s"${name}_sum", h.labelNames, values, number(seen.sum))
    sample(out, s"${name}_count", h.labelNames, values, seen.count.toString)

  private def ordered[A](series: Map[List[String], A]): Vector[(List[String], A)] =
    SortedMap.from(series)(using Ordering.Implicits.seqOrdering(using Ordering.String)).toVector

  private def sample(
      out: java.lang.StringBuilder,
      name: String,
      labelNames: List[LabelName],
      values: List[String],
      value: String,
      le: Option[String] = None
  ): Unit =
    out.append(name)
    val pairs = labelNames.map(_.value).zip(values) ++ le.map("le" -> _)
    if pairs.nonEmpty then
      out.append('{')
      pairs.zipWithIndex.foreach { case ((k, v), i) =>
        if i > 0 then out.append(',')
        out.append(k).append("=\"").append(escapeLabel(v)).append('"')
      }
      out.append('}')
    out.append(' ').append(value).append('\n')

  /** A sample value: integers without a fraction, the three special values as the format spells
    * them, every other number as Java prints it (`1.0E-4`, which the parsers of Prometheus read).
    */
  def number(v: Double): String =
    if v.isNaN then "NaN"
    else if v.isPosInfinity then "+Inf"
    else if v.isNegInfinity then "-Inf"
    else if v == math.rint(v) && math.abs(v) < 1e15 then v.toLong.toString
    else v.toString

  /** In a `# HELP` line a backslash and a line feed are escaped. */
  def escapeHelp(text: String): String = text.replace("\\", "\\\\").replace("\n", "\\n")

  /** In a label value a backslash, a double quote and a line feed are escaped. */
  def escapeLabel(text: String): String =
    text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
