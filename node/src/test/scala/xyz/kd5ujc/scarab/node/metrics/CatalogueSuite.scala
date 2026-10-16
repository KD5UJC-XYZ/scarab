// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

import cats.effect.SyncIO
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.BlockRule
import xyz.kd5ujc.scarab.protocol.Era

/** The catalogue is closed and consistent: every definition is registered, none repeats a name, and
  * the names follow the conventions the documentation promises.
  */
class CatalogueSuite extends FunSuite:
  /** Every `MetricDef` value the object declares, found by reflection (so a new one that is not
    * added to `all` is noticed).
    */
  private val declared: List[MetricDef] =
    Catalogue.getClass.getDeclaredMethods.toList
      .filter(m => m.getParameterCount == 0 && classOf[MetricDef].isAssignableFrom(m.getReturnType))
      .map(_.invoke(Catalogue).asInstanceOf[MetricDef])

  test("all lists every definition of the catalogue, once") {
    assert(declared.size > 50, declared.size)
    assertEquals(Catalogue.all.toSet, declared.toSet)
    assertEquals(Catalogue.all.size, Catalogue.all.toSet.size)
  }

  test("no two metrics share a name") {
    val names = Catalogue.all.map(_.name.value)
    assertEquals(names.diff(names.distinct), Nil)
  }

  test("a metric has a one-line help and no repeated label name") {
    Catalogue.all.foreach { d =>
      assert(d.help.nonEmpty && !d.help.contains('\n'), d.name.value)
      assertEquals(d.labelNames.map(_.value).distinct.size, d.labelNames.size, d.name.value)
    }
  }

  test(
    "our own counters end in _total, our gauges do not, and the Haskell names keep their suffix"
  ) {
    Catalogue.all.foreach { d =>
      val name = d.name.value
      val ours =
        name.startsWith("scarab_") || name.startsWith("jvm_") || name.startsWith("process_")
      d match
        case _: CounterDef[?] if ours => assert(name.endsWith("_total"), name)
        case _: GaugeDef[?] if ours   => assert(!name.endsWith("_total"), name)
        case _: HistogramDef[?]       => assert(!name.endsWith("_total"), name)
        case _                        => ()
      if name.startsWith("cardano_node_metrics_") then
        d match
          case _: CounterDef[?]   => assert(name.endsWith("_counter"), name)
          case _: GaugeDef[?]     => assert(name.endsWith("_int") || name.endsWith("_real"), name)
          case _: HistogramDef[?] => fail(s"$name: the Haskell node has no histograms")
    }
  }

  test("an enum label is written as the name of its case, a rule family as the log names it") {
    assertEquals(
      Catalogue.connectionsByState.labels.values(ConnectionStateLabel.Established),
      List("Established")
    )
    assertEquals(Catalogue.currentEra.labels.values(Era.Conway), List("Conway"))
    assertEquals(Catalogue.validationErrors.labels.values(BlockRule.TPraos), List("tpraos"))
    assertEquals(Catalogue.jvmMemoryUsed.labels.values(AreaLabel.NonHeap), List("nonheap"))
    assertEquals(
      Catalogue.buildInfo.labels.values(("0.1.0", "3.3.8", "abc")),
      List("0.1.0", "3.3.8", "abc")
    )
  }

  test("a fresh registry renders every counter and histogram, and parses") {
    val text =
      Exposition.render(Metrics.create[SyncIO](Catalogue.all).flatMap(_.snapshot).unsafeRunSync())
    val parsed = TextFormat.parse(text).fold(fail(_), identity)
    assertEquals(parsed.order.toSet, Catalogue.all.map(_.name.value).toSet)
    assert(parsed.samples.exists(_.name == "scarab_rollbacks_total"))
    assert(parsed.samples.exists(_.name == "scarab_block_apply_seconds_count"))
    // labelled counters have no series before the first event
    assert(!parsed.samples.exists(_.name == "scarab_validation_errors_total"))
  }
