// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.witness

import org.bouncycastle.math.ec.rfc8032.Ed25519
import scala.util.{Failure, Random, Success, Try}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.witness.Ed25519Model as M

/** `StrictEd25519` and `ByronEd25519` against `VendoredVerifiers`, the same verifiers over the
  * vendored curve25519-elisabeth objects: the results must be the same on every input, a verdict, a
  * refusal of an input or a failure (compared by its class), or the exception `verify` throws.
  *
  * The inputs are valid signatures, the same mutated (a bit of the key, of R, of S or of the
  * message flipped, the sign of x of the key or of R flipped, an edge encoding put for the key or
  * for R, S moved past L), keys and R of every order (a point of the prime-order subgroup plus each
  * torsion point), the cases where an equation holds although a rule refuses the signature (R or a
  * key of small order, S at or past L), and malformed arguments: wrong lengths and nulls. A null
  * message pins the order of the checks: a signature the old path refused before it hashed must be
  * refused, not fail on the message. Every key, signature and message is passed to the new
  * verifiers as a window of a larger array, with bytes on either side, so that an offset or a
  * length read wrongly changes the verdict.
  */
class VartimeEd25519DifferentialSuite extends munit.FunSuite:
  private val p = M.p
  private val L = M.L

  /** `bytes` inside a larger array, with `before` and `after` bytes of `fill` around it. */
  private def window(bytes: Array[Byte], random: Random): Bytes =
    val before = random.nextInt(40)
    val after = random.nextInt(40)
    val fill = random.nextInt(256).toByte
    val big = Array.fill[Byte](before + bytes.length + after)(fill)
    System.arraycopy(bytes, 0, big, before, bytes.length)
    Bytes.wrap(big, before, bytes.length)

  private final case class Tally(
      var strictAccepted: Int = 0,
      var byronAccepted: Int = 0,
      var cases: Int = 0
  )

  /** What a verifier gave: a verdict, a refusal of the input, a failure with its cause's class, or
    * an exception `verify` threw.
    */
  private enum Outcome:
    case Verdict(result: VerificationResult)
    case Refused(error: WitnessInputError)
    case Failed(cause: Class[?])
    case Threw(exception: Class[?])

  private def outcome(
      run: => Either[WitnessInputError | VerificationError, VerificationResult]
  ): Outcome =
    Try(run) match
      case Success(Right(result)) => Outcome.Verdict(result)
      case Success(Left(VerificationError.ImplementationFailure(cause))) =>
        Outcome.Failed(cause.getClass)
      case Success(Left(error: WitnessInputError)) => Outcome.Refused(error)
      case Failure(exception)                      => Outcome.Threw(exception.getClass)

  private val accepted = Outcome.Verdict(VerificationResult.SignatureVerified)
  private val rejected = Outcome.Verdict(VerificationResult.SignatureRejected)

  /** Both verifiers, new and vendored, on the same arguments; the new path's outcomes. */
  private def compareBytes(
      key: Bytes,
      signature: Bytes,
      message: Bytes,
      clue: => String
  ): (Outcome, Outcome) =
    val strict = outcome(StrictEd25519.verify(key, signature, message))
    val byron = outcome(ByronEd25519.verify(key, signature, message))
    assertEquals(
      strict,
      outcome(VendoredVerifiers.strictVerify(key, signature, message)),
      s"strict, $clue"
    )
    assertEquals(
      byron,
      outcome(VendoredVerifiers.byronVerify(key, signature, message)),
      s"byron, $clue"
    )
    (strict, byron)

  /** [[compareBytes]] on windows of larger arrays; the counts of what was accepted. */
  private def compare(
      key: Array[Byte],
      signature: Array[Byte],
      message: Array[Byte],
      random: Random,
      tally: Tally,
      clue: => String
  ): (Boolean, Boolean) =
    val (strict, byron) =
      compareBytes(window(key, random), window(signature, random), window(message, random), clue)
    assert(strict.isInstanceOf[Outcome.Verdict], s"strict: no verdict, $strict, $clue")
    assert(byron.isInstanceOf[Outcome.Verdict], s"byron: no verdict, $byron, $clue")
    tally.cases += 1
    if strict == accepted then tally.strictAccepted += 1
    if byron == accepted then tally.byronAccepted += 1
    (strict == accepted, byron == accepted)

  private def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString
  private def le(v: BigInt): Array[Byte] = M.le(v, 32)

  private def bytes(random: Random, n: Int): Array[Byte] =
    val out = new Array[Byte](n)
    random.nextBytes(out)
    out

  private def scalar(random: Random): BigInt = BigInt(1, bytes(random, 32)).mod(L)

  /** Encodings on the edges: y near 0, 1, p - 1, p and 2^255 - 1, both signs, the points of small
    * order, and off the curve.
    */
  private val edges: Vector[Array[Byte]] =
    val ys = (BigInt(0) until BigInt(24)).toVector ++ (BigInt(-20) until BigInt(20)).map(p + _) ++
      Vector((BigInt(1) << 255) - 1, (BigInt(1) << 255) - 2, BigInt(1) << 254)
    (ys.filter(y => y >= 0 && y < (BigInt(1) << 255)).map(le) ++ M.torsion.map(_.encode)).flatMap {
      b =>
        val flipped = b.clone()
        flipped(31) = (flipped(31) ^ 0x80).toByte
        Vector(b, flipped)
    }

  private def flipBit(a: Array[Byte], i: Int, bit: Int): Array[Byte] =
    val c = a.clone()
    c(i) = (c(i) ^ (1 << bit)).toByte
    c

  test("signatures, valid and mutated, get the verdicts of the vendored verifiers") {
    val random = new Random(1418)
    val tally = Tally()
    (0 until 1200).foreach { n =>
      val sk = bytes(random, 32)
      val pk = new Array[Byte](32)
      Ed25519.generatePublicKey(sk, 0, pk, 0)
      val message = bytes(random, random.nextInt(300))
      val sig = new Array[Byte](64)
      Ed25519.sign(sk, 0, pk, 0, message, 0, message.length, sig, 0)
      val (strictValid, byronValid) = compare(pk, sig, message, random, tally, s"valid $n")
      assert(strictValid && byronValid, s"a valid signature is accepted, case $n")
      val s = M.fromLe(sig.drop(32))
      val mutants = Vector(
        (flipBit(pk, random.nextInt(32), random.nextInt(8)), sig, message),
        (pk, flipBit(sig, random.nextInt(32), random.nextInt(8)), message),
        (pk, flipBit(sig, 32 + random.nextInt(32), random.nextInt(8)), message),
        (flipBit(pk, 31, 7), sig, message),
        (pk, flipBit(sig, 31, 7), message),
        (edges(random.nextInt(edges.size)), sig, message),
        (pk, edges(random.nextInt(edges.size)) ++ sig.drop(32), message),
        (pk, sig.take(32) ++ le(s + L), message),
        (pk, sig.take(32) ++ le((s + 2 * L).min((BigInt(1) << 256) - 1)), message),
        (pk, sig.take(32) ++ le(L), message),
        (pk, sig.take(32) ++ le(BigInt(1, bytes(random, 32))), message)
      ) ++ Option.when(message.nonEmpty)(
        (pk, sig, flipBit(message, random.nextInt(message.length), random.nextInt(8)))
      )
      mutants.zipWithIndex.foreach { case ((k, sg, m), i) =>
        compare(k, sg, m, random, tally, s"case $n mutant $i: ${hex(k)} ${hex(sg)}")
      }
    }
    assert(tally.strictAccepted >= 1200, s"strict accepted ${tally.strictAccepted}")
    // S + L is still below 2^253 for nearly every S, and Byron accepts it
    assert(tally.byronAccepted >= 2000, s"byron accepted ${tally.byronAccepted}")
    assert(tally.cases > 15000, s"${tally.cases} cases")
  }

  test("keys and R of every order: the verdicts of the vendored verifiers") {
    val random = new Random(1419)
    val tally = Tally()
    (0 until 400).foreach { n =>
      val a = scalar(random)
      val i = random.nextInt(8)
      val key = (M.basepoint * a + M.torsion(i)).encode
      val nonce = scalar(random)
      val j = if random.nextBoolean() then 0 else random.nextInt(8)
      val r = (M.basepoint * nonce + M.torsion(j)).encode
      val message = bytes(random, random.nextInt(80))
      val signature = M.signature(key, a, r, nonce, message)
      compare(key, signature, message, random, tally, s"case $n, torsion $i and $j")
    }
    // the equation holds when -[h]T_i = T_j, as it does for i = j = 0 and in part for the others
    // (about 88 of the 400 cases are expected)
    assert(tally.strictAccepted > 40, s"strict accepted ${tally.strictAccepted}")
    assert(tally.byronAccepted >= tally.strictAccepted, s"byron accepted ${tally.byronAccepted}")
  }

  /** A message for which [h]T is `target`, h being the challenge of `r`, `key` and the message. */
  private def messageFor(
      random: Random,
      r: Array[Byte],
      key: Array[Byte],
      t: M.Point,
      target: M.Point
  ) =
    Iterator
      .continually(bytes(random, 1 + random.nextInt(40)))
      .find(m => (t * M.challenge(r, key, m)).same(target))
      .get

  test("R of small order where the equation holds: refused by both strict verifiers") {
    // Q = [S]B - [h]A with A = [a]B + T and S = h a: Q = -[h]T. A message makes it each torsion
    // point in turn, R is that point's encoding, and the encoding of Q is then R's bytes: only the
    // small-order test of R refuses it.
    val random = new Random(1420)
    val tally = Tally()
    for
      target <- M.torsion.indices
      round <- 0 until 3
    do
      val a = scalar(random)
      val t = M.order8 // A has a component of order 8, so -[h]T reaches every torsion point
      val key = (M.basepoint * a + t).encode
      val r = M.torsion(target).encode
      val message = messageFor(random, r, key, t, -M.torsion(target))
      val signature = r ++ le((M.challenge(r, key, message) * a).mod(L))
      // the cofactorless equation holds: the encoding of [S]B - [h]A is R
      val q = M.basepoint * M.fromLe(signature.drop(32)) - M.decode(key, M.Reading.Strict).get * M
        .challenge(r, key, message)
      assertEquals(hex(q.encode), hex(r), s"the equation holds, torsion $target")
      val (strict, byron) =
        compare(key, signature, message, random, tally, s"R = T$target, round $round")
      assert(!strict, s"strict refuses R of small order, torsion $target")
      assert(byron, s"byron accepts it, torsion $target")
  }

  test("keys of small order where the equation holds: strict refuses, Byron accepts") {
    val random = new Random(1421)
    val tally = Tally()
    for
      i <- M.torsion.indices
      round <- 0 until 3
    do
      val key = M.torsion(i).encode
      val nonce = scalar(random)
      // R = [nonce]B - T' for a guess T' of [h]A in the group A generates; a message makes it right
      val guess = M.torsion(i) * random.nextInt(8)
      val r = (M.basepoint * nonce - guess).encode
      val message = messageFor(random, r, key, M.torsion(i), guess)
      val signature = r ++ le(nonce)
      val (strict, byron) =
        compare(key, signature, message, random, tally, s"A = T$i, round $round")
      assert(!strict, s"strict refuses a key of small order, T$i")
      assert(byron, s"byron accepts it, T$i")
  }

  test("non-canonical keys: Byron reads y modulo p and hashes the bytes as given") {
    // y = p + 1 is the identity, and p is y = 0, a point of order 4: [h]A is known, so the equation
    // can be made to hold with either sign of x
    val random = new Random(1422)
    val tally = Tally()
    for
      y <- Vector(p, p + 1)
      sign <- Vector(0, 1)
      round <- 0 until 4
    do
      val key = le(if sign == 1 then y.setBit(255) else y)
      val point = M.decode(key, M.Reading.Lenient).get
      val nonce = scalar(random)
      val guess = point * random.nextInt(8)
      val r = (M.basepoint * nonce - guess).encode
      val message = messageFor(random, r, key, point, guess)
      val (strict, byron) =
        compare(key, r ++ le(nonce), message, random, tally, s"y = p + ${y - p}, round $round")
      assert(!strict, "strict refuses a non-canonical key")
      assert(byron, s"byron accepts y = p + ${y - p}, sign $sign")
    // and every edge encoding with random signatures
    edges.foreach { e =>
      (0 until 4).foreach(_ =>
        compare(e, bytes(random, 64), bytes(random, 20), random, tally, hex(e))
      )
    }
  }

  test("a key is accepted as it was: edges, random bytes and the points of every order") {
    val random = new Random(1423)
    val encodings = edges ++ Vector.fill(20000)(bytes(random, 32)) ++
      Vector.fill(200)((M.basepoint * scalar(random) + M.torsion(random.nextInt(8))).encode)
    encodings.foreach { e =>
      assertEquals(
        StrictEd25519.acceptsKey(e),
        VendoredVerifiers.strictPoint(e).isDefined,
        hex(e)
      )
    }
  }

  // ---- rejected and malformed inputs: the whole result, and refusals made before the hash

  /** One case of each way strict refuses before it hashes, with its key and signature. */
  private def strictRefusals(random: Random): Vector[(String, Array[Byte], Array[Byte])] =
    val sk = bytes(random, 32)
    val pk = new Array[Byte](32)
    Ed25519.generatePublicKey(sk, 0, pk, 0)
    val sig = new Array[Byte](64)
    Ed25519.sign(sk, 0, pk, 0, Array[Byte](1, 2, 3), 0, 3, sig, 0)
    val offCurve = edges.find(e => M.decode(e, M.Reading.Lenient).isEmpty).get
    val nonCanonical = le(p + 2)
    val smallOrder = M.order8.encode
    Vector(
      ("non-canonical key", nonCanonical, sig),
      ("key off the curve", offCurve, sig),
      ("key of small order", smallOrder, sig),
      ("non-canonical R", pk, nonCanonical ++ sig.drop(32)),
      ("R off the curve", pk, offCurve ++ sig.drop(32)),
      ("R of small order", pk, smallOrder ++ sig.drop(32)),
      ("S = L", pk, sig.take(32) ++ le(L)),
      ("S + L", pk, sig.take(32) ++ le(M.fromLe(sig.drop(32)) + L)),
      ("S with bit 255", pk, sig.take(32) ++ le(M.fromLe(sig.drop(32)).setBit(255)))
    )

  test("each refusal of strict comes before the hash: the same refusal with a null message") {
    val random = new Random(1424)
    strictRefusals(random).foreach { (name, key, signature) =>
      val (strict, _) = compareBytes(Bytes.wrap(key), Bytes.wrap(signature), null, name)
      assertEquals(strict, rejected, s"strict refuses $name without reading the message")
      val direct = outcome(
        StrictEd25519.verifyEd25519(
          PublicKey32.fromArray(key).toOption.get,
          Signature64.fromArray(signature).toOption.get,
          null
        )
      )
      assertEquals(direct, rejected, s"verifyEd25519, $name")
    }
  }

  test("each refusal of Byron comes before the hash: the same refusal with a null message") {
    val random = new Random(1425)
    val sk = bytes(random, 32)
    val pk = new Array[Byte](32)
    Ed25519.generatePublicKey(sk, 0, pk, 0)
    val sig = new Array[Byte](64)
    Ed25519.sign(sk, 0, pk, 0, Array[Byte](1, 2, 3), 0, 3, sig, 0)
    val offCurve = edges.find(e => M.decode(e, M.Reading.Lenient).isEmpty).get
    Vector(
      ("key off the curve", offCurve, sig),
      ("S of 2^253", pk, sig.take(32) ++ le(BigInt(1) << 253)),
      ("S with bit 255", pk, sig.take(32) ++ le(M.fromLe(sig.drop(32)).setBit(255)))
    ).foreach { (name, key, signature) =>
      val (_, byron) = compareBytes(Bytes.wrap(key), Bytes.wrap(signature), null, name)
      assertEquals(byron, rejected, s"byron refuses $name without reading the message")
    }
  }

  test(
    "a signature that passes the checks reads the message: a null message fails in both, typed"
  ) {
    val random = new Random(1426)
    val sk = bytes(random, 32)
    val pk = new Array[Byte](32)
    Ed25519.generatePublicKey(sk, 0, pk, 0)
    val sig = new Array[Byte](64)
    Ed25519.sign(sk, 0, pk, 0, Array[Byte](1, 2, 3), 0, 3, sig, 0)
    val npe = Outcome.Failed(classOf[NullPointerException])
    // valid, and refused only by the equation (another message's signature)
    val (strict, byron) = compareBytes(Bytes.wrap(pk), Bytes.wrap(sig), null, "valid")
    assertEquals((strict, byron), (npe, npe), "a valid signature with a null message")
    // Byron reaches the hash for a key strict refuses: non-canonical, and of small order
    Vector(le(p + 1), M.order8.encode).foreach { key =>
      val (_, byronOnly) = compareBytes(Bytes.wrap(key), Bytes.wrap(sig), null, hex(key))
      assertEquals(byronOnly, npe, s"byron hashes for ${hex(key)}")
    }
  }

  test("malformed arguments: wrong lengths and nulls give the same result value") {
    val random = new Random(1427)
    val sk = bytes(random, 32)
    val pk = new Array[Byte](32)
    Ed25519.generatePublicKey(sk, 0, pk, 0)
    val message = bytes(random, 10)
    val sig = new Array[Byte](64)
    Ed25519.sign(sk, 0, pk, 0, message, 0, message.length, sig, 0)
    val keys = Vector(0, 1, 31, 33, 64).map(n => Bytes.wrap(bytes(random, n))) :+ Bytes.wrap(pk)
    val signatures = Vector(0, 1, 32, 63, 65, 128).map(n => Bytes.wrap(bytes(random, n))) :+
      Bytes.wrap(sig)
    for
      k <- keys
      s <- signatures
      m <- Vector(Bytes.wrap(message), Bytes.empty)
    do compareBytes(k, s, m, s"key of ${k.size}, signature of ${s.size}")
    val (strict, byron) =
      compareBytes(Bytes.wrap(bytes(random, 31)), Bytes.wrap(sig), Bytes.empty, "short")
    assertEquals(strict, Outcome.Refused(WitnessInputError.MalformedPublicKeyLength(31)), "strict")
    assertEquals(byron, Outcome.Refused(WitnessInputError.MalformedPublicKeyLength(31)), "byron")
    // nulls: a null key or signature makes `verify` throw, as it did; in verifyEd25519 a null
    // typed key or signature is a typed failure, as it was
    compareBytes(null, Bytes.wrap(sig), Bytes.wrap(message), "null key")
    compareBytes(Bytes.wrap(pk), null, Bytes.wrap(message), "null signature")
    val key = PublicKey32.fromArray(pk).toOption.get
    val signature = Signature64.fromArray(sig).toOption.get
    val cases = Vector(
      ("null key", null, signature, Bytes.wrap(message)),
      ("null signature", key, null, Bytes.wrap(message)),
      ("null key and message", null, signature, null)
    )
    cases.foreach { (name, k, sg, m) =>
      assertEquals(
        outcome(StrictEd25519.verifyEd25519(k, sg, m)),
        outcome(VendoredVerifiers.strictVerifyEd25519(k, sg, m)),
        s"strict verifyEd25519, $name"
      )
      assertEquals(
        outcome(ByronEd25519.verifyEd25519(k, sg, m)),
        outcome(VendoredVerifiers.byronVerifyEd25519(k, sg, m)),
        s"byron verifyEd25519, $name"
      )
    }
  }
