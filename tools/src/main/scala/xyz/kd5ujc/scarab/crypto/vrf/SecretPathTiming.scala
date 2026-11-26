// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.vrf

import com.weavechain.curve25519.{Constants, EdwardsPoint, Scalar}
import java.security.SecureRandom
import org.bouncycastle.math.ec.rfc7748.X25519Field
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.vrf.secret.Draft03Key

/** A dudect-style timing test of the VRF prover's secret path (#1418): for each operation, two
  * classes of secret input, one fixed and one random, measured interleaved in a random order, and
  * Welch's t of the two classes' times, on all measurements and on each of 100 crops that keep the
  * measurements below a percentile (Reparaz, Balasch and Verbauwhede, "Dude, is my code constant
  * time?", DATE 2017, and the `dudect` program's percentiles). A program run by hand (TOOL-1),
  * never a test: the numbers depend on the machine and its load.
  *
  * The operations: `prove` and `output` of `secret.Draft03Key` (the secret is the key, its seed;
  * the public input H is the same for both classes at each measurement), and the two constant-time
  * multiplications they make, `[k]H` and `[k]B` by radix-16 digits (the secret is the scalar; the
  * fixed class is zero, whose digits select the identity from every table, and then a scalar drawn
  * at random once, a secret like any other, so that an effect of the extreme value can be told from
  * one of the secret's value in general). Every input is chosen before the timing starts. Each
  * class draws its inputs from a pool of 1024 entries of its own (the fixed pool holds copies of
  * the fixed value); the two pools of a target are made in one loop, an entry of each in turn, so
  * that they lie in memory alike, and an array input is copied, untimed, into the one buffer that
  * every measurement of its target then passes. The two classes run the same code over the same
  * memory and differ only in their values. Three controls frame them: `random/random`, the `[k]H`
  * multiplication with both classes drawn at random, whose t estimates what this machine reports
  * when there is nothing to find; straight-line field arithmetic (Bouncy Castle's in place:
  * squarings and multiplications, no table, no selection, no allocation) with an element drawn once
  * against random ones, which estimates what the machine does with a repeated value against
  * changing ones; and the variable-time double-and-add of `Ed25519Point.scalarMultiply` with a
  * fixed scalar of one bit against random scalars, which leaks by construction and shows the test
  * can see a leak here.
  *
  * How to read it: the verdict is on the larger of |t| on all measurements and the largest |t| over
  * the crops. Above 10 is dudect's "probably not constant time", above 500 "definitely not"; below
  * 10 it is "no leak found with this many measurements on this machine", which is a bounded
  * negative observation. It cannot show constant time: the JIT compiles what it measures and may
  * compile it differently on another JVM, flags or input history; the timer resolves tens of
  * nanoseconds; collections and other threads add noise that cropping only partly removes; and a
  * leak smaller than the noise at this sample size, or one through the cache that this machine's
  * timing does not expose, is not seen. Compare each t with the `random/random` control's.
  *
  * Run in the JDK image, on an idle machine, in a JVM of its own:
  * `java -XX:+UseSerialGC -Xbatch -cp <tools runtime classpath> xyz.kd5ujc.scarab.crypto.vrf.SecretPathTiming [measurements]`
  * (docs/performance/mainnet-follower.md, section 67, has the protocol and a run).
  */
