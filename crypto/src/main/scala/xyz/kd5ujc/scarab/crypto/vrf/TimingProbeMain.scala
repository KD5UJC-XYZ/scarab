// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.vrf

import com.weavechain.curve25519.{Constants, Scalar}
import java.security.SecureRandom
import scala.util.control.NonFatal

/** A diagnostic timing probe for the crypto math (AUD-33): fix-vs-random secret classes, percentile
  * bucketing and Welch's t per bucket, with a measured noise control. It lives in the `vrf` package
  * so it can exercise the package-private variable-time Java math ([[Draft03Math]],
  * [[Ed25519Point]]); it is a diagnostic tool, never called by the node.
  *
  * JVM timing is noisy: t-scores are meaningful only relative to a measured noise control (the
  * basepoint table multiply, whose cost does not depend on its scalar's value). A target is
  * reported as a leak when `t > 10 && t > 5 * noise`. The expected result on a quiet machine: the
  * curve25519 `point.multiply` stays near the noise floor (its constant-time claim), while
  * `Ed25519Point.scalarMultiply` and the `uniform` control exceed it by an order of magnitude —
  * which is exactly the "variable-time, public inputs only" contract of `Draft03Math.java`.
  *
  * Run: `sbt "crypto/runMain xyz.kd5ujc.scarab.crypto.vrf.TimingProbeMain [samples [buckets
  * [repetitions]]]"` on an idle machine, ideally with `-XX:+UseSerialGC -Xbatch`. Protocol and
  * interpretation: docs/performance.md section 32.
  */
object TimingProbeMain:

  private type Op = () => Unit

  private def guarded(op: Op): Op = () =>
    try op()
    catch case NonFatal(_) => ()

  private def fixedBytes(length: Int, fill: Byte): Array[Byte] = Array.fill(length)(fill)

  /** The largest |Welch t| over the percentile buckets of the two class distributions. */
  private def maxT(fix: Array[Double], rnd: Array[Double], buckets: Int): Double =
    val samples = fix.length
    val width = math.max(1, samples / buckets)
    var max = 0.0
    var i = 0
    while i < buckets do
      val lo = i * width
      val hi = math.min(samples, (i + 1) * width)
      val count = hi - lo
      var meanFix = 0.0
      var meanRnd = 0.0
      var k = lo
      while k < hi do
        meanFix += fix(k)
        meanRnd += rnd(k)
        k += 1
      meanFix /= count
      meanRnd /= count
      var varFix = 0.0
      var varRnd = 0.0
      k = lo
      while k < hi do
        varFix += (fix(k) - meanFix) * (fix(k) - meanFix)
        varRnd += (rnd(k) - meanRnd) * (rnd(k) - meanRnd)
        k += 1
      varFix /= count
      varRnd /= count
      val se = math.sqrt(varFix / count + varRnd / count)
      val t = math.abs(meanFix - meanRnd) / math.max(se, 1e-12)
      if t > max then max = t
      i += 1
    max

  /** Warm both classes, then measure `samples` of each, interleaved in the same order. The mutation
    * here is the point of the probe: a measurement loop over pre-allocated arrays.
    */
  private def run(fixOp: Op, rndOp: Op, samples: Int, buckets: Int): Double =
    var i = 0
    while i < 1000 do
      fixOp()
      rndOp()
      i += 1
    val fix = new Array[Double](samples)
    val rnd = new Array[Double](samples)
    i = 0
    while i < samples do
      var start = System.nanoTime()
      fixOp()
      fix(i) = (System.nanoTime() - start).toDouble
      start = System.nanoTime()
      rndOp()
      rnd(i) = (System.nanoTime() - start).toDouble
      i += 1
    java.util.Arrays.sort(fix)
    java.util.Arrays.sort(rnd)
    maxT(fix, rnd, buckets)

  private def row(name: String, t: Double, noise: Option[Double]): Unit =
    val leak = noise.fold(t > 10.0)(n => t > 10.0 && t > 5.0 * n)
    println(f"  $name%-52s max|t| = $t%6.1f  ${if leak then "LEAK" else "clean"}")

  def main(args: Array[String]): Unit =
    val samples = args.lift(0).flatMap(_.toIntOption).getOrElse(20000)
    val buckets = args.lift(1).flatMap(_.toIntOption).getOrElse(100)
    val repetitions = args.lift(2).flatMap(_.toIntOption).getOrElse(2)
    val random = new SecureRandom
    val randomBytes = new Array[Byte](32)
    random.nextBytes(randomBytes)
    val fixScalarBytes = fixedBytes(32, 0x33.toByte)
    val rndScalarBytes = new Array[Byte](32)
    random.nextBytes(rndScalarBytes)
    val fixedScalar = Scalar.fromBytesModOrder(fixScalarBytes)
    val randomScalar = Scalar.fromBytesModOrder(rndScalarBytes)
    val fixedPoint =
      Constants.ED25519_BASEPOINT_TABLE.multiply(Scalar.fromBytesModOrder(randomBytes))

    val noiseFix: Op = guarded(() => Constants.ED25519_BASEPOINT_TABLE.multiply(fixedScalar))
    val noiseRnd: Op = guarded(() => Constants.ED25519_BASEPOINT_TABLE.multiply(randomScalar))
    val pointMulFix: Op = guarded(() => fixedPoint.multiply(fixedScalar))
    val pointMulRnd: Op = guarded(() => fixedPoint.multiply(randomScalar))
    val javaMulFix: Op =
      guarded(() => Draft03Math.uniform(randomBytes).scalarMultiply(fixScalarBytes))
    val javaMulRnd: Op =
      guarded(() => Draft03Math.uniform(randomBytes).scalarMultiply(rndScalarBytes))
    val uniformFix: Op = guarded(() => Draft03Math.uniform(fixedBytes(32, 0x42.toByte)))
    val uniformRnd: Op = guarded(() => Draft03Math.uniform(randomBytes))

    var rep = 1
    while rep <= repetitions do
      println(s"--- repetition $rep (samples=$samples, buckets=$buckets) ---")
      System.gc()
      val noise = run(noiseFix, noiseRnd, samples, buckets)
      row("noise: basepoint table multiply", noise, None)
      System.gc()
      row(
        "curve25519 point.multiply (secret scalar)",
        run(pointMulFix, pointMulRnd, samples, buckets),
        Some(noise)
      )
      System.gc()
      row(
        "Ed25519Point.scalarMultiply (secret scalar)",
        run(javaMulFix, javaMulRnd, samples, buckets),
        Some(noise)
      )
      System.gc()
      row(
        "Draft03Math.uniform (public input)",
        run(uniformFix, uniformRnd, samples, buckets),
        Some(noise)
      )
      rep += 1
