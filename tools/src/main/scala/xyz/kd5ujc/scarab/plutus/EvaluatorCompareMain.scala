// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import java.net.{URL, URLClassLoader}
import java.nio.file.{Files, Path}

/** Development tool (#1499): two builds of the evaluator timed against each other in one JVM, so
  * that the load of a shared machine falls on both alike.
  *
  * `EvaluatorCompareMain <classpath A> <classpath B> <corpus> [rounds] [warm-up rounds]`, run with
  * `java -cp <tools' runtime classpath>` from the checkout's root. Each classpath file holds a
  * runtime classpath of `tools` (`export tools/Runtime/fullClasspath`), one per build; each build
  * is loaded in a class loader of its own, beside the platform's, and makes the corpus ready
  * ([[EvaluatorSpeedMain.Session]], whose argument `<corpus>` is). Both digests of the results are
  * printed, and must be equal for the builds to answer alike; then the warm-up rounds (10 when not
  * given), and the timed rounds (40 when not given) in pairs, A then B and B then A in turn.
  * Printed: the median, fastest and slowest round of each, and the median and the 10th and 90th
  * percentiles of the ratio B / A within a pair. Not a gate.
  */
object EvaluatorCompareMain:
  /** A session of one build, reached by reflection: the classes are the build's own. */
  private final class Build(classpath: Path, corpus: String):
    private val loader = URLClassLoader(
      Files
        .readString(classpath)
        .trim
        .split(':')
        .map(p => Path.of(p).toUri.toURL: URL),
      ClassLoader.getPlatformClassLoader
    )
    private val session: AnyRef =
      loader
        .loadClass("xyz.kd5ujc.scarab.plutus.EvaluatorSpeedMain$Session")
        .getConstructor(classOf[String])
        .newInstance(corpus)
        .asInstanceOf[AnyRef]
    private val round = session.getClass.getMethod("timed")

    val digest: String = session.getClass.getMethod("digest").invoke(session).asInstanceOf[String]

    def timed(): Long = round.invoke(session).asInstanceOf[java.lang.Long].longValue

  private def median[A: Ordering](xs: Seq[A]): A = xs.sorted.apply(xs.size / 2)

  def main(args: Array[String]): Unit =
    val a = Build(Path.of(args(0)), args(2))
    val b = Build(Path.of(args(1)), args(2))
    val rounds = args.lift(3).flatMap(_.toIntOption).getOrElse(40)
    val warmUp = args.lift(4).flatMap(_.toIntOption).getOrElse(10)
    println(s"digest A ${a.digest}")
    println(s"digest B ${b.digest}")
    (1 to warmUp).foreach { _ =>
      a.timed()
      b.timed()
    }
    val pairs = (0 until rounds).map { i =>
      if i % 2 == 0 then
        val x = a.timed()
        (x, b.timed())
      else
        val y = b.timed()
        (a.timed(), y)
    }
    val ta = pairs.map(_._1)
    val tb = pairs.map(_._2)
    val ratios = pairs.map((x, y) => y.toDouble / x).sorted
    def ms(ns: Long) = ns / 1e6
    println(
      f"A median ${ms(median(ta))}%.2f ms (fastest ${ms(ta.min)}%.2f, slowest ${ms(ta.max)}%.2f); " +
        f"B median ${ms(median(tb))}%.2f ms (fastest ${ms(tb.min)}%.2f, slowest ${ms(tb.max)}%.2f); " +
        f"B/A in a pair: median ${median(ratios)}%.3f, 10th percentile ${ratios(ratios.size / 10)}%.3f, " +
        f"90th ${ratios(ratios.size * 9 / 10)}%.3f"
    )
