// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.secret

import java.security.{MessageDigest, MessageDigestSpi, NoSuchAlgorithmException, Provider}
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

  private def jdk(pieces: Array[Byte]*): Vector[Byte] =
    val d = MessageDigest.getInstance("SHA-512")
    pieces.foreach(d.update)
    d.digest().toVector

  test("the JDK's SHA-512 digest is found, and a lookup the JDK refuses is its typed refusal") {
    val found = Sha512.Digests.found
    assert(found.isRight, s"no SHA-512 in this JDK: $found")
    val out = new Array[Byte](64)
    found.foreach { case given Sha512.Digests => Sha512.into(out, seed) }
    assertEquals(out.toVector, jdk(seed))
    val refusal = new NoSuchAlgorithmException("no SHA-512 here")
    assertEquals(Sha512.Digests.foundWith(throw refusal), Left(refusal))
  }

  /** A SHA-512 over the JDK's, as a provider's implementation: cloneable, as the JDK's is. */
  private final class Spi(inner: MessageDigest) extends MessageDigestSpi with Cloneable:
    override protected def engineUpdate(b: Byte): Unit = inner.update(b)
    override protected def engineUpdate(in: Array[Byte], off: Int, len: Int): Unit =
      inner.update(in, off, len)
    override protected def engineDigest(): Array[Byte] = inner.digest()
    override protected def engineReset(): Unit = inner.reset()
    override def clone(): AnyRef = new Spi(inner.clone().asInstanceOf[MessageDigest])

  /** A provider that supplies one SHA-512 digest and refuses every later lookup. */
  private final class SuppliedOnce extends Provider("ScarabSuppliedOnce", "1", "SHA-512, once"):
    private val made = new AtomicInteger(0)
    putService(
      new Provider.Service(this, "MessageDigest", "SHA-512", classOf[Spi].getName, null, null):
        override def newInstance(constructorParameter: AnyRef): AnyRef =
          if made.incrementAndGet() > 1 then throw new NoSuchAlgorithmException("supplied once")
          else new Spi(MessageDigest.getInstance("SHA-512"))
    )

  test("every hash copies the digest found, so a provider that fails after it reaches no hash") {
    val once = new SuppliedOnce
    val found = Sha512.Digests.foundWith(MessageDigest.getInstance("SHA-512", once))
    assert(found.isRight, s"the provider's one digest was refused: $found")
    // a second lookup, as each hash made before, now fails
    intercept[NoSuchAlgorithmException](MessageDigest.getInstance("SHA-512", once))
    found.foreach { case given Sha512.Digests =>
      (1 to 3).foreach { i =>
        val out = new Array[Byte](64)
        Sha512.into(out, seed, Array(i.toByte))
        assertEquals(out.toVector, jdk(seed, Array(i.toByte)), s"hash $i")
      }
    }
  }

  test("a digest that cannot be copied is refused where it is found") {
    val refused = Sha512.Digests.foundWith(new Recording)
    assert(
      refused.swap.exists(_.isInstanceOf[CloneNotSupportedException]),
      s"a digest without clone was taken: $refused"
    )
  }
