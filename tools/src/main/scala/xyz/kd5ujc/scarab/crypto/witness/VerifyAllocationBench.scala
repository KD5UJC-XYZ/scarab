// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.witness

import java.lang.management.ManagementFactory
import java.security.SecureRandom
import org.bouncycastle.math.ec.rfc8032.Ed25519
import xyz.kd5ujc.scarab.codec.Bytes

/** Development tool: the bytes a thread allocates and the time it takes for one Ed25519
  * verification, through `StrictEd25519` and `ByronEd25519` and through the same verifiers as they
  * were, over the vendored curve25519-elisabeth objects (`VendoredVerifiers`, their `verify` copied
  * whole), in one JVM (#1418).
  *
  * `tools/runMain xyz.kd5ujc.scarab.crypto.witness.VerifyAllocationBench [rounds]`
  *
  * The inputs come from a fixed seed, 64 a workload: valid signatures over messages of 32 bytes and
  * of 1 KiB, and for the strict verifier signatures it refuses, each at another step: a
  * non-canonical key, a non-canonical R, S at or past L (S + L), and a valid signature over another
  * message, refused only by the equation. Every verdict is checked outside and inside the timed
  * region (each must be the workload's for both paths); another ends the run with status 1. After
  * the warm-up, the rounds alternate the two paths ABBA, each round verifying every signature of a
  * workload as often as fills about 250 ms; the median of the rounds is printed per verification.
  * Allocation is `com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes` around a round.
  */
