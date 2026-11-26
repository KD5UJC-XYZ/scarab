// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.witness

import java.security.{KeyFactory, Signature}
import java.security.spec.{EdECPrivateKeySpec, NamedParameterSpec}
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import xyz.kd5ujc.scarab.crypto.witness.Ed25519Model as M

import Ed25519Verifiers.*
import VerificationResult.*

/** `StrictEd25519` against the JDK's Ed25519 and Bouncy Castle's, on valid signatures, on mutated
  * ones, and on families of signatures built at the edges where the three rules differ: S >= L,
  * non-canonical R and A, R and A of small order, A and R with a torsion component, x = 0 with the
  * sign bit set, and encodings off the curve.
  *
  * The signatures of a family are built with the independent model (`Ed25519Model`): a family whose
  * equation holds is signed with the discrete logarithm of the prime-order part of the key, and its
  * message is searched for a challenge k with the property the family needs (k a multiple of the
  * order of a torsion point, or not). Each family states the outcome of each verifier: Scarab's is
  * the reference's rule, and where the JDK or Bouncy Castle differs from it, the difference is that
  * verifier's own rule, cited in `Ed25519Verifiers`. Nothing is skipped: a family runs whole on
  * every verifier. Properties do not shrink (`forAllNoShrink`): a shrunk key or seed would not be
  * 32 bytes.
  */
