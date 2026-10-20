// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.vrf

import com.weavechain.curve25519.{Constants, Scalar}
import java.security.SecureRandom
import scala.util.control.NonFatal

/** A diagnostic timing probe for the crypto math (AUD2-33): a fixed-secret class against a
  * random-secret class with a fresh random-class input per sample, percentile bucketing and Welch's
  * t per bucket, and a measured noise control. It lives in the `vrf` package so it can exercise the
  * package-private variable-time Java math ([[Draft03Math]], [[Ed25519Point]]); it is a diagnostic
  * tool, never called by the node.
  *
  * JVM timing is noisy: t-scores are meaningful only relative to a measured noise control (the
  * basepoint table multiply, whose cost does not depend on its scalar's value). A target is
  * reported as leaking when `t > 10 && t > 5 * noise`. A "no leak" verdict is "no leakage detected
  * by this experiment" — a bounded negative observation, not a proof of constant-time behaviour.
  * The expected outcome on a quiet machine: curve25519's `point.multiply` shows no leakage above
  * the floor, while `Ed25519Point.scalarMultiply` and the `uniform` control exceed it by an order
  * of magnitude — the "variable-time, public inputs only" contract of `Draft03Math.java`, which the
  * guard in `scripts/check_crypto_guard.py` enforces. A failed operation is reported and aborts the
  * run: exception handling is not measured as successful crypto timing. A repetition whose measured
  * noise floor exceeds the contamination bound (see below) is retried once and then discarded: on
  * shared or virtualized hosts the floor occasionally explodes with environmental spikes, and a
  * verdict computed against such a floor is meaningless.
  *
  * Run: `sbt "crypto/runMain xyz.kd5ujc.scarab.crypto.vrf.TimingProbeMain [samples [buckets
  * [repetitions]]]"` on an idle machine, ideally with `-XX:+UseSerialGC -Xbatch`. Protocol and
  * interpretation: docs/performance.md section 32.
  */