object VerifyAllocationBench:
  private val bean = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]

  private type Input = (Bytes, Bytes, Bytes)

  private enum Path:
    case Strict, StrictVendored, Byron, ByronVendored

    def verify(key: Bytes, signature: Bytes, message: Bytes): Boolean = this match
      case Strict         => StrictEd25519.verify(key, signature, message) == Verified
      case StrictVendored => VendoredVerifiers.strictVerify(key, signature, message) == Verified
      case Byron          => ByronEd25519.verify(key, signature, message) == Verified
      case ByronVendored  => VendoredVerifiers.byronVerify(key, signature, message) == Verified

  private val Verified = Right(VerificationResult.SignatureVerified)

  /** Inputs that each path must accept, or refuse, and the paths compared on them. */
  private final case class Workload(
      name: String,
      inputs: Vector[Input],
      accepted: Boolean,
      pairs: Vector[(Path, Path)]
  )

  /** A round's time and allocation per verification, and how many verdicts were the workload's. */
  private final case class Sample(nsPerOp: Double, bytesPerOp: Double, expected: Long)

  private def run(path: Path, workload: Workload, passes: Int): Sample =
    val array = workload.inputs.toArray
    val before = bean.getCurrentThreadAllocatedBytes
    val t0 = System.nanoTime()
    var expected = 0L
    var pass = 0
    while pass < passes do
      var i = 0
      while i < array.length do
        val (k, s, m) = array(i)
        if path.verify(k, s, m) == workload.accepted then expected += 1
        i += 1
      pass += 1
    val elapsed = System.nanoTime() - t0
    val allocated = bean.getCurrentThreadAllocatedBytes - before
    val ops = passes.toDouble * array.length
    Sample(elapsed / ops, allocated / ops, expected)

  private def median(xs: Seq[Double]): Double =
    val s = xs.sorted
    if s.size % 2 == 1 then s(s.size / 2) else (s(s.size / 2 - 1) + s(s.size / 2)) / 2

  private def passesFor(path: Path, workload: Workload, millis: Long): Int =
    val one = run(path, workload, 1).nsPerOp * workload.inputs.size
    ((millis * 1e6) / one.max(1.0)).toInt.max(1)

  private def measure(workload: Workload, a: Path, b: Path, rounds: Int): Unit =
    val name = workload.name
    (0 until 8).foreach { _ =>
      run(a, workload, passesFor(a, workload, 50))
      run(b, workload, passesFor(b, workload, 50))
    }
    val passes = Map(a -> passesFor(a, workload, 250), b -> passesFor(b, workload, 250))
    val order = (0 until rounds).flatMap(r => if r % 2 == 0 then Vector(a, b) else Vector(b, a))
    val samples = order.map(path => path -> run(path, workload, passes(path))).groupMap(_._1)(_._2)
    def line(path: Path): Sample =
      val s = samples(path)
      if s.exists(_.expected != passes(path).toLong * workload.inputs.size) then
        println(s"UNEXPECTED verdict in a timed round: $name $path")
        sys.exit(1)
      val result =
        Sample(median(s.map(_.nsPerOp)), median(s.map(_.bytesPerOp)), s.map(_.expected).sum)
      println(
        f"RESULT $name%-22s $path%-15s ns/op ${result.nsPerOp}%10.0f (min ${s.map(_.nsPerOp).min}%10.0f max ${s
            .map(_.nsPerOp)
            .max}%10.0f)  B/op ${result.bytesPerOp}%10.0f (min ${s.map(_.bytesPerOp).min}%10.0f max ${s
            .map(_.bytesPerOp)
            .max}%10.0f)"
      )
      result
    val (x, y) = (line(a), line(b))
    println(
      f"RATIO  $name%-22s $b/$a time ${y.nsPerOp / x.nsPerOp}%.2f allocation ${y.bytesPerOp / x.bytesPerOp}%.1f"
    )

  def main(args: Array[String]): Unit =
    val rounds = args.headOption.flatMap(_.toIntOption).getOrElse(10)
    val random = SecureRandom.getInstance("SHA1PRNG")
    random.setSeed(1418L)
    def bytes(n: Int): Array[Byte] =
      val out = new Array[Byte](n)
      random.nextBytes(out)
      out
    def signed(messageSize: Int): Vector[(Array[Byte], Array[Byte], Array[Byte])] =
      Vector.fill(64) {
        val sk = bytes(32)
        val pk = new Array[Byte](32)
        Ed25519.generatePublicKey(sk, 0, pk, 0)
        val message = bytes(messageSize)
        val signature = new Array[Byte](64)
        Ed25519.sign(sk, 0, pk, 0, message, 0, message.length, signature, 0)
        (pk, signature, message)
      }
    def wrapped(inputs: Vector[(Array[Byte], Array[Byte], Array[Byte])]): Vector[Input] =
      inputs.map((k, s, m) => (Bytes.wrap(k), Bytes.wrap(s), Bytes.wrap(m)))
    val p = (BigInt(1) << 255) - 19
    val l = (BigInt(1) << 252) + BigInt("27742317777372353535851937790883648493")
    def le(v: BigInt): Array[Byte] = Array.tabulate(32)(i => ((v >> (8 * i)) & 255).toByte)
    def fromLe(b: Array[Byte]): BigInt = BigInt(1, b.reverse)
    // y = p + 2 .. p + 18 with the sign of x as drawn: never canonical
    def nonCanonical(): Array[Byte] =
      val e = le(p + 2 + random.nextInt(17))
      if random.nextBoolean() then e(31) = (e(31) | 0x80).toByte
      e
    val valid = signed(32)
    val both = Vector(Path.Strict -> Path.StrictVendored, Path.Byron -> Path.ByronVendored)
    val strictOnly = Vector(Path.Strict -> Path.StrictVendored)
    val workloads = Vector(
      Workload("message 32 B", wrapped(valid), accepted = true, both),
      Workload("message 1 KiB", wrapped(signed(1024)), accepted = true, both),
      Workload(
        "non-canonical key",
        wrapped(valid.map((_, s, m) => (nonCanonical(), s, m))),
        accepted = false,
        strictOnly
      ),
      Workload(
        "non-canonical R",
        wrapped(valid.map((k, s, m) => (k, nonCanonical() ++ s.drop(32), m))),
        accepted = false,
        strictOnly
      ),
      Workload(
        "S + L",
        wrapped(valid.map((k, s, m) => (k, s.take(32) ++ le(fromLe(s.drop(32)) + l), m))),
        accepted = false,
        strictOnly
      ),
      Workload(
        "another message",
        wrapped(valid.map((k, s, m) => (k, s, m.map(b => (b ^ 1).toByte)))),
        accepted = false,
        strictOnly
      )
    )
    val unexpected = for
      w <- workloads
      (a, b) <- w.pairs
      path <- Vector(a, b)
      ((k, s, m), i) <- w.inputs.zipWithIndex
      if path.verify(k, s, m) != w.accepted
    yield s"${w.name} $path input $i"
    if unexpected.nonEmpty then
      unexpected.foreach(r => println(s"UNEXPECTED $r"))
      sys.exit(1)
    workloads.foreach(w => w.pairs.foreach((a, b) => measure(w, a, b, rounds)))
