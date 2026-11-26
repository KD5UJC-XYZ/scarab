// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.kes.secret

import com.weavechain.curve25519.Scalar
import java.security.MessageDigest
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import org.bouncycastle.crypto.digests.Blake2bDigest
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.kes.LegacySum6KesSigning
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning.Failure
import xyz.kd5ujc.scarab.crypto.secret.{Public, Scratch, Sha512}

/** What the secret half of a KES key holds and erases. Between operations, its array alone, which
  * after every evolution holds no seed of a period the key has left, and which `forget` zeroes;
  * within a generation, a load, an evolution and a signature, every value computed from it (the
  * seeds of the halves and the subtrees built from them, the leaf's expanded key, scalar, nonce and
  * their digits) held by the operation's scratch and zero when it ends, on success and on failure;
  * and a forget or an evolution that races a signature gives a signature of the key as it was or as
  * it became, or a refusal, never one from a key part evolved or part zeroed. Correctness is
  * `Sum6KesSigningDifferentialSuite` 's and the vectors'.
  *
  * The tree's seeds are derived here independently, with Bouncy Castle's Blake2b: the node at depth
  * `d` and index `i` covers the periods `[i 2^d, (i + 1) 2^d)`; the root is the seed, and a node's
  * halves are Blake2b-256 of `1 || seed` and `2 || seed`.
  */
