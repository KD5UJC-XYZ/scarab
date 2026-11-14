// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Path}

/** Development tool for [[MuslMath]] (#1003): bulk results for comparison with the release node's
  * own functions, and a benchmark against the JVM's `Math`.
  *
  * `tools/runMain xyz.kd5ujc.scarab.ledger.rules.MuslMathMain raw log|exp|pow IN OUT` reads
  * little-endian `f64` arguments (pairs for `pow`) and writes the `f64` results, the format of
  * `probe ELF raw` in `scripts/oracle/release-libm`, so that the two outputs compare with `cmp`.
  * `math-log`, `math-exp` and `math-pow` write the JVM's `Math` results instead, for the comparison
  * the port replaced.
  *
  * `tools/runMain xyz.kd5ujc.scarab.ledger.rules.MuslMathMain bench [pools]` times the likelihoods
  * of one epoch for `pools` stake shares: the formula of `Rewards.likelihood` (one `pow` and 200
  * `log` per pool) once on [[MuslMath]] and once on `Math`, the same code otherwise;
  * `Rewards.likelihood` itself, which also converts each stake to `Double`; and each function
  * alone. It prints the median of nine timed rounds after three warm-up rounds, and a checksum of
  * each round's results so that no work is elided.
  */
object MuslMathMain:

  private def doubles(path: String): Array[Double] =
    val bytes = ByteBuffer.wrap(Files.readAllBytes(Path.of(path))).order(ByteOrder.LITTLE_ENDIAN)
    Array.tabulate(bytes.remaining / 8)(i => bytes.getDouble(8 * i))

  private def write(path: String, values: Array[Double]): Unit =
    val out = ByteBuffer.allocate(8 * values.length).order(ByteOrder.LITTLE_ENDIAN)
    values.foreach(out.putDouble)
    Files.write(Path.of(path), out.array)
    ()

  private def raw(function: String, in: String, out: String): Unit =
    val args = doubles(in)
    val results = function match
      case "log" => args.map(MuslMath.log)
      case "exp" => args.map(MuslMath.exp)
      case "pow" => Array.tabulate(args.length / 2)(i => MuslMath.pow(args(2 * i), args(2 * i + 1)))
      case "math-log" => args.map(Math.log)
      case "math-exp" => args.map(Math.exp)
      case "math-pow" =>
        Array.tabulate(args.length / 2)(i => Math.pow(args(2 * i), args(2 * i + 1)))
      case other => sys.error(s"unknown function $other")
    write(out, results)
    println(s"$function: ${results.length} results")

  /** The formula of `Rewards.likelihood` with the given `pow` and `log`, so that only the functions
    * differ.
    */
  private def formula(
      pow: (Double, Double) => Double,
      log: Double => Double
  )(made: Double, slots: Double, s: Double): Vector[Float] =
    val t = (1.0 - pow(1.0 - 0.05, s)) * (1.0 - 0.0)
    val m = slots - made
    Vector.tabulate(100) { i =>
      val x = (i.toDouble + 0.5) / 100.0
      (made * log(x) + m * log(1.0 - t * x)).toFloat
    }

  @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.While"))
  private def time(name: String, rounds: Int)(body: => Double): Unit =
    var i = 0
    var sink = 0.0
    while i < 3 do
      sink += body
      i += 1
    val samples = (1 to rounds).map { _ =>
      val t0 = System.nanoTime
      sink += body
      System.nanoTime - t0
    }
    val sorted = samples.sorted
    println(
      f"METRIC $name%-28s median=${sorted(rounds / 2) / 1e6}%10.3f ms min=${sorted.head / 1e6}%10.3f ms checksum=$sink%.6e"
    )

  private def bench(pools: Int): Unit =
    val random = new scala.util.Random(1003)
    val total = BigInt("37511237419704189")
    // Stake shares log-uniform from 1e-9 to 1e-2 of circulation, as exact ratios, with block counts near expectation.
    val stakes = Vector.fill(pools) {
      val share = math.pow(10, -9 + 7 * random.nextDouble())
      xyz.kd5ujc.scarab.codec.Rational((BigDecimal(total) * share).toBigInt.max(1), total)
    }
    val shares =
      stakes.map(r => (BigDecimal(r.numerator) / BigDecimal(r.denominator)).toDouble).toArray
    val made = shares.map(s => math.floor(s * 0.05 * 432000.0))
    val args = shares.map(s => 1.0 - s)
    def sum(fs: Iterable[Float]): Double = fs.foldLeft(0.0)(_ + _)
    val musl = formula(MuslMath.pow, MuslMath.log)
    val jvm = formula(Math.pow, Math.log)
    time("formula MuslMath", 9)(
      shares.indices.foldLeft(0.0)((acc, i) => acc + sum(musl(made(i), 432000.0, shares(i))))
    )
    time("formula Math", 9)(
      shares.indices.foldLeft(0.0)((acc, i) => acc + sum(jvm(made(i), 432000.0, shares(i))))
    )
    time("Rewards.likelihood", 9) {
      stakes.indices.foldLeft(0.0)((acc, i) =>
        acc + sum(
          Rewards
            .likelihood(BigInt(made(i).toLong), stakes(i), 0.05, 0.0, BigInt(432000))
            .weights
            .map(_.float)
        )
      )
    }
    val reps = 100
    time(s"log x${pools * reps} MuslMath", 9)(
      (0 until reps).foldLeft(0.0)((a, _) => a + args.map(MuslMath.log).sum)
    )
    time(s"log x${pools * reps} Math", 9)(
      (0 until reps).foldLeft(0.0)((a, _) => a + args.map(Math.log).sum)
    )
    time(s"pow x${pools * reps} MuslMath", 9)(
      (0 until reps).foldLeft(0.0)((a, _) => a + shares.map(MuslMath.pow(0.95, _)).sum)
    )
    time(s"pow x${pools * reps} Math", 9)(
      (0 until reps).foldLeft(0.0)((a, _) => a + shares.map(Math.pow(0.95, _)).sum)
    )
    time(s"exp x${pools * reps} MuslMath", 9)(
      (0 until reps).foldLeft(0.0)((a, _) => a + args.map(x => MuslMath.exp(-700 * x)).sum)
    )
    time(s"exp x${pools * reps} Math", 9)(
      (0 until reps).foldLeft(0.0)((a, _) => a + args.map(x => Math.exp(-700 * x)).sum)
    )

  def main(args: Array[String]): Unit = args.toList match
    case "raw" :: function :: in :: out :: Nil => raw(function, in, out)
    case "bench" :: rest                       => bench(rest.headOption.fold(3000)(_.toInt))
    case _ => sys.error("usage: MuslMathMain raw [math-]log|exp|pow IN OUT | bench [pools]")
