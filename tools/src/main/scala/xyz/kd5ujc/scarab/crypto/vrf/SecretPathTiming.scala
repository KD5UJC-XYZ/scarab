// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.vrf

import com.weavechain.curve25519.{Constants, EdwardsPoint, Scalar}
import io.circe.Json
import io.circe.syntax.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.security.SecureRandom
import java.util.UUID
import org.bouncycastle.math.ec.rfc7748.X25519Field
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning.SigningKey
import xyz.kd5ujc.scarab.crypto.vrf.Draft03Prover.Proved
import xyz.kd5ujc.scarab.crypto.vrf.secret.Draft03Key

/** A dudect-style timing test of the secret paths of VRF proving and KES signing (#1418): for each
  * operation, two classes of secret input, one fixed and one random, measured interleaved in a
  * random order, and Welch's t of the two classes' times, on all measurements and on each of 100
  * crops that keep the measurements below a percentile (Reparaz, Balasch and Verbauwhede, "Dude, is
  * my code constant time?", DATE 2017, and the `dudect` program's percentiles). A program run by
  * hand and by the scheduled workflow `secret-timing.yml` (TOOL-1), never a test: the numbers
  * depend on the machine and its load.
  *
  * The operations: `prove` and `output` of `secret.Draft03Key` (the secret is the key, its seed;
  * the public input H is the same for both classes at each measurement); `sign` of a Sum6 KES key
  * at period 0, and two of its evolutions, from period 0 to 1 (the depth-0 case: the leaf's seed
  * erased and the next one copied into place, no hashing and no curve arithmetic) and from period 1
  * to 2 (a depth-1 subtree generated from a stored seed: two Blake2b-256 expansions and two leaves'
  * public keys) (`Sum6KesSigning`; the secret is the key's seed, the message the same for both
  * classes at each measurement, and an evolution moves a copy of the key made, untimed, just before
  * it); and the two constant-time multiplications they make, `[k]H` and `[k]B` by radix-16 digits
  * (the secret is the scalar; the fixed class is zero, whose digits select the identity from every
  * table, and then a scalar drawn at random once, a secret like any other, so that an effect of the
  * extreme value can be told from one of the secret's value in general). Every input is chosen
  * before the timing starts. Each class draws its inputs from a pool of 1024 entries of its own
  * (the fixed pool holds copies of the fixed value), the i-th measurement the entry i mod 1024; the
  * two pools of a target are made in one loop, an entry of each in turn, so that they lie in memory
  * alike, and an array input is copied, untimed, into the one buffer that every measurement of its
  * target then passes. The two classes run the same code over the same memory and differ only in
  * their values. Every result is consumed after the second clock read, all of its bytes folded into
  * a sink printed at the end (an evolution's, the evolved key's bytes), so that no work is dead.
  *
  * Three controls frame them: `random/random`, the `[k]H` multiplication with one pool of random
  * scalars, the i-th measurement taking the same entry whichever its class, whose t estimates what
  * this machine reports when there is nothing to find whatever the multiplication does; straight-
  * line field arithmetic (Bouncy Castle's in place: squarings and multiplications, no table, no
  * selection, no allocation) with an element drawn once against random ones, which estimates what
  * the machine does with a repeated value against changing ones; and the variable-time
  * double-and-add of `Ed25519Point.scalarMultiply` with a fixed scalar of one bit against random
  * scalars, which leaks by construction and shows the test can see a leak here.
  *
  * The statistic. Each target times a calibration batch of a tenth of its measurements first, in
  * the same random order, and takes the crops' cutoffs from it alone; that batch is then discarded
  * (dudect's first batch), so that the cutoffs are not estimated on the measurements they crop. A
  * crop counts only when both classes hold at least [[MinCropClass]] of the scored measurements
  * below its cutoff. A target's statistic is the larger of |t| on all scored measurements and the
  * largest |t| over the crops that count. Welch's t of two classes with no spread at all is 0 when
  * their means are equal and infinite when they differ. Above 10 is dudect's "probably not constant
  * time", above 500 "definitely not"; below 10 it is "no leak found with this many measurements on
  * this machine", a bounded negative observation. The largest of 101 statistics is a heuristic,
  * with no significance level corrected for taking the largest.
  *
  * The controls say how far the run can be read: the run is not conclusive when the random/random
  * control is above 10 (the machine reports a difference where there is none), when the
  * field-arithmetic control is above 10 (a value repeated against changing ones differs here by
  * itself), or when the variable-time control is not above 10 (the run could not see a leak that is
  * there). The program prints that assessment; how runs are combined into a verdict is
  * `scripts/secret_timing.py` 's, which recomputes it from the statistics
  * (docs/running/secret-timing.md).
  *
  * A diagnostic, never a verdict: for each class, every pool entry's fastest measurement, and the
  * entry whose fastest is the slowest, printed when it is at least [[SlowEntryFactor]] times the
  * median of all entries' fastest. A delay confined to rare secrets lands in a few measurements,
  * which the crops remove and the mean hardly moves; an entry that is slow every time it is
  * measured shows here, if it is in the pool. It cannot show constant time: the JIT compiles what
  * it measures and may compile it differently on another JVM, flags or input history; the timer
  * resolves tens of nanoseconds; collections and other threads add noise that cropping only partly
  * removes; and a leak smaller than the noise at this sample size, one confined to secrets the
  * pools do not hold, or one through the cache that this machine's timing does not expose, is not
  * seen.
  *
  * Run in the JDK image, on an idle machine, in a JVM of its own:
  * `java -XX:+UseSerialGC -Xbatch -cp <tools runtime classpath> xyz.kd5ujc.scarab.crypto.vrf.SecretPathTiming [measurements [report]]`
  * (docs/performance/mainnet-follower.md, section 67, has the protocol and a run). With `report`, a
  * path, the results are also written there as JSON, with an identity of the run drawn at random,
  * which `scripts/secret_timing.py` reads. It exits 0 once it has measured every target, whatever
  * it found; an operation that fails ends it with an exception.
  */
