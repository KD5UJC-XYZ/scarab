// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

import cats.effect.{IO, SyncIO}
import cats.syntax.all.*
import munit.{CatsEffectSuite, ScalaCheckSuite}
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.{forAll, propBoolean}

/** The registry's behaviour, the text it renders, and that the text is well formed for any sequence
  * of operations, including the label values that need escaping.
  */
class MetricsSuite extends CatsEffectSuite with ScalaCheckSuite:
  private val requests = CounterDef(
    MetricName("test_requests_total"),
    "Requests, by path.\nSecond line with a \\ backslash.",
    Labels.one[String](LabelName("path"))
  )
  private val total = CounterDef(MetricName("test_total"), "Unlabelled counter.")
  private val level = GaugeDef(MetricName("test_level"), "A gauge.")
  private val sizes = GaugeDef(
    MetricName("test_sizes"),
    "A labelled gauge.",
    Labels.two[String, String](LabelName("a"), LabelName("b"))
  )
  private val latency = HistogramDef(
    MetricName("test_latency_seconds"),
    "A histogram.",
    Labels.one[String](LabelName("op")),
    Buckets.of(0.1, 0.5, 1)
  )
  private val plain = HistogramDef(
    MetricName("test_plain_seconds"),
    "A histogram without labels.",
    Labels.none,
    Buckets.of(1)
  )

  private val defs: List[MetricDef] = List(requests, total, level, sizes, latency, plain)

  private def registry = Metrics.create[SyncIO](defs).unsafeRunSync()

  private def counterValue(snapshot: Snapshot, c: CounterDef[?], key: List[String]): Double =
    snapshot.families(c.name) match
      case f: Family.Counter => f.series.getOrElse(key, Double.NaN)
      case _                 => Double.NaN

  test("a counter adds, ignores what is not above zero, and an unlabelled one starts at zero") {
    val m = registry
    val program = for
      zero <- m.snapshot
      _ <- m.inc(total)
      _ <- m.inc(total, (), 2.5)
      _ <- m.inc(total, (), -4.0)
      _ <- m.inc(total, (), 0.0)
      after <- m.snapshot
    yield (counterValue(zero, total, Nil), counterValue(after, total, Nil))
    assertEquals(program.unsafeRunSync(), (0.0, 3.5))
  }

  test("advance only moves a counter up, and repeating it changes nothing") {
    val m = registry
    val program = for
      _ <- m.advance(total, 10)
      _ <- m.advance(total, 4)
      _ <- m.advance(total, 10)
      s <- m.snapshot
    yield counterValue(s, total, Nil)
    assertEquals(program.unsafeRunSync(), 10.0)
  }

  test("series are kept apart by their labels") {
    val m = registry
    val program = for
      _ <- m.inc(requests, "/a")
      _ <- m.inc(requests, "/b", 5.0)
      _ <- m.inc(requests, "/a")
      s <- m.snapshot
    yield (counterValue(s, requests, List("/a")), counterValue(s, requests, List("/b")))
    assertEquals(program.unsafeRunSync(), (2.0, 5.0))
  }

  test("a histogram counts a value into the first bucket at or above it; non-finite values drop") {
    val m = registry
    val program = for
      _ <- List(0.05, 0.1, 0.3, 0.5, 2.0, Double.NaN, Double.PositiveInfinity)
        .traverse_(m.observe(latency, "get", _))
      s <- m.snapshot
    yield s.families(latency.name) match
      case h: Family.Histogram => h.series(List("get"))
      case other               => fail(s"not a histogram: $other")
    val seen = program.unsafeRunSync()
    assertEquals(seen.counts, Vector(2L, 2L, 0L, 1L))
    assertEquals(seen.count, 5L)
    assertEqualsDouble(seen.sum, 2.95, 1e-9)
  }

  test("increments from many fibers all count") {
    val m = Metrics.create[IO](defs)
    m.flatMap { r =>
      (1 to 500).toList.parTraverse_(_ => r.inc(requests, "/p")) *> r.snapshot
    }.map(s => assertEquals(counterValue(s, requests, List("/p")), 500.0))
  }

  test("a golden text: the format dashboards and scrapers depend on") {
    val m = registry
    val program =
      m.inc(requests, "/tip", 3.0) *>
        m.inc(requests, "a \"quoted\" \\ path\nwith a break") *>
        m.set(level, 42.0) *>
        m.set(sizes, ("x", "y"), 0.25) *>
        List(0.05, 0.7, 3.0).traverse_(m.observe(latency, "get", _)) *>
        m.observe(plain, 0.5) *>
        m.snapshot
    val text = Exposition.render(program.unsafeRunSync())
    val expected =
      """# HELP test_latency_seconds A histogram.
        |# TYPE test_latency_seconds histogram
        |test_latency_seconds_bucket{op="get",le="0.1"} 1
        |test_latency_seconds_bucket{op="get",le="0.5"} 1
        |test_latency_seconds_bucket{op="get",le="1"} 2
        |test_latency_seconds_bucket{op="get",le="+Inf"} 3
        |test_latency_seconds_sum{op="get"} 3.75
        |test_latency_seconds_count{op="get"} 3
        |# HELP test_level A gauge.
        |# TYPE test_level gauge
        |test_level 42
        |# HELP test_plain_seconds A histogram without labels.
        |# TYPE test_plain_seconds histogram
        |test_plain_seconds_bucket{le="1"} 1
        |test_plain_seconds_bucket{le="+Inf"} 1
        |test_plain_seconds_sum 0.5
        |test_plain_seconds_count 1
        |# HELP test_requests_total Requests, by path.\nSecond line with a \\ backslash.
        |# TYPE test_requests_total counter
        |test_requests_total{path="/tip"} 3
        |test_requests_total{path="a \"quoted\" \\ path\nwith a break"} 1
        |# HELP test_sizes A labelled gauge.
        |# TYPE test_sizes gauge
        |test_sizes{a="x",b="y"} 0.25
        |# HELP test_total Unlabelled counter.
        |# TYPE test_total counter
        |test_total 0
        |""".stripMargin
    assertEquals(text, expected)
  }

  test("sample values are written as the format spells them") {
    assertEquals(
      List(
        0.0,
        -0.0,
        1.0,
        -3.0,
        0.5,
        0.0005,
        1792127183.33,
        2.5e-7,
        1e20,
        Double.NaN,
        Double.PositiveInfinity,
        Double.NegativeInfinity
      )
        .map(Exposition.number),
      List(
        "0",
        "0",
        "1",
        "-3",
        "0.5",
        "0.0005",
        "1792127183.33",
        "2.5E-7",
        "1.0E20",
        "NaN",
        "+Inf",
        "-Inf"
      )
    )
  }

  test("a name or a label name that the format does not allow does not compile") {
    assert(compileErrors("""MetricName("bad-name")""").nonEmpty)
    assert(compileErrors("""MetricName("1starts_with_digit")""").nonEmpty)
    assert(compileErrors("""LabelName("__reserved")""").nonEmpty)
    assert(compileErrors("""LabelName("le")""").nonEmpty)
    assert(compileErrors("""LabelName("has space")""").nonEmpty)
    assertEquals(compileErrors("""MetricName("a:b_c1")"""), "")
  }

  test("buckets are sorted, finite and distinct whatever is given") {
    assertEquals(
      Buckets.of(5, 1, 1, Double.NaN, Double.PositiveInfinity, 3).bounds,
      Vector(1.0, 3.0, 5.0)
    )
    assertEquals(Buckets.of(Double.NaN).bounds, Vector(0.0))
    assertEquals(Buckets.exponential(1, 2, 4).bounds, Vector(1.0, 2.0, 4.0, 8.0))
  }

  // ---- the property: any sequence of operations renders as well-formed text that says what the
  // ---- operations did

  private enum Op:
    case Inc(path: String, by: Double)
    case Advance(path: String, to: Double)
    case Set(a: String, b: String, value: Double)
    case Observe(op: String, value: Double)

  private val awkward: Gen[Char] = Gen.frequency(
    10 -> Gen.alphaNumChar,
    1 -> Gen.const('\\'),
    1 -> Gen.const('"'),
    1 -> Gen.const('\n'),
    1 -> Gen.oneOf(' ', '{', '}', ',', '=', '#', 'é', '☃')
  )
  private val text: Gen[String] = Gen.listOfN(6, awkward).map(_.mkString)
  private val paths: Gen[String] = Gen.oneOf(Gen.const("/same"), text)
  private val amounts: Gen[Double] =
    Gen.oneOf(Gen.choose(0.0, 1e6), Gen.choose(-5.0, 5.0), Gen.const(0.0))
  private val gaugeValues: Gen[Double] = Gen.oneOf(
    Gen.double,
    Gen.choose(-1e6, 1e6),
    Gen.oneOf(
      Double.NaN,
      Double.PositiveInfinity,
      Double.NegativeInfinity,
      0.0,
      -0.0,
      1e300,
      1e-300,
      Double.MaxValue,
      Double.MinPositiveValue
    )
  )
  private val ops: Gen[Op] = Gen.oneOf(
    for p <- paths; by <- amounts yield Op.Inc(p, by),
    for p <- paths; to <- amounts yield Op.Advance(p, to),
    for a <- paths; b <- paths; v <- gaugeValues yield Op.Set(a, b, v),
    for p <- paths; v <- Gen.oneOf(amounts, gaugeValues) yield Op.Observe(p, v)
  )

  private def run(ops: List[Op]): Snapshot =
    val m = registry
    ops
      .traverse_ {
        case Op.Inc(p, by)     => m.inc(requests, p, by)
        case Op.Advance(p, to) => m.advance(requests, p, to)
        case Op.Set(a, b, v)   => m.set(sizes, (a, b), v)
        case Op.Observe(op, v) => m.observe(latency, op, v)
      }
      .flatMap(_ => m.snapshot)
      .unsafeRunSync()

  private def same(a: Double, b: Double): Boolean =
    (a.isNaN && b.isNaN) || a == b || math.abs(a - b) <= 1e-9 * math.max(math.abs(a), math.abs(b))

  property("any sequence of operations renders as well-formed text that matches the operations") {
    forAll(Gen.listOf(ops)) { operations =>
      val rendered = Exposition.render(run(operations))
      TextFormat.parse(rendered) match
        case Left(error) => Prop.falsified :| s"not well formed: $error\n$rendered"
        case Right(parsed) =>
          val bySeries = parsed.samples.groupBy(s => (s.name, s.labels))
          def one(name: String, labels: List[(String, String)]): Option[Double] =
            bySeries.get((name, labels)).flatMap(_.headOption).map(_.value)

          // counters: the sum of the increments, and the advance moves up to its target
          val counterModel = operations
            .foldLeft(Map.empty[String, Double]) {
              case (m, Op.Inc(p, by)) =>
                m.updated(p, m.getOrElse(p, 0.0) + (if by > 0 then by else 0.0))
              case (m, Op.Advance(p, to)) => m.updated(p, m.getOrElse(p, 0.0).max(to))
              case (m, _)                 => m
            }
          val counters = counterModel.forall((p, v) =>
            one("test_requests_total", List("path" -> p)).exists(same(_, v))
          )
          // gauges: the last value set for each label pair
          val gaugeModel = operations.foldLeft(Map.empty[(String, String), Double]) {
            case (m, Op.Set(a, b, v)) => m.updated((a, b), v)
            case (m, _)               => m
          }
          val gauges = gaugeModel.forall { case ((a, b), v) =>
            one("test_sizes", List("a" -> a, "b" -> b)).exists(same(_, v))
          }
          // histograms: cumulative buckets that end in +Inf at the count of finite observations
          val seen = operations.collect { case Op.Observe(op, v) if v.isFinite => op -> v }
          val histograms = seen.groupMap(_._1)(_._2).forall { (op, values) =>
            val buckets = parsed.samples
              .filter(s =>
                s.name == "test_latency_seconds_bucket" && s.labels.headOption.contains("op" -> op)
              )
              .map(s => s.labels.last._2 -> s.value)
            val cumulative = buckets.map(_._2)
            buckets.map(_._1) == List("0.1", "0.5", "1", "+Inf") &&
            cumulative == cumulative.sorted &&
            cumulative.lastOption.contains(values.size.toDouble) &&
            one("test_latency_seconds_count", List("op" -> op)).contains(values.size.toDouble) &&
            one("test_latency_seconds_sum", List("op" -> op)).exists(same(_, values.sum))
          }
          val families =
            parsed.order == parsed.order.sorted && parsed.order.toSet == defs
              .map(_.name.value)
              .toSet
          (counters :| "counters") && (gauges :| "gauges") && (histograms :| "histograms") &&
          (families :| s"families: ${parsed.order}")
    }
  }