object TimingProbeMain:

  /** What one measurement produced: a byte consumed by the probe's sink (kept observable so the
    * computation is not eliminated), or a failure, which is never timed as success.
    */
  private enum OpOutcome:
    case Produced(value: Byte)
    case Failed(reason: String)

  /** An operation over the i-th pre-generated input of its class. */
  private type Op = Int => OpOutcome

  private def outcome(body: => Byte): OpOutcome =
    try OpOutcome.Produced(body)
    catch case NonFatal(e) => OpOutcome.Failed(e.getClass.getSimpleName)

  /** Pre-generated random inputs: the public bytes, the library scalar derived from them, and a
    * separate random 32-byte scalar for the Java `scalarMultiply` target.
    */
  private final case class Input(bytes: Array[Byte], scalar: Scalar, scalarBytes: Array[Byte])

  private def freshInputs(count: Int, random: SecureRandom): Array[Input] =
    Array.fill(count) {
      val bytes = new Array[Byte](32)
      random.nextBytes(bytes)
      val scalarBytes = new Array[Byte](32)
      random.nextBytes(scalarBytes)
      Input(bytes, Scalar.fromBytesModOrder(bytes), scalarBytes)
    }

  /** The largest |Welch t| over the percentile buckets of the two class distributions. */
  private def maxT(fix: Array[Double], rnd: Array[Double], buckets: Int): Double =
    val samples = fix.length
    val width = samples / buckets
    var max = 0.0
    var i = 0
    while i < buckets do
      val lo = i * width
      val hi = if i == buckets - 1 then samples else (i + 1) * width
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

  /** Warm both classes, then measure `samples` of each. Every sample uses its own pre-generated
    * random-class input; failed operations are counted and excluded, and reported at the end. The
    * mutation here is the point of the probe: a measurement loop over pre-allocated arrays with an
    * observable checksum printed after measurement so the produced bytes remain live.
    */
  private final class Measurement(val failures: Int, val t: Double)

  private def run(
      fixOp: Op,
      rndOp: Op,
      inputs: Array[Input],
      samples: Int,
      buckets: Int
  ): Measurement =
    val warmup = math.min(inputs.length, 1000)
    var i = 0
    while i < warmup do
      fixOp(i)
      rndOp(i)
      i += 1
    val fix = new Array[Double](samples)
    val rnd = new Array[Double](samples)
    var checksum = 0
    var measured = 0
    var failures = 0
    var attempts = 0
    val maxAttempts = samples * 2
    while measured < samples && attempts < maxAttempts do
      attempts += 1
      val idx = measured % inputs.length
      val startFix = System.nanoTime()
      fixOp(idx) match
        case OpOutcome.Produced(b) =>
          fix(measured) = (System.nanoTime() - startFix).toDouble
          checksum = Integer.rotateLeft(checksum, 1) ^ (b & 0xff)
        case OpOutcome.Failed(reason) =>
          failures += 1
          println(s"fix-class failure at sample $measured: $reason")
          fix(measured) = Double.NaN
      val startRnd = System.nanoTime()
      rndOp(idx) match
        case OpOutcome.Produced(b) =>
          rnd(measured) = (System.nanoTime() - startRnd).toDouble
          checksum = Integer.rotateLeft(checksum, 1) ^ (b & 0xff)
        case OpOutcome.Failed(reason) =>
          failures += 1
          println(s"random-class failure at sample $measured: $reason")
          rnd(measured) = Double.NaN
      if !java.lang.Double.isNaN(fix(measured)) && !java.lang.Double.isNaN(rnd(measured)) then
        measured += 1
      // else: the sample slot is overwritten by the next attempt
    // Publish after timing: an unobserved local accumulator is not a dead-code-elimination guard.
    println(f"  output checksum: 0x$checksum%08x")
    if failures > 0 then Measurement(failures, Double.NaN)
    else
      java.util.Arrays.sort(fix)
      java.util.Arrays.sort(rnd)
      Measurement(0, maxT(fix, rnd, buckets))

  private def row(name: String, t: Double, noise: Option[Double]): Unit =
    val verdict =
      if t.isNaN then "aborted"
      else
        noise match
          case None => "reference"
          case Some(n) =>
            if t > 10.0 && t > 5.0 * n then "leak" else "no leak detected"
    println(f"  $name%-52s max|t| = ${if t.isNaN then "    -" else f"$t%6.1f"}  $verdict")

  /** A measured noise floor above this is environmental contamination, not signal: clean floors
    * were observed at or below ~54 (audit sandbox 18–54, rutherford 45) while contaminated
    * repetitions read 9,000 to 2e14 (docs/performance.md section 32). Verdicts are noise-relative,
    * so a verdict computed against a contaminated floor is meaningless — discard and retry instead.
    */
  private val ContaminationBound = 1000.0

  def main(args: Array[String]): Unit =
    val samples = args.lift(0).flatMap(_.toIntOption).getOrElse(20000)
    val buckets = args.lift(1).flatMap(_.toIntOption).getOrElse(100)
    val repetitions = args.lift(2).flatMap(_.toIntOption).getOrElse(2)
    if samples < 1000 || buckets < 2 || buckets > samples / 2 || repetitions < 1 || repetitions > 10
    then
      println(
        "usage: TimingProbeMain [samples [buckets [repetitions]]]\n" +
          "  samples >= 1000 (default 20000); 2 <= buckets <= samples/2 (default 100);\n" +
          "  1 <= repetitions <= 10 (default 2)."
      )
      sys.exit(2)

    val random = new SecureRandom
    val inputs = freshInputs(samples, random)
    val fixedScalarBytes = Array.fill(32)(0x33.toByte)
    val fixedScalar = Scalar.fromBytesModOrder(fixedScalarBytes)
    val fixedPoint =
      Constants.ED25519_BASEPOINT_TABLE.multiply(Scalar.fromBytesModOrder(inputs(0).bytes))

    val noiseFix: Op = _ =>
      outcome(
        Constants.ED25519_BASEPOINT_TABLE.multiply(fixedScalar).compress().toByteArray.apply(0)
      )
    val noiseRnd: Op = i =>
      outcome(
        Constants.ED25519_BASEPOINT_TABLE.multiply(inputs(i).scalar).compress().toByteArray.apply(0)
      )
    val pointMulFix: Op = _ =>
      outcome(fixedPoint.multiply(fixedScalar).compress().toByteArray.apply(0))
    val pointMulRnd: Op = i =>
      outcome(fixedPoint.multiply(inputs(i).scalar).compress().toByteArray.apply(0))
    val javaMulFix: Op = i =>
      outcome(
        Draft03Math.uniform(inputs(i).bytes).scalarMultiply(fixedScalarBytes).encode().apply(0)
      )
    val javaMulRnd: Op = i =>
      outcome(
        Draft03Math.uniform(inputs(i).bytes).scalarMultiply(inputs(i).scalarBytes).encode().apply(0)
      )
    val uniformFix: Op = _ =>
      outcome(Draft03Math.uniform(Array.fill(32)(0x42.toByte)).encode().apply(0))
    val uniformRnd: Op = i => outcome(Draft03Math.uniform(inputs(i).bytes).encode().apply(0))

    var aborted = false
    var discarded = 0
    var rep = 1
    while rep <= repetitions && !aborted do
      println(s"--- repetition $rep (samples=$samples, buckets=$buckets) ---")
      var floor = Double.NaN
      var attempts = 0
      while floor.isNaN && attempts < 2 && !aborted do
        attempts += 1
        if attempts > 1 then println("  retrying the noise control after a contaminated floor")
        System.gc()
        val noise = run(noiseFix, noiseRnd, inputs, samples, buckets)
        if noise.failures > 0 then
          println(s"aborting: ${noise.failures} failed operations in the noise control")
          aborted = true
        else if noise.t <= ContaminationBound then floor = noise.t
        else
          println(
            f"  contaminated noise floor: max|t| = ${noise.t}%.0f exceeds the contamination bound ($ContaminationBound%.0f); discarding this attempt"
          )
      if !aborted && floor.isNaN then
        discarded += 1
        println("  repetition discarded: the environment is too noisy for a meaningful verdict")
      if !aborted && !floor.isNaN then
        row("noise: basepoint table multiply", floor, None)
        System.gc()
        val point = run(pointMulFix, pointMulRnd, inputs, samples, buckets)
        if point.failures > 0 then
          println(s"aborting: ${point.failures} failed operations in point.multiply")
          aborted = true
        else
          row("curve25519 point.multiply (secret scalar)", point.t, Some(floor))
          System.gc()
          val java = run(javaMulFix, javaMulRnd, inputs, samples, buckets)
          if java.failures > 0 then
            println(s"aborting: ${java.failures} failed operations in scalarMultiply")
            aborted = true
          else
            row("Ed25519Point.scalarMultiply (secret scalar)", java.t, Some(floor))
            System.gc()
            val uni = run(uniformFix, uniformRnd, inputs, samples, buckets)
            if uni.failures > 0 then
              println(s"aborting: ${uni.failures} failed operations in uniform")
              aborted = true
            else row("Draft03Math.uniform (public input)", uni.t, Some(floor))
      rep += 1
    if discarded > 0 then
      println(
        s"$discarded of $repetitions repetition(s) contaminated and discarded — re-run on a quieter machine"
      )
    if aborted then sys.exit(1)