object SecretPathTiming:
  private val Crops = 100

  /** dudect's thresholds on |t|, its `t_threshold_moderate` and `t_threshold_bananas`. */
  private val Probably = 10.0
  private val Definitely = 500.0

  /** The version of the report and of its roster of targets, which `scripts/secret_timing.py` holds
    * too.
    */
  private val ReportVersion = 2

  /** The entries of each class's pool. */
  private val Pool = 1024

  /** The calibration batch is this fraction of a target's measurements. */
  private val CalibrationShare = 10

  /** A crop counts only when each class holds this many measurements below its cutoff. dudect waits
    * for 10,000; that would leave a target of 25,000 measurements (about 12,500 a class) only its
    * crops above the 80th percentile. At 1,000 a class the t of a crop, whose times are bounded by
    * its cutoff, is close to normal, and such a target keeps its crops from about the 13th
    * percentile up; a target of 100,000 keeps every crop.
    */
  private val MinCropClass = 1000

  /** The diagnostic's factor, and the measurements an entry needs before its fastest counts. */
  private val SlowEntryFactor = 2.0
  private val MinEntrySamples = 5

  /** The bytes of a message a KES key signs: about a Praos header body's. */
  private val MessageBytes = 256

  /** What a target is for: a secret path, or a control that says how far the run can be read. */
  private enum Role(val label: String):
    /** A secret path. */
    case Secret extends Role("secret")

    /** Both classes from one pool: above [[Probably]], the machine reports a difference where none
      * is.
      */
    case NoiseControl extends Role("noise-control")

    /** Straight-line arithmetic, a value drawn once against random ones: above [[Probably]], a
      * value repeated against changing ones differs on this machine by itself.
      */
    case ValueControl extends Role("value-control")

    /** A variable-time multiplication: not above [[Probably]], the run cannot see a leak that is
      * there by construction.
      */
    case LeakControl extends Role("leak-control")

  /** One operation and its two classes of input, the i-th of a pool of each: `stage` makes,
    * untimed, what the timed `op` is given, and `digest` folds, after the second clock read, all of
    * what `op` produced into the sink.
    */
  private final case class Target[A, R](
      id: String,
      name: String,
      role: Role,
      measurements: Int,
      fixed: Int => A,
      random: Int => A,
      stage: A => A,
      op: A => R,
      digest: R => Int
  )

  /** Welch's t of two samples, by their counts, means and variances: 0 for a sample of fewer than
    * two, and for two with no spread and equal means; infinite, with the sign of the difference,
    * for two with no spread and different means.
    */
  private def welch(a: Stats, b: Stats): Double =
    if a.n < 2 || b.n < 2 then 0.0
    else
      val se = math.sqrt(a.variance / a.n + b.variance / b.n)
      val difference = a.mean - b.mean
      if se > 0.0 then difference / se
      else if difference == 0.0 then 0.0
      else math.signum(difference) * Double.PositiveInfinity

  private final case class Stats(n: Long, mean: Double, m2: Double, max: Long):
    def variance: Double = if n < 2 then 0.0 else m2 / (n - 1)
    // Welford's update
    def add(x: Long): Stats =
      val n1 = n + 1
      val delta = x - mean
      val mean1 = mean + delta / n1
      Stats(n1, mean1, m2 + delta * (x - mean1), math.max(max, x))

  private val empty = Stats(0, 0.0, 0.0, 0L)

  /** The pool entry, of one class, whose fastest measurement is the slowest, against the median of
    * every entry's fastest.
    */
  private final case class SlowEntry(
      randomClass: Boolean,
      entry: Int,
      samples: Int,
      fastest: Long,
      medianFastest: Long
  ):
    def ratio: Double = if medianFastest > 0 then fastest.toDouble / medianFastest else 0.0
    def flagged: Boolean = ratio >= SlowEntryFactor

  /** The result of one target: its classes over the scored measurements, t on all of them, the
    * largest |t| over the crops that count with the crop it was found at (-1 when none counts), the
    * crops that count, the slowest entry, and the sink.
    */
  private final case class Result(
      fixed: Stats,
      random: Stats,
      calibration: Int,
      t: Double,
      maxT: Double,
      atCrop: Int,
      cropsCounted: Int,
      slowest: Option[SlowEntry],
      sink: Int
  ):
    /** What the verdict is on: the larger of |t| on all measurements and the crops' largest. */
    def statistic: Double = math.max(math.abs(t), maxT)

  /** Why a run is not conclusive: a control, by its name, that read what it must not. */
  private enum Flaw:
    case Noisy(name: String, statistic: Double)
    case ValueEffect(name: String, statistic: Double)
    case Blind(name: String, statistic: Double)

    def message: String = this match
      case Noisy(name, s) =>
        f"$name reads $s%.1f, above $Probably%.0f: this machine reports a difference where there is none"
      case ValueEffect(name, s) =>
        f"$name reads $s%.1f, above $Probably%.0f: a value repeated against changing ones differs here by itself"
      case Blind(name, s) =>
        f"$name reads $s%.1f, not above $Probably%.0f: this run could not see a leak that is there by construction"

  /** The controls that read what they must not, in the order measured; none in a conclusive run. */
  private def flaws(measured: Vector[(Target[?, ?], Result)]): Vector[Flaw] =
    measured.flatMap { (target, r) =>
      target.role match
        case Role.NoiseControl if r.statistic > Probably =>
          Some(Flaw.Noisy(target.name, r.statistic))
        case Role.ValueControl if r.statistic > Probably =>
          Some(Flaw.ValueEffect(target.name, r.statistic))
        case Role.LeakControl if !(r.statistic > Probably) =>
          Some(Flaw.Blind(target.name, r.statistic))
        case _ => None
    }

  /** Every byte of `bytes` folded into an int, so that none of them is dead. */
  private def folded(bytes: Array[Byte]): Int =
    bytes.foldLeft(0)((acc, b) => Integer.rotateLeft(acc, 1) ^ (b & 0xff))

  /** Measure `target`: warm both classes, time a calibration batch and then `measurements` calls,
    * each of a class drawn at random, and compute the statistics on the second batch with the
    * crops' cutoffs of the first.
    */
  private def measure[A, R](target: Target[A, R], random: SecureRandom): Result =
    val warm = math.min(target.measurements, 5000)
    val warmSink = (0 until warm).foldLeft(0) { (acc, i) =>
      acc ^ target.digest(target.op(target.stage(target.fixed(i)))) ^
        target.digest(target.op(target.stage(target.random(i))))
    }
    val calibration = math.max(Crops, target.measurements / CalibrationShare)
    val total = calibration + target.measurements
    val classes = Array.fill(total)(random.nextBoolean())
    // each measurement's input, chosen by its class before any timing: the two classes then run
    // the same code, the one call timed, and differ only in their data
    val inputs =
      Vector.tabulate(total)(i => if classes(i) then target.random(i) else target.fixed(i))
    val times = new Array[Long](total)
    // the timed region (FUN-2 (e), TOOL-2): the clock read around one call, into an array made
    // before it; the result is folded into the sink after the second read, so the call stays live
    @scala.annotation.tailrec
    def timed(i: Int, sink: Int): Int =
      if i >= total then sink
      else
        val input = target.stage(inputs(i))
        val start = System.nanoTime()
        val result = target.op(input)
        times(i) = System.nanoTime() - start
        timed(i + 1, Integer.rotateLeft(sink, 1) ^ target.digest(result))
    val sink = timed(0, warmSink)
    // dudect's crops, keeping the measurements below the percentile 1 - 0.5^(10 (c + 1) / 100) of
    // the calibration batch, which is then discarded
    val calibrated = times.take(calibration).sorted
    val cutoffs = (0 until Crops).map { c =>
      val p = 1.0 - math.pow(0.5, 10.0 * (c + 1) / Crops)
      calibrated(math.min(calibrated.length - 1, (p * calibrated.length).toInt))
    }
    val scored = calibration until total
    def statsBelow(limit: Long): (Stats, Stats) =
      scored.foldLeft((empty, empty)) { case ((f, r), i) =>
        if times(i) >= limit then (f, r)
        else if classes(i) then (f, r.add(times(i)))
        else (f.add(times(i)), r)
      }
    val (allFixed, allRandom) = statsBelow(Long.MaxValue)
    val counted = cutoffs.zipWithIndex.flatMap { (limit, c) =>
      val (f, r) = statsBelow(limit)
      Option.when(f.n >= MinCropClass && r.n >= MinCropClass)((math.abs(welch(f, r)), c))
    }
    val (maxT, atCrop) = if counted.isEmpty then (0.0, -1) else counted.maxBy(_._1)
    Result(
      allFixed,
      allRandom,
      calibration,
      welch(allFixed, allRandom),
      maxT,
      atCrop,
      counted.size,
      slowestEntry(scored, classes, times),
      sink
    )

  /** The diagnostic: every pool entry's fastest scored measurement, by class, among the entries
    * measured at least [[MinEntrySamples]] times, and the slowest of them against their median.
    */
  private def slowestEntry(
      scored: Range,
      classes: Array[Boolean],
      times: Array[Long]
  ): Option[SlowEntry] =
    val fastest = scored
      .groupMapReduce(i => (classes(i), i % Pool))(i => (times(i), 1)) { (a, b) =>
        (math.min(a._1, b._1), a._2 + b._2)
      }
      .filter(_._2._2 >= MinEntrySamples)
    Option.when(fastest.nonEmpty) {
      val sorted = fastest.values.map(_._1).toVector.sorted
      val ((randomClass, entry), (time, samples)) = fastest.maxBy(_._2._1)
      SlowEntry(randomClass, entry, samples, time, sorted(sorted.size / 2))
    }

  private def verdict(t: Double): String =
    if t > Definitely then "definitely not constant time"
    else if t > Probably then "probably not constant time"
    else "no leak found at this size"

  /** A statistic as JSON: a number, or `Infinity` and `-Infinity` as strings. */
  private def number(x: Double): Json =
    if x.isFinite then x.asJson
    else if x > 0 then Json.fromString("Infinity")
    else if x < 0 then Json.fromString("-Infinity")
    else Json.fromString("NaN")

  /** The run as JSON: its identity, sizes and thresholds, every target's classes, statistics and
    * diagnostic, whether the run is conclusive and why not.
    */
  private def report(
      run: UUID,
      n: Int,
      proofs: Int,
      measured: Vector[(Target[?, ?], Result)],
      found: Vector[Flaw],
      sink: Int
  ): Json =
    def side(s: Stats): Json =
      Json.obj("n" -> s.n.asJson, "mean_ns" -> s.mean.asJson, "max_ns" -> s.max.asJson)
    def slow(e: SlowEntry): Json = Json.obj(
      "class" -> (if e.randomClass then "random" else "fixed").asJson,
      "entry" -> e.entry.asJson,
      "samples" -> e.samples.asJson,
      "fastest_ns" -> e.fastest.asJson,
      "median_fastest_ns" -> e.medianFastest.asJson,
      "ratio" -> e.ratio.asJson,
      "flagged" -> e.flagged.asJson
    )
    Json.obj(
      "tool" -> "SecretPathTiming".asJson,
      "version" -> ReportVersion.asJson,
      "run_id" -> run.toString.asJson,
      "measurements" -> n.asJson,
      "proofs" -> proofs.asJson,
      "calibration_share" -> CalibrationShare.asJson,
      "pool" -> Pool.asJson,
      "crops" -> Crops.asJson,
      "min_crop_class" -> MinCropClass.asJson,
      "probably" -> Probably.asJson,
      "definitely" -> Definitely.asJson,
      "targets" -> measured.map { (target, r) =>
        Json.obj(
          "id" -> target.id.asJson,
          "name" -> target.name.asJson,
          "role" -> target.role.label.asJson,
          "measurements" -> target.measurements.asJson,
          "calibration" -> r.calibration.asJson,
          "fixed" -> side(r.fixed),
          "random" -> side(r.random),
          "t_all" -> number(r.t),
          "max_t_crops" -> number(r.maxT),
          "at_crop" -> r.atCrop.asJson,
          "crops_counted" -> r.cropsCounted.asJson,
          "statistic" -> number(r.statistic),
          "over" -> (r.statistic > Probably).asJson,
          "slowest_entry" -> r.slowest.map(slow).asJson
        )
      }.asJson,
      "conclusive" -> found.isEmpty.asJson,
      "flaws" -> found.map(_.message).asJson,
      "sink" -> f"0x$sink%08x".asJson
    )

  def main(args: Array[String]): Unit =
    val n = args.headOption.flatMap(_.toIntOption).getOrElse(100000)
    val reportPath = args.lift(1).map(Paths.get(_))
    val run = UUID.randomUUID()
    val random = new SecureRandom
    def bytes(k: Int): Array[Byte] =
      val out = new Array[Byte](k)
      random.nextBytes(out)
      out
    // inputs made before any measurement: a pool of each, indexed by measurement
    def below255(b: Array[Byte]): Array[Byte] =
      b(31) = (b(31) & 0x7f).toByte
      b
    def digitsOf(scalar: Array[Byte]): Array[Byte] =
      val e = new Array[Byte](64)
      Scalar.toRadix16(scalar, e)
      e
    val hPoints = Vector.fill(Pool)(
      Constants.ED25519_BASEPOINT_TABLE.multiply(Scalar.fromBits(below255(bytes(32))))
    )
    val hStrings = hPoints.map(p => Bytes.fromArray(p.compress().toByteArray))
    def key(seed: Array[Byte]): Draft03Key =
      Draft03Key.fromSeed(Bytes.fromArray(seed)).fold(e => sys.error(e.toString), identity)
    def kesKey(seed: Array[Byte]): SigningKey =
      Sum6KesSigning.generate(Bytes.fromArray(seed)).fold(e => sys.error(e.message), identity)
    // a copy of `k` at the next period, which the pool keeps: each measurement moves a copy of it
    def evolvedOnce(k: SigningKey): SigningKey =
      val moved = k.copy
      moved.evolve().fold(e => sys.error(e.message), _ => moved)
    // the fixed and the random pool of a target, made in one loop, an entry of each in turn
    def paired[A](fixed: => A, random: => A): (Vector[A], Vector[A]) =
      Vector.fill(Pool)((fixed, random)).unzip
    // an array input copied into the target's one buffer, which every measurement then passes
    def staged(size: Int): Array[Byte] => Array[Byte] =
      val buffer = new Array[Byte](size)
      input =>
        System.arraycopy(input, 0, buffer, 0, size)
        buffer
    val (fixedKeys, randomKeys) = paired(key(new Array[Byte](32)), key(bytes(32)))
    val (fixedKes, randomKes) = paired(kesKey(new Array[Byte](32)), kesKey(bytes(32)))
    val (fixedKesNext, randomKesNext) =
      fixedKes.zip(randomKes).map((f, r) => (evolvedOnce(f), evolvedOnce(r))).unzip
    val messages = Vector.fill(Pool)(Bytes.fromArray(bytes(MessageBytes)))
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
      (keys(i % Pool), hStrings(i % Pool))
    // a KES key and the message of the measurement: the same message for both classes at each index
    def withMessage(keys: Vector[SigningKey])(i: Int): (SigningKey, Bytes) =
      (keys(i % Pool), messages(i % Pool))
    def proved(input: (Draft03Key, Bytes)): Proved =
      input._1.prove(input._2).fold(e => sys.error(e.toString), identity)
    def output(input: (Draft03Key, Bytes)): Bytes =
      input._1.output(input._2).fold(e => sys.error(e.toString), identity)
    def kesSigned(input: (SigningKey, Bytes)): Bytes =
      input._1.sign(input._2).fold(e => sys.error(e.message), identity)
    def kesEvolved(k: SigningKey): SigningKey =
      k.evolve().fold(e => sys.error(e.message), _ => k)
    // the evolved key whole, its period with it
    def evolvedKey(k: SigningKey): Int = folded(k.toBytes.toArray) ^ k.period
    def times(e: Array[Byte]): Array[Byte] = h0.multiplyRadix16(e).compress().toByteArray
    def base(e: Array[Byte]): Array[Byte] =
      Constants.ED25519_BASEPOINT_TABLE.multiplyRadix16(e).compress().toByteArray
    def control(s: Array[Byte]): Array[Byte] = controlPoint.scalarMultiply(s).encode()
    // straight-line field arithmetic in place, no table, no selection and no allocation: 1,000
    // squarings and multiplications of Bouncy Castle's X25519Field
    def field(b: Array[Byte]): Array[Int] =
      val x0 = X25519Field.create()
      X25519Field.decode255(b, x0)
      val x = X25519Field.create()
      X25519Field.copy(x0, 0, x, 0)
      (0 until 1000).foreach { _ =>
        X25519Field.sqr(x, x)
        X25519Field.mul(x, x0, x)
      }
      x
    def limbs(x: Array[Int]): Int = x.foldLeft(0)((acc, v) => Integer.rotateLeft(acc, 1) ^ v)
    def proof(p: Proved): Int = folded(p.proof.toArray) ^ folded(p.output.toArray)
    def encoded(b: Bytes): Int = folded(b.toArray)
    def from[A](pooled: Vector[A])(i: Int): A = pooled(i % Pool)
    val proofs = math.max(1000, n / 4)
    val targets: Vector[Target[?, ?]] = Vector(
      Target(
        "vrf-prove",
        "prove (secret: the key)",
        Role.Secret,
        proofs,
        withH(fixedKeys),
        withH(randomKeys),
        identity,
        proved,
        proof
      ),
      Target(
        "vrf-output",
        "output (secret: the key)",
        Role.Secret,
        proofs,
        withH(fixedKeys),
        withH(randomKeys),
        identity,
        output,
        encoded
      ),
      Target(
        "kes-sign",
        "KES sign, period 0 (secret: the key)",
        Role.Secret,
        proofs,
        withMessage(fixedKes),
        withMessage(randomKes),
        identity,
        kesSigned,
        encoded
      ),
      Target(
        "kes-evolve-0-1",
        "KES evolve, period 0 to 1 (secret: the key)",
        Role.Secret,
        proofs,
        from(fixedKes),
        from(randomKes),
        _.copy,
        kesEvolved,
        evolvedKey
      ),
      Target(
        "kes-evolve-1-2",
        "KES evolve, period 1 to 2 (secret: the key)",
        Role.Secret,
        proofs,
        from(fixedKesNext),
        from(randomKesNext),
        _.copy,
        kesEvolved,
        evolvedKey
      ),
      Target(
        "kh-zero",
        "[k]H, zero/random (secret: k)",
        Role.Secret,
        n,
        from(zeroDigits),
        from(randomDigits),
        staged(64),
        times,
        folded
      ),
      Target(
        "kb-zero",
        "[k]B, zero/random (secret: k)",
        Role.Secret,
        n,
        from(zeroDigits),
        from(randomDigits),
        staged(64),
        base,
        folded
      ),
      Target(
        "kh-once",
        "[k]H, k drawn once/random",
        Role.Secret,
        n,
        from(typicalDigits),
        from(otherDigits),
        staged(64),
        times,
        folded
      ),
      Target(
        "kb-once",
        "[k]B, k drawn once/random",
        Role.Secret,
        n,
        from(typicalDigits),
        from(otherDigits),
        staged(64),
        base,
        folded
      ),
      Target(
        "control-random",
        "control: [k]H, random/random",
        Role.NoiseControl,
        n,
        from(otherDigits),
        from(otherDigits),
        staged(64),
        times,
        folded
      ),
      Target(
        "control-field",
        "control: field arithmetic, drawn once/random",
        Role.ValueControl,
        n,
        from(fixedElements),
        from(randomElements),
        staged(32),
        field,
        limbs
      ),
      Target(
        "control-vartime",
        "control: Ed25519Point.scalarMultiply, one bit/random",
        Role.LeakControl,
        n,
        from(oneBit),
        from(randomScalars),
        staged(32),
        control,
        folded
      )
    )
    println(
      s"SecretPathTiming $run: $n measurements a multiplication, $proofs a proof, output, signature or evolution, each after a calibration batch of a tenth; $Crops crops, each counted from $MinCropClass a class"
    )
    println(
      f"${"operation"}%-52s ${"fixed"}%8s ${"random"}%8s ${"ns fixed"}%10s ${"ns random"}%10s ${"t all"}%8s ${"max |t|"}%8s ${"crop"}%5s ${"crops"}%5s  verdict"
    )
    val measured: Vector[(Target[?, ?], Result)] = targets.map { target =>
      System.gc()
      val r = measure(target, random)
      println(
        f"${target.name}%-52s ${r.fixed.n}%8d ${r.random.n}%8d ${r.fixed.mean}%10.0f ${r.random.mean}%10.0f ${r.t}%8.2f ${r.maxT}%8.2f ${r.atCrop}%5d ${r.cropsCounted}%5d  ${verdict(r.statistic)}"
      )
      (target, r)
    }
    val sink = measured.foldLeft(0)(_ ^ _._2.sink)
    println(f"sink 0x$sink%08x")
    measured.foreach { (target, r) =>
      r.slowest.filter(_.flagged).foreach { e =>
        println(
          f"diagnostic, not a verdict: ${target.name}: ${
              if e.randomClass then "random" else "fixed"
            } entry ${e.entry}'s fastest of ${e.samples} is ${e.fastest}%d ns, ${e.ratio}%.1f times the median entry's fastest (${e.medianFastest}%d ns)"
        )
      }
    }
    val found = flaws(measured)
    val over = measured.collect {
      case (target, r) if target.role == Role.Secret && r.statistic > Probably => target.name
    }
    println(
      if found.nonEmpty then s"run: not conclusive: ${found.map(_.message).mkString("; ")}"
      else if over.nonEmpty then
        f"run: above $Probably%.0f in this run, to be repeated before it is a finding: ${over.mkString(", ")}"
      else f"run: no secret target above $Probably%.0f"
    )
    reportPath.foreach(path =>
      Files.writeString(
        path,
        report(run, n, proofs, measured, found, sink).spaces2,
        StandardCharsets.UTF_8
      )
    )