object SecretPathTiming:
  private val Crops = 100

  /** One operation and its two classes of input, the i-th of a pool of each: `stage` makes,
    * untimed, what the timed `op` is given, and the operation's result is a byte the sink keeps.
    */
  private final case class Target[A](
      name: String,
      measurements: Int,
      fixed: Int => A,
      random: Int => A,
      stage: A => A,
      op: A => Byte
  )

  /** Welch's t of two samples, by their counts, means and variances. */
  private def welch(a: Stats, b: Stats): Double =
    if a.n < 2 || b.n < 2 then 0.0
    else
      val se = math.sqrt(a.variance / a.n + b.variance / b.n)
      if se == 0.0 then 0.0 else (a.mean - b.mean) / se

  private final case class Stats(n: Long, mean: Double, m2: Double):
    def variance: Double = if n < 2 then 0.0 else m2 / (n - 1)
    // Welford's update
    def add(x: Double): Stats =
      val n1 = n + 1
      val delta = x - mean
      val mean1 = mean + delta / n1
      Stats(n1, mean1, m2 + delta * (x - mean1))

  private val empty = Stats(0, 0.0, 0.0)

  /** The result of one target: the counts and mean times of the classes, t on all measurements, and
    * the largest |t| over the crops with the crop it was found at.
    */
  private final case class Result(
      fixed: Stats,
      random: Stats,
      t: Double,
      maxT: Double,
      atCrop: Int,
      sink: Int
  )

  /** Measure `target`: warm both classes, then time `measurements` calls, each of a class drawn at
    * random, and compute the statistics.
    */
  private def measure[A](target: Target[A], random: SecureRandom): Result =
    val warm = math.min(target.measurements, 5000)
    val warmSink = (0 until warm).foldLeft(0) { (acc, i) =>
      acc ^ target.op(target.stage(target.fixed(i))) ^ target.op(target.stage(target.random(i)))
    }
    val classes = Array.fill(target.measurements)(random.nextBoolean())
    // each measurement's input, chosen by its class before any timing: the two classes then run
    // the same code, the one call timed, and differ only in their data
    val inputs = Vector.tabulate(target.measurements)(i =>
      if classes(i) then target.random(i) else target.fixed(i)
    )
    val times = new Array[Long](target.measurements)
    // the timed region (FUN-2 (e), TOOL-2): the clock read around one call, into an array made
    // before it, with the result's byte folded into a sink printed afterwards so the call stays live
    @scala.annotation.tailrec
    def timed(i: Int, sink: Int): Int =
      if i >= target.measurements then sink
      else
        val input = target.stage(inputs(i))
        val start = System.nanoTime()
        val b = target.op(input)
        times(i) = System.nanoTime() - start
        timed(i + 1, Integer.rotateLeft(sink, 1) ^ (b & 0xff))
    val sink = timed(0, warmSink)
    val sorted = times.sorted
    // dudect's crops: keep the measurements below the percentile 1 - 0.5^(10 (i + 1) / 100)
    val thresholds = (0 until Crops).map { i =>
      val p = 1.0 - math.pow(0.5, 10.0 * (i + 1) / Crops)
      sorted(math.min(sorted.length - 1, (p * sorted.length).toInt))
    }
    def statsBelow(limit: Long): (Stats, Stats) =
      times.indices.foldLeft((empty, empty)) { case ((f, r), i) =>
        if times(i) >= limit then (f, r)
        else if classes(i) then (f, r.add(times(i).toDouble))
        else (f.add(times(i).toDouble), r)
      }
    val (allFixed, allRandom) = statsBelow(Long.MaxValue)
    val cropped = thresholds.zipWithIndex.map { (limit, i) =>
      val (f, r) = statsBelow(limit)
      (math.abs(welch(f, r)), i)
    }
    val (maxT, atCrop) = cropped.maxBy(_._1)
    Result(allFixed, allRandom, welch(allFixed, allRandom), maxT, atCrop, sink)

  private def verdict(t: Double): String =
    if t > 500 then "definitely not constant time"
    else if t > 10 then "probably not constant time"
    else "no leak found at this size"

  def main(args: Array[String]): Unit =
    val n = args.headOption.flatMap(_.toIntOption).getOrElse(100000)
    val random = new SecureRandom
    def bytes(k: Int): Array[Byte] =
      val out = new Array[Byte](k)
      random.nextBytes(out)
      out
    // inputs made before any measurement: a pool of each, indexed by measurement
    val pool = 1024
    def below255(b: Array[Byte]): Array[Byte] =
      b(31) = (b(31) & 0x7f).toByte
      b
    def digitsOf(scalar: Array[Byte]): Array[Byte] =
      val e = new Array[Byte](64)
      Scalar.toRadix16(scalar, e)
      e
    val hPoints = Vector.fill(pool)(
      Constants.ED25519_BASEPOINT_TABLE.multiply(Scalar.fromBits(below255(bytes(32))))
    )
    val hStrings = hPoints.map(p => Bytes.fromArray(p.compress().toByteArray))
    def key(seed: Array[Byte]): Draft03Key =
      Draft03Key.fromSeed(Bytes.fromArray(seed)).fold(e => sys.error(e.toString), identity)
    // the fixed and the random pool of a target, made in one loop, an entry of each in turn
    def paired[A](fixed: => A, random: => A): (Vector[A], Vector[A]) =
      Vector.fill(pool)((fixed, random)).unzip
    // an array input copied into the target's one buffer, which every measurement then passes
    def staged(size: Int): Array[Byte] => Array[Byte] =
      val buffer = new Array[Byte](size)
      input =>
        System.arraycopy(input, 0, buffer, 0, size)
        buffer
    val (fixedKeys, randomKeys) = paired(key(new Array[Byte](32)), key(bytes(32)))
    val drawnOnce = digitsOf(below255(bytes(32)))
    val oneBitScalar = Array.tabulate[Byte](32)(i => if i == 0 then 1 else 0)
    val (zeroDigits, randomDigits) = paired(new Array[Byte](64), digitsOf(below255(bytes(32))))
    val (typicalDigits, otherDigits) = paired(drawnOnce.clone(), digitsOf(below255(bytes(32))))
    val (oneBit, randomScalars) = paired(oneBitScalar.clone(), bytes(32))
    val elementOnce = bytes(32)
    val (fixedElements, randomElements) = paired(elementOnce.clone(), bytes(32))
    val h0: EdwardsPoint = hPoints(0)
    val controlPoint = Draft03Math.h2c(bytes(32), bytes(32))
    // a key and the H of the measurement: the same H for both classes at each index
    def withH(keys: Vector[Draft03Key])(i: Int): (Draft03Key, Bytes) =
      (keys(i % pool), hStrings(i % pool))
    def proved(input: (Draft03Key, Bytes)): Byte =
      input._1.prove(input._2).fold(e => sys.error(e.toString), _.proof.toArray(79))
    def output(input: (Draft03Key, Bytes)): Byte =
      input._1.output(input._2).fold(e => sys.error(e.toString), _.toArray(63))
    def times(e: Array[Byte]): Byte = h0.multiplyRadix16(e).compress().toByteArray()(0)
    def base(e: Array[Byte]): Byte =
      Constants.ED25519_BASEPOINT_TABLE.multiplyRadix16(e).compress().toByteArray()(0)
    def control(s: Array[Byte]): Byte = controlPoint.scalarMultiply(s).encode()(0)
    // straight-line field arithmetic in place, no table, no selection and no allocation: 1,000
    // squarings and multiplications of Bouncy Castle's X25519Field
    def field(b: Array[Byte]): Byte =
      val x0 = X25519Field.create()
      X25519Field.decode255(b, x0)
      val x = X25519Field.create()
      X25519Field.copy(x0, 0, x, 0)
      (0 until 1000).foreach { _ =>
        X25519Field.sqr(x, x)
        X25519Field.mul(x, x0, x)
      }
      x(0).toByte
    def from[A](pooled: Vector[A])(i: Int): A = pooled(i % pool)
    val proofs = math.max(1000, n / 4)
    val targets: Vector[Target[?]] = Vector(
      Target(
        "prove (secret: the key)",
        proofs,
        withH(fixedKeys),
        withH(randomKeys),
        identity,
        proved
      ),
      Target(
        "output (secret: the key)",
        proofs,
        withH(fixedKeys),
        withH(randomKeys),
        identity,
        output
      ),
      Target(
        "[k]H, zero/random (secret: k)",
        n,
        from(zeroDigits),
        from(randomDigits),
        staged(64),
        times
      ),
      Target(
        "[k]B, zero/random (secret: k)",
        n,
        from(zeroDigits),
        from(randomDigits),
        staged(64),
        base
      ),
      Target(
        "[k]H, k drawn once/random",
        n,
        from(typicalDigits),
        from(otherDigits),
        staged(64),
        times
      ),
      Target(
        "[k]B, k drawn once/random",
        n,
        from(typicalDigits),
        from(otherDigits),
        staged(64),
        base
      ),
      Target(
        "control: [k]H, random/random",
        n,
        from(otherDigits),
        from(randomDigits),
        staged(64),
        times
      ),
      Target(
        "control: field arithmetic, drawn once/random",
        n,
        from(fixedElements),
        from(randomElements),
        staged(32),
        field
      ),
      Target(
        "control: Ed25519Point.scalarMultiply, one bit/random",
        n,
        from(oneBit),
        from(randomScalars),
        staged(32),
        control
      )
    )
    println(
      s"SecretPathTiming: $n measurements a multiplication, $proofs a proof or output, $Crops crops"
    )
    println(
      f"${"operation"}%-52s ${"fixed"}%8s ${"random"}%8s ${"ns fixed"}%10s ${"ns random"}%10s ${"t all"}%8s ${"max |t|"}%8s ${"crop"}%5s  verdict"
    )
    val sinks = targets.map { target =>
      System.gc()
      val r = measure(target, random)
      println(
        f"${target.name}%-52s ${r.fixed.n}%8d ${r.random.n}%8d ${r.fixed.mean}%10.0f ${r.random.mean}%10.0f ${r.t}%8.2f ${r.maxT}%8.2f ${r.atCrop}%5d  ${verdict(math.max(math.abs(r.t), r.maxT))}"
      )
      r.sink
    }
    println(f"sink 0x${sinks.foldLeft(0)(_ ^ _)}%08x")
