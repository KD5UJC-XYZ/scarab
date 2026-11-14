// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
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
object MuslMathMain extends IOApp:

  /** The functions `raw` evaluates: the port's, or the JVM's for comparison. */
  private enum Target(val arity: Int, val eval: Array[Double] => Double):
    case Log extends Target(1, a => MuslMath.log(a(0)))
    case Exp extends Target(1, a => MuslMath.exp(a(0)))
    case Pow extends Target(2, a => MuslMath.pow(a(0), a(1)))
    case MathLog extends Target(1, a => Math.log(a(0)))
    case MathExp extends Target(1, a => Math.exp(a(0)))
    case MathPow extends Target(2, a => Math.pow(a(0), a(1)))

  private object Target:
    /** The command line's name of a function. */
    def parse(name: String): Option[Target] = name match
      case "log"      => Some(Log)
      case "exp"      => Some(Exp)
      case "pow"      => Some(Pow)
      case "math-log" => Some(MathLog)
      case "math-exp" => Some(MathExp)
      case "math-pow" => Some(MathPow)
      case _          => None

  private def doubles(path: Path): IO[Array[Double]] = IO.blocking {
    val bytes = ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN)
    Array.tabulate(bytes.remaining / 8)(i => bytes.getDouble(8 * i))
  }

  private def write(path: Path, values: Array[Double]): IO[Unit] = IO.blocking {
    val out = ByteBuffer.allocate(8 * values.length).order(ByteOrder.LITTLE_ENDIAN)
    values.foreach(out.putDouble)
    Files.write(path, out.array)
  }.void

  private def raw(function: Target, in: Path, out: Path): IO[Unit] =
    for
      args <- doubles(in)
      results = args.grouped(function.arity).map(function.eval).toArray
      _ <- write(out, results)
      _ <- IO.println(s"$function: ${results.length} results")
    yield ()

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

  /** Three warm-up rounds, then `rounds` timed ones. The timed region reads the system clock
    * directly: `Clock[IO]` would add its allocation to what is timed (design rules FUN-2 (e) and
    * EFF-6, the timed region of a measurement tool).
    */
  private def time(name: String, rounds: Int)(body: => Double): IO[Unit] = IO
    .blocking {
      val warm = (1 to 3).foldLeft(0.0)((sink, _) => sink + body)
      val timed = (1 to rounds).map { _ =>
        val t0 = System.nanoTime
        val value = body
        (System.nanoTime - t0, value)
      }
      val sorted = timed.map(_._1).sorted
      val sink = timed.foldLeft(warm)(_ + _._2)
      f"METRIC $name%-28s median=${sorted(rounds / 2) / 1e6}%10.3f ms min=${sorted.head / 1e6}%10.3f ms checksum=$sink%.6e"
    }
    .flatMap(IO.println)

  private def bench(pools: Int): IO[Unit] =
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
    val reps = 100
    List(
      time("formula MuslMath", 9)(
        shares.indices.foldLeft(0.0)((acc, i) => acc + sum(musl(made(i), 432000.0, shares(i))))
      ),
      time("formula Math", 9)(
        shares.indices.foldLeft(0.0)((acc, i) => acc + sum(jvm(made(i), 432000.0, shares(i))))
      ),
      time("Rewards.likelihood", 9) {
        stakes.indices.foldLeft(0.0)((acc, i) =>
          acc + sum(
            Rewards
              .likelihood(BigInt(made(i).toLong), stakes(i), 0.05, 0.0, BigInt(432000))
              .weights
              .map(_.float)
          )
        )
      },
      time(s"log x${pools * reps} MuslMath", 9)(
        (0 until reps).foldLeft(0.0)((a, _) => a + args.map(MuslMath.log).sum)
      ),
      time(s"log x${pools * reps} Math", 9)(
        (0 until reps).foldLeft(0.0)((a, _) => a + args.map(Math.log).sum)
      ),
      time(s"pow x${pools * reps} MuslMath", 9)(
        (0 until reps).foldLeft(0.0)((a, _) => a + shares.map(MuslMath.pow(0.95, _)).sum)
      ),
      time(s"pow x${pools * reps} Math", 9)(
        (0 until reps).foldLeft(0.0)((a, _) => a + shares.map(Math.pow(0.95, _)).sum)
      ),
      time(s"exp x${pools * reps} MuslMath", 9)(
        (0 until reps).foldLeft(0.0)((a, _) => a + args.map(x => MuslMath.exp(-700 * x)).sum)
      ),
      time(s"exp x${pools * reps} Math", 9)(
        (0 until reps).foldLeft(0.0)((a, _) => a + args.map(x => Math.exp(-700 * x)).sum)
      )
    ).sequence_

  private val usage = "usage: MuslMathMain raw [math-]log|exp|pow IN OUT | bench [pools]"

  def run(args: List[String]): IO[ExitCode] = args match
    case "raw" :: name :: in :: out :: Nil =>
      Target
        .parse(name)
        .fold(IO.println(usage).as(ExitCode.Error))(f =>
          raw(f, Path.of(in), Path.of(out)).as(ExitCode.Success)
        )
    case "bench" :: Nil => bench(3000).as(ExitCode.Success)
    case "bench" :: pools :: Nil =>
      pools.toIntOption.fold(IO.println(usage).as(ExitCode.Error))(n =>
        bench(n).as(ExitCode.Success)
      )
    case _ => IO.println(usage).as(ExitCode.Error)
