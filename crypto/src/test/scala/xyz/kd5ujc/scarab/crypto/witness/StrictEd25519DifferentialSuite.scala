// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.witness

import com.weavechain.curve25519.{
  CompressedEdwardsY,
  EdwardsPoint,
  InvalidEncodingException,
  Scalar
}
import java.security.MessageDigest
import java.util.Arrays
import org.bouncycastle.math.ec.rfc8032.Ed25519
import scala.util.Random
import xyz.kd5ujc.scarab.codec.Bytes

/** The verifier as it was before issue 147, kept as the oracle for the cheaper one: it decided that
  * an encoding is canonical by compressing the decompressed point and comparing the bytes, and that
  * the result is R by compressing it. The present one tests `y < p` on the bytes and compares the
  * points. The verdicts must be the same on every input.
  */
class StrictEd25519DifferentialSuite extends munit.FunSuite:
  import WitnessTestData.*

  private object Reference:
    def point(bytes: Array[Byte]): Option[EdwardsPoint] =
      val decoded =
        try Some(new CompressedEdwardsY(bytes.clone()).decompress())
        catch case _: InvalidEncodingException => None
      decoded.filter(p => Arrays.equals(p.compress().toByteArray, bytes) && !p.isSmallOrder)

    def verify(key: Array[Byte], signature: Array[Byte], message: Array[Byte]): Boolean =
      val rBytes = Arrays.copyOfRange(signature, 0, 32)
      val sBytes = Arrays.copyOfRange(signature, 32, 64)
      val s =
        try Some(Scalar.fromCanonicalBytes(sBytes))
        catch case _: IllegalArgumentException => None
      (for
        a <- point(key)
        _ <- point(rBytes)
        sc <- s
      yield
        val digest = MessageDigest.getInstance("SHA-512")
        digest.update(rBytes)
        digest.update(key)
        digest.update(message)
        val h = Scalar.fromBytesModOrderWide(digest.digest())
        val check = EdwardsPoint.vartimeDoubleScalarMultiplyBasepoint(h, a.negate(), sc)
        Arrays.equals(check.compress().toByteArray, rBytes)
      ).contains(true)

  private def verdict(key: Array[Byte], signature: Array[Byte], message: Array[Byte]): Boolean =
    StrictEd25519.verify(
      Bytes.fromArray(key),
      Bytes.fromArray(signature),
      Bytes.fromArray(message)
    ) == Right(VerificationResult.SignatureVerified)

  private def le(value: BigInt): Array[Byte] =
    Array.tabulate(32)(i => ((value >> (8 * i)) & 255).toByte)

  private val p = (BigInt(1) << 255) - 19

  /** Encodings that sit on the edges: y near 0, 1, p - 1, p and 2^255 - 1, both signs, the
    * blacklist, and the small-order points.
    */
  private val edges: Vector[Array[Byte]] =
    val ys = (BigInt(0) until BigInt(40)).toVector ++
      (BigInt(-20) until BigInt(20)).toVector.map(d => p + d) ++
      Vector(BigInt(2).pow(255) - 1, BigInt(2).pow(255) - 2, BigInt(2).pow(254))
    val named = Vector(
      "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
      "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
      "ec" + "ff" * 30 + "7f",
      "ed" + "ff" * 30 + "7f"
    ).map(h => hex(h).toArray)
    (ys.filter(y => y >= 0 && y < (BigInt(1) << 255)).map(le) ++ named).flatMap { b =>
      val flipped = b.clone()
      flipped(31) = (flipped(31) ^ 0x80).toByte
      Vector(b, flipped)
    }

  test("an encoding is accepted as a point exactly when it was before: edges") {
    edges.foreach { e =>
      assertEquals(
        StrictEd25519.point(e).isDefined,
        Reference.point(e).isDefined,
        Bytes.fromArray(e).hex
      )
    }
    // the two sides of the boundary, by name
    assert(StrictEd25519.canonicalY(le(p - 1)))
    assert(!StrictEd25519.canonicalY(le(p)))
    assert(!StrictEd25519.canonicalY(le(p + 18)))
    assert(!StrictEd25519.canonicalY(le(BigInt(2).pow(255) - 1)))
    assert(StrictEd25519.canonicalY(le(BigInt(0))))
  }

  test("an encoding is accepted as a point exactly when it was before: random bytes") {
    val random = new Random(147)
    (0 until 100000).foreach { _ =>
      val e = new Array[Byte](32)
      random.nextBytes(e)
      assertEquals(StrictEd25519.point(e).isDefined, Reference.point(e).isDefined)
    }
  }

  test("an encoding is accepted as a point exactly when it was before: points of the curve") {
    val random = new Random(148)
    var onCurve = 0
    (0 until 3000).foreach { _ =>
      val sk = new Array[Byte](32)
      random.nextBytes(sk)
      val pk = new Array[Byte](32)
      Ed25519.generatePublicKey(sk, 0, pk, 0)
      // the public key, the same with the opposite sign of x, and a multiple that has torsion added
      // by the sign flip (x -> -x is another point of the curve)
      val flipped = pk.clone()
      flipped(31) = (flipped(31) ^ 0x80).toByte
      Vector(pk, flipped).foreach { e =>
        val expected = Reference.point(e).isDefined
        if expected then onCurve += 1
        assertEquals(StrictEd25519.point(e).isDefined, expected)
      }
    }
    assert(onCurve > 5000, s"only $onCurve encodings were points")
  }

  test("all 2207 fixed-size published inputs preserve the prior Scala verifier's verdict") {
    val all = rows("vectors.tsv")
    assertEquals(all.size, 2219)
    val (fixed, malformed) = all.partition(f => hex(f(1)).size == 32 && hex(f(2)).size == 64)
    assertEquals(fixed.size, 2207)
    assertEquals(malformed.map(_(0)).toSet, (30 to 41).map(i => s"wycheproof-$i").toSet)
    // The old verifier has only a fixed-size ABI; WitnessGoldenSuite replays all raw inputs.
    fixed.foreach { f =>
      assertEquals(
        verdict(hex(f(1)).toArray, hex(f(2)).toArray, hex(f(3)).toArray),
        Reference.verify(hex(f(1)).toArray, hex(f(2)).toArray, hex(f(3)).toArray),
        f(0)
      )
    }
  }

  test("signatures, mutated and spliced, get the verdict they got before") {
    val random = new Random(149)
    var accepted = 0
    var rejected = 0
    (0 until 1500).foreach { n =>
      val sk = new Array[Byte](32)
      random.nextBytes(sk)
      val pk = new Array[Byte](32)
      Ed25519.generatePublicKey(sk, 0, pk, 0)
      val message = new Array[Byte](random.nextInt(300))
      random.nextBytes(message)
      val sig = new Array[Byte](64)
      Ed25519.sign(sk, 0, pk, 0, message, 0, message.length, sig, 0)
      val cases = Vector.newBuilder[(Array[Byte], Array[Byte], Array[Byte])]
      cases += ((pk, sig, message))
      // one bit flipped anywhere in key, signature or message
      def flipBit(a: Array[Byte], i: Int, bit: Int): Array[Byte] =
        val c = a.clone()
        c(i) = (c(i) ^ (1 << bit)).toByte
        c
      val k2 = flipBit(pk, random.nextInt(32), random.nextInt(8))
      val s2 = flipBit(sig, random.nextInt(64), random.nextInt(8))
      val m2 =
        if message.isEmpty then message
        else flipBit(message, random.nextInt(message.length), random.nextInt(8))
      cases += ((k2, sig, message)); cases += ((pk, s2, message)); cases += ((pk, sig, m2))
      // an edge encoding as the key or as R, the rest as it was
      val edge = edges(random.nextInt(edges.size))
      cases += ((edge, sig, message))
      cases += ((pk, edge ++ sig.drop(32), message))
      // the sign of x of R or of the key flipped (the point, with the opposite sign, is another)
      val k3 = flipBit(pk, 31, 7)
      val s3 = flipBit(sig, 31, 7)
      cases += ((k3, sig, message)); cases += ((pk, s3, message))
      cases.result().foreach { (k, s, m) =>
        val expected = Reference.verify(k, s, m)
        if expected then accepted += 1 else rejected += 1
        assertEquals(verdict(k, s, m), expected, s"case $n")
      }
    }
    assert(accepted >= 1500 && rejected > 5000, s"accepted $accepted rejected $rejected")
  }