class Ed25519CrossCheckSuite extends munit.ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(100)

  private final case class Case(key: Array[Byte], signature: Array[Byte], message: Array[Byte])
  private final case class Expected(scarab: VerificationResult, jdk: Jdk, bc: Boolean)

  private def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString

  private def check(c: Case, expected: Expected, what: String): Unit =
    val clue = s"$what: key ${hex(c.key)} signature ${hex(c.signature)} message ${hex(c.message)}"
    assertEquals(scarab(c.key, c.signature, c.message), Right(expected.scarab), s"Scarab, $clue")
    assertEquals(jdk(c.key, c.signature, c.message), expected.jdk, s"JDK, $clue")
    assertEquals(bc(c.key, c.signature, c.message), expected.bc, s"Bouncy Castle, $clue")

  private def bytesOf(n: Int): Gen[Array[Byte]] =
    Gen.listOfN(n, Gen.choose(0, 255)).map(_.map(_.toByte).toArray)

  private val message: Gen[Array[Byte]] = Gen.choose(0, 80).flatMap(bytesOf)
  private val secret: Gen[BigInt] = bytesOf(64).map(b => M.fromLe(b).mod(M.L)).suchThat(_ != 0)

  private def le(value: BigInt): Array[Byte] = M.le(value.mod(M.L), 32)

  /** `base || i`, for the first i of 0 to 255 whose challenge over `r` and `key` passes `want`. */
  private def searched(r: Array[Byte], key: Array[Byte], base: Array[Byte])(
      want: BigInt => Boolean
  ): Option[Array[Byte]] =
    (0 until 256).iterator.map(i => base :+ i.toByte).find(m => want(M.challenge(r, key, m)))

  /** The encodings with y = p + delta, each with sign 0 and 1. */
  private def nonCanonicalOf(deltas: Seq[Int]): Vector[Array[Byte]] =
    deltas.toVector.flatMap { delta =>
      val y = M.p + delta
      Vector(M.le(y, 32), M.le(y.setBit(255), 32))
    }

  /** Every non-canonical encoding: y from p to 2^255 - 1, that is y = p + delta, delta 0 to 18. */
  private val nonCanonical: Vector[Array[Byte]] = nonCanonicalOf(0 to 18)

  // Of the y values 0 to 18 these have a point (checked by "the non-canonical encodings" below). y = 0
  // (the two points of order 4) and y = 1 (the identity) are of small order; the others are not.
  private val onCurveDeltas = Vector(0, 1, 3, 4, 5, 6, 9, 10, 14, 15, 16, 18)

  /** y = p and p + 1: aliases of the points of order 4 and of the identity. */
  private val smallOrderAliases = nonCanonicalOf(Vector(0, 1))

  /** y = p + delta for the ten other delta with a point: aliases of points not of small order. */
  private val largeOrderAliases = nonCanonicalOf(onCurveDeltas.filterNot(Set(0, 1)))

  /** y = p + delta for the seven delta with no point. */
  private val offCurveAliases = nonCanonicalOf((0 to 18).filterNot(onCurveDeltas.contains))

  /** The canonical encodings of the eight points of order dividing 8. */
  private val torsionEncodings: Vector[Array[Byte]] = M.torsion.map(_.encode)

  /** x = 0 with the sign bit set: the identity and the point of order 2. */
  private val negativeZero: Vector[Array[Byte]] =
    Vector(M.identity, M.torsion(4)).map(t => M.le(t.affineY.setBit(255), 32))

  private val offCurve: Gen[Array[Byte]] =
    Gen
      .zip(bytesOf(32).map(b => M.fromLe(b) & ((BigInt(1) << 255) - 1)), Gen.oneOf(0, 1))
      .suchThat((y, _) => y < M.p && M.recoverX(y, 0).isEmpty)
      .map((y, s) => M.le(if s == 1 then y.setBit(255) else y, 32))

  /** A family of cases with the outcome of each verifier. */
  private def family(name: String, expected: Expected)(cases: Gen[Case]): Unit =
    val bcVerdict = if expected.bc then "accepts" else "rejects"
    property(s"$name: Scarab ${expected.scarab}, JDK ${expected.jdk}, Bouncy Castle $bcVerdict") {
      forAllNoShrink(cases)(c => check(c, expected, name))
    }

  private def defined[A](gen: Gen[Option[A]]): Gen[A] = gen.suchThat(_.isDefined).map(_.get)

  /** A key's encoding, a signature by its BC signer, and the message. */
  private val signed: Gen[Case] =
    Gen.zip(bytesOf(32), message).map { (seed, m) =>
      val pk = new Array[Byte](32)
      Ed25519.generatePublicKey(seed, 0, pk, 0)
      val sig = new Array[Byte](64)
      Ed25519.sign(seed, 0, m, 0, m.length, sig, 0)
      Case(pk, sig, m)
    }

  family("signatures of BC's signer", Expected(SignatureVerified, Jdk.Verified, true))(signed)

  property("ByronEd25519 accepts the same valid signatures") {
    forAllNoShrink(signed) { c =>
      assertEquals(byron(c.key, c.signature, c.message), Right(SignatureVerified))
    }
  }

  property("the JDK's signer, BC's and the model's give the same key and signature") {
    val jdkKeys = KeyFactory.getInstance("Ed25519", "SunEC")
    forAllNoShrink(bytesOf(32), message) { (seed, m) =>
      val pk = new Array[Byte](32)
      Ed25519.generatePublicKey(seed, 0, pk, 0)
      val bcSig = new Array[Byte](64)
      Ed25519.sign(seed, 0, m, 0, m.length, bcSig, 0)
      val signer = Signature.getInstance("Ed25519", "SunEC")
      signer.initSign(
        jdkKeys.generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed))
      )
      signer.update(m)
      val jdkSig = signer.sign()
      val a = M.secretScalar(seed)
      val nonce = M.fromLe(M.sha512(M.sha512(seed).drop(32), m)).mod(M.L)
      val modelKey = (M.basepoint * a).encode
      val modelSig = M.signature(modelKey, a, (M.basepoint * nonce).encode, nonce, m)
      assertEquals(hex(pk), hex(modelKey), "BC's key is the model's")
      assertEquals(hex(jdkSig), hex(bcSig), "the JDK's signature is BC's")
      assertEquals(hex(bcSig), hex(modelSig), "BC's signature is the model's")
    }
  }

  // Mutations of a valid signature. Every verifier rejects; the JDK's outcome is the first of its
  // refusals that applies, in its order (the key when verification starts, then S, then R).
  private enum Mutation:
    case Key(bit: Int)
    case R(bit: Int)
    case S(bit: Int)
    case Message(bit: Int)

  private def flipped(bytes: Array[Byte], bit: Int): Array[Byte] =
    val out = bytes.clone()
    out(bit / 8) = (out(bit / 8) ^ (1 << (bit % 8))).toByte
    out

  property(
    "every verifier rejects a valid signature with one bit flipped in A, R, S or the message"
  ) {
    val mutation = Gen.oneOf(
      Gen.choose(0, 255).map(Mutation.Key(_)),
      Gen.choose(0, 255).map(Mutation.R(_)),
      Gen.choose(0, 255).map(Mutation.S(_))
    )
    val cases = for
      c <- signed.suchThat(_.message.nonEmpty)
      flip <- Gen.frequency(
        3 -> mutation,
        1 -> Gen.choose(0, 8 * c.message.length - 1).map(Mutation.Message(_))
      )
    yield (c, flip)
    forAllNoShrink(cases) { (c, flip) =>
      val mutated = flip match
        case Mutation.Key(b)     => c.copy(key = flipped(c.key, b))
        case Mutation.R(b)       => c.copy(signature = flipped(c.signature, b))
        case Mutation.S(b)       => c.copy(signature = flipped(c.signature, 256 + b))
        case Mutation.Message(b) => c.copy(message = flipped(c.message, b))
      val s = M.fromLe(mutated.signature.drop(32))
      val jdkOutcome =
        if M.decode(mutated.key, M.Reading.Strict).isEmpty then Jdk.KeyRefused
        else if s >= M.L then Jdk.SignatureRefused
        else if M.decode(mutated.signature.take(32), M.Reading.Strict).isEmpty then
          Jdk.SignatureRefused
        else Jdk.Rejected
      check(mutated, Expected(SignatureRejected, jdkOutcome, false), flip.toString)
      assertEquals(
        byron(mutated.key, mutated.signature, mutated.message),
        Right(SignatureRejected),
        "Byron"
      )
    }
  }

  // S >= L. The reference refuses it (sc25519_is_canonical); so do the JDK (a SignatureException)
  // and BC. Only the Byron profile reduces S, and only below 2^253 (ByronEd25519Suite's concern).
  family("S + jL below 2^256, j from 1", Expected(SignatureRejected, Jdk.SignatureRefused, false)) {
    Gen.zip(signed, Gen.choose(1, 15)).map { (c, j) =>
      val s = M.fromLe(c.signature.drop(32))
      // 15L < 2^256 < 16L, so the largest j is 14 or 15
      val largest = ((BigInt(1) << 256) - 1 - s) / M.L
      c.copy(signature = c.signature.take(32) ++ M.le(s + BigInt(j).min(largest) * M.L, 32))
    }
  }

  test(
    "S = L and S = 2^256 - 1: Scarab rejects, JDK refuses the signature, Bouncy Castle rejects"
  ) {
    val c = signed.sample.get
    Vector(M.L, (BigInt(1) << 256) - 1).foreach { s =>
      check(
        c.copy(signature = c.signature.take(32) ++ M.le(s, 32)),
        Expected(SignatureRejected, Jdk.SignatureRefused, false),
        s"S = ${s.toString(16)}"
      )
    }
  }

  // Non-canonical encodings (y >= p). Rejecting one for its encoding alone cannot be isolated in a
  // signature: an alias of a point not of small order (y = p + delta, delta in largeOrderAliases)
  // has no known discrete logarithm, so no signature satisfies its equation, and the equation
  // fails whatever the encoding; and the aliases whose equation can hold, y = p and p + 1, are of
  // small order, which the reference refuses as well. The families below state which reasons apply;
  // the test "non-canonical encodings of points not of small order are refused for the encoding
  // alone" isolates the encoding check where it can be isolated, in the decoding of a key.

  // R = p + 1 or p (the identity, or a point of order 4, both signs), S = k a: [S]B - [k]A is the
  // identity, so for y = p + 1 a verifier that read y modulo p and had no small-order test would
  // accept. The reference refuses R for its encoding and for its order (y = p and p + 1 are on its
  // blacklist); the JDK and BC refuse the encoding.
  family(
    "non-canonical R aliasing a point of small order (refused for its encoding and its order)",
    Expected(SignatureRejected, Jdk.SignatureRefused, false)
  ) {
    Gen.zip(secret, message, Gen.oneOf(smallOrderAliases)).map { (a, m, r) =>
      val key = (M.basepoint * a).encode
      Case(key, r ++ le(M.challenge(r, key, m) * a), m)
    }
  }

  family(
    "non-canonical R of a point not of small order, or of no point (its equation fails too)",
    Expected(SignatureRejected, Jdk.SignatureRefused, false)
  ) {
    Gen.zip(secret, message, Gen.oneOf(largeOrderAliases ++ offCurveAliases)).map { (a, m, r) =>
      val key = (M.basepoint * a).encode
      Case(key, r ++ le(M.challenge(r, key, m) * a), m)
    }
  }

  // A = p + 1 or p, R = [r]B and S = r: for y = p + 1 a verifier that read y modulo p and had no
  // small-order test would accept. The reference refuses A for its encoding (ge25519_is_canonical)
  // and its order; the JDK refuses the key, BC the encoding (checkPointFullVar).
  family(
    "non-canonical A aliasing a point of small order (refused for its encoding and its order)",
    Expected(SignatureRejected, Jdk.KeyRefused, false)
  ) {
    Gen.zip(secret, message, Gen.oneOf(smallOrderAliases)).map { (r, m, key) =>
      Case(key, (M.basepoint * r).encode ++ le(r), m)
    }
  }

  family(
    "non-canonical A of a point not of small order, or of no point (its equation fails too)",
    Expected(SignatureRejected, Jdk.KeyRefused, false)
  ) {
    Gen.zip(secret, message, Gen.oneOf(largeOrderAliases ++ offCurveAliases)).map { (r, m, key) =>
      Case(key, (M.basepoint * r).encode ++ le(r), m)
    }
  }

  test("the non-canonical encodings: which have a point, and which of those are of small order") {
    (0 to 18).foreach { delta =>
      val alias = M.decode(M.le(M.p + delta, 32), M.Reading.Lenient)
      assertEquals(alias.isDefined, onCurveDeltas.contains(delta), s"y = p + $delta has a point")
      alias.foreach(point =>
        assertEquals(point.isSmallOrder, delta <= 1, s"y = p + $delta is of small order")
      )
    }
    assertEquals(nonCanonical.size, 38)
    assertEquals(smallOrderAliases.size + largeOrderAliases.size + offCurveAliases.size, 38)
  }

  // Each of these decodes, read modulo p as libsodium's ge25519_frombytes and the vendored
  // decompress read it, to a point not of small order: the encoding is the only reason to refuse it.
  test("non-canonical encodings of points not of small order are refused for the encoding alone") {
    largeOrderAliases.foreach { e =>
      val point = M.decode(e, M.Reading.Lenient).get
      assert(!point.isSmallOrder, s"${hex(e)} is not of small order")
      val decompressed =
        new com.weavechain.curve25519.CompressedEdwardsY(e.clone()).decompress().get
      assertEquals(
        hex(decompressed.compress().toByteArray),
        hex(point.encode),
        s"decompress ${hex(e)}"
      )
      assert(!StrictEd25519.acceptsKey(e), s"Scarab refuses ${hex(e)}")
      assert(!bcAcceptsKey(e), s"BC refuses ${hex(e)}")
      assert(!jdkAcceptsKey(e), s"the JDK refuses ${hex(e)}")
    }
  }

  // A of small order, canonical: each of the eight points, with R = [r]B, S = r and a message whose
  // k makes [k]A the identity, so that the equation holds without a cofactor. The reference refuses
  // A (its blacklist); the JDK has no small-order test and accepts; BC refuses A (checkPointFullVar).
  family(
    "A of small order, the equation holding",
    Expected(SignatureRejected, Jdk.Verified, false)
  ) {
    defined(Gen.zip(secret, message, Gen.choose(0, 7)).map { (r, m, i) =>
      val key = torsionEncodings(i)
      val rEnc = (M.basepoint * r).encode
      searched(rEnc, key, m)(k => (k * i).mod(8) == 0).map(found => Case(key, rEnc ++ le(r), found))
    })
  }

  family(
    "A of small order, the equation failing",
    Expected(SignatureRejected, Jdk.Rejected, false)
  ) {
    defined(Gen.zip(secret, message, Gen.choose(1, 7)).map { (r, m, i) =>
      val key = torsionEncodings(i)
      val rEnc = (M.basepoint * r).encode
      searched(rEnc, key, m)(k => (k * i).mod(8) != 0).map(found => Case(key, rEnc ++ le(r), found))
    })
  }

  // x = 0 with the sign bit set: the reference's blacklist masks bit 255, the JDK refuses the
  // encoding (Ed25519Operations.java:137), BC too (decodePointVar).
  family("A with x = 0 and the sign bit set", Expected(SignatureRejected, Jdk.KeyRefused, false)) {
    Gen.zip(secret, message, Gen.oneOf(negativeZero)).map { (r, m, key) =>
      Case(key, (M.basepoint * r).encode ++ le(r), m)
    }
  }

  // R the identity, A = [a]B and S = k a: [S]B - [k]A = R exactly. The reference refuses R (its
  // blacklist); the JDK accepts; BC's cofactored equation holds and it accepts.
  family("R the identity, the equation holding", Expected(SignatureRejected, Jdk.Verified, true)) {
    Gen.zip(secret, message).map { (a, m) =>
      val key = (M.basepoint * a).encode
      val r = M.identity.encode
      Case(key, r ++ le(M.challenge(r, key, m) * a), m)
    }
  }

  // R another point of small order, S = k a: [S]B - [k]A is the identity, not R, so the equation
  // fails; [8](R) is the identity, so BC's cofactored equation holds.
  family("R of small order and not the identity", Expected(SignatureRejected, Jdk.Rejected, true)) {
    Gen.zip(secret, message, Gen.choose(1, 7)).map { (a, m, i) =>
      val key = (M.basepoint * a).encode
      val r = torsionEncodings(i)
      Case(key, r ++ le(M.challenge(r, key, m) * a), m)
    }
  }

  family(
    "R with x = 0 and the sign bit set",
    Expected(SignatureRejected, Jdk.SignatureRefused, false)
  ) {
    Gen.zip(secret, message, Gen.oneOf(negativeZero)).map { (a, m, r) =>
      val key = (M.basepoint * a).encode
      Case(key, r ++ le(M.challenge(r, key, m) * a), m)
    }
  }

  // A = [a]B + T, T of small order and not the identity; R = [r]B, S = r + k a. Then
  // [S]B - [k]A = R - [k]T: the equation holds exactly when [k]T is the identity. The reference
  // accepts a mixed-order key (only the blacklist is refused: speccheck case 3), and so do the JDK
  // and BC; when [k]T is not the identity only BC's cofactored equation holds.
  private def mixedKey(a: BigInt, i: Int): Array[Byte] = (M.basepoint * a + M.torsion(i)).encode

  family(
    "A of mixed order, the equation holding",
    Expected(SignatureVerified, Jdk.Verified, true)
  ) {
    defined(Gen.zip(secret, secret, message, Gen.choose(1, 7)).map { (a, r, m, i) =>
      val key = mixedKey(a, i)
      val rEnc = (M.basepoint * r).encode
      searched(rEnc, key, m)(k => (k * i).mod(8) == 0)
        .map(found => Case(key, M.signature(key, a, rEnc, r, found), found))
    })
  }

  family(
    "A of mixed order, the equation failing",
    Expected(SignatureRejected, Jdk.Rejected, true)
  ) {
    defined(Gen.zip(secret, secret, message, Gen.choose(1, 7)).map { (a, r, m, i) =>
      val key = mixedKey(a, i)
      val rEnc = (M.basepoint * r).encode
      searched(rEnc, key, m)(k => (k * i).mod(8) != 0)
        .map(found => Case(key, M.signature(key, a, rEnc, r, found), found))
    })
  }

  // R = [r]B + T, A = [a]B, S = r + k a: [S]B - [k]A = [r]B, which is not R.
  family("R of mixed order, A of prime order", Expected(SignatureRejected, Jdk.Rejected, true)) {
    Gen.zip(secret, secret, message, Gen.choose(1, 7)).map { (a, r, m, i) =>
      val key = (M.basepoint * a).encode
      val rEnc = (M.basepoint * r + M.torsion(i)).encode
      Case(key, M.signature(key, a, rEnc, r, m), m)
    }
  }

  // A = [a]B + T_i and R = [r]B + T_j with T_j = -[k]T_i: [S]B - [k]A = [r]B - [k]T_i = R, the
  // equation holds with both points of mixed order. Found by trying each j for each message.
  family(
    "A and R of mixed order, the equation holding",
    Expected(SignatureVerified, Jdk.Verified, true)
  ) {
    defined(Gen.zip(secret, secret, message, Gen.choose(1, 7)).map { (a, r, m, i) =>
      val key = mixedKey(a, i)
      val tries = for
        n <- (0 until 256).iterator
        j <- (1 to 7).iterator
      yield (m :+ n.toByte, j)
      tries
        .map { (found, j) =>
          val rEnc = (M.basepoint * r + M.torsion(j)).encode
          (found, j, rEnc, M.challenge(rEnc, key, found))
        }
        .find((_, j, _, k) => (-k * i).mod(8) == j)
        .map((found, _, rEnc, _) => Case(key, M.signature(key, a, rEnc, r, found), found))
    })
  }

  family("A off the curve", Expected(SignatureRejected, Jdk.KeyRefused, false)) {
    Gen.zip(secret, message, offCurve).map { (r, m, key) =>
      Case(key, (M.basepoint * r).encode ++ le(r), m)
    }
  }

  family("R off the curve", Expected(SignatureRejected, Jdk.SignatureRefused, false)) {
    Gen.zip(secret, secret, message, offCurve).map { (a, s, m, r) =>
      Case((M.basepoint * a).encode, r ++ le(s), m)
    }
  }

  // The decoding of a key on its own.
  private val encoding: Gen[Array[Byte]] =
    val ys = (BigInt(0) until 24).toVector ++ (BigInt(-24) until 24).map(M.p + _) ++
      Vector((BigInt(1) << 255) - 1, BigInt(1) << 254) ++ M.torsion.map(_.affineY)
    Gen.frequency(
      3 -> Gen
        .zip(Gen.oneOf(ys.filter(y => y >= 0 && y < (BigInt(1) << 255))), Gen.oneOf(0, 1))
        .map((y, s) => M.le(if s == 1 then y.setBit(255) else y, 32)),
      3 -> bytesOf(32),
      2 -> Gen.zip(secret, Gen.choose(0, 7)).map((a, i) => (M.basepoint * a + M.torsion(i)).encode),
      1 -> Gen.oneOf(torsionEncodings ++ negativeZero ++ nonCanonical)
    )

  property("a key decodes for Scarab exactly when BC's validatePublicKeyPartial accepts it") {
    forAllNoShrink(encoding) { e =>
      assertEquals(StrictEd25519.acceptsKey(e), bcAcceptsKey(e), hex(e))
    }
  }

  property("the JDK decodes as RFC 8032, and Scarab the same points less those of small order") {
    forAllNoShrink(encoding) { e =>
      val strict = M.decode(e, M.Reading.Strict)
      assertEquals(jdkAcceptsKey(e), strict.isDefined, s"JDK, ${hex(e)}")
      assertEquals(
        StrictEd25519.acceptsKey(e),
        strict.exists(!_.isSmallOrder),
        s"Scarab, ${hex(e)}"
      )
      if StrictEd25519.acceptsKey(e) then
        assertEquals(
          VartimeEd25519Probe.decoded(e).map(hex),
          Some(hex(e)),
          s"Scarab's point of ${hex(e)}"
        )
    }
  }

  test(
    "the keys the JDK accepts and Scarab refuses are the eight canonical encodings of small order"
  ) {
    torsionEncodings.foreach { e =>
      assert(jdkAcceptsKey(e), s"JDK accepts ${hex(e)}")
      assert(!StrictEd25519.acceptsKey(e), s"Scarab refuses ${hex(e)}")
      assert(!bcAcceptsKey(e), s"BC refuses ${hex(e)}")
    }
    (negativeZero ++ nonCanonical).foreach { e =>
      assert(!jdkAcceptsKey(e), s"JDK refuses ${hex(e)}")
      assert(!StrictEd25519.acceptsKey(e), s"Scarab refuses ${hex(e)}")
      assert(!bcAcceptsKey(e), s"BC refuses ${hex(e)}")
    }
  }
