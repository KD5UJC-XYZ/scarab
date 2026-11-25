// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.vrf.secret

import com.weavechain.curve25519.{Constants, Scalar}
import java.security.MessageDigest
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.vrf.Draft03Prover.Failure

/** What the secret half of a VRF key holds and erases: the seed alone between operations, zeroed by
  * `forget`; within an operation, every value computed from the seed (the expanded key, the scalar
  * and its digits, the nonce digest, the nonce and its digits, the encodings of Gamma, U and V)
  * held by the operation's scratch and zero when it ends, on success and on failure; and a forget
  * that races an operation gives the key's proof or a refusal, never a proof from a partly zeroed
  * seed. The proof's correctness is the suites of the `vrf` package's (vectors, the replaced
  * prover, the strict verifier); here H is any point of the prime-order subgroup.
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
    assert(!k0.storage.forall(_ == 0))
    k0.forget()
    assert(k0.storage.forall(_ == 0), "forget left the seed")
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

  test("a forget that races proving gives the key's proof or a refusal, never another proof") {
    val proof = key().prove(h).toOption.get
    val pool = Executors.newFixedThreadPool(5)
    given ExecutionContext = ExecutionContext.fromExecutor(pool)
    try
      (1 to 40).foreach { round =>
        val k0 = key()
        val start = new CountDownLatch(1)
        val provers = (1 to 4).map(_ =>
          Future {
            start.await(10, TimeUnit.SECONDS)
            (1 to 3).map(_ => k0.prove(h))
          }
        )
        val forgetting = Future {
          start.await(10, TimeUnit.SECONDS)
          k0.forget()
        }
        start.countDown()
        val results = Await.result(Future.sequence(provers), 60.seconds).flatten
        Await.result(forgetting, 60.seconds)
        results.foreach { r =>
          assert(
            r == Right(proof) || r == Left(Failure.MissingSigningKey),
            s"round $round: $r is neither the key's proof nor a refusal"
          )
        }
      }
    finally pool.shutdownNow()
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
