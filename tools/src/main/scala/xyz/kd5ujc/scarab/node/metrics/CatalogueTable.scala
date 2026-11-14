// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

/** Writes the rows of the metrics tables for `docs/running/metrics.md`:
  * `sbt "tools/runMain xyz.kd5ujc.scarab.node.metrics.CatalogueTable"`.
  */
object CatalogueTable:
  def main(args: Array[String]): Unit =
    Catalogue.all.foreach { d =>
      val kind = d match
        case _: CounterDef[?]   => "counter"
        case _: GaugeDef[?]     => "gauge"
        case _: HistogramDef[?] => "histogram"
      val labels = d.labelNames.map(l => s"`${l.value}`").mkString(", ")
      println(s"| `${d.name.value}` | $kind | $labels | ${d.help} |")
    }