class Sum6KeySuite extends munit.FunSuite:
  // the JDK's SHA-512, which the leaves hash with
  private given Sha512.Digests = Sha512.Digests.found.toOption.get

  private val L = BigInt(2).pow(252) + BigInt("27742317777372353535851937790883648493")

  private val root: Array[Byte] = Array.tabulate(32)(i => (i * 41 + 7).toByte)
  private def generated(): Sum6Key = Sum6Key.generated(root, 0)

  private def blake(parts: Array[Byte]*): Array[Byte] =
    val d = new Blake2bDigest(256)
    parts.foreach(p => d.update(p, 0, p.length))
    val out = new Array[Byte](32)
    d.doFinal(out, 0)
    out

  private def sha512(parts: Array[Byte]*): Array[Byte] =
    val d = MessageDigest.getInstance("SHA-512")
    parts.foreach(d.update)
    d.digest()

  /** Every node's seed: (depth, index) to its 32 bytes. */
  private val seeds: Map[(Int, Int), Array[Byte]] =
    def below(depth: Int, index: Int, seed: Array[Byte]): List[((Int, Int), Array[Byte])] =
      ((depth, index) -> seed) :: (
        if depth == 0 then Nil
        else
          below(depth - 1, 2 * index, blake(Array(1.toByte), seed)) ++
            below(depth - 1, 2 * index + 1, blake(Array(2.toByte), seed))
      )
    below(Sum6Key.Depth, 0, root).toMap

  /** The seeds of the nodes that generate a period before `period`: those whose first period is
    * before it. A key at `period` may hold none of them.
    */
  private def past(period: Int): List[((Int, Int), Array[Byte])] =
    seeds.filter { case ((d, i), _) => (i << d) < period }.toList

  /** The depth-5 key, at its first period, of the top level's right half: the first 512 bytes of
    * the key at period 32, which the top level's evolution generates over its left half.
    */
  private val rightHalf: Array[Byte] =
    val k = LegacySum6KesSigning.generate(Bytes.fromArray(root)).toOption.get
    k.evolveTo(32)
    k.toBytes.toArray.take(512)

  private def holdsWindow(hay: Array[Byte], needle: Array[Byte]): Boolean =
    hay.indices.exists(i =>
      i + needle.length <= hay.length && java.util.Arrays
        .equals(hay, i, i + needle.length, needle, 0, needle.length)
    )

  private def holds(arrays: List[Array[Byte]], value: Array[Byte]): Boolean =
    arrays.exists(java.util.Arrays.equals(_, value))

  private def le(v: BigInt, n: Int): Array[Byte] =
    val big = v.toByteArray.reverse
    Array.tabulate(n)(i => if i < big.length then big(i) else 0.toByte)

  private def fromLe(b: Array[Byte]): BigInt = BigInt(1, b.reverse)

  private def digitsOf(scalar: Array[Byte]): Array[Byte] =
    val e = new Array[Byte](64)
    Scalar.toRadix16(scalar, e)
    e

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

  private def allZero(buffers: List[Array[Byte]], what: String): Unit =
    assert(buffers.nonEmpty, s"$what used no buffer")
    buffers.zipWithIndex.foreach((a, i) =>
      assert(a.forall(_ == 0), s"$what: buffer $i of ${buffers.size} kept its bytes")
    )

  test("a generation's every buffer is zero once it ends, and those buffers held the seeds") {
    val material = new Array[Byte](Sum6Key.KeyBytes)
    val (result, before, after) = observedRun(s => Sum6Key.generatedIn(s, root, 0, material))
    assert(result.isRight, s"$result")
    List((5, 0), (5, 1), (4, 3), (0, 0), (0, 63)).foreach { node =>
      assert(holds(before, seeds(node)), s"the seed of $node was not held by the scratch")
    }
    assert(holds(before, rightHalf), "the right half's tree was not built in the scratch")
    allZero(after, "the generation")
    assertEquals(
      Bytes.fromArray(material),
      LegacySum6KesSigning.generate(Bytes.fromArray(root)).toOption.get.toBytes,
      "the key's array is the replaced key's payload"
    )
    assert(!holdsWindow(material, root), "the key's array holds the root seed")
  }

  test(
    "a signature's every buffer is zero once it is made, and those buffers held the leaf's secrets"
  ) {
    val key = generated()
    assertEquals(key.evolveTo(5), Sum6Key.Evolution.Moved)
    val message = Bytes.fromArray(Array.tabulate(700)(i => (i * 13).toByte))
    val (result, before, after) = observedRun(s => key.signedIn(s, message))
    val signature = result.toOption.flatten.getOrElse(fail(s"no signature: $result")).toArray
    val leaf = seeds((0, 5))
    val az = sha512(leaf)
    val x =
      val c = az.take(32)
      c(0) = (c(0) & 248).toByte
      c(31) = ((c(31) & 127) | 64).toByte
      c
    val prefix = az.drop(32)
    val nonce = sha512(prefix, message.toArray)
    val r = le(fromLe(nonce).mod(L), 32)
    val named = List(
      "the leaf's seed" -> leaf,
      "SHA-512 of the seed" -> az,
      "the clamped scalar" -> x,
      "the nonce prefix" -> prefix,
      "the nonce digest" -> nonce,
      "the nonce" -> r,
      "the scalar's digits" -> digitsOf(x),
      "the nonce's digits" -> digitsOf(r)
    )
    named.foreach((what, value) =>
      assert(holds(before, value), s"$what was not held by the scratch")
    )
    allZero(after, "the signature")
    assertEquals(Right(Bytes.fromArray(signature)), key.sign(message))
  }

  test(
    "an evolution's every buffer is zero once it ends, and those buffers held what it generated"
  ) {
    val key = generated()
    assertEquals(key.evolveTo(31), Sum6Key.Evolution.Moved)
    // into the right half of the top level: the depth-5 tree of its seed is generated over the left
    val (result, before, after) = observedRun(s => key.evolvedIn(s, 32))
    assert(result.isRight, s"$result")
    List((4, 2), (4, 3), (0, 32), (0, 33)).foreach { node =>
      assert(holds(before, seeds(node)), s"the seed of $node was not held by the scratch")
    }
    allZero(after, "the evolution")
    assert(key.storedStartsWith(rightHalf), "the right half's tree")
    assertEquals(key.period, 32)
  }

  test("after every evolution the key's array holds no seed of a period it has left") {
    val key = generated()
    (1 until Sum6Key.Periods).foreach { p =>
      assertEquals(key.evolveTo(p), Sum6Key.Evolution.Moved)
      past(p).foreach((node, seed) =>
        assert(
          !key.storedHolds(seed),
          s"period $p: the seed of $node, which generates an earlier period, is in the key"
        )
      )
      assert(key.storedStartsWith(seeds((0, p))), s"period $p: the leaf")
    }
    // at the last period no seed is left but the last leaf's
    assertEquals(past(Sum6Key.Periods - 1).size, seeds.size - 1)
  }

  test("a load's every buffer is zero once it ends, whether the payload passes or is refused") {
    val payload = generated().toBytes.toArray
    val material = new Array[Byte](Sum6Key.KeyBytes)
    val (loaded, before, after) =
      observedRun(s => Sum6Key.loadedIn(s, payload, 0, None, material))
    assertEquals(loaded, Right(Right(0)))
    // a period-0 load regenerates every right half to check its seed
    List((4, 2), (3, 2), (0, 1), (0, 63)).foreach { node =>
      assert(holds(before, seeds(node)), s"the seed of $node was not held by the scratch")
    }
    assert(holds(before, rightHalf), "the right half's tree was not built in the scratch")
    allZero(after, "the load")
    assert(java.util.Arrays.equals(material, payload), "the key's array is the payload")
    val damaged = payload.clone()
    damaged(520) = (damaged(520) ^ 1).toByte
    val refusedInto = new Array[Byte](Sum6Key.KeyBytes)
    val (refused, _, afterRefusal) =
      observedRun(s => Sum6Key.loadedIn(s, damaged, 0, None, refusedInto))
    assertEquals(refused, Right(Left(Failure.RightSeedMismatch(Sum6Key.Depth))))
    allZero(afterRefusal, "the refused load")
    assert(refusedInto.forall(_ == 0), "a refused payload was copied into the key's array")
  }

  test("forget zeroes the array, and the key then signs and evolves no more") {
    val key = generated()
    key.evolveTo(9)
    assert(!key.storedIsZero)
    key.forget()
    assert(key.storedIsZero, "forget left key material")
    assert(key.isForgotten)
    assertEquals(key.sign(Bytes.empty), Left(Failure.Forgotten))
    assertEquals(key.evolveTo(10), Sum6Key.Evolution.Forgotten)
    assertEquals(key.evolve(), Sum6Key.Evolution.Forgotten)
    assertEquals(key.evolveTo(8), Sum6Key.Evolution.OutOfRange(8))
    assertEquals(key.period, 9)
    val copied = key.copy
    assert(copied.isForgotten && copied.storedIsZero, "the copy of a forgotten key")
  }

  test("sameMaterial is the equality of the 608 bytes") {
    val a = generated()
    val b = generated()
    assert(a.sameMaterial(b))
    b.evolve()
    assert(!a.sameMaterial(b))
    a.evolve()
    assert(a.sameMaterial(b))
    assert(a.copy.sameMaterial(a))
  }

  private def keyAt(p: Int): Sum6Key =
    val k = generated()
    assertEquals(k.evolveTo(p), Sum6Key.Evolution.Moved)
    k

  private final class Injected extends RuntimeException("a failure injected into the build")

  test("an evolution that fails in its build leaves the key wholly at the period before it") {
    val message = Bytes.fromArray(Array.fill(40)(9.toByte))
    // from period 31 into the right half of the top level: the whole left half is erased and
    // generated again, and the build fails just after the erase
    val key = keyAt(31)
    intercept[Injected](
      Scratch.within(s => key.evolvedIn(s, 32, erased = () => throw new Injected))
    )
    assertEquals(key.period, 31)
    assertEquals(key.toBytes, keyAt(31).toBytes, "the key's bytes are not those of period 31")
    assertEquals(key.sign(message), keyAt(31).sign(message), "the signature is not period 31's")
    // a longer evolution that fails at its second step keeps what the first committed
    val longer = keyAt(30)
    var builds = 0
    intercept[Injected](
      Scratch.within(s =>
        longer.evolvedIn(
          s,
          33,
          erased = () =>
            builds += 1
            if builds == 2 then throw new Injected
        )
      )
    )
    assertEquals(longer.period, 31)
    assertEquals(longer.toBytes, keyAt(31).toBytes)
    assertEquals(longer.evolveTo(33), Sum6Key.Evolution.Moved)
    assertEquals(longer.sign(message), keyAt(33).sign(message), "the key does not go on from 31")
  }

  test("a signature's scratch is erased while the signer still holds the key's monitor") {
    val key = generated()
    var heldAtRelease: Option[Boolean] = None
    var secretsAtRelease = false
    val signed = key.signing(Bytes.empty) { body =>
      Scratch.observed[Option[Bytes]] { arrays =>
        heldAtRelease = Some(Thread.holdsLock(key))
        secretsAtRelease = arrays.exists(_.exists(_ != 0))
      }(body)
    }
    assertEquals(signed, generated().sign(Bytes.empty))
    assert(secretsAtRelease, "the scratch held nothing when it was released")
    assertEquals(heldAtRelease, Some(true), "the scratch was erased after the monitor was released")
  }

  // A forget, an evolution, a copy and a read of the key all take its monitor: one that holds it
  // while it computes excludes them for that time, by construction. Seen here where it computes.
  test("a signature and an evolution hold the key's monitor while they compute") {
    val key = generated()
    var signing: Option[Boolean] = None
    Scratch.within(s => key.signedIn(s, Bytes.empty, () => signing = Some(Thread.holdsLock(key))))
    assertEquals(signing, Some(true), "the signature was computed without the key's monitor")
    var evolving: List[Boolean] = Nil
    Scratch.within(s => key.evolvedIn(s, 3, () => evolving = Thread.holdsLock(key) :: evolving))
    assertEquals(
      evolving,
      List(true, true, true),
      "an evolution step ran without the key's monitor"
    )
    assertEquals(key.period, 3)
  }

  /** Signatures from `rounds` races of four signers against `race`, started together. */
  private def raced(rounds: Int)(make: () => Sum6Key)(race: Sum6Key => Unit)(
      message: Bytes
  ): Vector[Either[Failure, Bytes]] =
    val pool = Executors.newFixedThreadPool(5)
    given ExecutionContext = ExecutionContext.fromExecutor(pool)
    try
      (1 to rounds).toVector.flatMap { _ =>
        val key = make()
        val start = new CountDownLatch(1)
        val signers = (1 to 4).map(_ =>
          Future {
            start.await(10, TimeUnit.SECONDS)
            (1 to 3).map(_ => key.sign(message))
          }
        )
        val racing = Future {
          start.await(10, TimeUnit.SECONDS)
          race(key)
        }
        start.countDown()
        val signatures = Await.result(Future.sequence(signers), 60.seconds).flatten
        Await.result(racing, 60.seconds)
        signatures
      }
    finally pool.shutdownNow()

  test("a forget that races signing gives the key's signature or a refusal, never another") {
    val message = Bytes.fromArray(Array.fill(40)(3.toByte))
    val expected = generated().sign(message)
    val results = raced(30)(() => generated())(_.forget())(message)
    results.foreach(r =>
      assert(
        r == expected || r == Left(Failure.Forgotten),
        s"$r is neither the key's nor a refusal"
      )
    )
  }

  test("an evolution that races signing gives the signature before it or after it, never another") {
    val message = Bytes.fromArray(Array.fill(40)(5.toByte))
    val after =
      val k = generated()
      k.evolveTo(32)
      k.sign(message)
    // from period 31 into the right half: the whole left half is zeroed and regenerated
    val results = raced(30) { () =>
      val k = generated()
      k.evolveTo(31)
      k
    }(_.evolveTo(32))(message)
    val at31 =
      val k = generated()
      k.evolveTo(31)
      k.sign(message)
    results.foreach(r =>
      assert(r == at31 || r == after, s"$r is the signature of neither period 31 nor 32")
    )
    assertNotEquals(at31, after)
  }

  test("a buffer of the key cannot be the result of a scratch") {
    assertEquals(compileErrors("Scratch.within(s => generated().signedIn(s, Bytes.empty))"), "")
    assertNotEquals(compileErrors("Scratch.within(s => generated().payloadIn(s))"), "")
    assertNotEquals(compileErrors("Scratch.within(s => s.array(generated().payloadIn(s)))"), "")
    assertNotEquals(compileErrors("Scratch.within(s => generated())"), "")
  }
