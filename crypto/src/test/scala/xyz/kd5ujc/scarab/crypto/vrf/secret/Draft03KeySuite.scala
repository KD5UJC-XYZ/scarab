// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.vrf.secret

import com.weavechain.curve25519.{Constants, Scalar}
import java.security.MessageDigest
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.secret.{Public, Scratch}
import xyz.kd5ujc.scarab.crypto.vrf.Draft03Prover.Failure

/** What the secret half of a VRF key holds and erases: the seed alone between operations, zeroed by
  * `forget`; within an operation, every value computed from the seed (the expanded key, the scalar
  * and its digits, the nonce digest, the nonce and its digits, the encodings of Gamma, U and V)
  * held by the operation's scratch and zero when it ends, on success and on failure; a forget after
  * a proof has copied the seed leaves that proof the key's, and a proof after a forget refuses; and
  * the digest of every hash is reset on the way out. The proof's correctness is the suites of the
  * `vrf` package's (vectors, the replaced prover, the strict verifier); here H is any point of the
  * prime-order subgroup.
  */
class Draft03KeySuite extends munit.FunSuite:
  private val L = BigInt(2).pow(252) + BigInt("27742317777372353535851937790883648493")

  private def le(v: BigInt, n: Int): Array[Byte] =
    val big = v.toByteArray.reverse
    Array.tabulate(n)(i => if i < big.length then big(i) else 0.toByte)

  private def fromLe(b: Array[Byte]): BigInt = BigInt(1, b.reverse)

  private def sha512(pieces: Array[Byte]*): Array[Byte] =
    val d = MessageDigest.getInstance("SHA-512")
    pieces.foreach(d.update)
    d.digest()

  private val seed = Array.tabulate(32)(i => (i * 29 + 3).toByte)

  private def key(): Draft03Key = Draft03Key.fromSeed(Bytes.fromArray(seed)).toOption.get

  /** H for these suites: [7]B, a point of the prime-order subgroup, as encoded. */
  private val h: Bytes =
    Bytes.fromArray(
      Constants.ED25519_BASEPOINT_TABLE.multiply(Scalar.fromBits(le(7, 32))).compress().toByteArray
    )

  private def digitsOf(scalar: Array[Byte]): Array[Byte] =
    val e = new Array[Byte](64)
    Scalar.toRadix16(scalar, e)
    e

  /** The secret values an operation computes from the seed, computed here independently. */
  private object expected:
    val az: Array[Byte] = sha512(seed)
    val x: Array[Byte] =
      val c = az.take(32)
      c(0) = (c(0) & 248).toByte
      c(31) = ((c(31) & 127) | 64).toByte
      c
    val nonceKey: Array[Byte] = az.drop(32)
    val nonce: Array[Byte] = sha512(nonceKey, h.toArray)
    val k: Array[Byte] = le(fromLe(nonce).mod(L), 32)

  /** The buffers of one operation run in a scratch that reports them: their bytes just before the
    * erasure, and the buffers after it.
    */
  private def observedRun[A: Public](
      body: Scratch => A
  ): (Either[Throwable, A], List[Array[Byte]], List[Array[Byte]]) =
    var before: List[Array[Byte]] = Nil
    var buffers: List[Array[Byte]] = Nil
    val result = scala.util
      .Try(Scratch.observed[A] { arrays =>
        before = arrays.map(_.clone())
        buffers = arrays
      }(body))
      .toEither
    (result, before, buffers)

  private def holds(arrays: List[Array[Byte]], value: Array[Byte]): Boolean =
    arrays.exists(java.util.Arrays.equals(_, value))

  test(
    "a proof's every buffer is zero once it is proved, and those buffers held each secret value"
  ) {
    val k0 = key()
    val (result, before, after) = observedRun(s => k0.provingIn(s, h))
    assert(result.exists(_.isDefined), s"no proof: $result")
    val named = List(
      "the seed" -> seed,
      "SHA-512 of the seed" -> expected.az,
      "the clamped scalar" -> expected.x,
      "the nonce key" -> expected.nonceKey,
      "the scalar's digits" -> digitsOf(expected.x),
      "the nonce digest" -> expected.nonce,
      "the nonce" -> expected.k,
      "the nonce's digits" -> digitsOf(expected.k)
    )
    named.foreach((what, value) =>
      assert(holds(before, value), s"$what was not held by the scratch")
    )
    assert(after.nonEmpty)
    after.zipWithIndex.foreach((a, i) =>
      assert(a.forall(_ == 0), s"buffer $i of ${after.size} kept its bytes")
    )
  }

  test("an output's every buffer is zero once it is computed") {
    val k0 = key()
    val (result, before, after) = observedRun(s => k0.outputIn(s, h))
    assert(result.exists(_.isDefined), s"no output: $result")
    assert(holds(before, expected.x), "the clamped scalar was not held by the scratch")
    assert(holds(before, digitsOf(expected.x)), "the scalar's digits were not held by the scratch")
    assert(after.forall(_.forall(_ == 0)), "a buffer kept its bytes")
  }

  test("an operation that fails part way leaves its buffers zero") {
    val k0 = key()
    // 32 bytes that are not an encoding of a point (y = 2 has no x on the curve), after the key
    // has been expanded
    val undecodable = Bytes.fromArray(le(2, 32))
    val (result, before, after) = observedRun(s => k0.provingIn(s, undecodable))
    assert(result.isLeft, s"an undecodable H gave $result")
    assert(holds(before, expected.x), "the failure came before the key was expanded")
    assert(after.forall(_.forall(_ == 0)), "a buffer kept its bytes after the failure")
    assert(k0.prove(undecodable) match
      case Left(Failure.InternalFailure(_)) => true
      case _                                => false)
  }

  test("forget zeroes the seed, and the key then refuses") {
    val k0 = key()
    val publicKey = k0.publicKey
    assert(!k0.storedSeedIsZero)
    k0.forget()
    assert(k0.storedSeedIsZero, "forget left the seed")
    assert(k0.isForgotten)
    assertEquals(k0.prove(h), Left(Failure.MissingSigningKey))
    assertEquals(k0.output(h), Left(Failure.MissingSigningKey))
    assertEquals(k0.toBytes, Bytes.fromArray(new Array[Byte](32)) ++ publicKey)
    assert(!k0.sameKey(key()) && !key().sameKey(k0), "a forgotten key is the same key as another")
  }

  test("sameKey is the seed's equality") {
    assert(key().sameKey(key()))
    val other =
      Draft03Key.fromSeed(Bytes.fromArray(seed.updated(31, (seed(31) ^ 1).toByte))).toOption.get
    assert(!key().sameKey(other))
  }

  test("a proof that copied the seed before a forget completes with the key's proof") {
    val expectedProof = key().prove(h).toOption.get
    val k0 = key()
    val copied = new CountDownLatch(1)
    val released = new CountDownLatch(1)
    val parkedFor = new AtomicBoolean(false)
    val pool = Executors.newSingleThreadExecutor()
    try
      val proving = pool.submit { () =>
        Scratch.within(s =>
          k0.provingIn(
            s,
            h,
            () =>
              copied.countDown()
              parkedFor.set(released.await(30, TimeUnit.SECONDS))
          )
        )
      }
      val parked = copied.await(30, TimeUnit.SECONDS)
      // the proof holds its copy of the seed and not the monitor, so forget runs now, while it waits
      k0.forget()
      val erased = k0.storedSeedIsZero
      released.countDown()
      val outcome = proving.get(30, TimeUnit.SECONDS)
      assert(parked, "the proof did not reach the point after its copy of the seed")
      assert(erased, "forget did not zero the seed while the proof was parked")
      assert(parkedFor.get(), "the proof was not released by the suite")
      assertEquals(outcome, Some(expectedProof), "the parked proof is not the key's")
      assertEquals(k0.prove(h), Left(Failure.MissingSigningKey), "a proof after the forget")
    finally
      released.countDown()
      pool.shutdown()
      val joined = pool.awaitTermination(30, TimeUnit.SECONDS)
      assert(joined, "the proving thread did not finish")
  }

  test("a proof that begins after a forget refuses before it copies anything") {
    val k0 = key()
    k0.forget()
    val copies = new AtomicInteger(0)
    val outcome = Scratch.within(s => k0.provingIn(s, h, () => copies.incrementAndGet(): Unit))
    assertEquals(outcome, None)
    assertEquals(copies.get(), 0, "the proof copied a seed after the forget")
  }

  /** A SHA-512 digest over the JDK's that counts its resets. */
  private final class Recording extends MessageDigest("SHA-512"):
    private val inner = MessageDigest.getInstance("SHA-512")
    val resets = new AtomicInteger(0)
    override protected def engineUpdate(b: Byte): Unit = inner.update(b)
    override protected def engineUpdate(in: Array[Byte], off: Int, len: Int): Unit =
      inner.update(in, off, len)
    override protected def engineDigest(): Array[Byte] = inner.digest()
    override protected def engineDigest(buf: Array[Byte], off: Int, len: Int): Int =
      inner.digest(buf, off, len)
    override protected def engineReset(): Unit =
      resets.incrementAndGet()
      inner.reset()

  // The JDK provider's own buffer, state words and schedule are private fields of
  // sun.security.provider.DigestBase and SHA5, which java.base does not open: a suite can read
  // them only with --add-opens. What it can see is that the digest is reset on every way out.
  test("the digest of a hash is reset once it returns and once it throws") {
    val returned = new Recording
    val out = new Array[Byte](64)
    Draft03Key.sha512With(returned)(out, seed)
    assertEquals(out.toVector, sha512(seed).toVector)
    assertEquals(returned.resets.get(), 1, "resets after a hash that returned")
    val failed = new Recording
    intercept[java.security.DigestException](
      Draft03Key.sha512With(failed)(new Array[Byte](63), seed)
    )
    assertEquals(failed.resets.get(), 1, "resets after a hash that threw")
  }

  test("the public key is [x]B of the clamped scalar") {
    assertEquals(
      key().publicKey,
      Bytes.fromArray(
        Constants.ED25519_BASEPOINT_TABLE
          .multiply(Scalar.fromBytesModOrder(expected.x))
          .compress()
          .toByteArray
      )
    )
  }
