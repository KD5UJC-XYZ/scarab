// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.secret

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** The SHA-512 of the secret path is the JDK's, and its digest is reset on every way out. */
class Sha512Suite extends munit.FunSuite:
  private val seed = Array.tabulate(32)(i => (i * 29 + 3).toByte)

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
    Sha512.intoWith(returned)(out, seed.take(10), seed.drop(10))
    assertEquals(out.toVector, MessageDigest.getInstance("SHA-512").digest(seed).toVector)
    assertEquals(returned.resets.get(), 1, "resets after a hash that returned")
    val failed = new Recording
    intercept[java.security.DigestException](Sha512.intoWith(failed)(new Array[Byte](63), seed))
    assertEquals(failed.resets.get(), 1, "resets after a hash that threw")
  }

  test("the JDK's SHA-512 provider is found, and a lookup the JDK refuses is its typed refusal") {
    val provider = Sha512.Provider.found
    assert(provider.isRight, s"no SHA-512 in this JDK: $provider")
    val out = new Array[Byte](64)
    provider.foreach { case given Sha512.Provider => Sha512.into(out, seed) }
    assertEquals(out.toVector, MessageDigest.getInstance("SHA-512").digest(seed).toVector)
    val refusal = new java.security.NoSuchAlgorithmException("no SHA-512 here")
    assertEquals(Sha512.Provider.foundWith(throw refusal), Left(refusal))
  }
